import { useState } from 'react';
import { Link } from 'react-router-dom';
import { ActionWithHint } from '../../components/ActionWithHint.jsx';
import { Button } from '../../components/Button.jsx';
import {
  buttons,
  footerDesktop,
  footerPhone,
  showAll,
  todoFooter,
  todoGroups,
} from '../../state/adminCopy.js';
import {
  bestGuess,
  filesLine,
  footerFor,
  forcedLine,
  fullLine,
  matchLine,
  visibleRows,
} from '../../state/adminTodo.js';
import { titleWithYear } from '../../state/scanFormat.js';

// The group keys of `adminTodo.js` (they follow the summary's `todo` figures) and their entries in `todoGroups`.
const COPY_KEY = {
  pickFilm: 'pickFilm',
  severalFiles: 'severalFiles',
  fullMissing: 'englishSubtitlesMissing',
  forcedQuestion: 'forcedNotFound',
};

function RowText({ title, line, mono = false }) {
  return (
    <div className="admin__todo-text">
      <p className="admin__todo-title">{title}</p>
      <p className={`admin__todo-line${mono ? ' admin__mono' : ''}`}>{line}</p>
    </div>
  );
}

/** A film the scanner could not match: "Yes, it's …" when the server is confident, and always "Find the film…". */
function MatchRow({ item, busy, problem, onConfirm, onFindFilm }) {
  const guess = item.confident ? bestGuess(item) : null;
  const find = (event) => onFindFilm(item, event.currentTarget);
  return (
    <li className="admin__todo-row">
      <RowText title={titleWithYear(item.title, item.year)} line={matchLine(item)} />
      <div className="admin__actions admin__todo-actions">
        {guess ? (
          <>
            {/* aria-disabled, not disabled: the button keeps focus while its request runs; the hook ignores clicks. */}
            <ActionWithHint
              hint={buttons.confirmGuess.hint}
              aria-disabled={busy || undefined}
              onClick={() => onConfirm(item)}
            >
              {buttons.confirmGuess.label(guess.title, guess.year)}
            </ActionWithHint>
            <Button className="admin__todo-quiet" onClick={find}>
              {buttons.findFilm.label}
            </Button>
          </>
        ) : (
          <ActionWithHint hint={buttons.findFilm.hint} onClick={find}>
            {buttons.findFilm.label}
          </ActionWithHint>
        )}
      </div>
      {problem ? (
        <p className="admin__todo-problem" role="alert">
          {problem}
        </p>
      ) : null}
    </li>
  );
}

/** A film with several files: "Sort out editions…" with the scanner's suggestion as the line. */
function FilesRow({ item, onSortOut }) {
  return (
    <li className="admin__todo-row">
      <RowText title={titleWithYear(item.title, item.year)} line={filesLine(item)} />
      <div className="admin__actions admin__todo-actions">
        <ActionWithHint
          hint={buttons.sortEditions.hint}
          onClick={(event) => onSortOut(item, event.currentTarget)}
        >
          {buttons.sortEditions.label}
        </ActionWithHint>
      </div>
    </li>
  );
}

/** A subtitle row, read-only here: the group's footer leads to Subtitles, where the fixing happens. */
function SubtitleRow({ row, line }) {
  return (
    <li className="admin__todo-row">
      <RowText title={titleWithYear(row.title, row.year)} line={line} />
    </li>
  );
}

function renderRow(group, row, props) {
  switch (group.key) {
    case 'pickFilm':
      return (
        <MatchRow
          key={`${row.kind}-${row.filmId}-${row.files.join('|')}`}
          item={row}
          busy={props.confirming.includes(row.filmId)}
          problem={props.problems[row.filmId]}
          onConfirm={props.onConfirm}
          onFindFilm={props.onFindFilm}
        />
      );
    case 'severalFiles':
      return (
        <FilesRow
          key={`${row.kind}-${row.filmId}-${row.files.join('|')}`}
          item={row}
          onSortOut={props.onSortOut}
        />
      );
    case 'fullMissing':
      return <SubtitleRow key={row.filmId} row={row} line={fullLine(row)} />;
    default:
      return <SubtitleRow key={row.filmId} row={row} line={forcedLine(row)} />;
  }
}

/**
 * One To do group: a card with its heading and why-line, up to five rows and a footer. The two film groups expand in
 * place ("Show all {n}", then "Show fewer", on the same button so focus stays put); the two subtitle groups link to
 * Subtitles. `group` is `{ key, rows, total }` from `buildTodoGroups`.
 */
export default function AdminTodoGroup({
  group,
  confirming,
  problems,
  onConfirm,
  onFindFilm,
  onSortOut,
}) {
  const [expanded, setExpanded] = useState(false);
  const copy = todoGroups[COPY_KEY[group.key]];
  const headingId = `admin-todo-${group.key}`;
  const footer = footerFor(group, expanded);
  return (
    <section className="admin__todo-group" aria-labelledby={headingId}>
      <div className="admin__todo-head">
        <h2 id={headingId} className="admin__todo-heading">
          {copy.heading}
        </h2>
        <p className="admin__todo-why">{copy.why}</p>
      </div>
      <ul className="admin__todo-rows">
        {visibleRows(group, expanded).map((row) =>
          renderRow(group, row, { confirming, problems, onConfirm, onFindFilm, onSortOut }),
        )}
      </ul>
      {footer ? (
        <div className="admin__todo-foot">
          <span className="admin__todo-count admin__todo-count--wide">
            {footerDesktop(footer.shown, footer.total)}
          </span>
          <span className="admin__todo-count admin__todo-count--phone">
            {footerPhone(footer.shown, footer.total)}
          </span>
          {footer.control === 'subtitles' ? (
            <Link to="/admin/subtitles" className="admin__todo-more">
              {todoFooter.allInSubtitles}
            </Link>
          ) : (
            <button
              type="button"
              className="admin__todo-more"
              onClick={() => setExpanded((open) => !open)}
            >
              {expanded ? todoFooter.showFewer : showAll(footer.total)}
            </button>
          )}
        </div>
      ) : null}
    </section>
  );
}
