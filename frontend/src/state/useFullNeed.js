import { adminApi } from '../api/resources.js';
import { useNeedChoices } from './useNeedChoices.js';

/**
 * The Admin list of which films need full English subtitles, and the owner's choice per film: {@link useNeedChoices}
 * over the full-need routes. `onSaved` is {@link useNeedChoices}'s. `refreshKey` changes whenever the library may have
 * changed (a scan).
 */
export function useFullNeed(refreshKey, { onSaved } = {}) {
  return useNeedChoices({
    name: 'admin-full-need',
    refreshKey,
    onSaved,
    load: adminApi.fullNeed,
    save: adminApi.setFullNeed,
  });
}
