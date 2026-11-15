import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client.js';
import { fixtures, installFetch, respond } from '../test/mockApi.jsx';
import { SUMMARY_POLL_MS, useAdminSummary } from './useAdminSummary.js';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

// Fake timers throughout: each step lets the pending fetches settle, then moves the clock.
const settle = (ms = 0) => act(() => vi.advanceTimersByTimeAsync(ms));

const summaryCalls = (fetchMock) =>
  fetchMock.mock.calls.filter(([url]) => url === '/api/admin/summary').length;

const STAMPS = { settledAt: 'a', subtitlesVersion: 'b', changed: 0 };
const mount = (initial = { visible: true, stamps: STAMPS }) =>
  renderHook(({ visible, stamps }) => useAdminSummary(visible, stamps), {
    initialProps: initial,
  });

const deferred = () => {
  const box = {};
  box.promise = new Promise((resolve) => {
    box.resolve = resolve;
  });
  return box;
};

describe('useAdminSummary: reading', () => {
  it('reads the summary once and returns it', async () => {
    const fetchMock = installFetch();
    vi.useFakeTimers();
    const { result } = mount();
    expect(result.current.loading).toBe(true);
    expect(result.current.summary).toBeNull();

    await settle();

    expect(summaryCalls(fetchMock)).toBe(1);
    expect(result.current.summary).toEqual(fixtures.adminSummary);
    expect(result.current.loading).toBe(false);
    expect(result.current.error).toBeNull();
  });

  it.each([
    ['settledAt', { settledAt: 'a2', subtitlesVersion: 'b', changed: 0 }],
    ['subtitlesVersion', { settledAt: 'a', subtitlesVersion: 'b2', changed: 0 }],
    ['changed', { settledAt: 'a', subtitlesVersion: 'b', changed: 1 }],
  ])('reads again when %s changes, and not when nothing does', async (_key, stamps) => {
    const fetchMock = installFetch();
    vi.useFakeTimers();
    const { rerender } = mount();
    await settle();
    expect(summaryCalls(fetchMock)).toBe(1);

    rerender({ visible: true, stamps: { ...STAMPS } });
    await settle();
    expect(summaryCalls(fetchMock)).toBe(1);

    rerender({ visible: true, stamps });
    await settle();
    expect(summaryCalls(fetchMock)).toBe(2);
  });

  it('keeps the figures on screen while a changed stamp is read again', async () => {
    const later = deferred();
    let calls = 0;
    installFetch((method, url) => {
      if (url !== '/api/admin/summary') return null;
      calls += 1;
      return calls === 1 ? respond(fixtures.adminSummary) : later.promise;
    });
    vi.useFakeTimers();
    const { result, rerender } = mount();
    await settle();

    rerender({ visible: true, stamps: { ...STAMPS, changed: 1 } });
    await settle();

    expect(result.current.summary).toEqual(fixtures.adminSummary);
  });
});

describe('useAdminSummary: the poll', () => {
  it('reads again every 30 s while the tab is visible, 1 ms either side of the boundary', async () => {
    const fetchMock = installFetch();
    vi.useFakeTimers();
    mount();
    await settle();
    expect(SUMMARY_POLL_MS).toBe(30000);
    expect(summaryCalls(fetchMock)).toBe(1);

    await settle(29_999);
    expect(summaryCalls(fetchMock)).toBe(1);
    await settle(1);
    expect(summaryCalls(fetchMock)).toBe(2);

    await settle(29_999);
    expect(summaryCalls(fetchMock)).toBe(2);
    await settle(1);
    expect(summaryCalls(fetchMock)).toBe(3);
  });

  it('shows the newer figures a poll brings, without flipping to loading', async () => {
    let live = 1;
    installFetch((method, url) =>
      url === '/api/admin/summary' ? respond({ ...fixtures.adminSummary, live: live++ }) : null,
    );
    vi.useFakeTimers();
    const { result } = mount();
    await settle();
    expect(result.current.summary.live).toBe(1);

    await settle(SUMMARY_POLL_MS - 1);
    expect(result.current.loading).toBe(false);
    await settle(1);

    expect(result.current.summary.live).toBe(2);
    expect(result.current.loading).toBe(false);
  });

  it('does not poll while the tab is hidden, and reads once when it is shown again', async () => {
    const fetchMock = installFetch();
    vi.useFakeTimers();
    const { rerender } = mount();
    await settle();

    rerender({ visible: false, stamps: STAMPS });
    await settle(SUMMARY_POLL_MS * 3);
    expect(summaryCalls(fetchMock)).toBe(1);

    rerender({ visible: true, stamps: STAMPS });
    await settle();
    expect(summaryCalls(fetchMock)).toBe(2);
    // The poll starts again from now.
    await settle(SUMMARY_POLL_MS - 1);
    expect(summaryCalls(fetchMock)).toBe(2);
    await settle(1);
    expect(summaryCalls(fetchMock)).toBe(3);
  });

  it('does not read again when the tab was never hidden', async () => {
    const fetchMock = installFetch();
    vi.useFakeTimers();
    const { rerender } = mount();
    await settle();

    rerender({ visible: true, stamps: STAMPS });
    await settle();

    expect(summaryCalls(fetchMock)).toBe(1);
  });

  it('keeps polling when a poll never answers: one more read at 30 s, not at 29,999 ms', async () => {
    const hung = deferred();
    let calls = 0;
    const fetchMock = installFetch((method, url) => {
      if (url !== '/api/admin/summary') return null;
      calls += 1;
      return calls === 1 ? respond(fixtures.adminSummary) : hung.promise;
    });
    vi.useFakeTimers();
    mount();
    await settle();
    await settle(SUMMARY_POLL_MS); // the second read is out and never answers
    expect(summaryCalls(fetchMock)).toBe(2);

    await settle(SUMMARY_POLL_MS - 1);
    expect(summaryCalls(fetchMock)).toBe(2);
    await settle(1);
    expect(summaryCalls(fetchMock)).toBe(3);
  });

  it('stops the poll when the hook unmounts', async () => {
    const fetchMock = installFetch();
    vi.useFakeTimers();
    const { unmount } = mount();
    await settle();

    unmount();
    await settle(SUMMARY_POLL_MS * 3);

    expect(summaryCalls(fetchMock)).toBe(1);
  });
});

describe('useAdminSummary: holding the first read', () => {
  const notReady = { ...STAMPS, ready: false };

  it('makes no request until ready, then exactly one', async () => {
    const fetchMock = installFetch();
    vi.useFakeTimers();
    const { rerender, result } = mount({ visible: true, stamps: notReady });
    await settle(SUMMARY_POLL_MS * 2);
    expect(summaryCalls(fetchMock)).toBe(0);
    expect(result.current.summary).toBeNull();

    rerender({ visible: true, stamps: { ...STAMPS, ready: true } });
    await settle();

    expect(summaryCalls(fetchMock)).toBe(1);
    expect(result.current.summary).toEqual(fixtures.adminSummary);
  });
});

describe('useAdminSummary: readiness latches', () => {
  it('keeps reading and polling when ready goes back to false after it was true', async () => {
    const fetchMock = installFetch();
    vi.useFakeTimers();
    const { rerender, result } = mount({ visible: true, stamps: { ...STAMPS, ready: true } });
    await settle();
    expect(summaryCalls(fetchMock)).toBe(1);

    rerender({ visible: true, stamps: { ...STAMPS, ready: false } });
    await settle();
    expect(summaryCalls(fetchMock)).toBe(1);
    expect(result.current.summary).toEqual(fixtures.adminSummary);

    await settle(SUMMARY_POLL_MS - 1);
    expect(summaryCalls(fetchMock)).toBe(1);
    await settle(1);
    expect(summaryCalls(fetchMock)).toBe(2);

    rerender({ visible: true, stamps: { ...STAMPS, changed: 1, ready: false } });
    await settle();
    expect(summaryCalls(fetchMock)).toBe(3);
  });
});

describe('useAdminSummary: the poll restarts with a changed stamp', () => {
  it('polls 30 s after a change read, not 30 s after the start: -1 ms, exact', async () => {
    const fetchMock = installFetch();
    vi.useFakeTimers();
    const { rerender } = mount();
    await settle();
    await settle(29_000);
    expect(summaryCalls(fetchMock)).toBe(1);

    rerender({ visible: true, stamps: { ...STAMPS, changed: 1 } });
    await settle();
    expect(summaryCalls(fetchMock)).toBe(2);

    await settle(SUMMARY_POLL_MS - 1);
    expect(summaryCalls(fetchMock)).toBe(2);
    await settle(1);
    expect(summaryCalls(fetchMock)).toBe(3);
  });
});

describe('useAdminSummary: a late older answer', () => {
  it('never overwrites the answer to a newer read', async () => {
    const older = deferred();
    const newer = deferred();
    const answers = [older, newer];
    installFetch((method, url) => (url === '/api/admin/summary' ? answers.shift().promise : null));
    vi.useFakeTimers();
    const { result, rerender } = mount();
    await settle();

    rerender({ visible: true, stamps: { ...STAMPS, changed: 1 } });
    await settle();
    newer.resolve(respond({ ...fixtures.adminSummary, live: 7 }));
    await settle();
    expect(result.current.summary.live).toBe(7);

    older.resolve(respond({ ...fixtures.adminSummary, live: 3 }));
    await settle();

    expect(result.current.summary.live).toBe(7);
  });

  it('drops a poll answer that arrives after a newer change read was asked for', async () => {
    const polled = deferred();
    const changed = deferred();
    let calls = 0;
    installFetch((method, url) => {
      if (url !== '/api/admin/summary') return null;
      calls += 1;
      if (calls === 1) return respond(fixtures.adminSummary);
      return calls === 2 ? polled.promise : changed.promise;
    });
    vi.useFakeTimers();
    const { result, rerender } = mount();
    await settle();
    await settle(SUMMARY_POLL_MS); // the poll is out

    rerender({ visible: true, stamps: { ...STAMPS, changed: 1 } });
    await settle();
    changed.resolve(respond({ ...fixtures.adminSummary, live: 9 }));
    await settle();
    polled.resolve(respond({ ...fixtures.adminSummary, live: 2 }));
    await settle();

    expect(result.current.summary.live).toBe(9);
  });
});

describe('useAdminSummary: errors', () => {
  it('keeps the last good figures when a later read fails', async () => {
    let fail = false;
    installFetch((method, url) =>
      url === '/api/admin/summary' && fail ? respond({ error: 'boom' }, 500) : null,
    );
    vi.useFakeTimers();
    const { result } = mount();
    await settle();

    fail = true;
    await settle(SUMMARY_POLL_MS);

    expect(result.current.error).toBeInstanceOf(ApiError);
    expect(result.current.summary).toEqual(fixtures.adminSummary);
  });

  it('reports a failed read, and reload reads again and clears it', async () => {
    let fail = true;
    const fetchMock = installFetch((method, url) =>
      url === '/api/admin/summary' && fail ? respond({ error: 'boom' }, 500) : null,
    );
    vi.useFakeTimers();
    const { result } = mount();
    await settle();

    expect(result.current.error).toBeInstanceOf(ApiError);
    expect(result.current.summary).toBeNull();
    expect(result.current.loading).toBe(false);

    fail = false;
    act(() => result.current.reload());
    await settle();

    expect(summaryCalls(fetchMock)).toBe(2);
    expect(result.current.error).toBeNull();
    expect(result.current.summary).toEqual(fixtures.adminSummary);
  });

  it('keeps looking every 30 s after a failure, so it recovers by itself', async () => {
    let fail = true;
    installFetch((method, url) =>
      url === '/api/admin/summary' && fail ? respond({ error: 'boom' }, 500) : null,
    );
    vi.useFakeTimers();
    const { result } = mount();
    await settle();
    expect(result.current.error).toBeInstanceOf(ApiError);

    fail = false;
    await settle(SUMMARY_POLL_MS);

    expect(result.current.summary).toEqual(fixtures.adminSummary);
    expect(result.current.error).toBeNull();
  });
});
