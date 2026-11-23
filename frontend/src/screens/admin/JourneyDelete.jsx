import { useEffect, useRef } from 'react';
import { Button } from '../../components/Button.jsx';
import { journeyDelete as copy } from '../../state/journeysCopy.js';
import { deleteSentence, deleteSpoken } from '../../state/journeysFormat.js';

/** The id of a row's "Delete…", for the page to put focus on it once a neighbour has gone. */
export const deleteButtonId = (journeyId) => `admin-journey-delete-${journeyId}`;

/**
 * A stored journey's "Delete…" and its inline confirmation. Focus goes to the safe choice ("Keep") when the owner
 * opens it, and back to "Delete…" when "Keep" or Escape closes it. The page owns which group is open (`open`), the
 * request (`busy`) and a refusal (`problem`, its sentence, or null). While the request runs both buttons are
 * `aria-disabled`, not `disabled`: they keep focus, and a press does nothing.
 */
export default function JourneyDelete({ journey, open, busy, problem, onOpen, onKeep, onConfirm }) {
  const openRef = useRef(null);
  const keepRef = useRef(null);
  // One rule, with no focus events: when the group opens, focus goes to "Keep"; when it closes, focus goes to "Delete…"
  // if it has fallen to the page (the focused button went with the group: "Keep", Escape, a 204 or a 404). Focus the
  // owner has put somewhere real stays, and so does focus on the button that opened another row's group.
  const wasOpen = useRef(open);
  useEffect(() => {
    if (open && !wasOpen.current) keepRef.current.focus();
    else if (!open && wasOpen.current && document.activeElement === document.body) {
      openRef.current.focus();
    }
    wasOpen.current = open;
  }, [open]);

  const keep = () => {
    if (!busy) onKeep();
  };
  if (!open) {
    return (
      <div className="admin__actions admin__todo-actions journey-row__delete">
        <Button
          id={deleteButtonId(journey.id)}
          ref={openRef}
          aria-label={deleteSpoken(journey)}
          aria-disabled={busy || undefined}
          onClick={() => {
            if (!busy) onOpen();
          }}
        >
          {copy.open}
        </Button>
      </div>
    );
  }
  return (
    // Escape is a shortcut for "Keep", pressed on a button inside the group, which is itself no control.
    // eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions
    <div
      className="journey-delete__confirm"
      role="group"
      aria-label={copy.group}
      onKeyDown={(event) => {
        if (event.key === 'Escape') keep();
      }}
    >
      <p className="admin__text">{deleteSentence(journey)}</p>
      {problem ? (
        <p className="admin__todo-problem" role="alert">
          {problem}
        </p>
      ) : null}
      <div className="admin__actions">
        <Button
          aria-disabled={busy || undefined}
          onClick={() => {
            if (!busy) onConfirm();
          }}
        >
          {busy ? copy.deleting : copy.confirm}
        </Button>
        <Button ref={keepRef} aria-disabled={busy || undefined} onClick={keep}>
          {copy.keep}
        </Button>
      </div>
    </div>
  );
}
