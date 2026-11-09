import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, respond } from '../test/mockApi.jsx';
import { SCAN_POLL_MS, useScan } from './useAdmin.js';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

const REFRESH = '/api/admin/ratings/refresh';
const isRefresh = (method, url) => method === 'POST' && url === REFRESH;

describe('useScan: refreshRatings', () => {
  it('posts the refresh, reloads the status and shows it running', async () => {
    let started = false;
    const fetchMock = installFetch((method, url) => {
      if (isRefresh(method, url)) {
        started = true;
        return respond({ ...fixtures.adminScan, ratingsRunning: true }, 202);
      }
      if (url === '/api/admin/scan') {
        return respond(
          started ? { ...fixtures.adminScan, ratingsRunning: true } : fixtures.adminScan,
        );
      }
      return null;
    });
    const { result } = renderHook(() => useScan());
    await waitFor(() => expect(result.current.scan).not.toBeNull());

    await act(() => result.current.refreshRatings());

    expect(fetchMock.mock.calls.some((c) => c[0] === REFRESH && c[1]?.method === 'POST')).toBe(
      true,
    );
    await waitFor(() => expect(result.current.scan.ratingsRunning).toBe(true));
    expect(result.current.ratingsStarting).toBe(false);
    expect(result.current.ratingsNotice).toBeNull();
  });

  it('treats a 409 as already running, not a failure', async () => {
    installFetch((method, url) =>
      isRefresh(method, url)
        ? respond({ error: 'A ratings refresh is already running' }, 409)
        : null,
    );
    const { result } = renderHook(() => useScan());
    await waitFor(() => expect(result.current.scan).not.toBeNull());

    await act(() => result.current.refreshRatings());

    expect(result.current.ratingsNotice).toBe('A ratings refresh is already running.');
  });

  it('reports any other failure and clears the notice on the next try', async () => {
    let fail = true;
    installFetch((method, url) =>
      isRefresh(method, url)
        ? respond(
            fail ? { error: 'boom' } : { ...fixtures.adminScan, ratingsRunning: true },
            fail ? 500 : 202,
          )
        : null,
    );
    const { result } = renderHook(() => useScan());
    await waitFor(() => expect(result.current.scan).not.toBeNull());

    await act(() => result.current.refreshRatings());
    expect(result.current.ratingsNotice).toBe('Could not start the ratings refresh.');

    fail = false;
    await act(() => result.current.refreshRatings());
    expect(result.current.ratingsNotice).toBeNull();
  });

  it('polls while only the ratings refresh is running', async () => {
    const fetchMock = installFetch((m, url) =>
      url === '/api/admin/scan' ? respond({ ...fixtures.adminScan, ratingsRunning: true }) : null,
    );
    vi.useFakeTimers();
    renderHook(() => useScan());
    await act(() => vi.advanceTimersByTimeAsync(0));
    const reads = () => fetchMock.mock.calls.filter((c) => c[0] === '/api/admin/scan').length;
    const before = reads();
    await act(() => vi.advanceTimersByTimeAsync(SCAN_POLL_MS + 10));
    expect(reads()).toBeGreaterThan(before);
  });
});
