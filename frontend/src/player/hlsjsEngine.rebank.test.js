import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FakeHls } from '../test/fakeHls.js';
import { RECONNECT_MS, startHlsJs } from './hlsjsEngine.js';
import { HEALTHY_MS } from './stepGate.js';

beforeEach(() => {
  FakeHls.reset();
  vi.useFakeTimers();
});
afterEach(() => vi.useRealTimers());

// The re-bank (rebank.js) as the engine runs it, on a fake hls.js: a ladder of four rungs whose ids are labels,
// so the floor hold never applies (it needs a 240p bottom and a 360p rung), and a ladder with real ids for the
// tests that put the hold and the re-bank together.
const rung = (id, height, bitrate) => ({ id, height, bitrate, copy: false });
const plain = [rung('a', 200, 400_000), rung('b', 300, 1_000_000), rung('c', 400, 1_500_000)];
const plainWith720 = [...plain, rung('d', 720, 3_000_000)];
const held = [
  rung('240p', 240, 364_000),
  rung('360p', 360, 816_000),
  rung('480p', 480, 1_328_000),
  rung('720p', 720, 3_128_000),
];
const levelsOf = (rungs) => rungs.map(({ height, bitrate }) => ({ height, bitrate }));

// One fragment loaded at `kbps` from its first byte.
const stats = (kbps) => ({
  loaded: kbps * 125,
  retry: 0,
  loading: { start: 0, first: 0, end: 1000 },
});
const FAST = 20_000;
// The flush the re-bank asks for in these worlds (the first fragment 300 s ahead to the end), as it finishes.
const OWN_FLUSH = { start: 300, end: Infinity };

// A <video> whose playhead and buffered range the test sets.
function makeVideo() {
  const video = document.createElement('video');
  const state = { ahead: 0, time: 0, ranges: null };
  Object.defineProperty(video, 'currentTime', { get: () => state.time });
  Object.defineProperty(video, 'buffered', {
    get: () => {
      const ranges =
        state.ranges ?? (state.ahead > 0 ? [[state.time, state.time + state.ahead]] : []);
      return { length: ranges.length, start: (i) => ranges[i][0], end: (i) => ranges[i][1] };
    },
  });
  return { video, state };
}

function world(rungs, { auto = true } = {}) {
  const { video, state } = makeVideo();
  let t = 0;
  const handlers = {
    onLevel: vi.fn(),
    onThroughput: vi.fn(),
    onReconnecting: vi.fn(),
    onSessionLost: vi.fn(),
    onFatal: vi.fn(),
    onRebank: vi.fn(),
  };
  const engine = startHlsJs({
    Hls: FakeHls,
    video,
    session: { masterUrl: '/m.m3u8', renditions: rungs },
    auto,
    startSeconds: 0,
    handlers,
    now: () => t,
  });
  const hls = FakeHls.last;
  hls.manifest(levelsOf(rungs), 0);
  const tick = () => video.dispatchEvent(new Event('timeupdate'));
  const env = {
    engine,
    handlers,
    video,
    hls,
    tick,
    now: () => t,
    advance: (ms) => (t += ms),
    setAhead: (s) => (state.ahead = s),
    setTime: (s) => (state.time = s),
    setRanges: (r) => (state.ranges = r),
    // The flush is the last thing the engine asks of hls.js: the floor at that moment is kept.
    flushes: [],
  };
  hls.trigger.mockImplementation((event, data) => {
    env.flushes.push({ event, data, floor: hls.config.minAutoBitrate });
  });
  return env;
}

// Ten-second fragments from 0 to 480, banked at `level` one after the other, the buffer growing with them;
// it is left at 460 s ahead, and the caller sets it to what the test needs.
function bank(env, level) {
  for (let s = 0; s < 480; s += 10) {
    env.setAhead(Math.min(s + 10, 460)); // short of 470 s until the test says the bank is full
    env.hls.fragBuffered({ sn: s / 10, start: s, duration: 10, level, type: 'main' });
  }
}

// Auto banked at `level` with the cap one above it and the link fast: the bank is full and quiet. hls.js has
// stepped up by itself and is fetching the new rung (LEVEL_SWITCHING), which is when a re-bank may start;
// `stepped: false` leaves it on the old rung.
function ready(env, level, { stepped = true } = {}) {
  env.hls.manifest(env.hls.levels, level);
  env.hls.fetchLevel(level);
  for (let i = 0; i < 3; i += 1) env.hls.fragLoaded(stats(FAST));
  env.advance(HEALTHY_MS);
  env.hls.fragLoaded(stats(FAST)); // the step gate climbs one rung
  bank(env, level);
  env.setAhead(480);
  if (stepped) env.hls.fetchLevel(level + 1);
}

// Back to a state `ready` left, after an undo: fast rates, the cap climbed again, a full bank.
function recover(env) {
  env.setAhead(480);
  for (let i = 0; i < 3; i += 1) env.hls.fragLoaded(stats(FAST));
  for (let i = 0; i < 2; i += 1) {
    env.advance(HEALTHY_MS);
    env.hls.fragLoaded(stats(FAST));
  }
}

const trouble = (hls) =>
  hls.error({ fatal: false, type: 'networkError', details: 'fragLoadTimeOut' });

describe('re-banking one rung up', () => {
  it('raises the floor, then flushes from the first fragment 300 s ahead to the end', () => {
    const env = world(plain);
    ready(env, 0);
    expect(env.hls.autoLevelCapping).toBe(1);
    env.tick();
    expect(env.flushes).toEqual([
      {
        event: FakeHls.Events.BUFFER_FLUSHING,
        data: { startOffset: 300, endOffset: Infinity, type: null },
        floor: 1_000_000, // the floor was already up when the flush was asked for
      },
    ]);
    expect(env.hls.config.minAutoBitrate).toBe(1_000_000);
  });

  it('starts the flush at a fragment start after the playhead plus 300 s, not mid-fragment', () => {
    const env = world(plain);
    ready(env, 0);
    env.setTime(5.5);
    env.tick();
    expect(env.flushes[0].data.startOffset).toBe(310);
  });

  it.each([
    [469, 0],
    [470, 1],
  ])('needs a bank of 470 s: %i s ahead flushes %i time(s)', (ahead, flushes) => {
    const env = world(plain);
    ready(env, 0);
    env.setAhead(ahead);
    env.tick();
    expect(env.flushes).toHaveLength(flushes);
  });

  it('asks again on every tick but flushes once: the floor is up until the bank is back', () => {
    const env = world(plain);
    ready(env, 0);
    env.tick();
    env.tick();
    expect(env.flushes).toHaveLength(1);
  });

  it('never goes above 480p: banked at the 480p level there is nothing to do', () => {
    const env = world(plainWith720);
    ready(env, 2); // hls.js is fetching level 3, the next rung: only the 480p limit can refuse
    for (let i = 0; i < 3; i += 1) env.hls.fragLoaded(stats(FAST));
    env.tick();
    expect(env.hls.autoLevelCapping).toBeGreaterThanOrEqual(3);
    expect(env.flushes).toHaveLength(0);
  });

  it('does nothing while the cap is not above the banked rung', () => {
    const env = world(plain);
    env.hls.manifest(env.hls.levels, 0);
    env.hls.fetchLevel(0);
    for (let i = 0; i < 3; i += 1) env.hls.fragLoaded(stats(FAST)); // no healthy minute yet: the cap is level 0
    bank(env, 0);
    env.setAhead(480);
    env.hls.fetchLevel(1); // hls.js is on the next rung (LEVEL_SWITCHING only ever lowers the cap, so it stays 0)
    expect(env.hls.autoLevelCapping).toBe(0);
    env.tick();
    expect(env.flushes).toHaveLength(0);
  });

  it('does nothing when the link has not shown 3 x the next rung', () => {
    const env = world(plain);
    env.hls.manifest(env.hls.levels, 0);
    env.hls.fetchLevel(0);
    for (let i = 0; i < 3; i += 1) env.hls.fragLoaded(stats(2999)); // 3 x 1,000 kbit/s is 3,000
    env.advance(HEALTHY_MS);
    env.hls.fragLoaded(stats(2999));
    bank(env, 0);
    env.setAhead(480);
    env.hls.fetchLevel(1); // hls.js has stepped up, the cap allows it: only the headroom can refuse
    expect(env.hls.autoLevelCapping).toBe(1);
    env.tick();
    expect(env.flushes).toHaveLength(0);
  });

  describe('the fragments', () => {
    it('says no when a fragment past the start is at another rung', () => {
      const env = world(plain);
      ready(env, 0);
      env.hls.fragBuffered({ sn: 40, start: 400, duration: 10, level: 1, type: 'main' });
      env.tick();
      expect(env.flushes).toHaveLength(0);
    });

    it('ignores init segments and other tracks', () => {
      const env = world(plain);
      ready(env, 0);
      env.hls.fragBuffered({ sn: 'initSegment', start: 400, duration: 10, level: 1 });
      env.hls.fragBuffered({ sn: 41, start: 410, duration: 10, level: 1, type: 'audio' });
      env.tick();
      expect(env.flushes).toHaveLength(1);
    });

    it('drops an entry that is no longer in the buffer before it decides', () => {
      const env = world(plain);
      ready(env, 0);
      env.hls.fragBuffered({ sn: 60, start: 600, duration: 10, level: 1, type: 'main' }); // not in 0-480
      env.tick();
      expect(env.flushes).toHaveLength(1);
    });

    it('forgets the fragments a flush removed', () => {
      const env = world(plain);
      ready(env, 0);
      env.hls.fragBuffered({ sn: 40, start: 400, duration: 10, level: 1, type: 'main' });
      env.setRanges([[0, 300]]);
      env.hls.bufferFlushed();
      env.setRanges(null);
      bank(env, 0); // the film is fetched again, all at one rung; the level-1 entry must not linger
      env.setAhead(480);
      env.tick();
      expect(env.flushes).toHaveLength(1);
    });

    it('replaces an entry buffered again at the same start', () => {
      const env = world(plain);
      ready(env, 0);
      env.hls.fragBuffered({ sn: 40, start: 400, duration: 10, level: 1, type: 'main' });
      env.hls.fragBuffered({ sn: 40, start: 400, duration: 10, level: 0, type: 'main' });
      env.tick();
      expect(env.flushes).toHaveLength(1);
    });
  });

  describe('a fragment in flight', () => {
    const loading = { sn: 50, start: 500, duration: 10, level: 0, type: 'main', stats: {} };

    it('blocks the flush until it is buffered, and the next FRAG_BUFFERED decides', () => {
      const env = world(plain);
      ready(env, 0);
      env.hls.fragLoading(loading);
      env.tick();
      expect(env.flushes).toHaveLength(0);
      env.hls.fragBuffered(loading);
      expect(env.flushes).toHaveLength(1);
    });

    it('is not an init segment: that blocks nothing', () => {
      const env = world(plain);
      ready(env, 0);
      env.hls.fragLoading({ sn: 'initSegment', stats: {} });
      env.tick();
      expect(env.flushes).toHaveLength(1);
    });

    it('is still blocking when the fragment is loaded but not yet buffered', () => {
      const env = world(plain);
      ready(env, 0);
      env.hls.fragLoading(loading);
      env.hls.fragLoaded(stats(FAST));
      env.tick();
      expect(env.flushes).toHaveLength(0);
    });

    it('stops blocking when hls.js abandons it for another level', () => {
      const env = world(plain);
      ready(env, 0);
      env.hls.fragLoading(loading);
      env.hls.fragAborted(loading);
      env.tick();
      expect(env.flushes).toHaveLength(1);
    });

    it('stops blocking when it fails with an HTTP answer (hls.js retries it)', () => {
      const env = world(plain);
      ready(env, 0);
      env.hls.fragLoading(loading);
      env.hls.error({
        fatal: false,
        type: 'networkError',
        details: 'fragLoadError',
        response: { code: 503 },
        frag: loading,
      });
      env.tick();
      expect(env.flushes).toHaveLength(1);
    });

    describe('a newer fragment starts before the buffered one is seen (hls.js’s own handler ran first)', () => {
      const next = { sn: 51, start: 510, duration: 10, level: 0, type: 'main', stats: {} };
      const started = () => {
        const env = world(plain);
        ready(env, 0);
        env.hls.fragLoading(loading);
        env.hls.fragLoading(next); // hls.js asked for the next one inside its FRAG_BUFFERED handler
        return env;
      };

      it('keeps blocking when the first one is buffered, and lets the flush go when the next is', () => {
        const env = started();
        env.hls.fragBuffered(loading);
        env.tick();
        expect(env.flushes).toHaveLength(0);
        env.hls.fragBuffered(next);
        expect(env.flushes).toHaveLength(1);
      });

      it('keeps blocking when hls.js abandons the stale one', () => {
        const env = started();
        env.hls.fragAborted(loading);
        env.tick();
        expect(env.flushes).toHaveLength(0);
      });

      it('keeps blocking when the stale one fails', () => {
        const env = started();
        env.hls.error({
          fatal: false,
          type: 'networkError',
          details: 'fragLoadError',
          response: { code: 503 },
          frag: loading,
        });
        env.tick();
        expect(env.flushes).toHaveLength(0);
      });

      it('stops blocking when the newer one is abandoned, told apart by sn and level', () => {
        const env = started();
        env.hls.fragAborted({ ...next });
        env.tick();
        expect(env.flushes).toHaveLength(1);
      });

      it('does not mistake the same sn at another level for it', () => {
        const env = started();
        env.hls.fragAborted({ ...next, level: 1 });
        env.tick();
        expect(env.flushes).toHaveLength(0);
      });
    });

    it('stops blocking at a restart', () => {
      const env = world(plain);
      ready(env, 0);
      env.hls.fragLoading(loading);
      env.hls.error({ fatal: true, type: 'networkError', details: 'levelLoadError' });
      vi.advanceTimersByTime(RECONNECT_MS);
      env.advance(RECONNECT_MS);
      recover(env);
      env.advance(600_000);
      env.tick();
      expect(env.flushes).toHaveLength(1);
    });
  });

  describe('the quiet time after a step down', () => {
    function afterStepDown() {
      const env = world(plainWith720);
      ready(env, 0);
      env.hls.fragLoaded(stats(FAST));
      env.advance(HEALTHY_MS);
      env.hls.fragLoaded(stats(FAST)); // the cap is at level 2
      env.hls.fetchLevel(2);
      env.hls.fetchLevel(1); // a step down: the cap falls to level 1, still above the banked rung
      return env;
    }

    it.each([
      [119_999, 0],
      [120_000, 1],
    ])('is 120 s: %i ms after it', (ms, flushes) => {
      const env = afterStepDown();
      expect(env.hls.autoLevelCapping).toBe(1);
      env.advance(ms);
      env.tick();
      expect(env.flushes).toHaveLength(flushes);
    });
  });

  describe('Manual', () => {
    it('never re-banks and never raises the floor', () => {
      const env = world(plain, { auto: false });
      ready(env, 0);
      env.hls.fragBuffered({ sn: 70, start: 480, duration: 10, level: 0, type: 'main' });
      env.tick();
      expect(env.flushes).toHaveLength(0);
      expect(env.hls.config.minAutoBitrate).toBe(0);
    });
  });
});

describe('the re-bank waits for hls.js to be fetching the new rung', () => {
  const oldLast = { sn: 48, start: 480, duration: 10, level: 0, type: 'main', stats: {} };
  const newFirst = { sn: 49, start: 490, duration: 10, level: 1, type: 'main', stats: {} };
  const fail503 = (hls, frag) =>
    hls.error({
      fatal: false,
      type: 'networkError',
      details: 'fragLoadError',
      response: { code: 503 },
      frag,
    });

  // `minAutoBitrate` writes are recorded: the floor must not be touched while hls.js is on the old rung.
  function recordWrites(env) {
    const writes = [];
    let value = env.hls.config.minAutoBitrate;
    Object.defineProperty(env.hls.config, 'minAutoBitrate', {
      get: () => value,
      set: (v) => {
        writes.push(v);
        value = v;
      },
    });
    return writes;
  }

  it('does nothing while hls.js is still on the old rung: no flush, the floor never written', () => {
    const env = world(plain);
    ready(env, 0, { stepped: false });
    const writes = recordWrites(env);
    env.tick();
    env.hls.fragBuffered(oldLast);
    env.tick();
    expect(env.flushes).toHaveLength(0);
    expect(writes).toEqual([]);
    expect(env.hls.config.minAutoBitrate).toBe(0);
  });

  it('starts on the old rung’s last fragment once LEVEL_SWITCHING named the new rung', () => {
    const env = world(plain);
    ready(env, 0, { stepped: false });
    env.hls.fetchLevel(1); // hls.js’s own handler runs first and names the new level
    env.hls.fragBuffered(oldLast);
    expect(env.hls.config.minAutoBitrate).toBe(1_000_000);
    expect(env.flushes).toHaveLength(1);
    expect(env.flushes[0].data).toEqual({ startOffset: 300, endOffset: Infinity, type: null });
    expect(env.flushes[0].floor).toBe(1_000_000);
  });

  it('does not start when a fragment at the new rung has already started loading', () => {
    const env = world(plain);
    ready(env, 0, { stepped: false });
    env.hls.fetchLevel(1);
    env.hls.fragLoading(newFirst); // the bank was under hls.js’s target: it loaded at once
    env.hls.fragBuffered(oldLast);
    env.tick();
    expect(env.flushes).toHaveLength(0);
    expect(env.hls.config.minAutoBitrate).toBe(0);
  });

  it('starts after a 503 on the first fragment of the new rung, hls.js still on the new rung', () => {
    const env = world(plain);
    ready(env, 0, { stepped: false });
    env.hls.fetchLevel(1);
    env.hls.fragLoading(newFirst);
    fail503(env.hls, newFirst);
    expect(env.flushes).toHaveLength(0);
    env.tick();
    expect(env.flushes).toHaveLength(1);
    expect(env.flushes[0].floor).toBe(1_000_000);
  });

  it('does not start after that 503 when the headroom is short of 3 x the new rung’s peak', () => {
    const env = world(plain);
    env.hls.manifest(env.hls.levels, 0);
    env.hls.fetchLevel(0);
    for (let i = 0; i < 3; i += 1) env.hls.fragLoaded(stats(2999));
    env.advance(HEALTHY_MS);
    env.hls.fragLoaded(stats(2999));
    bank(env, 0);
    env.setAhead(480);
    env.hls.fetchLevel(1);
    env.hls.fragLoading(newFirst);
    fail503(env.hls, newFirst);
    env.tick();
    expect(env.flushes).toHaveLength(0);
  });
});

describe('when the re-bank is done', () => {
  it('takes the floor off once the bank is back to 460 s after the flush, with no cool-down', () => {
    const env = world(plain);
    ready(env, 0);
    env.tick();
    env.setAhead(300); // the flushed bank
    env.hls.bufferFlushed(OWN_FLUSH);
    env.setAhead(459);
    env.tick();
    expect(env.hls.config.minAutoBitrate).toBe(1_000_000);
    env.setAhead(460);
    env.tick();
    expect(env.hls.config.minAutoBitrate).toBe(0);
    // A new decision waits only for the quiet time: 120 s after the re-bank was done.
    // The flushed film came back one rung up: the next re-bank is the rung after, 120 s after this one.
    for (let s = 300; s < 480; s += 10) {
      env.hls.fragBuffered({ sn: s / 10, start: s, duration: 10, level: 1, type: 'main' });
    }
    env.setAhead(480);
    env.hls.fetchLevel(1);
    env.advance(HEALTHY_MS);
    env.hls.fragLoaded(stats(FAST)); // the cap climbs to 480p
    env.hls.fetchLevel(2); // and hls.js steps up to it
    env.advance(119_999 - HEALTHY_MS);
    env.tick();
    expect(env.flushes).toHaveLength(1);
    env.advance(1);
    env.tick();
    expect(env.flushes).toHaveLength(2);
    expect(env.flushes[1].floor).toBe(1_500_000);
  });

  it('is not done while the flush has not happened: the old bank still reads full', () => {
    const env = world(plain);
    ready(env, 0);
    env.tick();
    env.setAhead(480);
    env.tick();
    expect(env.hls.config.minAutoBitrate).toBe(1_000_000);
  });

  it.each([
    ['hls.js’s own back-buffer flush', { start: 0, end: 20 }],
    ['a flush that ends elsewhere', { start: 300, end: 400 }],
    ['a flush from another start', { start: 310, end: Infinity }],
    ['a flush that failed (0 to 0)', { start: 0, end: 0 }],
    ['a flush starting 0.5 s after ours', { start: 300.5, end: Infinity }],
    ['a flush starting 0.5 s before ours', { start: 299.5, end: Infinity }],
  ])(
    'is not done by %s, which finishes first while the old bank still reads full',
    (_name, flush) => {
      const env = world(plain);
      ready(env, 0);
      env.tick();
      env.hls.bufferFlushed(flush);
      env.setAhead(480);
      env.tick();
      expect(env.hls.config.minAutoBitrate).toBe(1_000_000);
      env.hls.bufferFlushed(OWN_FLUSH);
      env.setAhead(460);
      env.tick();
      expect(env.hls.config.minAutoBitrate).toBe(0);
    },
  );
});

describe('the flush the re-bank asked for, as it finishes', () => {
  it.each([[300.49], [299.51]])('is ours when it starts at %f, within 0.5 s of from', (start) => {
    const env = world(plain);
    ready(env, 0);
    env.tick();
    env.hls.bufferFlushed({ start, end: Infinity });
    env.setAhead(460);
    env.tick();
    expect(env.hls.config.minAutoBitrate).toBe(0);
  });
});

describe('undoing the re-bank', () => {
  // Each way out: the floor is off at once, and no re-bank starts for 10 minutes after.
  const undoes = {
    'a bank under 270 s': (env) => {
      env.setAhead(269);
      env.tick();
    },
    'fragment trouble': (env) => trouble(env.hls),
    'a seek': (env) => env.video.dispatchEvent(new Event('seeking')),
    'a stall': (env) => env.video.dispatchEvent(new Event('waiting')),
    'a restart': (env) => {
      env.hls.error({ fatal: true, type: 'networkError', details: 'levelLoadError' });
      env.advance(RECONNECT_MS);
      vi.advanceTimersByTime(RECONNECT_MS);
    },
    'a new network': (env) => {
      window.dispatchEvent(new Event('offline'));
      env.tick(); // the undo and a fresh decision on the same tick: the cool-down wins
    },
    destroy: (env) => env.engine.destroy(),
  };

  describe.each(Object.entries(undoes))('%s', (name, undo) => {
    it('takes the floor off at once', () => {
      const env = world(plain);
      ready(env, 0);
      env.tick();
      expect(env.hls.config.minAutoBitrate).toBe(1_000_000);
      undo(env);
      expect(env.hls.config.minAutoBitrate).toBe(0);
      expect(env.flushes).toHaveLength(1);
    });

    if (name === 'destroy') return;
    it('blocks another re-bank for 10 minutes', () => {
      const env = world(plain);
      ready(env, 0);
      env.tick();
      undo(env);
      const undoneAt = env.now();
      recover(env);
      env.advance(undoneAt + 599_999 - env.now());
      env.tick();
      expect(env.flushes).toHaveLength(1);
      env.advance(1);
      env.tick();
      expect(env.flushes).toHaveLength(2);
    });
  });

  it('keeps the bank at 270 s: only a bank under 270 s is undone', () => {
    const env = world(plain);
    ready(env, 0);
    env.tick();
    env.setAhead(270);
    env.tick();
    expect(env.hls.config.minAutoBitrate).toBe(1_000_000);
  });

  it('does not start a cool-down for a bank that dips when no re-bank is under way', () => {
    const env = world(plain);
    ready(env, 0);
    env.setAhead(100);
    env.tick();
    bank(env, 0); // the entries past 100 s were forgotten with the buffer; the film comes back
    env.setAhead(480);
    env.tick();
    expect(env.flushes).toHaveLength(1);
  });
});

describe('the floor hold and the re-bank share one floor', () => {
  const PEAK_360 = 816_000;
  const PEAK_480 = 1_328_000;

  // 360p banked with the hold engaged at 360p, then the re-bank to 480p on top of it.
  function holdAndRebank() {
    const env = world(held);
    env.hls.manifest(env.hls.levels, 1);
    env.hls.fetchLevel(1);
    for (let i = 0; i < 3; i += 1) env.hls.fragLoaded(stats(FAST));
    env.advance(HEALTHY_MS);
    env.hls.fragLoaded(stats(FAST));
    bank(env, 1);
    env.setAhead(480);
    env.hls.fetchLevel(2); // hls.js has stepped up to 480p
    return env;
  }

  it('writes the larger of the two', () => {
    const env = holdAndRebank();
    env.tick();
    expect(env.flushes).toHaveLength(1);
    expect(env.flushes[0].floor).toBe(PEAK_480);
    expect(env.hls.config.minAutoBitrate).toBe(PEAK_480);
  });

  it('leaves the hold’s floor when the re-bank is undone', () => {
    const env = holdAndRebank();
    env.tick();
    env.video.dispatchEvent(new Event('seeking'));
    expect(env.hls.config.minAutoBitrate).toBe(PEAK_360);
  });

  it('leaves the hold’s floor when the re-bank is done', () => {
    const env = holdAndRebank();
    env.tick();
    env.hls.bufferFlushed(OWN_FLUSH);
    env.tick(); // the bank is back to 480 s
    expect(env.hls.config.minAutoBitrate).toBe(PEAK_360);
  });

  it('undoes the re-bank when the hold lets go, whatever else is true of the bank', () => {
    const env = holdAndRebank();
    env.tick();
    env.hls.fragLoaded(stats(100)); // a slow fragment: the bank would not last 600 s at 360p
    expect(env.hls.config.minAutoBitrate).toBe(0);
    expect(env.flushes).toHaveLength(1);
  });

  it('raises the floor with no hold at all on a ladder with no 360p', () => {
    const env = world(plain);
    ready(env, 0);
    env.tick();
    expect(env.hls.config.minAutoBitrate).toBe(1_000_000);
  });
});

describe('counting what the re-bank did (onRebank)', () => {
  const kinds = (env) => env.handlers.onRebank.mock.calls.map(([kind]) => kind);

  it('counts a re-bank started once, however many ticks follow', () => {
    const env = world(plain);
    ready(env, 0);
    env.tick();
    env.tick();
    env.tick();
    expect(kinds(env)).toEqual(['started']);
  });

  it('counts nothing when no re-bank starts', () => {
    const env = world(plain);
    ready(env, 0, { stepped: false });
    env.tick();
    expect(kinds(env)).toEqual([]);
  });

  it('counts done once, when the bank is back to 460 s, and never undone after it', () => {
    const env = world(plain);
    ready(env, 0);
    env.tick();
    env.setAhead(300);
    env.hls.bufferFlushed(OWN_FLUSH);
    env.setAhead(459);
    env.tick();
    expect(kinds(env)).toEqual(['started']);
    env.setAhead(460);
    env.tick();
    env.tick();
    expect(kinds(env)).toEqual(['started', 'done']);
    env.video.dispatchEvent(new Event('seeking')); // no re-bank is under way: nothing to undo
    expect(kinds(env)).toEqual(['started', 'done']);
  });

  it.each([
    ['a bank under 270 s', (env) => (env.setAhead(269), env.tick())],
    ['fragment trouble', (env) => trouble(env.hls)],
    ['a seek', (env) => env.video.dispatchEvent(new Event('seeking'))],
    ['a stall', (env) => env.video.dispatchEvent(new Event('waiting'))],
    ['destroy', (env) => env.engine.destroy()],
  ])('counts a re-bank undone once on %s, and not again by the events after it', (_name, undo) => {
    const env = world(plain);
    ready(env, 0);
    env.tick();
    undo(env);
    env.video.dispatchEvent(new Event('seeking'));
    env.video.dispatchEvent(new Event('waiting'));
    trouble(env.hls);
    expect(kinds(env)).toEqual(['started', 'undone']);
  });

  it('counts an undo with no re-bank under way as nothing, though it starts the cool-down', () => {
    const env = world(plain);
    ready(env, 0);
    env.setAhead(100);
    env.tick();
    env.video.dispatchEvent(new Event('seeking'));
    expect(kinds(env)).toEqual([]);
  });

  it('counts a re-bank undone and then started again as two started and one undone', () => {
    const env = world(plain);
    ready(env, 0);
    env.tick();
    env.setAhead(269);
    env.tick();
    const undoneAt = env.now();
    recover(env);
    env.advance(undoneAt + 600_000 - env.now());
    env.tick();
    expect(kinds(env)).toEqual(['started', 'undone', 'started']);
  });

  it('works with no onRebank handler (the native player has none)', () => {
    const env = world(plain);
    delete env.handlers.onRebank;
    ready(env, 0);
    expect(() => env.tick()).not.toThrow();
  });
});
