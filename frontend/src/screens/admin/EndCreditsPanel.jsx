import { useState } from 'react';
import { editionsApi } from '../../api/resources.js';
import { Button } from '../../components/Button.jsx';
import { SkeletonBlock } from '../../components/Skeleton.jsx';
import { formatResume } from '../../player/time.js';
import { panelTimeMs, rowLines, stillTimes } from '../../state/creditsAdmin.js';
import { creditsPanel, creditsRow } from '../../state/creditsAdminCopy.js';

/**
 * One still in a reserved 16:9 box, with a static skeleton until it loads. A failed one (an image cannot tell a 404
 * from a 503) says so and offers Try again, which remounts the image; nothing retries by itself.
 */
function Still({ editionId, atMs, caption }) {
  const [state, setState] = useState({ attempt: 0, status: 'loading' });
  const clock = formatResume(atMs);
  return (
    <figure className="credits-still">
      <div className="credits-still__box">
        {state.status === 'failed' ? (
          <div className="credits-still__failed">
            <p className="admin__text">{creditsPanel.noStill}</p>
            <Button
              className="btn--bare admin__quiet"
              onClick={() => setState((s) => ({ attempt: s.attempt + 1, status: 'loading' }))}
            >
              {creditsPanel.tryAgain}
            </Button>
          </div>
        ) : (
          <>
            {state.status === 'loading' ? (
              <SkeletonBlock className="credits-still__skeleton" />
            ) : null}
            <img
              key={state.attempt}
              className="credits-still__img"
              src={editionsApi.stillUrl(editionId, atMs)}
              alt={creditsPanel.frame(clock)}
              onLoad={() => setState((s) => ({ ...s, status: 'loaded' }))}
              onError={() => setState((s) => ({ ...s, status: 'failed' }))}
            />
          </>
        )}
      </div>
      <figcaption className="admin__why">
        {caption} · <span className="admin__mono">{clock}</span>
      </figcaption>
    </figure>
  );
}

/**
 * A row's panel, drawn inline under it only while it is open (so no still is requested before): two stills of the
 * film, at the panel's time and 30 s earlier. With no known length there are no stills, only the reason line.
 */
export default function EndCreditsPanel({ row, id }) {
  const atMs = row.runtimeMs ? panelTimeMs(row) : null;
  const times = atMs == null ? null : stillTimes(atMs);
  return (
    <div
      id={id}
      className="credits-panel"
      role="group"
      aria-label={creditsPanel.stillsLabel(row.title)}
    >
      {times ? (
        <div className="credits-panel__stills">
          <Still editionId={row.editionId} atMs={times[0]} caption={creditsPanel.earlier} />
          <Still editionId={row.editionId} atMs={times[1]} caption={creditsPanel.atTime} />
        </div>
      ) : (
        <p className="admin__text">{rowLines(row).three ?? creditsRow.noTimeline}</p>
      )}
    </div>
  );
}
