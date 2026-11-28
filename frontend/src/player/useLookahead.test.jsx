import { StrictMode } from 'react';
import { act, renderHook, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { installFetch, respond } from '../test/mockApi.jsx';
import { feedText, inputAtStart, northTrack } from '../test/lookaheadFixtures.js';
import { ASK_EVERY_MS } from './lookaheadSchedule.js';
import { FRAME_TEST_ID } from './trainFeedFrame.js';
import { useLookahead } from './useLookahead.js';

// The real hook, scheduler, matcher, input and map client: only the edges are fakes (the frame's answer, `fetch`
// and the clock).
beforeEach(() => vi.useFakeTimers());
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

const flush = () => act(async () => vi.advanceTimersByTimeAsync(0));
const tick = (ms) => act(async () => vi.advanceTimersByTimeAsync(ms));
const engineOf = () => ({ bankInput: vi.fn() });
const lastInput = (engine) => engine.bankInput.mock.calls.at(-1)?.[0];

function serveMap(tracks = [northTrack()]) {
  const fetchMock = installFetch((method, url) =>
    url === '/api/journeys/link-map' ? respond({ tracks }) : null,
  );
  const mapRequests = () =>
    fetchMock.mock.calls.filter(([url]) => url === '/api/journeys/link-map');
  return { mapRequests };
}

const mount = (props, options) =>
  renderHook((p) => useLookahead(p), { initialProps: props, ...options });

describe('the hook with the real modules', () => {
  it('hands an attached engine the input the matched answer gives, and counts it down', async () => {
    const { mapRequests } = serveMap();
    const ask = vi.fn(() => Promise.resolve(feedText()));
    const engine = engineOf();
    const hook = mount({ active: true, ask });
    hook.result.current.attach(engine);
    await flush();
    await flush();
    expect(mapRequests()).toHaveLength(1);
    expect(lastInput(engine)).toEqual(inputAtStart(0));
    await tick(3000);
    expect(lastInput(engine)).toEqual(inputAtStart(3));
  });

  it('asks nothing and loads no map while not active (at home)', async () => {
    const { mapRequests } = serveMap();
    const ask = vi.fn(() => Promise.resolve(feedText()));
    const engine = engineOf();
    const hook = mount({ active: false, ask });
    hook.result.current.attach(engine);
    act(() => window.dispatchEvent(new Event('online')));
    await tick(ASK_EVERY_MS * 2);
    expect(ask).not.toHaveBeenCalled();
    expect(mapRequests()).toHaveLength(0);
    expect(engine.bankInput).not.toHaveBeenCalled();
  });

  it('starts when it becomes active and stops, sending null, when it stops being so', async () => {
    serveMap();
    const ask = vi.fn(() => Promise.resolve(feedText()));
    const engine = engineOf();
    const hook = mount({ active: false, ask });
    hook.result.current.attach(engine);
    hook.rerender({ active: true, ask });
    await flush();
    await flush();
    expect(ask).toHaveBeenCalledTimes(1);
    hook.rerender({ active: false, ask });
    expect(lastInput(engine)).toBeNull();
    await tick(ASK_EVERY_MS * 2);
    expect(ask).toHaveBeenCalledTimes(1);
    expect(vi.getTimerCount()).toBe(0);
  });

  it.each(['online', 'offline'])('asks again on the window event %s', async (name) => {
    serveMap();
    const ask = vi.fn(() => Promise.resolve(null));
    const hook = mount({ active: true, ask });
    hook.result.current.attach(engineOf());
    await flush();
    expect(ask).toHaveBeenCalledTimes(1);
    act(() => window.dispatchEvent(new Event(name)));
    await flush();
    expect(ask).toHaveBeenCalledTimes(2);
  });

  it('stops on unmount: the ask is cancelled, the timers are gone, the engine gets null, the listeners go', async () => {
    serveMap();
    let signal;
    const ask = vi.fn((s) => {
      signal = s;
      return new Promise(() => {});
    });
    const engine = engineOf();
    const hook = mount({ active: true, ask });
    hook.result.current.attach(engine);
    await flush();
    hook.unmount();
    expect(signal.aborted).toBe(true);
    expect(lastInput(engine)).toBeNull();
    expect(vi.getTimerCount()).toBe(0);
    act(() => window.dispatchEvent(new Event('online')));
    expect(ask).toHaveBeenCalledTimes(1);
  });

  it('starts cleanly under StrictMode: started, stopped and started again, one ask once an engine is attached', async () => {
    serveMap();
    const ask = vi.fn(() => Promise.resolve(null));
    const hook = mount({ active: true, ask }, { wrapper: StrictMode });
    await flush();
    expect(ask).not.toHaveBeenCalled(); // no engine yet
    hook.result.current.attach(engineOf());
    await flush();
    expect(ask).toHaveBeenCalledTimes(1);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('carries on without a map when the server refuses it', async () => {
    installFetch((method, url) =>
      url === '/api/journeys/link-map' ? respond({ error: 'no' }, 500) : null,
    );
    const ask = vi.fn(() => Promise.resolve(feedText()));
    const engine = engineOf();
    const hook = mount({ active: true, ask });
    hook.result.current.attach(engine);
    await flush();
    await flush();
    await tick(2000);
    expect(lastInput(engine)).toBeNull();
  });
});

describe('the default ask: the real frame', () => {
  it('puts the sandboxed frame in the page at the start and takes it out when stopped', async () => {
    serveMap();
    const hook = mount({ active: true });
    hook.result.current.attach(engineOf());
    await flush();
    const frame = screen.queryByTestId(FRAME_TEST_ID);
    expect(frame).not.toBeNull();
    expect(frame.getAttribute('sandbox')).toBe('allow-scripts');
    expect(frame.srcdoc).toContain('https://www.ombord.info/api/jsonp/position/');
    hook.unmount();
    await flush();
    expect(screen.queryByTestId(FRAME_TEST_ID)).toBeNull();
  });

  it('asks nothing in the fixtures build', async () => {
    vi.stubEnv('VITE_FIXTURES', '1');
    serveMap();
    const hook = mount({ active: true });
    hook.result.current.attach(engineOf());
    await flush();
    expect(screen.queryByTestId(FRAME_TEST_ID)).toBeNull();
    expect(vi.getTimerCount()).toBe(0);
  });
});
