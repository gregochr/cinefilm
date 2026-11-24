import { useId, useRef, useState } from 'react';
import { Button } from '../../components/Button.jsx';
import { useAdminContext } from '../../state/adminContext.js';
import { readJourneyLog } from '../../state/journeyFile.js';
import { journeyImport as copy } from '../../state/journeysCopy.js';
import {
  importToast,
  leftOutHint,
  problemText,
  readyLine,
  sentRows,
} from '../../state/journeysFormat.js';
import { readFileText } from '../../state/readFileText.js';
import { useJourneyImport } from '../../state/useJourneyImport.js';

/** What a chosen file comes to: the request and summary to show, or the one sentence for why not. */
async function look(file) {
  try {
    const result = readJourneyLog(await readFileText(file), file.name);
    return result.ok
      ? { request: result.request, summary: result.summary }
      : { problem: problemText(result.problem) };
  } catch {
    return { problem: copy.unreadable };
  }
}

/**
 * The import card: choose a logger file, read it here in the browser, see what will be sent, then send it. Nothing is
 * sent before "Import". One import at a time; a file chosen while one is in flight is ignored (and the input emptied,
 * so the same file can be chosen again). Of two files chosen in a row only the last one shows. `onImported` is called
 * after a 201, so the page can read the stored journeys again.
 */
export default function JourneyImport({ onImported }) {
  const inputId = useId();
  const input = useRef(null);
  const { showToast } = useAdminContext();
  const writer = useJourneyImport();
  const [choice, setChoice] = useState(null);
  // Which choice is the latest, so a slow read of an earlier file never shows.
  const latest = useRef(0);

  const choose = async (event) => {
    const field = event.target;
    if (writer.busy) {
      field.value = '';
      return;
    }
    const mine = ++latest.current;
    writer.clearFailure();
    setChoice(null);
    const file = field.files[0];
    if (!file) return;
    const next = await look(file);
    if (latest.current !== mine) return;
    // A file that comes to a problem is not kept: the browser fires no change for the file the input already holds, so
    // choosing it again (a log still being written) would do nothing.
    if (next.problem) field.value = '';
    setChoice(next);
  };

  const send = async () => {
    if (writer.busy) return;
    const outcome = await writer.run(choice.request);
    if (outcome.ignored || !outcome.ok) return;
    showToast(importToast(outcome.response));
    onImported();
    // The input is gone with the page, so there is nothing left to clear or focus.
    const field = input.current;
    if (!field) return;
    setChoice(null);
    field.value = '';
    field.focus();
  };

  const problem = choice?.problem ?? writer.failure?.text;
  const hint = choice?.summary ? leftOutHint(choice.summary) : null;
  return (
    <section className="admin__card" aria-labelledby="admin-journey-import-title">
      <div className="admin__head">
        <h2 id="admin-journey-import-title" className="admin__title">
          {copy.title}
        </h2>
      </div>
      <p className="admin__text">{copy.text}</p>
      <div className="journey-import__field">
        <label htmlFor={inputId} className="admin__text">
          {copy.label}
        </label>
        <input
          ref={input}
          id={inputId}
          className="journey-import__file"
          type="file"
          accept=".jsonl,.json,.txt"
          onChange={choose}
        />
      </div>
      {problem ? (
        <p className="admin__todo-problem" role="alert">
          {problem}
        </p>
      ) : null}
      {choice?.summary ? (
        <>
          <p className="admin__text" role="status">
            {readyLine(choice.summary)}
          </p>
          <h3 className="journey-import__sent">{copy.sentHeading}</h3>
          <dl className="admin__rows admin__rows--wide">
            {sentRows(choice.summary, choice.request).map((row) => (
              <div key={row.term} className="journey-import__row">
                <dt>{row.term}</dt>
                <dd>{row.value}</dd>
              </div>
            ))}
          </dl>
          {hint ? <p className="admin__text">{hint}</p> : null}
          <div className="admin__actions">
            {/* aria-disabled, not disabled: the button keeps focus while the import runs; `send` ignores presses. */}
            <Button variant="primary" aria-disabled={writer.busy || undefined} onClick={send}>
              {writer.busy ? copy.importing : copy.button}
            </Button>
          </div>
        </>
      ) : null}
    </section>
  );
}
