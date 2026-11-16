import { useCallback, useEffect, useRef, useState } from 'react';
import { adminApi } from '../api/resources.js';
import { RequestTimeout, withTimeout } from '../api/timeout.js';
import { subtitleTiming } from './subtitleFixCopy.js';
import { EARLIER, LATER, nextOffsets, timingProblem, trackToken } from './subtitleTiming.js';

/**
 * The player's "Earlier" / "Later" / "Reset timing" for the subtitle *file* that shows (`kind`: `'FORCED'` or `'FULL'`;
 * `ids`: the sidecar ids the film showed for it, one row per part; `offsets`: their offsets in the same order, both from
 * the film). It never touches the playback and reads nothing: the offsets come from the film and, after a change, from
 * each answer of `PUT /api/admin/subtitles/{id}/offset`, which is all it sends. The answers go to `onApplied(kind,
 * token, offsets)` so the player can load the new timing into that one `<track>`; `offsets` is what each row is at now
 * (this hook's `offsets` are the server's: what the next press works from, not what is on screen).
 *
 * - Each row moves by the step from its own offset (rows of different parts may differ); Reset sets every row to 0. One
 *   absolute offset per row that moves goes out, one request at a time; the first refusal stops the rest.
 * - Each request gives up after `ADMIN_TIMEOUT_MS` (a failure like any other, so a dead link never leaves the buttons
 *   busy). Offsets go out absolute, so asking again after no answer never adds a second step.
 * - A request with no answer leaves the file's save uncertain (the server may hold it): nothing of that press is
 *   recorded, and the next press re-sends the same absolute targets to every row, whichever button it is.
 * - A press with nothing to send (a reset at 0, say) while `onScreen` (the offsets of the track showing) differs from
 *   the server's retries the last timing's track instead, and leaves the problem line alone otherwise.
 * - A new track that failed to load (`swapFailed`): the server already holds the timing it was made for, so the next
 *   press, whichever button, loads that saved timing again (the same token) and moves nothing, and the `online` event
 *   does the same by itself for the file that shows. Only the press after that moves the offset again.
 * - One press per file at a time (a ref, so two clicks in one frame send one request); the buttons stay focusable and
 *   `busy` says why they do nothing.
 * - If the server no longer serves a changed row (it answers `served: false`: the owner's check was cleared and another
 *   subtitle plays now), the rows after it are not sent, the track is not reloaded, a change still loading is cancelled
 *   (`onReplaced(kind)`), and the timing of that file stays
 *   `frozen` for the rest of the viewing, with the subtitle-changed line saying why.
 * - What an answer says (offsets, a problem) belongs to the file it was for: a problem is forgotten when the shown file
 *   changes. The offsets learned here are kept for each file, so switching away and back shows them. The `<track>` of a
 *   file that changed is still told (`onApplied`), whatever shows now; nothing is said after the player is gone.
 */
export function useSubtitleTiming({ kind, ids, offsets, onScreen = null, onApplied, onReplaced }) {
  const fileKey =
    kind && ids?.length && offsets?.length === ids.length ? `${kind}:${ids.join(',')}` : null;
  const shown = useRef({ fileKey, kind, ids, offsets, onScreen });
  shown.current = { fileKey, kind, ids, offsets, onScreen };
  const callback = useRef(onApplied);
  callback.current = onApplied;
  const replacedCallback = useRef(onReplaced);
  replacedCallback.current = onReplaced;
  const frozenKeys = useRef(new Set());
  const alive = useRef(true);
  const flying = useRef(new Set());
  // Offsets learned from answers, by file; the ref is what a press reads, the state is what renders.
  const learned = useRef({});
  // The token of the last timing loaded for each file, so a press with nothing to send can load it again.
  const tokens = useRef({});
  // Files whose new track failed to load: their saved timing is not on screen yet, so a press reloads it.
  const failedLoads = useRef(new Set());
  // Files whose last save got no answer from some row (the server may or may not have it): the absolute targets that
  // were meant, re-sent whole by the next press (idempotent) before anything else moves.
  const uncertain = useRef({});
  const [known, setKnown] = useState({});
  const [problemOf, setProblemOf] = useState({ key: fileKey, text: null });
  const [inFlight, setInFlight] = useState([]);
  const [frozen, setFrozen] = useState([]);

  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);

  // A new file starts with no problem: another file's words never carry over.
  useEffect(() => {
    setProblemOf((p) => (p.key === fileKey ? p : { key: fileKey, text: null }));
  }, [fileKey]);

  // What the words are for: they are dropped when another file shows by the time they arrive.
  const say = useCallback((key, text) => {
    if (alive.current) setProblemOf((p) => (p.key === key ? { key, text } : p));
  }, []);

  // Loads the saved timing of a file again (the same token, so the same address) as the pending track.
  const reload = useCallback(
    (key, fileKind) => {
      failedLoads.current.delete(key);
      say(key, null);
      callback.current(
        fileKind,
        tokens.current[key],
        learned.current[key] ?? shown.current.offsets,
      );
    },
    [say],
  );

  const press = useCallback(
    async (delta) => {
      const { fileKey: key, kind: fileKind, ids: fileIds, offsets: base, onScreen } = shown.current;
      // A file the server no longer serves is left alone for the rest of the viewing: no press, no retry.
      if (!key || flying.current.has(key) || frozenKeys.current.has(key)) return;
      // The last new track did not load: this press shows the timing the server already holds, and moves nothing.
      if (failedLoads.current.has(key) && tokens.current[key]) {
        if (alive.current) reload(key, fileKind);
        return;
      }
      const start = learned.current[key] ?? base;
      const rows = fileIds.map((id, i) => ({ id, offsetMs: start[i] }));
      // One absolute offset per row that actually moves, in the film's order.
      const retried = uncertain.current[key];
      const moves = retried ?? nextOffsets(rows, delta).filter((m) => m.offsetMs !== m.was);
      if (moves.length === 0) {
        // Nothing to send. But if the screen shows other timing than the server has (the last new track did not
        // load), the press is a retry: the same timing is loaded again. Otherwise it changes nothing, not even the line.
        const same = onScreen?.length === start.length && onScreen.every((o, i) => o === start[i]);
        if (tokens.current[key] && onScreen && !same && alive.current) {
          say(key, null);
          callback.current(fileKind, tokens.current[key], start);
        }
        return;
      }
      failedLoads.current.delete(key);
      flying.current.add(key);
      setInFlight((keys) => [...keys, key]);
      say(key, null);
      const answers = [];
      let problem = null;
      let timedOut = false;
      try {
        // The first refusal, or the first row the server no longer serves, stops the rest.
        for (const move of moves) {
          const answer = await withTimeout((signal) =>
            adminApi.setSubtitleOffset(move.id, move.offsetMs, { signal }),
          );
          answers.push(answer);
          if (answer.served === false) break;
        }
      } catch (error) {
        problem = timingProblem(error);
        // No answer: rows answered before it are not recorded either, so the readout never says "differs by part".
        timedOut = error instanceof RequestTimeout;
      } finally {
        flying.current.delete(key);
        setInFlight((keys) => keys.filter((k) => k !== key));
      }
      const now = rows.map((r) => answers.find((a) => a.id === r.id)?.offsetMs ?? r.offsetMs);
      // The server state is unknown after no answer, and stays unknown after a failed retry of it: the targets are kept
      // until every row has answered (or the file is replaced).
      const unsure =
        timedOut ||
        (retried !== undefined &&
          answers.length < moves.length &&
          !answers.some((a) => a.served === false));
      if (unsure) uncertain.current[key] = moves;
      else delete uncertain.current[key];
      if (answers.length > 0 && !unsure) {
        // Rows that changed are at their new offset on the server, even when a later one was refused.
        learned.current = { ...learned.current, [key]: now };
        if (alive.current) setKnown(learned.current);
      }
      // The answers say whether each row is still the one served. If not, another subtitle plays now.
      const replaced = answers.some((a) => a.served === false);
      if (replaced) {
        frozenKeys.current.add(key);
        failedLoads.current.delete(key);
      }
      if (replaced && alive.current) {
        setFrozen((keys) => [...keys, key]);
        // An earlier change of this file may still be loading: it must not take over now.
        replacedCallback.current?.(fileKind);
      }
      say(key, problem ?? (replaced ? subtitleTiming.changed : null));
      // Whatever shows now, the file that was changed has new timing: its track is told, unless it is no longer served.
      if (answers.length > 0 && !unsure && !replaced && alive.current) {
        tokens.current[key] = trackToken(answers);
        callback.current(fileKind, tokens.current[key], now);
      }
    },
    [say, reload],
  );

  // The new track did not load: the old timing stays on screen, the new one is saved, and the viewer is told.
  const swapFailed = useCallback(
    (failedKind) => {
      const { fileKey: key, kind: now } = shown.current;
      if (!key || now !== failedKind) return;
      if (tokens.current[key]) failedLoads.current.add(key);
      say(key, subtitleTiming.swapFailed);
    },
    [say],
  );

  // The link is back: the track that failed to load is asked for again, once, for the file that shows.
  useEffect(() => {
    const onOnline = () => {
      const { fileKey: key, kind: fileKind } = shown.current;
      if (key && failedLoads.current.has(key) && alive.current) {
        reload(key, fileKind);
      }
    };
    window.addEventListener('online', onOnline);
    return () => window.removeEventListener('online', onOnline);
  }, [reload]);

  return {
    available: fileKey !== null,
    offsets: fileKey === null ? null : (known[fileKey] ?? offsets),
    problem:
      problemOf.key === fileKey
        ? (problemOf.text ?? (frozen.includes(fileKey) ? subtitleTiming.changed : null))
        : null,
    busy: fileKey !== null && inFlight.includes(fileKey),
    frozen: fileKey !== null && frozen.includes(fileKey),
    earlier: () => press(EARLIER.delta),
    later: () => press(LATER.delta),
    reset: () => press(null),
    swapFailed,
  };
}
