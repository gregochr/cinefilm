import { useEffect, useRef } from 'react';
import { adminApi } from '../api/resources.js';
import { useResource } from './useResource.js';

/** How often the transcoding state is read again while the Playback section is on screen and the tab is visible. */
export const TRANSCODING_POLL_MS = 5000;

/**
 * The Playback section's one read of the transcoding state (encoder, keyframe index progress, live sessions and
 * the HLS cache size), read again every few seconds while `visible` (the tab is shown), and once at once when a
 * hidden tab is shown again. "Now playing" matches its live sessions to streams; Conversion shows the encoder and
 * index.
 *
 * Like the Admin summary, the poll is a quiet `refresh`: the figures stay on screen, `loading` does not flip, and a
 * failed read leaves `error` set (with the last data still returned) until a later one answers, so a failure before
 * the first answer shows one error rather than a skeleton and an error taking turns. A refresh aborts a read still in
 * flight, so a read that never answers cannot stop the poll and reads never pile up. `reload` (the retry button)
 * shows the loading state again.
 */
export function useTranscoding(visible = true) {
  const { data, error, loading, reload, refresh } = useResource('admin-transcoding', (options) =>
    adminApi.transcoding(options),
  );

  useEffect(() => {
    if (!visible) return undefined;
    const id = setInterval(refresh, TRANSCODING_POLL_MS);
    return () => clearInterval(id);
  }, [visible, refresh]);

  // Coming back to a hidden tab reads once at once: the figures may be long out of date.
  const wasHidden = useRef(false);
  useEffect(() => {
    if (!visible) {
      wasHidden.current = true;
    } else if (wasHidden.current) {
      wasHidden.current = false;
      refresh();
    }
  }, [visible, refresh]);

  return { transcoding: data, error, loading, reload };
}
