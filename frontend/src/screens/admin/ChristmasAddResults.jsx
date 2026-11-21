import { POSTER_SIZE_THUMB } from '../../api/images.js';
import Art from '../../components/Art.jsx';
import { Button } from '../../components/Button.jsx';
import { addResultRow, yearSuffix } from '../../state/christmas.js';
import { filmCount } from '../../state/useSearchCombobox.js';

export const SEARCH_LOADING_TEXT = 'Loading…';
export const SEARCH_ERROR_TEXT = 'Search isn’t available right now.';
export const STATES_FAILED_TEXT =
  'Couldn’t check which of these are in Christmas week. Change the search to try again.';

/** The line above the list: how many films match (or why there is no list). */
export function headText({ ready, status, query, rows, total }) {
  if (status === 'loading') return SEARCH_LOADING_TEXT;
  if (!ready) return SEARCH_ERROR_TEXT;
  if (total === 0) return `No films in your library match “${query.trim()}”.`;
  return total > rows.length
    ? `Showing ${rows.length} of ${filmCount(total)} in your library`
    : `${filmCount(total)} in your library`;
}

function ResultRow({ row, busy, onAct, buttonRef }) {
  const view = addResultRow(row.state);
  return (
    <li id={row.id} className="xmas-add-row">
      <Art
        kind="poster"
        path={row.posterPath}
        id={row.filmId}
        title={row.title}
        size={POSTER_SIZE_THUMB}
        showTitle={false}
        className="xmas-add-row__poster"
      />
      <div className="xmas-add-row__text">
        <p className="xmas-add-row__title">
          {row.title}
          {row.year ? <span className="xmas-year">{yearSuffix(row)}</span> : null}
        </p>
        {view?.chip ? (
          <span className={`xmas-chip xmas-chip--${view.chip.variant}`}>{view.chip.label}</span>
        ) : null}
        {view?.line ? <p className="xmas-add-row__line">{view.line}</p> : null}
      </div>
      {view ? (
        <Button
          ref={buttonRef}
          className="xmas-row__action"
          aria-label={`${view.action.label} ${row.title}`}
          aria-disabled={busy || undefined}
          onClick={busy ? undefined : (event) => onAct(row, event.currentTarget)}
        >
          {view.action.label}
        </Button>
      ) : null}
    </li>
  );
}

/**
 * The results of "Add a film", for the desktop panel and the phone sheet: a count line and a plain list (the rows hold
 * buttons, so it is not a listbox). Each row shows its state and one button (`onAct(row, opener)`), blocked while a
 * write for that TMDB id is in flight; a row with no state yet has no button. `registerButton(filmId)` is the ref
 * callback of a row's button.
 */
export default function ChristmasAddResults({
  head,
  rows,
  busy,
  statesFailed,
  onAct,
  registerButton,
}) {
  return (
    <>
      {/* The one live region of the results: it stays mounted, so a change of its text is heard. */}
      <div role="status">
        <p className="xmas-add__head">{head}</p>
        {statesFailed ? <p className="xmas-add__failed">{STATES_FAILED_TEXT}</p> : null}
      </div>
      {rows.length === 0 ? null : (
        <ul aria-label="Films in your library" className="xmas-add__list">
          {rows.map((row) => (
            <ResultRow
              key={row.filmId}
              row={row}
              busy={row.tmdbId !== null && busy.has(row.tmdbId)}
              onAct={onAct}
              buttonRef={registerButton(row.filmId)}
            />
          ))}
        </ul>
      )}
    </>
  );
}
