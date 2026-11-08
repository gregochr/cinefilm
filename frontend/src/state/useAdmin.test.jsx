import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, respond } from '../test/mockApi.jsx';
import { SCAN_POLL_MS, useAttention, useScan } from './useAdmin.js';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

const running = (processed) => ({
  ...fixtures.adminScan,
  running: true,
  progress: { processed, total: 40, current: `Film ${processed}.m4v` },
});

describe('useScan', () => {
  it('loads the scan status', async () => {
    installFetch();
    const { result } = renderHook(() => useScan());
    expect(result.current.loading).toBe(true);
    await waitFor(() => expect(result.current.scan).toEqual(fixtures.adminScan));
  });

  // Fake timers throughout: each step lets the pending fetches settle, then moves the clock.
  const settle = (ms = 0) => act(() => vi.advanceTimersByTimeAsync(ms));

  it('does not poll while idle', async () => {
    const fetchMock = installFetch();
    vi.useFakeTimers();
    const { result } = renderHook(() => useScan());
    await settle();
    expect(result.current.scan).not.toBeNull();

    await settle(SCAN_POLL_MS * 4);

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('polls every 1.5 s while a scan runs and stops when it finishes', async () => {
    let calls = 0;
    const fetchMock = installFetch((method, url) => {
      if (url !== '/api/admin/scan') return null;
      calls += 1;
      return respond(calls < 3 ? running(calls * 10) : fixtures.adminScan);
    });
    vi.useFakeTimers();
    const { result } = renderHook(() => useScan());
    await settle();
    expect(result.current.scan.progress.processed).toBe(10);
    expect(SCAN_POLL_MS).toBe(1500);

    await settle(SCAN_POLL_MS - 1);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    await settle(1);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(result.current.scan.progress.processed).toBe(20);
    await settle(SCAN_POLL_MS);
    expect(result.current.scan.running).toBe(false);

    await settle(SCAN_POLL_MS * 4);
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it('keeps polling after one poll fails', async () => {
    let calls = 0;
    installFetch((method, url) => {
      if (url !== '/api/admin/scan') return null;
      calls += 1;
      if (calls === 2) return respond(null, 500);
      return respond(calls === 1 ? running(5) : running(15));
    });
    vi.useFakeTimers();
    const { result } = renderHook(() => useScan());
    await settle();
    expect(result.current.scan.progress.processed).toBe(5);
    await settle(SCAN_POLL_MS);
    expect(result.current.error).not.toBeNull();
    await settle(SCAN_POLL_MS);
    expect(result.current.scan.progress.processed).toBe(15);
  });

  it('starts a scan with POST, then refetches the status', async () => {
    let started = false;
    const fetchMock = installFetch((method, url) => {
      if (url === '/api/admin/scan' && method === 'POST') {
        started = true;
        return respond(running(0), 202);
      }
      if (url === '/api/admin/scan') return respond(started ? running(0) : fixtures.adminScan);
      return null;
    });
    const { result } = renderHook(() => useScan());
    await waitFor(() => expect(result.current.scan).not.toBeNull());

    await act(() => result.current.startScan());

    expect(fetchMock.mock.calls.some((c) => c[1]?.method === 'POST')).toBe(true);
    await waitFor(() => expect(result.current.scan.running).toBe(true));
    expect(result.current.starting).toBe(false);
    expect(result.current.notice).toBeNull();
  });

  it('is starting while the request is in flight', async () => {
    let release;
    installFetch((method, url) =>
      method === 'POST' && url === '/api/admin/scan'
        ? new Promise((resolve) => {
            release = () => resolve(respond(running(0), 202));
          })
        : null,
    );
    const { result } = renderHook(() => useScan());
    await waitFor(() => expect(result.current.scan).not.toBeNull());

    let pending;
    act(() => {
      pending = result.current.startScan();
    });
    await waitFor(() => expect(result.current.starting).toBe(true));
    await act(async () => {
      release();
      await pending;
    });
    expect(result.current.starting).toBe(false);
  });

  it('treats a 409 as "already running", not a failure, and shows the running scan', async () => {
    installFetch((method, url) => {
      if (url === '/api/admin/scan' && method === 'POST') {
        return respond({ error: 'A scan is already running' }, 409);
      }
      if (url === '/api/admin/scan') return respond(running(7));
      return null;
    });
    const { result } = renderHook(() => useScan());
    await waitFor(() => expect(result.current.scan).not.toBeNull());

    await act(() => result.current.startScan());

    expect(result.current.notice).toBe('A scan is already running.');
    await waitFor(() => expect(result.current.scan.running).toBe(true));
  });

  it('reports any other failure to start', async () => {
    installFetch((method) => (method === 'POST' ? respond({ error: 'boom' }, 500) : null));
    const { result } = renderHook(() => useScan());
    await waitFor(() => expect(result.current.scan).not.toBeNull());

    await act(() => result.current.startScan());

    expect(result.current.notice).toBe('Could not start the scan.');
  });

  it('clears an old notice when the next scan starts', async () => {
    let fail = true;
    installFetch((method, url) =>
      method === 'POST' && url === '/api/admin/scan'
        ? respond(fail ? { error: 'boom' } : running(0), fail ? 500 : 202)
        : null,
    );
    const { result } = renderHook(() => useScan());
    await waitFor(() => expect(result.current.scan).not.toBeNull());
    await act(() => result.current.startScan());
    expect(result.current.notice).toBe('Could not start the scan.');

    fail = false;
    await act(() => result.current.startScan());
    expect(result.current.notice).toBeNull();
  });
});

describe('useAttention', () => {
  it('loads the attention list and reloads when the version changes', async () => {
    const fetchMock = installFetch();
    const { result, rerender } = renderHook(({ version }) => useAttention(version), {
      initialProps: { version: 'never' },
    });
    await waitFor(() => expect(result.current.data).toEqual(fixtures.adminAttention));
    expect(fetchMock.mock.calls.filter((c) => c[0] === '/api/admin/attention')).toHaveLength(1);

    rerender({ version: '2026-11-08T12:41:10Z' });
    await waitFor(() =>
      expect(fetchMock.mock.calls.filter((c) => c[0] === '/api/admin/attention')).toHaveLength(2),
    );
  });
});
