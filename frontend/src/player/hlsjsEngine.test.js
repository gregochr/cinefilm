import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FakeHls } from '../test/fakeHls.js';
import { RECONNECT_MS, hlsConfig, startHlsJs } from './hlsjsEngine.js';
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

function start(over = {}) {
  const video = document.createElement('video');
  const handlers = {
    onLevel: vi.fn(),
    onThroughput: vi.fn(),
    onReconnecting: vi.fn(),
    onSessionLost: vi.fn(),
    onFatal: vi.fn(),
  };
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
    hls.switchTo(3);
    hls.switchTo(1);
    expect(hls.autoLevelCapping).toBe(1);
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

  it('reports nothing for a fragment with no timing', () => {
    const { hls, handlers } = start();
    hls.manifest(levels, 3);
    hls.fragLoaded({ loaded: 0, loading: { start: 0, first: 0, end: 0 } });
    expect(handlers.onThroughput).not.toHaveBeenCalled();
  });
});

describe('a dropped connection', () => {
  const networkError = { fatal: true, type: 'networkError', response: { code: 0 } };

  it('shows Reconnecting once and retries at the playhead every 5 s until a fragment loads', () => {
    const { hls, video, handlers } = start();
    hls.manifest(levels, 3);
    video.currentTime = 1234.5;
    hls.error(networkError);
    hls.error(networkError);
    expect(handlers.onReconnecting.mock.calls).toEqual([[true]]);
    expect(hls.startLoad).not.toHaveBeenCalled();
    vi.advanceTimersByTime(RECONNECT_MS - 1);
    expect(hls.startLoad).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);
    expect(hls.startLoad).toHaveBeenCalledWith(1234.5);
    vi.advanceTimersByTime(RECONNECT_MS);
    expect(hls.startLoad).toHaveBeenCalledTimes(2);
    hls.fragLoaded(stats);
    expect(handlers.onReconnecting.mock.calls).toEqual([[true], [false]]);
    vi.advanceTimersByTime(RECONNECT_MS * 3);
    expect(hls.startLoad).toHaveBeenCalledTimes(2);
  });

  it('reloads the master when the manifest never arrived', () => {
    const { hls } = start();
    hls.error({ fatal: true, type: 'networkError', response: { code: 0 } });
    vi.advanceTimersByTime(RECONNECT_MS);
    expect(hls.loadSource).toHaveBeenCalledTimes(2);
    expect(hls.startLoad).not.toHaveBeenCalled();
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
