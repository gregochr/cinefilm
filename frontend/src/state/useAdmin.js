import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, announceLibraryChanged } from '../api/client.js';
import { adminApi } from '../api/resources.js';
import { creditsFinishedText } from './plexCreditsFormat.js';
import { useResource } from './useResource.js';

/** How often the Library scan panel asks for progress while a scan runs. */
export const SCAN_POLL_MS = 1500;

/** How often the Library scan panel looks again when nothing is running, to notice a job that starts. */
export const SCAN_IDLE_POLL_MS = 60000;

/** How often the Plex import panel looks again while Plex is not reachable. */
export const PLEX_RECHECK_MS = 30000;

/**
 * Announces `cinefilm:library-changed` when a run's last-finished time changes after one was already seen. Going by
 * that rather than by watching `running` flip means a scan or import that starts and ends between two polls is
 * still announced; the first status loaded announces nothing. `finishedAt` is undefined until a status has loaded.
 */
function useAnnounceWhenFinished(finishedAt) {
  const seen = useRef(undefined);
  useEffect(() => {
    if (finishedAt === undefined) return;
    if (seen.current !== undefined && seen.current !== finishedAt) announceLibraryChanged();
    seen.current = finishedAt;
  }, [finishedAt]);
}

/**
 * The Library scan panel: status and counts, polled while a scan or a ratings refresh runs, plus "Scan now" and
 * "Refresh ratings". Starting either while one is already running (409) is not an error: the panel just shows it
 * running. It looks again every minute when nothing runs, so a job that starts in the background is noticed.
 * `visible` (the tab is shown; the UI layer knows) pauses the polling, and a change back to true reads once.
 */
export function useScan(visible = true) {
  const { data, error, loading, reload, refresh } = useResource('admin-scan', (options) =>
    adminApi.scan(options),
  );
  const [starting, setStarting] = useState(false);
  const [notice, setNotice] = useState(null);
  const [ratingsStarting, setRatingsStarting] = useState(false);
  const [ratingsNotice, setRatingsNotice] = useState(null);
  const running = Boolean(data?.running);
  const ratingsRunning = Boolean(data?.ratingsRunning);
  const subtitlesRunning =
    Boolean(data?.openSubtitlesRunning) || Boolean(data?.subtitleChecksRunning);
  useAnnounceWhenFinished(data ? (data.lastScan?.finishedAt ?? null) : undefined);

  const busy = running || ratingsRunning || subtitlesRunning;

  // The same status carries the ratings refresh and the subtitle jobs, so one poll follows whichever is running.
  // With none running it looks again every minute, so a job that starts in the background (the nightly scan, the
  // 06:00 subtitle search) is noticed. Each answer arms the next look, so requests never overlap; a hidden tab
  // does not poll.
  useEffect(() => {
    if (!visible) return undefined;
    const id = setTimeout(refresh, busy ? SCAN_POLL_MS : SCAN_IDLE_POLL_MS);
    return () => clearTimeout(id);
  }, [visible, busy, data, error, refresh]);

  // Coming back to a hidden tab reads the status once at once: it may be long out of date.
  const wasHidden = useRef(false);
  useEffect(() => {
    if (!visible) {
      wasHidden.current = true;
    } else if (wasHidden.current) {
      wasHidden.current = false;
      refresh();
    }
  }, [visible, refresh]);

  const refreshRatings = useCallback(async () => {
    setRatingsStarting(true);
    setRatingsNotice(null);
    try {
      await adminApi.startRatingsRefresh();
    } catch (failure) {
      setRatingsNotice(
        failure instanceof ApiError && failure.status === 409
          ? 'A ratings refresh is already running.'
          : 'Could not start the ratings refresh.',
      );
    } finally {
      setRatingsStarting(false);
      reload();
    }
  }, [reload]);

  const startScan = useCallback(async () => {
    setStarting(true);
    setNotice(null);
    try {
      await adminApi.startScan();
    } catch (failure) {
      setNotice(
        failure instanceof ApiError && failure.status === 409
          ? 'A scan is already running.'
          : 'Could not start the scan.',
      );
    } finally {
      setStarting(false);
      reload();
    }
  }, [reload]);

  return {
    scan: data,
    error,
    loading,
    starting,
    notice,
    startScan,
    ratingsStarting,
    ratingsNotice,
    refreshRatings,
    reload,
  };
}

/** Films needing attention. `version` changes when a scan finishes, so the list refreshes. */
export function useAttention(version) {
  return useResource(`admin-attention:${version}`, (options) => adminApi.attention(options));
}

/** The newest grouping changes. `version` changes when a scan or import finishes, so the report refreshes. */
export function useGroupingReport(version) {
  return useResource(`admin-grouping:${version}`, (options) => adminApi.groupingReport(options));
}

/**
 * The Plex import panel: status, polled while an import runs, plus "Import from Plex". Starting while a scan or
 * import already runs (409) is not an error: the notice says why, and the panel shows whichever is running.
 */
export function usePlexImport() {
  const { data, error, loading, reload } = useResource('admin-plex', (options) =>
    adminApi.plex(options),
  );
  const [starting, setStarting] = useState(false);
  const [notice, setNotice] = useState(null);
  const running = Boolean(data?.running);
  useAnnounceWhenFinished(data ? (data.lastImport?.finishedAt ?? null) : undefined);

  // Poll quickly while an import runs, and slowly while Plex cannot be reached, so the panel recovers by itself.
  const unreachable = Boolean(data?.configured) && !running && !data.reachable;
  useEffect(() => {
    if (!running && !unreachable) return undefined;
    const id = setTimeout(reload, running ? SCAN_POLL_MS : PLEX_RECHECK_MS);
    return () => clearTimeout(id);
  }, [running, unreachable, data, error, reload]);

  const startImport = useCallback(async () => {
    setStarting(true);
    setNotice(null);
    try {
      await adminApi.startPlexImport();
    } catch (failure) {
      setNotice(
        failure instanceof ApiError && failure.status === 409
          ? 'A scan or import is already running, or no scan has finished yet.'
          : 'Could not start the import.',
      );
    } finally {
      setStarting(false);
      reload();
    }
  }, [reload]);

  return { plex: data, error, loading, starting, notice, startImport, reload };
}

/**
 * The "Import credits from Plex" block: status, polled while an import runs (each answer arms the next look, so
 * requests never overlap), plus the start. Starting while one already runs (409) is not an error: the notice says so
 * and the block shows the running import. A second click while a start is in flight is ignored.
 *
 * The start's 202 carries the new status: it is shown at once and kept until a status read answers, so a failing read
 * right after the start still shows the import running, and polling goes on (after a failed read too) while the
 * status shown is running. The poll is the quiet `refresh`, armed only after each answer (a success or a failure), so
 * an error stays on screen until a read answers and no request is ever aborted by the next. `announcement` is the sentence to put in a live region when a run finishes while the
 * block is open (never for the first status loaded).
 */
export function usePlexCreditsImport() {
  const { data, error, loading, reload, refresh } = useResource('admin-plex-credits', (options) =>
    adminApi.plexCredits(options),
  );
  const [starting, setStarting] = useState(false);
  const [notice, setNotice] = useState(null);
  const [started, setStarted] = useState(null);
  const [announcement, setAnnouncement] = useState('');
  const startedRef = useRef(false);
  const seenFinished = useRef(undefined);
  const credits = started ?? data;
  const running = Boolean(credits?.running);
  useAnnounceWhenFinished(data ? (data.lastImport?.finishedAt ?? null) : undefined);

  // The start's answer gives way to a status read that shows the new run: running, or a last import other than the
  // one the start saw. A read issued before the start that answers after it shows neither, so it changes nothing.
  useEffect(() => {
    if (!started || !data) return;
    const sawNewRun =
      data.running ||
      (data.lastImport?.startedAt ?? null) !== (started.lastImport?.startedAt ?? null);
    if (sawNewRun) setStarted(null);
  }, [data, started]);

  const last = credits?.lastImport;
  useEffect(() => {
    if (!credits) return;
    if (credits.running) setAnnouncement('');
    const finishedAt = last?.finishedAt ?? null;
    if (
      seenFinished.current !== undefined &&
      finishedAt !== null &&
      seenFinished.current !== finishedAt
    ) {
      setAnnouncement(creditsFinishedText(last));
    }
    seenFinished.current = finishedAt;
  }, [credits, last]);

  useEffect(() => {
    if (!running) return undefined;
    const id = setTimeout(refresh, SCAN_POLL_MS);
    return () => clearTimeout(id);
  }, [running, data, error, refresh]);

  const startImport = useCallback(async () => {
    if (startedRef.current) return;
    startedRef.current = true;
    setStarting(true);
    setNotice(null);
    try {
      setStarted((await adminApi.startPlexCreditsImport()) ?? null);
      setAnnouncement('');
    } catch (failure) {
      setNotice(
        failure instanceof ApiError && failure.status === 409
          ? failure.body?.error || 'A credits import is already running.'
          : 'Could not start the credits import.',
      );
    } finally {
      startedRef.current = false;
      setStarting(false);
      refresh();
    }
  }, [refresh]);

  return { credits, error, loading, starting, notice, announcement, startImport, reload };
}
