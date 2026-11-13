import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FakeHls } from '../test/fakeHls.js';
import { RECONNECT_MS, hlsConfig, retryNoAnswer, startHlsJs } from './hlsjsEngine.js';
import { HEALTHY_MS } from './stepGate.js';

beforeEach(() => {
  FakeHls.reset();
  vi.useFakeTimers();
});
afterEach(() => vi.useRealTimers());

const renditions = [
  { id: 'original', height: 820, bitrate: 15000000, copy: true },
  { id: '720p', height: 720, bitrate: 2500000, copy: false },
  { id: '480p', height: 480, bitrate: 1000000, copy: false },
  { id: '360p', height: 360, bitrate: 600000, copy: false },
];
// hls.js orders levels lowest bitrate first.
const levels = [
  { height: 360, bitrate: 600000 },
  { height: 480, bitrate: 1000000 },
  { height: 720, bitrate: 2500000 },
  { height: 820, bitrate: 15000000 },
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
  return { engine, video, handlers, hls: FakeHls.last, advance: (ms) => (t += ms) };
}

describe('hls.js settings', () => {
  it('buffers 120 s ahead within ~250 MB and keeps 30 s behind', () => {
    const config = hlsConfig(0);
    expect(config.maxBufferLength).toBe(120);
    expect(config.maxMaxBufferLength).toBe(120);
    expect(config.maxBufferSize).toBe(250_000_000);
    expect(config.backBufferLength).toBe(30);
    expect(config.preferManagedMediaSource).toBe(false);
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
