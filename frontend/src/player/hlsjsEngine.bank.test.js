import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FakeHls } from '../test/fakeHls.js';
import { BANK_RUNG_TEXT, HOLD_CHECK_MS, hlsConfig, startHlsJs } from './hlsjsEngine.js';
import { HEALTHY_MS } from './stepGate.js';

beforeEach(() => {
  FakeHls.reset();
  vi.useFakeTimers();
});
afterEach(() => vi.useRealTimers());

// The engine's manual level for the film bank (outside-bank-plan.md, decision 3) on a fake hls.js. The ladder
// has real ids, so the floor hold applies, and the champagne ceiling starts at the 480p level.
const rung = (id, height, bitrate) => ({ id, height, bitrate, copy: false });
const rungs = [
  rung('240p', 240, 364_000),
  rung('360p', 360, 816_000),
  rung('480p', 480, 1_328_000),
  rung('720p', 720, 3_128_000),
];
const levelsOf = (list) => list.map(({ height, bitrate }) => ({ height, bitrate }));
const FAST = 20_000;
const stats = (kbps) => ({
  loaded: kbps * 125,
  retry: 0,
  loading: { start: 0, first: 0, end: 1000 },
});
const media = (sn, start, level = 0) => ({ sn, start, duration: 10, level, type: 'main' });

// What hls.js's error controller does to a failing fragment's level before the engine hears of the error.
const failFragment = (hls, details, frag, response) => {
  hls.levels[frag.level].fragmentError += 1;
  hls.error({ fatal: false, type: 'networkError', details, frag, response });
};
// hls.js's retry delay for the next failure: its backoff on the sum of the levels' `fragmentError`.
const retryDelayMs = (hls) => {
  const policy = hlsConfig(0).fragLoadPolicy.default.errorRetry;
  const count = hls.levels.reduce((sum, l) => sum + l.fragmentError, 0);
  return Math.min(policy.retryDelayMs * 2 ** count, policy.maxRetryDelayMs);
};

// A <video> whose playhead and buffered ranges the test sets.
function world({ first = 0, auto = true } = {}) {
  const video = document.createElement('video');
  const state = { time: 0, ranges: [] };
  Object.defineProperty(video, 'currentTime', { get: () => state.time });
  Object.defineProperty(video, 'buffered', {
    get: () => ({
      length: state.ranges.length,
      start: (i) => state.ranges[i][0],
      end: (i) => state.ranges[i][1],
    }),
  });
  let t = 0;
  const handlers = {
    onLevel: vi.fn(),
    onThroughput: vi.fn(),
    onReconnecting: vi.fn(),
    onSessionLost: vi.fn(),
    onFatal: vi.fn(),
    onNetworkError: vi.fn(),
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
  // Every write to hls.js, in order, with the floor and the cap in force at that moment.
  const log = [];
  let loadLevel = -1;
  Object.defineProperty(hls, 'loadLevel', {
    get: () => loadLevel,
    set: (v) => {
      loadLevel = v;
      log.push({ write: 'loadLevel', value: v, floor: hls.config.minAutoBitrate });
    },
  });
  let floor = 0;
  Object.defineProperty(hls.config, 'minAutoBitrate', {
    get: () => floor,
    set: (v) => {
      floor = v;
      log.push({ write: 'floor', value: v });
    },
  });
  let capping = -1;
  Object.defineProperty(hls, 'autoLevelCapping', {
    get: () => capping,
    set: (v) => {
      capping = v;
      log.push({ write: 'cap', value: v });
    },
  });
  hls.manifest(levelsOf(rungs), first);
  log.length = 0;
  const lookup = {
    levelAt: vi.fn((s) => (s < 200 ? 1 : null)), // film up to 200 s is held at level 1
    frontLevel: vi.fn(() => 2),
  };
  return {
    engine,
    video,
    hls,
    handlers,
    log,
    lookup,
    setTime: (s) => (state.time = s),
    setRanges: (r) => (state.ranges = r),
    advance: (ms) => (t += ms),
    tick: () => video.dispatchEvent(new Event('timeupdate')),
    seek: () => video.dispatchEvent(new Event('seeking')),
    levelWrites: () => log.filter((e) => e.write === 'loadLevel').map((e) => e.value),
  };
}

describe('hls.js settings', () => {
  it('keeps a manual level through an error', () => {
    expect(hlsConfig(0).preserveManualLevelOnError).toBe(true);
  });
});

describe('the engine holds hls.js on the bank’s level', () => {
  it('names the text of the bank’s wrong-rung answer', () => {
    expect(BANK_RUNG_TEXT).toBe('bank-rung');
  });

  it('turns on at the film after the buffered range holding the playhead', () => {
    const env = world();
    env.setRanges([[0, 90]]);
    env.setTime(30);
    env.engine.bankOn(env.lookup);
    expect(env.lookup.levelAt).toHaveBeenCalledWith(90);
    expect(env.levelWrites()).toEqual([1]);
  });

  it('turns on at the playhead when nothing is buffered, and at the front’s rung beyond what is held', () => {
    const env = world();
    env.setTime(250);
    env.engine.bankOn(env.lookup);
    expect(env.lookup.levelAt).toHaveBeenCalledWith(250);
    expect(env.levelWrites()).toEqual([2]); // not held: the front's level
  });

  it('turning on again only replaces the lookup', () => {
    const env = world();
    env.engine.bankOn(env.lookup);
    const other = { levelAt: vi.fn(() => 3), frontLevel: vi.fn(() => 0) };
    env.engine.bankOn(other);
    expect(env.levelWrites()).toEqual([1]);
    env.hls.emit(FakeHls.Events.FRAG_LOADED, { frag: media(1, 0) });
    expect(env.levelWrites()).toEqual([1, 3]);
  });

  describe('on FRAG_LOADED', () => {
    it('writes the level for the film after the loaded fragment', () => {
      const env = world();
      env.engine.bankOn(env.lookup);
      env.log.length = 0;
      env.setRanges([[0, 40]]);
      env.hls.emit(FakeHls.Events.FRAG_LOADED, { frag: media(5, 40) });
      expect(env.lookup.levelAt).toHaveBeenLastCalledWith(50);
      expect(env.log).toEqual([{ write: 'loadLevel', value: 1, floor: 0 }]);
    });

    it('reads the end of the merged range: film buffered after the fragment joins it when it lands', () => {
      const env = world();
      env.engine.bankOn(env.lookup);
      env.setRanges([
        [0, 40],
        [50, 200],
        [200.3, 260],
      ]);
      env.hls.emit(FakeHls.Events.FRAG_LOADED, { frag: media(5, 40) });
      expect(env.lookup.levelAt).toHaveBeenLastCalledWith(260);
      expect(env.levelWrites().at(-1)).toBe(2); // past what the bank holds: the front's level
    });

    it('does not join a range that starts further than a hole ahead', () => {
      const env = world();
      env.engine.bankOn(env.lookup);
      env.setRanges([[51, 200]]);
      env.hls.emit(FakeHls.Events.FRAG_LOADED, { frag: media(5, 40) });
      expect(env.lookup.levelAt).toHaveBeenLastCalledWith(50);
    });

    it('ignores an init segment, another track and a fragment with no length', () => {
      const env = world();
      env.engine.bankOn(env.lookup);
      env.log.length = 0;
      const emit = (frag) => env.hls.emit(FakeHls.Events.FRAG_LOADED, { frag });
      emit({ sn: 'initSegment', start: 0, duration: 0, level: 0 });
      emit({ ...media(6, 60), type: 'audio' });
      emit({ sn: 7, start: 70, level: 0 });
      emit(undefined);
      expect(env.log).toEqual([]);
    });

    it('writes nothing while the bank is off', () => {
      const env = world();
      env.hls.emit(FakeHls.Events.FRAG_LOADED, { frag: media(5, 40) });
      expect(env.log).toEqual([]);
    });
  });

  describe('on seeking', () => {
    it('inside the buffer: the end of the range holding the target', () => {
      const env = world();
      env.setRanges([
        [0, 100],
        [300, 400],
      ]);
      env.engine.bankOn(env.lookup);
      env.log.length = 0;
      env.setTime(60);
      env.seek();
      expect(env.lookup.levelAt).toHaveBeenLastCalledWith(100);
      expect(env.levelWrites()).toEqual([1]);
    });

    it('outside the buffer: the target', () => {
      const env = world();
      env.setRanges([[0, 100]]);
      env.engine.bankOn(env.lookup);
      env.setTime(500);
      env.seek();
      expect(env.lookup.levelAt).toHaveBeenLastCalledWith(500);
      expect(env.levelWrites().at(-1)).toBe(2);
    });

    it('lets Auto handle a seek with the bank off', () => {
      const env = world();
      env.seek();
      expect(env.log).toEqual([]);
    });
  });

  describe('a fatal media error', () => {
    it('writes the level held at the playhead before hls.js recovers', () => {
      const env = world();
      env.hls.recoverMediaError.mockImplementation(() => env.log.push({ write: 'recover' }));
      env.engine.bankOn(env.lookup);
      env.log.length = 0;
      env.setTime(120);
      env.hls.error({ fatal: true, type: 'mediaError', details: 'bufferAppendError' });
      expect(env.lookup.levelAt).toHaveBeenLastCalledWith(120);
      expect(env.log).toEqual([{ write: 'loadLevel', value: 1, floor: 0 }, { write: 'recover' }]);
      env.engine.bankRelease(1);
      env.log.length = 0;
      env.hls.error({ fatal: true, type: 'mediaError', details: 'bufferAppendError' });
      expect(env.log).toEqual([{ write: 'recover' }]); // off: no level written
    });
  });

  describe('a request the bank fails as wrong-rung', () => {
    it('sets the held rung before the error, and takes the error’s count back', () => {
      const env = world();
      env.engine.bankOn(env.lookup);
      env.log.length = 0;
      env.engine.bankWrongRung(1);
      expect(env.log).toEqual([{ write: 'loadLevel', value: 1, floor: 0 }]);
      const frag = media(9, 90, 2);
      failFragment(env.hls, 'fragLoadError', frag, { code: 503, text: BANK_RUNG_TEXT });
      expect(env.hls.levels[2].fragmentError).toBe(0);
      // A real 503 that follows is retried after 1 s, not later: hls.js's delay doubles with each count.
      expect(retryDelayMs(env.hls)).toBe(1000);
      failFragment(env.hls, 'fragLoadError', frag, { code: 503, text: 'Service Unavailable' });
      expect(env.hls.levels[2].fragmentError).toBe(1);
    });

    it('leaves a count that is already 0 alone, and other errors’ counts', () => {
      const env = world();
      env.hls.levels[0].fragmentError = 2;
      env.hls.error({
        fatal: false,
        type: 'networkError',
        details: 'fragLoadError',
        frag: media(1, 10, 2),
        response: { code: 503, text: BANK_RUNG_TEXT },
      });
      expect(env.hls.levels[2].fragmentError).toBe(0);
      expect(env.hls.levels[0].fragmentError).toBe(2);
      env.hls.error({
        fatal: false,
        type: 'networkError',
        details: 'fragLoadError',
        frag: undefined,
        response: { code: 503, text: BANK_RUNG_TEXT },
      });
      expect(env.hls.levels[0].fragmentError).toBe(2);
    });

    it('is not fragment trouble', () => {
      const env = world();
      env.engine.bankOn(env.lookup);
      failFragment(env.hls, 'fragLoadError', media(9, 90, 1), {
        code: 503,
        text: BANK_RUNG_TEXT,
      });
      expect(env.handlers.onNetworkError).not.toHaveBeenCalled();
    });

    it('writes nothing while the bank is off', () => {
      const env = world();
      env.engine.bankWrongRung(1);
      expect(env.log).toEqual([]);
    });
  });

  describe('the rules are suspended', () => {
    it('keeps the cap, the floor and the gate while hls.js changes level, a fragment loads and playback ticks', () => {
      const env = world({ first: 1 });
      env.hls.fetchLevel(1);
      for (let i = 0; i < 3; i += 1) env.hls.fragLoaded(stats(FAST));
      env.engine.bankOn(env.lookup);
      env.log.length = 0;
      env.advance(HEALTHY_MS);
      env.hls.fetchLevel(0); // a step down would pull the cap to 0
      env.hls.fragLoaded(stats(FAST)); // a healthy minute would raise the cap
      env.hls.fetchLevel(2);
      vi.advanceTimersByTime(HOLD_CHECK_MS * 3);
      env.tick();
      env.video.dispatchEvent(new Event('waiting'));
      expect(env.log).toEqual([]);
      expect(env.hls.trigger).not.toHaveBeenCalled();
    });

    it('still reports the level hls.js fetches', () => {
      const env = world();
      const onFetching = vi.fn();
      env.engine.destroy();
      const again = world();
      again.handlers.onFetching = onFetching;
      again.engine.bankOn(again.lookup);
      again.hls.fetchLevel(2);
      expect(onFetching).toHaveBeenCalledWith('480p');
    });

    it('undoes a re-bank under way first: its floor comes off before the manual level is written', () => {
      const env = world({ first: 1 });
      env.hls.fetchLevel(1);
      for (let i = 0; i < 3; i += 1) env.hls.fragLoaded(stats(FAST));
      env.advance(HEALTHY_MS);
      env.hls.fragLoaded(stats(FAST)); // the gate climbs to level 2
      for (let s = 0; s < 480; s += 10) {
        env.setRanges([[0, Math.min(s + 10, 460)]]);
        env.hls.fragBuffered(media(s / 10, s, 1));
      }
      env.setRanges([[0, 480]]);
      env.hls.fetchLevel(2);
      env.tick();
      expect(env.hls.trigger).toHaveBeenCalledTimes(1); // the re-bank flushed
      expect(env.hls.config.minAutoBitrate).toBe(1_328_000);
      env.log.length = 0;
      env.engine.bankOn(env.lookup);
      expect(env.log).toEqual([
        { write: 'floor', value: 816_000 }, // the re-bank's 480p floor is off; the hold's 360p one stays
        { write: 'loadLevel', value: 2, floor: 816_000 },
      ]);
    });

    // The gate has climbed to level 2 while hls.js still fetches level 1: a timeout would pull it down to 1.
    const gateAhead = (env) => {
      env.hls.fetchLevel(1);
      env.hls.fragLoaded(stats(FAST));
      env.advance(HEALTHY_MS);
      env.hls.fragLoaded(stats(FAST));
      expect(env.hls.autoLevelCapping).toBe(2);
    };

    it('counts a fragment timeout as trouble but lowers no gate', () => {
      const env = world({ first: 1 });
      gateAhead(env);
      env.engine.bankOn(env.lookup);
      env.log.length = 0;
      env.hls.error({ fatal: false, type: 'networkError', details: 'fragLoadTimeOut' });
      expect(env.log).toEqual([]); // no cap written
      expect(env.handlers.onNetworkError).toHaveBeenCalledWith({ timeout: true });
      // The trouble restarted the healthy minute the release's gate starts from; nothing lowered it.
      env.engine.bankRelease(2);
      expect(env.log).toEqual([
        { write: 'loadLevel', value: -1, floor: 0 },
        { write: 'floor', value: 0 },
        { write: 'cap', value: 2 },
      ]);
    });

    it('is lowered by the same timeout when the bank is off', () => {
      const env = world({ first: 1 });
      gateAhead(env);
      env.log.length = 0;
      env.hls.error({ fatal: false, type: 'networkError', details: 'fragLoadTimeOut' });
      expect(env.log).toContainEqual({ write: 'cap', value: 1 });
    });
  });

  describe('release', () => {
    it('puts hls.js back on Auto, writes the floor the hold had, and starts the gate at the level fetched', () => {
      const env = world({ first: 1 });
      env.hls.fetchLevel(1);
      for (let i = 0; i < 3; i += 1) env.hls.fragLoaded(stats(FAST));
      env.setRanges([[0, 400]]);
      vi.advanceTimersByTime(HOLD_CHECK_MS);
      env.hls.fragLoaded(stats(FAST));
      expect(env.hls.config.minAutoBitrate).toBe(816_000); // R: the hold's floor, on the 360p level
      env.engine.bankOn(env.lookup);
      env.log.length = 0;
      env.engine.bankRelease(2);
      expect(env.log).toEqual([
        { write: 'loadLevel', value: -1, floor: 816_000 },
        { write: 'floor', value: 816_000 }, // written again though it did not change
        { write: 'cap', value: 2 }, // above R's level (360p): the cap is written again
      ]);
    });

    it('with a kept 240p gate and 480p fetched caps Auto at 480p', () => {
      const env = world({ first: 0 });
      env.hls.fetchLevel(0);
      expect(env.hls.autoLevelCapping).toBe(0);
      env.engine.bankOn(env.lookup);
      env.hls.fetchLevel(2);
      env.log.length = 0;
      env.engine.bankRelease(2);
      expect(env.hls.autoLevelCapping).toBe(2);
      expect(env.log.at(-1)).toEqual({ write: 'cap', value: 2 });
    });

    it('is still clamped by the champagne ceiling', () => {
      const env = world({ first: 0 });
      env.engine.bankOn(env.lookup);
      env.engine.bankRelease(3); // 720p: nothing measured on this link has earned a rung above 480p
      expect(env.hls.autoLevelCapping).toBe(2);
    });

    it('climbs from there one level a minute', () => {
      const env = world({ first: 0 });
      env.engine.bankOn(env.lookup);
      env.hls.fetchLevel(1);
      env.engine.bankRelease(1);
      expect(env.hls.autoLevelCapping).toBe(1);
      env.advance(HEALTHY_MS - 1);
      env.hls.fragLoaded(stats(FAST));
      expect(env.hls.autoLevelCapping).toBe(1);
      env.advance(1);
      env.hls.fragLoaded(stats(FAST));
      expect(env.hls.autoLevelCapping).toBe(2);
    });

    it('takes the level hls.js is fetching when none is given', () => {
      const env = world({ first: 0 });
      env.engine.bankOn(env.lookup);
      env.hls.fetchLevel(1);
      env.engine.bankRelease();
      expect(env.hls.autoLevelCapping).toBe(1);
    });

    it('starts at the bottom when no level is known', () => {
      const env = world({ first: 0 });
      env.engine.bankOn(env.lookup);
      env.engine.bankRelease();
      expect(env.hls.autoLevelCapping).toBe(0);
    });

    it('gives every rule back: a seek and a level change are Auto’s again, and no level is written', () => {
      const env = world({ first: 0 });
      env.engine.bankOn(env.lookup);
      env.engine.bankRelease(2);
      env.log.length = 0;
      env.hls.emit(FakeHls.Events.FRAG_LOADED, { frag: media(1, 0) });
      env.seek();
      env.hls.fetchLevel(1); // a step down lowers the cap again
      expect(env.levelWrites()).toEqual([]);
      expect(env.hls.autoLevelCapping).toBe(1);
    });

    it('does nothing when the bank is not on, and on a pinned quality only gives Auto back', () => {
      const env = world();
      env.engine.bankRelease(2);
      expect(env.log).toEqual([]);
      const pinned = world({ auto: false });
      pinned.engine.bankOn(pinned.lookup);
      pinned.log.length = 0;
      pinned.engine.bankRelease(2);
      expect(pinned.levelWrites()).toEqual([-1]);
      expect(pinned.log.some((e) => e.write === 'cap')).toBe(false);
    });

    it('a recovery during the release is the engine’s own again: the level is not written', () => {
      const env = world();
      env.engine.bankOn(env.lookup);
      env.engine.bankRelease(1);
      env.log.length = 0;
      env.setTime(50);
      env.hls.error({ fatal: true, type: 'mediaError', details: 'bufferAppendError' });
      expect(env.levelWrites()).toEqual([]);
      expect(env.hls.recoverMediaError).toHaveBeenCalledTimes(1);
    });
  });

  describe('destroy', () => {
    it('while on, and every later call, writes nothing to hls.js', () => {
      const env = world();
      env.engine.bankOn(env.lookup);
      env.log.length = 0;
      env.engine.destroy();
      env.engine.bankWrongRung(1);
      env.engine.bankRelease(2);
      env.engine.bankOn(env.lookup);
      expect(env.log).toEqual([]);
      expect(env.hls.destroy).toHaveBeenCalledTimes(1);
    });
  });
});
