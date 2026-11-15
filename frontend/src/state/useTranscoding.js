import { useCallback, useEffect, useState } from 'react';
import { adminApi } from '../api/resources.js';
import { useResource } from './useResource.js';

/** How often the Transcoding panel refreshes while it is on screen. */
export const TRANSCODING_POLL_MS = 5000;

/**
 * The Admin Transcoding panel: encoder, keyframe index progress, live sessions and the HLS cache size,
 * refreshed every few seconds, plus "Stop" for one session. `onStopped` runs after each stop attempt, once the live list
 * is read again (the Playback section re-reads the summary there, so "Now playing" agrees).
 */
export function useTranscoding({ onStopped } = {}) {
  const { data, error, loading, reload } = useResource('admin-transcoding', (options) =>
    adminApi.transcoding(options),
  );
  const [notice, setNotice] = useState(null);

  useEffect(() => {
    const id = setTimeout(reload, TRANSCODING_POLL_MS);
    return () => clearTimeout(id);
  }, [data, error, reload]);

  const stop = useCallback(
    async (sessionId) => {
      setNotice(null);
      try {
        await adminApi.stopTranscodingSession(sessionId);
      } catch {
        setNotice('Could not stop the session.');
      }
      reload();
      onStopped?.();
    },
    [reload, onStopped],
  );

  return { transcoding: data, error, loading, notice, stop, reload };
}
