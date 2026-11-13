import { adminApi } from '../api/resources.js';
import { useNeedChoices } from './useNeedChoices.js';

/**
 * The Admin list of which films need forced subtitles, and the owner's choice per film: {@link useNeedChoices} over
 * the forced-need routes. `refreshKey` changes whenever the subtitle files may have changed.
 */
export function useForcedNeed(refreshKey) {
  return useNeedChoices({
    name: 'admin-forced-need',
    refreshKey,
    load: adminApi.forcedNeed,
    save: adminApi.setForcedNeed,
  });
}
