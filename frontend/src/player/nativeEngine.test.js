import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { POLL_MS, RELOAD_SPACING_MS, STALL_MS, bufferAhead, startNative } from './nativeEngine.js';

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

const renditions = [
  { id: 'original', height: 820, bitrate: 15000000, copy: true },
  { id: '720p', height: 720, bitrate: 2500000, copy: false },
  { id: '480p', height: 480, bitrate: 1000000, copy: false },
];
const session = { masterUrl: '/api/sessions/abc/master.m3u8', renditions };

// A <video> whose buffered ranges and decoded height the test controls (jsdom has no media pipeline).
function videoWith({ buffered = [], height = 0 } = {}) {
  const video = document.createElement('video');
  let ranges = buffered;
  let h = height;
  Object.defineProperty(video, 'buffered', {
    get: () => ({
      length: ranges.length,
      start: (i) => ranges[i][0],
      end: (i) => ranges[i][1],
    }),
  });
  Object.defineProperty(video, 'videoHeight', { get: () => h });
  video.load = vi.fn();
  return {
    video,
    setBuffered: (next) => (ranges = next),
    setHeight: (next) => (h = next),
  };
}

const ok = (status = 200) => vi.fn(async () => ({ status }));

function start({ fetchImpl = ok(), playing = true, ...over } = {}) {
  const media = videoWith();
  if (playing) media.video.play();
  const handlers = {
    onLevel: vi.fn(),
    onReconnecting: vi.fn(),
    onReload: vi.fn(),
    onSessionLost: vi.fn(),
  };
  const engine = startNative({ video: media.video, session, handlers, fetchImpl, ...over });
  return { engine, ...media, handlers, fetchImpl };
}

const tick = (ms) => vi.advanceTimersByTimeAsync(ms);
const src = (video) => video.getAttribute('src');

describe('bufferAhead', () => {
  it('is the seconds buffered past the playhead in the range it is inside', () => {
    const { video, setBuffered } = videoWith({ buffered: [[0, 30]] });
    video.currentTime = 10;
    expect(bufferAhead(video)).toBe(20);
    setBuffered([
      [0, 5],
      [8, 40],
    ]);
    expect(bufferAhead(video)).toBe(30);
  });

  it('is 0 when the playhead is outside every range', () => {
    const { video, setBuffered } = videoWith({ buffered: [[0, 5]] });
    video.currentTime = 10;
    expect(bufferAhead(video)).toBe(0);
    setBuffered([]);
    expect(bufferAhead(video)).toBe(0);
  });
});

describe('starting', () => {
  it('plays the master playlist natively', () => {
    const { video } = start();
    expect(src(video)).toBe('/api/sessions/abc/master.m3u8');
  });
});

describe('level detection from the video height (Safari adapts by itself)', () => {
  it('names the rung from videoHeight on resize, the first as the initial level', () => {
    const { video, setHeight, handlers } = start();
    setHeight(720);
    video.dispatchEvent(new Event('resize'));
    setHeight(480);
    video.dispatchEvent(new Event('resize'));
    expect(handlers.onLevel.mock.calls).toEqual([
      ['720p', { initial: true }],
      ['480p', { initial: false }],
    ]);
  });

  it('reads the height on loadedmetadata too', () => {
    const { video, setHeight, handlers } = start();
    setHeight(820);
    video.dispatchEvent(new Event('loadedmetadata'));
    expect(handlers.onLevel).toHaveBeenCalledWith('original', { initial: true });
  });

  it('reports nothing for an unchanged height, an unknown height or no height yet', () => {
    const { video, setHeight, handlers } = start();
    video.dispatchEvent(new Event('resize'));
    setHeight(720);
    video.dispatchEvent(new Event('resize'));
    video.dispatchEvent(new Event('resize'));
    setHeight(333);
    video.dispatchEvent(new Event('resize'));
    expect(handlers.onLevel).toHaveBeenCalledTimes(1);
  });
});

describe('stalls', () => {
  it('does nothing for `stalled` alone: it fires while buffered video still plays', async () => {
    const { video, setBuffered, fetchImpl, handlers } = start();
    video.currentTime = 10;
    setBuffered([[0, 40]]);
    video.dispatchEvent(new Event('stalled'));
    await tick(POLL_MS);
    expect(handlers.onReconnecting).not.toHaveBeenCalled();
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it('does not reload while the buffer ahead still has video, however long nothing moves', async () => {
    const { video, setBuffered, fetchImpl } = start();
    video.currentTime = 10;
    setBuffered([[0, 40]]);
    await tick(STALL_MS * 4);
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it('does not reload a paused player', async () => {
    const { video, fetchImpl } = start({ playing: false });
    expect(video.paused).toBe(true);
    await tick(STALL_MS * 4);
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it('reloads after 15 s with no progress, playing, with nothing buffered: 14 s no, 15 s yes', async () => {
    const { video, fetchImpl, handlers } = start();
    video.currentTime = 600;
    await tick(STALL_MS - POLL_MS);
    expect(fetchImpl).not.toHaveBeenCalled();
    await tick(POLL_MS);
    expect(handlers.onReconnecting).toHaveBeenCalledWith(true);
    expect(fetchImpl).toHaveBeenCalledWith('/api/sessions/abc/master.m3u8', {
      credentials: 'same-origin',
      signal: expect.any(AbortSignal),
    });
  });

  it('progress moves the stall clock', async () => {
    const { video, fetchImpl } = start();
    await tick(STALL_MS - POLL_MS);
    video.currentTime = 5;
    video.dispatchEvent(new Event('timeupdate'));
    await tick(STALL_MS - POLL_MS);
    expect(fetchImpl).not.toHaveBeenCalled();
    await tick(POLL_MS);
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });

  it('a timeupdate that did not move the playhead does not count as progress', async () => {
    const { video, fetchImpl } = start();
    await tick(STALL_MS - POLL_MS);
    video.dispatchEvent(new Event('timeupdate'));
    await tick(POLL_MS);
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });
});

describe('playing again after a long pause', () => {
  it('waits the stall window from play, not from the last progress, before reloading', async () => {
    const { video, fetchImpl, handlers } = start({ playing: false });
    video.currentTime = 600;
    await tick(STALL_MS * 4);
    video.play();
    await tick(STALL_MS - POLL_MS);
    expect(fetchImpl).not.toHaveBeenCalled();
    expect(handlers.onReconnecting).not.toHaveBeenCalled();
    await tick(POLL_MS);
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });

  it('still reloads exactly once when nothing moves for the stall window after play', async () => {
    const { video, fetchImpl, handlers } = start({ playing: false });
    video.currentTime = 600;
    await tick(STALL_MS * 4);
    video.play();
    await tick(STALL_MS + POLL_MS);
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    expect(handlers.onReload).toHaveBeenCalledTimes(1);
    expect(video.load).toHaveBeenCalledTimes(1);
  });
});

describe('reloading at the playhead', () => {
  it('tells the caller where to seek back to, then reloads the same source', async () => {
    const { video, handlers } = start();
    video.currentTime = 1234.5;
    await tick(STALL_MS);
    expect(handlers.onReload).toHaveBeenCalledWith(1234500);
    expect(handlers.onReconnecting.mock.calls).toEqual([[true]]);
    expect(video.load).toHaveBeenCalledTimes(1);
    expect(src(video)).toBe('/api/sessions/abc/master.m3u8');
  });

  it('calls onReload before the source changes', async () => {
    const { video, handlers } = start();
    video.currentTime = 50;
    let srcWhenCalled;
    handlers.onReload.mockImplementation(() => {
      srcWhenCalled = video.load.mock.calls.length;
    });
    await tick(STALL_MS);
    expect(srcWhenCalled).toBe(0);
  });

  it('reloads on a fatal error at once, even while paused', async () => {
    const { video, handlers } = start({ playing: false });
    video.currentTime = 77;
    video.dispatchEvent(new Event('error'));
    await tick(0);
    expect(handlers.onReload).toHaveBeenCalledWith(77000);
    expect(handlers.onReconnecting).toHaveBeenCalledWith(true);
  });

  it('clears Reconnecting once the playhead moves again', async () => {
    const { video, handlers } = start();
    video.currentTime = 600;
    await tick(STALL_MS);
    video.currentTime = 600.5;
    video.dispatchEvent(new Event('timeupdate'));
    expect(handlers.onReconnecting.mock.calls).toEqual([[true], [false]]);
    await tick(STALL_MS - POLL_MS); // recovered: no more reloads while it keeps playing
    expect(handlers.onReload).toHaveBeenCalledTimes(1);
  });

  it('keeps retrying every 5 s while offline, from the same position, not the reset playhead', async () => {
    const offline = vi.fn(async () => {
      throw new TypeError('offline');
    });
    const { video, fetchImpl, handlers } = start({ fetchImpl: offline });
    video.currentTime = 900;
    await tick(STALL_MS);
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    video.currentTime = 0; // the element has been reset by an earlier reload
    await tick(RELOAD_SPACING_MS - POLL_MS);
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    await tick(POLL_MS);
    expect(fetchImpl).toHaveBeenCalledTimes(2);
    expect(handlers.onReconnecting.mock.calls).toEqual([[true]]);
    expect(handlers.onReload).not.toHaveBeenCalled();
    fetchImpl.mockImplementation(async () => ({ status: 200 }));
    await tick(RELOAD_SPACING_MS);
    expect(handlers.onReload).toHaveBeenCalledWith(900000);
  });

  it('keeps waiting on a server error rather than reloading into it', async () => {
    const { video, handlers } = start({ fetchImpl: ok(503) });
    video.currentTime = 10;
    await tick(STALL_MS);
    expect(handlers.onReload).not.toHaveBeenCalled();
    expect(handlers.onSessionLost).not.toHaveBeenCalled();
  });
});

const CHECK_TIMEOUT_MS = 10000;

// A fetch the test settles by hand; it rejects with an AbortError when its signal aborts, like the real one.
function pendingFetch() {
  const calls = [];
  const fetchImpl = vi.fn(
    (url, { signal } = {}) =>
      new Promise((resolve, reject) => {
        calls.push({ resolve, signal });
        signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
      }),
  );
  return { fetchImpl, calls };
}

describe('one recovery check at a time', () => {
  it('two slow checks cause at most one reload', async () => {
    const { fetchImpl, calls } = pendingFetch();
    const { video, handlers } = start({ fetchImpl, checkTimeoutMs: CHECK_TIMEOUT_MS });
    video.currentTime = 10;
    await tick(STALL_MS + CHECK_TIMEOUT_MS - POLL_MS); // spacing passes while the first check still hangs
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    calls[0].resolve({ status: 200 });
    await tick(0);
    expect(handlers.onReload).toHaveBeenCalledTimes(1);
    expect(video.load).toHaveBeenCalledTimes(1);
  });

  it('aborts a check that times out, then tries again', async () => {
    const { fetchImpl, calls } = pendingFetch();
    const { video, handlers } = start({ fetchImpl, checkTimeoutMs: CHECK_TIMEOUT_MS });
    video.currentTime = 10;
    await tick(STALL_MS);
    expect(calls[0].signal.aborted).toBe(false);
    await tick(CHECK_TIMEOUT_MS);
    expect(calls[0].signal.aborted).toBe(true);
    expect(handlers.onReload).not.toHaveBeenCalled();
    await tick(RELOAD_SPACING_MS);
    expect(fetchImpl).toHaveBeenCalledTimes(2);
  });

  it('ignores a response that arrives after playback resumed (stale generation)', async () => {
    const fetchImpl = vi.fn(() => new Promise((r) => setTimeout(() => r({ status: 200 }), 3000)));
    const { video, handlers } = start({ fetchImpl });
    video.currentTime = 10;
    await tick(STALL_MS);
    video.currentTime = 11;
    video.dispatchEvent(new Event('timeupdate')); // recovered by itself
    await tick(3000);
    expect(handlers.onReload).not.toHaveBeenCalled();
    expect(handlers.onSessionLost).not.toHaveBeenCalled();
    expect(video.load).not.toHaveBeenCalled();
  });

  it('does not reload when playback already resumed, and aborts the check', async () => {
    const { fetchImpl, calls } = pendingFetch();
    const { video, handlers } = start({ fetchImpl });
    video.currentTime = 10;
    await tick(STALL_MS);
    video.currentTime = 10.5;
    video.dispatchEvent(new Event('timeupdate'));
    expect(calls[0].signal.aborted).toBe(true);
    await tick(0);
    expect(handlers.onReload).not.toHaveBeenCalled();
    expect(video.load).not.toHaveBeenCalled();
  });

  it('never rewinds: resumes from the later of the trouble position and the current time', async () => {
    const { fetchImpl, calls } = pendingFetch();
    const { video, handlers } = start({ fetchImpl });
    video.currentTime = 100;
    await tick(STALL_MS);
    video.currentTime = 140; // advanced during the check, without a timeupdate
    calls[0].resolve({ status: 200 });
    await tick(0);
    expect(handlers.onReload).toHaveBeenCalledWith(140000);
  });
});

describe('an expired session', () => {
  it('asks for a new session instead of reloading a master that is gone', async () => {
    const { video, handlers } = start({ fetchImpl: ok(404) });
    video.currentTime = 10;
    await tick(STALL_MS);
    expect(handlers.onSessionLost).toHaveBeenCalledTimes(1);
    expect(handlers.onReload).not.toHaveBeenCalled();
    expect(video.load).not.toHaveBeenCalled();
  });
});

describe('destroy', () => {
  it('stops the poll and the listeners', async () => {
    const { engine, video, setHeight, fetchImpl, handlers } = start();
    engine.destroy();
    expect(vi.getTimerCount()).toBe(0);
    setHeight(720);
    video.dispatchEvent(new Event('resize'));
    video.dispatchEvent(new Event('error'));
    video.dispatchEvent(new Event('play'));
    await tick(STALL_MS * 2);
    expect(handlers.onLevel).not.toHaveBeenCalled();
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it('does not reload if destroyed while the session check was in flight', async () => {
    let resolve;
    const slow = vi.fn(() => new Promise((r) => (resolve = r)));
    const { engine, video, handlers } = start({ fetchImpl: slow });
    video.currentTime = 10;
    await tick(STALL_MS);
    engine.destroy();
    resolve({ status: 200 });
    await tick(0);
    expect(handlers.onReload).not.toHaveBeenCalled();
    expect(video.load).not.toHaveBeenCalled();
  });
});

describe('the connection coming back', () => {
  const online = () => window.dispatchEvent(new Event('online'));

  it('checks at once while reconnecting, ignoring the 5 s spacing', async () => {
    const offline = vi.fn(async () => {
      throw new TypeError('offline');
    });
    const { video, fetchImpl, handlers } = start({ fetchImpl: offline });
    video.currentTime = 900;
    await tick(STALL_MS);
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    expect(handlers.onReconnecting.mock.calls).toEqual([[true]]);
    fetchImpl.mockImplementation(async () => ({ status: 200 }));
    await tick(1000); // well inside the 5 s spacing
    online();
    await tick(0);
    expect(fetchImpl).toHaveBeenCalledTimes(2);
    expect(handlers.onReload).toHaveBeenCalledWith(900000);
  });

  it('leaves a check already in flight to finish', async () => {
    const slow = vi.fn(() => new Promise(() => {}));
    const { video, fetchImpl } = start({ fetchImpl: slow });
    video.currentTime = 10;
    await tick(STALL_MS);
    online();
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });

  it('does nothing while playing', async () => {
    const { fetchImpl } = start();
    online();
    await tick(0);
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it('removes its online listener on destroy', () => {
    const remove = vi.spyOn(window, 'removeEventListener');
    const { engine } = start();
    engine.destroy();
    expect(remove).toHaveBeenCalledWith('online', expect.any(Function));
    remove.mockRestore();
  });

  it('does nothing after destroy()', async () => {
    const offline = vi.fn(async () => {
      throw new TypeError('offline');
    });
    const { engine, video, fetchImpl } = start({ fetchImpl: offline });
    video.currentTime = 900;
    await tick(STALL_MS);
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    engine.destroy();
    online();
    await tick(0);
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });
});
