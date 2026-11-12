import { useMemo, useState } from 'react';
import {
  DEFAULT_NEED_FILTER,
  NEED_FILTERS,
  OWNER_CHOICES,
  PAGE_SIZE,
  filterRows,
  needCounts,
  needTitle,
  needWords,
  reasonText,
  searchNote,
  subtitleNote,
} from '../state/forcedNeed.js';
import { useForcedNeed } from '../state/useForcedNeed.js';
import { Button } from './Button.jsx';
import { SkeletonBlock } from './Skeleton.jsx';
import { ErrorState } from './StateMessages.jsx';

function NeedRow({ row, problem, saving, onChoose }) {
  const title = needTitle(row);
  const note = subtitleNote(row);
  const reason = reasonText(row);
  const notSearched = searchNote(row);
  return (
    <li className="admin__item" aria-busy={saving}>
      <div className="admin__item-head">
        <span className="admin__item-title">{title}</span>
        <span className="admin__tag">{needWords(row.need)}</span>
        {note ? <span className="admin__text">{note}</span> : null}
      </div>
      {reason ? <div className="admin__item-detail">{reason}</div> : null}
      {notSearched ? <div className="admin__item-detail">{notSearched}</div> : null}
      {problem ? (
        <p className="admin__warning" role="status">
          {problem}
        </p>
      ) : null}
      <div
        className="segmented subs__segmented"
        role="group"
        aria-label={`Forced subtitles for ${title}`}
      >
        {OWNER_CHOICES.map((choice) => (
          <button
            key={choice.label}
            type="button"
            className="segmented__btn"
            aria-pressed={row.ownerChoice === choice.value}
            aria-label={`${choice.label}: ${title}`}
            onClick={() => onChoose(row.filmId, choice.value)}
          >
            {choice.label}
          </button>
        ))}
      </div>
    </li>
  );
}

/**
 * Admin → Subtitles → "Which films need them": every film with whether it needs forced subtitles and why, filtered
 * by chip (Needed, none yet is the first view), shown 100 at a time, with the owner's choice per film. `refreshKey`
 * changes when the subtitle files may have changed, and the list is read again.
 */
export default function ForcedNeedSection({ refreshKey }) {
  const need = useForcedNeed(refreshKey);
  const { rows, loading, error, reload } = need;
  const [filter, setFilter] = useState(DEFAULT_NEED_FILTER);
  const [limit, setLimit] = useState(PAGE_SIZE);
  const counts = useMemo(() => (rows ? needCounts(rows) : null), [rows]);
  const shown = useMemo(() => (rows ? filterRows(rows, filter) : []), [rows, filter]);

  let body;
  if (!rows) {
    body = error ? (
      <ErrorState onRetry={reload}>Couldn&rsquo;t load which films need them.</ErrorState>
    ) : (
      <div role="status" aria-label="Loading which films need them">
        <SkeletonBlock style={{ height: 120 }} />
      </div>
    );
  } else {
    body = (
      <>
        {error ? (
          <ErrorState onRetry={reload}>Couldn&rsquo;t refresh which films need them.</ErrorState>
        ) : null}
        <div className="segmented subs__segmented" role="group" aria-label="Show films">
          {NEED_FILTERS.map((f) => (
            <button
              key={f.key}
              type="button"
              className="segmented__btn"
              aria-pressed={filter === f.key}
              onClick={() => {
                setFilter(f.key);
                setLimit(PAGE_SIZE);
              }}
            >
              {f.label} ({counts[f.key]})
            </button>
          ))}
        </div>
        {shown.length === 0 ? (
          <p className="admin__text">No films here.</p>
        ) : (
          <ul className="admin__list" aria-label="Films" aria-busy={loading}>
            {shown.slice(0, limit).map((row) => (
              <NeedRow
                key={row.filmId}
                row={row}
                problem={need.problems[row.filmId]}
                saving={Boolean(need.saving[row.filmId])}
                onChoose={need.choose}
              />
            ))}
          </ul>
        )}
        {shown.length > limit ? (
          <div className="admin__actions">
            <Button onClick={() => setLimit(limit + PAGE_SIZE)}>
              Show more ({shown.length - limit} left)
            </Button>
          </div>
        ) : null}
      </>
    );
  }

  return (
    <div className="subs__need">
      <h3 className="admin__subtitle">Which films need them</h3>
      {body}
    </div>
  );
}
