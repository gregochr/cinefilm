import { useCallback, useEffect, useState } from 'react';
import { ApiError } from '../api/client.js';
import { adminApi } from '../api/resources.js';
import { useResource } from './useResource.js';

/** How often the Library scan panel asks for progress while a scan runs. */
export const SCAN_POLL_MS = 1500;

/** How often the Plex import panel looks again while Plex is not reachable. */
export const PLEX_RECHECK_MS = 30000;

/**
 * The Library scan panel: status and counts, polled while a scan runs, plus "Scan now". Starting a scan
 * while one is already running (409) is not an error: the panel just shows it running.
 */
export function useScan() {
  const { data, error, loading, reload } = useResource('admin-scan', (options) =>
    adminApi.scan(options),
  );
  const [starting, setStarting] = useState(false);
  const [notice, setNotice] = useState(null);
  const running = Boolean(data?.running);

  useEffect(() => {
    if (!running) return undefined;
    const id = setTimeout(reload, SCAN_POLL_MS);
    return () => clearTimeout(id);
  }, [running, data, error, reload]);

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

  return { scan: data, error, loading, starting, notice, startScan, reload };
}

/** Films needing attention. `version` changes when a scan finishes, so the list refreshes. */
export function useAttention(version) {
  return useResource(`admin-attention:${version}`, (options) => adminApi.attention(options));
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
