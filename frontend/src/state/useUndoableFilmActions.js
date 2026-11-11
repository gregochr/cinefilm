import { useCallback, useEffect, useRef, useState } from 'react';
import { editionsApi } from '../api/resources.js';

/** How long the Undo pill stays up; the action is already committed by then. */
export const UNDO_MS = 8000;

/**
 * The optimistic core shared by every list that offers Remove from Continue watching and Mark as watched: it knows
 * nothing about the list. `begin(kind, film, change, restore)` calls `change()` (the caller's local change), starts the
 * request and makes it the single undoable action (the latest) for `UNDO_MS`. `restore()` puts the local change back;
 * it runs when the request fails (unless something newer has happened to that film) and on Undo. Callers keep
 * `restore` independent of what else changed since, for example by using functional state updates.
 *
 * Requests for one edition are serialised (each starts after the previous one settled), so a quick Remove, Undo,
 * Remove reaches the server in that order. `reload` is called if an Undo cannot be sent, to resynchronise with the
 * server. `onRollback(film)` is called after a failed request has been restored. `begin` returns false (and
 * changes nothing) when the film has no edition to act on. `meta` (optional) is spread onto the `action` entry.
 */
export function useUndoableFilmActions(reload, onRollback) {
  const [action, setAction] = useState(null);
  const current = useRef(null);
  const timer = useRef(null);
  const chains = useRef(new Map());
  const rolledBack = useRef(onRollback);
  const resyncing = useRef(new Set()); // editions waiting to reload
  const versions = useRef(new Map()); // film id -> bumped by every action and Undo on it

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

  const bump = useCallback((filmId) => {
    const next = (versions.current.get(filmId) ?? 0) + 1;
    versions.current.set(filmId, next);
    return next;
  }, []);

  const clearAction = useCallback((entry) => {
    if (current.current !== entry) return;
    current.current = null;
    clearTimeout(timer.current);
    setAction(null);
  }, []);

  // Reads the list again after everything queued for the edition has settled, so the answer includes the newer
  // requests. Several failures on one edition wait for one reload.
  const resync = useCallback(
    (editionId) => {
      if (resyncing.current.has(editionId)) return;
      resyncing.current.add(editionId);
      const drain = () => {
        const tail = chains.current.get(editionId);
        tail.then(() => {
          if (chains.current.get(editionId) !== tail) {
            drain(); // more was queued meanwhile
            return;
          }
          resyncing.current.delete(editionId);
          reload();
        });
      };
      drain();
    },
    [reload],
  );

  const begin = useCallback(
    (kind, film, change, restore, meta) => {
      const editionId = film.progress?.editionId;
      if (!editionId) return false;
      const entry = { ...meta, kind, film, editionId, restore, version: bump(film.id) };
      change();
      const call =
        kind === 'remove'
          ? () => editionsApi.setHiddenFromContinueWatching(editionId, true)
          : () => editionsApi.setWatched(editionId, true);
      entry.call = enqueue(editionId, call);
      entry.call.catch(() => {
        clearAction(entry);
        // Only roll back if nothing newer has happened to this film since (Undo, another action). Otherwise
        // the local state no longer matches the server for this film, so read it again once its queue is empty.
        if (versions.current.get(film.id) !== entry.version) {
          resync(editionId);
          return;
        }
        restore();
        rolledBack.current?.(film);
      });
      current.current = entry;
      setAction(entry);
      clearTimeout(timer.current);
      timer.current = setTimeout(() => clearAction(entry), UNDO_MS);
      return true;
    },
    [clearAction, enqueue, bump, resync],
  );

  const undo = useCallback(async () => {
    const entry = current.current;
    if (!entry) return false;
    clearAction(entry);
    bump(entry.film.id);
    entry.restore();
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
      return true;
    } catch {
      reload();
      return false;
    }
  }, [clearAction, reload, enqueue, bump]);

  return { action, begin, undo };
}
