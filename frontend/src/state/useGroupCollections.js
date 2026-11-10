import { useCallback, useState } from 'react';

export const GROUP_KEY = 'cinefilm.library.groupCollections';

function read() {
  try {
    return window.localStorage.getItem(GROUP_KEY) === '1';
  } catch {
    return false;
  }
}

/** The "Group collections" preference, remembered in this browser. Off by default. */
export function useGroupCollections() {
  const [on, setOn] = useState(read);
  const set = useCallback((value) => {
    setOn(value);
    try {
      if (value) window.localStorage.setItem(GROUP_KEY, '1');
      else window.localStorage.removeItem(GROUP_KEY);
    } catch {
      // Storage can be blocked; the switch still works for this visit.
    }
  }, []);
  return [on, set];
}
