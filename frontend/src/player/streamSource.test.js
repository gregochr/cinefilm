import { beforeEach, describe, expect, it, vi } from 'vitest';
import { FakeHls } from '../test/fakeHls.js';
import { HLS_MIME, startStream } from './streamSource.js';

vi.mock('hls.js', async () => ({ default: (await import('../test/fakeHls.js')).FakeHls }));

beforeEach(() => FakeHls.reset());

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
  it('uses the browser’s own HLS where it has it (Safari, iPhone), without loading hls.js', async () => {
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

  it('is null (direct play) when hls.js cannot be loaded', async () => {
    const loadHls = vi.fn().mockRejectedValue(new Error('chunk failed'));
    expect(await start(videoThat(false), { loadHls })).toBeNull();
  });
});
