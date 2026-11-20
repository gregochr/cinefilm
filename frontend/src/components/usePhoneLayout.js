import { useSyncExternalStore } from 'react';

const QUERY = '(max-width: 600px)';

function query() {
  return typeof window !== 'undefined' && typeof window.matchMedia === 'function'
    ? window.matchMedia(QUERY)
    : null;
}

function subscribe(onChange) {
  const list = query();
  list?.addEventListener('change', onChange);
  return () => list?.removeEventListener('change', onChange);
}

/** True at phone width (600px and under), the width where the Admin pages switch to their phone layout. */
export function usePhoneLayout() {
  // One line on purpose: Prettier would split out the SSR snapshot as an uncovered line.
  // prettier-ignore
  return useSyncExternalStore(subscribe, () => query()?.matches ?? false, () => false);
}
