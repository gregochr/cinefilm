import { useEffect, useRef } from 'react';
import { Button } from '../../components/Button.jsx';

/**
 * The pill itself. It is held (`onHold`) while the pointer is over it or focus is inside it, and let go (`onRelease`)
 * once neither is, so the toast does not vanish under someone who is about to press Undo. It is re-created for each
 * message (the parent's `key`), so what it remembers is only ever about its own message.
 */
function ToastPill({ toast, undoRef, onUndo, onHold, onRelease }) {
  const on = useRef({ pointer: false, focus: false });
  const set = (which, value) => {
    const before = on.current.pointer || on.current.focus;
    on.current[which] = value;
    const after = on.current.pointer || on.current.focus;
    if (!before && after) onHold();
    if (before && !after) onRelease();
  };
  // A pill that goes away while it holds the toast (focus was on its Undo when it was moved, and removing a focused
  // button fires no blur) lets go, or the toast would never restart its timer.
  useEffect(
    () => () => {
      if (on.current.pointer || on.current.focus) onRelease();
    },
    [onRelease],
  );
  return (
    <div
      className="xmas-toast"
      onMouseEnter={() => set('pointer', true)}
      onMouseLeave={() => set('pointer', false)}
      onFocus={() => set('focus', true)}
      onBlur={() => set('focus', false)}
    >
      <span>{toast.message}</span>
      {toast.undo ? (
        <Button ref={undoRef} className="xmas-toast__undo" onClick={() => onUndo(toast.undo)}>
          Undo
        </Button>
      ) : null}
    </div>
  );
}

/**
 * The bottom pill: the message and, for a change that can be undone, an Undo button. The live region is always
 * present so a new message is announced; a new action replaces the one before it.
 */
export default function ChristmasToast({ toast, undoRef, onUndo, onHold, onRelease }) {
  return (
    <div className="xmas-toast-region" role="status" aria-label="Christmas notification">
      {toast ? (
        <ToastPill
          key={toast.id}
          toast={toast}
          undoRef={undoRef}
          onUndo={onUndo}
          onHold={onHold}
          onRelease={onRelease}
        />
      ) : null}
    </div>
  );
}
