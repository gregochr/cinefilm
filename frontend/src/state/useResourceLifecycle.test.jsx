import { act, renderHook, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { SIGNED_IN_EVENT } from '../api/client.js';
import { useResource } from './useResource.js';

describe('useResource lifecycle', () => {
  it('reloads on every reload() call, not just the first', async () => {
    const fetcher = vi.fn(async () => 1);
    const { result } = renderHook(() => useResource('k', fetcher));
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1));
    act(() => result.current.reload());
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
    act(() => result.current.reload());
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(3));
  });

  it('refetches after every sign-in, not just the first', async () => {
    const fetcher = vi.fn(async () => 1);
    renderHook(() => useResource('k', fetcher));
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1));
    act(() => window.dispatchEvent(new CustomEvent(SIGNED_IN_EVENT)));
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
    act(() => window.dispatchEvent(new CustomEvent(SIGNED_IN_EVENT)));
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(3));
  });

  it('aborts the request and stops listening on unmount', async () => {
    const remove = vi.spyOn(window, 'removeEventListener');
    let signal;
    const { unmount } = renderHook(() =>
      useResource('k', ({ signal: s }) => {
        signal = s;
        return new Promise(() => {});
      }),
    );
    await waitFor(() => expect(signal).toBeDefined());
    unmount();
    expect(signal.aborted).toBe(true);
    expect(remove).toHaveBeenCalledWith(SIGNED_IN_EVENT, expect.any(Function));
    remove.mockRestore();
  });

  it('ignores a slow response for a key that has been replaced', async () => {
    const pending = {};
    const { result, rerender } = renderHook(
      ({ key }) => useResource(key, () => new Promise((resolve) => (pending[key] = resolve))),
      { initialProps: { key: 'a' } },
    );
    await waitFor(() => expect(pending.a).toBeDefined());
    rerender({ key: 'b' });
    await waitFor(() => expect(pending.b).toBeDefined());
    await act(async () => pending.b('B'));
    await act(async () => pending.a('A'));
    expect(result.current.data).toBe('B');
  });

  it('survives a rejection with no error object', async () => {
    const { result } = renderHook(() => useResource('k', () => Promise.reject(undefined)));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.data).toBeNull();
  });
});
