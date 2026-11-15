import { useMemo, useState } from 'react';
import { adminApi } from '../api/resources.js';
import { subtitlesPage } from '../state/adminCopy.js';
import {
  FILE_FILTERS,
  alignmentSummary,
  checkSummary,
  fileFilterCounts,
  filterSidecars,
  formatOffset,
  isDownload,
  isFull,
  originLine,
  ownerTimingLine,
  placement,
  reportLine,
  servedLine,
  sidecarGroups,
  takeFiles,
  warningsFor,
} from '../state/subtitleAdmin.js';
import { useResource } from '../state/useResource.js';
import { Button } from './Button.jsx';
import { SkeletonBlock } from './Skeleton.jsx';
import { ErrorState } from './StateMessages.jsx';

/** How many files are listed before "Show more". */
export const FILES_PAGE = 50;

/** What fitting the file to the film's own English track says, and "Timing set by you"; never colour alone. */
function TimingLine({ item }) {
  const summary = alignmentSummary(item);
  const owner = ownerTimingLine(item);
  if (!summary && !owner) return null;
  return (
    <div className={`subs__check subs__check--${summary ? summary.kind : 'unchecked'}`}>
      {summary ? (
        <>
          <span className="subs__check-label">{summary.label}</span>
          {summary.note ? <p className="subs__check-note">{summary.note}</p> : null}
          {summary.lines.length > 0 ? (
            <ul className="subs__check-reasons" aria-label={`${summary.label}: details`}>
              {summary.lines.map((line) => (
                <li key={line}>{line}</li>
              ))}
            </ul>
          ) : null}
        </>
      ) : null}
      {owner ? <p className="subs__check-note">{owner}</p> : null}
    </div>
  );
}

/** What the comparison with OpenSubtitles says about a file, as words (never colour alone). */
function CheckLine({ item }) {
  const summary = checkSummary(item);
  if (!summary) return null;
  return (
    <div className={`subs__check subs__check--${summary.kind}`}>
      <span className="subs__check-label">{summary.label}</span>
      {summary.kind === 'unchecked' ? <p className="subs__check-note">{summary.lines[0]}</p> : null}
      {summary.kind !== 'unchecked' && summary.lines.length > 0 ? (
        <ul className="subs__check-reasons" aria-label={`${summary.label}: details`}>
          {summary.lines.map((line) => (
            <li key={line}>{line}</li>
          ))}
        </ul>
      ) : null}
      {summary.reference ? <p className="subs__check-note">{summary.reference}</p> : null}
    </div>
  );
}

function Item({ item }) {
  const download = isDownload(item);
  const origin = originLine(item);
  const served = servedLine(item);
  return (
    <li className="admin__item">
      <div className="admin__item-head">
        {download ? (
          <>
            <span className="admin__item-title">{origin.title}</span>
            {origin.release ? (
              <span className="admin__mono subs__release">{origin.release}</span>
            ) : null}
          </>
        ) : (
          <span className="admin__item-title admin__mono">{origin.title}</span>
        )}
        {isFull(item) ? <span className="admin__tag">Full</span> : null}
        {item.verified && !isFull(item) ? <span className="admin__tag">Checked</span> : null}
        {item.served ? <span className="admin__tag">Playing</span> : null}
      </div>
      {served ? <p className="subs__served">{served}</p> : null}
      <div className="admin__item-detail">{placement(item)}</div>
      <div className="admin__item-files admin__mono">{reportLine(item)}</div>
      {item.filmId != null && item.offsetMs !== 0 ? (
        <p className="admin__item-detail">
          {subtitlesPage.files.offset(formatOffset(item.offsetMs))}
        </p>
      ) : null}
      <CheckLine item={item} />
      <TimingLine item={item} />
      {warningsFor(item).map((warning) => (
        <p key={warning} className="admin__warning" role="status">
          {warning}
        </p>
      ))}
    </li>
  );
}

/**
 * Admin: the English subtitle files beside the films, forced (checked and played as the forced subtitle) and full
 * (labelled "Full": found and served, with no checks), as a read-only diagnostic. They are grouped (Unmatched,
 * Conflicts, Missing, Matched); each shows what it is, where it sits, how it was read and whether it is served or
 * checked. Nothing here changes a file: that is done from the film's page (Fix…) and the player (8b-2). At most
 * {@link FILES_PAGE} files are listed until "Show more". `version` makes the list read again.
 */
export default function SubtitlesPanel({ version }) {
  const {
    data: items,
    error,
    loading,
    reload,
  } = useResource(`admin-subtitles:${version}`, (options) => adminApi.subtitles(options));
  const [filter, setFilter] = useState('all');
  const [limit, setLimit] = useState(FILES_PAGE);
  const chooseFilter = (key) => {
    setFilter(key);
    setLimit(FILES_PAGE);
  };
  const counts = useMemo(() => (items ? fileFilterCounts(items) : null), [items]);
  const shown = useMemo(() => (items ? filterSidecars(items, filter) : []), [items, filter]);
  let body;
  if (loading && !items) {
    body = (
      <div role="status" aria-label="Loading">
        <SkeletonBlock style={{ height: 120 }} />
      </div>
    );
  } else if (error && !items) {
    body = <ErrorState onRetry={reload}>Couldn&rsquo;t load the subtitle files.</ErrorState>;
  } else if (!items || items.length === 0) {
    body = (
      <p className="admin__text">
        No subtitle files found. They are named like Film.en.forced.srt (forced) or Film.en.srt
        (full) and sit beside the film.
      </p>
    );
  } else {
    // Grouped first, then cut: the first page holds the first files as they are drawn, not as the server listed them.
    const groups = takeFiles(sidecarGroups(shown), limit);
    body = (
      <>
        <div className="segmented subs__segmented" role="group" aria-label="Show subtitle files">
          {FILE_FILTERS.map((f) => (
            <button
              key={f.key}
              type="button"
              className="segmented__btn"
              aria-pressed={filter === f.key}
              onClick={() => chooseFilter(f.key)}
            >
              {f.label} ({counts[f.key]})
            </button>
          ))}
        </div>
        {groups.length === 0 ? <p className="admin__text">No files here.</p> : null}
        {groups.map((group) => (
          <div key={group.key} className="subs__group">
            <h3 className="admin__subtitle">
              {group.title} ({group.items.length})
            </h3>
            <ul className="admin__list" aria-label={`${group.title} subtitles`}>
              {group.items.map((item) => (
                <Item key={item.id} item={item} />
              ))}
            </ul>
          </div>
        ))}
        {shown.length > limit ? (
          <div className="admin__actions">
            <Button onClick={() => setLimit(limit + FILES_PAGE)}>
              {subtitlesPage.files.showMore(shown.length - limit)}
            </Button>
          </div>
        ) : null}
      </>
    );
  }
  // A failed refresh keeps the list that is there and says so, with a way to try again.
  const refreshFailure =
    error && items ? (
      <ErrorState onRetry={reload}>Couldn&rsquo;t refresh the subtitle files.</ErrorState>
    ) : null;
  return (
    <section className="admin__card" aria-labelledby="admin-subtitles-title">
      <div className="admin__head">
        <h2 id="admin-subtitles-title" className="admin__title">
          {subtitlesPage.files.heading}
        </h2>
      </div>
      {refreshFailure}
      {body}
    </section>
  );
}
