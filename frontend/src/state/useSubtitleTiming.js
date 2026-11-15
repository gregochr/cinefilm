import { useCallback, useEffect, useRef, useState } from 'react';
import { adminApi } from '../api/resources.js';
import {
  EARLIER,
  LATER,
  TimingChanged,
  TimingLookupFailed,
  nextOffsets,
  timingProblem,
  timingRows,
  trackToken,
} from './subtitleTiming.js';

const NOTHING = { offsets: null, problem: null };

/**
 * The player's "Earlier" / "Later" / "Reset timing" for the subtitle *file* that shows (`kind`: `'FORCED'` or `'FULL'`;
 * `ids`: the sidecar ids the film showed for it, one row per part). It never touches the playback: it only calls
 * `PUT /api/admin/subtitles/{id}/offset` and hands the answer to `onApplied(kind, token)`, so the player can reload that
 * one `<track>`.
 *
 * - The current offsets are read from `GET /api/admin/subtitles` while `enabled` (the panel is open) and again at each
 *   press, so a press never works from a stale number. Each row moves by the step from its own offset (rows of
 *   different parts may differ); Reset sets every row to 0. One absolute offset per row goes out, one request at a time.
 * - One press per file at a time (a ref, so two clicks in one frame send one request); the buttons stay focusable and
 *   `busy` says why they do nothing.
 * - What an answer says (offsets, a problem) belongs to the file it was for: once another file shows, it is dropped,
 *   and a problem is forgotten when the shown file changes. The `<track>` of a file that changed is still told
 *   (`onApplied`), whatever shows now, because its timing did change; nothing is said after the player is gone.
 */
export function useSubtitleTiming({ kind, ids, enabled, onApplied }) {
  const fileKey = kind && ids?.length ? `${kind}:${ids.join(',')}` : null;
  const shown = useRef({ fileKey, kind, ids });
  shown.current = { fileKey, kind, ids };
  const callback = useRef(onApplied);
  callback.current = onApplied;
  const alive = useRef(true);
  const flying = useRef(new Set());
  const writes = useRef(0);
  const [state, setState] = useState({ key: fileKey, ...NOTHING });
  const [inFlight, setInFlight] = useState([]);

  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);

  // A new file starts with nothing known and no problem: another file's words never carry over.
  useEffect(() => {
    setState((s) => (s.key === fileKey ? s : { key: fileKey, ...NOTHING }));
  }, [fileKey]);

  // What the answer is for: it is dropped when another file shows by the time it arrives.
  const patch = useCallback((key, change) => {
    if (alive.current) setState((s) => (s.key === key ? { ...s, ...change } : s));
  }, []);

  // The offsets for the readout, read while the panel is open. A change made since the read started wins over it.
  useEffect(() => {
    if (!enabled || !fileKey) return undefined;
    let live = true;
    const seen = writes.current;
    adminApi
      .subtitles()
      .then((list) => {
        if (!live || writes.current !== seen || flying.current.has(fileKey)) return;
        const rows = timingRows(list, shown.current.ids);
        if (rows.length > 0) patch(fileKey, { offsets: rows.map((r) => r.offsetMs) });
      })
      .catch(() => {
        // The readout stays empty; a press reads the list itself and says what went wrong.
      });
    return () => {
      live = false;
    };
  }, [enabled, fileKey, patch]);

  const press = useCallback(
    async (delta) => {
      const { fileKey: key, kind: fileKind, ids: fileIds } = shown.current;
      if (!key || flying.current.has(key)) return;
      flying.current.add(key);
      setInFlight((keys) => [...keys, key]);
      patch(key, { problem: null });
      const answers = [];
      let offsets = null;
      let problem = null;
      try {
        const rows = timingRows(await adminApi.subtitles(), fileIds);
        if (rows.length === 0) throw new TimingLookupFailed();
        if (rows.length !== fileIds.length) throw new TimingChanged();
        offsets = rows.map((r) => r.offsetMs);
        // One absolute offset per row that actually moves, in the film's order; the first refusal stops the rest.
        for (const move of nextOffsets(rows, delta).filter((m) => m.offsetMs !== m.was)) {
          answers.push(await adminApi.setSubtitleOffset(move.id, move.offsetMs));
        }
        offsets = rows.map((r) => answers.find((a) => a.id === r.id)?.offsetMs ?? r.offsetMs);
      } catch (error) {
        problem = timingProblem(error);
        // Rows that did change before the refusal show their new offset.
        if (offsets)
          offsets = offsets.map((o, i) => answers.find((a) => a.id === fileIds[i])?.offsetMs ?? o);
      } finally {
        flying.current.delete(key);
        writes.current += 1;
        setInFlight((keys) => keys.filter((k) => k !== key));
      }
      patch(key, { problem, ...(offsets ? { offsets } : {}) });
      // Whatever shows now, the file that was changed has new timing: its track is told, and only if it changed.
      if (answers.length > 0 && alive.current) callback.current(fileKind, trackToken(answers));
    },
    [patch],
  );

  const mine = state.key === fileKey ? state : { ...NOTHING };
  return {
    available: fileKey !== null,
    offsets: mine.offsets,
    problem: mine.problem,
    busy: fileKey !== null && inFlight.includes(fileKey),
    earlier: () => press(EARLIER.delta),
    later: () => press(LATER.delta),
    reset: () => press(null),
  };
}
