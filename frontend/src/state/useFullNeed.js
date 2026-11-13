import { adminApi } from '../api/resources.js';
import { useNeedChoices } from './useNeedChoices.js';

/**
 * The Admin list of which films need full English subtitles, and the owner's choice per film: {@link useNeedChoices} over
 * the full-need routes. `refreshKey` changes whenever the library may have changed (a scan).
 */
export function useFullNeed(refreshKey) {
  return useNeedChoices({
    name: 'admin-full-need',
    refreshKey,
    load: adminApi.fullNeed,
    save: adminApi.setFullNeed,
  });
}
