import { useEffect, useLayoutEffect, useRef } from 'react';
import { containTab } from '../../components/useDialog.js';
import { PREVIEW_LABEL } from '../../state/christmas.js';
import { useSignInRecovery } from './useSignInRecovery.js';

/**
 * The phone's Home preview: a full-screen sheet holding the row (`children`) and the screen's toast, so the toast's
 * Undo stays reachable while it is up (a modal dialog hides what is outside it). Focus goes to Close on open; Esc and
 * Close call `onClose` (the opener takes focus back there), Tab stays inside, and nothing behind scrolls.
 */
export default function ChristmasPreviewSheet({ onClose, toast, children }) {
  const dialog = useRef(null);
  const close = useRef(null);
  const signedIn = useSignInRecovery();

  useLayoutEffect(() => {
    close.current.focus();
  }, []);

  // After a 401 the sign-in card takes focus and then goes, leaving it on the page body: put it back inside the sheet,
  // or Esc and the Tab trap would no longer reach it.
  useEffect(() => {
    if (signedIn === 0) return;
    if (!dialog.current.contains(document.activeElement)) close.current.focus();
  }, [signedIn]);

  useEffect(() => {
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = previous;
    };
  }, []);

  const onKeyDown = (event) => {
    if (event.key === 'Escape' && !event.nativeEvent.isComposing) {
      event.preventDefault();
      onClose();
      return;
    }
    containTab(event, event.currentTarget);
  };

  return (
    // The dialog only listens for Esc and Tab, bubbled from the controls inside it.
    // eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions
    <div
      ref={dialog}
      className="xmas-preview-sheet"
      role="dialog"
      aria-modal="true"
      aria-label="Home in Christmas week"
      onKeyDown={onKeyDown}
    >
      <div className="xmas-preview-sheet__bar">
        <span className="xmas-preview__label">{PREVIEW_LABEL}</span>
        <button ref={close} type="button" className="xmas-add-sheet__cancel" onClick={onClose}>
          Close
        </button>
      </div>
      <div className="xmas-preview-sheet__body">{children}</div>
      {toast}
    </div>
  );
}
