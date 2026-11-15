import { useCallback, useRef, useState } from 'react';
import { useToast } from './useToast.js';
import { usePlexImport, useScan } from './useAdmin.js';
import { useAdminSummary } from './useAdminSummary.js';

/**
 * The state the Admin layout owns for every section: the one scan poller and the one Plex status poller (their
 * finish times drive each section's reloads), the one summary read (`adminSummary`: rail counts and the To do strip, read
 * again on each of those stamps and every 30 s while visible), the Fix match and Edit editions dialogs, the toast, and `changed`,
 * a counter bumped after a match is applied or editions are saved. `visible` (the tab is shown) pauses polling.
 *
 * The OpenSubtitles picker is a dialog of the layout too: `openSubtitlePicker({ film, editionId, kind }, opener,
 * { onPicked })` opens it (`onPicked` runs when a file was downloaded and the dialog is still the open one), and
 * `pickerDone(picking)` is what the picker calls once a download went through: the lists are read again whether or
 * not the dialog is still open (the server saved the file), and the dialog closes only if it is still that one.
 *
 * The dialogs remember the element that opened them so the layout can hand focus back on close.
 */
export function useAdminShell(visible, pathname = '') {
  const scanState = useScan(visible);
  const plexImport = usePlexImport();
  const { scan, reload: reloadScan } = scanState;
  const [changed, setChanged] = useState(0);
  const [fixing, setFixing] = useState(null);
  const [editing, setEditing] = useState(null);
  const [picking, setPicking] = useState(null);
  // The picker that is open now, for a download that finishes after the owner closed it or opened another.
  const openPicker = useRef(null);
  openPicker.current = picking;
  const toast = useToast();
  const fallback = useRef(false);

  // Dialogs belong to the section they were opened from: moving to another one closes them.
  const [seenPath, setSeenPath] = useState(pathname);
  if (seenPath !== pathname) {
    setSeenPath(pathname);
    setFixing(null);
    setEditing(null);
    setPicking(null);
  }

  // The lists refresh when a scan or a Plex import finishes: both settle matches.
  const settledAt = `${scan?.lastScan?.finishedAt ?? 'never'}|${plexImport.plex?.lastImport?.finishedAt ?? 'none'}`;
  // Read again when a scan, a subtitle search or a subtitle check finishes (new checks and evidence).
  const subtitlesVersion = `${settledAt}|${scan?.openSubtitlesLastSearchedAt ?? 'never'}|${scan?.subtitleChecksLastFinishedAt ?? 'never'}`;

  // The rail and the To do page share this one read of the summary.
  const adminSummary = useAdminSummary(visible, {
    settledAt,
    subtitlesVersion,
    changed,
    // Held until the scan status has answered (or failed) once, so the first read has its stamp. Plex is not waited
    // for (it can take a minute when the Plex host is off): its stamp landing later costs one more read.
    ready: scan !== null || scanState.error !== null,
  });

  const markChanged = useCallback(() => {
    setChanged((n) => n + 1);
    reloadScan();
  }, [reloadScan]);
  const openFixMatch = useCallback((item, opener) => setFixing({ item, opener }), []);
  const openEditEditions = useCallback((film, opener) => setEditing({ film, opener }), []);
  const openSubtitlePicker = useCallback(
    (target, opener, { onPicked } = {}) => setPicking({ ...target, opener, onPicked }),
    [],
  );
  const closeSubtitlePicker = useCallback(() => setPicking(null), []);
  const pickerDone = useCallback(
    (done) => {
      // Whichever picker it was, the server holds the file now: read the lists again.
      markChanged();
      // Only the dialog that is still open is closed, and only it runs its caller's follow-up.
      if (openPicker.current !== done) return;
      setPicking(null);
      done.onPicked?.();
    },
    [markChanged],
  );
  const armFocusFallback = useCallback(() => {
    fallback.current = true;
  }, []);
  const takeFocusFallback = useCallback(() => {
    const armed = fallback.current;
    fallback.current = false;
    return armed;
  }, []);
  const closeFixMatch = useCallback(() => setFixing(null), []);
  const closeEditEditions = useCallback(() => setEditing(null), []);

  return {
    value: {
      ...scanState,
      plexImport,
      adminSummary,
      settledAt,
      subtitlesVersion,
      changed,
      openFixMatch,
      openEditEditions,
      openSubtitlePicker,
      markChanged,
      armFocusFallback,
      takeFocusFallback,
    },
    fixing,
    editing,
    picking,
    closeFixMatch,
    closeEditEditions,
    closeSubtitlePicker,
    pickerDone,
    markChanged,
    armFocusFallback,
    toast,
  };
}
