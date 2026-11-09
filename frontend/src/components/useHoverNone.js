import { useSyncExternalStore } from 'react';

const QUERY = '(hover: none)';

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

/** True on touch devices (no hover): the ⋯ menu becomes a bottom sheet. False where matchMedia is missing. */
export function useHoverNone() {
  return useSyncExternalStore(subscribe, () => query()?.matches ?? false, () => false);
}
