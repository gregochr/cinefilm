import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  LIBRARY_CHANGED_EVENT,
  SESSION_EXPIRED_EVENT,
  SIGNED_IN_EVENT,
  announceLibraryChanged,
} from '../api/client.js';
import { installStorage } from '../test/storage.js';
import {
  SEARCH_INDEX_STORAGE_KEY,
  getSnapshot,
  prepareIndex,
  resetSearchIndexForTests,
  revalidate,
  startSearchIndex,
  subscribe,
  useSearchIndex,
} from './searchIndex.js';

const RAW = {
  films: [
    {
      id: 1,
      t: 'Warrior',
      y: 2011,
      d: ["Gavin O'Connor"],
      p: '/w.jpg',
      left: 7380000,
      at: '2026-11-01T20:00:00Z',
      s: 'warrior',
    },
    { id: 2, t: 'The Hangover', y: 2009, d: ['Todd Phillips'], s: 'hangover' },
  ],
  collections: [{ id: 4, n: 'Star Wars Collection', o: 2, of: 9, p: ['/a.jpg', '/b.jpg'] }],
  people: [{ id: 9, n: 'Tom Hanks', a: 9, d: 0 }],
};
const RAW_2 = { ...RAW, films: [RAW.films[0]] };

const json = (body, etag) =>
  new Response(JSON.stringify(body), { status: 200, headers: etag ? { ETag: etag } : {} });
const notModified = () => new Response(null, { status: 304 });

/** Fetch answers from the queue, one per call; an empty queue is a 500. */
function queueFetch(...responses) {
  const queue = [...responses];
  const mock = vi.fn(async () => queue.shift()?.() ?? new Response(null, { status: 500 }));
  vi.stubGlobal('fetch', mock);
  return mock;
}

const sent = (mock, n) => mock.mock.calls[n][1].headers['If-None-Match'];
const stored = (storage) => JSON.parse(storage.getItem(SEARCH_INDEX_STORAGE_KEY));

let storage;
beforeEach(() => {
  storage = installStorage();
});
afterEach(() => {
  resetSearchIndexForTests();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('prepareIndex', () => {
  it('spells out the keys and builds the display strings once', () => {
    const index = prepareIndex(RAW);

    expect(index.films[0]).toEqual({
      id: 1,
      title: 'Warrior',
      year: 2011,
      directors: ["Gavin O'Connor"],
      subtitle: "2011 · Gavin O'Connor",
      posterPath: '/w.jpg',
      leftMs: 7380000,
      playedAt: '2026-11-01T20:00:00Z',
      sortTitle: 'warrior',
    });
    expect(index.people).toEqual([{ id: 9, name: 'Tom Hanks', actorCount: 9, directorCount: 0 }]);
  });

  it('shows a collection by its display name, without " Collection"', () => {
    expect(prepareIndex(RAW).collections).toEqual([
      { id: 4, name: 'Star Wars', ownedCount: 2, totalCount: 9, posterPaths: ['/a.jpg', '/b.jpg'] },
    ]);
  });

  it('fills what the server left out: no poster, time left or last played; no director or year', () => {
    const [bare] = prepareIndex({ films: [{ id: 3, t: 'Heat' }] }).films;

    expect(bare).toMatchObject({
      year: null,
      directors: [],
      subtitle: '',
      posterPath: null,
      leftMs: null,
      playedAt: null,
      sortTitle: 'heat',
    });
    expect(prepareIndex({ films: [{ id: 3, t: 'Heat', y: 1995 }] }).films[0].subtitle).toBe('1995');
    expect(
      prepareIndex({
        collections: [{ id: 1, n: 'X', o: 2, of: 3 }],
        people: [{ id: 2, n: 'Y' }],
      }),
    ).toMatchObject({
      collections: [{ posterPaths: [] }],
      people: [{ actorCount: 0, directorCount: 0 }],
    });
  });

  it('gives three empty lists for nothing', () => {
    expect(prepareIndex(undefined)).toEqual({ films: [], collections: [], people: [] });
    expect(prepareIndex({})).toEqual({ films: [], collections: [], people: [] });
  });
});

describe('the search index store', () => {
  it('starts out loading with nothing held', () => {
    expect(getSnapshot()).toEqual({ index: null, status: 'loading' });
  });

  it('loads once, publishes the prepared index and keeps a copy with its ETag in localStorage', async () => {
    const fetchMock = queueFetch(() => json(RAW, '"e1"'));

    startSearchIndex();
    startSearchIndex();

    await waitFor(() => expect(getSnapshot().status).toBe('ready'));
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(sent(fetchMock, 0)).toBeUndefined();
    expect(getSnapshot().index.films.map((f) => f.title)).toEqual(['Warrior', 'The Hangover']);
    expect(stored(storage)).toEqual({ etag: '"e1"', raw: RAW });
  });

  it('shows the stored copy at once on a reload, then revalidates with its ETag; a 304 keeps the data', async () => {
    storage.setItem(SEARCH_INDEX_STORAGE_KEY, JSON.stringify({ etag: '"e1"', raw: RAW }));
    const fetchMock = queueFetch(notModified);

    startSearchIndex();

    expect(getSnapshot().status).toBe('ready');
    expect(getSnapshot().index.films).toHaveLength(2);
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    expect(sent(fetchMock, 0)).toBe('"e1"');
    await act(async () => {});
    expect(getSnapshot().index.films).toHaveLength(2);
    expect(stored(storage).etag).toBe('"e1"');
  });

  it('replaces the held index and the stored copy when the server has a newer one', async () => {
    storage.setItem(SEARCH_INDEX_STORAGE_KEY, JSON.stringify({ etag: '"e1"', raw: RAW }));
    queueFetch(() => json(RAW_2, '"e2"'));

    startSearchIndex();

    await waitFor(() => expect(getSnapshot().index.films).toHaveLength(1));
    expect(stored(storage)).toEqual({ etag: '"e2"', raw: RAW_2 });
  });

  it('does not store a response that carries no ETag, and so never sends If-None-Match for it', async () => {
    const fetchMock = queueFetch(
      () => json(RAW),
      () => json(RAW_2, '"e2"'),
    );

    startSearchIndex();
    await waitFor(() => expect(getSnapshot().status).toBe('ready'));
    expect(storage.getItem(SEARCH_INDEX_STORAGE_KEY)).toBeNull();

    await act(async () => {
      await revalidate();
    });
    expect(sent(fetchMock, 1)).toBeUndefined();
    expect(getSnapshot().index.films).toHaveLength(1);
  });

  it('ignores a stored copy that is not valid', async () => {
    for (const bad of ['not json', '{"etag":"x"}', '{"raw":{}}', 'null']) {
      storage.setItem(SEARCH_INDEX_STORAGE_KEY, bad);
      const fetchMock = queueFetch(() => json(RAW, '"e1"'));

      startSearchIndex();

      expect(getSnapshot().status).toBe('loading');
      await waitFor(() => expect(getSnapshot().status).toBe('ready'));
      expect(sent(fetchMock, 0)).toBeUndefined();
      resetSearchIndexForTests();
    }
  });

  it('still works when localStorage throws on every access', async () => {
    const throwing = () => {
      throw new Error('storage blocked');
    };
    const blocked = { getItem: throwing, setItem: throwing, removeItem: throwing };
    vi.stubGlobal('localStorage', blocked);
    Object.defineProperty(window, 'localStorage', { value: blocked, configurable: true });
    queueFetch(() => json(RAW, '"e1"'));

    startSearchIndex();
    await waitFor(() => expect(getSnapshot().status).toBe('ready'));

    expect(getSnapshot().index.films).toHaveLength(2);
    expect(() => window.dispatchEvent(new CustomEvent(SESSION_EXPIRED_EVENT))).not.toThrow();
  });

  it('is an error when the first load fails with nothing held, and recovers on the next revalidation', async () => {
    queueFetch(
      () => new Response(null, { status: 500 }),
      () => json(RAW, '"e1"'),
    );

    startSearchIndex();
    await waitFor(() => expect(getSnapshot().status).toBe('error'));
    expect(getSnapshot().index).toBeNull();

    await act(async () => {
      await revalidate();
    });
    expect(getSnapshot().status).toBe('ready');
  });

  it('keeps what it holds when a revalidation fails', async () => {
    storage.setItem(SEARCH_INDEX_STORAGE_KEY, JSON.stringify({ etag: '"e1"', raw: RAW }));
    const fetchMock = queueFetch(() => new Response(null, { status: 500 }));

    startSearchIndex();
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    await act(async () => {});

    expect(getSnapshot().status).toBe('ready');
    expect(getSnapshot().index.films).toHaveLength(2);
  });

  it('revalidates when the tab becomes visible, and not when it is hidden', async () => {
    const fetchMock = queueFetch(() => json(RAW, '"e1"'), notModified, notModified);
    startSearchIndex();
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
    expect(sent(fetchMock, 1)).toBe('"e1"');
    delete document.visibilityState;
  });

  it('revalidates on cinefilm:library-changed and picks up the new index', async () => {
    const fetchMock = queueFetch(
      () => json(RAW, '"e1"'),
      () => json(RAW_2, '"e2"'),
    );
    startSearchIndex();
    await waitFor(() => expect(getSnapshot().status).toBe('ready'));

    act(() => announceLibraryChanged());

    await waitFor(() => expect(getSnapshot().index.films).toHaveLength(1));
    expect(sent(fetchMock, 1)).toBe('"e1"');
    expect(LIBRARY_CHANGED_EVENT).toBe('cinefilm:library-changed');
  });

  it('revalidates after signing in again', async () => {
    const fetchMock = queueFetch(() => json(RAW, '"e1"'), notModified);
    startSearchIndex();
    await waitFor(() => expect(getSnapshot().status).toBe('ready'));

    window.dispatchEvent(new CustomEvent(SIGNED_IN_EVENT));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    expect(sent(fetchMock, 1)).toBe('"e1"');
  });

  it('shares the request in flight, then asks exactly once more, so a change made meanwhile is not missed', async () => {
    const fetchMock = queueFetch(
      () => json(RAW, '"e1"'),
      () => json(RAW_2, '"e2"'),
      () => json(RAW, '"e3"'),
    );

    startSearchIndex();
    const again = revalidate();
    window.dispatchEvent(new CustomEvent(LIBRARY_CHANGED_EVENT));
    await again;

    await waitFor(() => expect(getSnapshot().index.films).toHaveLength(1));
    await act(async () => {});
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(sent(fetchMock, 1)).toBe('"e1"');
  });

  it('does not ask again when nothing overlapped', async () => {
    const fetchMock = queueFetch(() => json(RAW, '"e1"'), notModified);
    startSearchIndex();
    await waitFor(() => expect(getSnapshot().status).toBe('ready'));
    await act(async () => {});

    await act(async () => {
      await revalidate();
    });
    await act(async () => {});

    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('clears the stored copy when the session expires, and drops the request in flight', async () => {
    storage.setItem(SEARCH_INDEX_STORAGE_KEY, JSON.stringify({ etag: '"e1"', raw: RAW }));
    let signal;
    vi.stubGlobal(
      'fetch',
      vi.fn(
        (url, init) =>
          new Promise((resolve, reject) => {
            signal = init.signal;
            init.signal.addEventListener('abort', () =>
              reject(new DOMException('x', 'AbortError')),
            );
          }),
      ),
    );
    startSearchIndex();
    await waitFor(() => expect(signal).toBeDefined());

    act(() => {
      window.dispatchEvent(new CustomEvent(SESSION_EXPIRED_EVENT));
    });

    expect(storage.getItem(SEARCH_INDEX_STORAGE_KEY)).toBeNull();
    expect(signal.aborted).toBe(true);
    await act(async () => {});
    expect(getSnapshot().status).toBe('ready');
  });

  it('does not let an aborted first load turn into an error', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(
        (url, init) =>
          new Promise((resolve, reject) => {
            init.signal.addEventListener('abort', () =>
              reject(new DOMException('x', 'AbortError')),
            );
          }),
      ),
    );
    startSearchIndex();

    window.dispatchEvent(new CustomEvent(SESSION_EXPIRED_EVENT));
    await act(async () => {});

    expect(getSnapshot().status).toBe('loading');
  });

  it('notifies subscribers on a change and stops after they unsubscribe', async () => {
    queueFetch(
      () => json(RAW, '"e1"'),
      () => json(RAW_2, '"e2"'),
    );
    const listener = vi.fn();
    const unsubscribe = subscribe(listener);
    startSearchIndex();
    await waitFor(() => expect(listener).toHaveBeenCalledTimes(1));

    unsubscribe();
    await act(async () => {
      await revalidate();
    });

    expect(listener).toHaveBeenCalledTimes(1);
  });

  it('reset forgets the held index and detaches the listeners', async () => {
    const fetchMock = queueFetch(() => json(RAW, '"e1"'));
    startSearchIndex();
    await waitFor(() => expect(getSnapshot().status).toBe('ready'));

    resetSearchIndexForTests();
    announceLibraryChanged();
    await act(async () => {});

    expect(getSnapshot()).toEqual({ index: null, status: 'loading' });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});

describe('useSearchIndex', () => {
  it('starts the load on first use and returns the shared { index, status }', async () => {
    const fetchMock = queueFetch(() => json(RAW, '"e1"'));

    const { result, rerender } = renderHook(() => useSearchIndex());
    const second = renderHook(() => useSearchIndex());

    expect(result.current.status).toBe('loading');
    await waitFor(() => expect(result.current.status).toBe('ready'));
    expect(result.current.index.collections[0].name).toBe('Star Wars');
    expect(second.result.current).toBe(result.current);
    rerender();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
