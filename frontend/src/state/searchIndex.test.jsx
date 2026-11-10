import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { installStorage } from '../test/storage.js';
import {
  SEARCH_INDEX_STORAGE_KEY,
  forgetSearchIndex,
  getSnapshot,
  isValidEtag,
  prepareIndex,
  resetSearchIndexForTests,
  revalidate,
  sanitizeRaw,
  startSearchIndex,
  subscribe,
  useSearchIndex,
} from './searchIndex.js';

const E1 = 'W/"0000000000000001"';
const E2 = 'W/"0000000000000002"';
const E3 = 'W/"0000000000000003"';

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
const RAW_CLEAN = {
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
  collections: RAW.collections,
  people: RAW.people,
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
    const fetchMock = queueFetch(() => json(RAW, E1));

    expect(startSearchIndex()).toBe(true);
    expect(startSearchIndex()).toBe(false);

    await waitFor(() => expect(getSnapshot().status).toBe('ready'));
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(sent(fetchMock, 0)).toBeUndefined();
    expect(getSnapshot().index.films.map((f) => f.title)).toEqual(['Warrior', 'The Hangover']);
    expect(stored(storage)).toEqual({ etag: E1, raw: RAW });
  });

  it('shows the stored copy at once on a reload, then revalidates with its ETag; a 304 keeps the data', async () => {
    storage.setItem(SEARCH_INDEX_STORAGE_KEY, JSON.stringify({ etag: E1, raw: RAW }));
    const fetchMock = queueFetch(notModified);

    startSearchIndex();

    expect(getSnapshot().status).toBe('ready');
    expect(getSnapshot().index.films).toHaveLength(2);
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    expect(sent(fetchMock, 0)).toBe(E1);
    await act(async () => {});
    expect(getSnapshot().index.films).toHaveLength(2);
    expect(stored(storage).etag).toBe(E1);
  });

  it('replaces the held index and the stored copy when the server has a newer one', async () => {
    storage.setItem(SEARCH_INDEX_STORAGE_KEY, JSON.stringify({ etag: E1, raw: RAW }));
    queueFetch(() => json(RAW_2, E2));

    startSearchIndex();

    await waitFor(() => expect(getSnapshot().index.films).toHaveLength(1));
    expect(stored(storage)).toEqual({ etag: E2, raw: RAW_2 });
  });

  it('does not store a response that carries no ETag, and so never sends If-None-Match for it', async () => {
    const fetchMock = queueFetch(
      () => json(RAW),
      () => json(RAW_2, E2),
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
      const fetchMock = queueFetch(() => json(RAW, E1));

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
    queueFetch(() => json(RAW, E1));

    startSearchIndex();
    await waitFor(() => expect(getSnapshot().status).toBe('ready'));

    expect(getSnapshot().index.films).toHaveLength(2);
    expect(() => forgetSearchIndex()).not.toThrow();
  });

  it('is an error when the first load fails with nothing held, and recovers on the next revalidation', async () => {
    queueFetch(
      () => new Response(null, { status: 500 }),
      () => json(RAW, E1),
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
    storage.setItem(SEARCH_INDEX_STORAGE_KEY, JSON.stringify({ etag: E1, raw: RAW }));
    const fetchMock = queueFetch(() => new Response(null, { status: 500 }));

    startSearchIndex();
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    await act(async () => {});

    expect(getSnapshot().status).toBe('ready');
    expect(getSnapshot().index.films).toHaveLength(2);
  });

  it('shares the request in flight, then asks exactly once more, so a change made meanwhile is not missed', async () => {
    const fetchMock = queueFetch(
      () => json(RAW, E1),
      () => json(RAW_2, E2),
      () => json(RAW, E3),
    );

    startSearchIndex();
    const again = revalidate();
    void revalidate();
    await again;

    await waitFor(() => expect(getSnapshot().index.films).toHaveLength(1));
    await act(async () => {});
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(sent(fetchMock, 1)).toBe(E1);
  });

  it('does not ask again when nothing overlapped', async () => {
    const fetchMock = queueFetch(() => json(RAW, E1), notModified);
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
    storage.setItem(SEARCH_INDEX_STORAGE_KEY, JSON.stringify({ etag: E1, raw: RAW }));
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

    act(() => forgetSearchIndex());

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

    forgetSearchIndex();
    await act(async () => {});

    expect(getSnapshot().status).toBe('loading');
  });

  it('notifies subscribers on a change and stops after they unsubscribe', async () => {
    queueFetch(
      () => json(RAW, E1),
      () => json(RAW_2, E2),
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

  it('reset forgets the held index', async () => {
    const fetchMock = queueFetch(() => json(RAW, E1));
    startSearchIndex();
    await waitFor(() => expect(getSnapshot().status).toBe('ready'));

    resetSearchIndexForTests();
    await act(async () => {});

    expect(getSnapshot()).toEqual({ index: null, status: 'loading' });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});

describe('useSearchIndex', () => {
  it('returns the shared { index, status } to every reader', async () => {
    const fetchMock = queueFetch(() => json(RAW, E1));
    startSearchIndex();

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

describe('what the store trusts', () => {
  it('accepts only weak ETags of 16 hex characters, or the fixture ETag', () => {
    expect(isValidEtag('W/"0123456789abcdef"')).toBe(true);
    expect(isValidEtag('"fixtures"')).toBe(true);
    for (const bad of [
      '"0123456789abcdef"',
      'W/"0123456789ABCDEF"',
      'W/"0123456789abcde"',
      'W/"0123456789abcdef0"',
      'W/"0123456789abcdef"\n',
      '<script>',
      '',
      null,
      undefined,
      42,
      { toString: () => 'W/"0123456789abcdef"' },
    ]) {
      expect(isValidEtag(bad), String(bad)).toBe(false);
    }
  });

  it('rebuilds a conforming index with exactly the known fields', () => {
    const clean = sanitizeRaw({
      films: [{ ...RAW.films[0], extra: 'x', __proto__: { evil: true } }],
      collections: [{ ...RAW.collections[0], extra: 1 }],
      people: [{ ...RAW.people[0], extra: [] }],
      extra: 'ignored',
    });

    expect(clean).toEqual({ ...RAW_CLEAN, films: [RAW_CLEAN.films[0]] });
    expect(Object.keys(clean)).toEqual(['films', 'collections', 'people']);
  });

  it('drops entries missing their id or name, or whose id is not a finite number', () => {
    const clean = sanitizeRaw({
      films: [
        { t: 'No id' },
        { id: '7', t: 'String id' },
        { id: Infinity, t: 'Infinite id' },
        { id: NaN, t: 'NaN id' },
        { id: 8 },
        { id: 9, t: 42 },
        null,
        'film',
        { id: 10, t: 'Kept' },
      ],
      collections: [
        { id: 1, n: 'No counts' },
        { id: 2, n: 'Bad counts', o: '2', of: 3 },
        { id: 3, n: 'Kept', o: 2, of: 3 },
      ],
      people: [{ id: 'x', n: 'Bad id' }, { id: 1 }, { id: 2, n: 'Kept' }],
    });

    expect(clean.films).toEqual([{ id: 10, t: 'Kept', d: [] }]);
    expect(clean.collections).toEqual([{ id: 3, n: 'Kept', o: 2, of: 3, p: [] }]);
    expect(clean.people).toEqual([{ id: 2, n: 'Kept', a: 0, d: 0 }]);
  });

  it('leaves out optional fields of the wrong type and filters lists to strings with capped lengths', () => {
    const [film] = sanitizeRaw({
      films: [
        {
          id: 1,
          t: 'Warrior',
          y: '2011',
          d: [
            'Ok',
            5,
            null,
            { a: 1 },
            'x'.repeat(301),
            ...Array.from({ length: 30 }, (_, i) => `D${i}`),
          ],
          p: { path: 1 },
          left: Infinity,
          at: 12,
          s: ['warrior'],
        },
      ],
    }).films;

    expect(Object.keys(film)).toEqual(['id', 't', 'd']);
    expect(film.d).toHaveLength(20);
    expect(film.d.slice(0, 3)).toEqual(['Ok', 'D0', 'D1']);
    const [collection] = sanitizeRaw({
      collections: [{ id: 1, n: 'X', o: 1, of: 1, p: ['/a', '/b', '/c', '/d', 7] }],
    }).collections;
    expect(collection.p).toEqual(['/a', '/b', '/c']);
  });

  it('gives empty lists for anything that is not an index', () => {
    for (const bad of [
      null,
      undefined,
      'x',
      5,
      [],
      { films: 'x', collections: {}, people: null },
    ]) {
      expect(sanitizeRaw(bad)).toEqual({ films: [], collections: [], people: [] });
    }
  });

  it('stores and shows only the rebuilt index when the server sends extra or malformed fields', async () => {
    const dirty = {
      ...RAW,
      films: [
        { ...RAW.films[0], onload: 'alert(1)' },
        { id: 'bad', t: 'x' },
      ],
      injected: { a: 1 },
    };
    queueFetch(() => json(dirty, E1));

    startSearchIndex();
    await waitFor(() => expect(getSnapshot().status).toBe('ready'));

    expect(getSnapshot().index.films).toHaveLength(1);
    expect(JSON.parse(storage.getItem(SEARCH_INDEX_STORAGE_KEY))).toEqual({
      etag: E1,
      raw: { ...RAW_CLEAN, films: [RAW_CLEAN.films[0]] },
    });
  });

  it('does not store or send back an ETag that is not one of ours', async () => {
    const fetchMock = queueFetch(
      () => json(RAW, '"not-ours"'),
      () => json(RAW_2, '<img src=x>'),
    );

    startSearchIndex();
    await waitFor(() => expect(getSnapshot().status).toBe('ready'));
    expect(storage.getItem(SEARCH_INDEX_STORAGE_KEY)).toBeNull();
    await act(async () => {
      await revalidate();
    });

    expect(sent(fetchMock, 1)).toBeUndefined();
    expect(storage.getItem(SEARCH_INDEX_STORAGE_KEY)).toBeNull();
    expect(getSnapshot().index.films).toHaveLength(1);
  });

  it('rebuilds a stored copy before using it: extra keys and wrong types do not get through', async () => {
    storage.setItem(
      SEARCH_INDEX_STORAGE_KEY,
      JSON.stringify({
        etag: E1,
        raw: {
          ...RAW,
          films: [
            { ...RAW.films[0], y: 'not a year', onload: 'alert(1)' },
            { id: [], t: 'bad' },
          ],
          extra: 1,
        },
      }),
    );
    const fetchMock = queueFetch(notModified);

    startSearchIndex();

    expect(getSnapshot().index.films).toEqual([
      expect.objectContaining({ id: 1, title: 'Warrior', year: null }),
    ]);
    expect(Object.keys(getSnapshot().index.films[0])).not.toContain('onload');
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
  });

  it('ignores a stored copy whose ETag is not one of ours, and does not send it', async () => {
    for (const etag of ['"x"', 'W/"zzzzzzzzzzzzzzzz"', 5, null, '<script>alert(1)</script>']) {
      storage.setItem(SEARCH_INDEX_STORAGE_KEY, JSON.stringify({ etag, raw: RAW }));
      const fetchMock = queueFetch(() => json(RAW, E1));

      startSearchIndex();

      expect(getSnapshot().status).toBe('loading');
      await waitFor(() => expect(getSnapshot().status).toBe('ready'));
      expect(sent(fetchMock, 0)).toBeUndefined();
      resetSearchIndexForTests();
    }
  });

  it('copes with a stored raw that is not an object', async () => {
    storage.setItem(SEARCH_INDEX_STORAGE_KEY, JSON.stringify({ etag: E1, raw: 'nonsense' }));
    queueFetch(notModified);

    startSearchIndex();

    expect(getSnapshot()).toEqual({
      index: { films: [], collections: [], people: [] },
      status: 'ready',
    });
  });
});
