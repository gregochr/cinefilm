import { useEffect, useRef } from 'react';
import { Button } from './Button.jsx';

export function IndicatorPill({ text }) {
  return (
    <div className="pill" role="status">
      <span className="pill__dot" aria-hidden="true" />
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
