import { useCallback } from 'react';
import { useAdminContext } from '../state/adminContext.js';
import { buttons, playingNow } from '../state/adminCopy.js';
import { usePlayingNow } from '../state/usePlayingNow.js';
import { ActionWithHint } from './ActionWithHint.jsx';
import { SkeletonBlock } from './Skeleton.jsx';
import { ErrorState } from './StateMessages.jsx';

const rowId = (playbackId) => `playing-${playbackId}`;

/**
 * Once a stopped row is hidden, focus goes to the section title when it was on that row (or nowhere): the button it
 * was on is leaving the page. Focus that has moved somewhere else on its own is left alone.
 */
function leaveRow(row) {
  const active = document.activeElement;
  if (
    !active ||
    active === document.body ||
    document.getElementById(rowId(row.playbackId))?.contains(active)
  ) {
    document.getElementById('admin-section-title')?.focus();
  }
}

function Rows({ rows, stopping, failures, onStop }) {
  if (rows.length === 0) return <p className="admin__text">{playingNow.empty}</p>;
  return (
    <ul className="admin__list" aria-label={playingNow.title}>
      {rows.map((row) => (
        <li key={row.playbackId} id={rowId(row.playbackId)} className="admin__item">
          <div className="admin__item-head">
            <span className="admin__item-title">{row.filmTitle}</span>
          </div>
          <div className="admin__item-files admin__mono">{row.detail}</div>
          {row.sessionId ? (
            <div className="admin__actions">
              <ActionWithHint
                hint={row.hint}
                aria-label={`${buttons.stopStream.label}: ${row.filmTitle} (${row.device})`}
                aria-disabled={stopping[row.playbackId] ? 'true' : undefined}
                aria-busy={stopping[row.playbackId] ? 'true' : undefined}
                onClick={() => onStop(row)}
              >
                {buttons.stopStream.label}
              </ActionWithHint>
            </div>
          ) : null}
          {failures[row.playbackId] ? (
            <p className="admin__notice" role="status">
              {failures[row.playbackId]}
            </p>
          ) : null}
        </li>
      ))}
    </ul>
  );
}

/**
 * Playback: "Now playing", from the summary's `playingNow` (direct play included), with "Stop this stream" where an
 * HLS session of that edition is live. `transcoding` is the section's one read of the live sessions, shared with the
 * Conversion card.
 */
export default function PlayingNowPanel({ transcoding }) {
  const { adminSummary } = useAdminContext();
  const { summary, error, reload: reloadSummary } = adminSummary;
  const onLeft = useCallback(leaveRow, []);
  const { rows, stopping, failures, stop } = usePlayingNow({
    entries: summary?.playingNow,
    sessions: transcoding.transcoding?.sessions,
    reloadSessions: transcoding.reload,
    reloadSummary,
    onLeft,
  });
  let body;
  if (!summary && !error) {
    body = (
      <div role="status" aria-label="Loading">
        <SkeletonBlock style={{ height: 64 }} />
      </div>
    );
  } else if (!summary) {
    body = <ErrorState onRetry={reloadSummary}>{playingNow.loadError}</ErrorState>;
  } else {
    body = <Rows rows={rows} stopping={stopping} failures={failures} onStop={stop} />;
  }
  return (
    <section className="admin__card" aria-labelledby="admin-playing-now-title">
      <div className="admin__head">
        <h2 id="admin-playing-now-title" className="admin__title">
          {playingNow.title}
        </h2>
        {summary && rows.length > 0 ? (
          <span className="admin__text">{playingNow.streams(rows.length)}</span>
        ) : null}
      </div>
      {body}
    </section>
  );
}
