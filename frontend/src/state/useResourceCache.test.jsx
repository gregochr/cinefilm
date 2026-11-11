import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SIGNED_IN_EVENT, put } from '../api/client.js';
import {
  MAX_AGE_MS,
  SETTLE_WAIT_MS,
  beginRequest,
  clearResourceCache,
  commit,
  lookup,
  setResourceCacheClock,
} from './resourceCache.js';
import { useResource } from './useResource.js';

const REVALIDATE = { cache: 'revalidate' };
const FRESH = { cache: 'fresh', maxAgeMs: 60_000 };

let time;
beforeEach(() => {
  clearResourceCache();
  time = 5_000_000;
  setResourceCacheClock(() => time);
});
afterEach(() => vi.unstubAllGlobals());

const seed = (key, data) => commit(key, beginRequest(), data);

// A fetcher the test settles by hand: `calls` counts requests, `resolve(i, value)` / `reject(i, error)` answer one.
function held() {
  const settlers = [];
  const fetcher = vi.fn(() => new Promise((resolve, reject) => settlers.push({ resolve, reject })));
  return {
    fetcher,
    resolve: (i, value) => act(async () => settlers[i].resolve(value)),
    reject: (i, error) => act(async () => settlers[i].reject(error)),
  };
}

describe('useResource without a cache option', () => {
  it('neither reads nor stores', async () => {
    seed('k', { old: true });
    const fetcher = vi.fn(async () => ({ fresh: true }));
    const { result } = renderHook(() => useResource('k', fetcher));
    expect(result.current).toMatchObject({ data: null, loading: true });
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.data).toEqual({ fresh: true });
    expect(lookup('k').data).toEqual({ old: true });
  });
});

describe("useResource cache: 'revalidate'", () => {
  it('shows the cached answer in the first render, keeps loading false, and fetches once', async () => {
    seed('k', { n: 1 });
    const { fetcher, resolve } = held();
    const renders = [];
    const { result } = renderHook(() => {
      const value = useResource('k', fetcher, REVALIDATE);
      renders.push({ data: value.data, loading: value.loading });
      return value;
    });
    expect(renders[0]).toEqual({ data: { n: 1 }, loading: false });
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1));
    expect(renders.every((r) => r.loading === false && r.data !== null)).toBe(true);
    await resolve(0, { n: 1 });
    expect(result.current.loading).toBe(false);
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it('a re-render does not refetch', async () => {
    seed('k', { n: 1 });
    const fetcher = vi.fn(async () => ({ n: 1 }));
    const { rerender } = renderHook(() => useResource('k', fetcher, { ...REVALIDATE }));
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1));
    rerender();
    rerender();
    await act(async () => {});
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it('keeps the same object for a JSON-equal answer, and refreshes the entry time', async () => {
    const first = { n: 1, list: [1, 2] };
    seed('k', first);
    time += MAX_AGE_MS - 1000;
    const { fetcher, resolve } = held();
    const { result } = renderHook(() => useResource('k', fetcher, REVALIDATE));
    expect(result.current.data).toBe(first);
    await waitFor(() => expect(fetcher).toHaveBeenCalled());
    await resolve(0, { n: 1, list: [1, 2] });
    expect(result.current.data).toBe(first);
    time += MAX_AGE_MS - 1000; // the confirmation moved the entry's time
    expect(lookup('k').data).toBe(first);
  });

  it('a background check keeps the shown object for an equal answer, a reload() replaces it', async () => {
    const first = { n: 1 };
    seed('k', first);
    const { fetcher, resolve } = held();
    const { result } = renderHook(() => useResource('k', fetcher, REVALIDATE));
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1));
    await resolve(0, { n: 1 });
    expect(result.current.data).toBe(first); // background revalidation: same object

    act(() => result.current.reload());
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
    await resolve(1, { n: 1 });
    // A reload() always shows what the server answered (callers drop their local changes when the data is replaced).
    expect(result.current.data).toEqual({ n: 1 });
    expect(result.current.data).not.toBe(first);
    expect(result.current.loading).toBe(false);
  });

  it('replaces the shown answer with a different one and stores it', async () => {
    seed('k', { n: 1 });
    const { fetcher, resolve } = held();
    const { result } = renderHook(() => useResource('k', fetcher, REVALIDATE));
    await waitFor(() => expect(fetcher).toHaveBeenCalled());
    await resolve(0, { n: 2 });
    expect(result.current).toMatchObject({ data: { n: 2 }, loading: false, error: null });
    expect(lookup('k').data).toEqual({ n: 2 });
  });

  it('keeps the shown answer and sets the error when the background fetch fails', async () => {
    seed('k', { n: 1 });
    const { fetcher, reject } = held();
    const { result } = renderHook(() => useResource('k', fetcher, REVALIDATE));
    await waitFor(() => expect(fetcher).toHaveBeenCalled());
    const boom = new Error('offline');
    await reject(0, boom);
    expect(result.current).toMatchObject({ data: { n: 1 }, error: boom, loading: false });
  });

  it('neither shows nor stores a background answer that crossed an invalidation', async () => {
    seed('k', { n: 1 });
    vi.stubGlobal('fetch', async () => new Response(null, { status: 204 }));
    const { fetcher, resolve } = held();
    const { result } = renderHook(() => useResource('k', fetcher, REVALIDATE));
    await waitFor(() => expect(fetcher).toHaveBeenCalled());
    await act(async () => {
      await put('/api/editions/1/continue-watching', {});
    });
    await resolve(0, { n: 'stale' });
    expect(result.current.data).toEqual({ n: 1 });
    expect(lookup('k')).toBeNull();
  });

  const mutate = () =>
    act(async () => {
      await put('/api/editions/1/continue-watching', {});
    });

  it('a miss whose answer crosses a mutation is not shown: it fetches again and shows the second answer', async () => {
    vi.stubGlobal('fetch', async () => new Response(null, { status: 204 }));
    const { fetcher, resolve } = held();
    const { result } = renderHook(() => useResource('k', fetcher, REVALIDATE));
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1));
    await mutate();
    await resolve(0, { n: 'before' });
    expect(result.current).toMatchObject({ data: null, loading: true });
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
    await resolve(1, { n: 'after' });
    expect(result.current).toMatchObject({ data: { n: 'after' }, loading: false });
    expect(lookup('k').data).toEqual({ n: 'after' });
  });

  it('a reload() whose answer crosses a mutation fetches again and shows the new answer', async () => {
    seed('k', { n: 1 });
    vi.stubGlobal('fetch', async () => new Response(null, { status: 204 }));
    const { fetcher, resolve } = held();
    const { result } = renderHook(() => useResource('k', fetcher, REVALIDATE));
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1));
    await resolve(0, { n: 1 });
    act(() => result.current.reload());
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
    await mutate();
    await resolve(1, { n: 'old' });
    expect(result.current).toMatchObject({ data: { n: 1 }, loading: true });
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(3));
    await resolve(2, { n: 'new' });
    expect(result.current).toMatchObject({ data: { n: 'new' }, loading: false });
  });

  // A mutation the test ends by hand: `end()` answers its request.
  function slowMutation() {
    let end;
    vi.stubGlobal('fetch', () => new Promise((resolve) => (end = resolve)));
    const sent = put('/api/editions/1/continue-watching', {});
    return {
      end: () =>
        act(async () => {
          end(new Response(null, { status: 204 }));
          await sent;
        }),
    };
  }

  it('a miss that crosses a slow mutation retries only once the mutation has ended, and stores that answer', async () => {
    const { fetcher, resolve } = held();
    const { result } = renderHook(() => useResource('k', fetcher, REVALIDATE));
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1));
    const mutation = slowMutation();
    await resolve(0, { n: 'before' });
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(result.current).toMatchObject({ data: null, loading: true });
    await mutation.end();
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
    await resolve(1, { n: 'after' });
    expect(result.current).toMatchObject({ data: { n: 'after' }, loading: false });
    expect(lookup('k').data).toEqual({ n: 'after' });
  });

  it('does not wait for a hung mutation for ever: one more fetch, and its answer is shown unstored', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    try {
      const { fetcher, resolve } = held();
      const { result } = renderHook(() => useResource('k', fetcher, REVALIDATE));
      await act(async () => {});
      expect(fetcher).toHaveBeenCalledTimes(1);
      slowMutation(); // never ends
      await resolve(0, { n: 'before' });
      await act(async () => vi.advanceTimersByTimeAsync(SETTLE_WAIT_MS));
      expect(fetcher).toHaveBeenCalledTimes(2);
      await resolve(1, { n: 'later' });
      expect(result.current).toMatchObject({ data: { n: 'later' }, loading: false });
      expect(lookup('k')).toBeNull();
      expect(fetcher).toHaveBeenCalledTimes(2);
    } finally {
      vi.useRealTimers();
    }
  });

  it('makes no fetch when unmounted while waiting for a mutation to end', async () => {
    const { fetcher, resolve } = held();
    const { unmount } = renderHook(() => useResource('k', fetcher, REVALIDATE));
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1));
    const mutation = slowMutation();
    await resolve(0, { n: 'before' });
    unmount();
    await mutation.end();
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it('shows the third answer, unstored, when three in a row crossed a change', async () => {
    vi.stubGlobal('fetch', async () => new Response(null, { status: 204 }));
    const { fetcher, resolve } = held();
    const { result } = renderHook(() => useResource('k', fetcher, REVALIDATE));
    for (let i = 0; i < 3; i += 1) {
      await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(i + 1));
      await mutate();
      expect(result.current.data).toBeNull();
      await resolve(i, { n: i });
    }
    expect(result.current).toMatchObject({ data: { n: 2 }, loading: false });
    expect(lookup('k')).toBeNull();
    expect(fetcher).toHaveBeenCalledTimes(3);
  });

  it('makes no further fetch when unmounted during a retry', async () => {
    vi.stubGlobal('fetch', async () => new Response(null, { status: 204 }));
    const { fetcher, resolve } = held();
    const { unmount } = renderHook(() => useResource('k', fetcher, REVALIDATE));
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1));
    await mutate();
    await resolve(0, { n: 0 });
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
    unmount();
    await resolve(1, { n: 1 });
    expect(fetcher).toHaveBeenCalledTimes(2);
  });

  it('treats an entry of 30 minutes as a miss', async () => {
    seed('k', { n: 1 });
    time += MAX_AGE_MS;
    const { fetcher } = held();
    const { result } = renderHook(() => useResource('k', fetcher, REVALIDATE));
    expect(result.current).toMatchObject({ data: null, loading: true });
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1));
  });

  it('a miss loads, then stores for the next mount', async () => {
    const fetcher = vi.fn(async () => ({ n: 1 }));
    const { result } = renderHook(() => useResource('k', fetcher, REVALIDATE));
    expect(result.current.loading).toBe(true);
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(lookup('k').data).toBe(result.current.data);
    const next = renderHook(() => useResource('k', fetcher, REVALIDATE));
    expect(next.result.current).toMatchObject({ data: { n: 1 }, loading: false });
  });

  it('shows a cached answer for a new key in the same render the key changes', async () => {
    seed('b', { n: 'b' });
    const fetcher = vi.fn(async () => ({ n: 'a' }));
    const { result, rerender } = renderHook(({ key }) => useResource(key, fetcher, REVALIDATE), {
      initialProps: { key: 'a' },
    });
    await waitFor(() => expect(result.current.data).toEqual({ n: 'a' }));
    const seen = [];
    rerender({ key: 'b' });
    seen.push({ ...result.current });
    expect(seen[0]).toMatchObject({ data: { n: 'b' }, loading: false });
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
    expect(result.current.data).toEqual({ n: 'a' }); // the background answer for key b (the fetcher is shared)
  });

  it('reload() and the refetch after a sign-in set loading and fetch again', async () => {
    seed('k', { n: 1 });
    const fetcher = vi.fn(async () => ({ n: 1 }));
    const { result } = renderHook(() => useResource('k', fetcher, REVALIDATE));
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1));
    const { fetcher: slow, resolve } = held();
    const second = renderHook(() => useResource('k', slow, REVALIDATE));
    await waitFor(() => expect(slow).toHaveBeenCalledTimes(1));
    act(() => second.result.current.reload());
    expect(second.result.current.loading).toBe(true);
    expect(second.result.current.data).toEqual({ n: 1 });
    await waitFor(() => expect(slow).toHaveBeenCalledTimes(2));
    await resolve(1, { n: 1 });
    expect(second.result.current.loading).toBe(false);
    act(() => {
      window.dispatchEvent(new CustomEvent(SIGNED_IN_EVENT));
    });
    expect(second.result.current.loading).toBe(true);
    await waitFor(() => expect(slow).toHaveBeenCalledTimes(3));
    expect(result.current.data).toEqual({ n: 1 });
  });

  it('an answer for a replaced key is not stored under the new key', async () => {
    const { fetcher, resolve } = held();
    const { rerender } = renderHook(({ key }) => useResource(key, fetcher, REVALIDATE), {
      initialProps: { key: 'a' },
    });
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1));
    rerender({ key: 'b' });
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
    await resolve(0, { n: 'a' });
    expect(lookup('a')).toBeNull();
    expect(lookup('b')).toBeNull();
  });

  it('a failed fetch with nothing cached sets the error and stores nothing', async () => {
    const boom = new Error('boom');
    const { result } = renderHook(() => useResource('k', () => Promise.reject(boom), REVALIDATE));
    await waitFor(() => expect(result.current.error).toBe(boom));
    expect(lookup('k')).toBeNull();
  });
});

describe("useResource cache: 'fresh'", () => {
  it('uses an answer under maxAgeMs old with no fetch at all', async () => {
    seed('k', { n: 1 });
    time += 59_999;
    const fetcher = vi.fn(async () => ({ n: 2 }));
    const { result, rerender } = renderHook(() => useResource('k', fetcher, FRESH));
    expect(result.current).toMatchObject({ data: { n: 1 }, loading: false });
    await act(async () => {});
    rerender();
    expect(fetcher).not.toHaveBeenCalled();
    expect(result.current.data).toEqual({ n: 1 });
  });

  it('at maxAgeMs loads with nothing shown, then stores', async () => {
    seed('k', { n: 1 });
    time += 60_000;
    const { fetcher, resolve } = held();
    const { result } = renderHook(() => useResource('k', fetcher, FRESH));
    expect(result.current).toMatchObject({ data: null, loading: true });
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1));
    await resolve(0, { n: 2 });
    expect(result.current).toMatchObject({ data: { n: 2 }, loading: false });
    expect(lookup('k').data).toEqual({ n: 2 });
  });

  it('reload() fetches even when the answer is fresh', async () => {
    seed('k', { n: 1 });
    const fetcher = vi.fn(async () => ({ n: 2 }));
    const { result } = renderHook(() => useResource('k', fetcher, FRESH));
    expect(fetcher).not.toHaveBeenCalled();
    act(() => result.current.reload());
    await waitFor(() => expect(result.current.data).toEqual({ n: 2 }));
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it('shows an entry another hook stored between this render and its effect', async () => {
    const fetcher = vi.fn(async () => ({ n: 2 }));
    const { result } = renderHook(() => {
      const value = useResource('k', fetcher, FRESH);
      if (value.loading && lookup('k') === null) seed('k', { n: 3 }); // stored after the first render looked
      return value;
    });
    await waitFor(() => expect(result.current).toMatchObject({ data: { n: 3 }, loading: false }));
    expect(fetcher).not.toHaveBeenCalled();
  });
});
