import { StrictMode } from 'react';
import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LIBRARY_CHANGED_EVENT } from '../api/client.js';
import { installFetch, respond } from '../test/mockApi.jsx';
import { useFixMatch } from './useFixMatch.js';

afterEach(() => {
  vi.unstubAllGlobals();
});

const deferred = () => {
  let resolve;
  const promise = new Promise((res) => {
    resolve = res;
  });
  return { promise, resolve };
};

const isSearch = (m, url) => m === 'GET' && url.startsWith('/api/admin/films/7/match?');
const isApply = (m, url) => m === 'POST' && url === '/api/admin/films/7/match';

describe('useFixMatch', () => {
  it('searches on open with the title and keeps only the newest search when answers arrive out of order', async () => {
    const first = deferred();
    const second = deferred();
    const pending = [first, second];
    const fetchMock = installFetch((m, url) => (isSearch(m, url) ? pending.shift().promise : null));
    const { result } = renderHook(() => useFixMatch(7, 'Dune', vi.fn()));
    expect(fetchMock.mock.calls[0][0]).toBe('/api/admin/films/7/match?query=Dune');

    act(() => result.current.setQuery('Dune 2021'));
    act(() => result.current.setYear('2021'));
    act(() => {
      result.current.submit();
    });
    expect(fetchMock.mock.calls[1][0]).toBe('/api/admin/films/7/match?query=Dune+2021&year=2021');

    await act(async () => second.resolve(respond([{ tmdbId: 2 }])));
    await act(async () => first.resolve(respond([{ tmdbId: 1 }])));
    expect(result.current.search.results).toEqual([{ tmdbId: 2 }]);
  });

  it('ignores a failure of a search that is no longer the newest', async () => {
    const first = deferred();
    const second = deferred();
    const pending = [first, second];
    installFetch((m, url) => (isSearch(m, url) ? pending.shift().promise : null));
    const { result } = renderHook(() => useFixMatch(7, 'Dune', vi.fn()));
    act(() => {
      result.current.submit();
    });

    await act(async () => second.resolve(respond([])));
    await act(async () => first.resolve(respond({ error: 'x' }, 503)));
    expect(result.current.search.status).toBe('ready');
  });

  it('under StrictMode the replayed mount effect keeps the answer to the search that counts', async () => {
    // The first search of the replayed pair gets a 409 (as it once did from the library lock): it must not win.
    const answers = [respond({ error: 'A scan is running' }, 409), respond([{ tmdbId: 5 }])];
    const fetchMock = installFetch((m, url) => (isSearch(m, url) ? answers.shift() : null));
    const { result } = renderHook(() => useFixMatch(7, 'Dune', vi.fn()), { wrapper: StrictMode });

    await waitFor(() => expect(result.current.search.status).toBe('ready'));
    expect(result.current.search.results).toEqual([{ tmdbId: 5 }]);
    expect(fetchMock.mock.calls.filter((c) => c[0].includes('/match?'))).toHaveLength(2);
  });

  it('applies nothing until a result is picked, then posts it and reports the outcome', async () => {
    const fetchMock = installFetch((m, url) => {
      if (isSearch(m, url)) return respond([{ tmdbId: 5 }]);
      if (isApply(m, url)) return respond({ filmId: 7, joined: true });
      return null;
    });
    const onApplied = vi.fn();
    const { result } = renderHook(() => useFixMatch(7, 'Dune', onApplied));
    await waitFor(() => expect(result.current.search.status).toBe('ready'));

    await act(async () => result.current.apply());
    expect(fetchMock.mock.calls.some((c) => c[1]?.method === 'POST')).toBe(false);

    act(() => result.current.setPicked(5));
    await act(async () => result.current.apply());
    const post = fetchMock.mock.calls.find((c) => c[1]?.method === 'POST');
    expect(post[1].headers['X-CineFilm']).toBe('1');
    expect(JSON.parse(post[1].body)).toEqual({ tmdbId: 5 });
    expect(onApplied).toHaveBeenCalledWith({ filmId: 7, joined: true }, false);
  });

  it('shows the apply problem and does not report success when the apply fails', async () => {
    installFetch((m, url) => {
      if (isSearch(m, url)) return respond([{ tmdbId: 5 }]);
      if (isApply(m, url)) return respond({ error: 'A scan is running' }, 409);
      return null;
    });
    const onApplied = vi.fn();
    const { result } = renderHook(() => useFixMatch(7, 'Dune', onApplied));
    await waitFor(() => expect(result.current.search.status).toBe('ready'));
    act(() => result.current.setPicked(5));
    await act(async () => result.current.apply());

    expect(result.current.applyProblem).toBe('A scan is running. Try again when it finishes.');
    expect(onApplied).not.toHaveBeenCalled();
  });

  it('reports an apply that ends after the panel closed as stale, and one that ends after the film changed', async () => {
    const slow = deferred();
    installFetch((m, url) => {
      if (isSearch(m, url)) return respond([{ tmdbId: 5 }]);
      if (isApply(m, url)) return slow.promise;
      return null;
    });
    const onApplied = vi.fn();
    const { result, unmount } = renderHook(() => useFixMatch(7, 'Dune', onApplied));
    await waitFor(() => expect(result.current.search.status).toBe('ready'));
    act(() => result.current.setPicked(5));
    let finished;
    act(() => {
      finished = result.current.apply();
    });
    unmount();
    await act(async () => {
      slow.resolve(respond({ filmId: 7, joined: false }));
      await finished;
    });

    expect(onApplied).toHaveBeenCalledWith({ filmId: 7, joined: false }, true);
  });

  it('reports an apply for a film the hook has since moved off as stale', async () => {
    const slow = deferred();
    installFetch((m, url) => {
      if (isSearch(m, url)) return respond([{ tmdbId: 5 }]);
      if (isApply(m, url)) return slow.promise;
      return respond([]);
    });
    const onApplied = vi.fn();
    const { result, rerender } = renderHook(({ id }) => useFixMatch(id, 'Dune', onApplied), {
      initialProps: { id: 7 },
    });
    await waitFor(() => expect(result.current.search.status).toBe('ready'));
    act(() => result.current.setPicked(5));
    let finished;
    act(() => {
      finished = result.current.apply();
    });
    rerender({ id: 8 });
    await act(async () => {
      slow.resolve(respond({ filmId: 7, joined: false }));
      await finished;
    });

    expect(onApplied).toHaveBeenCalledWith({ filmId: 7, joined: false }, true);
  });

  it('announces a library change once a match was applied', async () => {
    installFetch((m, url) => {
      if (isSearch(m, url)) return respond([{ tmdbId: 5 }]);
      if (isApply(m, url)) return respond({ filmId: 7, joined: false });
      return null;
    });
    const changed = vi.fn();
    window.addEventListener(LIBRARY_CHANGED_EVENT, changed);
    const { result } = renderHook(() => useFixMatch(7, 'Dune', vi.fn()));
    await waitFor(() => expect(result.current.search.status).toBe('ready'));
    act(() => result.current.setPicked(5));

    await act(async () => result.current.apply());

    expect(changed).toHaveBeenCalledTimes(1);
    window.removeEventListener(LIBRARY_CHANGED_EVENT, changed);
  });
});
