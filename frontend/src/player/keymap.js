// The player keyboard map (handover §4). Scoped to the player: the caller only feeds it
// key events that happen inside the player.

export const SEEK_STEP_S = 10;
export const VOLUME_STEP = 0.1;

/**
 * Maps a key event to a player action, or null when the key is not ours.
 * C does nothing when the film has no subtitles.
 */
export function keyToAction(event, { hasSubtitles = true } = {}) {
  if (event.ctrlKey || event.metaKey || event.altKey) return null;
  switch (event.key) {
    case ' ':
    case 'Spacebar':
      return { type: 'togglePlay' };
    case 'ArrowLeft':
      return { type: 'seek', seconds: -SEEK_STEP_S };
    case 'ArrowRight':
      return { type: 'seek', seconds: SEEK_STEP_S };
    case 'ArrowUp':
      return { type: 'volume', delta: VOLUME_STEP };
    case 'ArrowDown':
      return { type: 'volume', delta: -VOLUME_STEP };
    case 'f':
    case 'F':
      return { type: 'fullscreen' };
    case 'c':
    case 'C':
      return hasSubtitles ? { type: 'cycleCaptions' } : null;
    case 'a':
    case 'A':
      return { type: 'openAudio' };
    case 'q':
    case 'Q':
      return { type: 'openQuality' };
    case 'Escape':
      return { type: 'escape' };
    default:
      return null;
  }
}

/** Esc closes a menu first, then exits fullscreen, then dismisses the end overlay. */
export function resolveEscape({ menuOpen, fullscreen, endOverlay }) {
  if (menuOpen) return 'closeMenu';
  if (fullscreen) return 'exitFullscreen';
  if (endOverlay) return 'dismissEnd';
  return 'none';
}

export function clampVolume(v) {
  return Math.min(1, Math.max(0, Math.round(v * 100) / 100));
}
