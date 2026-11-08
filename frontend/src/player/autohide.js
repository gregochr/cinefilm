import { useCallback, useEffect, useState } from 'react';

export const AUTOHIDE_MS = 3000;

/** Controls may hide only while playing with no menu open and nothing buffering. */
export function canAutoHide({ playing, menuOpen, ended = false }) {
  return Boolean(playing) && !menuOpen && !ended;
}

/**
 * Visibility of the player chrome. Any activity (`poke`) shows it and restarts the 3 s idle
 * timer; it never hides while paused or while a menu is open.
 */
export function useAutoHide({ playing, menuOpen, ended = false, delay = AUTOHIDE_MS }) {
  const [visible, setVisible] = useState(true);
  const [activity, setActivity] = useState(0);
  const poke = useCallback(() => {
    setVisible(true);
    setActivity((n) => n + 1);
  }, []);

  const allowed = canAutoHide({ playing, menuOpen, ended });
  useEffect(() => {
    if (!allowed) {
      setVisible(true);
      return undefined;
    }
    const id = setTimeout(() => setVisible(false), delay);
    return () => clearTimeout(id);
  }, [allowed, activity, delay]);

  return { visible, poke };
}
