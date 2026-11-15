import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { SIGNED_IN_EVENT } from '../api/client.js';
import { fixtures, installFetch, respond } from '../test/mockApi.jsx';
import { usePlaybackLog } from './usePlaybackLog.js';

afterEach(() => vi.unstubAllGlobals());

const BASE = '/api/admin/playback-health';
const ALL_0 = `${BASE}?page=0&size=50`;
const ALL_1 = `${BASE}?page=1&size=50`;
const FILMS_0 = `${BASE}?minWatchedMs=180000&page=0&size=50`;
const PROBLEMS_0 = `${BASE}?problems=true&page=0&size=50`;

/** `count` API-shaped rows with ids `${prefix}-n`, as one answer of the list route. */
function answer(prefix, count, total) {
  const playbacks = Array.from({ length: count }, (_, n) => ({
    ...structuredClone(fixtures.adminPlaybackHealth.playbacks[0]),
    playbackId: `${prefix}-${n}`,
  }));
  return { playbacks, total };
}

/** A request the test answers when it chooses. */
function held() {
  let resolve;
  const promise = new Promise((done) => {
    resolve = (body) => done(respond(body));
  });
  return { promise, resolve };
}

describe('usePlaybackLog', () => {
  it('reads the first page of All and is loading until it arrives', async () => {
    const first = held();
    const fetchMock = installFetch((m, url) => (url === ALL_0 ? first.promise : null));
    const { result } = renderHook(() => usePlaybackLog());
    expect(result.current).toMatchObject({
      rows: null,
      loading: true,
      error: false,
      filter: 'all',
    });
    first.resolve(answer('a', 2, 2));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.rows.map((r) => r.playbackId)).toEqual(['a-0', 'a-1']);
    expect(result.current).toMatchObject({ total: 2, hasMore: false });
    expect(fetchMock.mock.calls.map((c) => c[0])).toEqual([ALL_0]);
  });

  it('sends each filter to the server and shows what the server answers, never filtering itself', async () => {
    const fetchMock = installFetch((m, url) => {
      if (url === ALL_0) return respond(answer('all', 3, 3));
      if (url === FILMS_0) return respond(answer('films', 1, 1));
      if (url === PROBLEMS_0) return respond(answer('problems', 2, 2));
      return null;
    });
    const { result } = renderHook(() => usePlaybackLog());
    await waitFor(() => expect(result.current.rows).toHaveLength(3));

    act(() => result.current.setFilter('films'));
    expect(result.current).toMatchObject({ filter: 'films', rows: null, loading: true });
    await waitFor(() => expect(result.current.rows).toHaveLength(1));
    // The answer holds a row under three minutes watched: it is shown, because the server chose it.
    expect(result.current.rows[0].report.watchedMs).toBeLessThan(180_000);

    act(() => result.current.setFilter('problems'));
    await waitFor(() => expect(result.current.rows).toHaveLength(2));
    expect(fetchMock.mock.calls.map((c) => c[0])).toEqual([ALL_0, FILMS_0, PROBLEMS_0]);
  });

  it('ignores the older filter’s late answer and aborts its request', async () => {
    const all = held();
    const films = held();
    const fetchMock = installFetch((m, url) => {
      if (url === ALL_0) return all.promise;
      if (url === FILMS_0) return films.promise;
      return null;
    });
    const { result } = renderHook(() => usePlaybackLog());
    act(() => result.current.setFilter('films'));
    expect(fetchMock.mock.calls[0][1].signal.aborted).toBe(true);
    films.resolve(answer('films', 1, 1));
    await waitFor(() => expect(result.current.rows?.[0].playbackId).toBe('films-0'));
    await act(async () => {
      all.resolve(answer('all', 5, 5));
    });
    expect(result.current.rows.map((r) => r.playbackId)).toEqual(['films-0']);
    expect(result.current.total).toBe(1);
  });

  it('appends the next page on more, and stops offering more at the total', async () => {
    const fetchMock = installFetch((m, url) => {
      if (url === ALL_0) return respond(answer('p0', 50, 60));
      if (url === ALL_1) return respond(answer('p1', 10, 60));
      return null;
    });
    const { result } = renderHook(() => usePlaybackLog());
    await waitFor(() => expect(result.current.rows).toHaveLength(50));
    expect(result.current.hasMore).toBe(true);

    act(() => result.current.more());
    expect(result.current.loadingMore).toBe(true);
    await waitFor(() => expect(result.current.rows).toHaveLength(60));
    expect(result.current.rows[50].playbackId).toBe('p1-0');
    expect(result.current).toMatchObject({ hasMore: false, loadingMore: false, total: 60 });
    expect(fetchMock.mock.calls.map((c) => c[0])).toEqual([ALL_0, ALL_1]);
  });

  it('does not start a second page read while one is on its way', async () => {
    const second = held();
    const fetchMock = installFetch((m, url) => {
      if (url === ALL_0) return respond(answer('p0', 50, 100));
      if (url === ALL_1) return second.promise;
      return null;
    });
    const { result } = renderHook(() => usePlaybackLog());
    await waitFor(() => expect(result.current.rows).toHaveLength(50));
    act(() => result.current.more());
    act(() => result.current.more());
    expect(fetchMock.mock.calls.map((c) => c[0])).toEqual([ALL_0, ALL_1]);
    second.resolve(answer('p1', 50, 100));
    await waitFor(() => expect(result.current.rows).toHaveLength(100));
  });

  it('does not add a row twice when a play that started meanwhile pushed it onto the next page', async () => {
    const shifted = answer('p1', 3, 53);
    shifted.playbacks[0].playbackId = 'p0-49';
    installFetch((m, url) => {
      if (url === ALL_0) return respond(answer('p0', 50, 52));
      if (url === ALL_1) return respond(shifted);
      return null;
    });
    const { result } = renderHook(() => usePlaybackLog());
    await waitFor(() => expect(result.current.rows).toHaveLength(50));
    act(() => result.current.more());
    await waitFor(() => expect(result.current.rows).toHaveLength(52));
    expect(new Set(result.current.rows.map((r) => r.playbackId)).size).toBe(52);
    // total (53) is above the 52 rows that can be reached: the short page says there is no more.
    expect(result.current).toMatchObject({ total: 53, hasMore: false });
  });

  it('keeps offering more after a short page and reads the next one: an unreadable report leaves a row out', async () => {
    const fetchMock = installFetch((m, url) => {
      if (url === ALL_0) return respond(answer('p0', 49, 120));
      if (url === ALL_1) return respond(answer('p1', 50, 120));
      return null;
    });
    const { result } = renderHook(() => usePlaybackLog());
    await waitFor(() => expect(result.current.rows).toHaveLength(49));
    expect(result.current.hasMore).toBe(true);
    act(() => result.current.more());
    await waitFor(() => expect(result.current.rows).toHaveLength(99));
    expect(fetchMock.mock.calls.map((c) => c[0])).toEqual([ALL_0, ALL_1]);
    expect(result.current.hasMore).toBe(true);
  });

  it('stops once the pages read cover the total, whatever number of rows they held', async () => {
    installFetch((m, url) => {
      if (url === ALL_0) return respond(answer('p0', 50, 100));
      if (url === ALL_1) return respond(answer('p1', 49, 100));
      return null;
    });
    const { result } = renderHook(() => usePlaybackLog());
    await waitFor(() => expect(result.current.rows).toHaveLength(50));
    expect(result.current.hasMore).toBe(true);
    act(() => result.current.more());
    await waitFor(() => expect(result.current.rows).toHaveLength(99));
    expect(result.current).toMatchObject({ total: 100, hasMore: false });
  });

  it('starts again from the first page on reload, with the pages read forgotten', async () => {
    const fetchMock = installFetch((m, url) => {
      if (url === ALL_0) return respond(answer('p0', 50, 100));
      if (url === ALL_1) return respond(answer('p1', 50, 100));
      return null;
    });
    const { result } = renderHook(() => usePlaybackLog());
    await waitFor(() => expect(result.current.rows).toHaveLength(50));
    act(() => result.current.more());
    await waitFor(() => expect(result.current.hasMore).toBe(false));
    act(() => result.current.reload());
    await waitFor(() => expect(result.current.rows).toHaveLength(50));
    expect(result.current.hasMore).toBe(true);
    expect(fetchMock.mock.calls.map((c) => c[0])).toEqual([ALL_0, ALL_1, ALL_0]);
  });

  it('offers more again after a filter switch that follows a log that was all read', async () => {
    installFetch((m, url) => {
      if (url === ALL_0) return respond(answer('a', 3, 9));
      if (url === FILMS_0) return respond(answer('f', 50, 80));
      return null;
    });
    const { result } = renderHook(() => usePlaybackLog());
    await waitFor(() => expect(result.current.rows).toHaveLength(3));
    expect(result.current.hasMore).toBe(false);
    act(() => result.current.setFilter('films'));
    await waitFor(() => expect(result.current.rows).toHaveLength(50));
    expect(result.current.hasMore).toBe(true);
  });

  it('keeps the rows and reports moreError when the next page fails, then reads that page again', async () => {
    let fail = true;
    const fetchMock = installFetch((m, url) => {
      if (url === ALL_0) return respond(answer('p0', 50, 60));
      if (url === ALL_1) return fail ? respond(null, 500) : respond(answer('p1', 10, 60));
      return null;
    });
    const { result } = renderHook(() => usePlaybackLog());
    await waitFor(() => expect(result.current.rows).toHaveLength(50));
    act(() => result.current.more());
    await waitFor(() => expect(result.current.moreError).toBe(true));
    expect(result.current).toMatchObject({ loadingMore: false, error: false });
    expect(result.current.rows).toHaveLength(50);
    // The failed read is not a page read: more is still on offer.
    expect(result.current.hasMore).toBe(true);

    fail = false;
    act(() => result.current.more());
    expect(result.current.moreError).toBe(false);
    await waitFor(() => expect(result.current.rows).toHaveLength(60));
    expect(fetchMock.mock.calls.map((c) => c[0])).toEqual([ALL_0, ALL_1, ALL_1]);
  });

  it('reports a failure of the first page with no rows, and reload reads it again', async () => {
    let fail = true;
    installFetch((m, url) =>
      url === ALL_0 ? (fail ? respond(null, 500) : respond(answer('a', 1, 1))) : null,
    );
    const { result } = renderHook(() => usePlaybackLog());
    await waitFor(() => expect(result.current.error).toBe(true));
    expect(result.current).toMatchObject({ rows: null, loading: false });

    fail = false;
    act(() => result.current.reload());
    expect(result.current).toMatchObject({ error: false, loading: true });
    await waitFor(() => expect(result.current.rows).toHaveLength(1));
  });

  it('reads an empty log as no rows and nothing more', async () => {
    installFetch((m, url) => (url === ALL_0 ? respond({ playbacks: [], total: 0 }) : null));
    const { result } = renderHook(() => usePlaybackLog());
    await waitFor(() => expect(result.current.rows).toEqual([]));
    expect(result.current).toMatchObject({ total: 0, hasMore: false, loading: false });
  });

  it('reads the first page again after a sign-in that follows a 401, for the selected filter', async () => {
    let expired = true;
    const fetchMock = installFetch((m, url) => {
      if (url === ALL_0) return respond(answer('a', 1, 1));
      if (url === FILMS_0) return expired ? respond(null, 401) : respond(answer('f', 2, 2));
      return null;
    });
    const { result } = renderHook(() => usePlaybackLog());
    await waitFor(() => expect(result.current.rows).toHaveLength(1));
    act(() => result.current.setFilter('films'));
    await waitFor(() => expect(result.current.error).toBe(true));

    expired = false;
    act(() => {
      window.dispatchEvent(new CustomEvent(SIGNED_IN_EVENT));
    });
    expect(result.current).toMatchObject({ error: false, loading: true, filter: 'films' });
    await waitFor(() => expect(result.current.rows).toHaveLength(2));
    expect(result.current.rows[0].playbackId).toBe('f-0');
    expect(fetchMock.mock.calls.map((c) => c[0])).toEqual([ALL_0, FILMS_0, FILMS_0]);
  });

  it('no longer reads on a sign-in once unmounted', async () => {
    const fetchMock = installFetch((m, url) => (url === ALL_0 ? respond(answer('a', 1, 1)) : null));
    const { result, unmount } = renderHook(() => usePlaybackLog());
    await waitFor(() => expect(result.current.rows).toHaveLength(1));
    unmount();
    window.dispatchEvent(new CustomEvent(SIGNED_IN_EVENT));
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('aborts the request that is still open when it unmounts', () => {
    const fetchMock = installFetch(() => new Promise(() => {}));
    const { unmount } = renderHook(() => usePlaybackLog());
    const { signal } = fetchMock.mock.calls[0][1];
    expect(signal.aborted).toBe(false);
    unmount();
    expect(signal.aborted).toBe(true);
  });
});
