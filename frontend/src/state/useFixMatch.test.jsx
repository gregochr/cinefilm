import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client.js';
import { adminApi } from '../api/resources.js';
import { useFixMatch } from './useFixMatch.js';

afterEach(() => {
  vi.restoreAllMocks();
});

const deferred = () => {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
};

describe('useFixMatch', () => {
  it('searches on open with the title and keeps only the newest search when answers arrive out of order', async () => {
    const first = deferred();
    const second = deferred();
    const search = vi
      .spyOn(adminApi, 'matchSearch')
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise);
    const { result } = renderHook(() => useFixMatch(7, 'Dune', vi.fn()));
    expect(search).toHaveBeenCalledWith(7, { query: 'Dune', year: null });

    act(() => result.current.setQuery('Dune 2021'));
    act(() => result.current.setYear('2021'));
    act(() => {
      result.current.submit();
    });
    expect(search).toHaveBeenLastCalledWith(7, { query: 'Dune 2021', year: 2021 });

    await act(async () => second.resolve([{ tmdbId: 2 }]));
    await act(async () => first.resolve([{ tmdbId: 1 }]));
    expect(result.current.search.results).toEqual([{ tmdbId: 2 }]);
  });

  it('ignores a failure of a search that is no longer the newest', async () => {
    const first = deferred();
    const second = deferred();
    vi.spyOn(adminApi, 'matchSearch')
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise);
    const { result } = renderHook(() => useFixMatch(7, 'Dune', vi.fn()));
    act(() => {
      result.current.submit();
    });

    await act(async () => second.resolve([]));
    await act(async () => first.reject(new ApiError(503)));
    expect(result.current.search.status).toBe('ready');
  });

  it('applies nothing until a result is picked, then applies it and reports the outcome', async () => {
    vi.spyOn(adminApi, 'matchSearch').mockResolvedValue([{ tmdbId: 5 }]);
    const apply = vi.spyOn(adminApi, 'applyMatch').mockResolvedValue({ filmId: 7, joined: true });
    const onApplied = vi.fn();
    const { result } = renderHook(() => useFixMatch(7, 'Dune', onApplied));
    await waitFor(() => expect(result.current.search.status).toBe('ready'));

    await act(async () => result.current.apply());
    expect(apply).not.toHaveBeenCalled();

    act(() => result.current.setPicked(5));
    await act(async () => result.current.apply());
    expect(apply).toHaveBeenCalledWith(7, 5);
    expect(onApplied).toHaveBeenCalledWith({ filmId: 7, joined: true });
  });
});
