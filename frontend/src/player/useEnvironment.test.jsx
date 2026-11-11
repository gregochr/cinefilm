import { StrictMode } from 'react';
import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NETWORKS_KEY, recallAnswer } from '../state/networks.js';
import { installStorage } from '../test/storage.js';
import { useEnvironment } from './useEnvironment.js';

let storage;
beforeEach(() => {
  storage = installStorage();
});
afterEach(() => vi.unstubAllGlobals());

const deferred = () => {
  const handle = {};
  handle.promise = new Promise((resolve, reject) => {
    handle.resolve = resolve;
    handle.reject = reject;
  });
  return handle;
};
// Lets the measurement and the lookup run through their awaits.
const flush = async () => {
  for (let i = 0; i < 4; i += 1) await act(async () => {});
};
const stored = () => JSON.parse(storage.getItem(NETWORKS_KEY));

// Counts the renders, so "sets no state" can be asserted.
function mount({ latency, operator, wrapper } = {}) {
  const measure = vi.fn(() => Promise.resolve(latency));
  const lookup = vi.fn(() => Promise.resolve(operator));
  const renders = { count: 0 };
  const view = renderHook(
    () => {
      renders.count += 1;
      return useEnvironment({ measure, lookup });
    },
    { wrapper },
  );
  return { ...view, measure, lookup, renders };
}

describe('when there is no figure', () => {
  it.each([[null], [undefined], [NaN]])(
    'does nothing and sets no state for %s',
    async (latency) => {
      const { result, lookup, renders } = mount({ latency });
      await flush();
      expect(renders.count).toBe(1);
      expect(lookup).not.toHaveBeenCalled();
      expect(result.current).toMatchObject({
        environment: 'unknown',
        operator: null,
        latencyMs: null,
        question: null,
      });
    },
  );

  it('measures once, with a signal', () => {
    const { measure } = mount({ latency: null });
    expect(measure).toHaveBeenCalledTimes(1);
    expect(measure).toHaveBeenCalledWith({ signal: expect.any(AbortSignal) });
  });
});

describe('at home', () => {
  it('under 20 ms is home, with the latency, no lookup and no question', async () => {
    const { result, lookup } = mount({ latency: 19 });
    await flush();
    expect(result.current).toMatchObject({
      environment: 'home',
      operator: null,
      latencyMs: 19,
      question: null,
    });
    expect(lookup).not.toHaveBeenCalled();
  });

  it('20 ms is not home: the lookup runs', async () => {
    const { result, lookup } = mount({ latency: 20, operator: 'AS1 Net' });
    await flush();
    expect(lookup).toHaveBeenCalledWith({ signal: expect.any(AbortSignal) });
    expect(result.current.environment).toBe('unknown');
  });
});

describe('away from home', () => {
  it('takes a remembered answer silently', async () => {
    storage.setItem(
      NETWORKS_KEY,
      JSON.stringify([{ operator: 'AS64500 Example Rail', environment: 'train' }]),
    );
    const { result } = mount({ latency: 48, operator: 'AS64500 Example Rail' });
    await flush();
    expect(result.current).toMatchObject({
      environment: 'train',
      operator: 'AS64500 Example Rail',
      latencyMs: 48,
      question: null,
    });
  });

  it('takes a remembered away answer silently', async () => {
    storage.setItem(NETWORKS_KEY, JSON.stringify([{ operator: 'AS1 Hotel', environment: 'away' }]));
    const { result } = mount({ latency: 48, operator: 'AS1 Hotel' });
    await flush();
    expect(result.current).toMatchObject({ environment: 'away', question: null });
  });

  it('asks about a new operator, and an answer is stored, returned as stored and closes the question', async () => {
    const { result } = mount({ latency: 48, operator: 'AS64500 Example Rail' });
    await flush();
    expect(result.current).toMatchObject({
      environment: 'unknown',
      operator: 'AS64500 Example Rail',
      latencyMs: 48,
      question: { operator: 'AS64500 Example Rail' },
    });
    let stored$;
    act(() => {
      stored$ = result.current.answer('train');
    });
    expect(stored$).toBe(true);
    expect(result.current).toMatchObject({ environment: 'train', question: null });
    expect(stored()).toEqual([{ operator: 'AS64500 Example Rail', environment: 'train' }]);
    expect(recallAnswer('AS64500 Example Rail')).toBe('train');
  });

  it('records No as away', async () => {
    const { result } = mount({ latency: 48, operator: 'AS1 Net' });
    await flush();
    act(() => {
      result.current.answer('away');
    });
    expect(result.current.environment).toBe('away');
    expect(stored()).toEqual([{ operator: 'AS1 Net', environment: 'away' }]);
  });

  it('with no operator asks with a null operator, stores nothing, and still sets the environment', async () => {
    const { result } = mount({ latency: 48, operator: null });
    await flush();
    expect(result.current).toMatchObject({
      environment: 'unknown',
      operator: null,
      latencyMs: 48,
      question: { operator: null },
    });
    let stored$;
    act(() => {
      stored$ = result.current.answer('away');
    });
    expect(stored$).toBe(false);
    expect(storage.getItem(NETWORKS_KEY)).toBeNull();
    expect(result.current).toMatchObject({ environment: 'away', question: null });
  });

  it('treats a lookup that answers nothing as no operator', async () => {
    const { result } = mount({ latency: 48, operator: undefined });
    await flush();
    expect(result.current).toMatchObject({ operator: null, question: { operator: null } });
  });

  it('records a relay operator but never stores under it, even if it was stored', async () => {
    storage.setItem(
      NETWORKS_KEY,
      JSON.stringify([{ operator: 'AS13335 Cloudflare, Inc.', environment: 'train' }]),
    );
    const before = storage.getItem(NETWORKS_KEY);
    const { result } = mount({ latency: 48, operator: 'AS13335 Cloudflare, Inc.' });
    await flush();
    expect(result.current).toMatchObject({
      environment: 'unknown',
      operator: 'AS13335 Cloudflare, Inc.',
      question: { operator: null },
    });
    let stored$;
    act(() => {
      stored$ = result.current.answer('away');
    });
    expect(stored$).toBe(false);
    expect(storage.getItem(NETWORKS_KEY)).toBe(before);
    expect(result.current).toMatchObject({
      environment: 'away',
      operator: 'AS13335 Cloudflare, Inc.',
    });
  });

  it('has the latency before the lookup answers, and the rest when it does', async () => {
    const lookup = deferred();
    const measure = vi.fn(() => Promise.resolve(48));
    const lookupFn = vi.fn(() => lookup.promise);
    const { result } = renderHook(() => useEnvironment({ measure, lookup: lookupFn }));
    await flush();
    expect(result.current).toMatchObject({
      environment: 'unknown',
      operator: null,
      latencyMs: 48,
      question: null,
    });
    lookup.resolve('AS1 Net');
    await flush();
    expect(result.current).toMatchObject({
      operator: 'AS1 Net',
      question: { operator: 'AS1 Net' },
    });
  });
});

describe('answer and dismiss', () => {
  it('answer with no question open does nothing and returns false', async () => {
    const { result } = mount({ latency: 5 });
    await flush();
    let stored$;
    act(() => {
      stored$ = result.current.answer('train');
    });
    expect(stored$).toBe(false);
    expect(result.current.environment).toBe('home');
    expect(storage.getItem(NETWORKS_KEY)).toBeNull();
  });

  it('a second answer finds the question already closed', async () => {
    const { result } = mount({ latency: 48, operator: 'AS1 Net' });
    await flush();
    const results = [];
    act(() => {
      results.push(result.current.answer('train'));
      results.push(result.current.answer('away'));
    });
    expect(results).toEqual([true, false]);
    expect(result.current.environment).toBe('train');
    expect(stored()).toEqual([{ operator: 'AS1 Net', environment: 'train' }]);
  });

  it('dismiss closes the question and remembers nothing', async () => {
    const { result } = mount({ latency: 48, operator: 'AS1 Net' });
    await flush();
    act(() => result.current.dismiss());
    expect(result.current).toMatchObject({
      environment: 'unknown',
      operator: 'AS1 Net',
      question: null,
    });
    expect(storage.getItem(NETWORKS_KEY)).toBeNull();
    let stored$;
    act(() => {
      stored$ = result.current.answer('train');
    });
    expect(stored$).toBe(false);
  });

  it('dismiss with no question open changes nothing', async () => {
    const { result } = mount({ latency: 5 });
    await flush();
    act(() => result.current.dismiss());
    expect(result.current).toMatchObject({ environment: 'home', latencyMs: 5, question: null });
  });

  it('answer and dismiss keep their identity across renders', async () => {
    const { result, rerender } = mount({ latency: 48, operator: 'AS1 Net' });
    const { answer, dismiss } = result.current;
    await flush();
    rerender();
    expect(result.current.answer).toBe(answer);
    expect(result.current.dismiss).toBe(dismiss);
  });

  it('still reports whether it stored when the write is refused', async () => {
    const { result } = mount({ latency: 48, operator: 'AS1 Net' });
    await flush();
    vi.stubGlobal('localStorage', {
      getItem: () => null,
      setItem: () => {
        throw new Error('full');
      },
    });
    let stored$;
    act(() => {
      stored$ = result.current.answer('train');
    });
    expect(stored$).toBe(false);
    expect(result.current.environment).toBe('train');
  });
});

describe('leaving', () => {
  it('aborts the signal on unmount and sets nothing afterwards', async () => {
    const latency = deferred();
    const measure = vi.fn(() => latency.promise);
    const lookup = vi.fn(() => Promise.resolve('AS1 Net'));
    const renders = { count: 0 };
    const { unmount } = renderHook(() => {
      renders.count += 1;
      return useEnvironment({ measure, lookup });
    });
    const { signal } = measure.mock.calls[0][0];
    expect(signal.aborted).toBe(false);
    unmount();
    expect(signal.aborted).toBe(true);
    const before = renders.count;
    latency.resolve(48);
    await flush();
    expect(lookup).not.toHaveBeenCalled();
    expect(renders.count).toBe(before);
  });

  it('aborts a lookup in flight and ignores its answer', async () => {
    const lookup = deferred();
    const measure = vi.fn(() => Promise.resolve(48));
    const lookupFn = vi.fn(() => lookup.promise);
    const renders = { count: 0 };
    const { unmount } = renderHook(() => {
      renders.count += 1;
      return useEnvironment({ measure, lookup: lookupFn });
    });
    await flush();
    const { signal } = lookupFn.mock.calls[0][0];
    const before = renders.count;
    unmount();
    expect(signal.aborted).toBe(true);
    lookup.resolve('AS1 Net');
    await flush();
    expect(renders.count).toBe(before);
    expect(storage.getItem(NETWORKS_KEY)).toBeNull();
  });

  it('restarts correctly under StrictMode: the first run is aborted and the second decides', async () => {
    const { result, measure, lookup } = mount({
      latency: 48,
      operator: 'AS1 Net',
      wrapper: StrictMode,
    });
    await flush();
    expect(measure).toHaveBeenCalledTimes(2);
    expect(measure.mock.calls[0][0].signal.aborted).toBe(true);
    expect(measure.mock.calls[1][0].signal.aborted).toBe(false);
    // Only the second run reaches the lookup: the first was closed before its measurement answered.
    expect(lookup).toHaveBeenCalledTimes(1);
    expect(result.current).toMatchObject({
      environment: 'unknown',
      operator: 'AS1 Net',
      latencyMs: 48,
      question: { operator: 'AS1 Net' },
    });
    let stored$;
    act(() => {
      stored$ = result.current.answer('train');
    });
    expect(stored$).toBe(true);
    expect(result.current.environment).toBe('train');
  });
});

describe('when detection fails', () => {
  it('leaves the state as it is when the measurement rejects', async () => {
    const measure = vi.fn(() => Promise.reject(new Error('boom')));
    const lookup = vi.fn();
    const { result } = renderHook(() => useEnvironment({ measure, lookup }));
    await flush();
    expect(result.current).toMatchObject({
      environment: 'unknown',
      latencyMs: null,
      question: null,
    });
    expect(lookup).not.toHaveBeenCalled();
    expect(measure).toHaveBeenCalledTimes(1);
  });

  it('leaves the state as it is when the measurement throws', async () => {
    const measure = vi.fn(() => {
      throw new Error('boom');
    });
    const lookup = vi.fn();
    const { result } = renderHook(() => useEnvironment({ measure, lookup }));
    await flush();
    expect(measure).toHaveBeenCalledTimes(1);
    expect(lookup).not.toHaveBeenCalled();
    expect(result.current).toMatchObject({ environment: 'unknown', latencyMs: null });
  });

  it('keeps the latency and asks nothing when the lookup rejects', async () => {
    const measure = vi.fn(() => Promise.resolve(48));
    const lookup = vi.fn(() => Promise.reject(new Error('boom')));
    const { result } = renderHook(() => useEnvironment({ measure, lookup }));
    await flush();
    expect(lookup).toHaveBeenCalledTimes(1);
    expect(result.current).toMatchObject({
      environment: 'unknown',
      operator: null,
      latencyMs: 48,
      question: null,
    });
  });

  it('keeps the latency and asks nothing when the lookup throws', async () => {
    const measure = vi.fn(() => Promise.resolve(48));
    const lookup = vi.fn(() => {
      throw new Error('boom');
    });
    const { result } = renderHook(() => useEnvironment({ measure, lookup }));
    await flush();
    expect(lookup).toHaveBeenCalledTimes(1);
    expect(result.current).toMatchObject({ latencyMs: 48, question: null });
  });
});

describe('runs that are no longer current', () => {
  it('ignores the late answer of a run that was closed, whichever answers last', async () => {
    const lookups = [deferred(), deferred()];
    const measure = vi.fn(() => Promise.resolve(48));
    const lookup = vi.fn(() => lookups[lookup.mock.calls.length - 1].promise);
    const { result, rerender } = renderHook(({ m }) => useEnvironment({ measure: m, lookup }), {
      initialProps: { m: measure },
    });
    await flush();
    expect(lookup).toHaveBeenCalledTimes(1);
    // A new measure function starts a new run and closes the first.
    const measure2 = vi.fn(() => Promise.resolve(48));
    rerender({ m: measure2 });
    await flush();
    expect(measure.mock.calls[0][0].signal.aborted).toBe(true);
    expect(measure2).toHaveBeenCalledTimes(1);
    expect(lookup).toHaveBeenCalledTimes(2);
    lookups[1].resolve('AS2 Second');
    await flush();
    lookups[0].resolve('AS1 First');
    await flush();
    expect(result.current).toMatchObject({
      operator: 'AS2 Second',
      question: { operator: 'AS2 Second' },
    });
  });
});
