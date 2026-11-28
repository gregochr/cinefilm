import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { feedText, inputAtStart, northTrack } from '../test/lookaheadFixtures.js';
import { fixtures, installFetch, respond } from '../test/mockApi.jsx';
import { createLookaheadSchedule } from './lookaheadSchedule.js';
import { measureThroughput } from './probe.js';
import { startStream } from './streamSource.js';
import { resetThroughputState } from './throughput.js';
import { useStream } from './useStream.js';

// `useStream` with the real scheduler, matcher and input: the engine, the probe, the sessions API's `fetch` and the
// timers are the fakes (a timer here only runs when the test fires it).
vi.mock('./streamSource.js', () => ({ startStream: vi.fn() }));
vi.mock('./probe.js', () => ({ measureThroughput: vi.fn() }));

const SESSION = fixtures.session;
let engines;

const newEngine = () => ({ destroy: vi.fn(), kind: 'hlsjs', bankInput: vi.fn() });

beforeEach(() => {
  engines = [];
  startStream.mockReset().mockImplementation(async () => {
    const engine = newEngine();
    engines.push(engine);
    return engine;
  });
  measureThroughput.mockReset().mockResolvedValue(null);
  sessionStorage.clear();
  resetThroughputState();
  installFetch((method, url) => {
    if (method === 'POST' && url === '/api/editions/11/sessions') return respond(SESSION, 201);
    if (method === 'DELETE' && url.startsWith('/api/sessions/')) return respond(null, 204);
    return null;
  });
});
afterEach(() => vi.unstubAllGlobals());

/** A schedule whose interval runs only when the test calls `fire()`; the clock is the test's `clock.ms`. */
function schedule() {
  const clock = { ms: 1_000_000 };
  const intervals = new Map();
  const timers = {
    setTimeout: vi.fn(() => 1),
    clearTimeout: vi.fn(),
    setInterval: vi.fn((fn) => {
      intervals.set(intervals.size + 1, fn);
      return intervals.size;
    }),
    clearInterval: vi.fn((id) => intervals.delete(id)),
  };
  const ask = vi.fn(() => Promise.resolve(feedText()));
  const real = createLookaheadSchedule({
    ask,
    loadMap: () => Promise.resolve([northTrack()]),
    now: () => clock.ms,
    timers,
  });
  return {
    real,
    clock,
    ask,
    fire: () => intervals.forEach((fn) => fn()),
    attachSpy: vi.spyOn(real, 'attach'),
    detachSpy: vi.spyOn(real, 'detach'),
  };
}

function mount(lookahead, overrides = {}) {
  const props = {
    videoRef: { current: document.createElement('video') },
    edition: { id: 11, parts: 1, qualities: [], file: { height: 1080 } },
    quality: '720p',
    audioStreamId: null,
    getPositionMs: () => 0,
    initialSeekMs: 0,
    onToast: vi.fn(),
    lookahead,
    ...overrides,
  };
  return { ...renderHook((p) => useStream(p), { initialProps: props }), props };
}

const engineCount = (n) => vi.waitFor(() => expect(engines).toHaveLength(n));
const flush = () => act(async () => {});

describe('the engine the stream builds and the look-ahead', () => {
  it('receives the look-ahead input, counted down by the clock, once the schedule has an answer', async () => {
    const s = schedule();
    s.real.begin();
    await flush();
    await flush();
    const hook = mount(s.real);
    await engineCount(1);
    // The input already stood when the engine arrived: it was handed over at once.
    expect(engines[0].bankInput).toHaveBeenCalledTimes(1);
    expect(engines[0].bankInput).toHaveBeenLastCalledWith(inputAtStart(0));
    s.clock.ms += 4000;
    s.fire();
    expect(engines[0].bankInput).toHaveBeenLastCalledWith(inputAtStart(4));
    hook.unmount();
  });

  it('hands the next engine, built for another quality, the current input at once', async () => {
    const s = schedule();
    s.real.begin();
    await flush();
    await flush();
    const hook = mount(s.real);
    await engineCount(1);
    s.clock.ms += 7000;
    hook.rerender({ ...hook.props, quality: '480p' });
    await engineCount(2);
    expect(engines[1].bankInput.mock.calls[0]).toEqual([inputAtStart(7)]);
    // The old engine is not fed again.
    engines[0].bankInput.mockClear();
    await flush();
    s.fire();
    expect(engines[0].bankInput).not.toHaveBeenCalled();
    // The new engine's own ask was answered meanwhile: the train is placed afresh.
    expect(engines[1].bankInput).toHaveBeenLastCalledWith(inputAtStart(0));
    hook.unmount();
  });

  it('lets go of the engine when the hook unmounts', async () => {
    const s = schedule();
    const hook = mount(s.real);
    await engineCount(1);
    expect(s.attachSpy).toHaveBeenCalledWith(engines[0]);
    hook.unmount();
    expect(s.detachSpy).toHaveBeenCalledWith(engines[0]);
  });

  it('lets go of the engine on a fatal error and when Admin stops the playback', async () => {
    const s = schedule();
    const hook = mount(s.real);
    await engineCount(1);
    act(() => startStream.mock.calls[0][0].handlers.onStopped());
    expect(s.detachSpy).toHaveBeenCalledWith(engines[0]);
    s.detachSpy.mockClear();

    act(() => hook.result.current.resume(0));
    await engineCount(2);
    act(() => startStream.mock.calls[1][0].handlers.onFatal());
    expect(s.detachSpy).toHaveBeenCalledWith(engines[1]);
    hook.unmount();
  });

  it('pauses the asks and the ticks while Admin has stopped the playback, and asks again on a new engine', async () => {
    const s = schedule();
    s.real.begin();
    const hook = mount(s.real);
    await engineCount(1);
    await flush();
    expect(s.ask).toHaveBeenCalledTimes(1);
    act(() => startStream.mock.calls[0][0].handlers.onStopped());
    engines[0].bankInput.mockClear();
    s.fire();
    await flush();
    expect(engines[0].bankInput).not.toHaveBeenCalled();
    expect(s.ask).toHaveBeenCalledTimes(1);

    act(() => hook.result.current.resume(0));
    await engineCount(2);
    expect(s.ask).toHaveBeenCalledTimes(2);
    hook.unmount();
  });

  it('is unchanged without a look-ahead', async () => {
    const hook = mount(null);
    await engineCount(1);
    hook.unmount();
    expect(engines[0].destroy).toHaveBeenCalledTimes(1);
    expect(engines[0].bankInput).not.toHaveBeenCalled();
  });
});
