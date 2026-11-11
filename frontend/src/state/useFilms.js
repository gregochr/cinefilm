import { useCallback, useState } from 'react';
import { editionsApi, filmsApi } from '../api/resources.js';
import { useResource } from './useResource.js';

const REVALIDATE = { cache: 'revalidate' };
/** The Player's film: one Film detail loaded under a minute ago is used as it is, with no second request. */
const PLAY_FRESH = { cache: 'fresh', maxAgeMs: 60_000 };

/** The library list. `params`: { q, sort, genre, decade, collection, show }. */
export function useFilms(params) {
  const key = `films:${JSON.stringify(params)}`;
  return useResource(key, (options) => filmsApi.list(params, options), REVALIDATE);
}

export function useFilm(id) {
  return useResource(`film:${id}`, (options) => filmsApi.get(id, options), REVALIDATE);
}

/** The film the Player starts: the same entry as `useFilm`, but never shown older than a minute and never refetched under it. */
export function useFilmToPlay(id) {
  return useResource(`film:${id}`, (options) => filmsApi.get(id, options), PLAY_FRESH);
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
      [editionId]: { watched, positionMs: 0 },
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
