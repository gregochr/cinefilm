import { useEffect, useId, useMemo, useRef, useState } from 'react';
import { Button } from '../../components/Button.jsx';
import { subtitlesPage } from '../../state/adminCopy.js';
import { filmTitle, ownerChoices } from '../../state/adminSubtitles.js';

const copy = subtitlesPage.else;

/** The id of the "Everything else" heading, where focus goes when the last choice has been given back. */
export const ELSE_HEADING_ID = 'admin-subtitles-else';

const keyOf = (kind, row) => `${kind}:${row.filmId}`;

/**
 * "Your choices ({n})": a quiet toggle (shown only while the owner has choices) over the films the owner has chosen
 * for, in the full-subtitles list (`full`) and the forced list (`forced`), each with what was chosen and "Let CineFilm
 * decide", which sends choice `null` through that list's own route. It is the way back from "It's English", "No
 * foreign scenes" and an owner "needed", now that the old panels are gone. The lists are the hooks' state
 * (`useNeedChoices`): a saved row leaves here at once, and the page reads again through their `onSaved`.
 *
 * One request per film: a click on a row that is saving does nothing (`aria-disabled`, still focusable). A failure
 * keeps the row with the words `problems` holds. When the row that had focus leaves, focus moves to the toggle, or
 * to the section heading when it was the last choice.
 */
export default function SubtitleYourChoices({ full, forced }) {
  const [open, setOpen] = useState(false);
  const listId = useId();
  const toggleRef = useRef(null);
  const sent = useRef(new Set());
  const leaving = useRef(null);
  const items = useMemo(
    () => ownerChoices({ fullRows: full.rows, forcedRows: forced.rows }),
    [full.rows, forced.rows],
  );
  const needOf = { full, forced };

  useEffect(() => {
    const key = leaving.current;
    if (key === null) return;
    const [kind, id] = key.split(':');
    if (items.some((item) => keyOf(item.kind, item.row) === key)) {
      if (!(kind === 'full' ? full : forced).saving[id]) leaving.current = null;
      return;
    }
    leaving.current = null;
    const focused = document.activeElement;
    if (focused && focused !== document.body && focused.isConnected) return;
    if (items.length > 0) toggleRef.current?.focus();
    else document.getElementById(ELSE_HEADING_ID)?.focus();
  }, [items, full, forced]);

  // With no choice left there is nothing to show: the next choice starts closed, not reopened expanded.
  if (items.length === 0 && open) setOpen(false);
  if (items.length === 0) return null;

  const reset = ({ kind, row }) => {
    const key = keyOf(kind, row);
    if (sent.current.has(key) || needOf[kind].saving[row.filmId]) return;
    sent.current.add(key);
    leaving.current = key;
    needOf[kind].choose(row.filmId, null).finally(() => sent.current.delete(key));
  };

  return (
    <>
      <div className="admin__actions admin__else-actions">
        <Button
          ref={toggleRef}
          className="admin__todo-quiet"
          aria-expanded={open}
          aria-controls={open ? listId : undefined}
          onClick={() => setOpen(!open)}
        >
          {copy.yourChoices(items.length)}
        </Button>
      </div>
      {open ? (
        <ul id={listId} className="admin__else-films" aria-label={copy.yourChoicesList}>
          {items.map((item) => {
            const { kind, row } = item;
            const saving = Boolean(needOf[kind].saving[row.filmId]);
            const problem = needOf[kind].problems[row.filmId];
            return (
              <li key={keyOf(kind, row)} className="admin__else-choice">
                <span className="admin__else-film">
                  {filmTitle(row)}
                  <span className="admin__todo-year">
                    {' '}
                    · {copy.choiceLine(kind, row.ownerChoice)}
                  </span>
                </span>
                <Button
                  className="admin__todo-quiet"
                  aria-label={copy.letDecide(filmTitle(row), kind)}
                  aria-disabled={saving || undefined}
                  onClick={() => reset(item)}
                >
                  {copy.letDecideLabel}
                </Button>
                {problem ? (
                  <p className="admin__todo-problem" role="alert">
                    {problem}
                  </p>
                ) : null}
              </li>
            );
          })}
        </ul>
      ) : null}
    </>
  );
}
