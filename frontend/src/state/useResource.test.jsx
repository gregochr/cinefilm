import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { SIGNED_IN_EVENT } from '../api/client.js';
import { useResource } from './useResource.js';

afterEach(() => vi.useRealTimers());

describe('useResource', () => {
  it('starts loading, then holds the data', async () => {
    const { result } = renderHook(() => useResource('k', async () => ({ ok: 1 })));
    expect(result.current).toMatchObject({ data: null, error: null, loading: true });
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current).toMatchObject({ data: { ok: 1 }, error: null });
  });

  it('passes an abort signal to the fetcher', async () => {
    const fetcher = vi.fn(async () => 1);
    renderHook(() => useResource('k', fetcher));
    await waitFor(() => expect(fetcher).toHaveBeenCalled());
    expect(fetcher.mock.calls[0][0].signal).toBeInstanceOf(AbortSignal);
  });

  it('exposes a failure and stops loading', async () => {
    const boom = new Error('boom');
    const { result } = renderHook(() =>
      useResource('k', async () => {
        throw boom;
      }),
    );
    await waitFor(() => expect(result.current.error).toBe(boom));
    expect(result.current.loading).toBe(false);
    expect(result.current.data).toBeNull();
  });

  it('keeps the previous data visible while a new key loads, and on a failed reload', async () => {
    let fail = false;
    const { result, rerender } = renderHook(
      ({ key }) =>
        useResource(key, async () => {
          if (fail) throw new Error('later');
          return key;
        }),
      { initialProps: { key: 'a' } },
    );
    await waitFor(() => expect(result.current.data).toBe('a'));
    fail = true;
    rerender({ key: 'b' });
    expect(result.current.data).toBe('a');
    expect(result.current.loading).toBe(true);
    await waitFor(() => expect(result.current.error?.message).toBe('later'));
    expect(result.current.data).toBe('a');
  });

  it('ignores a stale response that lands after the key changed', async () => {
    const resolvers = {};
    const { result, rerender } = renderHook(
      ({ key }) => useResource(key, () => new Promise((resolve) => (resolvers[key] = resolve))),
      { initialProps: { key: 'a' } },
    );
    await waitFor(() => expect(resolvers.a).toBeDefined());
    rerender({ key: 'b' });
    await waitFor(() => expect(resolvers.b).toBeDefined());
    await act(async () => {
      resolvers.a('stale');
      resolvers.b('fresh');
    });
    expect(result.current.data).toBe('fresh');
  });

  it('ignores an abort error, and a failure that lands after unmount', async () => {
    const aborted = Object.assign(new Error('aborted'), { name: 'AbortError' });
    const { result } = renderHook(() =>
      useResource('k', async () => {
        throw aborted;
      }),
    );
    await act(async () => {});
    expect(result.current.error).toBeNull();

    let reject;
    const late = renderHook(() => useResource('k2', () => new Promise((_, r) => (reject = r))));
    await waitFor(() => expect(reject).toBeDefined());
    late.unmount();
    await act(async () => reject(new Error('too late')));
    expect(late.result.current.error).toBeNull();
  });

  it('reload fetches again', async () => {
    const fetcher = vi.fn(async () => 'x');
    const { result } = renderHook(() => useResource('k', fetcher));
    await waitFor(() => expect(result.current.loading).toBe(false));
    act(() => result.current.reload());
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
  });

  it('reloads after a sign-in that follows a session expiry', async () => {
    const fetcher = vi.fn(async () => 'x');
    const { result } = renderHook(() => useResource('k', fetcher));
    await waitFor(() => expect(result.current.loading).toBe(false));
    act(() => {
      window.dispatchEvent(new CustomEvent(SIGNED_IN_EVENT));
    });
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
  });

  it('stops listening for sign-ins once unmounted', async () => {
    const fetcher = vi.fn(async () => 'x');
    const { result, unmount } = renderHook(() => useResource('k', fetcher));
    await waitFor(() => expect(result.current.loading).toBe(false));
    unmount();
    window.dispatchEvent(new CustomEvent(SIGNED_IN_EVENT));
    await act(async () => {});
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it('uses the latest fetcher without refetching on every render', async () => {
    let value = 1;
    const { result, rerender } = renderHook(() => useResource('k', async () => value));
    await waitFor(() => expect(result.current.data).toBe(1));
    value = 2;
    rerender();
    await act(async () => {});
    expect(result.current.data).toBe(1);
    act(() => result.current.reload());
    await waitFor(() => expect(result.current.data).toBe(2));
  });
});
