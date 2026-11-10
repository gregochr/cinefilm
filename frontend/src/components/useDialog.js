import { useCallback, useEffect, useRef, useState } from 'react';

/** How long the dialog's fade takes, in milliseconds (the CSS animation uses the same value). */
export const FADE_MS = 150;

const FOCUSABLE =
  'button:not([disabled]), select:not([disabled]), input:not([disabled]), a[href], [tabindex="0"]';

/** True when the person asked for less motion. Without matchMedia (tests, old engines) there is no animation. */
function reducedMotion() {
  return (
    typeof window.matchMedia !== 'function' ||
    window.matchMedia('(prefers-reduced-motion: reduce)').matches
  );
}

/**
 * Behaviour of a modal dialog: a focus trap, Esc, focus on open and focus returned to what opened it, a page that
 * does not scroll behind it, and a short fade out before it closes (none under reduced motion).
 *
 * @param onEscape runs when Esc is pressed (the dialog's Cancel)
 * @param opener the element that opened the dialog, else whatever had focus when it opened
 * @returns a ref for the dialog, `closing` while it fades out, and `close(after)`
 */
export function useDialog({ onEscape, opener }) {
  const dialogRef = useRef(null);
  const [closing, setClosing] = useState(false);
  const [returnTo] = useState(() => opener ?? document.activeElement);
  const timer = useRef(null);
  const escape = useRef(onEscape);
  useEffect(() => {
    escape.current = onEscape;
  });

  useEffect(() => {
    const onKeyDown = (event) => {
      const dialog = dialogRef.current;
      if (!dialog) return;
      if (event.key === 'Escape') {
        event.preventDefault();
        escape.current();
        return;
      }
      if (event.key !== 'Tab') return;
      const items = [...dialog.querySelectorAll(FOCUSABLE)];
      if (items.length === 0) return;
      const first = items[0];
      const last = items[items.length - 1];
      const active = document.activeElement;
      if (!dialog.contains(active)) {
        event.preventDefault();
        (event.shiftKey ? last : first).focus();
      } else if (event.shiftKey && active === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && active === last) {
        event.preventDefault();
        first.focus();
      }
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, []);

  useEffect(() => {
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = previous;
    };
  }, []);

  useEffect(
    () => () => {
      clearTimeout(timer.current);
      if (returnTo?.isConnected) returnTo.focus();
    },
    [returnTo],
  );

  const close = useCallback((after) => {
    if (reducedMotion()) {
      after();
      return;
    }
    setClosing(true);
    timer.current = setTimeout(after, FADE_MS);
  }, []);

  return { dialogRef, closing, close };
}
