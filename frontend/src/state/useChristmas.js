import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, SIGNED_IN_EVENT, serverText } from '../api/client.js';
import { getChristmas, putChristmasChoice } from '../api/christmas.js';
import {
  INVERSE_TOAST,
  POLL_MS,
  TOAST_MS,
  failureText,
  resultWrite,
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
 * Returns `{ data, version, issued, pinToast, loading, error, reload, refresh, busy, toast, change, undo, focusRequest, holdToast, releaseToast, keepToast }`.
 * `toast` is `{ message, undo: { tmdbId, filmId, title, choice, kind, panel } | null }` or null; it clears after 8 seconds unless it is
 * held (the pointer or the keyboard is on it); each toast carries a counter `id`. `focusRequest` is
 * `{ filmId, undo, panel, n }` after a change lands or fails: `undo` is true after a change from a list row that left an
 * Undo (the screen then moves focus on to that row's button, else the next row's, else the list; Undo stays reachable by
 * Tab and the pointer), `filmId` is the row the change was for, and with neither the screen settles focus itself.
 */
export function useChristmas() {
  const [data, setData] = useState(null);
  // When the data shown was asked for, as a position in the sequence of requests (see `accept`).
  const [version, setVersion] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(() => new Set());
  const [toast, setToast] = useState(null);
  const [focusRequest, setFocusRequest] = useState(null);
  const latest = useRef(0);
  const inFlight = useRef(new Set());
  const droppedStale = useRef(false);
  const toastTimer = useRef(null);
  const pinned = useRef(false);
  const held = useRef(false);
  const kept = useRef(false);
  const shownVersion = useRef(0);
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
    if (!live.current) return;
    if (!pinned.current && !held.current && !kept.current)
      toastTimer.current = setTimeout(() => setToast(null), TOAST_MS);
  }, []);

  const showToast = useCallback(
    (next) => {
      toastCount.current += 1;
      // A new toast is a new pill: whatever held the old one let go with it.
      held.current = false;
      kept.current = false;
      setToast({ ...next, id: toastCount.current });
      armToast();
    },
    [armToast],
  );

  const holdToast = useCallback(() => {
    held.current = true;
    clearTimeout(toastTimer.current);
  }, []);
  const releaseToast = useCallback(() => {
    held.current = false;
    armToast();
  }, [armToast]);

  /**
   * Keeps the toast up while keyboard focus rests on the button the screen moved it to after a row action: Undo sits
   * after the whole list, so Tab could not reach it inside the 8 seconds. `keepToast(false)` starts the countdown.
   */
  const keepToast = useCallback(
    (on) => {
      kept.current = on;
      if (on) clearTimeout(toastTimer.current);
      else armToast();
    },
    [armToast],
  );

  /** Keeps the toast up while the search panel is open (its Undo is then out of keyboard reach); released on close. */
  const pinToast = useCallback(
    (on) => {
      pinned.current = on;
      if (on) clearTimeout(toastTimer.current);
      else armToast();
    },
    [armToast],
  );

  // The data is stamped with where its request stood in the sequence when it was ASKED (a write's a half step later,
  // so a `/states` question asked while the write was in flight counts as older than the write's answer): the stamp
  // is what `resultState` compares a `/states` answer's with, not the order the answers happened to land in.
  const accept = useCallback((body, stamp) => {
    shownVersion.current = stamp;
    setData(body);
    setVersion(stamp);
  }, []);
  /**
   * What a `/states` question is stamped with: the newest request number, or the data's own stamp if that is later (a
   * write's answer, a half step after its request). A question asked while a write is in flight is older than the
   * write's answer; one asked after the answer landed is not.
   */
  const issued = useCallback(() => Math.max(latest.current, shownVersion.current), []);

  // `quiet` is a poll: the list stays and a failure says nothing.
  const read = useCallback(
    (quiet) => {
      const mine = ++latest.current;
      // The failure stays on screen (with its Try again, busy) until the new answer replaces it.
      if (!quiet) setLoading(true);
      getChristmas().then(
        (body) => {
          if (mine !== latest.current) {
            // A write started after this was asked: if that write fails nothing else would read again.
            if (inFlight.current.size > 0) droppedStale.current = true;
            return;
          }
          accept(body, mine);
          setError(null);
          setLoading(false);
        },
        (failure) => {
          if (mine !== latest.current || quiet) return;
          setError(failure);
          setLoading(false);
        },
      );
    },
    [accept],
  );

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
    async (tmdbId, filmId, title, choice, { kind, previous, withUndo, panel = false }) => {
      if (inFlight.current.has(tmdbId)) return;
      inFlight.current.add(tmdbId);
      setBusy(new Set(inFlight.current));
      const mine = ++latest.current;
      const ask = (focusRow, toUndo) => {
        focusCount.current += 1;
        setFocusRequest({ filmId: focusRow, undo: toUndo, panel, n: focusCount.current });
      };
      try {
        const body = await putChristmasChoice(tmdbId, choice);
        if (!live.current) return;
        if (mine === latest.current) accept(body, mine + 0.5);
        else droppedStale.current = true;
        showToast({
          message: toastText(kind, title),
          undo: withUndo ? { tmdbId, filmId, title, choice: previous, kind, panel } : null,
        });
        // A change made from the search panel keeps focus on that result's button: the panel stays open.
        ask(filmId, withUndo && !panel);
      } catch (failure) {
        if (!live.current) return;
        const status = failure instanceof ApiError ? failure.status : 0;
        showToast({ message: failureText(status, serverText(failure)), undo: null });
        // A failed Undo replaces the toast its button was in: the screen must not leave focus on the page body.
        ask(panel ? filmId : null, false);
      } finally {
        inFlight.current.delete(tmdbId);
        setBusy(new Set(inFlight.current));
        if (live.current && inFlight.current.size === 0 && droppedStale.current) {
          droppedStale.current = false;
          read(true);
        }
      }
    },
    [showToast, read, accept],
  );

  /** A quiet read: the list stays and a failure says nothing (after Fix match saved). */
  const refresh = useCallback(() => read(true), [read]);

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

  /**
   * The action of a search result (Add, Leave out, Remove or Put back), `{ filmId, tmdbId, title, state }`: the same
   * write, toast and Undo as a list row's, but focus stays on the result's button.
   */
  const changeResult = useCallback(
    (result) => {
      const action = resultWrite(result.state, result.tmdbId, data?.added);
      if (!action) return Promise.resolve();
      return write(result.tmdbId, result.filmId, result.title, action.choice, {
        kind: action.toast,
        previous: action.previous,
        withUndo: true,
        panel: true,
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
        panel: pending.panel === true,
      });
    },
    [write],
  );

  return {
    data,
    loading,
    error,
    version,
    issued,
    pinToast,
    reload: () => read(false),
    refresh,
    busy,
    toast,
    change,
    changeResult,
    undo,
    focusRequest,
    holdToast,
    releaseToast,
    keepToast,
  };
}
