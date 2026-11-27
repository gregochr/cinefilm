import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FakeHls } from '../test/fakeHls.js';
import {
  BUFFER_BUDGET_BYTES,
  HOLD_CHECK_MS,
  IN_FLIGHT_MIN_AGE_MS,
  MAX_BUFFER_AHEAD_S,
  MIN_BUFFER_AHEAD_S,
  RECONNECT_MS,
  hlsConfig,
  retryNoAnswer,
  startHlsJs,
} from './hlsjsEngine.js';
import { HEALTHY_MS } from './stepGate.js';
import { rememberSpeed, resetThroughputState } from './throughput.js';

beforeEach(() => {
  FakeHls.reset();
  vi.useFakeTimers();
});
// Every engine `start()` makes: each holds a window `online` listener, video listeners and timers, released here.
const engines = [];
afterEach(() => {
  engines.splice(0).forEach((engine) => engine.destroy()); // destroy is safe to call twice
  vi.useRealTimers();
});

// A ladder the champagne ceiling (champagne.js) leaves alone: every height is 480 lines or less, so Auto may
// reach the top by the step gate alone. The ids are labels here; champagneCeiling is tested on real ladders below.
const renditions = [
  { id: 'original', height: 480, bitrate: 15000000, copy: true },
  { id: '720p', height: 400, bitrate: 2500000, copy: false },
  { id: '480p', height: 300, bitrate: 1000000, copy: false },
  { id: '360p', height: 200, bitrate: 600000, copy: false },
];
// hls.js orders levels lowest bitrate first.
const levels = [
  { height: 200, bitrate: 600000 },
  { height: 300, bitrate: 1000000 },
  { height: 400, bitrate: 2500000 },
  { height: 480, bitrate: 15000000 },
];
const session = { masterUrl: '/api/sessions/abc/master.m3u8', renditions };
const stats = { loaded: 125_000, loading: { start: 0, first: 0, end: 100 } }; // 10,000 kbit/s

const baseHandlers = () => ({
  onLevel: vi.fn(),
  onThroughput: vi.fn(),
  onReconnecting: vi.fn(),
  onSessionLost: vi.fn(),
  onFatal: vi.fn(),
});

function start(over = {}) {
  const video = document.createElement('video');
  const handlers = over.handlers ?? baseHandlers();
  let t = 0;
  const engine = startHlsJs({
    Hls: FakeHls,
    video,
    session,
    auto: true,
    startSeconds: 0,
    handlers,
    now: () => t,
    ...over,
  });
  engines.push(engine);
  return { engine, video, handlers, hls: FakeHls.last, advance: (ms) => (t += ms) };
}

describe('hls.js settings', () => {
  it('buffers 120 to 480 s ahead within 100 MB and keeps 30 s behind', () => {
    const config = hlsConfig(0);
    expect(MIN_BUFFER_AHEAD_S).toBe(120);
    expect(MAX_BUFFER_AHEAD_S).toBe(480);
    expect(BUFFER_BUDGET_BYTES).toBe(100_000_000);
    expect(config.maxBufferLength).toBe(MIN_BUFFER_AHEAD_S);
    expect(config.maxMaxBufferLength).toBe(MAX_BUFFER_AHEAD_S);
    expect(config.maxBufferSize).toBe(BUFFER_BUDGET_BYTES);
    expect(config.backBufferLength).toBe(30);
    expect(config.preferManagedMediaSource).toBe(false);
  });

  it('keeps about 8 min ahead on the low levels, less on heavy ones, never under 120 s', () => {
    const { maxBufferLength, maxMaxBufferLength, maxBufferSize } = hlsConfig(0);
    // hls.js's own rule (getMaxBufferLength), written out here so a change to any number shows its effect.
    const depth = (bitsPerSecond) =>
      Math.floor(
        Math.min(
          Math.max((8 * maxBufferSize) / bitsPerSecond, maxBufferLength),
          maxMaxBufferLength,
        ),
      );
    expect(depth(364_000)).toBe(480);
    expect(depth(816_000)).toBe(480);
    expect(depth(1_328_000)).toBe(480);
    expect(depth(3_128_000)).toBe(255);
    expect(depth(6_128_000)).toBe(130);
    expect(depth(22_628_000)).toBe(120);
  });

  it('retries fragments, playlists and the manifest with backoff on errors and timeouts', () => {
    const config = hlsConfig(0);
    for (const key of ['fragLoadPolicy', 'playlistLoadPolicy', 'manifestLoadPolicy']) {
      const { errorRetry, timeoutRetry } = config[key].default;
      expect(errorRetry.maxNumRetry).toBeGreaterThanOrEqual(4);
      expect(timeoutRetry.maxNumRetry).toBeGreaterThanOrEqual(4);
      expect(errorRetry).toMatchObject({
        retryDelayMs: 1000,
        maxRetryDelayMs: 8000,
        backoff: 'exponential',
      });
    }
    // The 503 + Retry-After: 1 the encoder sends while it catches up must be retried, so segments get the most.
    expect(config.fragLoadPolicy.default.errorRetry.maxNumRetry).toBe(8);
  });

  it('gives a segment 120 s and keeps the playlists’ and the manifest’s 20 s', () => {
    const config = hlsConfig(0);
    expect(config.fragLoadPolicy.default.maxLoadTimeMs).toBe(120000);
    expect(config.fragLoadPolicy.default.maxTimeToFirstByteMs).toBe(10000);
    expect(config.playlistLoadPolicy.default.maxLoadTimeMs).toBe(20000);
    expect(config.manifestLoadPolicy.default.maxLoadTimeMs).toBe(20000);
  });

  it('lets a penalised rung be chosen again after a minute', () => {
    expect(hlsConfig(0).errorPenaltyExpireMs).toBe(60000);
  });

  it('gives every errorRetry the no-answer rule', () => {
    const config = hlsConfig(0);
    for (const key of ['fragLoadPolicy', 'playlistLoadPolicy', 'manifestLoadPolicy']) {
      expect(config[key].default.errorRetry.shouldRetry).toBe(retryNoAnswer);
    }
    expect(config.fragLoadPolicy.default.timeoutRetry.shouldRetry).toBeUndefined();
  });

  it('plays segments through the no-progress loader built on the default loader', () => {
    const { hls } = start();
    expect(hls.config.fLoader).toBeTypeOf('function');
    const loader = new hls.config.fLoader({});
    expect(loader.inner).toBeInstanceOf(FakeHls.DefaultConfig.loader);
    expect(hlsConfig(0).fLoader).toBeUndefined(); // set where Hls is at hand
  });

  it('starts at the requested position, or lets hls.js choose when it is 0', () => {
    expect(hlsConfig(93.5).startPosition).toBe(93.5);
    expect(hlsConfig(0).startPosition).toBe(-1);
  });

  it('loads the master playlist into the element', () => {
    const { hls, video } = start({ startSeconds: 12 });
    expect(hls.config.startPosition).toBe(12);
    expect(hls.loadSource).toHaveBeenCalledWith('/api/sessions/abc/master.m3u8');
    expect(hls.attachMedia).toHaveBeenCalledWith(video);
  });
});

describe('Auto step-up gating', () => {
  it('caps Auto at the level the master listed first', () => {
    const { hls } = start();
    hls.manifest(levels, 1);
    expect(hls.autoLevelCapping).toBe(1);
  });

  it('does not cap a pinned quality', () => {
    const { hls } = start({ auto: false });
    hls.manifest(levels, 0);
    expect(hls.autoLevelCapping).toBe(-1);
  });

  it('steps up one level only after 60 s of loaded fragments: 59,999 ms no, 60,000 ms yes', () => {
    const { hls, advance } = start();
    hls.manifest(levels, 1);
    advance(HEALTHY_MS - 1);
    hls.fragLoaded(stats);
    expect(hls.autoLevelCapping).toBe(1);
    advance(1);
    hls.fragLoaded(stats);
    expect(hls.autoLevelCapping).toBe(2);
  });

  it('goes up one level at a time, never two', () => {
    const { hls, advance } = start();
    hls.manifest(levels, 0);
    advance(HEALTHY_MS * 3);
    hls.fragLoaded(stats);
    expect(hls.autoLevelCapping).toBe(1);
    hls.fragLoaded(stats);
    expect(hls.autoLevelCapping).toBe(1);
  });

  it('follows a step down at once', () => {
    const { hls } = start();
    hls.manifest(levels, 3);
    hls.fetchLevel(3);
    hls.fetchLevel(1);
    expect(hls.autoLevelCapping).toBe(1);
  });

  it('follows the level being fetched, not the one played: the played step restarts nothing', () => {
    const { hls, advance } = start();
    hls.manifest(levels, 3);
    advance(10000);
    hls.fetchLevel(1);
    expect(hls.autoLevelCapping).toBe(1);
    advance(HEALTHY_MS - 10000 - 1);
    hls.switchTo(1); // the playhead reaches it much later: this must not restart the clock
    hls.fragLoaded(stats);
    expect(hls.autoLevelCapping).toBe(1);
    advance(10000);
    // 60 s after the fetched step down (at 10 s), not after the played one
    advance(1);
    hls.fragLoaded(stats);
    expect(hls.autoLevelCapping).toBe(2);
  });

  it('a played level change leaves the cap alone', () => {
    const { hls } = start();
    hls.manifest(levels, 3);
    hls.switchTo(1);
    expect(hls.autoLevelCapping).toBe(3);
  });

  it('a stall (waiting) restarts the healthy minute', () => {
    const { hls, video, advance } = start();
    hls.manifest(levels, 1);
    advance(HEALTHY_MS - 1);
    video.dispatchEvent(new Event('waiting'));
    advance(HEALTHY_MS - 1);
    hls.fragLoaded(stats);
    expect(hls.autoLevelCapping).toBe(1);
    advance(1);
    hls.fragLoaded(stats);
    expect(hls.autoLevelCapping).toBe(2);
  });

  it('does not gate a pinned quality on fragments', () => {
    const { hls, advance } = start({ auto: false });
    hls.manifest(levels, 0);
    advance(HEALTHY_MS * 2);
    hls.fragLoaded(stats);
    expect(hls.autoLevelCapping).toBe(-1);
  });
});

describe('level changes', () => {
  it('reports the rung of each level, the first one as the initial level', () => {
    const { hls, handlers } = start();
    hls.manifest(levels, 3);
    hls.switchTo(3);
    hls.switchTo(2);
    expect(handlers.onLevel.mock.calls).toEqual([
      ['original', { initial: true }],
      ['720p', { initial: false }],
    ]);
  });

  it('tells onFetching the rung of each level hls.js starts to fetch', () => {
    const onFetching = vi.fn();
    const { hls, handlers } = start({ handlers: { ...baseHandlers(), onFetching } });
    hls.manifest(levels, 3);
    hls.fetchLevel(2);
    hls.fetchLevel(0);
    expect(onFetching.mock.calls).toEqual([['720p'], ['360p']]);
    expect(handlers.onLevel).not.toHaveBeenCalled();
  });

  it('tells onFetching of a fetched level in a pinned quality, and never touches the cap', () => {
    const onFetching = vi.fn();
    const { hls } = start({ auto: false, handlers: { ...baseHandlers(), onFetching } });
    hls.manifest(levels, 3);
    hls.fetchLevel(1);
    expect(onFetching).toHaveBeenCalledWith('480p');
    expect(hls.autoLevelCapping).toBe(-1);
  });

  it('calls nothing for a fetched level it cannot name', () => {
    const onFetching = vi.fn();
    const { hls } = start({ handlers: { ...baseHandlers(), onFetching } });
    hls.manifest([{ height: 999, bitrate: 1 }], 0);
    hls.fetchLevel(0);
    expect(onFetching).not.toHaveBeenCalled();
    expect(hls.autoLevelCapping).toBe(0);
  });

  it('does not need an onFetching handler', () => {
    const { hls } = start();
    hls.manifest(levels, 3);
    expect(() => hls.fetchLevel(1)).not.toThrow();
  });

  it('ignores a level it cannot name', () => {
    const { hls, handlers } = start();
    hls.manifest([{ height: 999, bitrate: 1 }], 0);
    hls.switchTo(0);
    expect(handlers.onLevel).not.toHaveBeenCalled();
  });
});

describe('throughput', () => {
  it('reports each loaded fragment in kbit/s', () => {
    const { hls, handlers } = start();
    hls.manifest(levels, 3);
    hls.fragLoaded(stats);
    expect(handlers.onThroughput).toHaveBeenCalledWith(10000);
  });

  it('reads the stats from frag.stats of the real FRAG_LOADED payload, not from the payload root', () => {
    const { hls, handlers } = start();
    hls.manifest(levels, 3);
    hls.emit('hlsFragLoaded', { stats, frag: {}, part: null });
    expect(handlers.onThroughput).not.toHaveBeenCalled();
    hls.emit('hlsFragLoaded', {
      frag: { stats },
      part: null,
      payload: new ArrayBuffer(0),
      networkDetails: null,
    });
    expect(handlers.onThroughput).toHaveBeenCalledWith(10000);
  });

  it('reports nothing for a fragment with no timing', () => {
    const { hls, handlers } = start();
    hls.manifest(levels, 3);
    hls.fragLoaded({ loaded: 0, loading: { start: 0, first: 0, end: 0 } });
    expect(handlers.onThroughput).not.toHaveBeenCalled();
  });
});

describe('retryNoAnswer: a request that got no HTTP answer is retried like a 5xx', () => {
  const config = { maxNumRetry: 8 };

  it('retries status 0 below the limit and not at it', () => {
    expect(retryNoAnswer(config, 7, false, { code: 0 }, false)).toBe(true);
    expect(retryNoAnswer(config, 8, false, { code: 0 }, false)).toBe(false);
  });

  it('keeps hls.js’s own answer for an HTTP answer, a timeout and no response', () => {
    expect(retryNoAnswer(config, 0, false, { code: 404 }, false)).toBe(false);
    expect(retryNoAnswer(config, 0, false, { code: 503 }, true)).toBe(true);
    expect(retryNoAnswer(config, 0, false, { code: 503 }, false)).toBe(false);
    expect(retryNoAnswer(config, 0, true, undefined, true)).toBe(true);
    expect(retryNoAnswer(config, 0, true, undefined, false)).toBe(false);
    expect(retryNoAnswer(config, 0, true, { code: 0 }, false)).toBe(false);
    expect(retryNoAnswer(config, 0, false, undefined, false)).toBe(false);
    expect(retryNoAnswer(config, 0, false, undefined, true)).toBe(true);
  });
});

describe('a segment that timed out', () => {
  it('counts as trouble for the step gate: Auto does not step up straight after it', () => {
    const { hls, advance } = start();
    hls.manifest(levels, 1);
    advance(HEALTHY_MS - 1);
    hls.error({
      fatal: false,
      type: 'networkError',
      details: FakeHls.ErrorDetails.FRAG_LOAD_TIMEOUT,
    });
    advance(1); // the healthy minute has not run since the timeout
    hls.fragLoaded(stats);
    expect(hls.autoLevelCapping).toBe(1);
    advance(HEALTHY_MS);
    hls.fragLoaded(stats);
    expect(hls.autoLevelCapping).toBe(2);
  });

  it('is not trouble when another detail fails', () => {
    const { hls, advance } = start();
    hls.manifest(levels, 1);
    advance(HEALTHY_MS - 1);
    hls.error({
      fatal: false,
      type: 'networkError',
      details: FakeHls.ErrorDetails.FRAG_LOAD_ERROR,
      response: { code: 503 },
    });
    advance(1);
    hls.fragLoaded(stats);
    expect(hls.autoLevelCapping).toBe(2);
  });
});

describe('a dropped connection', () => {
  const networkError = { fatal: true, type: 'networkError', response: { code: 0 } };

  // The element has metadata: it reads its playhead.
  const withMetadata = (video, currentTime) => {
    Object.defineProperty(video, 'readyState', { value: 4, configurable: true });
    video.currentTime = currentTime;
  };

  it('shows Reconnecting once and restarts once, 5 s after the fatal error, at the playhead', () => {
    const { hls, video, handlers } = start();
    hls.manifest(levels, 3);
    withMetadata(video, 1234.5);
    hls.error(networkError);
    expect(handlers.onReconnecting.mock.calls).toEqual([[true]]);
    expect(hls.startLoad).not.toHaveBeenCalled();
    vi.advanceTimersByTime(RECONNECT_MS - 1);
    expect(hls.startLoad).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);
    expect(hls.startLoad).toHaveBeenCalledWith(1234.5);
    // Nothing more is scheduled, however long: hls.js's own backoff does the retrying.
    vi.advanceTimersByTime(RECONNECT_MS * 20);
    expect(hls.startLoad).toHaveBeenCalledTimes(1);
    expect(vi.getTimerCount()).toBe(0);
    expect(handlers.onReconnecting.mock.calls).toEqual([[true]]);
    // The next fatal error schedules the next restart; the notice is still the one episode.
    hls.error(networkError);
    expect(handlers.onReconnecting.mock.calls).toEqual([[true]]);
    vi.advanceTimersByTime(RECONNECT_MS);
    expect(hls.startLoad).toHaveBeenCalledTimes(2);
    hls.fragLoaded(stats);
    expect(handlers.onReconnecting.mock.calls).toEqual([[true], [false]]);
  });

  it('announces a new Reconnecting episode after the first ended', () => {
    const { hls, handlers } = start();
    hls.manifest(levels, 3);
    hls.error(networkError);
    hls.fragLoaded(stats);
    hls.error(networkError);
    expect(handlers.onReconnecting.mock.calls).toEqual([[true], [false], [true]]);
  });

  it('restarts with loadSource while hls.js has no levels at all (not even an empty list)', () => {
    const { hls } = start();
    hls.levels = undefined;
    hls.error(networkError);
    vi.advanceTimersByTime(RECONNECT_MS);
    expect(hls.loadSource).toHaveBeenCalledTimes(2);
  });

  it('removes its online and waiting listeners on destroy', () => {
    const remove = vi.spyOn(window, 'removeEventListener');
    const { engine, video } = start();
    const removeVideo = vi.spyOn(video, 'removeEventListener');
    engine.destroy();
    expect(remove).toHaveBeenCalledWith('online', expect.any(Function));
    expect(removeVideo).toHaveBeenCalledWith('waiting', expect.any(Function));
    remove.mockRestore();
  });

  it('a second fatal error while a restart is pending moves it to 5 s after the second', () => {
    const { hls } = start();
    hls.manifest(levels, 3);
    hls.error(networkError);
    vi.advanceTimersByTime(3000);
    hls.error(networkError);
    vi.advanceTimersByTime(RECONNECT_MS - 1);
    expect(hls.startLoad).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);
    expect(hls.startLoad).toHaveBeenCalledTimes(1);
  });

  it('reloads the master when the manifest never arrived, once per fatal manifestLoadError', () => {
    const { hls } = start();
    const fatal = {
      fatal: true,
      type: 'networkError',
      details: FakeHls.ErrorDetails.MANIFEST_LOAD_ERROR,
      response: { code: 0 },
    };
    hls.error(fatal);
    vi.advanceTimersByTime(RECONNECT_MS);
    expect(hls.loadSource).toHaveBeenCalledTimes(2);
    vi.advanceTimersByTime(RECONNECT_MS * 5);
    expect(hls.loadSource).toHaveBeenCalledTimes(2);
    expect(hls.startLoad).not.toHaveBeenCalled();
    hls.error(fatal);
    vi.advanceTimersByTime(RECONNECT_MS);
    expect(hls.loadSource).toHaveBeenCalledTimes(3);
  });

  it('a fragment that loads cancels a pending restart', () => {
    const { hls, handlers } = start();
    hls.manifest(levels, 3);
    hls.error(networkError);
    hls.fragLoaded(stats);
    expect(handlers.onReconnecting.mock.calls).toEqual([[true], [false]]);
    expect(vi.getTimerCount()).toBe(0);
    vi.advanceTimersByTime(RECONNECT_MS * 2);
    expect(hls.startLoad).not.toHaveBeenCalled();
  });

  it('restarts at the start position before the first frame: the film start, or -1 from 0:00', () => {
    const resumed = start({ startSeconds: 93.5 });
    resumed.hls.manifest(levels, 3);
    resumed.hls.error(networkError);
    vi.advanceTimersByTime(RECONNECT_MS);
    expect(resumed.hls.startLoad).toHaveBeenCalledWith(93.5);

    const fresh = start({ startSeconds: 0 });
    fresh.hls.manifest(levels, 3);
    fresh.hls.error(networkError);
    vi.advanceTimersByTime(RECONNECT_MS);
    expect(fresh.hls.startLoad).toHaveBeenCalledWith(-1);
  });

  it('restarts at the playhead once the element has metadata, even at 0:00', () => {
    const { hls, video } = start({ startSeconds: 93.5 });
    hls.manifest(levels, 3);
    withMetadata(video, 0);
    hls.error(networkError);
    vi.advanceTimersByTime(RECONNECT_MS);
    expect(hls.startLoad).toHaveBeenCalledWith(0);
  });

  it('the online event makes a pending restart at once, and not again at 5 s', () => {
    const { hls } = start();
    hls.manifest(levels, 3);
    hls.error(networkError);
    window.dispatchEvent(new Event('online'));
    expect(hls.startLoad).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(RECONNECT_MS * 2);
    expect(hls.startLoad).toHaveBeenCalledTimes(1);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('the online event with no restart pending does nothing, while healthy or reconnecting after one', () => {
    const { hls } = start();
    hls.manifest(levels, 3);
    window.dispatchEvent(new Event('online'));
    expect(hls.startLoad).not.toHaveBeenCalled();
    hls.error(networkError);
    window.dispatchEvent(new Event('online'));
    expect(hls.startLoad).toHaveBeenCalledTimes(1);
    window.dispatchEvent(new Event('online')); // the restart was made; hls.js's backoff runs
    expect(hls.startLoad).toHaveBeenCalledTimes(1);
  });

  it('the online event restarts with loadSource while there are no levels yet', () => {
    const { hls } = start();
    hls.error(networkError);
    window.dispatchEvent(new Event('online'));
    expect(hls.loadSource).toHaveBeenCalledTimes(2);
    expect(hls.startLoad).not.toHaveBeenCalled();
  });

  it('the online event after destroy() does nothing', () => {
    const { engine, hls } = start();
    hls.manifest(levels, 3);
    hls.error(networkError);
    engine.destroy();
    window.dispatchEvent(new Event('online'));
    expect(hls.startLoad).not.toHaveBeenCalled();
  });

  it('shows no Reconnecting for an error hls.js marks offline: it waits and resumes by itself', () => {
    const { hls, handlers } = start();
    hls.manifest(levels, 3);
    hls.error({
      fatal: false,
      type: 'networkError',
      details: FakeHls.ErrorDetails.FRAG_LOAD_ERROR,
      reason: 'offline',
      response: { code: 0 },
    });
    expect(handlers.onReconnecting).not.toHaveBeenCalled();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('does not announce Reconnecting for a fragment that loads normally', () => {
    const { hls, handlers } = start();
    hls.manifest(levels, 3);
    hls.fragLoaded(stats);
    expect(handlers.onReconnecting).not.toHaveBeenCalled();
  });

  it('does nothing for a non-fatal error such as a 503 hls.js is already retrying', () => {
    const { hls, handlers } = start();
    hls.manifest(levels, 3);
    hls.error({ fatal: false, type: 'networkError', response: { code: 503 } });
    expect(handlers.onReconnecting).not.toHaveBeenCalled();
    expect(handlers.onSessionLost).not.toHaveBeenCalled();
    expect(vi.getTimerCount()).toBe(0);
  });
});

describe('an expired session', () => {
  it('asks for a new session on a 404, once, without reconnecting', () => {
    const { hls, handlers } = start();
    hls.manifest(levels, 3);
    hls.error({ fatal: true, type: 'networkError', response: { code: 404 } });
    hls.error({ fatal: true, type: 'networkError', response: { code: 404 } });
    expect(handlers.onSessionLost).toHaveBeenCalledTimes(1);
    expect(handlers.onReconnecting).not.toHaveBeenCalled();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('treats a non-fatal 404 the same way', () => {
    const { hls, handlers } = start();
    hls.error({ fatal: false, type: 'networkError', response: { code: 404 } });
    expect(handlers.onSessionLost).toHaveBeenCalledTimes(1);
  });
});

describe('a playback Admin stopped (410)', () => {
  it.each([true, false])(
    'says so once (fatal: %s), and neither asks for a new session nor reconnects',
    (fatal) => {
      const onStopped = vi.fn();
      const { hls, handlers } = start({ handlers: { ...baseHandlers(), onStopped } });
      hls.manifest(levels, 3);
      hls.error({ fatal, type: 'networkError', response: { code: 410 } });
      hls.error({ fatal, type: 'networkError', response: { code: 410 } });
      expect(onStopped).toHaveBeenCalledTimes(1);
      expect(handlers.onSessionLost).not.toHaveBeenCalled();
      expect(handlers.onReconnecting).not.toHaveBeenCalled();
      expect(handlers.onFatal).not.toHaveBeenCalled();
      expect(vi.getTimerCount()).toBe(0);
    },
  );

  it('does not treat a 404, a 5xx, no answer or another fatal error as a stop', () => {
    const onStopped = vi.fn();
    const { hls } = start({ handlers: { ...baseHandlers(), onStopped } });
    hls.manifest(levels, 3);
    for (const code of [404, 500, 503, 502, 403]) {
      hls.error({ fatal: false, type: 'networkError', response: { code } });
    }
    hls.error({ fatal: true, type: 'networkError', details: 'manifestLoadError' });
    hls.error({ fatal: true, type: 'mediaError' });
    expect(onStopped).not.toHaveBeenCalled();
  });

  it('is harmless for a caller that does not listen for it', () => {
    const { hls, handlers } = start();
    hls.error({ fatal: true, type: 'networkError', response: { code: 410 } });
    expect(handlers.onSessionLost).not.toHaveBeenCalled();
  });
});

describe('other fatal errors', () => {
  it('recovers a media error in place', () => {
    const { hls, handlers } = start();
    hls.error({ fatal: true, type: 'mediaError' });
    expect(hls.recoverMediaError).toHaveBeenCalledTimes(1);
    expect(handlers.onFatal).not.toHaveBeenCalled();
  });

  it('gives up on anything else so the caller can direct-play', () => {
    const { hls, handlers } = start();
    hls.error({ fatal: true, type: 'otherError' });
    expect(handlers.onFatal).toHaveBeenCalledTimes(1);
  });
});

// hls.js 1.7.4 runs its own error controller before the engine hears of an error: a reset is recovered, then a
// fatal error stops loading. The engine is told of the aftermath, with the media reset (playhead 0, no data).
describe('recovery in the order hls.js runs it', () => {
  const RESET = { action: 2, flags: 24 }; // hls.js's: SendAlternateToPenaltyBox, ResetMediaSource | SwitchToSDR

  // A resumed film (asked to start at 120 s) with its playhead at 300 s and media loaded; `hls.js`'s own
  // `recoverMediaError` resets the media as the browser does.
  function playing() {
    FakeHls.errorController = true;
    const state = { time: 300, readyState: 4 };
    const env = start({ startSeconds: 120 });
    Object.defineProperty(env.video, 'currentTime', { get: () => state.time });
    Object.defineProperty(env.video, 'readyState', { get: () => state.readyState });
    env.hls.manifest(levels, 1);
    env.video.dispatchEvent(new Event('timeupdate')); // the playhead is seen at 300 s
    const recover = env.hls.recoverMediaError.getMockImplementation();
    env.hls.recoverMediaError.mockImplementation((...args) => {
      recover(...args);
      state.time = 0;
      state.readyState = 0;
    });
    env.hls.startLoad.mockClear();
    return { ...env, state };
  }

  it('starts loading again from the playhead after a fatal media error that hls.js stopped', () => {
    const { hls, handlers } = playing();
    hls.error({ fatal: true, type: 'mediaError', details: 'bufferAppendError' });
    expect(hls.recoverMediaError).toHaveBeenCalledTimes(1);
    expect(hls.loadingEnabled).toBe(true);
    expect(hls.startLoad).toHaveBeenCalledTimes(1);
    expect(hls.startLoad).toHaveBeenCalledWith(300);
    expect(handlers.onFatal).not.toHaveBeenCalled();
  });

  it('does not reset the media a second time for a fatal reset hls.js has recovered', () => {
    const { hls } = playing();
    hls.error({
      fatal: true,
      type: 'mediaError',
      details: 'mediaSourceRequiresReset',
      errorAction: RESET,
    });
    expect(hls.recoverMediaError).toHaveBeenCalledTimes(1); // hls.js's own
    expect(hls.loadingEnabled).toBe(true); // hls.js stopped it after the recovery
    expect(hls.startLoad).toHaveBeenLastCalledWith(300);
  });

  it('adds no second start when hls.js recovered with loading running', () => {
    const { hls } = playing();
    FakeHls.errorController = false; // nothing stopped loading before the engine's own recovery
    hls.error({ fatal: true, type: 'mediaError', details: 'bufferAppendError' });
    expect(hls.startLoad).toHaveBeenCalledTimes(1); // the recovery's own
  });

  it('reloads a resumed film from the playhead, not from where it was asked to start', () => {
    const { hls } = playing();
    expect(hls.config.startPosition).toBe(300);
    hls.error({ fatal: true, type: 'mediaError', details: 'bufferAppendError' });
    hls.stopLoad(); // anything that stops it before the media is attached again
    hls.mediaAttached(); // hls.js reloads from its configured start position
    expect(hls.startLoad).toHaveBeenLastCalledWith(300);
  });

  it('restarts after a fatal network error and a reset from the playhead too', () => {
    const { hls } = playing();
    hls.error({
      fatal: true,
      type: 'networkError',
      details: 'fragLoadError',
      response: { code: 0 },
    });
    hls.error({
      fatal: false,
      type: 'mediaError',
      details: 'mediaSourceRequiresReset',
      errorAction: RESET,
    });
    hls.mediaAttached();
    expect(hls.startLoad).toHaveBeenLastCalledWith(300);
    vi.advanceTimersByTime(RECONNECT_MS);
    expect(hls.startLoad).toHaveBeenLastCalledWith(300); // the media has not loaded: no readyState
  });

  it('follows a seek to the start, which hls.js reloads from the beginning', () => {
    const { hls, video, state } = playing();
    state.time = 0;
    video.dispatchEvent(new Event('seeking'));
    expect(hls.config.startPosition).toBe(-1);
  });

  it('keeps the last playhead while the media is reset and has no data', () => {
    const { hls, video, state } = playing();
    state.time = 0;
    state.readyState = 0;
    video.dispatchEvent(new Event('timeupdate'));
    expect(hls.config.startPosition).toBe(300);
  });
});

describe('destroy', () => {
  it('destroys hls.js, stops listening for stalls and cancels a pending retry', () => {
    const { engine, hls, video, handlers, advance } = start();
    hls.manifest(levels, 1);
    hls.error({ fatal: true, type: 'networkError', response: { code: 0 } });
    engine.destroy();
    expect(hls.destroy).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(RECONNECT_MS * 2);
    expect(hls.startLoad).not.toHaveBeenCalled();
    // A stall after destroy no longer restarts the clock: the gate is no longer consulted.
    advance(HEALTHY_MS);
    video.dispatchEvent(new Event('waiting'));
    expect(handlers.onReconnecting).toHaveBeenCalledTimes(1);
  });
});

describe('network errors for the health record', () => {
  function withHandler() {
    const onNetworkError = vi.fn();
    const handlers = {
      onLevel: vi.fn(),
      onThroughput: vi.fn(),
      onReconnecting: vi.fn(),
      onSessionLost: vi.fn(),
      onFatal: vi.fn(),
      onNetworkError,
    };
    const { hls } = start({ handlers });
    return { hls, onNetworkError, handlers };
  }

  it.each([
    ['with no response at all', {}],
    ['with a response code of 0', { response: { code: 0 } }],
    ['with an empty response', { response: {} }],
  ])('reports a network error %s as not a timeout', (_name, extra) => {
    const { hls, onNetworkError } = withHandler();
    hls.error({ fatal: false, type: 'networkError', details: 'fragLoadError', ...extra });
    expect(onNetworkError).toHaveBeenCalledTimes(1);
    expect(onNetworkError).toHaveBeenCalledWith({ timeout: false });
  });

  it.each(['fragLoadTimeOut', 'manifestLoadTimeOut', 'levelLoadTimeOut'])(
    'reports details %s as a timeout',
    (details) => {
      const { hls, onNetworkError } = withHandler();
      hls.error({ fatal: false, type: 'networkError', details, response: { code: 0 } });
      expect(onNetworkError).toHaveBeenCalledWith({ timeout: true });
    },
  );

  it('does not count a detail that merely contains TimeOut: only loads that failed or ran out of time count', () => {
    const { hls, onNetworkError } = withHandler();
    hls.error({ fatal: false, type: 'networkError', details: 'TimeOutLater' });
    expect(onNetworkError).not.toHaveBeenCalled();
  });

  it('does not count a network error with no details', () => {
    const { hls, onNetworkError } = withHandler();
    hls.error({ fatal: false, type: 'networkError' });
    expect(onNetworkError).not.toHaveBeenCalled();
  });

  it.each(['fragLoadError', 'levelLoadError', 'manifestLoadError'])(
    'counts %s with no status as a network error',
    (details) => {
      const { hls, onNetworkError } = withHandler();
      hls.error({ fatal: false, type: 'networkError', details });
      expect(onNetworkError.mock.calls).toEqual([[{ timeout: false }]]);
    },
  );

  it.each(['fragLoadTimeOut', 'levelLoadTimeOut'])('counts %s as a timeout', (details) => {
    const { hls, onNetworkError } = withHandler();
    hls.error({ fatal: false, type: 'networkError', details });
    expect(onNetworkError.mock.calls).toEqual([[{ timeout: true }]]);
  });

  it.each(['aborted', 'manifestParsingError', 'levelParsingError', 'levelEmptyError'])(
    'does not count %s: a load aborted on purpose, or a playlist that arrived but could not be read',
    (details) => {
      const { hls, onNetworkError } = withHandler();
      hls.error({ fatal: false, type: 'networkError', details });
      expect(onNetworkError).not.toHaveBeenCalled();
    },
  );

  it.each([404, 503, 500, 403])('does not report an error that has an HTTP status (%i)', (code) => {
    const { hls, onNetworkError } = withHandler();
    hls.error({ fatal: false, type: 'networkError', details: 'fragLoadError', response: { code } });
    expect(onNetworkError).not.toHaveBeenCalled();
  });

  it('does not report an error that is not a network error', () => {
    const { hls, onNetworkError } = withHandler();
    hls.error({ fatal: true, type: 'mediaError', details: 'bufferStalledError' });
    hls.error({ fatal: true, type: 'otherError', details: 'fragLoadTimeOut' });
    expect(onNetworkError).not.toHaveBeenCalled();
  });

  it('reports first, so a fatal network error is still counted and still reconnects', () => {
    const { hls, onNetworkError, handlers } = withHandler();
    hls.error({
      fatal: true,
      type: 'networkError',
      details: 'manifestLoadError',
      response: { code: 0 },
    });
    expect(onNetworkError).toHaveBeenCalledTimes(1);
    expect(handlers.onReconnecting).toHaveBeenCalledWith(true);
  });

  it('works without the handler (the shared fixture has only the five)', () => {
    const { hls, handlers } = start();
    expect(() =>
      hls.error({
        fatal: true,
        type: 'networkError',
        details: 'fragLoadTimeOut',
        response: { code: 0 },
      }),
    ).not.toThrow();
    expect(handlers.onReconnecting).toHaveBeenCalledWith(true);
  });
});

describe('the floor hold: a deep buffer keeps Auto at 360p or above', () => {
  // The 360p variant's BANDWIDTH: Rung.peakBandwidth(), 600k * 1.2 + 96k audio.
  const PEAK_360 = 816000;
  const holdRenditions = [
    { id: 'original', height: 820, bitrate: 15000000, copy: true },
    { id: '720p', height: 720, bitrate: 2500000, copy: false },
    { id: '480p', height: 480, bitrate: 1000000, copy: false },
    { id: '360p', height: 360, bitrate: PEAK_360, copy: false },
    { id: '240p', height: 240, bitrate: 350000, copy: false },
  ];
  const l240 = { height: 240, bitrate: 350000 };
  const l360 = { height: 360, bitrate: PEAK_360 };
  const l480 = { height: 480, bitrate: 1000000 };
  const l720 = { height: 720, bitrate: 2500000 };
  const ladder = [l240, l360, l480, l720];

  // A <video> with `ahead` seconds buffered from a playhead at 0, which the test moves.
  function holdStart(over = {}) {
    const video = over.video ?? document.createElement('video');
    const state = { ahead: 0, ranges: null };
    Object.defineProperty(video, 'buffered', {
      get: () => {
        const ranges = state.ranges ?? (state.ahead > 0 ? [[0, state.ahead]] : []);
        return { length: ranges.length, start: (i) => ranges[i][0], end: (i) => ranges[i][1] };
      },
    });
    const started = start({
      session: { masterUrl: session.masterUrl, renditions: holdRenditions },
      ...over,
      video,
    });
    return {
      ...started,
      video,
      setAhead: (s) => (state.ahead = s),
      setRanges: (r) => (state.ranges = r),
    };
  }

  const timeout = { fatal: false, type: 'networkError', details: 'fragLoadTimeOut' };

  it('stays at 0 while level 0 is fetched, and engages once level 1 is fetched', () => {
    const { hls, video, setAhead, advance } = holdStart();
    hls.manifest(ladder, 2);
    hls.fetchLevel(0);
    setAhead(480);
    advance(HEALTHY_MS * 2);
    hls.fragLoaded(stats); // the cap rises to 1 while 240p is fetched
    hls.fragLoaded(stats);
    expect(hls.autoLevelCapping).toBe(1);
    video.dispatchEvent(new Event('timeupdate'));
    expect(hls.config.minAutoBitrate).toBe(0);
    hls.fetchLevel(1);
    expect(hls.config.minAutoBitrate).toBe(PEAK_360);
  });

  it('takes the floor off at once on a fragment timeout and sets it again after the next fragment', () => {
    const { hls, video, setAhead } = holdStart();
    hls.manifest(ladder, 3);
    hls.fetchLevel(3);
    hls.fragLoaded(stats);
    setAhead(480);
    video.dispatchEvent(new Event('timeupdate'));
    expect(hls.config.minAutoBitrate).toBe(PEAK_360);
    hls.error(timeout);
    expect(hls.config.minAutoBitrate).toBe(0);
    video.dispatchEvent(new Event('timeupdate'));
    video.dispatchEvent(new Event('timeupdate'));
    expect(hls.config.minAutoBitrate).toBe(0);
    hls.fragLoaded(stats);
    expect(hls.config.minAutoBitrate).toBe(PEAK_360);
  });

  it('gives 0 and does not throw when the level at the hold index has gone', () => {
    const { hls, video, setAhead } = holdStart();
    hls.manifest(ladder, 3);
    hls.fetchLevel(3);
    hls.fragLoaded(stats);
    hls.levels = [l240];
    setAhead(480);
    expect(() => video.dispatchEvent(new Event('timeupdate'))).not.toThrow();
    expect(hls.config.minAutoBitrate).toBe(0);
  });

  it('is not released by a 0.3 s hole in the buffer, nor by a waiting at that hole', () => {
    const { hls, video, setAhead, setRanges } = holdStart();
    hls.manifest(ladder, 3);
    hls.fetchLevel(3);
    hls.fragLoaded(stats);
    setAhead(500);
    video.dispatchEvent(new Event('timeupdate'));
    expect(hls.config.minAutoBitrate).toBe(PEAK_360);
    setRanges([
      [0, 10],
      [10.3, 500],
    ]);
    video.currentTime = 10;
    video.dispatchEvent(new Event('timeupdate'));
    video.dispatchEvent(new Event('waiting'));
    expect(hls.config.minAutoBitrate).toBe(PEAK_360);
  });

  it('raises the minimum to the 360p level at 240 s ahead, on timeupdate', () => {
    const { hls, video, setAhead } = holdStart();
    hls.manifest(ladder, 3);
    hls.fetchLevel(3);
    hls.fragLoaded(stats);
    expect(hls.config.minAutoBitrate).toBe(0);
    setAhead(239);
    video.dispatchEvent(new Event('timeupdate'));
    expect(hls.config.minAutoBitrate).toBe(0);
    setAhead(240);
    video.dispatchEvent(new Event('timeupdate'));
    expect(hls.config.minAutoBitrate).toBe(PEAK_360);
  });

  it('keeps it down to 180 s ahead and drops it at 179 s, from timeupdate alone', () => {
    const { hls, video, setAhead } = holdStart();
    hls.manifest(ladder, 3);
    hls.fetchLevel(3);
    hls.fragLoaded(stats);
    setAhead(420);
    video.dispatchEvent(new Event('timeupdate'));
    setAhead(180);
    video.dispatchEvent(new Event('timeupdate'));
    expect(hls.config.minAutoBitrate).toBe(PEAK_360);
    setAhead(179);
    video.dispatchEvent(new Event('timeupdate'));
    expect(hls.config.minAutoBitrate).toBe(0);
  });

  it.each(['waiting', 'seeking'])('re-evaluates on %s', (type) => {
    const { hls, video, setAhead } = holdStart();
    hls.manifest(ladder, 3);
    hls.fetchLevel(3);
    hls.fragLoaded(stats);
    setAhead(500);
    video.dispatchEvent(new Event(type));
    expect(hls.config.minAutoBitrate).toBe(PEAK_360);
    setAhead(100);
    video.dispatchEvent(new Event(type));
    expect(hls.config.minAutoBitrate).toBe(0);
  });

  it('re-evaluates when a fragment has loaded', () => {
    const { hls, setAhead } = holdStart();
    hls.manifest(ladder, 3);
    hls.fetchLevel(3);
    setAhead(500);
    hls.fragLoaded(stats);
    expect(hls.config.minAutoBitrate).toBe(PEAK_360);
    setAhead(100);
    hls.fragLoaded(stats);
    expect(hls.config.minAutoBitrate).toBe(0);
  });

  it('drops the minimum at once when the cap falls to 240p, and sets it again at 360p', () => {
    const { hls, video, setAhead, advance } = holdStart();
    hls.manifest(ladder, 1);
    hls.fetchLevel(1);
    hls.fragLoaded(stats);
    setAhead(480);
    video.dispatchEvent(new Event('timeupdate'));
    expect(hls.config.minAutoBitrate).toBe(PEAK_360);
    hls.fetchLevel(0);
    expect(hls.autoLevelCapping).toBe(0);
    expect(hls.config.minAutoBitrate).toBe(0);
    advance(HEALTHY_MS);
    hls.fragLoaded(stats);
    expect(hls.autoLevelCapping).toBe(1);
    expect(hls.config.minAutoBitrate).toBe(0); // still fetching 240p: a floor, not a pull
    hls.fetchLevel(1);
    expect(hls.config.minAutoBitrate).toBe(PEAK_360);
  });

  it('does not write the minimum when the value has not changed', () => {
    const { hls, video, setAhead } = holdStart();
    hls.manifest(ladder, 3);
    hls.fetchLevel(3);
    hls.fragLoaded(stats);
    let writes = 0;
    let value = hls.config.minAutoBitrate;
    Object.defineProperty(hls.config, 'minAutoBitrate', {
      get: () => value,
      set: (v) => {
        writes += 1;
        value = v;
      },
    });
    setAhead(500);
    video.dispatchEvent(new Event('timeupdate'));
    video.dispatchEvent(new Event('timeupdate'));
    hls.fragLoaded(stats);
    expect(writes).toBe(1);
  });

  it('never writes the minimum for a pinned quality', () => {
    const { hls, video, setAhead } = holdStart({ auto: false });
    hls.manifest(ladder, 3);
    hls.fetchLevel(3);
    setAhead(500);
    video.dispatchEvent(new Event('timeupdate'));
    hls.fragLoaded(stats);
    expect(hls.config.minAutoBitrate).toBe(0);
  });

  it.each([
    ['a two-level ladder (240p and the original)', [l240, { height: 820, bitrate: 15000000 }]],
    ['a ladder with no 240p level', [l360, l480, l720]],
    ['a ladder with 240p but no 360p', [l240, l480, l720]],
  ])('never writes the minimum for %s', (_name, levelList) => {
    const { hls, video, setAhead } = holdStart();
    hls.manifest(levelList, levelList.length - 1);
    setAhead(500);
    video.dispatchEvent(new Event('timeupdate'));
    hls.fragLoaded(stats);
    expect(hls.config.minAutoBitrate).toBe(0);
  });

  it('does nothing before the manifest has been parsed', () => {
    const { hls, video, setAhead } = holdStart();
    setAhead(500);
    expect(() => video.dispatchEvent(new Event('timeupdate'))).not.toThrow();
    expect(hls.config.minAutoBitrate).toBe(0);
  });

  it('stops after destroy and leaves no engine listener on the video', () => {
    const video = document.createElement('video');
    const added = vi.spyOn(video, 'addEventListener');
    const removed = vi.spyOn(video, 'removeEventListener');
    const { engine, hls, setAhead } = holdStart({ video });
    hls.manifest(ladder, 3);
    hls.fetchLevel(3);
    hls.fragLoaded(stats);
    engine.destroy();
    setAhead(500);
    video.dispatchEvent(new Event('timeupdate'));
    expect(hls.config.minAutoBitrate).toBe(0);
    const addedPairs = added.mock.calls.map(([type, fn]) => [type, fn]);
    expect(removed.mock.calls.map(([type, fn]) => [type, fn])).toEqual(
      expect.arrayContaining(addedPairs),
    );
  });

  describe('by time: the bank at 360p, the engine’s own samples and the fragment in flight', () => {
    // A hold armed at 300 s in hand on a fast link (one 10,000 kbit/s sample), with the clock the test moves.
    function armed(over = {}) {
      let clockMs = 0;
      const started = holdStart({ clock: () => clockMs, ...over });
      const { hls, video, setAhead } = started;
      hls.manifest(ladder, 3);
      hls.fetchLevel(3);
      hls.fragLoaded(stats);
      setAhead(300);
      video.dispatchEvent(new Event('timeupdate'));
      expect(hls.config.minAutoBitrate).toBe(PEAK_360);
      return { ...started, setClock: (ms) => (clockMs = ms) };
    }
    // A fragment that began loading at `start`, `loaded` bytes in, its first byte at `first` (0: none yet).
    const loadingFrag = (start = 0, loaded = 0, first = loaded > 0 ? start + 1 : 0) => ({
      stats: { loaded, aborted: false, loading: { start, first, end: 0 } },
    });

    it('lets stale fast samples hold the floor only while 180 s are in hand', () => {
      const { hls, video, setAhead } = armed();
      setAhead(181);
      video.dispatchEvent(new Event('timeupdate'));
      expect(hls.config.minAutoBitrate).toBe(PEAK_360);
      setAhead(179);
      video.dispatchEvent(new Event('timeupdate'));
      expect(hls.config.minAutoBitrate).toBe(0);
    });

    it('releases within one timer tick when the fragment in flight trickles after its first byte', () => {
      const { hls, setClock } = armed();
      const frag = loadingFrag(0, 20_000, 1_000); // first byte at 1 s
      hls.fragLoading(frag);
      setClock(1_000 + IN_FLIGHT_MIN_AGE_MS); // 20,000 bytes in 4 s: 40 kbit/s, 300 s last about 316 s
      vi.advanceTimersByTime(HOLD_CHECK_MS);
      expect(hls.config.minAutoBitrate).toBe(0);
    });

    it('keeps the floor while the fragment in flight is fast', () => {
      const { hls, setClock } = armed();
      hls.fragLoading(loadingFrag(0, 2_000_000, 1_000)); // 4,000 kbit/s over 4 s
      setClock(1_000 + IN_FLIGHT_MIN_AGE_MS);
      vi.advanceTimersByTime(HOLD_CHECK_MS * 3);
      expect(hls.config.minAutoBitrate).toBe(PEAK_360);
    });

    it('does not release on a fragment with no first byte at 10 s (noProgressLoader covers silence)', () => {
      const { hls, setClock } = armed();
      hls.fragLoading(loadingFrag(0, 0));
      setClock(10_000);
      vi.advanceTimersByTime(HOLD_CHECK_MS);
      expect(hls.config.minAutoBitrate).toBe(PEAK_360);
    });

    it('counts the transfer from 4000 ms after the first byte, not at 3900 ms', () => {
      const { hls, setClock } = armed();
      hls.fragLoading(loadingFrag(0, 20_000, 5_000)); // first byte at 5 s, a trickle
      setClock(5_000 + 3_900);
      vi.advanceTimersByTime(2_000);
      expect(hls.config.minAutoBitrate).toBe(PEAK_360);
      setClock(5_000 + 4_000);
      vi.advanceTimersByTime(2_000);
      expect(hls.config.minAutoBitrate).toBe(0);
    });

    it('does not read the rate of a fragment that was aborted or has ended', () => {
      const { hls, setClock } = armed();
      // A trickle that would release the hold if it were read: 20,000 bytes in 4 s from the first byte.
      const aborted = loadingFrag(0, 20_000, 1_000);
      aborted.stats.aborted = true;
      hls.fragLoading(aborted);
      setClock(5_000);
      vi.advanceTimersByTime(2_000);
      expect(hls.config.minAutoBitrate).toBe(PEAK_360);
      const ended = loadingFrag(0, 20_000, 1_000);
      ended.stats.loading.end = 4_500; // the failed attempt of a retry
      hls.fragLoading(ended);
      vi.advanceTimersByTime(2_000);
      expect(hls.config.minAutoBitrate).toBe(PEAK_360);
    });

    it('pins the check interval at 2 s and the minimum age at 4 s', () => {
      expect(HOLD_CHECK_MS).toBe(2000);
      expect(IN_FLIGHT_MIN_AGE_MS).toBe(4000);
    });

    it('does not release before the 2 s tick, and does at it', () => {
      const { hls, setClock } = armed();
      hls.fragLoading(loadingFrag(0, 20_000, 1_000)); // a trickle, 4 s old at the clock below
      setClock(5_000);
      vi.advanceTimersByTime(1_999);
      expect(hls.config.minAutoBitrate).toBe(PEAK_360);
      vi.advanceTimersByTime(1);
      expect(hls.config.minAutoBitrate).toBe(0);
    });

    it('reads the newest three samples for the release: a slow one third-newest releases', () => {
      const { hls } = armed();
      const slow = { loaded: 12_500, loading: { start: 0, first: 1, end: 1000 } }; // 100 kbit/s
      hls.fragLoaded(slow);
      hls.fragLoaded(stats);
      hls.fragLoaded(stats); // the slow one is third-newest
      expect(hls.config.minAutoBitrate).toBe(0);
    });

    it('does not read a slow sample that is fourth-newest', () => {
      const { hls } = armed();
      const slow = { loaded: 12_500, loading: { start: 0, first: 1, end: 1000 } };
      hls.fragLoaded(slow);
      hls.fragLoaded(stats);
      hls.fragLoaded(stats);
      expect(hls.config.minAutoBitrate).toBe(0);
      hls.fragLoaded(stats); // three fast samples after it: the slow one is fourth-newest
      expect(hls.config.minAutoBitrate).toBe(PEAK_360);
    });

    it('releases on the lowest of the last three samples', () => {
      const { hls } = armed();
      // 12,500 bytes over 1,000 ms is 100 kbit/s: 300 s in hand last about 342 s at 360p.
      hls.fragLoaded({ loaded: 12_500, loading: { start: 0, first: 1, end: 1000 } });
      expect(hls.config.minAutoBitrate).toBe(0);
    });

    it('arms on the lower middle of nine samples, though the newest four are slow', () => {
      const { hls, video, setAhead } = holdStart();
      hls.manifest(ladder, 3);
      hls.fetchLevel(3);
      for (let i = 0; i < 5; i += 1) hls.fragLoaded(stats); // 10,000 kbit/s
      // 400 kbit/s: 300 s in hand last about 588 s, so each slow sample alone would not arm it, yet it would
      // not release it either at 400 s in hand (784 s).
      for (let i = 0; i < 4; i += 1) {
        hls.fragLoaded({ loaded: 50_000, loading: { start: 0, first: 1, end: 1000 } });
      }
      setAhead(400);
      video.dispatchEvent(new Event('timeupdate'));
      expect(hls.config.minAutoBitrate).toBe(PEAK_360);
    });

    it('times a sample from loading.start, so the wait for the first byte counts', () => {
      const { hls, video, setAhead } = holdStart();
      hls.manifest(ladder, 3);
      hls.fetchLevel(3);
      // 50,000 bytes over 1,000 ms from the start is 400 kbit/s; timed from the first byte (900 ms in) the same
      // fragment would read 4,000. The engine must read 400.
      hls.fragLoaded({ loaded: 50_000, loading: { start: 0, first: 900, end: 1000 } });
      setAhead(300); // 300 s last about 588 s at 400 kbit/s: under 900, so not armed
      video.dispatchEvent(new Event('timeupdate'));
      expect(hls.config.minAutoBitrate).toBe(0);
    });

    it('drops the samples on fragment trouble: a fragment with no rate does not arm it again', () => {
      const { hls, video } = armed();
      hls.error(timeout);
      expect(hls.config.minAutoBitrate).toBe(0);
      hls.fragLoaded({ loaded: 0, loading: { start: 0, first: 0, end: 0 } }); // loaded, but nothing to measure
      video.dispatchEvent(new Event('timeupdate'));
      expect(hls.config.minAutoBitrate).toBe(0);
      hls.fragLoaded(stats);
      expect(hls.config.minAutoBitrate).toBe(PEAK_360);
    });

    it('drops the samples when the network changes', () => {
      const { hls, video } = armed();
      window.dispatchEvent(new Event('offline'));
      video.dispatchEvent(new Event('timeupdate'));
      expect(hls.config.minAutoBitrate).toBe(0);
      hls.fragLoaded({ loaded: 0, loading: { start: 0, first: 0, end: 0 } });
      expect(hls.config.minAutoBitrate).toBe(0);
      hls.fragLoaded(stats);
      expect(hls.config.minAutoBitrate).toBe(PEAK_360);
    });

    it('ignores an init segment: no sample, no timer', () => {
      const { hls, video } = holdStart();
      hls.manifest(ladder, 3);
      hls.fetchLevel(3);
      hls.fragLoading({ sn: 'initSegment', ...loadingFrag() });
      expect(vi.getTimerCount()).toBe(0);
      hls.emit(FakeHls.Events.FRAG_LOADED, { frag: { sn: 'initSegment', stats } });
      video.dispatchEvent(new Event('timeupdate'));
      expect(hls.config.minAutoBitrate).toBe(0);
    });

    it('runs one timer while a fragment loads and none between fragments', () => {
      const { hls } = armed();
      expect(vi.getTimerCount()).toBe(0);
      hls.fragLoading(loadingFrag());
      hls.fragLoading(loadingFrag()); // a retry of the same fragment
      expect(vi.getTimerCount()).toBe(1);
      hls.fragLoaded(stats);
      expect(vi.getTimerCount()).toBe(0);
    });

    it('stops the timer when the fragment is abandoned, on trouble, on a restart and on destroy', () => {
      const { hls, engine } = armed();
      hls.fragLoading(loadingFrag());
      hls.fragAborted(loadingFrag());
      expect(vi.getTimerCount()).toBe(0);
      hls.fragLoading(loadingFrag());
      hls.error(timeout);
      expect(vi.getTimerCount()).toBe(0);
      hls.fragLoading(loadingFrag());
      hls.error({
        fatal: true,
        type: 'networkError',
        details: 'levelLoadError',
        response: { code: 0 },
      });
      vi.advanceTimersByTime(RECONNECT_MS);
      expect(vi.getTimerCount()).toBe(0);
      hls.fragLoading(loadingFrag());
      expect(vi.getTimerCount()).toBe(1);
      engine.destroy();
      expect(vi.getTimerCount()).toBe(0);
    });

    it('runs the timer on a ladder that is never held too (the ceiling is re-read on it), and not when pinned', () => {
      const { hls } = holdStart();
      hls.manifest([l360, l480, l720], 2);
      hls.fragLoading(loadingFrag());
      expect(vi.getTimerCount()).toBe(1);
      const pinned = holdStart({ auto: false });
      pinned.hls.manifest(ladder, 2);
      vi.clearAllTimers();
      pinned.hls.fragLoading(loadingFrag());
      expect(vi.getTimerCount()).toBe(0);
    });
  });
});

describe('the step gate follows what hls.js fetches', () => {
  it('raises the cap only while hls.js is fetching at it', () => {
    const { hls, advance } = start();
    hls.manifest(levels, 3);
    hls.fetchLevel(1);
    expect(hls.autoLevelCapping).toBe(1);
    advance(HEALTHY_MS);
    hls.fragLoaded(stats);
    expect(hls.autoLevelCapping).toBe(2);
    advance(HEALTHY_MS);
    hls.fragLoaded(stats);
    expect(hls.autoLevelCapping).toBe(2); // level 1 is still being fetched
    hls.fetchLevel(2);
    advance(HEALTHY_MS); // the healthy minute runs from when hls.js reached level 2
    hls.fragLoaded(stats);
    expect(hls.autoLevelCapping).toBe(3);
  });

  it('starts the healthy minute when hls.js reaches the cap, not when the cap rose', () => {
    const { hls, advance } = start();
    hls.manifest(levels, 3);
    hls.fetchLevel(1);
    advance(HEALTHY_MS);
    hls.fragLoaded(stats);
    expect(hls.autoLevelCapping).toBe(2); // level 1 is still fetched
    advance(HEALTHY_MS * 5);
    hls.fetchLevel(2); // hls.js reaches the cap
    advance(4000);
    hls.fragLoaded(stats);
    expect(hls.autoLevelCapping).toBe(2);
    advance(HEALTHY_MS - 4000);
    hls.fragLoaded(stats);
    expect(hls.autoLevelCapping).toBe(3);
  });

  it('does not restart the clock when the fetched level was already at the cap', () => {
    const { hls, advance } = start();
    hls.manifest(levels, 2);
    hls.fetchLevel(2);
    advance(HEALTHY_MS - 1000);
    hls.fetchLevel(2);
    advance(1000);
    hls.fragLoaded(stats);
    expect(hls.autoLevelCapping).toBe(3);
  });

  it('does not restart the clock on a first fetch or a step down', () => {
    const { hls, advance } = start();
    hls.manifest(levels, 2);
    advance(HEALTHY_MS - 1000);
    hls.fetchLevel(2); // first fetch, previous level unknown
    advance(1000);
    hls.fragLoaded(stats);
    expect(hls.autoLevelCapping).toBe(3);
  });

  it('leaves a pinned quality alone', () => {
    const { hls } = start({ auto: false });
    hls.manifest(levels, 2);
    hls.fetchLevel(1);
    hls.fetchLevel(2);
    expect(hls.autoLevelCapping).toBe(-1);
  });

  describe('fragment trouble', () => {
    const trouble = (hls, over) =>
      hls.error({ fatal: false, type: 'networkError', details: 'fragLoadTimeOut', ...over });

    it.each([
      ['a timeout', { details: 'fragLoadTimeOut' }],
      ['an error with no HTTP answer', { details: 'fragLoadError' }],
      ['an error with a zero code', { details: 'fragLoadError', response: { code: 0 } }],
    ])('brings a cap above the fetched level down to it on %s', (_name, over) => {
      const { hls, advance } = start();
      hls.manifest(levels, 1);
      hls.fetchLevel(1);
      advance(HEALTHY_MS);
      hls.fragLoaded(stats);
      expect(hls.autoLevelCapping).toBe(2);
      trouble(hls, over);
      expect(hls.autoLevelCapping).toBe(1);
    });

    it('does not count a 503 as trouble', () => {
      const { hls, advance } = start();
      hls.manifest(levels, 1);
      hls.fetchLevel(1);
      advance(HEALTHY_MS);
      hls.fragLoaded(stats);
      expect(hls.autoLevelCapping).toBe(2);
      trouble(hls, { details: 'fragLoadError', response: { code: 503 } });
      expect(hls.autoLevelCapping).toBe(2);
    });

    it('leaves a pinned quality alone', () => {
      const { hls } = start({ auto: false });
      hls.manifest(levels, 3);
      hls.fetchLevel(1);
      trouble(hls, {});
      expect(hls.autoLevelCapping).toBe(-1);
    });
  });
});

describe('a rung hls.js marked after a failed fragment is forgotten after a healthy minute', () => {
  const trouble = (hls, over) =>
    hls.error({ fatal: false, type: 'networkError', details: 'fragLoadTimeOut', ...over });

  // Auto fetching level 1, level 2 marked: the first FRAG_LOADED, at t = 0, is the one that sees the mark.
  function marked() {
    const env = start();
    env.hls.manifest(levels, 1);
    env.hls.fetchLevel(1);
    env.hls.levels[2].fragmentError = 1;
    env.hls.fragLoaded(stats);
    return env;
  }

  it.each([
    [HEALTHY_MS - 1, 1],
    [HEALTHY_MS, 0],
  ])('a fragment loaded %i ms after the one that saw the mark leaves it at %i', (ms, mark) => {
    const { hls, advance } = marked();
    advance(ms);
    hls.fragLoaded(stats);
    expect(hls.levels[2].fragmentError).toBe(mark);
  });

  it.each([
    ['a fragment timeout', (hls) => trouble(hls, {})],
    ['a fragment error with no HTTP answer', (hls) => trouble(hls, { details: 'fragLoadError' })],
    ['a waiting event on the video', (_hls, video) => video.dispatchEvent(new Event('waiting'))],
  ])('starts the minute again on %s', (_name, act) => {
    const { hls, video, advance } = marked();
    advance(30_000);
    act(hls, video);
    advance(HEALTHY_MS - 1);
    hls.fragLoaded(stats);
    expect(hls.levels[2].fragmentError).toBe(1);
    advance(1);
    hls.fragLoaded(stats);
    expect(hls.levels[2].fragmentError).toBe(0);
  });

  it('does not start the minute again on a fragment error with an HTTP answer (a 503)', () => {
    const { hls, advance } = marked();
    advance(30_000);
    trouble(hls, { details: 'fragLoadError', response: { code: 503 } });
    advance(30_000);
    hls.fragLoaded(stats);
    expect(hls.levels[2].fragmentError).toBe(0);
  });

  it('keeps the mark of the level being fetched however long it stands', () => {
    const { hls, advance } = start();
    hls.manifest(levels, 1);
    hls.fetchLevel(2);
    hls.levels[2].fragmentError = 1;
    hls.fragLoaded(stats);
    advance(HEALTHY_MS * 10);
    hls.fragLoaded(stats);
    expect(hls.levels[2].fragmentError).toBe(1);
  });

  it('leaves a pinned quality alone', () => {
    const { hls, advance } = start({ auto: false });
    hls.manifest(levels, 1);
    hls.fetchLevel(1);
    hls.levels[2].fragmentError = 1;
    hls.fragLoaded(stats);
    advance(HEALTHY_MS);
    hls.fragLoaded(stats);
    expect(hls.levels[2].fragmentError).toBe(1);
  });
});

describe('the champagne ceiling: Auto climbs above 480p only on headroom', () => {
  // Real rates (Rung.java): a transcoded rung's average is its target + audio, its peak is x 1.2 + audio; a
  // copy rung's peak is x 1.5 + audio. `bitrate` is the variant's BANDWIDTH (the peak), as hls.js has it.
  const rung = (id, height, video, audio, copy = false) => {
    const peak = Math.round(video * (copy ? 1.5 : 1.2)) + audio;
    const level = { height, bitrate: peak, maxBitrate: peak, averageBitrate: video + audio };
    return { id, height, bitrate: peak, copy, level };
  };
  const r240 = rung('240p', 240, 250_000, 64_000);
  const r360 = rung('360p', 360, 600_000, 96_000);
  const r480 = rung('480p', 480, 1_000_000, 128_000);
  const r720 = rung('720p', 720, 2_500_000, 128_000);
  const r1080 = rung('1080p', 1080, 5_000_000, 128_000);
  const rOriginal = rung('original', 1080, 15_000_000, 128_000, true); // a 15 Mbit/s copy rung
  const ladderOf = (rungs) => ({
    renditions: rungs.map(({ id, height, bitrate, copy }) => ({ id, height, bitrate, copy })),
    levels: rungs.map((r) => r.level),
  });
  const full = ladderOf([r240, r360, r480, r720, r1080]); // 480p is level 2
  const noHold = ladderOf([r480, r720, r1080]); // no 360p: the floor hold never applies; 480p is level 0
  const copyLadder = ladderOf([r240, r360, r480, r720, rOriginal]);

  // One fragment whose rate from its first byte is `kbps`.
  const frag = (kbps, over = {}) => ({
    loaded: kbps * 125,
    retry: 0,
    loading: { start: 0, first: 0, end: 1000 },
    ...over,
  });

  function champStart(ladder, over = {}) {
    const video = document.createElement('video');
    const state = { ahead: 480 };
    Object.defineProperty(video, 'buffered', {
      get: () => ({
        length: state.ahead > 0 ? 1 : 0,
        start: () => 0,
        end: () => state.ahead,
      }),
    });
    const started = start({
      session: { masterUrl: session.masterUrl, renditions: ladder.renditions },
      video,
      ...over,
    });
    return { ...started, video, setAhead: (seconds) => (state.ahead = seconds) };
  }

  // Starts at level `first`, then `rounds` times: a healthy minute, a fragment of `kbps`, hls.js fetching the cap.
  function climb(ladder, kbps, rounds, first = 2) {
    const env = champStart(ladder);
    env.hls.manifest(ladder.levels, first);
    env.hls.fetchLevel(env.hls.autoLevelCapping);
    // Three rates are needed before a step up above 480p; the minute has not passed yet.
    for (let i = 0; i < 2; i += 1) env.hls.fragLoaded(frag(kbps));
    for (let i = 0; i < rounds; i += 1) {
      env.advance(HEALTHY_MS);
      env.hls.fragLoaded(frag(kbps));
      env.hls.fetchLevel(env.hls.autoLevelCapping);
    }
    return env;
  }

  const trouble = (hls) =>
    hls.error({ fatal: false, type: 'networkError', details: 'fragLoadTimeOut' });

  describe('the start', () => {
    it('is clamped to the 480p level when the playlist lists a higher rung first', () => {
      const { hls } = champStart(full);
      hls.manifest(full.levels, 4);
      expect(hls.autoLevelCapping).toBe(2);
    });

    it('is clamped even when a fast speed is remembered: no fragment has proved it yet', () => {
      rememberSpeed(55_000);
      const { hls } = champStart(full);
      hls.manifest(full.levels, 3);
      expect(hls.autoLevelCapping).toBe(2);
      resetThroughputState();
    });

    it('is left alone when it is at or under the 480p level', () => {
      const { hls } = champStart(full);
      hls.manifest(full.levels, 1);
      expect(hls.autoLevelCapping).toBe(1);
      const second = champStart(full);
      second.hls.manifest(full.levels, 2);
      expect(second.hls.autoLevelCapping).toBe(2);
    });

    it('is clamped to the 360p level of a ladder with no 480p rung', () => {
      const ladder = ladderOf([r240, r360, r720, r1080]);
      const { hls } = champStart(ladder);
      hls.manifest(ladder.levels, 3);
      expect(hls.autoLevelCapping).toBe(1);
    });

    it('does not touch a pinned quality', () => {
      const { hls } = champStart(full, { auto: false });
      hls.manifest(full.levels, 4);
      hls.fetchLevel(4);
      hls.fragLoaded(frag(100_000));
      expect(hls.autoLevelCapping).toBe(-1);
    });
  });

  describe('stepping up', () => {
    it.each([
      [7884, 3],
      [7883, 2],
    ])('to 720p at 3 x its average: %i kbit/s caps at level %i', (kbps, level) => {
      expect(climb(full, kbps, 1).hls.autoLevelCapping).toBe(level);
    });

    it('does not go from 480p to 720p with a bank under 450 s, and does at 450 s', () => {
      const env = champStart(full);
      env.hls.manifest(full.levels, 2);
      env.hls.fetchLevel(2);
      env.setAhead(449);
      env.hls.fragLoaded(frag(20_000));
      env.hls.fragLoaded(frag(20_000));
      env.advance(HEALTHY_MS);
      env.hls.fragLoaded(frag(20_000));
      expect(env.hls.autoLevelCapping).toBe(2);
      env.setAhead(450);
      env.hls.fragLoaded(frag(20_000));
      expect(env.hls.autoLevelCapping).toBe(3);
    });

    it('climbs one rung a minute even at a very fast speed', () => {
      const { hls } = climb(full, 55_000, 1);
      expect(hls.autoLevelCapping).toBe(3);
      hls.fragLoaded(frag(55_000));
      expect(hls.autoLevelCapping).toBe(3); // the minute has not passed again
    });

    it('reaches a 1080p transcode and a 15 Mbit/s copy rung at home speed, a rung a minute', () => {
      expect(climb(full, 55_000, 2).hls.autoLevelCapping).toBe(4);
      expect(climb(copyLadder, 55_000, 2).hls.autoLevelCapping).toBe(4);
    });

    it('reaches a 15 Mbit/s copy rung at 3 x its average (45,384 kbit/s), not a kbit/s under', () => {
      expect(climb(copyLadder, 45_384, 2).hls.autoLevelCapping).toBe(4);
      expect(climb(copyLadder, 45_383, 2).hls.autoLevelCapping).toBe(3);
    });

    it('does not run ahead of the ceiling: one rung when it lets the gate climb, not several', () => {
      const env = champStart(full);
      env.hls.manifest(full.levels, 2);
      env.hls.fetchLevel(2);
      env.advance(HEALTHY_MS * 5);
      env.hls.fragLoaded(frag(1_000)); // slow: the ceiling stays at 480p
      env.hls.fragLoaded(frag(55_000)); // the lower middle of two samples is still the slow one
      expect(env.hls.autoLevelCapping).toBe(2);
      for (let i = 0; i < 4; i += 1) env.hls.fragLoaded(frag(55_000));
      expect(env.hls.autoLevelCapping).toBe(3);
    });

    it('needs 3 rates before the first step up: 2 are not enough, the 3rd is', () => {
      const env = champStart(full);
      env.hls.manifest(full.levels, 2);
      env.hls.fetchLevel(2);
      env.advance(HEALTHY_MS);
      env.hls.fragLoaded(frag(20_000));
      env.hls.fragLoaded(frag(20_000));
      expect(env.hls.autoLevelCapping).toBe(2);
      env.hls.fragLoaded(frag(20_000));
      expect(env.hls.autoLevelCapping).toBe(3);
    });

    it('does not let a retried fragment raise the ceiling: only a clean one does', () => {
      const env = champStart(full);
      env.hls.manifest(full.levels, 2);
      env.hls.fetchLevel(2);
      env.setAhead(449);
      for (let i = 0; i < 3; i += 1) env.hls.fragLoaded(frag(20_000));
      env.advance(HEALTHY_MS);
      env.setAhead(450); // the bank fills as the retried fragment lands
      env.hls.fragLoaded(frag(20_000, { retry: 1 }));
      expect(env.hls.autoLevelCapping).toBe(2);
      env.video.dispatchEvent(new Event('timeupdate')); // the block lasts until a clean fragment, not one moment
      expect(env.hls.autoLevelCapping).toBe(2);
      env.hls.fragLoaded(frag(20_000));
      expect(env.hls.autoLevelCapping).toBe(3);
    });

    it('does not step from 720p to 1080p while the bank is not full for 720p, however the ceiling was reached', () => {
      const env = climb(full, 55_000, 1); // at 720p, the ceiling asked for 720p only
      expect(env.hls.autoLevelCapping).toBe(3);
      env.setAhead(200); // 720p's bank target is 256 s: 226 s counts as full
      env.advance(HEALTHY_MS);
      env.hls.fragLoaded(frag(55_000));
      expect(env.hls.autoLevelCapping).toBe(3);
      env.setAhead(226);
      env.hls.fragLoaded(frag(55_000));
      expect(env.hls.autoLevelCapping).toBe(4);
    });

    it('takes no rate from an init segment', () => {
      const { hls, advance } = champStart(full);
      hls.manifest(full.levels, 2);
      hls.fetchLevel(2);
      advance(HEALTHY_MS);
      hls.emit(FakeHls.Events.FRAG_LOADED, { frag: { sn: 'initSegment', stats: frag(55_000) } });
      expect(hls.autoLevelCapping).toBe(2);
    });

    it('times a fragment from its first byte: an encoder’s wait does not count against the link', () => {
      const { hls, advance } = champStart(full);
      hls.manifest(full.levels, 2);
      hls.fetchLevel(2);
      advance(HEALTHY_MS);
      // 1,000,000 bytes in the 1,000 ms after the first byte (8,000 kbit/s), 6,000 ms after the request began.
      const delayed = {
        loaded: 1_000_000,
        retry: 0,
        loading: { start: 0, first: 5000, end: 6000 },
      };
      for (let i = 0; i < 3; i += 1) hls.fragLoaded(delayed);
      expect(hls.autoLevelCapping).toBe(3);
    });
  });

  describe('staying on a rung', () => {
    // At 720p (level 3) with one good fragment, then nine at `kbps`.
    function onTop(kbps) {
      const env = climb(full, 7884, 1);
      expect(env.hls.autoLevelCapping).toBe(3);
      for (let i = 0; i < 9; i += 1) env.hls.fragLoaded(frag(kbps));
      return env;
    }

    it.each([
      [5256, 3],
      [5255, 2],
    ])(
      'at 2 x the average: nine fragments at %i kbit/s leave the cap at level %i',
      (kbps, level) => {
        expect(onTop(kbps).hls.autoLevelCapping).toBe(level);
      },
    );

    it('is not shaken by a few slow fragments among good ones: the lower middle of nine is read', () => {
      const { hls } = onTop(7884);
      for (let i = 0; i < 4; i += 1) hls.fragLoaded(frag(100));
      expect(hls.autoLevelCapping).toBe(3);
      hls.fragLoaded(frag(100));
      expect(hls.autoLevelCapping).toBe(2);
    });

    it('falls to the highest rung that still passes, not straight to 480p', () => {
      const env = climb(full, 20_000, 2);
      expect(env.hls.autoLevelCapping).toBe(4);
      for (let i = 0; i < 9; i += 1) env.hls.fragLoaded(frag(6000)); // under 2 x 1080p, over 2 x 720p
      expect(env.hls.autoLevelCapping).toBe(3);
      for (let i = 0; i < 9; i += 1) env.hls.fragLoaded(frag(5000)); // under 2 x 720p
      expect(env.hls.autoLevelCapping).toBe(2);
    });

    it('lowers the gate with it, so a ceiling that comes back climbs a rung a minute', () => {
      const env = climb(full, 20_000, 2);
      for (let i = 0; i < 9; i += 1) env.hls.fragLoaded(frag(1_000));
      expect(env.hls.autoLevelCapping).toBe(2);
      env.hls.fetchLevel(2);
      for (let i = 0; i < 9; i += 1) env.hls.fragLoaded(frag(55_000));
      expect(env.hls.autoLevelCapping).toBe(2); // the healthy minute starts again
      env.advance(HEALTHY_MS);
      env.hls.fragLoaded(frag(55_000));
      expect(env.hls.autoLevelCapping).toBe(3);
    });

    it('leaves a retried fragment out of the rates, and 4 retried in a row forget them all', () => {
      const env = onTop(7884);
      for (let i = 0; i < 3; i += 1) env.hls.fragLoaded(frag(100, { retry: 1 }));
      expect(env.hls.autoLevelCapping).toBe(3);
      env.hls.fragLoaded(frag(100, { retry: 1 }));
      expect(env.hls.autoLevelCapping).toBe(2); // the 480p level, whatever the old rates said
    });

    it('does not count 3 retried fragments and a clean one as a run: the run starts again', () => {
      const env = onTop(7884);
      for (let i = 0; i < 3; i += 1) env.hls.fragLoaded(frag(100, { retry: 1 }));
      env.hls.fragLoaded(frag(7884));
      for (let i = 0; i < 3; i += 1) env.hls.fragLoaded(frag(100, { retry: 1 }));
      expect(env.hls.autoLevelCapping).toBe(3);
    });

    it('forgets a run of retried fragments on trouble too, so 3 more do not finish it', () => {
      const env = onTop(7884);
      for (let i = 0; i < 3; i += 1) env.hls.fragLoaded(frag(100, { retry: 1 }));
      trouble(env.hls);
      for (let i = 0; i < 3; i += 1) env.hls.fragLoaded(frag(7884));
      env.advance(HEALTHY_MS);
      env.hls.fragLoaded(frag(100, { retry: 1 }));
      env.hls.fragLoaded(frag(7884));
      expect(env.hls.autoLevelCapping).toBe(3);
    });

    it('counts a fragment that needed no retry (hls.js’s stats carry retry 0)', () => {
      const env = onTop(7884);
      for (let i = 0; i < 5; i += 1) env.hls.fragLoaded(frag(100));
      expect(env.hls.autoLevelCapping).toBe(2);
    });
  });

  describe('trouble and the network', () => {
    it('takes a rung held back to the 480p level at once, and climbs again only after a healthy minute', () => {
      const env = climb(full, 20_000, 1);
      expect(env.hls.autoLevelCapping).toBe(3);
      trouble(env.hls);
      expect(env.hls.autoLevelCapping).toBe(2);
      for (let i = 0; i < 3; i += 1) env.hls.fragLoaded(frag(20_000));
      expect(env.hls.autoLevelCapping).toBe(2);
      env.advance(HEALTHY_MS);
      env.hls.fragLoaded(frag(20_000));
      expect(env.hls.autoLevelCapping).toBe(3);
    });

    it('forgets the rates from before the trouble: stale fast ones do not carry a rung back', () => {
      const env = climb(full, 20_000, 1);
      for (let i = 0; i < 8; i += 1) env.hls.fragLoaded(frag(20_000));
      trouble(env.hls);
      env.advance(HEALTHY_MS);
      env.hls.fragLoaded(frag(100));
      env.advance(HEALTHY_MS);
      env.hls.fragLoaded(frag(100));
      expect(env.hls.autoLevelCapping).toBe(2);
    });

    it.each([
      ['a ladder with a 360p hold', full, 3, 2],
      ['a ladder with no 360p level', noHold, 1, 0],
    ])(
      'falls to the 480p level on the hold timer when the network changes: %s',
      (_name, ladder, top, base) => {
        const env = climb(ladder, 20_000, top - base, base);
        expect(env.hls.autoLevelCapping).toBe(top);
        window.dispatchEvent(new Event('offline'));
        env.hls.fragLoading({
          stats: { loaded: 0, aborted: false, loading: { start: 0, end: 0 } },
        });
        vi.advanceTimersByTime(HOLD_CHECK_MS);
        expect(env.hls.autoLevelCapping).toBe(base);
      },
    );

    it('forgets the ceiling when it restarts after a fatal playlist error', () => {
      const env = climb(full, 20_000, 1);
      expect(env.hls.autoLevelCapping).toBe(3);
      env.hls.error({ fatal: true, type: 'networkError', details: 'levelLoadError' });
      vi.advanceTimersByTime(RECONNECT_MS);
      expect(env.hls.startLoad).toHaveBeenCalledTimes(1);
      expect(env.hls.autoLevelCapping).toBe(2); // at once, not at the next event
      env.hls.fragLoaded(frag(20_000)); // one clean rate is not three
      env.video.dispatchEvent(new Event('timeupdate'));
      expect(env.hls.autoLevelCapping).toBe(2);
    });

    it('falls to the 480p level on the next played second after the network changes', () => {
      const env = climb(full, 20_000, 1);
      window.dispatchEvent(new Event('offline'));
      env.video.dispatchEvent(new Event('timeupdate'));
      expect(env.hls.autoLevelCapping).toBe(2);
    });

    it('stops reading the ceiling off the video after destroy', () => {
      const env = climb(full, 20_000, 1);
      env.engine.destroy();
      window.dispatchEvent(new Event('offline'));
      env.video.dispatchEvent(new Event('timeupdate'));
      env.video.dispatchEvent(new Event('seeking'));
      expect(env.hls.autoLevelCapping).toBe(3);
    });
  });

  describe('other ladders', () => {
    it('climbs from the 480p level of a ladder with no 360p, with no hold in play', () => {
      const env = champStart(noHold);
      env.hls.manifest(noHold.levels, 2);
      expect(env.hls.autoLevelCapping).toBe(0);
      expect(climb(noHold, 20_000, 2, 0).hls.autoLevelCapping).toBe(2);
    });

    it('never raises a ladder that stops at 480p', () => {
      const ladder = ladderOf([r240, r360, r480]);
      expect(climb(ladder, 55_000, 3, 0).hls.autoLevelCapping).toBe(2);
    });
  });
});
