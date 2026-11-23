import { useEffect, useRef, useState } from 'react';
import AdminErrorBanner from '../../components/AdminErrorBanner.jsx';
import { SkeletonBlock } from '../../components/Skeleton.jsx';
import { journeyList as copy } from '../../state/journeysCopy.js';
import { journeyLines } from '../../state/journeysFormat.js';

/** One stored journey: when it was logged, then its unit, class, rounds and viewings, its train, its file and laptop. */
function JourneyRow({ journey, all }) {
  const { title, second, third, fourth } = journeyLines(journey, all);
  return (
    <li className="admin__todo-row">
      <div className="admin__todo-text">
        <p className="admin__todo-title">{title}</p>
        <p className="admin__todo-line admin__mono">{second}</p>
        <p className="admin__todo-line">{third}</p>
        {fourth ? <p className="admin__todo-line admin__mono">{fourth}</p> : null}
      </div>
    </li>
  );
}

/**
 * The stored journeys, in the server's order. `journeys` is null before the first answer (the skeleton). A read again
 * under way keeps the rows on show; one that failed shows the failure, with its retry, in place of them.
 */
export default function JourneyList({ journeys, loading, error, onRetry }) {
  // A press of "Check again" is `pressed` until its read is seen under way (`running`). While it runs the banner stays,
  // busy, so the button keeps focus and the rows from before the failure are not shown as if current.
  const [phase, setPhase] = useState('idle');
  const [failures, setFailures] = useState(0);
  const heading = useRef(null);
  if (phase === 'pressed' && loading) setPhase('running');
  const retrying = phase === 'running' && loading;

  // The retry has ended: a failure keeps the banner (announced again), an answer takes focus to the heading.
  useEffect(() => {
    if (phase !== 'running' || loading) return;
    setPhase('idle');
    if (error) setFailures((n) => n + 1);
    // Only when focus fell to the page (the banner went with it): focus the owner has put somewhere real stays.
    else if (document.activeElement === document.body) heading.current.focus();
  }, [phase, loading, error]);

  const retry = () => {
    setPhase('pressed');
    onRetry();
  };

  let body;
  if (error || retrying) {
    body = (
      <AdminErrorBanner
        onRetry={retry}
        busy={retrying}
        announceKey={failures}
        retryLabel={copy.checkAgain}
      >
        {copy.loadError}
      </AdminErrorBanner>
    );
  } else if (journeys === null) {
    body = (
      <div role="status" aria-label={copy.loadingLabel}>
        <SkeletonBlock style={{ height: 60 }} />
      </div>
    );
  } else if (journeys.length === 0) {
    body = <p className="admin__text">{copy.empty}</p>;
  } else {
    body = (
      <ul className="admin__todo-rows" aria-label={copy.label}>
        {journeys.map((journey) => (
          <JourneyRow key={journey.id} journey={journey} all={journeys} />
        ))}
      </ul>
    );
  }
  return (
    <section className="admin__card" aria-labelledby="admin-journey-list-title">
      <div className="admin__head">
        <h2 ref={heading} tabIndex={-1} id="admin-journey-list-title" className="admin__title">
          {copy.title}
        </h2>
      </div>
      {body}
    </section>
  );
}
