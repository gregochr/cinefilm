import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, respond } from '../test/mockApi.jsx';
import { useFilmActions } from './useFilms.js';
import { useCollection, usePerson } from './usePeopleAndCollections.js';

afterEach(() => vi.unstubAllGlobals());

describe('useFilmActions', () => {
  it('marking watched overrides immediately and tells the API', async () => {
    const fetchMock = installFetch();
    const { result } = renderHook(() => useFilmActions(1));
    await act(async () => {
      await result.current.setWatched(11, true);
    });
    expect(result.current.overrides[11]).toEqual({ watched: true, positionMs: 0 });
    const post = fetchMock.mock.calls.find((c) => c[0] === '/api/editions/11/watched');
    expect(JSON.parse(post[1].body)).toEqual({ watched: true });
  });

  it('marking unwatched also resets the position to the start', async () => {
    installFetch();
    const { result } = renderHook(() => useFilmActions(1));
    await act(async () => {
      await result.current.setWatched(11, false);
    });
    expect(result.current.overrides[11]).toEqual({ watched: false, positionMs: 0 });
  });

  it('rolls the override back when the API call fails, leaving other editions alone', async () => {
    installFetch((method, url) => (url.endsWith('/12/watched') ? respond(null, 500) : null));
    const { result } = renderHook(() => useFilmActions(1));
    await act(async () => {
      await result.current.setWatched(11, true);
    });
    await act(async () => {
      await result.current.setWatched(12, true);
    });
    expect(result.current.overrides[12]).toBeUndefined();
    expect(result.current.overrides[11]).toEqual({ watched: true, positionMs: 0 });
  });

  it('saves preferences for its film with PUT', async () => {
    const fetchMock = installFetch();
    const { result } = renderHook(() => useFilmActions(6));
    await act(async () => {
      await result.current.savePreferences({ quality: 'original' });
    });
    const put = fetchMock.mock.calls.find((c) => c[1]?.method === 'PUT');
    expect(put[0]).toBe('/api/films/6/preferences');
    expect(JSON.parse(put[1].body)).toEqual({ quality: 'original' });
  });

  it('a failed preference save resolves to null instead of throwing', async () => {
    installFetch(() => respond(null, 500));
    const { result } = renderHook(() => useFilmActions(6));
    let outcome;
    await act(async () => {
      outcome = await result.current.savePreferences({ quality: 'original' });
    });
    expect(outcome).toBeNull();
  });
});

describe('people and collections hooks', () => {
  it('usePerson loads a person by id', async () => {
    const fetchMock = installFetch();
    const { result } = renderHook(() => usePerson(11));
    await waitFor(() => expect(result.current.data).toEqual(fixtures.person));
    expect(fetchMock.mock.calls[0][0]).toBe('/api/people/11');
  });

  it('useCollection loads a collection by id', async () => {
    const fetchMock = installFetch();
    const { result } = renderHook(() => useCollection(1));
    await waitFor(() => expect(result.current.data).toEqual(fixtures.collection));
    expect(fetchMock.mock.calls[0][0]).toBe('/api/collections/1');
  });
});
