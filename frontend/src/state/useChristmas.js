import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, SIGNED_IN_EVENT, serverText } from '../api/client.js';
import { getChristmas, putChristmasChoice } from '../api/christmas.js';
import {
  INVERSE_TOAST,
  POLL_MS,
  TOAST_MS,
  failureText,
  rowAction,
  toastText,
} from './christmas.js';

/**
 * The Christmas films screen's data and changes. Every request takes a number; an answer is shown only if no newer
 * request has started since (a stale answer is dropped), and polling does not run while a write is in flight. A row has
 * one write in flight at a time. A change is redrawn from the PUT's answer (the same body as the GET). When an answer
 * was dropped as stale (two rows changed at once: the server may have handled them in either order, so the newest
 * answer may lack the older change) the screen reads once more as soon as no write is in flight.
 *
 * Returns `{ data, loading, error, reload, busy, toast, change, undo, focusRequest, holdToast, releaseToast }`.
 * `toast` is `{ message, undo: { tmdbId, filmId, title, choice, kind } | null }` or null; it clears after 8 seconds unless it is
 * held (the pointer or the keyboard is on it); each toast carries a counter `id`. `focusRequest` is
 * `{ filmId, undo, n }` after a change lands or fails: `undo` is true after a change that left an Undo (focus goes to
 * the toast's Undo button, which a keyboard user would otherwise reach only after every row), `filmId` is the row to
 * focus (after an Undo), and with neither the screen settles focus itself.
 */
export function useChristmas() {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(() => new Set());
  const [toast, setToast] = useState(null);
  const [focusRequest, setFocusRequest] = useState(null);
  const latest = useRef(0);
  const inFlight = useRef(new Set());
  const droppedStale = useRef(false);
  const toastTimer = useRef(null);
  const live = useRef(true);
  const focusCount = useRef(0);
  const toastCount = useRef(0);

  useEffect(() => {
    live.current = true;
    return () => {
      live.current = false;
      clearTimeout(toastTimer.current);
    };
  }, []);

  const armToast = useCallback(() => {
    clearTimeout(toastTimer.current);
    toastTimer.current = setTimeout(() => setToast(null), TOAST_MS);
  }, []);

  const showToast = useCallback(
    (next) => {
      toastCount.current += 1;
      setToast({ ...next, id: toastCount.current });
      armToast();
    },
    [armToast],
  );

  const holdToast = useCallback(() => clearTimeout(toastTimer.current), []);

  // `quiet` is a poll: the list stays and a failure says nothing.
  const read = useCallback((quiet) => {
    const mine = ++latest.current;
    // The failure stays on screen (with its Try again, busy) until the new answer replaces it.
    if (!quiet) setLoading(true);
    getChristmas().then(
      (body) => {
        if (mine !== latest.current) return;
        setData(body);
        setError(null);
        setLoading(false);
      },
      (failure) => {
        if (mine !== latest.current || quiet) return;
        setError(failure);
        setLoading(false);
      },
    );
  }, []);

  useEffect(() => {
    read(false);
  }, [read]);

  // A 401 shows the sign-in card over this page, which stays mounted: read again once signed in.
  useEffect(() => {
    const onSignedIn = () => read(false);
    window.addEventListener(SIGNED_IN_EVENT, onSignedIn);
    return () => window.removeEventListener(SIGNED_IN_EVENT, onSignedIn);
  }, [read]);

  const running = data?.keywords.running === true;
  useEffect(() => {
    if (!running) return undefined;
    const timer = setInterval(() => {
      if (inFlight.current.size === 0) read(true);
    }, POLL_MS);
    return () => clearInterval(timer);
  }, [running, read]);

  const write = useCallback(
    async (tmdbId, filmId, title, choice, { kind, previous, withUndo }) => {
      if (inFlight.current.has(tmdbId)) return;
      inFlight.current.add(tmdbId);
      setBusy(new Set(inFlight.current));
      const mine = ++latest.current;
      const ask = (focusRow, toUndo) => {
        focusCount.current += 1;
        setFocusRequest({ filmId: focusRow, undo: toUndo, n: focusCount.current });
      };
      try {
        const body = await putChristmasChoice(tmdbId, choice);
        if (!live.current) return;
        if (mine === latest.current) setData(body);
        else droppedStale.current = true;
        showToast({
          message: toastText(kind, title),
          undo: withUndo ? { tmdbId, filmId, title, choice: previous, kind } : null,
        });
        ask(withUndo ? null : filmId, withUndo);
      } catch (failure) {
        if (!live.current) return;
        const status = failure instanceof ApiError ? failure.status : 0;
        showToast({ message: failureText(status, serverText(failure)), undo: null });
        // A failed Undo replaces the toast its button was in: the screen must not leave focus on the page body.
        ask(null, false);
      } finally {
        inFlight.current.delete(tmdbId);
        setBusy(new Set(inFlight.current));
        if (live.current && inFlight.current.size === 0 && droppedStale.current) {
          droppedStale.current = false;
          read(true);
        }
      }
    },
    [showToast, read],
  );

  /** The row's one action (Leave out, Remove or Put back). */
  const change = useCallback(
    (row) => {
      // The previous choice depends on the Added list (two films can share a TMDB id): see `rowAction`.
      const action = rowAction(row, data?.added);
      return write(row.tmdbId, row.filmId, row.title, action.choice, {
        kind: action.toast,
        previous: action.previous,
        withUndo: true,
      });
    },
    [write, data],
  );

  /** Undo of the toast's change: sends the previous choice, then says the opposite, and asks for focus on the row. */
  const undo = useCallback(
    async (pending) => {
      await write(pending.tmdbId, pending.filmId, pending.title, pending.choice, {
        kind: INVERSE_TOAST[pending.kind],
        previous: null,
        withUndo: false,
      });
    },
    [write],
  );

  return {
    data,
    loading,
    error,
    reload: () => read(false),
    busy,
    toast,
    change,
    undo,
    focusRequest,
    holdToast,
    releaseToast: armToast,
  };
}
