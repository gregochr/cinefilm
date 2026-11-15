import { useState } from 'react';
import { playbackLog as copy } from '../state/adminCopy.js';
import { usePlaybackLog } from '../state/usePlaybackLog.js';
import { Button } from './Button.jsx';
import PlaybackLogRow from './PlaybackLogRow.jsx';
import { SkeletonBlock } from './Skeleton.jsx';
import { ErrorState } from './StateMessages.jsx';

function Table({ rows, open, onToggle }) {
  return (
    <table className="plog__table" aria-label={copy.tableLabel}>
      <thead>
        <tr>
          {copy.columns.map((name) => (
            <th key={name} scope="col">
              {name}
            </th>
          ))}
          <th scope="col">
            <span className="plog__sr">{copy.actionsColumn}</span>
          </th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <PlaybackLogRow
            key={row.playbackId}
            row={row}
            open={open.has(row.playbackId)}
            onToggle={() => onToggle(row.playbackId)}
          />
        ))}
      </tbody>
    </table>
  );
}

/**
 * Admin Playback: every playback newest first, 50 to a page, filtered by the server (All, Films only, With
 * problems). Details open under a row; which rows are open is this card's own state.
 */
export default function PlaybackLogPanel() {
  const log = usePlaybackLog();
  const [open, setOpen] = useState(() => new Set());
  const toggle = (id) =>
    setOpen((previous) => {
      const next = new Set(previous);
      if (!next.delete(id)) next.add(id);
      return next;
    });
  let body;
  if (log.loading) {
    body = (
      <div role="status" aria-label={copy.loadingLabel}>
        <SkeletonBlock style={{ height: 160 }} />
      </div>
    );
  } else if (log.error) {
    body = <ErrorState onRetry={log.reload}>{copy.loadError}</ErrorState>;
  } else if (log.rows.length === 0) {
    body = <p className="admin__text">{copy.empty[log.filter]}</p>;
  } else {
    body = (
      <>
        <Table rows={log.rows} open={open} onToggle={toggle} />
        <div className="plog__more">
          <span className="admin__text">{copy.showing(log.rows.length, log.total)}</span>
          {log.moreError ? (
            <span className="plog-error" role="alert">
              {copy.moreError}
            </span>
          ) : null}
          {log.hasMore ? (
            <Button aria-disabled={log.loadingMore || undefined} onClick={log.more}>
              {copy.showMore}
            </Button>
          ) : null}
        </div>
      </>
    );
  }
  return (
    <section className="admin__card plog" aria-labelledby="admin-playback-log-title">
      <div className="plog__head">
        <h2 id="admin-playback-log-title" className="admin__title">
          {copy.title}
        </h2>
        <span className="admin__text">{copy.why}</span>
        <div className="segmented plog__filters" role="group" aria-label={copy.filtersLabel}>
          {copy.filters.map(({ key, label }) => (
            <button
              key={key}
              type="button"
              className="segmented__btn"
              aria-pressed={log.filter === key}
              onClick={() => log.setFilter(key)}
            >
              {label}
            </button>
          ))}
        </div>
      </div>
      {body}
    </section>
  );
}
