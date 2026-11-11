import { useCallback, useEffect, useRef, useState } from 'react';
import { useUndoableFilmActions } from './useUndoableFilmActions.js';

/** What a Mark as watched does to a card: it leaves the In progress list, or stays as a watched film. */
const WATCHED_GONE = { gone: true };
const WATCHED_STAYS = { inProgress: false, watched: true };

/** The patches for one film without the given keys (an empty patch is dropped, so the card is the loaded one). */
function without(patches, filmId, keys) {
  const patch = { ...patches[filmId] };
  keys.forEach((key) => delete patch[key]);
  const next = { ...patches };
  if (Object.keys(patch).length) next[filmId] = patch;
  else delete next[filmId];
  return next;
}

/**
 * The Library Films list over the loaded `data` ({ films, total }) with optimistic Remove from Continue watching and
 * Mark as watched on in-progress films, by way of `useUndoableFilmActions`. Local changes are patches over the
 * loaded cards, so reversing one restores the card exactly (same place, same fields), and they are dropped when new
 * data arrives. Remove leaves the poster in place (the film is still in progress) and only flags it as hidden.
 * Mark as watched takes the poster out of the list and the total under `show === 'in-progress'`, and elsewhere keeps
 * it as a watched film without the bar.
 */
export function useLibraryFilmActions(data, show, reload, onRollback, loading = false) {
  const [source, setSource] = useState(data);
  const [patches, setPatches] = useState({});
  const { action, begin, undo: undoAction } = useUndoableFilmActions(reload, onRollback);
  const current = useRef(data);
  const reading = useRef(loading); // a list read is in flight
  useEffect(() => {
    current.current = data;
    reading.current = loading;
  });

  if (source !== data) {
    setSource(data);
    setPatches({});
  }

  const apply = useCallback(
    (kind, film) => {
      const patch =
        kind === 'remove'
          ? { hiddenFromContinueWatching: true }
          : show === 'in-progress'
            ? WATCHED_GONE
            : WATCHED_STAYS;
      const keys = Object.keys(patch);
      begin(
        kind,
        film,
        () => setPatches((p) => ({ ...p, [film.id]: { ...p[film.id], ...patch } })),
        () => setPatches((p) => without(p, film.id, keys)),
        {
          source: data,
          // The list may have been read again before the change committed, or be being read now (its answer may
          // predate the change): read it again, which supersedes any read in flight. Never otherwise, so other
          // films' patches stand.
          onCommitted: () => {
            if (current.current !== data || reading.current) reload();
          },
        },
      );
    },
    [begin, show, data, reload],
  );

  const remove = useCallback((film) => apply('remove', film), [apply]);
  const markWatched = useCallback((film) => apply('watched', film), [apply]);

  // New data drops the patches, so an Undo after a reload has nothing to put back: once the server has reversed the
  // action, read the list again to show it. (`undo()` resolves to true when it asked for that reload.)
  const undo = useCallback(async () => {
    const taken = action?.source;
    const reversed = await undoAction();
    // Replaced, or a read in flight whose answer may predate the reversal: read again (which supersedes it).
    const reloading = reversed && (taken !== current.current || reading.current);
    if (reloading) reload();
    return reloading;
  }, [action, undoAction, reload]);

  const all = data?.films ?? [];
  const films = all.flatMap((film) => {
    const patch = patches[film.id];
    if (!patch) return [film];
    return patch.gone ? [] : [{ ...film, ...patch }];
  });
  const total = (data?.total ?? 0) - (all.length - films.length);

  return { films, total, action, remove, markWatched, undo };
}
