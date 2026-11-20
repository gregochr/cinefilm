// Whether the Christmas screen's Home preview is open, remembered in this browser. No JSX. Storage can be missing or
// refuse (private windows, blocked site data), so every read and write is guarded and the preview defaults to open.

export const PREVIEW_OPEN_KEY = 'cinefilm.christmas.previewOpen';

/** True unless the owner hid the preview on this browser. */
export function readPreviewOpen() {
  try {
    return localStorage.getItem(PREVIEW_OPEN_KEY) !== 'false';
  } catch {
    return true;
  }
}

/** Remembers the choice; a refusing storage just means it is not remembered. */
export function writePreviewOpen(open) {
  try {
    localStorage.setItem(PREVIEW_OPEN_KEY, open ? 'true' : 'false');
  } catch {
    // Not remembered: the preview still works for this visit.
  }
}
