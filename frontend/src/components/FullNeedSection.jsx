import { useMemo, useState } from 'react';
import { OWNER_CHOICES, PAGE_SIZE, needTitle } from '../state/forcedNeed.js';
import {
  DEFAULT_FULL_NEED_FILTER,
  FULL_NEED_FILTERS,
  audioNote,
  filterFullNeed,
  fullNeedCounts,
  fullNeedReason,
  fullNeedWords,
} from '../state/fullNeed.js';
import { useFullNeed } from '../state/useFullNeed.js';
import { Button } from './Button.jsx';
import { SkeletonBlock } from './Skeleton.jsx';
import { ErrorState } from './StateMessages.jsx';

function FullNeedRow({ row, problem, saving, onChoose }) {
  const title = needTitle(row);
  const reason = fullNeedReason(row);
  const audio = audioNote(row);
  return (
    <li className="admin__item" aria-busy={saving}>
      <div className="admin__item-head">
        <span className="admin__item-title">{title}</span>
        <span className="admin__tag">{fullNeedWords(row.need)}</span>
      </div>
      {reason ? <div className="admin__item-detail">{reason}</div> : null}
      {audio ? <div className="admin__item-detail">{audio}</div> : null}
      {problem ? (
        <p className="admin__warning" role="status">
          {problem}
        </p>
      ) : null}
      <div
        className="segmented subs__segmented"
        role="group"
        aria-label={`Full subtitles for ${title}`}
      >
        {OWNER_CHOICES.map((choice) => (
          <button
            key={choice.label}
            type="button"
            className="segmented__btn"
            aria-pressed={row.ownerChoice === choice.value}
            aria-label={`Full subtitles ${choice.label.toLowerCase()}: ${title}`}
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
 * Admin → Subtitles → "Full subtitles": the films not made in English (or chosen by the owner) with whether they need
 * full English subtitles and why, filtered by chip (Needed first), shown 100 at a time, with the owner's choice per
 * film. `refreshKey` changes when the library may have changed, and the list is read again.
 */
export default function FullNeedSection({ refreshKey }) {
  const need = useFullNeed(refreshKey);
  const { rows, loading, error, reload } = need;
  const [filter, setFilter] = useState(DEFAULT_FULL_NEED_FILTER);
  const [limit, setLimit] = useState(PAGE_SIZE);
  const counts = useMemo(() => (rows ? fullNeedCounts(rows) : null), [rows]);
  const shown = useMemo(() => (rows ? filterFullNeed(rows, filter) : []), [rows, filter]);

  let body;
  if (!rows) {
    body = error ? (
      <ErrorState onRetry={reload}>Couldn&rsquo;t load which films need full subtitles.</ErrorState>
    ) : (
      <div role="status" aria-label="Loading which films need full subtitles">
        <SkeletonBlock style={{ height: 120 }} />
      </div>
    );
  } else {
    body = (
      <>
        {error ? (
          <ErrorState onRetry={reload}>
            Couldn&rsquo;t refresh which films need full subtitles.
          </ErrorState>
        ) : null}
        <div className="segmented subs__segmented" role="group" aria-label="Show full subtitles">
          {FULL_NEED_FILTERS.map((f) => (
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
          <ul className="admin__list" aria-label="Films for full subtitles" aria-busy={loading}>
            {shown.slice(0, limit).map((row) => (
              <FullNeedRow
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
      <h3 className="admin__subtitle">Full subtitles</h3>
      {body}
    </div>
  );
}
