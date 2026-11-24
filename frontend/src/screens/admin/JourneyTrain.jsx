import { useEffect, useId, useRef, useState } from 'react';
import { Button } from '../../components/Button.jsx';
import { journeyTrain as copy } from '../../state/journeysCopy.js';
import {
  trainProblemText,
  trainReadyLine,
  trainRows,
  trainSpoken,
} from '../../state/journeysFormat.js';
import { readFileText } from '../../state/readFileText.js';
import { readTrainFile } from '../../state/trainFile.js';

/** What a chosen file comes to: the request and summary to show, or the one sentence for why not. */
async function look(file) {
  try {
    const result = readTrainFile(await readFileText(file));
    return result.ok
      ? { request: result.request, summary: result.summary }
      : { problem: trainProblemText(result.problem) };
  } catch {
    return { problem: copy.unreadable };
  }
}

/**
 * What is inside an open panel: choose a file, read it here, see what will be sent, then send it. Nothing is sent
 * before the button. A file chosen while a request is in flight is ignored (and the input emptied, so the same file can
 * be chosen again); of two files chosen in a row only the last one shows. It is mounted only while open, so a chosen
 * file is forgotten when it closes.
 */
function TrainPanel({ id, inputRef, replacing, busy, problem, onClose, onGive, onEdit }) {
  const inputId = useId();
  const [choice, setChoice] = useState(null);
  // Which choice is the latest, so a slow read of an earlier file never shows.
  const latest = useRef(0);

  const choose = async (event) => {
    const field = event.target;
    if (busy) {
      field.value = '';
      return;
    }
    const mine = ++latest.current;
    onEdit();
    setChoice(null);
    const file = field.files[0];
    if (!file) return;
    const next = await look(file);
    if (latest.current !== mine) return;
    // A file that comes to a problem is not kept: the browser fires no change for the file the input already holds, so
    // choosing it again (a file still being written) would do nothing.
    if (next.problem) field.value = '';
    setChoice(next);
  };

  const shown = choice?.problem ?? problem;
  const send = replacing ? copy.sendReplace : copy.sendAdd;
  return (
    // Escape is a shortcut for "Cancel", pressed on a control inside the group, which is itself no control.
    // eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions
    <div
      id={id}
      className="journey-train__panel"
      role="group"
      aria-label={copy.group}
      onKeyDown={(event) => {
        if (event.key === 'Escape' && !busy) onClose();
      }}
    >
      <p className="admin__text">{copy.text}</p>
      <div className="journey-import__field">
        <label htmlFor={inputId} className="admin__text">
          {copy.label}
        </label>
        <input
          ref={inputRef}
          id={inputId}
          className="journey-import__file"
          type="file"
          accept=".json"
          onChange={choose}
        />
      </div>
      {shown ? (
        <p className="admin__todo-problem" role="alert">
          {shown}
        </p>
      ) : null}
      {choice?.summary ? (
        <>
          <p className="admin__text" role="status">
            {trainReadyLine(choice.summary)}
          </p>
          <h3 className="journey-import__sent">{copy.sentHeading}</h3>
          <dl className="admin__rows admin__rows--wide">
            {trainRows(choice.summary).map((row) => (
              <div key={row.term} className="journey-import__row">
                <dt>{row.term}</dt>
                <dd>{row.value}</dd>
              </div>
            ))}
          </dl>
        </>
      ) : null}
      <div className="admin__actions">
        {choice?.summary ? (
          // aria-disabled, not disabled: the button keeps focus while the request runs; a press does nothing.
          <Button
            aria-disabled={busy || undefined}
            onClick={() => {
              if (!busy) onGive(choice.request);
            }}
          >
            {busy ? copy.storing : send}
          </Button>
        ) : null}
        <Button
          aria-disabled={busy || undefined}
          onClick={() => {
            if (!busy) onClose();
          }}
        >
          {copy.cancel}
        </Button>
      </div>
    </div>
  );
}

/**
 * A stored journey's "Add train file…" or "Replace train file…" and the panel it opens inside the row (the button's
 * `aria-expanded` and `aria-controls`). Focus goes to the file input when a press opens the panel, and back to the
 * button when the panel closes with focus fallen to the page. The page owns which panel is open (`open`), the request
 * (`busy`, true while any write runs, which makes the button and the panel's buttons do nothing) and a refusal
 * (`problem`, its sentence, or null). `onGive(request)` sends the chosen file; `onEdit` tells the page a file has been chosen.
 */
export default function JourneyTrain({
  journey,
  open,
  busy,
  problem,
  onOpen,
  onClose,
  onGive,
  onEdit,
}) {
  const panelId = useId();
  const buttonRef = useRef(null);
  const inputRef = useRef(null);
  // One rule, with no focus events: when the panel opens, focus goes to the input; when it closes, focus goes to the
  // button if it has fallen to the page (the focused control went with the panel: Cancel, Escape, a 200 or a 404).
  // Focus the owner has put somewhere real stays.
  const wasOpen = useRef(open);
  useEffect(() => {
    if (open && !wasOpen.current) inputRef.current.focus();
    else if (!open && wasOpen.current && document.activeElement === document.body) {
      buttonRef.current.focus();
    }
    wasOpen.current = open;
  }, [open]);

  // The same rule for a train taken away (the row's "Remove train…" or its confirmation went with it): focus that has
  // fallen to the page goes to this button, which now adds. Focus the owner has put somewhere real stays.
  const replacing = journey.train !== null;
  const hadTrain = useRef(replacing);
  useEffect(() => {
    if (hadTrain.current && !replacing && document.activeElement === document.body) {
      buttonRef.current.focus();
    }
    hadTrain.current = replacing;
  }, [replacing]);

  return (
    <>
      <div className="admin__actions admin__todo-actions journey-row__train">
        <Button
          ref={buttonRef}
          aria-label={trainSpoken(journey)}
          aria-expanded={open}
          aria-controls={panelId}
          aria-disabled={busy || undefined}
          onClick={() => {
            if (busy) return;
            if (open) onClose();
            else onOpen();
          }}
        >
          {replacing ? copy.replace : copy.add}
        </Button>
      </div>
      {open ? (
        <TrainPanel
          id={panelId}
          inputRef={inputRef}
          replacing={replacing}
          busy={busy}
          problem={problem}
          onClose={onClose}
          onGive={onGive}
          onEdit={onEdit}
        />
      ) : null}
    </>
  );
}
