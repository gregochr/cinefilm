import { useCallback, useEffect, useRef } from 'react';
import { editionsApi, filmsApi } from '../api/resources.js';
import { PROGRESS_INTERVAL_MS } from '../player/resume.js';

/**
 * Progress reporting and preference saving for one playing edition. No DOM: the caller
 * supplies `getPositionMs`. Reports every 10 s while `playing`, when playback pauses, and on
 * unmount.
 */
export function usePlayback({ filmId, editionId, playing, getPositionMs }) {
  const getRef = useRef(getPositionMs);
  const lastReported = useRef(null);
  useEffect(() => {
    getRef.current = getPositionMs;
  });

  const report = useCallback(async () => {
    const positionMs = Math.round(getRef.current());
    if (!editionId || !Number.isFinite(positionMs) || positionMs === lastReported.current)
      return null;
    lastReported.current = positionMs;
    try {
      return await editionsApi.reportProgress(editionId, positionMs);
    } catch {
      lastReported.current = null;
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

  useEffect(() => () => void report(), [report]);

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
