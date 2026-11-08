// Pure time formatting for the player and detail pages. All inputs are milliseconds.

const pad = (n) => String(n).padStart(2, '0');

function parts(ms) {
  const totalSeconds = Math.max(0, Math.floor((Number.isFinite(ms) ? ms : 0) / 1000));
  return {
    h: Math.floor(totalSeconds / 3600),
    m: Math.floor((totalSeconds % 3600) / 60),
    s: totalSeconds % 60,
  };
}

/** "01:04:31" — the mono clock used by the scrubber row. */
export function formatClock(ms) {
  const { h, m, s } = parts(ms);
  return `${pad(h)}:${pad(m)}:${pad(s)}`;
}

/** "1:04:31" (or "4:31" under an hour) — the label on Resume. */
export function formatResume(ms) {
  const { h, m, s } = parts(ms);
  return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${m}:${pad(s)}`;
}

export function remainingMs(positionMs, durationMs) {
  return Math.max(0, (durationMs || 0) - (positionMs || 0));
}

/** "−01:45:29" with a true minus sign. */
export function formatRemaining(positionMs, durationMs) {
  return `−${formatClock(remainingMs(positionMs, durationMs))}`;
}

/** "2h 50m", "48m". */
export function formatRuntime(ms) {
  const { h, m } = parts(ms);
  if (h === 0) return `${m}m`;
  return `${h}h ${m}m`;
}

/** "1h 45m left", "52m left". */
export function formatLeft(positionMs, durationMs) {
  return `${formatRuntime(remainingMs(positionMs, durationMs))} left`;
}

const unit = (n, word) => `${n} ${word}${n === 1 ? '' : 's'}`;

/** "1 hour 4 minutes 31 seconds" for aria-valuetext. */
export function spokenTime(ms) {
  const { h, m, s } = parts(ms);
  const out = [];
  if (h > 0) out.push(unit(h, 'hour'));
  if (h > 0 || m > 0) out.push(unit(m, 'minute'));
  out.push(unit(s, 'second'));
  return out.join(' ');
}

/** Progress as 0..1, clamped. */
export function fraction(positionMs, durationMs) {
  if (!durationMs) return 0;
  return Math.min(1, Math.max(0, positionMs / durationMs));
}
