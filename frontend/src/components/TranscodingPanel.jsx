import { useTranscoding } from '../state/useTranscoding.js';
import { formatScanTime } from '../state/scanFormat.js';
import {
  aheadText,
  encoderLabel,
  formatBytes,
  formatSpeed,
  indexText,
} from '../state/transcodingFormat.js';
import { rungName } from '../player/ladder.js';
import { Button } from './Button.jsx';
import { SkeletonBlock } from './Skeleton.jsx';
import { ErrorState } from './StateMessages.jsx';

function Sessions({ sessions, onStop }) {
  if (sessions.length === 0) return <p className="admin__text">Nothing is being transcoded.</p>;
  return (
    <ul className="admin__list" aria-label="Playback sessions">
      {sessions.map((session) => (
        <li key={session.id} className="admin__item">
          <div className="admin__item-head">
            <span className="admin__item-title">{session.filmTitle}</span>
            <span className="admin__tag">{rungName(session.rung)}</span>
          </div>
          <div className="admin__item-files admin__mono">
            {formatSpeed(session.speed)} · {aheadText(session.segmentsAhead)} · started{' '}
            {formatScanTime(session.startedAt)}
          </div>
          <div className="admin__actions">
            <Button onClick={() => onStop(session.id)} aria-label={`Stop ${session.filmTitle}`}>
              Stop
            </Button>
          </div>
        </li>
      ))}
    </ul>
  );
}

/** Admin: the live encoder state and the playback sessions (plan: Admin, Transcoding panel). */
export default function TranscodingPanel() {
  const { transcoding, error, loading, notice, stop, reload } = useTranscoding();
  let body;
  if (loading && !transcoding) {
    body = (
      <div role="status" aria-label="Loading">
        <SkeletonBlock style={{ height: 120 }} />
      </div>
    );
  } else if (error && !transcoding) {
    body = <ErrorState onRetry={reload}>Couldn&rsquo;t load the transcoding state.</ErrorState>;
  } else if (transcoding) {
    body = (
      <>
        <dl className="admin__rows">
          <dt>Encoder</dt>
          <dd className="admin__mono">{encoderLabel(transcoding.encoder)}</dd>
          <dt>Keyframe index</dt>
          <dd className="admin__mono">{indexText(transcoding.index)}</dd>
          <dt>Stream cache</dt>
          <dd className="admin__mono">{formatBytes(transcoding.hlsBytes)}</dd>
        </dl>
        <Sessions sessions={transcoding.sessions} onStop={stop} />
        {notice ? (
          <p className="admin__notice" role="status">
            {notice}
          </p>
        ) : null}
      </>
    );
  }
  return (
    <section className="admin__card" aria-labelledby="admin-transcoding-title">
      <div className="admin__head">
        <h2 id="admin-transcoding-title" className="admin__title">
          Transcoding
        </h2>
      </div>
      {body}
    </section>
  );
}
