import { useEffect, useRef } from 'react';
import { Button } from '../../components/Button.jsx';

/**
 * A row's action button and the inline confirmation it opens, with the words, ids and class names of the one using it:
 * `opener` is `{ id, className, label, name }` (the button's id, its wrapper's classes, its text and its spoken name),
 * `confirmClassName` the group's class, and `words` is `{ group, sentence, confirm, busy, keep }`. Focus goes to the safe
 * choice ("Keep") when the owner opens it, and back to the opener when "Keep" or Escape closes it. The page owns which
 * group is open (`open`), the request (`busy`) and a refusal (`problem`, its sentence, or null). While the request runs
 * both buttons are `aria-disabled`, not `disabled`: they keep focus, and a press does nothing.
 */
export default function RowConfirm({
  opener,
  confirmClassName,
  words,
  open,
  busy,
  problem,
  onOpen,
  onKeep,
  onConfirm,
}) {
  const openRef = useRef(null);
  const keepRef = useRef(null);
  // One rule, with no focus events: when the group opens, focus goes to "Keep"; when it closes, focus goes to the opener
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
      <div className={opener.className}>
        <Button
          id={opener.id}
          ref={openRef}
          aria-label={opener.name}
          aria-disabled={busy || undefined}
          onClick={() => {
            if (!busy) onOpen();
          }}
        >
          {opener.label}
        </Button>
      </div>
    );
  }
  return (
    // Escape is a shortcut for "Keep", pressed on a button inside the group, which is itself no control.
    // eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions
    <div
      className={confirmClassName}
      role="group"
      aria-label={words.group}
      onKeyDown={(event) => {
        if (event.key === 'Escape') keep();
      }}
    >
      <p className="admin__text">{words.sentence}</p>
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
          {busy ? words.busy : words.confirm}
        </Button>
        <Button ref={keepRef} aria-disabled={busy || undefined} onClick={keep}>
          {words.keep}
        </Button>
      </div>
    </div>
  );
}
