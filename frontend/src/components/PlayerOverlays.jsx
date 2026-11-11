import { useEffect, useRef } from 'react';
import BufferingIndicator from './BufferingIndicator.jsx';
import { QUESTION, questionText } from '../player/environment.js';
import { Button } from './Button.jsx';

export function IndicatorPill({ text, amber = false }) {
  return (
    <div className="pill" role="status">
      <span className={`pill__dot${amber ? ' pill__dot--amber' : ''}`} aria-hidden="true" />
      <span className="sr-only">{amber ? 'Connection is limited. ' : 'Connection is good. '}</span>
      {text}
    </div>
  );
}

/** Shown while the connection is down and the player keeps retrying; polite, never an error. */
export function ReconnectingNotice() {
  return <BufferingIndicator label="Reconnecting…" delay={0} />;
}

/** The one-off card per session on a slow connection (decision 11). */
export function SlowConnectionCard({ rung, onOk, onChoose }) {
  const ok = useRef(null);
  useEffect(() => {
    ok.current?.focus();
  }, []);
  return (
    <div className="slow-card" role="dialog" aria-modal="false" aria-labelledby="slow-card-title">
      <h2 id="slow-card-title" className="slow-card__title">
        You&rsquo;re on a slow connection
      </h2>
      <p className="slow-card__text">
        Playing at {rung} to avoid stalls. Auto will step up if things improve.
      </p>
      <div className="slow-card__actions">
        <Button variant="primary" ref={ok} onClick={onOk}>
          OK
        </Button>
        <Button onClick={onChoose}>Choose quality</Button>
      </div>
    </div>
  );
}

/**
 * "Are you on a train?" on a network CineFilm has not seen. It never takes focus (Space must still pause the film
 * and a focused button would take it as an answer), so the player announces it from its own live region.
 * `operator` is a display name or null. All three buttons are plain: none is the answer CineFilm wants.
 */
export function EnvironmentCard({ operator, onTrain, onNo, onNotNow }) {
  return (
    <div
      className="slow-card"
      role="group"
      aria-labelledby="environment-card-title"
      aria-describedby="environment-card-text"
    >
      <h2 id="environment-card-title" className="slow-card__title">
        {QUESTION}
      </h2>
      <p id="environment-card-text" className="slow-card__text">
        {questionText(operator)}
      </p>
      <div className="slow-card__actions">
        <Button onClick={onTrain}>Yes, on a train</Button>
        <Button onClick={onNo}>No</Button>
        <Button onClick={onNotNow}>Not now</Button>
      </div>
    </div>
  );
}

/**
 * A film split across several files plays only as one adaptive stream, so when that stream cannot be made there is
 * nothing to fall back to. Says so plainly and offers to try again; never an error page.
 */
export function UnavailableCard({ onRetry }) {
  const retry = useRef(null);
  useEffect(() => {
    retry.current?.focus();
  }, []);
  return (
    <div className="slow-card" role="alert">
      <h2 className="slow-card__title">This film can&rsquo;t be played right now</h2>
      <p className="slow-card__text">The server could not prepare it. Try again in a moment.</p>
      <div className="slow-card__actions">
        <Button variant="primary" ref={retry} onClick={onRetry}>
          Try again
        </Button>
      </div>
    </div>
  );
}

export function Toast({ children }) {
  return (
    <div className="toast" role="status">
      {children}
    </div>
  );
}

/** "The end": Mark as watched (focused), Back to library, and a link to keep watching. */
export function EndOverlay({ title, year, director, onWatched, onLibrary, onKeepWatching }) {
  const primary = useRef(null);
  useEffect(() => {
    primary.current?.focus();
  }, []);
  return (
    <div className="end" role="dialog" aria-modal="false" aria-labelledby="end-title">
      <div className="end__eyebrow">The end</div>
      <h2 id="end-title" className="end__title">
        {title}
      </h2>
      <div className="end__meta">{[year, director].filter(Boolean).join(' · ')}</div>
      <div className="end__actions">
        <Button variant="primary" size="lg" ref={primary} onClick={onWatched}>
          Mark as watched
        </Button>
        <Button size="lg" onClick={onLibrary}>
          Back to library
        </Button>
      </div>
      <button type="button" className="end__link" onClick={onKeepWatching}>
        Keep watching the credits
      </button>
    </div>
  );
}
