import { useEffect } from 'react';
import { LIBRARY_CHANGED_EVENT, SESSION_EXPIRED_EVENT, SIGNED_IN_EVENT } from '../api/client.js';
import { forgetSearchIndex, revalidate, startSearchIndex } from '../state/searchIndex.js';

/**
 * Keeps the shared search index loaded and fresh while the app shell is mounted. The browser-facing wiring lives
 * here so the store in `state/` stays plain functions: it starts the load, and revalidates when the tab becomes
 * visible, after a sign-in, and when this tab says the library changed (a scan or import finished, the player
 * closed). A session expiry makes the store forget its stored copy.
 *
 * The shell is not mounted while the player is open, so a mount after the first one revalidates too: that is how
 * the time left shows what the player just saved.
 */
export function useSearchIndexLifecycle() {
  useEffect(() => {
    if (!startSearchIndex()) void revalidate();
    const onVisibility = () => {
      if (document.visibilityState === 'visible') void revalidate();
    };
    const onChanged = () => void revalidate();
    document.addEventListener('visibilitychange', onVisibility);
    window.addEventListener(LIBRARY_CHANGED_EVENT, onChanged);
    window.addEventListener(SIGNED_IN_EVENT, onChanged);
    window.addEventListener(SESSION_EXPIRED_EVENT, forgetSearchIndex);
    return () => {
      document.removeEventListener('visibilitychange', onVisibility);
      window.removeEventListener(LIBRARY_CHANGED_EVENT, onChanged);
      window.removeEventListener(SIGNED_IN_EVENT, onChanged);
      window.removeEventListener(SESSION_EXPIRED_EVENT, forgetSearchIndex);
    };
  }, []);
}
