import { adminApi } from '../api/resources.js';
import { describePlayback, environmentLabel } from '../state/healthFormat.js';
import { usePlaybackHealth } from '../state/usePlaybackHealth.js';
import { SkeletonBlock } from './Skeleton.jsx';
import { ErrorState } from './StateMessages.jsx';

function Playbacks({ playbacks }) {
  if (playbacks.length === 0) return <p className="admin__text">No playbacks recorded yet.</p>;
  return (
    <ul className="admin__list" aria-label="Recent playbacks">
      {playbacks.map((row) => (
        <li key={row.playbackId} className="admin__item">
          <div className="admin__item-head">
            <span className="admin__item-title">{row.filmTitle}</span>
            <span className="admin__tag">{environmentLabel(row.report?.environment)}</span>
          </div>
          {describePlayback(row).map((line, i) => (
            <div key={`${i}-${line}`} className="admin__item-files admin__mono">
              {line}
            </div>
          ))}
          <div className="admin__actions admin__item-actions">
            <a
              className="admin__link"
              href={adminApi.playbackHealthUrl(row.playbackId)}
              target="_blank"
              rel="noreferrer"
              aria-label={`Full record of ${row.filmTitle}`}
            >
              Full record
            </a>
          </div>
        </li>
      ))}
    </ul>
  );
}

/** Admin: how the recent viewings went (plan: playback health, "Admin: the Playback health card"). */
export default function PlaybackHealthPanel() {
  const { playbacks, error, loading, reload } = usePlaybackHealth();
  let body;
  if (loading && !playbacks) {
    body = (
      <div role="status" aria-label="Loading">
        <SkeletonBlock style={{ height: 120 }} />
      </div>
    );
  } else if (error && !playbacks) {
    body = <ErrorState onRetry={reload}>Couldn&rsquo;t load playback health.</ErrorState>;
  } else if (playbacks) {
    body = <Playbacks playbacks={playbacks} />;
  }
  return (
    <section className="admin__card" aria-labelledby="admin-playback-health-title">
      <div className="admin__head">
        <h2 id="admin-playback-health-title" className="admin__title">
          Playback health
        </h2>
      </div>
      {body}
    </section>
  );
}
