import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, respond } from '../test/mockApi.jsx';
import { TRANSCODING_POLL_MS, useTranscoding } from './useTranscoding.js';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe('useTranscoding', () => {
  it('is loading until the state arrives, then returns it', async () => {
    installFetch();
    const { result } = renderHook(() => useTranscoding());
    expect(result.current).toMatchObject({ transcoding: null, loading: true, error: null });
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.transcoding).toEqual(fixtures.adminTranscoding);
  });

  it('reports a failure and no state', async () => {
    installFetch((m, url) => (url === '/api/admin/transcoding' ? respond(null, 500) : null));
    const { result } = renderHook(() => useTranscoding());
    await waitFor(() => expect(result.current.error).not.toBeNull());
    expect(result.current.transcoding).toBeNull();
  });

  it('reads again every 5 s and not before', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const fetchMock = installFetch();
    const { result } = renderHook(() => useTranscoding());
    await waitFor(() => expect(result.current.loading).toBe(false));
    const reads = () =>
      fetchMock.mock.calls.filter((c) => c[0] === '/api/admin/transcoding').length;
    expect(reads()).toBe(1);
    await act(() => vi.advanceTimersByTimeAsync(TRANSCODING_POLL_MS - 100));
    expect(reads()).toBe(1);
    await act(() => vi.advanceTimersByTimeAsync(200));
    expect(reads()).toBe(2);
  });
});
