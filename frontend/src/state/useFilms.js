import { useCallback, useState } from 'react';
import { editionsApi, filmsApi } from '../api/resources.js';
import { useResource } from './useResource.js';

/** The library list. `params`: { q, sort, genre, decade, collection, show }. */
export function useFilms(params) {
  const key = `films:${JSON.stringify(params)}`;
  return useResource(key, (options) => filmsApi.list(params, options));
}

export function useFilm(id) {
  return useResource(`film:${id}`, (options) => filmsApi.get(id, options));
}

/**
 * Local overrides for per-edition state (watched / position) after the user marks something
 * watched on the detail page, plus a saver for the remembered preferences.
 */
export function useFilmActions(filmId) {
  const [overrides, setOverrides] = useState({});

  const setWatched = useCallback(async (editionId, watched) => {
    setOverrides((o) => ({
      ...o,
      [editionId]: { watched, ...(watched ? {} : { positionMs: 0 }) },
    }));
    try {
      await editionsApi.setWatched(editionId, watched);
    } catch {
      setOverrides((o) => {
        const next = { ...o };
        delete next[editionId];
        return next;
      });
    }
  }, []);

  const savePreferences = useCallback(
    (preferences) => filmsApi.savePreferences(filmId, preferences).catch(() => null),
    [filmId],
  );

  /** Forget the local overrides: the server's state is the truth again (after Edit editions changed it). */
  const clearOverrides = useCallback(() => setOverrides({}), []);

  return { overrides, setWatched, savePreferences, clearOverrides };
}
