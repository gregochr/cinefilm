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
 * "Now playing" with its Stop. `entries` is the summary's `playingNow`, `sessions` the live HLS sessions (both read
 * by the section already); `reloadSessions` and `reloadSummary` re-read them after a stop that worked.
 *
 * `rows` are the entries as the card draws them. A row with a `sessionId` has a live HLS session and can be stopped:
 * `stop(row)` sends the stop once, and a second call for the same row while it is in flight does nothing
 * (`stopping` holds the playback ids in flight). A failure sets `failures[playbackId]` and keeps the row; a success
 * hides the row (`ended[playbackId]` is the session that was stopped), calls `onLeft(row)` so the section can move
 * focus off the button that is leaving, and re-reads both lists. Stop ends only the server's converting session: a
 * player that is still playing starts a new one under the same playback id, and the summary keeps listing the row
 * while it reports. So a hidden row stays hidden only while its edition has no live session or still has the stopped
 * one; a session with another id brings the row back, with Stop, and the hide is dropped for good once that
 * row is seen (so a later end of that session does not hide it again, and the map stays small). A new try on a row clears its old failure. The
 * flags are looked up by playback id, and a later viewing has a new one.
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
        await adminApi.stopTranscodingSession(row.sessionId, row.playbackId);
      } catch {
        setFailures((f) => ({ ...f, [id]: copy.stopFailed }));
        return;
      } finally {
        inFlight.current.delete(id);
        setStopping((s) => without(s, id));
      }
      setEnded((e) => ({ ...e, [id]: row.sessionId }));
      onLeft?.(row);
      reloadSessions();
      reloadSummary();
    },
    [onLeft, reloadSessions, reloadSummary],
  );

  const all = (entries ?? []).map((entry) => playingNowRow(entry, sessions));
  const shows = (row) => {
    const stopped = ended[row.playbackId];
    return !stopped || (row.sessionId !== null && row.sessionId !== stopped);
  };
  // A row shown again on a new session no longer needs its hide: drop it now (in render, as `useResource` does for a
  // new key), so it cannot hide the row once that session ends too.
  const superseded = all.filter((row) => ended[row.playbackId] && shows(row));
  if (superseded.length > 0) {
    setEnded((e) => superseded.reduce((rest, row) => without(rest, row.playbackId), e));
  }
  const rows = all.filter(shows);
  return { rows, stopping, failures, stop };
}
