import { useEffect } from 'react';
import { adminApi } from '../api/resources.js';
import { useResource } from './useResource.js';

/** How often the transcoding state is read again while the Playback section is on screen. */
export const TRANSCODING_POLL_MS = 5000;

/**
 * The Playback section's one read of the transcoding state (encoder, keyframe index progress, live sessions and
 * the HLS cache size), read again every few seconds. "Now playing" matches its live sessions to streams; Conversion
 * shows the encoder and index.
 */
export function useTranscoding() {
  const { data, error, loading, reload } = useResource('admin-transcoding', (options) =>
    adminApi.transcoding(options),
  );

  useEffect(() => {
    const id = setTimeout(reload, TRANSCODING_POLL_MS);
    return () => clearTimeout(id);
  }, [data, error, reload]);

  return { transcoding: data, error, loading, reload };
}
