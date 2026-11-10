import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LIBRARY_CHANGED_EVENT } from '../api/client.js';
import { fixtures, installFetch, respond } from '../test/mockApi.jsx';
import { SCAN_POLL_MS, usePlexImport, useScan } from './useAdmin.js';
import { usePlayback } from './usePlayback.js';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

function listen() {
  const events = [];
  const onChanged = () => events.push('changed');
  window.addEventListener(LIBRARY_CHANGED_EVENT, onChanged);
  return { events, stop: () => window.removeEventListener(LIBRARY_CHANGED_EVENT, onChanged) };
}

const settle = (ms = 0) => act(() => vi.advanceTimersByTimeAsync(ms));

/** Answers GET `url` with each body in turn, repeating the last. */
const sequence = (url, bodies) => {
  let calls = 0;
  return (method, requested) => {
    if (method !== 'GET' || requested !== url) return null;
    calls += 1;
    return respond(bodies[Math.min(calls, bodies.length) - 1]);
  };
};

const scanAt = (finishedAt, extra = {}) => ({
  ...fixtures.adminScan,
  lastScan: finishedAt === null ? null : { ...fixtures.adminScan.lastScan, finishedAt },
  ...extra,
});
const plexAt = (finishedAt, extra = {}) => ({
  ...fixtures.adminPlex,
  lastImport: finishedAt === null ? null : { ...fixtures.adminPlex.lastImport, finishedAt },
  ...extra,
});
const T1 = '2026-11-08T12:41:10Z';
const T2 = '2026-11-10T09:00:00Z';

describe('Admin announces cinefilm:library-changed when a scan or import finishes', () => {
  it('announces nothing for the first status, whatever it holds', async () => {
    installFetch(sequence('/api/admin/scan', [scanAt(T1)]));
    vi.useFakeTimers();
    const { events, stop } = listen();
    renderHook(() => useScan());

    await settle(SCAN_POLL_MS * 3);

    stop();
    expect(events).toEqual([]);
  });

  it('announces once when the last scan time changes while polling a running scan', async () => {
    const running = scanAt(T1, { running: true, progress: { processed: 1, total: 2 } });
    installFetch(sequence('/api/admin/scan', [running, running, scanAt(T2)]));
    vi.useFakeTimers();
    const { events, stop } = listen();
    renderHook(() => useScan());

    await settle();
    await settle(SCAN_POLL_MS);
    expect(events).toEqual([]);
    await settle(SCAN_POLL_MS);
    expect(events).toEqual(['changed']);
    await settle(SCAN_POLL_MS * 4);

    stop();
    expect(events).toEqual(['changed']);
  });

  it('announces a scan that starts and finishes between two polls (it is never seen running)', async () => {
    installFetch(sequence('/api/admin/scan', [scanAt(T1), scanAt(T2)]));
    vi.useFakeTimers();
    const { result } = renderHook(() => useScan());
    await settle();
    const { events, stop } = listen();

    await act(async () => {
      void result.current.startScan();
    });
    await settle();

    stop();
    expect(result.current.scan.running).toBe(false);
    expect(events).toEqual(['changed']);
  });

  it('announces the first scan ever finishing on a library that never had one', async () => {
    installFetch(sequence('/api/admin/scan', [scanAt(null), scanAt(T2)]));
    vi.useFakeTimers();
    const { result } = renderHook(() => useScan());
    await settle();
    const { events, stop } = listen();
    expect(events).toEqual([]);

    await act(async () => {
      void result.current.startScan();
    });
    await settle();

    stop();
    expect(events).toEqual(['changed']);
  });

  it('announces nothing while the last scan time stays the same', async () => {
    installFetch();
    vi.useFakeTimers();
    const { events, stop } = listen();
    renderHook(() => useScan());

    await settle(SCAN_POLL_MS * 3);

    stop();
    expect(events).toEqual([]);
  });

  it('announces once when a Plex import finishes, including one that finishes before the first poll', async () => {
    installFetch(sequence('/api/admin/plex', [plexAt(T1), plexAt(T2)]));
    vi.useFakeTimers();
    const { result } = renderHook(() => usePlexImport());
    await settle();
    const { events, stop } = listen();
    expect(events).toEqual([]);

    await act(async () => {
      void result.current.startImport();
    });
    await settle();

    stop();
    expect(result.current.plex.running).toBe(false);
    expect(events).toEqual(['changed']);
  });

  it('announces nothing for the first Plex status', async () => {
    installFetch(sequence('/api/admin/plex', [plexAt(T1)]));
    vi.useFakeTimers();
    const { events, stop } = listen();
    renderHook(() => usePlexImport());

    await settle(SCAN_POLL_MS * 2);

    stop();
    expect(events).toEqual([]);
  });

  it('treats a Plex status with no import yet as a first load, then announces the first import', async () => {
    installFetch(sequence('/api/admin/plex', [plexAt(null), plexAt(T2)]));
    vi.useFakeTimers();
    const { result } = renderHook(() => usePlexImport());
    await settle();
    const { events, stop } = listen();

    await act(async () => {
      void result.current.startImport();
    });
    await settle();

    stop();
    expect(events).toEqual(['changed']);
  });
});

describe('the player announces cinefilm:library-changed when it closes', () => {
  const props = { filmId: 1, editionId: 11, playing: false };

  it('announces after the final progress report has been saved', async () => {
    const order = [];
    installFetch((method, url) => {
      if (method === 'PUT' && url.endsWith('/progress')) order.push('saved');
      return null;
    });
    const onChanged = () => order.push('changed');
    window.addEventListener(LIBRARY_CHANGED_EVENT, onChanged);
    const { unmount } = renderHook(() => usePlayback({ ...props, getPositionMs: () => 61000 }));

    unmount();

    await waitFor(() => expect(order).toEqual(['saved', 'changed']));
    window.removeEventListener(LIBRARY_CHANGED_EVENT, onChanged);
  });

  it('announces even when there was nothing new to save (the position never moved)', async () => {
    installFetch();
    const { events, stop } = listen();
    const { unmount } = renderHook(() => usePlayback({ ...props, getPositionMs: () => 0 }));

    unmount();

    await waitFor(() => expect(events).toEqual(['changed']));
    stop();
  });

  it('announces even when the final report itself throws', async () => {
    installFetch();
    const { events, stop } = listen();
    let broken = false;
    const { unmount } = renderHook(() =>
      usePlayback({
        ...props,
        getPositionMs: () => {
          if (broken) throw new Error('video element gone');
          return 0;
        },
      }),
    );
    broken = true;

    unmount();

    await waitFor(() => expect(events).toEqual(['changed']));
    stop();
  });

  it('waits for a progress write still in flight before announcing', async () => {
    const order = [];
    let release;
    const held = new Promise((resolve) => {
      release = resolve;
    });
    installFetch((method, url) => {
      if (method === 'PUT' && url.endsWith('/progress')) {
        order.push('put');
        return held.then(() => {
          order.push('put-done');
          return respond({ positionMs: 61000, watched: false });
        });
      }
      return null;
    });
    const onChanged = () => order.push('changed');
    window.addEventListener(LIBRARY_CHANGED_EVENT, onChanged);
    const { result, unmount } = renderHook(() =>
      usePlayback({ ...props, getPositionMs: () => 61000 }),
    );
    act(() => {
      void result.current.report();
    });
    await waitFor(() => expect(order).toEqual(['put']));

    unmount();
    await act(async () => {});
    expect(order).toEqual(['put']);
    release();

    await waitFor(() => expect(order).toEqual(['put', 'put-done', 'changed']));
    window.removeEventListener(LIBRARY_CHANGED_EVENT, onChanged);
  });

  it('announces nothing while it is open', async () => {
    installFetch();
    const { events, stop } = listen();
    renderHook(() => usePlayback({ ...props, getPositionMs: () => 61000 }));

    await act(async () => {});

    stop();
    expect(events).toEqual([]);
  });
});
