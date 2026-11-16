// The words of Film detail "Subtitles → Fix…" and the player's "Subtitles are wrong" (8a). No JSX.
// The five renamed buttons and their hints are `buttons.useMyFileInstead`, `addFileToFilm`, `previewLines`,
// `deleteDownload` and `letCineFilmDecide` in `adminCopy.js` (source '07'); every other string here is "planner, 8a":
// no board draws the panel or the player line. Kept apart from `adminCopy.js` because these screens are not Admin.

/** The Fix… panel on Film detail. */
export const subtitleFix = {
  // AR7 names the entry "Subtitles → Fix…"; the button sits beside the Subtitles field, so it reads "Fix…".
  entry: { label: 'Fix…', spoken: 'Fix subtitles…' }, // AR7; spoken: planner, 8a
  eyebrow: 'Fix subtitles', // planner, 8a
  lead: 'The subtitle files CineFilm has for this film. Timing is changed while you watch.', // planner, 8a
  close: 'Close', // planner, 8a
  loading: 'Loading the subtitle files', // planner, 8a
  loadError: 'Couldn’t load this film’s subtitle files.', // planner, 8a
  refreshError: 'Couldn’t refresh the subtitle files.', // planner, 8a
  none: 'CineFilm has no subtitle file for this film yet.', // planner, 8a
  listLabel: 'Subtitle files for this film', // planner, 8a
  hideLines: 'Hide lines', // planner, 8a
  confirmDelete: 'Delete download', // planner, 8a: the confirming step of "Delete this download"
  keep: 'Keep it', // the existing Admin word
  tryAgain: 'Try again', // the existing Admin word
  playing: 'Playing', // the existing Admin tag
  yourFile: 'Your file', // planner, 8a
  usedMine: 'Your file plays instead of the download.', // planner, 8a
  decided: 'CineFilm decides for that file again.', // planner, 8a
  full: 'Full', // the existing Admin tag
  forced: 'Forced', // planner, 8a
};

/** "Looks right" in the Fix… panel (8b-2). The button is the existing verify route's word; AR7 and 07 give no hint. */
export const subtitleCheck = {
  label: 'Looks right',
  hint: 'Marks the lines as checked: you’ve seen they’re right.', // planner, 8b-2
  checked: 'You checked this: the lines look right.', // the existing Admin line
  undo: 'Undo', // the existing Admin word
  done: 'Marked as checked.', // planner, 8b-2
  undone: 'The check is taken away.', // planner, 8b-2
  undoneReplaced: 'The download plays again.', // planner, 8b-2: undoing "Use my file instead"
};

/** The player's "Subtitles are wrong": deletes the download that is showing and blocks it. All "planner, 8a". */
export const subtitlesWrong = {
  label: 'Subtitles are wrong',
  busy: 'Removing…',
  done: 'Subtitle download removed. CineFilm won’t use it again.',
  changed: 'This subtitle has changed since the film started. Reload to see the new one.',
  failed: 'Couldn’t remove the download. Try again.',
};

/** The words of the removing flow (`RemoveDownload`), with AR7's name for it; the confirming steps are "planner, 8a". */
export const fixRemoveLabels = {
  open: 'Delete this download', // 07 (`buttons.deleteDownload`)
  openSpoken: (name) => `Delete this download: ${name}`,
  group: 'Delete this download',
  confirm: 'Delete download',
  tryAgain: 'Try again',
  keep: 'Keep it',
};

/**
 * The player's subtitle timing ("Earlier" / "Later" / "Reset timing", 8b-1). A block of its own so the 8b-2 edits to
 * this file merge cleanly. The spoken names of Earlier and Later are Admin's own nudge words (`NUDGES`); the readout
 * of an offset is Admin's `formatOffset` ("+0.25 s", "No timing change"). Everything else is "planner, 8b-1".
 */
export const subtitleTiming = {
  group: 'Subtitle timing', // planner, 8b-1
  earlier: 'Earlier', // the handover's word for the nudge
  later: 'Later', // the handover's word for the nudge
  reset: 'Reset timing', // Admin's existing word
  readout: (offset) => `Timing: ${offset}`, // planner, 8b-1
  differs: 'differs by part', // planner, 8b-1: the parts of one film's subtitle do not share an offset
  busy: 'Saving…', // planner, 8b-1
  changed: 'This subtitle has changed since the film started. Reload to see the new one.', // as subtitlesWrong.changed
  swapFailed: 'The new timing is saved but hasn’t loaded. Press again to retry.', // planner, review
  failed: 'Couldn’t change the timing. Try again.', // planner, 8b-1
};
