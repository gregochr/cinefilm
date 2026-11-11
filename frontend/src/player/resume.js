// Resume and end-of-film rules as seen by the client. The backend owns "watched".

export const RESUME_REWIND_MS = 10000;
export const PROGRESS_INTERVAL_MS = 10000;
export const END_FRACTION = 0.92;

/** Where playback starts: the saved position minus 10 s, never below 0. */
export function resumePosition(savedMs) {
  return Math.max(0, (savedMs || 0) - RESUME_REWIND_MS);
}

/** The `at` address value (edition time in whole milliseconds, 0 or more), or null when absent or not a time. */
export function parseStartAt(value) {
  if (value === null || value === undefined || String(value).trim() === '') return null;
  const ms = Number(value);
  return Number.isFinite(ms) && ms >= 0 ? Math.floor(ms) : null;
}

/**
 * Where playback starts. An explicit time (`at`, "Play from here" in Admin) is used as it is, kept inside the
 * film; otherwise the start of the film when asked (`startAtZero`), else the resume point.
 */
export function startPosition({ at = null, startAtZero = false, savedMs = 0, runtimeMs = 0 }) {
  if (at !== null) return runtimeMs > 0 ? Math.min(at, runtimeMs) : at;
  return startAtZero ? 0 : resumePosition(savedMs);
}

/** The point at which the end-of-film overlay appears: credits marker, else 92%. */
export function endThresholdMs(runtimeMs, creditsMarkerMs) {
  if (Number.isFinite(creditsMarkerMs) && creditsMarkerMs > 0) return creditsMarkerMs;
  return Math.floor(runtimeMs * END_FRACTION);
}

export function hasReachedEnd(positionMs, runtimeMs, creditsMarkerMs) {
  if (!runtimeMs) return false;
  return positionMs >= endThresholdMs(runtimeMs, creditsMarkerMs);
}

/** How close (ms) the playhead must be to a requested position for the seek to count as landed. */
export const SEEK_LANDED_MS = 2000;

/** True once the playhead is within 2 s of the position a reloaded source was asked to seek to. */
export const seekLanded = (currentMs, targetMs) => Math.abs(currentMs - targetMs) < SEEK_LANDED_MS;
