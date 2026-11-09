import { useCallback, useEffect, useRef, useState } from 'react';
import { editionsApi } from '../api/resources.js';

/** How long the Undo pill stays up; the action is already committed by then. */
export const UNDO_MS = 8000;

function insertAt(list, film, index) {
  if (list.some((f) => f.id === film.id)) return list;
  const next = [...list];
  next.splice(Math.min(Math.max(index, 0), next.length), 0, film);
  return next;
}

/**
 * The Continue watching row and hero over the Home payload, with optimistic Remove / Mark as watched
 * and a single undoable action (the latest). The API is called straight away; Undo reverses it.
 * `reload` is called if an Undo cannot be sent, to resynchronise with the server.
 */
export function useContinueWatching(data, reload) {
  const [source, setSource] = useState(data);
  const [row, setRow] = useState(data?.continueWatching ?? []);
  const [hero, setHero] = useState(data?.hero ?? null);
  const [action, setAction] = useState(null);
  const current = useRef(null);
  const timer = useRef(null);

  if (source !== data) {
    setSource(data);
    setRow(data?.continueWatching ?? []);
    setHero(data?.hero ?? null);
  }

  useEffect(() => () => clearTimeout(timer.current), []);

  const clearAction = useCallback((entry) => {
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
      };
      setRow((r) => r.filter((f) => f.id !== film.id));
      if (entry.heroWas) setHero(null);
      entry.call = call(editionId);
      entry.call.catch(() => {
        clearAction(entry);
        setRow((r) => insertAt(r, film, index));
        if (entry.heroWas) setHero(entry.heroWas);
      });
      current.current = entry;
      setAction(entry);
      clearTimeout(timer.current);
      timer.current = setTimeout(() => clearAction(entry), UNDO_MS);
    },
    [row, hero, clearAction],
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
    setRow((r) => insertAt(r, entry.film, entry.index));
    if (entry.heroWas) setHero(entry.heroWas);
    let result;
    try {
      result = await entry.call;
    } catch {
      return; // the original failed and has already been rolled back
    }
    try {
      if (entry.kind === 'remove') {
        await editionsApi.setHiddenFromContinueWatching(entry.editionId, false);
      } else {
        await editionsApi.restoreState(entry.editionId, result.previous);
      }
    } catch {
      reload();
    }
  }, [clearAction, reload]);

  return { hero, row, action, remove, markWatched, undo };
}
