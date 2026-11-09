import { useEffect, useState } from 'react';

const KEY = 'cinefilm.slowCardShown';

function alreadyShown() {
  try {
    return sessionStorage.getItem(KEY) === '1';
  } catch {
    return false;
  }
}

function remember() {
  try {
    sessionStorage.setItem(KEY, '1');
  } catch {
    // Storage blocked: the card may show again; harmless.
  }
}

/**
 * The slow-connection card shows once per browser session (decision 11). `active` is true while Auto is
 * playing a slow rung on a remote link. Returns whether the card is open and a function to close it.
 */
export function useSlowCard(active) {
  const [open, setOpen] = useState(false);
  useEffect(() => {
    if (!active || alreadyShown()) return;
    remember();
    setOpen(true);
  }, [active]);
  return { open, close: () => setOpen(false) };
}
