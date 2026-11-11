import { useCallback, useEffect, useRef, useState } from 'react';
import { editionsApi } from '../api/resources.js';

/** How long the Undo pill stays up; the action is already committed by then. */
export const UNDO_MS = 8000;

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
 * and a single undoable action (the latest). The API is called straight away; Undo reverses it.
 * `reload` is called if an Undo cannot be sent, to resynchronise with the server. `onRollback(film)` is
 * called after a failed request has put its film back (not when something newer has happened to it).
 * Requests for one edition are serialised (each starts after the previous one settled), so a quick
 * Remove, Undo, Remove reaches the server in that order. `watched` holds local watched overrides by film
 * id (set while a Mark as watched stands) for the other Home rows to apply. The screen shows the first few
 * of `row`, so the next film backfills a removal and an Undo pushes it out again with no extra state;
 * `total` counts every film in progress (the hero included), not just those in `row`.
 */
export function useContinueWatching(data, reload, onRollback) {
  const [source, setSource] = useState(data);
  const [list, setList] = useState(() => seed(data));
  const { row, total } = list;
  const [hero, setHero] = useState(data?.hero ?? null);
  const [action, setAction] = useState(null);
  const [watched, setWatched] = useState({});
  const current = useRef(null);
  const timer = useRef(null);
  const chains = useRef(new Map());
  const rolledBack = useRef(onRollback);
  const versions = useRef(new Map()); // film id -> bumped by every action and Undo on it

  if (source !== data) {
    setSource(data);
    setList(seed(data));
    setHero(data?.hero ?? null);
    setWatched({});
  }

  useEffect(() => {
    rolledBack.current = onRollback;
  });
  useEffect(() => () => clearTimeout(timer.current), []);

  const enqueue = useCallback((editionId, run) => {
    const tail = chains.current.get(editionId) ?? Promise.resolve();
    const result = tail.then(run);
    chains.current.set(
      editionId,
      result.catch(() => {}),
    );
    return result;
  }, []);

  const dropOverride = useCallback((filmId) => {
    setWatched((w) => {
      const next = { ...w };
      delete next[filmId];
      return next;
    });
  }, []);

  const bump = useCallback((filmId) => {
      const next = (versions.current.get(filmId) ?? 0) + 1;
      versions.current.set(filmId, next);
      return next;
    }, []),
    clearAction = useCallback((entry) => {
      if (current.current !== entry) return;
      current.current = null;
      clearTimeout(timer.current);
      setAction(null);
    }, []);

  const begin = useCallback(
    (kind, film, call) => {
      const index = row.findIndex((f) => f.id === film.id);
      const editionId = film.progress?.editionId;
      if (index < 0 || !editionId) return;
      const entry = {
        kind,
        film,
        index,
        editionId,
        heroWas: hero?.id === film.id ? hero : null,
        version: bump(film.id),
      };
      setList((l) => withoutFilm(l, film));
      if (entry.heroWas) setHero(null);
      if (kind === 'watched') setWatched((w) => ({ ...w, [film.id]: true }));
      entry.call = enqueue(editionId, () => call(editionId));
      entry.call.catch(() => {
        clearAction(entry);
        // Only roll back if nothing newer has happened to this film since (Undo, another action).
        if (versions.current.get(film.id) !== entry.version) return;
        if (kind === 'watched') dropOverride(film.id);
        setList((l) => withFilmAt(l, film, index));
        if (entry.heroWas) setHero(entry.heroWas);
        rolledBack.current?.(film);
      });
      current.current = entry;
      setAction(entry);
      clearTimeout(timer.current);
      timer.current = setTimeout(() => clearAction(entry), UNDO_MS);
    },
    [row, hero, clearAction, enqueue, dropOverride, bump],
  );

  const remove = useCallback(
    (film) => begin('remove', film, (id) => editionsApi.setHiddenFromContinueWatching(id, true)),
    [begin],
  );
  const markWatched = useCallback(
    (film) => begin('watched', film, (id) => editionsApi.setWatched(id, true)),
    [begin],
  );

  const undo = useCallback(async () => {
    const entry = current.current;
    if (!entry) return;
    clearAction(entry);
    bump(entry.film.id);
    setList((l) => withFilmAt(l, entry.film, entry.index));
    if (entry.heroWas) setHero(entry.heroWas);
    if (entry.kind === 'watched') dropOverride(entry.film.id); // back to what was loaded
    try {
      await enqueue(entry.editionId, async () => {
        let result;
        try {
          result = await entry.call;
        } catch {
          return; // the original failed and has already been rolled back
        }
        if (entry.kind === 'remove') {
          await editionsApi.setHiddenFromContinueWatching(entry.editionId, false);
        } else {
          await editionsApi.restoreState(entry.editionId, result.previous);
        }
      });
    } catch {
      reload();
    }
  }, [clearAction, reload, enqueue, dropOverride, bump]);

  return { hero, row, total, action, watched, remove, markWatched, undo };
}
