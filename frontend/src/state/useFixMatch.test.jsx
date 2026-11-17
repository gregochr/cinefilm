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

const answer = (results, extra = {}) =>
  respond({
    fileRuntimeMs: null,
    suggestedTmdbId: null,
    suggestedBecause: null,
    results,
    ...extra,
  });

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

    await act(async () => second.resolve(answer([{ tmdbId: 2 }])));
    await act(async () => first.resolve(answer([{ tmdbId: 1 }])));
    expect(result.current.search.results).toEqual([{ tmdbId: 2 }]);
  });

  it('picks the suggested result when the answer arrives, and the person can pick another', async () => {
    installFetch((m, url) =>
      isSearch(m, url)
        ? answer([{ tmdbId: 1 }, { tmdbId: 2 }], {
            fileRuntimeMs: 6420000,
            suggestedTmdbId: 2,
            suggestedBecause: 'runtime',
          })
        : null,
    );
    const { result } = renderHook(() => useFixMatch(7, 'Dune', vi.fn()));
    expect(result.current.picked).toBeNull();
    await waitFor(() => expect(result.current.search.status).toBe('ready'));

    expect(result.current.picked).toBe(2);
    expect(result.current.search.fileRuntimeMs).toBe(6420000);
    expect(result.current.search.suggestedTmdbId).toBe(2);
    expect(result.current.search.suggestedBecause).toBe('runtime');
    act(() => result.current.setPicked(1));
    expect(result.current.picked).toBe(1);
  });

  it('picks nothing when the answer has no suggestion', async () => {
    installFetch((m, url) => (isSearch(m, url) ? answer([{ tmdbId: 1 }]) : null));
    const { result } = renderHook(() => useFixMatch(7, 'Dune', vi.fn()));
    await waitFor(() => expect(result.current.search.status).toBe('ready'));

    expect(result.current.picked).toBeNull();
    expect(result.current.search.suggestedTmdbId).toBeNull();
  });

  it('a newer search replaces the default, and clears the pick while it is in flight', async () => {
    const second = deferred();
    const pending = [
      Promise.resolve(answer([{ tmdbId: 1 }, { tmdbId: 2 }], { suggestedTmdbId: 1 })),
      second.promise,
    ];
    installFetch((m, url) => (isSearch(m, url) ? pending.shift() : null));
    const { result } = renderHook(() => useFixMatch(7, 'Dune', vi.fn()));
    await waitFor(() => expect(result.current.picked).toBe(1));

    act(() => {
      result.current.submit();
    });
    expect(result.current.picked).toBeNull();
    await act(async () => second.resolve(answer([{ tmdbId: 3 }], { suggestedTmdbId: 3 })));

    expect(result.current.picked).toBe(3);
  });

  it('a stale answer never sets the pick', async () => {
    const first = deferred();
    const second = deferred();
    const pending = [first, second];
    installFetch((m, url) => (isSearch(m, url) ? pending.shift().promise : null));
    const { result } = renderHook(() => useFixMatch(7, 'Dune', vi.fn()));
    act(() => {
      result.current.submit();
    });

    await act(async () => second.resolve(answer([{ tmdbId: 2 }])));
    await act(async () => first.resolve(answer([{ tmdbId: 1 }], { suggestedTmdbId: 1 })));

    expect(result.current.search.results).toEqual([{ tmdbId: 2 }]);
    expect(result.current.picked).toBeNull();
  });

  it('keeps the file length through a new search and a failed one', async () => {
    const second = deferred();
    const pending = [
      Promise.resolve(answer([{ tmdbId: 1 }], { fileRuntimeMs: 6420000, suggestedTmdbId: 1 })),
      second.promise,
      Promise.resolve(respond({ error: 'x' }, 503)),
    ];
    installFetch((m, url) => (isSearch(m, url) ? pending.shift() : null));
    const { result } = renderHook(() => useFixMatch(7, 'Dune', vi.fn()));
    await waitFor(() => expect(result.current.search.fileRuntimeMs).toBe(6420000));

    act(() => {
      result.current.submit();
    });
    expect(result.current.search.status).toBe('loading');
    expect(result.current.search.fileRuntimeMs).toBe(6420000);
    expect(result.current.search.suggestedTmdbId).toBeNull();
    expect(result.current.picked).toBeNull();
    await act(async () => second.resolve(answer([{ tmdbId: 2 }], { fileRuntimeMs: 6480000 })));
    expect(result.current.search.fileRuntimeMs).toBe(6480000);

    act(() => {
      result.current.submit();
    });
    await waitFor(() => expect(result.current.search.status).toBe('error'));
    expect(result.current.search.fileRuntimeMs).toBe(6480000);
    expect(result.current.search.results).toEqual([]);
  });

  it('does not carry the file length over to another film', async () => {
    const eight = deferred();
    installFetch((m, url) => {
      if (isSearch(m, url)) return answer([{ tmdbId: 1 }], { fileRuntimeMs: 6420000 });
      if (m === 'GET' && url.startsWith('/api/admin/films/8/match?')) return eight.promise;
      return null;
    });
    const { result, rerender } = renderHook(({ id }) => useFixMatch(id, 'Dune', vi.fn()), {
      initialProps: { id: 7 },
    });
    await waitFor(() => expect(result.current.search.fileRuntimeMs).toBe(6420000));

    rerender({ id: 8 });

    expect(result.current.search.status).toBe('loading');
    expect(result.current.search.fileRuntimeMs).toBeNull();
    await act(async () => eight.resolve(answer([{ tmdbId: 9 }], { fileRuntimeMs: 7200000 })));
    expect(result.current.search.fileRuntimeMs).toBe(7200000);
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

    await act(async () => second.resolve(answer([])));
    await act(async () => first.resolve(respond({ error: 'x' }, 503)));
    expect(result.current.search.status).toBe('ready');
  });

  it('under StrictMode the replayed mount effect keeps the answer to the search that counts', async () => {
    // The first search of the replayed pair gets a 409 (as it once did from the library lock): it must not win.
    const answers = [respond(null, 409), answer([{ tmdbId: 5 }])];
    const fetchMock = installFetch((m, url) => (isSearch(m, url) ? answers.shift() : null));
    const { result } = renderHook(() => useFixMatch(7, 'Dune', vi.fn()), { wrapper: StrictMode });

    await waitFor(() => expect(result.current.search.status).toBe('ready'));
    expect(result.current.search.results).toEqual([{ tmdbId: 5 }]);
    expect(fetchMock.mock.calls.filter((c) => c[0].includes('/match?'))).toHaveLength(2);
  });

  it('applies nothing until a result is picked, then posts it and reports the outcome', async () => {
    const fetchMock = installFetch((m, url) => {
      if (isSearch(m, url)) return answer([{ tmdbId: 5 }]);
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
      if (isSearch(m, url)) return answer([{ tmdbId: 5 }]);
      if (isApply(m, url)) return respond(null, 409);
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
      if (isSearch(m, url)) return answer([{ tmdbId: 5 }]);
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
      if (isSearch(m, url)) return answer([{ tmdbId: 5 }]);
      if (isApply(m, url)) return slow.promise;
      return answer([]);
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
      if (isSearch(m, url)) return answer([{ tmdbId: 5 }]);
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
