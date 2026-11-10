import { useCallback, useEffect, useRef, useState } from 'react';

/** How long a toast stays, matching the Undo pill. */
export const TOAST_MS = 4000;

/** One short message at a time that clears itself: `show("Editions saved")`. */
export function useToast() {
  const [message, setMessage] = useState(null);
  const timer = useRef(null);
  const show = useCallback((text) => {
    clearTimeout(timer.current);
    setMessage(text);
    timer.current = setTimeout(() => setMessage(null), TOAST_MS);
  }, []);
  useEffect(() => () => clearTimeout(timer.current), []);
  return { message, show };
}
