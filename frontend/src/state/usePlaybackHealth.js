import { adminApi } from '../api/resources.js';
import { useResource } from './useResource.js';

/** The Admin "Playback health" card: the recent playbacks, read once when Admin opens (no polling). */
export function usePlaybackHealth() {
  const { data, error, loading, reload } = useResource('admin-playback-health', (options) =>
    adminApi.playbackHealth(options),
  );
  return { playbacks: data?.playbacks ?? null, error, loading, reload };
}
