import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FakeHls } from '../test/fakeHls.js';
import {
  BANK_RUNG_TEXT,
  HOLD_CHECK_MS,
  RECONNECT_MS,
  hlsConfig,
  startHlsJs,
} from './hlsjsEngine.js';
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
  const state = { time: 0, ranges: [], paused: false };
  Object.defineProperty(video, 'currentTime', { get: () => state.time });
  Object.defineProperty(video, 'paused', { get: () => state.paused });
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
  const levelAccessor = Object.getOwnPropertyDescriptor(FakeHls.prototype, 'loadLevel');
  Object.defineProperty(hls, 'loadLevel', {
    get: () => levelAccessor.get.call(hls),
    set: (v) => {
      log.push({ write: 'loadLevel', value: v, floor: hls.config.minAutoBitrate });
      levelAccessor.set.call(hls, v);
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
    setPaused: (paused) => (state.paused = paused),
    play: () => {
      state.paused = false;
      video.dispatchEvent(new Event('play'));
    },
    pause: () => {
      state.paused = true;
      video.dispatchEvent(new Event('pause'));
    },
    timeUpdate: () => video.dispatchEvent(new Event('timeupdate')),
    setRanges: (r) => (state.ranges = r),
    advance: (ms) => (t += ms),
    tick: () => video.dispatchEvent(new Event('timeupdate')),
    seek: () => video.dispatchEvent(new Event('seeking')),
    levelWrites: () => log.filter((e) => e.write === 'loadLevel').map((e) => e.value),
  };
}

// The floor hold engaged (hls.js fetching 360p on a fast link with 400 s banked): the floor is the 360p level's.
function engageHold(env) {
  env.hls.fetchLevel(1);
  for (let i = 0; i < 3; i += 1) env.hls.fragLoaded(stats(FAST));
  env.setRanges([[0, 400]]);
  vi.advanceTimersByTime(HOLD_CHECK_MS);
  env.hls.fragLoaded(stats(FAST));
  expect(env.hls.config.minAutoBitrate).toBe(816_000);
}

// The gate has climbed to level 2 while hls.js still fetches level 1: a timeout would pull it down to 1.
function gateAhead(env) {
  env.hls.fetchLevel(1);
  env.hls.fragLoaded(stats(FAST));
  env.advance(HEALTHY_MS);
  env.hls.fragLoaded(stats(FAST));
  expect(env.hls.autoLevelCapping).toBe(2);
}

// A deep buffer at level 1 with the gate at 2 and hls.js on level 2: a re-bank may start at the next tick.
function rebankReady(env) {
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
}

const timeout = (hls) =>
  hls.error({ fatal: false, type: 'networkError', details: 'fragLoadTimeOut' });
const mediaError = (hls) =>
  hls.error({ fatal: true, type: 'mediaError', details: 'bufferAppendError' });

describe('hls.js settings', () => {
  it('keeps a manual level through an error', () => {
    expect(hlsConfig(0).preserveManualLevelOnError).toBe(true);
  });
});

describe('the engine holds hls.js on the bank’s level', () => {
  it('names the text of the bank’s wrong-rung answer', () => {
    expect(BANK_RUNG_TEXT).toBe('bank-rung');
  });

  describe('turning on', () => {
    it('writes the level for the film after the buffered range holding the playhead', () => {
      const env = world();
      env.setRanges([[0, 90]]);
      env.setTime(30);
      env.engine.bankOn(env.lookup);
      expect(env.lookup.levelAt).toHaveBeenCalledWith(90);
      expect(env.levelWrites()).toEqual([1]);
    });

    it('asks for the playhead when nothing is buffered', () => {
      const env = world();
      env.setTime(250);
      env.engine.bankOn(env.lookup);
      expect(env.lookup.levelAt).toHaveBeenCalledWith(250);
    });

    it('writes the front’s level beyond what is held', () => {
      const env = world();
      env.setTime(250);
      env.engine.bankOn(env.lookup);
      expect(env.levelWrites()).toEqual([2]);
    });

    it('while on only replaces the lookup', () => {
      const env = world();
      env.engine.bankOn(env.lookup);
      const other = { levelAt: vi.fn(() => 3), frontLevel: vi.fn(() => 0) };
      env.engine.bankOn(other);
      expect(env.levelWrites()).toEqual([1]);
      env.hls.emit(FakeHls.Events.FRAG_LOADED, { frag: media(1, 0) });
      expect(env.levelWrites()).toEqual([1, 3]);
    });

    it('joins ranges less than the hole apart', () => {
      const env = world();
      env.hls.config.maxBufferHole = 0.5;
      env.setRanges([
        [0, 100],
        [100.25, 200],
      ]);
      env.setTime(60);
      env.engine.bankOn(env.lookup);
      expect(env.lookup.levelAt).toHaveBeenLastCalledWith(200);
    });

    it('takes no hole while paused', () => {
      const env = world();
      env.hls.config.maxBufferHole = 0.5;
      env.setPaused(true);
      env.setRanges([
        [0, 100],
        [100.25, 200],
      ]);
      env.setTime(60);
      env.engine.bankOn(env.lookup);
      expect(env.lookup.levelAt).toHaveBeenLastCalledWith(100);
    });
  });

  describe('on FRAG_LOADED', () => {
    const loaded = (env, frag = media(5, 40)) => env.hls.emit(FakeHls.Events.FRAG_LOADED, { frag });

    it('writes the level for the film after the loaded fragment', () => {
      const env = world();
      env.engine.bankOn(env.lookup);
      env.log.length = 0;
      env.setRanges([[0, 40]]);
      loaded(env);
      expect(env.lookup.levelAt).toHaveBeenLastCalledWith(50);
      expect(env.log).toEqual([{ write: 'loadLevel', value: 1, floor: 0 }]);
    });

    it('reads the end of the range the fragment joins when it lands, past what the bank holds', () => {
      const env = world();
      env.engine.bankOn(env.lookup);
      env.setRanges([
        [0, 40],
        [50, 200],
        [200.05, 260],
      ]);
      loaded(env);
      expect(env.lookup.levelAt).toHaveBeenLastCalledWith(260);
      expect(env.levelWrites().at(-1)).toBe(2); // not held there: the front's level
    });

    // hls.js's hole (`maxBufferHole`) is 0.5 here; the range after the fragment (ending at 50) starts below, at
    // and above it, and runs to 200.
    it.each([
      ['below the hole', 50.25, false, 200],
      ['at the hole', 50.5, false, 50],
      ['above the hole', 50.75, false, 50],
      ['below the hole while paused', 50.25, true, 50],
      ['touching while paused', 50, true, 200],
    ])('a range %s: the film is read from %#', (_name, start, paused, expected) => {
      const env = world();
      env.hls.config.maxBufferHole = 0.5;
      env.setPaused(paused);
      env.engine.bankOn(env.lookup);
      env.setRanges([[start, 200]]);
      loaded(env);
      expect(env.lookup.levelAt).toHaveBeenLastCalledWith(expected);
    });

    it('takes no hole just after a backward seek', () => {
      const env = world();
      env.hls.config.maxBufferHole = 0.5;
      env.engine.bankOn(env.lookup);
      env.setTime(100);
      env.tick();
      env.setTime(60); // the playhead is behind where the last time update left it
      env.setRanges([[50.25, 200]]);
      loaded(env);
      expect(env.lookup.levelAt).toHaveBeenLastCalledWith(50);
    });

    it('ignores an init segment', () => {
      const env = world();
      env.engine.bankOn(env.lookup);
      env.log.length = 0;
      loaded(env, { sn: 'initSegment', start: 0, duration: 0, level: 0 });
      expect(env.log).toEqual([]);
    });

    it('ignores another track', () => {
      const env = world();
      env.engine.bankOn(env.lookup);
      env.log.length = 0;
      loaded(env, { ...media(6, 60), type: 'audio' });
      expect(env.log).toEqual([]);
    });

    it('ignores a fragment with no length, and a missing one', () => {
      const env = world();
      env.engine.bankOn(env.lookup);
      env.log.length = 0;
      loaded(env, { sn: 7, start: 70, level: 0 });
      loaded(env, null);
      expect(env.log).toEqual([]);
    });

    it('writes nothing while the bank is off', () => {
      const env = world();
      loaded(env);
      expect(env.log).toEqual([]);
    });
  });

  describe('on seeking', () => {
    it('inside the buffer writes the level after the range holding the target', () => {
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

    it('outside the buffer writes the level at the target', () => {
      const env = world();
      env.setRanges([[0, 100]]);
      env.engine.bankOn(env.lookup);
      env.setTime(500);
      env.seek();
      expect(env.lookup.levelAt).toHaveBeenLastCalledWith(500);
      expect(env.levelWrites().at(-1)).toBe(2);
    });

    // The hole is 0.5: the second range starts below, at and above it after the first one's end (100).
    it.each([
      ['below the hole', 100.25, 200],
      ['at the hole', 100.5, 100],
      ['above the hole', 100.75, 100],
    ])('a range %s after the one holding the target', (_name, start, expected) => {
      const env = world();
      env.hls.config.maxBufferHole = 0.5;
      env.setRanges([
        [0, 100],
        [start, 200],
      ]);
      env.engine.bankOn(env.lookup);
      env.setTime(60);
      env.seek();
      expect(env.lookup.levelAt).toHaveBeenLastCalledWith(expected);
    });

    it('takes no hole while paused', () => {
      const env = world();
      env.hls.config.maxBufferHole = 0.5;
      env.setPaused(true);
      env.setRanges([
        [0, 100],
        [100.25, 200],
      ]);
      env.engine.bankOn(env.lookup);
      env.setTime(60);
      env.seek();
      expect(env.lookup.levelAt).toHaveBeenLastCalledWith(100);
    });

    it('takes no hole after a backward seek', () => {
      const env = world();
      env.hls.config.maxBufferHole = 0.5;
      env.setRanges([
        [0, 100],
        [100.25, 200],
      ]);
      env.engine.bankOn(env.lookup);
      env.setTime(90);
      env.tick();
      env.setTime(60);
      env.seek();
      expect(env.lookup.levelAt).toHaveBeenLastCalledWith(100);
    });

    it('takes the hole again once playback has moved on', () => {
      const env = world();
      env.hls.config.maxBufferHole = 0.5;
      env.setRanges([
        [0, 100],
        [100.25, 200],
      ]);
      env.engine.bankOn(env.lookup);
      env.setTime(90);
      env.tick();
      env.setTime(60);
      env.tick();
      env.seek();
      expect(env.lookup.levelAt).toHaveBeenLastCalledWith(200);
    });

    it('is Auto’s with the bank off', () => {
      const env = world();
      env.seek();
      expect(env.log).toEqual([]);
    });
  });

  describe('the hole that picks the next fragment', () => {
    it('counts a range that starts within the hole after the playhead as holding it', () => {
      const env = world();
      env.setRanges([[0.05, 100]]);
      env.engine.bankOn(env.lookup);
      expect(env.lookup.levelAt).toHaveBeenLastCalledWith(100);
    });

    it('counts one that starts at the hole as holding it', () => {
      const env = world();
      env.hls.config.maxBufferHole = 0.5;
      env.setRanges([[0.5, 100]]);
      env.engine.bankOn(env.lookup);
      expect(env.lookup.levelAt).toHaveBeenLastCalledWith(100);
    });

    it('does not count one that starts beyond the hole', () => {
      const env = world();
      env.hls.config.maxBufferHole = 0.5;
      env.setRanges([[0.75, 100]]);
      env.engine.bankOn(env.lookup);
      expect(env.lookup.levelAt).toHaveBeenLastCalledWith(0);
    });

    it('does not count one that starts after the playhead while paused', () => {
      const env = world();
      env.setPaused(true);
      env.setRanges([[0.05, 100]]);
      env.engine.bankOn(env.lookup);
      expect(env.lookup.levelAt).toHaveBeenLastCalledWith(0);
    });

    describe('when it changes with no event of hls.js', () => {
      // A fragment loaded (40 to 50) with the next range 0.05 s after it: joined only when a hole is allowed.
      const loadedBeforeRange = (env) => {
        env.setRanges([
          [0, 40],
          [50.05, 200],
        ]);
        env.hls.emit(FakeHls.Events.FRAG_LOADED, { frag: media(5, 40) });
      };

      it('on play: the level is written again for the joined end', () => {
        const env = world();
        env.setPaused(true);
        env.engine.bankOn(env.lookup);
        loadedBeforeRange(env);
        expect(env.lookup.levelAt).toHaveBeenLastCalledWith(50);
        env.play();
        expect(env.lookup.levelAt).toHaveBeenLastCalledWith(200);
      });

      it('on pause: the level is written again for the end without the hole', () => {
        const env = world();
        env.engine.bankOn(env.lookup);
        loadedBeforeRange(env);
        expect(env.lookup.levelAt).toHaveBeenLastCalledWith(200);
        env.pause();
        expect(env.lookup.levelAt).toHaveBeenLastCalledWith(50);
      });

      it('at the end of a backward seek, on the next time update', () => {
        const env = world();
        env.engine.bankOn(env.lookup);
        env.setTime(100);
        env.tick();
        env.setTime(20);
        env.seek(); // behind the last time update: no hole
        loadedBeforeRange(env);
        expect(env.lookup.levelAt).toHaveBeenLastCalledWith(50);
        env.tick();
        expect(env.lookup.levelAt).toHaveBeenLastCalledWith(200);
      });

      it('writes nothing when the hole has not changed', () => {
        const env = world();
        env.engine.bankOn(env.lookup);
        loadedBeforeRange(env);
        env.log.length = 0;
        env.tick();
        env.play();
        expect(env.log).toEqual([]);
      });

      it('writes nothing while the bank is off', () => {
        const env = world();
        env.pause();
        env.play();
        expect(env.log).toEqual([]);
      });
    });
  });

  describe('on a flush', () => {
    describe('with a fragment loaded and not yet appended', () => {
      // Film below 50 is held at level 1, above at 3; the fragment 40 to 50 has loaded, not yet appended.
      const loadedUnappended = () => {
        const env = world();
        env.lookup.levelAt.mockImplementation((s) => (s < 50 ? 1 : 3));
        env.setRanges([[0, 40]]);
        env.engine.bankOn(env.lookup);
        env.hls.emit(FakeHls.Events.FRAG_LOADED, { frag: media(5, 40) });
        env.setTime(10);
        env.setRanges([[5, 40]]);
        return env;
      };

      it('keeps the level the loaded fragment chose when the flush reaches the film ahead', () => {
        const env = loadedUnappended();
        env.hls.bufferFlushed({ start: 100, end: Infinity });
        expect(env.levelWrites().at(-1)).toBe(3);
      });

      it('writes nothing for a back-buffer flush that ends before the playhead', () => {
        const env = loadedUnappended();
        env.log.length = 0;
        env.hls.bufferFlushed({ start: 0, end: 5 });
        expect(env.log).toEqual([]);
      });

      it('forgets the fragment once it is appended', () => {
        const env = loadedUnappended();
        env.hls.fragBuffered(media(5, 40)); // the test leaves the ranges as they were
        env.hls.bufferFlushed({ start: 100, end: Infinity });
        expect(env.levelWrites().at(-1)).toBe(1);
      });

      it('keeps it through a seek inside the buffer', () => {
        const env = loadedUnappended();
        env.setTime(20);
        env.seek();
        expect(env.lookup.levelAt).toHaveBeenLastCalledWith(50);
        expect(env.levelWrites().at(-1)).toBe(3);
      });

      it('keeps it when another track buffers the same sequence number and level', () => {
        const env = loadedUnappended();
        env.hls.fragBuffered({ ...media(5, 40), type: 'audio' });
        env.hls.bufferFlushed({ start: 100, end: Infinity });
        expect(env.levelWrites().at(-1)).toBe(3);
      });

      it('keeps it when another fragment is buffered', () => {
        const env = loadedUnappended();
        env.hls.fragBuffered(media(4, 30));
        env.hls.bufferFlushed({ start: 100, end: Infinity });
        expect(env.levelWrites().at(-1)).toBe(3);
      });

      it('forgets it when its own append fails', () => {
        const env = loadedUnappended();
        env.hls.error({
          fatal: false,
          type: 'mediaError',
          details: 'bufferAppendError',
          frag: media(5, 40),
        });
        env.hls.bufferFlushed({ start: 100, end: Infinity });
        expect(env.levelWrites().at(-1)).toBe(1);
      });

      it('keeps it when another fragment’s append fails', () => {
        const env = loadedUnappended();
        env.hls.error({
          fatal: false,
          type: 'mediaError',
          details: 'bufferAppendError',
          frag: media(4, 30),
        });
        env.hls.bufferFlushed({ start: 100, end: Infinity });
        expect(env.levelWrites().at(-1)).toBe(3);
      });

      it('forgets it when a media error is recovered', () => {
        const env = loadedUnappended();
        mediaError(env.hls); // no fragment named: the recovery detaches the media
        env.seek();
        expect(env.lookup.levelAt).toHaveBeenLastCalledWith(40);
        expect(env.levelWrites().at(-1)).toBe(1);
      });

      it('forgets it at a fatal network error, and writes the level for the buffer as it is', () => {
        const env = loadedUnappended();
        env.hls.error({ fatal: true, type: 'networkError', details: 'fragLoadError' });
        expect(env.lookup.levelAt).toHaveBeenLastCalledWith(40);
        expect(env.levelWrites().at(-1)).toBe(1);
      });

      it('writes that level again when loading restarts', () => {
        const env = loadedUnappended();
        env.hls.error({ fatal: true, type: 'networkError', details: 'fragLoadError' });
        env.engine.bankWrongRung(3); // anything written in the seconds before the restart
        vi.advanceTimersByTime(RECONNECT_MS);
        expect(env.hls.startLoad).toHaveBeenCalledTimes(1);
        expect(env.levelWrites().at(-1)).toBe(1);
      });

      // What hls.js's error controller has done by the time the engine hears of a non-fatal reset: it read the
      // playhead, recovered (loading restarts from it) and reloaded the media, so the playhead is 0 and nothing
      // is buffered.
      const resetByHls = (env) => {
        env.hls.recoverMediaError();
        env.setTime(0);
        env.setRanges([]);
        env.lookup.levelAt.mockClear();
        env.hls.error({ fatal: false, type: 'mediaError', details: 'mediaSourceRequiresReset' });
      };
      const levelsByPosition = (env) =>
        env.lookup.levelAt.mockImplementation((s) => (s < 5 ? 0 : s < 50 ? 1 : s < 2000 ? 2 : 3));

      it('forgets it when hls.js resets the media by itself, and writes the level for where it reloads', () => {
        const env = loadedUnappended();
        levelsByPosition(env);
        env.tick(); // the playhead is at 10
        resetByHls(env);
        expect(env.lookup.levelAt).toHaveBeenCalledTimes(1);
        expect(env.lookup.levelAt).toHaveBeenCalledWith(10);
        expect(env.hls.loadLevel).toBe(1);
        expect(env.hls.recoverMediaError).toHaveBeenCalledTimes(1); // hls.js's, not the engine's
      });

      it('reloads from a seek that no time update has followed', () => {
        const env = loadedUnappended();
        levelsByPosition(env);
        env.tick();
        env.setTime(2400);
        env.seek(); // waiting for data: no timeupdate yet
        resetByHls(env);
        expect(env.lookup.levelAt).toHaveBeenCalledWith(2400);
        expect(env.hls.loadLevel).toBe(3);
      });

      it('reloads from a seek made while paused', () => {
        const env = loadedUnappended();
        levelsByPosition(env);
        env.tick();
        env.setPaused(true);
        env.setTime(2400);
        env.seek();
        resetByHls(env);
        expect(env.lookup.levelAt).toHaveBeenCalledWith(2400);
        expect(env.hls.loadLevel).toBe(3);
      });

      it('takes hls.js’s position as it is when loading had stopped, so it does not restart', () => {
        const env = loadedUnappended();
        levelsByPosition(env);
        env.hls.startLoad(30);
        env.hls.stopLoad();
        env.setTime(2400);
        resetByHls(env); // loading had not started: hls.js reloads nothing, `startPosition` stays 30
        expect(env.hls.startLoad).toHaveBeenCalledTimes(1);
        expect(env.lookup.levelAt).toHaveBeenCalledWith(30);
      });

      it('recovers a fatal reset itself, with the level held at the playhead', () => {
        const env = loadedUnappended();
        env.lookup.levelAt.mockClear();
        env.hls.error({ fatal: true, type: 'mediaError', details: 'mediaSourceRequiresReset' });
        expect(env.lookup.levelAt).toHaveBeenCalledTimes(1);
        expect(env.lookup.levelAt).toHaveBeenCalledWith(10);
        expect(env.hls.recoverMediaError).toHaveBeenCalledTimes(1);
        env.seek();
        expect(env.lookup.levelAt).toHaveBeenLastCalledWith(40); // the dropped fragment is not counted
      });

      it('forgets it when the bank turns off and on again', () => {
        const env = loadedUnappended();
        env.engine.bankRelease(2);
        env.engine.bankOn(env.lookup);
        env.hls.bufferFlushed({ start: 100, end: Infinity });
        expect(env.levelWrites().at(-1)).toBe(1);
      });
    });

    it('rewrites the level for the new buffered end', () => {
      // A re-bank's flush is pending (floor raised, flush asked for) when the bank turns on.
      const env = world({ first: 1 });
      rebankReady(env);
      env.tick();
      expect(env.hls.trigger).toHaveBeenCalledTimes(1);
      env.lookup.levelAt.mockImplementation((s) => (s === 300 ? 3 : 1));
      env.engine.bankOn(env.lookup);
      env.log.length = 0;
      env.setRanges([[0, 300]]); // the flush from 300 s has finished
      env.hls.bufferFlushed({ start: 300, end: Infinity });
      expect(env.lookup.levelAt).toHaveBeenLastCalledWith(300);
      expect(env.log).toEqual([{ write: 'loadLevel', value: 3, floor: 816_000 }]);
    });

    it('writes nothing while the bank is off', () => {
      const env = world();
      env.hls.bufferFlushed({ start: 0, end: Infinity });
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
      mediaError(env.hls);
      expect(env.lookup.levelAt).toHaveBeenLastCalledWith(120);
      expect(env.log).toEqual([{ write: 'loadLevel', value: 1, floor: 0 }, { write: 'recover' }]);
    });

    it('is recovered without a level written once the bank is off', () => {
      const env = world();
      env.hls.recoverMediaError.mockImplementation(() => env.log.push({ write: 'recover' }));
      env.engine.bankOn(env.lookup);
      env.engine.bankRelease(1);
      env.log.length = 0;
      mediaError(env.hls);
      expect(env.log).toEqual([{ write: 'recover' }]);
    });
  });

  describe('a request the bank fails as wrong-rung', () => {
    const wrongRung = { code: 503, text: BANK_RUNG_TEXT };

    it('sets the held rung before the error is raised', () => {
      const env = world();
      env.engine.bankOn(env.lookup);
      env.log.length = 0;
      env.engine.bankWrongRung(1);
      expect(env.log).toEqual([{ write: 'loadLevel', value: 1, floor: 0 }]);
    });

    it('has the error’s count taken back', () => {
      const env = world();
      env.engine.bankOn(env.lookup);
      failFragment(env.hls, 'fragLoadError', media(9, 90, 2), wrongRung);
      expect(env.hls.levels[2].fragmentError).toBe(0);
    });

    it('lets a following real 503 be retried after 1 s', () => {
      const env = world();
      env.engine.bankOn(env.lookup);
      const frag = media(9, 90, 2);
      failFragment(env.hls, 'fragLoadError', frag, wrongRung);
      expect(retryDelayMs(env.hls)).toBe(1000); // hls.js's delay doubles with each count
      failFragment(env.hls, 'fragLoadError', frag, { code: 503, text: 'Service Unavailable' });
      expect(env.hls.levels[2].fragmentError).toBe(1);
    });

    it('leaves a count that is already 0 at 0', () => {
      const env = world();
      env.hls.error({
        fatal: false,
        type: 'networkError',
        details: 'fragLoadError',
        frag: media(1, 10, 2),
        response: wrongRung,
      });
      expect(env.hls.levels[2].fragmentError).toBe(0);
    });

    it('leaves the other levels’ counts alone', () => {
      const env = world();
      env.hls.levels[0].fragmentError = 2;
      failFragment(env.hls, 'fragLoadError', media(1, 10, 2), wrongRung);
      expect(env.hls.levels[0].fragmentError).toBe(2);
    });

    it('takes nothing back from an error with no fragment', () => {
      const env = world();
      env.hls.levels[0].fragmentError = 2;
      env.hls.error({
        fatal: false,
        type: 'networkError',
        details: 'fragLoadError',
        frag: undefined,
        response: wrongRung,
      });
      expect(env.hls.levels[0].fragmentError).toBe(2);
    });

    it('takes nothing back when the answer is not a 503', () => {
      const env = world();
      failFragment(env.hls, 'fragLoadError', media(1, 10, 2), {
        code: 404,
        text: BANK_RUNG_TEXT,
      });
      expect(env.hls.levels[2].fragmentError).toBe(1);
    });

    it('is not fragment trouble', () => {
      const env = world();
      env.engine.bankOn(env.lookup);
      failFragment(env.hls, 'fragLoadError', media(9, 90, 1), wrongRung);
      expect(env.handlers.onNetworkError).not.toHaveBeenCalled();
    });

    it('leaves the floor hold standing at the release', () => {
      const env = world({ first: 1 });
      engageHold(env);
      env.engine.bankOn(env.lookup);
      failFragment(env.hls, 'fragLoadError', media(9, 90, 1), wrongRung);
      env.log.length = 0;
      env.engine.bankRelease(2);
      expect(env.log).toEqual([
        { write: 'loadLevel', value: -1, floor: 816_000 },
        { write: 'floor', value: 816_000 },
        { write: 'cap', value: 2 },
      ]);
    });

    it('writes nothing while the bank is off', () => {
      const env = world();
      env.engine.bankWrongRung(1);
      expect(env.log).toEqual([]);
    });
  });

  describe('the rules are suspended', () => {
    it('does not re-bank: the floor stays and no film is flushed', () => {
      const env = world({ first: 1 });
      rebankReady(env);
      env.engine.bankOn(env.lookup);
      env.log.length = 0;
      env.tick();
      expect(env.hls.trigger).not.toHaveBeenCalled();
      expect(env.log.filter((e) => e.write === 'floor')).toEqual([]);
    });

    it('does not read the floor hold when a timeout and a stall come', () => {
      const env = world({ first: 1 });
      engageHold(env);
      env.engine.bankOn(env.lookup);
      env.log.length = 0;
      timeout(env.hls);
      env.video.dispatchEvent(new Event('waiting'));
      expect(env.log).toEqual([]);
    });

    it('does not move the cap when the ceiling falls', () => {
      const env = world({ first: 2 });
      env.hls.fetchLevel(2);
      for (let i = 0; i < 3; i += 1) env.hls.fragLoaded(stats(FAST));
      env.setRanges([[0, 460]]);
      env.advance(HEALTHY_MS);
      env.hls.fragLoaded(stats(FAST)); // the headroom is earned: the cap climbs to 720p
      expect(env.hls.autoLevelCapping).toBe(3);
      env.engine.bankOn(env.lookup);
      timeout(env.hls); // trouble: the ceiling falls back to 480p
      env.log.length = 0;
      env.tick();
      expect(env.log).toEqual([]);
    });

    it('does not clear the marks of levels it is not fetching', () => {
      const env = world({ first: 1 });
      env.hls.fetchLevel(1);
      env.engine.bankOn(env.lookup);
      env.hls.levels[0].fragmentError = 2;
      env.hls.fragLoaded(stats(FAST));
      env.advance(HEALTHY_MS);
      env.hls.fragLoaded(stats(FAST));
      expect(env.hls.levels[0].fragmentError).toBe(2);
    });

    it('clears them with the bank off, as the control', () => {
      const env = world({ first: 1 });
      env.hls.fetchLevel(1);
      env.hls.levels[0].fragmentError = 2;
      env.hls.fragLoaded(stats(FAST));
      env.advance(HEALTHY_MS);
      env.hls.fragLoaded(stats(FAST));
      expect(env.hls.levels[0].fragmentError).toBe(0);
    });

    it('still reports the level hls.js fetches', () => {
      const env = world();
      env.handlers.onFetching = vi.fn();
      env.engine.bankOn(env.lookup);
      env.hls.fetchLevel(2);
      expect(env.handlers.onFetching).toHaveBeenCalledWith('480p');
    });

    it('counts a fragment timeout as trouble', () => {
      const env = world({ first: 1 });
      engageHold(env);
      env.engine.bankOn(env.lookup);
      timeout(env.hls);
      expect(env.handlers.onNetworkError).toHaveBeenCalledWith({ timeout: true });
      env.log.length = 0;
      env.engine.bankRelease(2);
      // The trouble let the floor hold go: the release writes the floor it had, then the floor with it gone.
      expect(env.log).toEqual([
        { write: 'loadLevel', value: -1, floor: 816_000 },
        { write: 'floor', value: 816_000 },
        { write: 'cap', value: 2 },
        { write: 'floor', value: 0 },
      ]);
    });

    it('lowers the gate for the same timeout when the bank is off', () => {
      const env = world({ first: 1 });
      gateAhead(env);
      env.log.length = 0;
      timeout(env.hls);
      expect(env.log).toContainEqual({ write: 'cap', value: 1 });
    });
  });

  describe('release', () => {
    it('puts hls.js back on Auto, writes the floor the hold had, and writes the cap again', () => {
      const env = world({ first: 1 });
      engageHold(env);
      env.engine.bankOn(env.lookup);
      env.log.length = 0;
      env.engine.bankRelease(2);
      expect(env.log).toEqual([
        { write: 'loadLevel', value: -1, floor: 816_000 },
        { write: 'floor', value: 816_000 }, // written again though it did not change
        { write: 'cap', value: 2 }, // above the 360p level of the floor
      ]);
    });

    it('with a kept 240p gate and 480p fetched caps Auto at 480p', () => {
      const env = world({ first: 0 });
      env.hls.fetchLevel(0);
      expect(env.hls.autoLevelCapping).toBe(0);
      env.engine.bankOn(env.lookup);
      env.log.length = 0;
      env.engine.bankRelease(2);
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
      env.engine.bankRelease(1);
      expect(env.hls.autoLevelCapping).toBe(1);
      env.advance(HEALTHY_MS - 1);
      env.hls.fragLoaded(stats(FAST));
      expect(env.hls.autoLevelCapping).toBe(1);
      env.advance(1);
      env.hls.fragLoaded(stats(FAST));
      expect(env.hls.autoLevelCapping).toBe(2);
    });

    it('starts the gate at the level the bank fetched, not the one hls.js was last put on', () => {
      const env = world({ first: 0 });
      env.engine.bankOn(env.lookup); // hls.js is on level 1, the held rung
      expect(env.hls.loadLevel).toBe(1);
      env.engine.bankRelease(0);
      expect(env.hls.autoLevelCapping).toBe(0);
    });

    it('gives every rule back', () => {
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

    it.each([undefined, null, NaN, 1.5, '2'])(
      'refuses %s as the level the bank fetched',
      (level) => {
        const env = world();
        env.engine.bankOn(env.lookup);
        env.log.length = 0;
        expect(() => env.engine.bankRelease(level)).toThrow('bankRelease needs the level');
        expect(env.log).toEqual([]); // still on: a valid release works
        env.engine.bankRelease(2);
        expect(env.levelWrites()).toEqual([-1]);
      },
    );

    it('does nothing when the bank is not on', () => {
      const env = world();
      env.engine.bankRelease(2);
      expect(env.log).toEqual([]);
    });

    it('on a pinned quality only gives Auto back', () => {
      const pinned = world({ auto: false });
      pinned.engine.bankOn(pinned.lookup);
      pinned.log.length = 0;
      pinned.engine.bankRelease(2);
      expect(pinned.levelWrites()).toEqual([-1]);
      expect(pinned.log.some((e) => e.write === 'cap')).toBe(false);
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
