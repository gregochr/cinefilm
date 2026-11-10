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

/** Answers `url` with each body in turn, repeating the last. */
const sequence = (url, bodies) => {
  let calls = 0;
  return (method, requested) => {
    if (requested !== url) return null;
    calls += 1;
    return respond(bodies[Math.min(calls, bodies.length) - 1]);
  };
};

describe('Admin announces cinefilm:library-changed when a scan or import finishes', () => {
  it('announces once when a scan goes from running to finished, and not before', async () => {
    const running = { ...fixtures.adminScan, running: true, progress: { processed: 1, total: 2 } };
    installFetch(sequence('/api/admin/scan', [running, running, fixtures.adminScan]));
    vi.useFakeTimers();
    const { events, stop } = listen();
    renderHook(() => useScan());

    await settle();
    expect(events).toEqual([]);
    await settle(SCAN_POLL_MS);
    expect(events).toEqual([]);
    await settle(SCAN_POLL_MS);
    expect(events).toEqual(['changed']);
    await settle(SCAN_POLL_MS * 4);

    stop();
    expect(events).toEqual(['changed']);
  });

  it('announces nothing for a scan that was never running', async () => {
    installFetch();
    vi.useFakeTimers();
    const { events, stop } = listen();
    renderHook(() => useScan());

    await settle(SCAN_POLL_MS * 3);

    stop();
    expect(events).toEqual([]);
  });

  it('announces once when a Plex import goes from running to finished', async () => {
    const importing = { ...fixtures.adminPlex, running: true, progress: { done: 1, total: 2 } };
    installFetch(
      sequence('/api/admin/plex', [importing, { ...fixtures.adminPlex, running: false }]),
    );
    vi.useFakeTimers();
    const { events, stop } = listen();
    renderHook(() => usePlexImport());

    await settle();
    expect(events).toEqual([]);
    await settle(SCAN_POLL_MS);
    expect(events).toEqual(['changed']);

    stop();
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

  it('announces nothing while it is open', async () => {
    installFetch();
    const { events, stop } = listen();
    renderHook(() => usePlayback({ ...props, getPositionMs: () => 61000 }));

    await act(async () => {});

    stop();
    expect(events).toEqual([]);
  });
});
