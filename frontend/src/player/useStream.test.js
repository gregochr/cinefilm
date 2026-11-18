import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, respond } from '../test/mockApi.jsx';
import { AUTO, ORIGINAL } from './ladder.js';
import { measureThroughput } from './probe.js';
import { startStream } from './streamSource.js';
import { resetThroughputState } from './throughput.js';
import { MAX_RECOVERIES, useStream } from './useStream.js';

// The collaborators that would reach hls.js and the network probe are replaced; the sessions API runs for real
// against a stubbed `fetch`, as in the screen tests.
vi.mock('./streamSource.js', () => ({ startStream: vi.fn() }));
vi.mock('./probe.js', () => ({ measureThroughput: vi.fn() }));

const SESSION = fixtures.session;
const NO_COPY = {
  ...SESSION,
  renditions: SESSION.renditions.map((r) => ({ ...r, copy: false })),
};
const STREAM_URL = '/api/editions/11/stream';

const makeHealth = () => ({
  rung: vi.fn(),
  fetching: vi.fn(),
  throughput: vi.fn(),
  reconnecting: vi.fn(),
  recovered: vi.fn(),
  engine: vi.fn(),
  fellBack: vi.fn(),
  networkError: vi.fn(),
  end: vi.fn(),
});

let engine;

beforeEach(() => {
  engine = { destroy: vi.fn(), kind: 'hlsjs' };
  startStream.mockReset().mockResolvedValue(engine);
  measureThroughput.mockReset().mockResolvedValue(null);
  sessionStorage.clear();
  resetThroughputState();
});
afterEach(() => {
  vi.unstubAllGlobals();
});

/** Stubs fetch for the session routes and records what the hook posted and deleted. */
function serve({ create, remove } = {}) {
  const calls = { posted: [], deleted: [] };
  installFetch((method, url, init = {}) => {
    if (method === 'POST' && url === '/api/editions/11/sessions') {
      calls.posted.push(JSON.parse(init.body));
      return create ? create(calls.posted.length) : respond(SESSION, 201);
    }
    if (method === 'DELETE' && url.startsWith('/api/sessions/')) {
      calls.deleted.push({ id: url.split('/').pop(), keepalive: init.keepalive === true });
      return remove ? remove() : respond(null, 204);
    }
    return null;
  });
  return calls;
}

function mount(overrides = {}) {
  const video = document.createElement('video');
  const health = makeHealth();
  const onToast = vi.fn();
  const props = {
    videoRef: { current: video },
    edition: { id: 11, parts: 1, qualities: [], file: { height: 1080 } },
    quality: '720p',
    audioStreamId: null,
    getPositionMs: () => 0,
    initialSeekMs: 0,
    onToast,
    health,
    ...overrides,
  };
  const hook = renderHook((p) => useStream(p), { initialProps: props });
  return { ...hook, video, health, onToast, props };
}

const hlsReady = (hook) => vi.waitFor(() => expect(hook.result.current.mode).toBe('hls'));
const engineStarted = () => vi.waitFor(() => expect(startStream).toHaveBeenCalled());
const handlersOf = (n = 0) => startStream.mock.calls[n][0].handlers;

describe('creating the session and attaching the stream', () => {
  it('asks for a session at the chosen quality and start, and attaches the engine to it', async () => {
    const calls = serve();
    const hook = mount({ initialSeekMs: 90000 });

    await hlsReady(hook);
    await vi.waitFor(() => expect(hook.health.engine).toHaveBeenCalledWith('hlsjs'));

    expect(calls.posted).toEqual([{ quality: '720p', startMs: 90000 }]);
    expect(hook.result.current.rung).toBe('original');
    expect(hook.result.current.renditions).toEqual(SESSION.renditions);
    expect(startStream).toHaveBeenCalledTimes(1);
    expect(startStream.mock.calls[0][0]).toMatchObject({
      video: hook.video,
      session: SESSION,
      auto: false,
      startSeconds: 90,
    });
    expect(hook.health.rung).toHaveBeenCalledWith('original', 1080);
  });

  it('sends the chosen audio track and the viewing id', async () => {
    const calls = serve();
    const hook = mount({ audioStreamId: 3, health: { ...makeHealth(), id: 'viewing-1' } });

    await hlsReady(hook);

    expect(calls.posted[0]).toEqual({
      quality: '720p',
      startMs: 0,
      playbackId: 'viewing-1',
      audioStreamId: 3,
    });
  });

  it('leaves the audio track and viewing id out when there are none', async () => {
    const calls = serve();
    const hook = mount({ audioStreamId: undefined });

    await hlsReady(hook);

    expect(calls.posted[0]).toEqual({ quality: '720p', startMs: 0 });
  });

  it('measures the link first on Auto and starts on the rung the measurement allows', async () => {
    measureThroughput.mockResolvedValue(4000);
    const calls = serve();
    const hook = mount({ quality: AUTO });

    await hlsReady(hook);

    expect(measureThroughput).toHaveBeenCalledTimes(1);
    expect(calls.posted[0]).toEqual({ quality: AUTO, startMs: 0, probeKbps: 4000 });
    expect(startStream.mock.calls[0][0].auto).toBe(true);
    expect(hook.result.current.kbps).toBe(4000);
  });

  it('starts Auto on the lowest rung when the probe measured nothing', async () => {
    const calls = serve();
    const hook = mount({ quality: AUTO });

    await hlsReady(hook);

    expect(calls.posted[0].probeKbps).toBe(0);
  });

  it('does not probe a film split across files, and starts it on the lowest rung', async () => {
    const calls = serve();
    const hook = mount({
      quality: AUTO,
      edition: { id: 11, parts: 2, qualities: [], file: { height: 1080 } },
    });

    await hlsReady(hook);

    expect(measureThroughput).not.toHaveBeenCalled();
    expect(calls.posted[0].probeKbps).toBe(0);
  });

  it('a new quality drops the old session and asks for a new one', async () => {
    const calls = serve();
    const hook = mount();
    await hlsReady(hook);
    await vi.waitFor(() => expect(hook.health.engine).toHaveBeenCalledTimes(1));

    hook.rerender({ ...hook.props, quality: '480p' });

    await vi.waitFor(() => expect(calls.posted).toHaveLength(2));
    expect(calls.posted[1].quality).toBe('480p');
    expect(engine.destroy).toHaveBeenCalledTimes(1);
    expect(calls.deleted).toEqual([{ id: SESSION.sessionId, keepalive: true }]);
  });
});

describe('dropping the session', () => {
  it('removes it, with keepalive, and destroys the engine when the hook unmounts', async () => {
    const calls = serve();
    const hook = mount();
    await hlsReady(hook);
    await vi.waitFor(() => expect(hook.health.engine).toHaveBeenCalled());

    hook.unmount();

    await vi.waitFor(() =>
      expect(calls.deleted).toEqual([{ id: SESSION.sessionId, keepalive: true }]),
    );
    expect(engine.destroy).toHaveBeenCalledTimes(1);
  });

  it('removes it and direct-plays when no engine can play it', async () => {
    startStream.mockResolvedValue(null);
    const calls = serve();
    const hook = mount();

    await vi.waitFor(() => expect(hook.result.current.mode).toBe('direct'));

    expect(calls.deleted).toEqual([{ id: SESSION.sessionId, keepalive: false }]);
    expect(hook.video.src).toContain(STREAM_URL);
    expect(hook.health.fellBack).toHaveBeenCalledTimes(1);
    expect(hook.health.engine).toHaveBeenCalledWith('direct');
  });

  it('swallows a failed remove, on forget and on unmount, with no unhandled rejection', async () => {
    const unhandled = vi.fn();
    process.on('unhandledRejection', unhandled);
    try {
      startStream.mockResolvedValueOnce(null);
      const calls = serve({
        remove: () => {
          throw new TypeError('Failed to fetch');
        },
      });
      const first = mount();
      await vi.waitFor(() => expect(first.result.current.mode).toBe('direct'));
      await vi.waitFor(() => expect(calls.deleted).toHaveLength(1));

      const second = mount();
      await hlsReady(second);
      await vi.waitFor(() => expect(second.health.engine).toHaveBeenCalledWith('hlsjs'));
      second.unmount();
      await vi.waitFor(() => expect(calls.deleted).toHaveLength(2));
      await new Promise((resolve) => setImmediate(resolve));

      expect(unhandled).not.toHaveBeenCalled();
      expect(first.result.current.mode).toBe('direct');
    } finally {
      process.off('unhandledRejection', unhandled);
    }
  });

  it('drops a session that arrives after the hook moved on, and attaches nothing', async () => {
    let release;
    const arrives = new Promise((resolve) => {
      release = resolve;
    });
    const calls = serve({ create: () => arrives });
    const hook = mount();
    await vi.waitFor(() => expect(calls.posted).toHaveLength(1));

    hook.unmount();
    await act(async () => {
      release(respond(SESSION, 201));
      await arrives;
    });

    await vi.waitFor(() =>
      expect(calls.deleted).toEqual([{ id: SESSION.sessionId, keepalive: false }]),
    );
    expect(startStream).not.toHaveBeenCalled();
  });

  it('destroys an engine that finished loading after the hook moved on', async () => {
    let release;
    startStream.mockReturnValue(
      new Promise((resolve) => {
        release = resolve;
      }),
    );
    const calls = serve();
    const hook = mount();
    await engineStarted();

    hook.unmount();
    await act(async () => release(engine));

    expect(engine.destroy).toHaveBeenCalledTimes(1);
    await vi.waitFor(() => expect(calls.deleted).toHaveLength(1));
  });

  it('tells the server when the tab is closed, and only while a session exists', async () => {
    let release;
    const arrives = new Promise((resolve) => {
      release = resolve;
    });
    const calls = serve({ create: () => arrives });
    const hook = mount();
    await vi.waitFor(() => expect(calls.posted).toHaveLength(1));

    window.dispatchEvent(new Event('pagehide'));
    expect(calls.deleted).toEqual([]);

    await act(async () => {
      release(respond(SESSION, 201));
      await arrives;
    });
    await hlsReady(hook);
    window.dispatchEvent(new Event('pagehide'));

    await vi.waitFor(() =>
      expect(calls.deleted).toEqual([{ id: SESSION.sessionId, keepalive: true }]),
    );
  });
});

describe('the Original quality', () => {
  it('drops the session and plays the file when the server has no copy rung', async () => {
    const calls = serve({ create: () => respond(NO_COPY, 201) });
    const hook = mount({ quality: ORIGINAL });

    await vi.waitFor(() => expect(hook.result.current.mode).toBe('direct'));

    expect(calls.deleted).toEqual([{ id: SESSION.sessionId, keepalive: false }]);
    expect(startStream).not.toHaveBeenCalled();
    expect(hook.video.src).toContain(STREAM_URL);
    expect(hook.health.fellBack).not.toHaveBeenCalled();
    expect(hook.health.rung).toHaveBeenCalledWith(ORIGINAL, 1080);
  });

  it('streams the copy rung when the server has one', async () => {
    const calls = serve();
    const hook = mount({ quality: ORIGINAL });

    await hlsReady(hook);

    expect(calls.deleted).toEqual([]);
    expect(startStream).toHaveBeenCalledTimes(1);
  });

  it('plays the file at once, without a session, while the worker is offline', async () => {
    const calls = serve();
    const hook = mount({
      quality: ORIGINAL,
      edition: {
        id: 11,
        parts: 1,
        qualities: [{ id: AUTO, available: false }],
        file: { height: 1080 },
      },
      getPositionMs: () => 61234.6,
    });

    await vi.waitFor(() => expect(hook.result.current.mode).toBe('direct'));

    expect(calls.posted).toEqual([]);
    expect(hook.video.src).toContain(STREAM_URL);
    expect(hook.result.current.pendingSeekRef.current).toBe(61235);
  });
});

describe('a session that cannot be had', () => {
  it('falls back to direct play when the server refuses, with nothing to remove', async () => {
    const calls = serve({ create: () => respond({ error: 'No encoder' }, 400) });
    const hook = mount();

    await vi.waitFor(() => expect(hook.result.current.mode).toBe('direct'));

    expect(hook.health.fellBack).toHaveBeenCalledTimes(1);
    expect(calls.deleted).toEqual([]);
  });

  it('reports a split film as unavailable, not direct-played', async () => {
    serve({ create: () => respond({ error: 'No encoder' }, 400) });
    const hook = mount({ edition: { id: 11, parts: 2, qualities: [], file: null } });

    await vi.waitFor(() => expect(hook.result.current.mode).toBe('unavailable'));

    expect(hook.video.getAttribute('src')).toBeNull();
    expect(hook.health.fellBack).not.toHaveBeenCalled();
  });

  it('stops at the position it was at when Admin stopped the playback, and plays again from a chosen one', async () => {
    const calls = serve({
      create: (n) => (n === 1 ? respond({ error: 'Stopped' }, 410) : respond(SESSION, 201)),
    });
    let positionMs = 5000.4;
    const hook = mount({ getPositionMs: () => positionMs });

    await vi.waitFor(() => expect(hook.result.current.mode).toBe('stopped'));

    expect(hook.result.current.stoppedAtRef.current).toBe(5000);
    expect(hook.health.end).toHaveBeenCalledTimes(1);
    expect(startStream).not.toHaveBeenCalled();
    expect(hook.video.getAttribute('src')).toBeNull();

    positionMs = 0; // The element was reset when the stop took the source away.
    await act(async () => hook.result.current.resume(30000));

    expect(hook.result.current.stoppedAtRef.current).toBeNull();
    await hlsReady(hook);
    expect(calls.posted[1].startMs).toBe(30000);
    expect(hook.result.current.pendingSeekRef.current).toBe(30000);
  });
});

describe('what the running engine reports', () => {
  async function running(overrides) {
    const calls = serve();
    const hook = mount(overrides);
    await hlsReady(hook);
    await engineStarted();
    await vi.waitFor(() => expect(hook.health.engine).toHaveBeenCalledWith('hlsjs'));
    return { calls, hook };
  }

  it('shows a level change and, on Auto, says so when it steps down or up', async () => {
    const { hook } = await running({ quality: AUTO });

    act(() => handlersOf().onLevel('720p', { initial: false }));
    expect(hook.result.current.rung).toBe('720p');
    expect(hook.onToast).toHaveBeenLastCalledWith('Switching to 720p for smoother playback');
    expect(hook.health.rung).toHaveBeenLastCalledWith('720p', 720);

    act(() => handlersOf().onLevel('original', { initial: false }));
    expect(hook.onToast).toHaveBeenLastCalledWith('Back to Original');
  });

  it('keeps quiet about the first level on Auto', async () => {
    const { hook } = await running({ quality: AUTO });

    act(() => handlersOf().onLevel('720p', { initial: true }));

    expect(hook.result.current.rung).toBe('720p');
    expect(hook.onToast).not.toHaveBeenCalled();
  });

  it('keeps quiet about any level change on a fixed quality', async () => {
    const { hook } = await running({ quality: '720p' });

    act(() => handlersOf().onLevel('480p', { initial: false }));

    expect(hook.result.current.rung).toBe('480p');
    expect(hook.onToast).not.toHaveBeenCalled();
  });

  it('passes on what is being fetched and the network errors', async () => {
    const { hook } = await running();
    const error = new Error('offline');

    handlersOf().onFetching('720p');
    handlersOf().onNetworkError(error);

    expect(hook.health.fetching).toHaveBeenCalledWith('720p');
    expect(hook.health.networkError).toHaveBeenCalledWith(error);
  });

  it('keeps the throughput it is told of, and forgets it when the network changes', async () => {
    const { hook } = await running();

    act(() => handlersOf().onThroughput(3000));
    expect(hook.result.current.kbps).toBe(3000);
    expect(hook.health.throughput).toHaveBeenCalledWith(3000);

    act(() => window.dispatchEvent(new Event('offline')));
    expect(hook.result.current.kbps).toBeNull();

    act(() => handlersOf().onThroughput(2500));
    expect(hook.result.current.kbps).toBe(2500);
    act(() => window.dispatchEvent(new Event('online')));
    expect(hook.result.current.kbps).toBeNull();
  });

  it('shows and clears the reconnecting notice, once per change', async () => {
    const { hook } = await running();
    hook.health.reconnecting.mockClear();

    act(() => handlersOf().onReconnecting(true));
    act(() => handlersOf().onReconnecting(true));
    expect(hook.result.current.reconnecting).toBe(true);
    expect(hook.health.reconnecting).toHaveBeenCalledTimes(1);

    act(() => handlersOf().onReconnecting(false));
    expect(hook.result.current.reconnecting).toBe(false);
    expect(hook.health.reconnecting).toHaveBeenLastCalledWith(false);
  });

  it('seeks to the reload position and keeps a paused film paused afterwards', async () => {
    const { hook } = await running();

    handlersOf().onReload(45000);

    expect(hook.result.current.pendingSeekRef.current).toBe(45000);
    expect(hook.result.current.takePlayAfterLoad()).toBe(false);
    expect(hook.result.current.takePlayAfterLoad()).toBe(true);
  });

  it('leaves the pending seek alone when the reload has no position', async () => {
    const { hook } = await running();
    hook.result.current.pendingSeekRef.current = 7000;

    handlersOf().onReload(0);

    expect(hook.result.current.pendingSeekRef.current).toBe(7000);
  });

  it('recovers an expired session at the current position, up to the limit, then gives up', async () => {
    const { calls, hook } = await running({ getPositionMs: () => 12000 });

    for (let n = 1; n <= MAX_RECOVERIES; n += 1) {
      act(() => handlersOf(n - 1).onSessionLost());
      await vi.waitFor(() => expect(startStream).toHaveBeenCalledTimes(n + 1));
      expect(hook.health.recovered).toHaveBeenCalledTimes(n);
    }
    expect(calls.posted).toHaveLength(MAX_RECOVERIES + 1);
    expect(calls.posted[1].startMs).toBe(12000);
    expect(hook.result.current.mode).toBe('hls');

    act(() => handlersOf(MAX_RECOVERIES).onSessionLost());

    await vi.waitFor(() => expect(hook.result.current.mode).toBe('direct'));
    expect(hook.health.recovered).toHaveBeenCalledTimes(MAX_RECOVERIES);
    expect(hook.health.fellBack).toHaveBeenCalledTimes(1);
    expect(calls.posted).toHaveLength(MAX_RECOVERIES + 1);
  });

  it('gives up on HLS for the film on a fatal error: engine destroyed, session removed, file played', async () => {
    const { calls, hook } = await running();

    act(() => handlersOf().onFatal());

    expect(hook.result.current.mode).toBe('direct');
    expect(engine.destroy).toHaveBeenCalledTimes(1);
    expect(hook.video.src).toContain(STREAM_URL);
    await vi.waitFor(() =>
      expect(calls.deleted).toEqual([{ id: SESSION.sessionId, keepalive: false }]),
    );
  });

  it('stops without sending anything when the engine hears that Admin stopped the playback', async () => {
    const { calls, hook } = await running({ getPositionMs: () => 8000 });

    act(() => handlersOf().onStopped());

    expect(hook.result.current.mode).toBe('stopped');
    expect(hook.result.current.stoppedAtRef.current).toBe(8000);
    expect(engine.destroy).toHaveBeenCalledTimes(1);
    expect(hook.health.end).toHaveBeenCalledTimes(1);
    hook.unmount();
    expect(calls.deleted).toEqual([]);
  });

  it('restart asks for a new session', async () => {
    const { calls, hook } = await running();

    act(() => hook.result.current.restart());

    await vi.waitFor(() => expect(calls.posted).toHaveLength(2));
  });
});
