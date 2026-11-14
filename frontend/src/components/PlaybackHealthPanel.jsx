import { useRef, useState } from 'react';
import { adminApi } from '../api/resources.js';
import { describePlayback, environmentLabel } from '../state/healthFormat.js';
import { forgetNetwork, rememberedNetworks } from '../state/networks.js';
import { usePlaybackHealth } from '../state/usePlaybackHealth.js';
import { Button } from './Button.jsx';
import { buttons, rememberedNetworksNote } from '../state/adminCopy.js';
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

const NETWORKS_TITLE = 'Remembered networks on this device';

// The answers this browser holds for networks it has been asked about, newest first, each with Forget.
function RememberedNetworks({ networks, onForget }) {
  return (
    <>
      <h3 className="admin__subtitle">{NETWORKS_TITLE}</h3>
      <ul className="admin__list" aria-label={NETWORKS_TITLE}>
        {[...networks].reverse().map(({ operator, environment }) => (
          <li key={operator} className="admin__item">
            <div className="admin__item-head">
              <span className="admin__item-title">{operator}</span>
              <span className="admin__tag">{environmentLabel(environment)}</span>
            </div>
            <div className="admin__actions admin__item-actions">
              <Button
                aria-label={`${buttons.forget.label} ${operator}`}
                onClick={() => onForget(operator)}
              >
                {buttons.forget.label}
              </Button>
            </div>
          </li>
        ))}
      </ul>
      <p className="admin__notice">{rememberedNetworksNote}</p>
    </>
  );
}

/** Admin: how the recent viewings went (plan: playback health, "Admin: the Playback health card"). */
export default function PlaybackHealthPanel() {
  const { playbacks, error, loading, reload } = usePlaybackHealth();
  // Read once when the card opens: no polling, no storage events.
  const [networks, setNetworks] = useState(rememberedNetworks);
  const heading = useRef(null);
  // The Forget button that had focus is gone with its row: put focus on the card's own heading.
  const forget = (operator) => {
    setNetworks(forgetNetwork(operator));
    heading.current?.focus();
  };
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
        <h2 id="admin-playback-health-title" className="admin__title" ref={heading} tabIndex={-1}>
          Playback health
        </h2>
      </div>
      {body}
      {networks.length > 0 ? <RememberedNetworks networks={networks} onForget={forget} /> : null}
    </section>
  );
}
