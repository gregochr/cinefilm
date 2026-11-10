import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  LIBRARY_CHANGED_EVENT,
  SESSION_EXPIRED_EVENT,
  SIGNED_IN_EVENT,
  announceLibraryChanged,
} from '../api/client.js';
import {
  SEARCH_INDEX_STORAGE_KEY,
  getSnapshot,
  resetSearchIndexForTests,
} from '../state/searchIndex.js';
import { installStorage } from '../test/storage.js';
import { useSearchIndexLifecycle } from './useSearchIndexLifecycle.js';

const E1 = 'W/"0000000000000001"';
const E2 = 'W/"0000000000000002"';
const RAW = { films: [{ id: 1, t: 'Warrior' }], collections: [], people: [] };
const RAW_2 = { films: [], collections: [], people: [] };

const json = (body, etag) =>
  new Response(JSON.stringify(body), { status: 200, headers: { ETag: etag } });
const notModified = () => new Response(null, { status: 304 });

function queueFetch(...responses) {
  const queue = [...responses];
  const mock = vi.fn(async () => queue.shift()?.() ?? new Response(null, { status: 500 }));
  vi.stubGlobal('fetch', mock);
  return mock;
}

const sent = (mock, n) => mock.mock.calls[n][1].headers['If-None-Match'];

let storage;
beforeEach(() => {
  storage = installStorage();
});
afterEach(() => {
  resetSearchIndexForTests();
  delete document.visibilityState;
  vi.unstubAllGlobals();
});

describe('useSearchIndexLifecycle', () => {
  it('starts the load when the app shell mounts', async () => {
    const fetchMock = queueFetch(() => json(RAW, E1));

    renderHook(() => useSearchIndexLifecycle());

    await waitFor(() => expect(getSnapshot().status).toBe('ready'));
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(sent(fetchMock, 0)).toBeUndefined();
  });

  it('revalidates when the shell mounts again, as after the player (which has no shell) closes', async () => {
    const fetchMock = queueFetch(
      () => json(RAW, E1),
      () => json(RAW_2, E2),
    );
    const first = renderHook(() => useSearchIndexLifecycle());
    await waitFor(() => expect(getSnapshot().status).toBe('ready'));
    first.unmount();

    renderHook(() => useSearchIndexLifecycle());

    await waitFor(() => expect(getSnapshot().index.films).toHaveLength(0));
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(sent(fetchMock, 1)).toBe(E1);
  });

  it('revalidates when the tab becomes visible, and not when it is hidden', async () => {
    const fetchMock = queueFetch(() => json(RAW, E1), notModified, notModified);
    renderHook(() => useSearchIndexLifecycle());
    await waitFor(() => expect(getSnapshot().status).toBe('ready'));
    const setVisibility = (value) =>
      Object.defineProperty(document, 'visibilityState', { value, configurable: true });

    setVisibility('hidden');
    document.dispatchEvent(new Event('visibilitychange'));
    await act(async () => {});
    expect(fetchMock).toHaveBeenCalledTimes(1);

    setVisibility('visible');
    document.dispatchEvent(new Event('visibilitychange'));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    expect(sent(fetchMock, 1)).toBe(E1);
  });

  it('revalidates on cinefilm:library-changed and picks up the new index', async () => {
    const fetchMock = queueFetch(
      () => json(RAW, E1),
      () => json(RAW_2, E2),
    );
    renderHook(() => useSearchIndexLifecycle());
    await waitFor(() => expect(getSnapshot().status).toBe('ready'));

    act(() => announceLibraryChanged());

    await waitFor(() => expect(getSnapshot().index.films).toHaveLength(0));
    expect(sent(fetchMock, 1)).toBe(E1);
    expect(LIBRARY_CHANGED_EVENT).toBe('cinefilm:library-changed');
  });

  it('revalidates after signing in again', async () => {
    const fetchMock = queueFetch(() => json(RAW, E1), notModified);
    renderHook(() => useSearchIndexLifecycle());
    await waitFor(() => expect(getSnapshot().status).toBe('ready'));

    window.dispatchEvent(new CustomEvent(SIGNED_IN_EVENT));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    expect(sent(fetchMock, 1)).toBe(E1);
  });

  it('makes the store forget its stored copy when the session expires', async () => {
    queueFetch(() => json(RAW, E1));
    renderHook(() => useSearchIndexLifecycle());
    await waitFor(() => expect(storage.getItem(SEARCH_INDEX_STORAGE_KEY)).not.toBeNull());

    act(() => {
      window.dispatchEvent(new CustomEvent(SESSION_EXPIRED_EVENT));
    });

    expect(storage.getItem(SEARCH_INDEX_STORAGE_KEY)).toBeNull();
    expect(getSnapshot().status).toBe('ready');
  });

  it('stops listening when the shell unmounts', async () => {
    const fetchMock = queueFetch(() => json(RAW, E1), notModified);
    const { unmount } = renderHook(() => useSearchIndexLifecycle());
    await waitFor(() => expect(getSnapshot().status).toBe('ready'));
    unmount();

    announceLibraryChanged();
    window.dispatchEvent(new CustomEvent(SIGNED_IN_EVENT));
    window.dispatchEvent(new CustomEvent(SESSION_EXPIRED_EVENT));
    document.dispatchEvent(new Event('visibilitychange'));
    await act(async () => {});

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(storage.getItem(SEARCH_INDEX_STORAGE_KEY)).not.toBeNull();
  });
});
