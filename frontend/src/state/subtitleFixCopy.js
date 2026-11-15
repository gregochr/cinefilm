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

/** The player's "Subtitles are wrong": deletes the download that is showing and blocks it. All "planner, 8a". */
export const subtitlesWrong = {
  label: 'Subtitles are wrong',
  busy: 'Removing…',
  done: 'Subtitle download removed. CineFilm won’t use it again.',
  changed: 'This subtitle has changed since the film started. Reload to see the new one.',
  lookupFailed: 'Couldn’t find that download to remove.',
  failed: 'Couldn’t remove the download. Try again.',
};

/** The words of the removing flow (`RemoveDownload`): Admin's old words until 8b takes the per-file tools out of Admin. */
export const adminRemoveLabels = {
  open: 'Remove',
  openSpoken: (name) => `Remove the OpenSubtitles download ${name}`,
  group: 'Remove this download',
  confirm: 'Remove download',
  tryAgain: 'Try again',
  keep: 'Keep it',
};

/** The same flow on Film detail, with AR7's name for it; the confirming steps are "planner, 8a". */
export const fixRemoveLabels = {
  open: 'Delete this download', // 07 (`buttons.deleteDownload`)
  openSpoken: (name) => `Delete this download: ${name}`,
  group: 'Delete this download',
  confirm: 'Delete download',
  tryAgain: 'Try again',
  keep: 'Keep it',
};
