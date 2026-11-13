import { useMemo, useState } from 'react';
import { OWNER_CHOICES, PAGE_SIZE, needTitle } from '../state/forcedNeed.js';
import {
  FULL_NEED_FILTERS,
  audioNote,
  filterFullNeed,
  fullNeedCounts,
  fullNeedReason,
  fullNeedWords,
  fullSubtitleLine,
  initialFullNeedFilter,
} from '../state/fullNeed.js';
import { useFullNeed } from '../state/useFullNeed.js';
import { Button } from './Button.jsx';
import { SkeletonBlock } from './Skeleton.jsx';
import { ErrorState } from './StateMessages.jsx';

function FullNeedRow({ row, problem, saving, onChoose, onShowFiles }) {
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
      <div className="admin__item-detail">{fullSubtitleLine(row)}</div>
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
      {onShowFiles ? (
        <div className="admin__actions">
          <Button
            aria-label={`Show the full subtitle files of ${title}`}
            onClick={() => onShowFiles({ filmId: row.filmId, title })}
          >
            Show files
          </Button>
        </div>
      ) : null}
    </li>
  );
}

/**
 * Admin → Subtitles → "Full subtitles": the films not made in English (or chosen by the owner) with whether they need
 * full English subtitles and why, filtered by chip (Needed first), shown 100 at a time, with the owner's choice per
 * film, and a button that shows that film's full subtitle files in the file list (`onShowFiles({ filmId, title })`).
 * `refreshKey` changes when the library may have changed, and the list is read again. The chip it opens on is picked
 * once, when the first list arrives (see {@link initialFullNeedFilter}); a refresh never moves it.
 */
export default function FullNeedSection({ refreshKey, onShowFiles }) {
  const need = useFullNeed(refreshKey);
  const { rows, loading, error, reload } = need;
  const [chosen, setChosen] = useState(null);
  const [limit, setLimit] = useState(PAGE_SIZE);
  const counts = useMemo(() => (rows ? fullNeedCounts(rows) : null), [rows]);
  // Set while rendering, once: the first list decides the opening chip, and the owner's clicks replace it.
  if (chosen === null && counts) setChosen(initialFullNeedFilter(counts));
  const filter = chosen ?? initialFullNeedFilter(counts ?? {});
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
                setChosen(f.key);
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
                onShowFiles={onShowFiles}
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
