import { useCallback, useState } from 'react';
import { UNDO_MS, useUndoableFilmActions } from './useUndoableFilmActions.js';

export { UNDO_MS };

/** The list of film cards and the server's total, seeded from the Home payload. */
const seed = (data) => {
  const row = data?.continueWatching ?? [];
  return { row, total: data?.continueWatchingTotal ?? row.length };
};

/** Takes a film out of the list and the total (a film that is not in the list changes nothing). */
function withoutFilm(list, film) {
  if (!list.row.some((f) => f.id === film.id)) return list;
  return { row: list.row.filter((f) => f.id !== film.id), total: list.total - 1 };
}

/** Puts a film back at its index and in the total (a film that is already in the list changes nothing). */
function withFilmAt(list, film, index) {
  if (list.row.some((f) => f.id === film.id)) return list;
  const row = [...list.row];
  row.splice(Math.min(Math.max(index, 0), row.length), 0, film);
  return { row, total: list.total + 1 };
}

/**
 * The Continue watching list (up to 12 films after the hero), its total and the hero over the Home payload, with optimistic Remove / Mark as watched
 * and a single undoable action (the latest), by way of `useUndoableFilmActions` (which holds the request
 * ordering, the rollback and the Undo timer). `reload` is called if an Undo cannot be sent, to resynchronise with
 * the server. `onRollback(film)` is called after a failed request has put its film back (not when something newer
 * has happened to it). `watched` holds local watched overrides by film id (set while a Mark as watched stands) for
 * the other Home rows to apply. The screen shows the first few of `row`, so the next film backfills a removal and
 * an Undo pushes it out again with no extra state; `total` counts every film in progress (the hero included), not
 * just those in `row`.
 */
export function useContinueWatching(data, reload, onRollback) {
  const [source, setSource] = useState(data);
  const [list, setList] = useState(() => seed(data));
  const { row, total } = list;
  const [hero, setHero] = useState(data?.hero ?? null);
  const [watched, setWatched] = useState({});
  const { action, begin: start, undo } = useUndoableFilmActions(reload, onRollback);

  if (source !== data) {
    setSource(data);
    setList(seed(data));
    setHero(data?.hero ?? null);
    setWatched({});
  }

  const dropOverride = useCallback((filmId) => {
    setWatched((w) => {
      const next = { ...w };
      delete next[filmId];
      return next;
    });
  }, []);

  const begin = useCallback(
    (kind, film) => {
      const index = row.findIndex((f) => f.id === film.id);
      if (index < 0) return;
      const heroWas = hero?.id === film.id ? hero : null;
      start(
        kind,
        film,
        () => {
          setList((l) => withoutFilm(l, film));
          if (heroWas) setHero(null);
          if (kind === 'watched') setWatched((w) => ({ ...w, [film.id]: true }));
        },
        () => {
          setList((l) => withFilmAt(l, film, index));
          if (heroWas) setHero(heroWas);
          if (kind === 'watched') dropOverride(film.id); // back to what was loaded
        },
        { index, heroWas },
      );
    },
    [row, hero, start, dropOverride],
  );

  const remove = useCallback((film) => begin('remove', film), [begin]);
  const markWatched = useCallback((film) => begin('watched', film), [begin]);

  return { hero, row, total, action, watched, remove, markWatched, undo };
}
