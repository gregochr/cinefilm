import { useCallback, useEffect, useRef } from 'react';
import { announceLibraryChanged } from '../api/client.js';
import { editionsApi, filmsApi } from '../api/resources.js';
import { PROGRESS_INTERVAL_MS } from '../player/resume.js';

/** A failed report is retried after 2 s, then 4 s, 8 s … never more than 30 s apart. */
export const RETRY_BASE_MS = 2000;
export const RETRY_MAX_MS = 30000;

/**
 * Progress reporting and preference saving for one playing edition. No DOM: the caller
 * supplies `getPositionMs`. Reports every 10 s while `playing`, when playback pauses, and on
 * unmount. A position of 0 or less is never reported (a reloading source reads 0 and must not
 * overwrite the saved position), and a failed report is kept and retried with backoff. When the player closes
 * it announces `cinefilm:library-changed` once the final report has settled.
 */
export function usePlayback({ filmId, editionId, playing, getPositionMs }) {
  const getRef = useRef(getPositionMs);
  const lastReported = useRef(null);
  const failures = useRef(0);
  const retryTimer = useRef(null);
  const closed = useRef(false);
  useEffect(() => {
    getRef.current = getPositionMs;
  });

  const report = useCallback(async () => {
    const positionMs = Math.round(getRef.current());
    if (
      !editionId ||
      !Number.isFinite(positionMs) ||
      positionMs <= 0 ||
      positionMs === lastReported.current
    )
      return null;
    lastReported.current = positionMs;
    clearTimeout(retryTimer.current);
    try {
      const saved = await editionsApi.reportProgress(editionId, positionMs);
      failures.current = 0;
      return saved;
    } catch {
      lastReported.current = null;
      const delay = Math.min(RETRY_BASE_MS * 2 ** failures.current, RETRY_MAX_MS);
      failures.current += 1;
      if (!closed.current) retryTimer.current = setTimeout(() => void report(), delay);
      return null;
    }
  }, [editionId]);

  useEffect(() => {
    if (!playing) return undefined;
    const id = setInterval(report, PROGRESS_INTERVAL_MS);
    return () => clearInterval(id);
  }, [playing, report]);

  const wasPlaying = useRef(false);
  useEffect(() => {
    if (wasPlaying.current && !playing) report();
    wasPlaying.current = playing;
  }, [playing, report]);

  useEffect(() => {
    closed.current = false;
    return () => {
      closed.current = true;
      clearTimeout(retryTimer.current);
      // Leaving the player: once the last position is saved, tell the search index its time-left is out of date.
      void report().then(announceLibraryChanged, announceLibraryChanged);
    };
  }, [report]);

  const setWatched = useCallback(
    (watched) => (editionId ? editionsApi.setWatched(editionId, watched) : Promise.resolve(null)),
    [editionId],
  );

  const savePreferences = useCallback(
    (preferences) => filmsApi.savePreferences(filmId, preferences).catch(() => null),
    [filmId],
  );

  return { report, setWatched, savePreferences };
}
