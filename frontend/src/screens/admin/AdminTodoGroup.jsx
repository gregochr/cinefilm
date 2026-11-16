import { useId, useState } from 'react';
import { Link } from 'react-router-dom';
import { ActionWithHint } from '../../components/ActionWithHint.jsx';
import { Button } from '../../components/Button.jsx';
import {
  buttons,
  footerDesktop,
  footerPhone,
  todoFooter,
  todoGroups,
  todoRow,
} from '../../state/adminCopy.js';
import {
  forcedRowLine,
  foreignLine,
  pickerAction,
  pickerButton,
} from '../../state/adminSubtitles.js';
import {
  bestGuess,
  filesLine,
  footerFor,
  matchLine,
  TODO_ROW_CAP,
  visibleRows,
} from '../../state/adminTodo.js';
import RowText from './AdminRowText.jsx';

// The group keys of `adminTodo.js` (they follow the summary's `todo` figures) and their entries in `todoGroups`.
const COPY_KEY = {
  pickFilm: 'pickFilm',
  severalFiles: 'severalFiles',
  fullMissing: 'englishSubtitlesMissing',
  forcedQuestion: 'forcedNotFound',
};

/** A film the scanner could not match: "Yes, it's …" when the server is confident, and always "Find the film…". */
function MatchRow({ item, busy, problem, onConfirm, onFindFilm }) {
  const guess = item.confident ? bestGuess(item) : null;
  // While this row's post runs both buttons are aria-disabled (still focusable); a click on either does nothing.
  const find = (event) => {
    if (!busy) onFindFilm(item, event.currentTarget);
  };
  const busyProps = { 'aria-disabled': busy || undefined };
  // The row's title describes its buttons, so each names its film while the visible label stays the start of its name.
  const titleId = useId();
  return (
    <li className="admin__todo-row">
      <RowText row={item} line={matchLine(item)} titleId={titleId} />
      <div className="admin__actions admin__todo-actions">
        {guess ? (
          <>
            {/* aria-disabled, not disabled: the button keeps focus while its request runs; the hook ignores clicks. */}
            <ActionWithHint
              hint={buttons.confirmGuess.hint}
              describedBy={titleId}
              {...busyProps}
              onClick={(event) => onConfirm(item, event.currentTarget)}
            >
              {buttons.confirmGuess.label(guess.title, guess.year)}
            </ActionWithHint>
            <Button
              className="admin__todo-quiet"
              aria-describedby={titleId}
              {...busyProps}
              onClick={find}
            >
              {buttons.findFilm.label}
            </Button>
          </>
        ) : (
          <ActionWithHint
            hint={buttons.findFilm.hint}
            describedBy={titleId}
            {...busyProps}
            onClick={find}
          >
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
  const titleId = useId();
  return (
    <li className="admin__todo-row">
      <RowText row={item} line={filesLine(item)} titleId={titleId} />
      <div className="admin__actions admin__todo-actions">
        <ActionWithHint
          hint={buttons.sortEditions.hint}
          describedBy={titleId}
          onClick={(event) => onSortOut(item, event.currentTarget)}
        >
          {buttons.sortEditions.label}
        </ActionWithHint>
      </div>
    </li>
  );
}

/**
 * A subtitle row: the group's footer leads to Subtitles, where the other fixing happens, but the search button is
 * here too (the same one the Subtitles tab shows) and opens the OpenSubtitles picker for the row's film. A row with
 * no `editionId` has nothing to search for and no button.
 */
function SubtitleRow({ row, line, kind, onFindSubtitles }) {
  const action = pickerAction(row, kind);
  const button = action ? pickerButton(action, row) : null;
  const titleId = useId();
  return (
    <li className="admin__todo-row">
      <RowText row={row} line={line} titleId={titleId} />
      {button ? (
        <div className="admin__actions admin__todo-actions">
          <ActionWithHint
            hint={button.hint}
            describedBy={titleId}
            aria-haspopup="dialog"
            onClick={(event) => onFindSubtitles(row, kind, event.currentTarget)}
          >
            {button.label}
          </ActionWithHint>
        </div>
      ) : null}
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
      return (
        <SubtitleRow
          key={row.filmId}
          row={row}
          line={foreignLine(row)}
          kind="full"
          onFindSubtitles={props.onFindSubtitles}
        />
      );
    default:
      return (
        <SubtitleRow
          key={row.filmId}
          row={row}
          line={forcedRowLine(row)}
          kind="forced"
          onFindSubtitles={props.onFindSubtitles}
        />
      );
  }
}

/**
 * One To do group: a card with its heading and why-line, up to five rows and a footer. The two film groups expand in
 * place ("Show all {n}", then "Show fewer", on the same button so focus stays put); the two subtitle groups link to
 * Subtitles. `group` is `{ key, rows, total }` from `buildTodoGroups`. `onFindSubtitles(row, kind, opener)` opens the
 * OpenSubtitles picker from a subtitle row (`kind` is `full` or `forced`).
 */
export default function AdminTodoGroup({
  group,
  confirming,
  problems,
  onConfirm,
  onFindFilm,
  onSortOut,
  onFindSubtitles,
}) {
  const [open, setOpen] = useState(false);
  // A group that has shrunk to the cap or below has nothing to fold: the next time it grows it starts folded.
  const expanded = open && group.total > TODO_ROW_CAP;
  if (open && !expanded) setOpen(false);
  const copy = todoGroups[COPY_KEY[group.key]];
  const headingId = `admin-todo-${group.key}`;
  const listId = `admin-todo-rows-${group.key}`;
  const footer = footerFor(group, expanded);
  return (
    <section className="admin__todo-group" aria-labelledby={headingId}>
      <div className="admin__todo-head">
        <h2 id={headingId} className="admin__todo-heading">
          {copy.heading}
        </h2>
        <p className="admin__todo-why">{copy.why}</p>
        <p className="admin__todo-group-count">
          {(group.key === 'pickFilm' ? todoRow.groupCount.pickFilm : todoRow.groupCount.other)(
            group.total,
          )}
        </p>
      </div>
      <ul id={listId} className="admin__todo-rows">
        {visibleRows(group, expanded).map((row) =>
          renderRow(group, row, {
            confirming,
            problems,
            onConfirm,
            onFindFilm,
            onSortOut,
            onFindSubtitles,
          }),
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
            <Link
              to={group.key === 'forcedQuestion' ? '/admin/subtitles#forced' : '/admin/subtitles'}
              className="admin__todo-more"
            >
              {todoFooter.allInSubtitles}{' '}
              <span className="admin__todo-wide">{todoFooter.arrow}</span>
            </Link>
          ) : (
            <button
              type="button"
              className="admin__todo-more"
              aria-expanded={expanded}
              aria-controls={listId}
              onClick={() => setOpen(!expanded)}
            >
              {expanded ? (
                todoFooter.showFewer
              ) : (
                <>
                  {todoFooter.showAll} <span className="admin__todo-wide">{footer.total}</span>
                </>
              )}
            </button>
          )}
        </div>
      ) : null}
    </section>
  );
}
