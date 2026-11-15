import { useEffect, useRef } from 'react';
import {
  bucketShares,
  columnLabel,
  environmentLetter,
  RUNG_BUCKETS,
  sessionDotSize,
  STALL_COLOUR,
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

/** The 100% / 50% / 0% labels at the left of the bars, and "stalls" at the left of the dot row (AR4). */
function Axis() {
  return (
    <div className="pchart__axis" aria-hidden="true">
      <div className="pchart__ticks">
        <span>100%</span>
        <span>50%</span>
        <span>0%</span>
      </div>
      <div className="pchart__axis-stalls">{copy.stallsAxis}</div>
    </div>
  );
}

/**
 * Moves only `box` (the row that scrolls sideways) so that `button` is inside it: nothing happens when it already is,
 * and the nearer edge is used when it is not. `scrollIntoView` and `focus()` would scroll every ancestor too, the page
 * included, which is why neither is used here.
 */
function reveal(box, button) {
  if (!box || !button) return;
  const left = button.offsetLeft;
  const right = left + button.offsetWidth;
  if (left < box.scrollLeft) box.scrollLeft = left;
  else if (right > box.scrollLeft + box.clientWidth) box.scrollLeft = right - box.clientWidth;
}

/**
 * One button per session, oldest to newest: a stack of the time on each rung, then a dot sized by the stall time, then
 * the environment letter. One column is in the tab order (the selected one, a roving tabindex); the arrow keys, Home
 * and End move the selection and focus along the row, hovering with a mouse or clicking selects. Each button says its
 * session in words (`aria-label`), so nothing relies on colour. The row scrolls sideways and the newest session,
 * selected first, is at its far end, so the selected column is brought into view when the row is first drawn, when its
 * sessions change and on a keyboard move; a mouse entering a column does not scroll (the column under the pointer
 * would move away from it).
 */
export default function PlaybackColumns({ sessions, selectedId, onSelect, describedBy }) {
  const buttons = useRef(new Map());
  const box = useRef(null);
  const selection = useRef(selectedId);
  useEffect(() => {
    selection.current = selectedId;
  });
  useEffect(() => {
    reveal(box.current, buttons.current.get(selection.current));
  }, [sessions]);
  const move = (event, index) => {
    const to = target(event.key, index, sessions.length);
    if (to === null) return;
    event.preventDefault();
    const next = sessions[to];
    onSelect(next.playbackId);
    const button = buttons.current.get(next.playbackId);
    button?.focus({ preventScroll: true });
    reveal(box.current, button);
  };
  return (
    <div className="pchart__plot">
      <Axis />
      <div className="pchart__scroll" ref={box} data-testid="pchart-scroll">
        <div
          className="pchart__cols"
          role="group"
          aria-label={copy.chartLabel}
          aria-describedby={describedBy}
        >
          {sessions.map((session, index) => {
            const shares = bucketShares(session.rungMs);
            const dot = sessionDotSize(session);
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
    </div>
  );
}
