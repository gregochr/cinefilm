import { useId, useRef, useState } from 'react';
import { ActionWithHint } from '../../components/ActionWithHint.jsx';
import { buttons, todoRow } from '../../state/adminCopy.js';

function LeftRow({ film, busy, problem, onPutBack }) {
  const titleId = useId();
  return (
    <li className="admin__todo-row">
      <div className="admin__todo-text">
        <p id={titleId} className="admin__todo-title">
          {film.title}
          {film.year ? <span className="admin__todo-year"> · {film.year}</span> : null}
        </p>
      </div>
      <div className="admin__actions admin__todo-actions">
        {/* aria-disabled, not disabled: the button keeps focus while its request runs; the hook ignores clicks. */}
        <ActionWithHint
          hint={buttons.putBack.hint}
          describedBy={titleId}
          aria-disabled={busy || undefined}
          onClick={(event) => {
            if (!busy) onPutBack(film, event.currentTarget);
          }}
        >
          {buttons.putBack.label}
        </ActionWithHint>
      </div>
      {problem ? (
        <p className="admin__todo-problem" role="alert">
          {problem}
        </p>
      ) : null}
    </li>
  );
}

/**
 * "Left unmatched (N)": the films the owner chose not to match, folded until the heading's button opens them, each with
 * "Put back". Drawn only when there are any. `rows` are `{ id, title, year }`; `busy` the ids with a post in flight;
 * `problems` the failure words by id. `onPutBack(film, { onHidden })` posts; once the film has gone, focus that was on its
 * row goes to the heading button, or to the section title when it was the last film (the section leaves with it).
 */
export default function AdminLeftUnmatched({ rows, busy, problems, onPutBack }) {
  const [open, setOpen] = useState(false);
  const toggle = useRef(null);
  const listId = useId();
  if (rows.length === 0) return null;
  const heading = todoRow.leftUnmatched.heading(rows.length);
  const putBack = (film, opener) => {
    const last = rows.length === 1;
    onPutBack(film, {
      onHidden: () => {
        const active = document.activeElement;
        const row = opener?.closest('li');
        if (active && active !== document.body && !row?.contains(active)) return;
        (last ? document.getElementById('admin-section-title') : toggle.current)?.focus();
      },
    });
  };
  return (
    <section className="admin__todo-group" aria-label={heading}>
      <div className="admin__todo-head">
        <h2 className="admin__todo-heading">
          <button
            ref={toggle}
            type="button"
            className="admin__todo-more"
            aria-expanded={open}
            aria-controls={listId}
            onClick={() => setOpen(!open)}
          >
            {heading}
          </button>
        </h2>
        <p className="admin__todo-why">{todoRow.leftUnmatched.why}</p>
      </div>
      <ul id={listId} className="admin__todo-rows" hidden={!open}>
        {rows.map((film) => (
          <LeftRow
            key={film.id}
            film={film}
            busy={busy.includes(film.id)}
            problem={problems[film.id]}
            onPutBack={putBack}
          />
        ))}
      </ul>
    </section>
  );
}
