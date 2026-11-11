import { useEffect } from 'react';
import { LIBRARY_CHANGED_EVENT, SESSION_EXPIRED_EVENT } from '../api/client.js';
import { invalidateAll } from '../state/resourceCache.js';

/**
 * Empties the browse cache when this tab says the library changed (a scan or import finished, the player closed)
 * or the session ended. Mounted by `App`, which stays mounted while the player is open (the shell does not), so
 * the player closing is never missed. The window wiring lives here so `state/` stays free of the DOM.
 */
export function useResourceCacheLifecycle() {
  useEffect(() => {
    window.addEventListener(LIBRARY_CHANGED_EVENT, invalidateAll);
    window.addEventListener(SESSION_EXPIRED_EVENT, invalidateAll);
    return () => {
      window.removeEventListener(LIBRARY_CHANGED_EVENT, invalidateAll);
      window.removeEventListener(SESSION_EXPIRED_EVENT, invalidateAll);
    };
  }, []);
}
