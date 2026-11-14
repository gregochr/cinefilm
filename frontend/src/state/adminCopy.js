// Every word on an Admin button, hint and section header, in one place. No JSX: the Apple TV and native clients reuse it.
// Each entry is { label, busyLabel?, hint?, source }. `source` says where the string comes from:
//   '07'      docs/design/admin-redesign/screens/07-buttons-renamed.png (reference/AR7-Buttons.dc.html)
//   'AR1'..'AR7'  that board
//   'README'  docs/design/admin-redesign/README.md
//   'plan'    docs/engineering/admin-redesign-plan.md (the owner's answers, 2026-11-14)
// The entries for buttons that move in later phases are here too so the copy has one home; only some are wired today.

export const buttons = {
  // Wired today.
  findFilm: {
    label: 'Find the film…',
    hint: 'Search TMDB and choose which film this file is.',
    source: '07',
  },
  sortEditions: {
    label: 'Sort out editions…',
    hint: 'Choose which file is which cut, the part order and the default.',
    source: '07',
  },
  scanLibrary: {
    label: 'Scan library now',
    busyLabel: 'Scanning…',
    hint: 'Looks through your movie folders for new, changed and removed files. A few minutes; you can keep watching.',
    source: 'README',
  },
  refreshRatings: {
    label: 'Refresh all ratings',
    busyLabel: 'Refreshing ratings…',
    hint: 'Re-fetches IMDb and Rotten Tomatoes scores for every film.',
    source: '07',
  },
  importPlex: {
    label: 'Import from Plex again',
    busyLabel: 'Importing…',
    hint: 'Copies watched state, resume points and artwork; your newer CineFilm changes win.',
    source: '07',
  },
  tryPlex: {
    label: 'Try connecting to Plex',
    hint: 'Checks whether the Plex server answers now.',
    source: '07',
  },
  // 07's wording: the README's "Ends playback on the {device}; it can resume from here" needs a device the
  // transcoding session does not carry yet. Swap this hint when the session reports one.
  stopStream: {
    label: 'Stop this stream',
    hint: 'Ends playback on that device; it can resume later.',
    source: '07',
  },
  // 07 says Forget "starts at a safe quality and measures again", which is not what it does: the owner's answer
  // (Q4) is the line under the list, `rememberedNetworksNote`.
  forget: { label: 'Forget', source: 'plan' },
  signOut: {
    label: 'Sign out',
    busyLabel: 'Signing out…',
    hint: "Signing out here doesn't affect other devices.",
    source: 'README',
  },

  // Later phases.
  confirmGuess: {
    label: (title, year) => `Yes, it’s ${title} (${year})`,
    hint: 'Confirms the guess and fetches its poster and details',
    source: 'AR1',
  },
  searchEnglishSubtitles: {
    label: 'Search for English subtitles…',
    hint: 'Shows OpenSubtitles results for this film so you can pick one',
    source: 'AR1',
  },
  addMyFile: {
    label: 'Add my own file…',
    hint: 'Picks which film (and part) a subtitle file belongs to.',
    source: '07',
  },
  useItAnyway: {
    label: 'Use it anyway',
    hint: 'Plays that file with the film; you can adjust timing while watching',
    source: 'AR1',
  },
  searchAgain: { label: 'Search again…', source: 'AR1' },
  // Owner's answer Q3 replaces 07's "Yes, find forced subtitles…" (the forced list now holds films with none found).
  findForced: {
    label: 'Find forced subtitles…',
    hint: 'Searches OpenSubtitles for a file that only covers the foreign scenes.',
    source: 'plan',
  },
  noForeignScenes: {
    label: 'No foreign scenes',
    hint: '“No foreign scenes” marks it done and stops asking',
    source: 'AR1',
  },
  letCineFilmDecide: {
    label: 'Let CineFilm decide',
    hint: 'Forgets your choice; CineFilm decides from the film’s languages again.',
    source: '07',
  },
  useMyFileInstead: {
    label: 'Use my file instead',
    hint: 'Plays your own file rather than the download.',
    source: '07',
  },
  previewLines: {
    label: 'Preview lines',
    hint: 'Shows the first lines so you can see it’s English and the right film.',
    source: '07',
  },
  deleteDownload: {
    label: 'Delete this download',
    hint: 'Deletes the downloaded file; CineFilm won’t fetch it again.',
    source: '07',
  },
  earlier: {
    label: 'Earlier',
    hint: 'Shifts when lines appear; done while watching, where you can see the effect.',
    source: '07',
  },
  later: {
    label: 'Later',
    hint: 'Shifts when lines appear; done while watching, where you can see the effect.',
    source: '07',
  },
};

/** The line under Remembered networks (owner's answer Q4; 07's hint is false). */
export const rememberedNetworksNote =
  "Forget makes CineFilm ask where you're watching next time on that network.";

export const todo = {
  intro:
    "Only things CineFilm can't sort out by itself. Everything else carries on automatically. When this list is empty, there's nothing to do.",
  empty: 'Nothing to do.',
  source: 'AR1',
};

/** The To do groups in order: heading and why-line. */
export const todoGroups = {
  pickFilm: {
    heading: 'Pick the right film',
    why: 'CineFilm couldn’t tell which film these files are',
    source: 'AR1',
  },
  severalFiles: {
    heading: 'Sort out several files',
    why: 'One film has more than one file and CineFilm can’t tell how they fit',
    source: 'AR1',
  },
  englishSubtitlesMissing: {
    heading: 'English subtitles missing',
    why: 'Foreign-language films with nothing to read',
    source: 'AR1',
  },
  // Owner's answer Q3: the forced tab lists NEEDED films with no forced subtitle found. The why-line is derived.
  forcedNotFound: {
    heading: 'Forced subtitles not found',
    why: 'English films with foreign dialogue and no forced subtitle found',
    source: 'plan',
  },
};

/** "Showing {k} of {n}" under a group card on desktop. */
export const footerDesktop = (shown, total) => `Showing ${shown} of ${total}`;

/** "{k} of {n}" under a group card on phone (AR6). */
export const footerPhone = (shown, total) => `${shown} of ${total}`;

/** The link beside a desktop footer, "Show all {n}". */
export const showAll = (total) => `Show all ${total}`;
