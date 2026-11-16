import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, respond } from '../test/mockApi.jsx';
import { TRANSCODING_POLL_MS, useTranscoding } from './useTranscoding.js';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

// Fake timers: each step lets the pending fetches settle, then moves the clock.
const settle = (ms = 0) => act(() => vi.advanceTimersByTimeAsync(ms));
const reads = (fetchMock) =>
  fetchMock.mock.calls.filter((c) => c[0] === '/api/admin/transcoding').length;

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
    expect(reads(fetchMock)).toBe(1);
    await act(() => vi.advanceTimersByTimeAsync(TRANSCODING_POLL_MS - 100));
    expect(reads(fetchMock)).toBe(1);
    await act(() => vi.advanceTimersByTimeAsync(200));
    expect(reads(fetchMock)).toBe(2);
  });

  it('keeps the loading state off while it polls, so the figures never flip back to a skeleton', async () => {
    const fetchMock = installFetch();
    vi.useFakeTimers();
    const { result } = renderHook(() => useTranscoding());
    await settle();
    await settle(TRANSCODING_POLL_MS);

    expect(reads(fetchMock)).toBe(2);
    expect(result.current.loading).toBe(false);
    expect(result.current.transcoding).toEqual(fixtures.adminTranscoding);
  });

  it('keeps the same error on a failure before any data, with no loading flip on each tick', async () => {
    const fetchMock = installFetch((m, url) =>
      url === '/api/admin/transcoding' ? respond(null, 500) : null,
    );
    vi.useFakeTimers();
    const seen = [];
    const { result } = renderHook(() => {
      const state = useTranscoding();
      seen.push(state.loading);
      return state;
    });
    await settle();
    expect(result.current.error).not.toBeNull();
    const rendersAfterFirstFailure = seen.length;

    for (let tick = 0; tick < 3; tick += 1) await settle(TRANSCODING_POLL_MS);

    expect(reads(fetchMock)).toBe(4);
    expect(result.current.error).not.toBeNull();
    // Nothing after the first failure turned loading back on.
    expect(seen.slice(rendersAfterFirstFailure)).not.toContain(true);
  });

  it('keeps showing the last data when a later read fails, and recovers on the next answer', async () => {
    let calls = 0;
    installFetch((m, url) => {
      if (url !== '/api/admin/transcoding') return null;
      calls += 1;
      return calls === 2 ? respond(null, 500) : respond(fixtures.adminTranscoding);
    });
    vi.useFakeTimers();
    const { result } = renderHook(() => useTranscoding());
    await settle();

    await settle(TRANSCODING_POLL_MS);
    expect(result.current.error).not.toBeNull();
    expect(result.current.transcoding).toEqual(fixtures.adminTranscoding);

    await settle(TRANSCODING_POLL_MS);
    expect(result.current.error).toBeNull();
  });

  it('keeps polling when a read never answers: one more read at 5 s, not at 4,999 ms', async () => {
    let calls = 0;
    const fetchMock = installFetch((m, url) => {
      if (url !== '/api/admin/transcoding') return null;
      calls += 1;
      return calls === 1 ? respond(fixtures.adminTranscoding) : new Promise(() => {});
    });
    vi.useFakeTimers();
    renderHook(() => useTranscoding());
    await settle();
    await settle(TRANSCODING_POLL_MS); // the second read is out and never answers
    expect(reads(fetchMock)).toBe(2);

    await settle(TRANSCODING_POLL_MS - 1);
    expect(reads(fetchMock)).toBe(2);
    await settle(1);
    expect(reads(fetchMock)).toBe(3);
  });

  it('does not poll while the tab is hidden, and reads once when it is shown again', async () => {
    const fetchMock = installFetch();
    vi.useFakeTimers();
    const { rerender } = renderHook(({ visible }) => useTranscoding(visible), {
      initialProps: { visible: true },
    });
    await settle();

    rerender({ visible: false });
    await settle(TRANSCODING_POLL_MS * 3);
    expect(reads(fetchMock)).toBe(1);

    rerender({ visible: true });
    await settle();
    expect(reads(fetchMock)).toBe(2);
    await settle(TRANSCODING_POLL_MS);
    expect(reads(fetchMock)).toBe(3);
  });

  it('does not read again when the tab was never hidden', async () => {
    const fetchMock = installFetch();
    vi.useFakeTimers();
    const { rerender } = renderHook(({ visible }) => useTranscoding(visible), {
      initialProps: { visible: true },
    });
    await settle();

    rerender({ visible: true });
    await settle();

    expect(reads(fetchMock)).toBe(1);
  });

  it('stops polling when it unmounts', async () => {
    const fetchMock = installFetch();
    vi.useFakeTimers();
    const { unmount } = renderHook(() => useTranscoding());
    await settle();

    unmount();
    await settle(TRANSCODING_POLL_MS * 3);

    expect(reads(fetchMock)).toBe(1);
  });
});
