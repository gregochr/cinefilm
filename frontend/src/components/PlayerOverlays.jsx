import { useEffect, useRef } from 'react';
import { Button } from './Button.jsx';

export function IndicatorPill({ text, amber = false }) {
  return (
    <div className="pill" role="status">
      <span className={`pill__dot${amber ? ' pill__dot--amber' : ''}`} aria-hidden="true" />
      {text}
    </div>
  );
}

export function BufferingSpinner() {
  return (
    <div className="buffering" role="status">
      <span className="buffering__spinner" aria-hidden="true" />
      <span className="buffering__text">BUFFERING</span>
    </div>
  );
}

/** Shown while the connection is down and the player keeps retrying; polite, never an error. */
export function ReconnectingNotice() {
  return (
    <div className="reconnecting" role="status">
      <span className="buffering__spinner" aria-hidden="true" />
      <span className="buffering__text">Reconnecting…</span>
    </div>
  );
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
