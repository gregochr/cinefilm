import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FakeHls } from '../test/fakeHls.js';
import { chooseEngine, HLS_MIME, startStream } from './streamSource.js';

vi.mock('hls.js/light', async () => ({ default: (await import('../test/fakeHls.js')).FakeHls }));
// The full build must never be imported: loading it fails the test that tries.
vi.mock('hls.js', () => {
  throw new Error('the full hls.js build was imported; the player uses hls.js/light');
});

beforeEach(() => {
  FakeHls.reset();
  window.MediaSource = class {};
});
afterEach(() => {
  delete window.MediaSource;
});

const session = {
  masterUrl: '/api/sessions/abc/master.m3u8',
  renditions: [{ id: 'original', height: 820, bitrate: 15000000, copy: true }],
};
const handlers = {
  onLevel: vi.fn(),
  onThroughput: vi.fn(),
  onReconnecting: vi.fn(),
  onReload: vi.fn(),
  onSessionLost: vi.fn(),
  onFatal: vi.fn(),
};

// A <video> that can (Safari) or cannot (Chrome, Firefox) play HLS itself.
function videoThat(nativeHls) {
  const video = document.createElement('video');
  video.canPlayType = vi.fn((type) => (type === HLS_MIME && nativeHls ? 'maybe' : ''));
  return video;
}

const start = (video, over = {}) =>
  startStream({ video, session, auto: true, startSeconds: 0, handlers, ...over });

describe('choosing how to play a session', () => {
  it('is null without Media Source and without native HLS', async () => {
    delete window.MediaSource;
    expect(await start(videoThat(false))).toBeNull();
  });

  it('still picks hls.js when canPlayType claims native HLS but Media Source exists (Chrome)', async () => {
    const video = videoThat(true);
    const engine = await start(video);
    expect(FakeHls.instances).toHaveLength(1);
    expect(video.getAttribute('src')).toBeNull();
    engine.destroy();
  });

  it('uses the browser’s own HLS only without Media Source (iPhone), without loading hls.js', async () => {
    delete window.MediaSource;
    const loadHls = vi.fn();
    const video = videoThat(true);
    const engine = await start(video, { loadHls });
    expect(video.canPlayType).toHaveBeenCalledWith('application/vnd.apple.mpegurl');
    expect(video.getAttribute('src')).toBe('/api/sessions/abc/master.m3u8');
    expect(loadHls).not.toHaveBeenCalled();
    expect(FakeHls.instances).toHaveLength(0);
    engine.destroy();
  });

  it('uses hls.js, lazy-loaded, where the browser has none (Chrome, Firefox)', async () => {
    const video = videoThat(false);
    const engine = await start(video);
    expect(FakeHls.instances).toHaveLength(1);
    expect(FakeHls.last.loadSource).toHaveBeenCalledWith('/api/sessions/abc/master.m3u8');
    expect(FakeHls.last.attachMedia).toHaveBeenCalledWith(video);
    expect(video.getAttribute('src')).toBeNull();
    engine.destroy();
    expect(FakeHls.last.destroy).toHaveBeenCalledTimes(1);
  });

  it('loads the light build of hls.js, never the full one', async () => {
    await start(videoThat(false));
    expect(FakeHls.instances).toHaveLength(1);
  });

  it('hands hls.js the start position and whether Auto is on', async () => {
    const engine = await start(videoThat(false), { startSeconds: 90, auto: false });
    expect(FakeHls.last.config.startPosition).toBe(90);
    FakeHls.last.manifest([{ height: 820, bitrate: 15000000 }], 0);
    expect(FakeHls.last.autoLevelCapping).toBe(-1);
    engine.destroy();
  });

  it('is null (direct play) when hls.js says the browser cannot play HLS', async () => {
    FakeHls.supported = false;
    expect(await start(videoThat(false))).toBeNull();
    expect(FakeHls.instances).toHaveLength(0);
  });

  it('attaches nothing when cancelled while hls.js was loading', async () => {
    let cancelled = false;
    const loadHls = vi.fn(async () => {
      cancelled = true;
      return FakeHls;
    });
    const video = videoThat(false);
    expect(await start(video, { loadHls, isCancelled: () => cancelled })).toBeNull();
    expect(FakeHls.instances).toHaveLength(0);
    expect(video.getAttribute('src')).toBeNull();
  });

  it('still starts when the predicate says it is not cancelled', async () => {
    const engine = await start(videoThat(false), { isCancelled: () => false });
    expect(FakeHls.instances).toHaveLength(1);
    engine.destroy();
  });

  it('is null (direct play) when hls.js cannot be loaded although the server answers', async () => {
    const loadHls = vi.fn().mockRejectedValue(new Error('chunk failed'));
    expect(await start(videoThat(false), { loadHls })).toBeNull();
    expect(loadHls).toHaveBeenCalledTimes(2); // the first try, and one more at once
  });

  it('says which engine it started: hlsjs, with its destroy', async () => {
    const engine = await start(videoThat(false));
    expect(engine.kind).toBe('hlsjs');
    expect(typeof engine.destroy).toBe('function');
    engine.destroy();
    expect(FakeHls.last.destroy).toHaveBeenCalledTimes(1);
  });

  it('says which engine it started: native, with its destroy', async () => {
    delete window.MediaSource;
    const engine = await start(videoThat(true));
    expect(engine.kind).toBe('native');
    expect(typeof engine.destroy).toBe('function');
    engine.destroy();
  });

  it.each([
    [true, true, true, 'hlsjs'],
    [true, true, false, 'hlsjs'],
    [true, false, true, null],
    [true, false, false, null],
    [false, true, true, 'native'],
    [false, false, true, 'native'],
    [false, true, false, null],
    [false, false, false, null],
  ])(
    'chooseEngine: mediaSource=%s hlsSupported=%s native=%s -> %s',
    (mediaSource, hls, native, expected) => {
      expect(
        chooseEngine({ hasMediaSource: mediaSource, hlsSupported: hls, nativeHls: native }),
      ).toBe(expected);
    },
  );
});

// A failed hls.js chunk: ask the server before waiting. Plan: poor-link-plan.md, section 1 ("The hls.js chunk").
describe('when hls.js cannot be loaded', () => {
  const failing = (...results) => {
    const loadHls = vi.fn();
    results.forEach((result) =>
      result === 'fail'
        ? loadHls.mockRejectedValueOnce(new Error('chunk failed'))
        : loadHls.mockResolvedValueOnce(FakeHls),
    );
    return loadHls;
  };

  it('the server answers: one more try at once, and a second try that works starts the engine', async () => {
    const loadHls = failing('fail', 'ok');
    const reachable = vi.fn().mockResolvedValue(true);
    const wait = vi.fn();
    const engine = await start(videoThat(false), { loadHls, reachable, wait });
    expect(loadHls).toHaveBeenCalledTimes(2);
    expect(wait).not.toHaveBeenCalled();
    expect(engine.kind).toBe('hlsjs');
    engine.destroy();
  });

  it('the server answers but the second try fails too: null, and no notice', async () => {
    const onRetrying = vi.fn();
    const loadHls = failing('fail', 'fail', 'fail');
    const result = await start(videoThat(false), {
      loadHls,
      reachable: vi.fn().mockResolvedValue(true),
      onRetrying,
    });
    expect(result).toBeNull();
    expect(loadHls).toHaveBeenCalledTimes(2);
    expect(onRetrying).not.toHaveBeenCalled();
    expect(FakeHls.instances).toHaveLength(0);
  });

  it('the server does not answer (or answers 502): Reconnecting, tried again after 1 s, 2 s, 4 s, then the engine starts', async () => {
    const onRetrying = vi.fn();
    const loadHls = failing('fail', 'fail', 'fail', 'ok');
    const reachable = vi.fn().mockResolvedValue(false);
    const wait = vi.fn().mockResolvedValue(undefined);
    const engine = await start(videoThat(false), { loadHls, reachable, wait, onRetrying });
    expect(wait.mock.calls).toEqual([[1000], [2000], [4000]]);
    expect(onRetrying.mock.calls).toEqual([[true], [false]]);
    expect(loadHls).toHaveBeenCalledTimes(4);
    expect(FakeHls.instances).toHaveLength(1);
    engine.destroy();
  });

  it('keeps trying after twenty failures with no answer', async () => {
    const loadHls = vi.fn();
    for (let i = 0; i < 20; i += 1) loadHls.mockRejectedValueOnce(new Error('chunk failed'));
    loadHls.mockResolvedValue(FakeHls);
    const wait = vi.fn().mockResolvedValue(undefined);
    const engine = await start(videoThat(false), {
      loadHls,
      reachable: vi.fn().mockResolvedValue(false),
      wait,
    });
    expect(engine.kind).toBe('hlsjs');
    expect(wait).toHaveBeenCalledTimes(20);
    engine.destroy();
  });

  it('a server that answers after a failed wait gives one more try and then null, notice off', async () => {
    const reachable = vi.fn().mockResolvedValueOnce(false).mockResolvedValue(true);
    const onRetrying = vi.fn();
    const result = await start(videoThat(false), {
      loadHls: failing('fail', 'fail', 'fail'),
      reachable,
      wait: vi.fn().mockResolvedValue(undefined),
      onRetrying,
    });
    expect(result).toBeNull();
    expect(onRetrying.mock.calls).toEqual([[true], [false]]);
  });

  it('cancelled during a wait: null, with hls.js never built and nothing called back after', async () => {
    let cancelled = false;
    const onRetrying = vi.fn();
    const wait = vi.fn().mockImplementation(async () => {
      cancelled = true;
    });
    const loadHls = failing('fail', 'ok');
    const result = await start(videoThat(false), {
      loadHls,
      reachable: vi.fn().mockResolvedValue(false),
      wait,
      isCancelled: () => cancelled,
      onRetrying,
    });
    expect(result).toBeNull();
    expect(loadHls).toHaveBeenCalledTimes(1); // no further try after the cancelled wait
    expect(FakeHls.instances).toHaveLength(0);
    expect(onRetrying.mock.calls).toEqual([[true]]); // only what came before the cancellation
  });

  it('cancelled while the server is asked: null and nothing more', async () => {
    let cancelled = false;
    const loadHls = failing('fail', 'ok');
    const reachable = vi.fn().mockImplementation(async () => {
      cancelled = true;
      return false;
    });
    const onRetrying = vi.fn();
    const wait = vi.fn();
    const result = await start(videoThat(false), {
      loadHls,
      reachable,
      wait,
      isCancelled: () => cancelled,
      onRetrying,
    });
    expect(result).toBeNull();
    expect(loadHls).toHaveBeenCalledTimes(1);
    expect(wait).not.toHaveBeenCalled();
    expect(onRetrying).not.toHaveBeenCalled();
  });

  it('cancelled straight after the first failure: null without asking the server', async () => {
    const reachable = vi.fn();
    const result = await start(videoThat(false), {
      loadHls: failing('fail'),
      reachable,
      isCancelled: () => true,
    });
    expect(result).toBeNull();
    expect(reachable).not.toHaveBeenCalled();
  });

  it('really waits between tries with its own timer by default', async () => {
    vi.useFakeTimers();
    try {
      const loadHls = failing('fail', 'ok');
      const pending = start(videoThat(false), {
        loadHls,
        reachable: vi.fn().mockResolvedValue(false),
      });
      await vi.advanceTimersByTimeAsync(999);
      expect(loadHls).toHaveBeenCalledTimes(1);
      await vi.advanceTimersByTimeAsync(1);
      const engine = await pending;
      expect(loadHls).toHaveBeenCalledTimes(2);
      engine.destroy();
    } finally {
      vi.useRealTimers();
    }
  });
});
