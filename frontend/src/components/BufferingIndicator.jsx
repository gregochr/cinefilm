import { useEffect, useId, useState } from 'react';

/** One frame of the strip. The gradient is defined once, in the first frame, and shared. */
function Frame({ gradientId, defineGradient }) {
  return (
    <div className="buffering__frame">
      <svg width="160" height="80" viewBox="-8 0 128 64">
        {defineGradient ? (
          <defs>
            <linearGradient
              id={gradientId}
              data-testid="buffering-gradient"
              x1="0"
              x2="1"
              y1="0"
              y2="0"
            >
              <stop offset="0" stopColor="currentColor" stopOpacity=".8" />
              <stop offset="1" stopColor="currentColor" stopOpacity="0" />
            </linearGradient>
          </defs>
        ) : null}
        <g className="buffering__beam">
          <polygon
            data-testid="buffering-beam"
            points="42,29 112,14 112,50 42,35"
            fill={`url(#${gradientId})`}
          />
        </g>
        <path
          d="M50 14 H20 A10 10 0 0 0 10 24 V40 A10 10 0 0 0 20 50 H50"
          fill="none"
          stroke="currentColor"
          strokeWidth="6.5"
          strokeLinecap="round"
        />
        <circle className="buffering__lamp" cx="38" cy="32" r="6.5" fill="currentColor" />
        <circle className="buffering__dust buffering__dust--1" cx="52" cy="31" r="1" />
        <circle className="buffering__dust buffering__dust--2" cx="55" cy="34" r=".8" />
        <circle className="buffering__dust buffering__dust--3" cx="58" cy="29" r=".9" />
      </svg>
      <div className="buffering__frame-line" />
    </div>
  );
}

/**
 * The Gate buffering animation: the lamp throws a flickering beam while a film strip advances
 * one frame every 2.2 s. The strip holds two identical frames so the loop has no visible jump.
 * It renders nothing until it has been mounted for `delay` ms, so a short stall never flashes
 * it; the caller unmounts it the moment playback resumes.
 */
export default function BufferingIndicator({ label = 'BUFFERING', delay = 400 }) {
  const [shown, setShown] = useState(delay <= 0);
  // useId returns characters (":" or guillemets) that are not safe inside url(#...).
  const gradientId = `buffering-beam-${useId().replace(/[^a-zA-Z0-9_-]/g, '')}`;

  useEffect(() => {
    if (shown) return undefined;
    const timer = setTimeout(() => setShown(true), delay);
    return () => clearTimeout(timer);
  }, [shown, delay]);

  if (!shown) return null;
  return (
    <div className="buffering" role="status" aria-live="polite">
      <div className="buffering__gate" aria-hidden="true">
        <div className="buffering__strip">
          <Frame gradientId={gradientId} defineGradient />
          <Frame gradientId={gradientId} />
        </div>
      </div>
      <span className="buffering__text">{label}</span>
    </div>
  );
}
