import { useSyncExternalStore } from 'react';

function subscribe(onChange) {
  document.addEventListener('visibilitychange', onChange);
  return () => document.removeEventListener('visibilitychange', onChange);
}

const visibleNow = () => document.visibilityState === 'visible';

/** True while the tab is visible; changes when the tab is hidden or shown again. Polls pause while it is false. */
export function useDocumentVisible() {
  return useSyncExternalStore(subscribe, visibleNow, () => true);
}
