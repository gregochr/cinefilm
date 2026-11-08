import { useRef, useState } from 'react';
import { SEEK_STEP_S } from '../player/keymap.js';
import { formatClock, fraction, spokenTime } from '../player/time.js';

/**
 * The player scrubber: buffered range, amber played fill, hover marker with a time bubble
 * (no sprite sheet yet, so time only), drag to seek, and slider semantics.
 */
export default function Scrubber({ positionMs, durationMs, bufferedMs = 0, onSeek }) {
  const track = useRef(null);
  const dragging = useRef(false);
  const [hover, setHover] = useState(null);

  const msAt = (clientX) => {
    const rect = track.current.getBoundingClientRect();
    const f = rect.width ? Math.min(1, Math.max(0, (clientX - rect.left) / rect.width)) : 0;
    return { f, ms: Math.round(f * durationMs) };
  };

  const onPointerDown = (event) => {
    dragging.current = true;
    event.currentTarget.setPointerCapture?.(event.pointerId);
    onSeek(msAt(event.clientX).ms);
  };
  const onPointerMove = (event) => {
    const point = msAt(event.clientX);
    setHover(point);
    if (dragging.current) onSeek(point.ms);
  };
  const endDrag = (event) => {
    dragging.current = false;
    event.currentTarget.releasePointerCapture?.(event.pointerId);
  };

  const onKeyDown = (event) => {
    if (event.key === 'Home') {
      event.preventDefault();
      onSeek(0);
    } else if (event.key === 'End') {
      event.preventDefault();
      onSeek(durationMs);
    } else if (event.key === 'PageUp' || event.key === 'PageDown') {
      event.preventDefault();
      const step = SEEK_STEP_S * 6 * 1000;
      onSeek(
        Math.min(durationMs, Math.max(0, positionMs + (event.key === 'PageUp' ? step : -step))),
      );
    }
    // Arrow keys fall through to the player's keyboard map (±10 s).
  };

  return (
    <div
      className="scrubber"
      role="slider"
      tabIndex={0}
      aria-label="Seek"
      aria-valuemin={0}
      aria-valuemax={Math.round(durationMs / 1000)}
      aria-valuenow={Math.round(positionMs / 1000)}
      aria-valuetext={spokenTime(positionMs)}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={endDrag}
      onPointerCancel={endDrag}
      onPointerLeave={() => !dragging.current && setHover(null)}
      onKeyDown={onKeyDown}
    >
      <div className="scrubber__track" ref={track}>
        <div
          className="scrubber__buffered"
          style={{ width: `${fraction(bufferedMs, durationMs) * 100}%` }}
        />
        <div
          className="scrubber__played"
          style={{ width: `${fraction(positionMs, durationMs) * 100}%` }}
        />
        <div
          className="scrubber__thumb"
          style={{ left: `${fraction(positionMs, durationMs) * 100}%` }}
        />
        {hover ? (
          <>
            <div className="scrubber__marker" style={{ left: `${hover.f * 100}%` }} />
            <div
              className="scrubber__bubble"
              style={{ left: `${hover.f * 100}%` }}
              aria-hidden="true"
            >
              {formatClock(hover.ms)}
            </div>
          </>
        ) : null}
      </div>
    </div>
  );
}
