import { useCallback, useRef, useState } from 'react';
import { adminApi } from '../api/resources.js';
import { playingNow as copy } from './adminCopy.js';
import { playingNowRow } from './playingNowFormat.js';

const without = (object, key) => {
  const rest = { ...object };
  delete rest[key];
  return rest;
};

/**
 * "Playing now" with its Stop. `entries` is the summary's `playingNow`, `sessions` the live HLS sessions (both read
 * by the section already); `reloadSessions` and `reloadSummary` re-read them after a stop that worked.
 *
 * `rows` are the entries as the card draws them. A row with a `sessionId` has a live HLS session and can be stopped:
 * `stop(row)` sends the stop once, and a second call for the same row while it is in flight does nothing
 * (`stopping` holds the playback ids in flight). A failure sets `failures[playbackId]` and keeps the row; a success
 * hides the row at once (its player stops reporting, but the summary still lists it for up to two minutes), calls
 * `onLeft(row)` so the section can move focus off the button that is leaving, and re-reads both lists. A new try
 * on the same row clears its old failure. The flags are looked up by playback id, and a later viewing has a new one.
 */
export function usePlayingNow({ entries, sessions, reloadSessions, reloadSummary, onLeft }) {
  const [stopping, setStopping] = useState({});
  const [failures, setFailures] = useState({});
  const [ended, setEnded] = useState({});
  const inFlight = useRef(new Set());

  const stop = useCallback(
    async (row) => {
      const id = row.playbackId;
      if (!row.sessionId || inFlight.current.has(id)) return;
      inFlight.current.add(id);
      setStopping((s) => ({ ...s, [id]: true }));
      setFailures((f) => without(f, id));
      try {
        await adminApi.stopTranscodingSession(row.sessionId);
      } catch {
        setFailures((f) => ({ ...f, [id]: copy.stopFailed }));
        return;
      } finally {
        inFlight.current.delete(id);
        setStopping((s) => without(s, id));
      }
      setEnded((e) => ({ ...e, [id]: true }));
      onLeft?.(row);
      reloadSessions();
      reloadSummary();
    },
    [onLeft, reloadSessions, reloadSummary],
  );

  const rows = (entries ?? [])
    .filter((entry) => !ended[entry.playbackId])
    .map((entry) => playingNowRow(entry, sessions));
  return { rows, stopping, failures, stop };
}
