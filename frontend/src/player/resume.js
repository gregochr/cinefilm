// Resume and end-of-film rules as seen by the client. The backend owns "watched".

export const RESUME_REWIND_MS = 10000;
export const PROGRESS_INTERVAL_MS = 10000;
export const END_FRACTION = 0.92;

/** Where playback starts: the saved position minus 10 s, never below 0. */
export function resumePosition(savedMs) {
  return Math.max(0, (savedMs || 0) - RESUME_REWIND_MS);
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
