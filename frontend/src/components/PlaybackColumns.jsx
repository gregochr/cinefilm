import { useRef } from 'react';
import {
  columnLabel,
  environmentLetter,
  RUNG_BUCKETS,
  rungShares,
  STALL_COLOUR,
  stallDotSize,
} from '../state/playbackChart.js';
import { playbackChart as copy } from '../state/adminCopy.js';

/** The key that moves the selection, and where it goes from index `at` of `count` columns; null for any other key. */
function target(key, at, count) {
  if (key === 'ArrowLeft') return Math.max(at - 1, 0);
  if (key === 'ArrowRight') return Math.min(at + 1, count - 1);
  if (key === 'Home') return 0;
  if (key === 'End') return count - 1;
  return null;
}

/**
 * One button per session, oldest to newest: a stack of the time on each rung, then a dot sized by the stall time, then
 * the environment letter. One column is in the tab order (the selected one, a roving tabindex); the arrow keys, Home
 * and End move the selection and focus along the row, hovering with a mouse or clicking selects. Each button says its
 * session in words (`aria-label`), so nothing relies on colour.
 */
export default function PlaybackColumns({ sessions, selectedId, onSelect, describedBy }) {
  const buttons = useRef(new Map());
  const move = (event, index) => {
    const to = target(event.key, index, sessions.length);
    if (to === null) return;
    event.preventDefault();
    const next = sessions[to];
    onSelect(next.playbackId);
    buttons.current.get(next.playbackId)?.focus();
  };
  return (
    <div className="pchart__scroll">
      <div
        className="pchart__cols"
        role="group"
        aria-label={copy.chartLabel}
        aria-describedby={describedBy}
      >
        {sessions.map((session, index) => {
          const shares = rungShares(session.rungMs);
          const dot = stallDotSize(session.stallMs);
          const selected = session.playbackId === selectedId;
          return (
            <button
              key={session.playbackId}
              ref={(element) => {
                if (element) buttons.current.set(session.playbackId, element);
                else buttons.current.delete(session.playbackId);
              }}
              type="button"
              className="pchart__col"
              aria-label={columnLabel(session)}
              aria-pressed={selected}
              tabIndex={selected ? 0 : -1}
              onClick={() => onSelect(session.playbackId)}
              onPointerEnter={(event) => {
                if (event.pointerType === 'mouse') onSelect(session.playbackId);
              }}
              onKeyDown={(event) => move(event, index)}
            >
              <span className="pchart__bar" aria-hidden="true">
                {RUNG_BUCKETS.map(({ key, colour }) =>
                  shares[key] > 0 ? (
                    <span
                      key={key}
                      className="pchart__seg"
                      style={{ flexGrow: shares[key], background: colour }}
                    />
                  ) : null,
                )}
              </span>
              <span className="pchart__stall" aria-hidden="true">
                {dot > 0 ? (
                  <span
                    className="pchart__dot"
                    style={{ width: dot, height: dot, background: STALL_COLOUR }}
                  />
                ) : null}
              </span>
              <span className="pchart__env" aria-hidden="true">
                {environmentLetter(session.environment)}
              </span>
            </button>
          );
        })}
      </div>
    </div>
  );
}
