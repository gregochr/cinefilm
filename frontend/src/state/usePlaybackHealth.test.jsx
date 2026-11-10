import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, respond } from '../test/mockApi.jsx';
import { usePlaybackHealth } from './usePlaybackHealth.js';

afterEach(() => vi.unstubAllGlobals());

describe('usePlaybackHealth', () => {
  it('is loading with no playbacks until the list arrives, then returns it', async () => {
    const fetchMock = installFetch();
    const { result } = renderHook(() => usePlaybackHealth());
    expect(result.current).toMatchObject({ playbacks: null, error: null, loading: true });
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.playbacks).toEqual(fixtures.adminPlaybackHealth.playbacks);
    expect(result.current.error).toBeNull();
    expect(fetchMock.mock.calls.map((c) => c[0])).toEqual(['/api/admin/playback-health']);
  });

  it('returns an empty list as an empty list', async () => {
    installFetch((m, url) =>
      url === '/api/admin/playback-health' ? respond({ playbacks: [] }) : null,
    );
    const { result } = renderHook(() => usePlaybackHealth());
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.playbacks).toEqual([]);
  });

  it('reports a failure and no playbacks', async () => {
    installFetch((m, url) => (url === '/api/admin/playback-health' ? respond(null, 500) : null));
    const { result } = renderHook(() => usePlaybackHealth());
    await waitFor(() => expect(result.current.error).not.toBeNull());
    expect(result.current.playbacks).toBeNull();
    expect(result.current.loading).toBe(false);
  });

  it('reads the list again on reload, and not on its own', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      const fetchMock = installFetch();
      const { result } = renderHook(() => usePlaybackHealth());
      await waitFor(() => expect(result.current.loading).toBe(false));
      await act(() => vi.advanceTimersByTimeAsync(60_000));
      expect(fetchMock).toHaveBeenCalledTimes(1);

      act(() => result.current.reload());
      await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
      await waitFor(() => expect(result.current.loading).toBe(false));
      expect(result.current.playbacks).toHaveLength(3);
    } finally {
      vi.useRealTimers();
    }
  });
});
