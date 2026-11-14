import { useCallback, useRef, useState } from 'react';
import { useToast } from './useToast.js';
import { usePlexImport, useScan } from './useAdmin.js';

/**
 * The state the Admin layout owns for every section: the one scan poller and the one Plex status poller (their
 * finish times drive each section's reloads), the Fix match and Edit editions dialogs, the toast, and `changed`,
 * a counter bumped after a match is applied or editions are saved. `visible` (the tab is shown) pauses polling.
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
  const toast = useToast();
  const fallback = useRef(false);

  // Dialogs belong to the section they were opened from: moving to another one closes them.
  const [seenPath, setSeenPath] = useState(pathname);
  if (seenPath !== pathname) {
    setSeenPath(pathname);
    setFixing(null);
    setEditing(null);
  }

  // The lists refresh when a scan or a Plex import finishes: both settle matches.
  const settledAt = `${scan?.lastScan?.finishedAt ?? 'never'}|${plexImport.plex?.lastImport?.finishedAt ?? 'none'}`;
  // Read again when a scan, a subtitle search or a subtitle check finishes (new checks and evidence).
  const subtitlesVersion = `${settledAt}|${scan?.openSubtitlesLastSearchedAt ?? 'never'}|${scan?.subtitleChecksLastFinishedAt ?? 'never'}`;

  const markChanged = useCallback(() => {
    setChanged((n) => n + 1);
    reloadScan();
  }, [reloadScan]);
  const openFixMatch = useCallback((item, opener) => setFixing({ item, opener }), []);
  const openEditEditions = useCallback((film, opener) => setEditing({ film, opener }), []);
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
      settledAt,
      subtitlesVersion,
      changed,
      openFixMatch,
      openEditEditions,
      takeFocusFallback,
    },
    fixing,
    editing,
    closeFixMatch,
    closeEditEditions,
    markChanged,
    armFocusFallback,
    toast,
  };
}
