import { adminApi } from '../api/resources.js';
import { useNeedChoices } from './useNeedChoices.js';

/**
 * The Admin list of which films need forced subtitles, and the owner's choice per film: {@link useNeedChoices} over
 * the forced-need routes. `onSaved` is {@link useNeedChoices}'s. `refreshKey` changes whenever the subtitle files may have changed.
 */
export function useForcedNeed(refreshKey, { onSaved } = {}) {
  return useNeedChoices({
    name: 'admin-forced-need',
    refreshKey,
    onSaved,
    load: adminApi.forcedNeed,
    save: adminApi.setForcedNeed,
  });
}
