import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, respond } from '../test/mockApi.jsx';
import { SCAN_IDLE_POLL_MS, SCAN_POLL_MS, useScan } from './useAdmin.js';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

// Fake timers throughout: each step lets the pending fetches settle, then moves the clock.
const settle = (ms = 0) => act(() => vi.advanceTimersByTimeAsync(ms));

const scanCalls = (fetchMock) =>
  fetchMock.mock.calls.filter(([url]) => String(url).includes('/api/admin/scan')).length;

const mount = () =>
  renderHook(({ visible }) => useScan(visible), { initialProps: { visible: true } });

describe('useScan: the idle poll', () => {
  it('looks again every 60 s when nothing is running', async () => {
    const fetchMock = installFetch();
    vi.useFakeTimers();
    mount();
    await settle();
    expect(scanCalls(fetchMock)).toBe(1);
    expect(SCAN_IDLE_POLL_MS).toBe(60000);

    await settle(SCAN_IDLE_POLL_MS - 1);
    expect(scanCalls(fetchMock)).toBe(1);
    await settle(1);
    expect(scanCalls(fetchMock)).toBe(2);
    await settle(SCAN_IDLE_POLL_MS);
    expect(scanCalls(fetchMock)).toBe(3);
  });

  it('notices a job that starts in the background, then polls fast and goes back to idle when it ends', async () => {
    let calls = 0;
    const fetchMock = installFetch((method, url) => {
      if (url !== '/api/admin/scan') return null;
      calls += 1;
      if (calls === 1) return respond(fixtures.adminScan);
      // The 06:00 subtitle search starts between two looks, runs for two fast polls, then ends.
      if (calls <= 3) return respond({ ...fixtures.adminScan, openSubtitlesRunning: true });
      return respond({
        ...fixtures.adminScan,
        openSubtitlesLastSearchedAt: '2026-11-13T06:05:00Z',
      });
    });
    vi.useFakeTimers();
    const { result } = mount();
    await settle();
    expect(result.current.scan.openSubtitlesRunning).toBe(false);

    await settle(SCAN_IDLE_POLL_MS);
    expect(result.current.scan.openSubtitlesRunning).toBe(true);
    expect(scanCalls(fetchMock)).toBe(2);

    await settle(SCAN_POLL_MS);
    expect(scanCalls(fetchMock)).toBe(3);
    await settle(SCAN_POLL_MS);
    expect(scanCalls(fetchMock)).toBe(4);
    expect(result.current.scan.openSubtitlesRunning).toBe(false);

    // Back to the slow look: nothing for most of a minute.
    await settle(SCAN_IDLE_POLL_MS - 1);
    expect(scanCalls(fetchMock)).toBe(4);
    await settle(1);
    expect(scanCalls(fetchMock)).toBe(5);
  });

  it('does not poll while the tab is hidden, and reads once when it is shown again', async () => {
    const fetchMock = installFetch();
    vi.useFakeTimers();
    const { rerender } = mount();
    await settle();
    expect(scanCalls(fetchMock)).toBe(1);

    rerender({ visible: false });
    await settle(SCAN_IDLE_POLL_MS * 3);
    expect(scanCalls(fetchMock)).toBe(1);

    rerender({ visible: true });
    await settle();
    expect(scanCalls(fetchMock)).toBe(2);
    // The idle look starts again from now.
    await settle(SCAN_IDLE_POLL_MS);
    expect(scanCalls(fetchMock)).toBe(3);
  });

  it('does not read again when the tab was never hidden', async () => {
    const fetchMock = installFetch();
    vi.useFakeTimers();
    const { rerender } = mount();
    await settle();

    rerender({ visible: true });
    await settle();

    expect(scanCalls(fetchMock)).toBe(1);
  });

  it('does not poll while hidden even when a job is running', async () => {
    const fetchMock = installFetch((method, url) =>
      url === '/api/admin/scan'
        ? respond({ ...fixtures.adminScan, subtitleChecksRunning: true })
        : null,
    );
    vi.useFakeTimers();
    const { rerender } = mount();
    await settle();
    rerender({ visible: false });
    const before = scanCalls(fetchMock);

    await settle(SCAN_POLL_MS * 5);

    expect(scanCalls(fetchMock)).toBe(before);
  });

  it('stops polling when the panel unmounts', async () => {
    const fetchMock = installFetch();
    vi.useFakeTimers();
    const { unmount } = mount();
    await settle();
    unmount();

    await settle(SCAN_IDLE_POLL_MS * 3);

    expect(scanCalls(fetchMock)).toBe(1);
  });
});
