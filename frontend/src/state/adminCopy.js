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
  // The label is 07's. 07's and AR4's hints ("Ends playback on …") are not true: Stop ends the server's converting
  // session (`SessionManager.end`), the player sees a 404 as an expired session and starts a new one. The hint says
  // what Stop does (planner, 5a-1). A real "end playback" is an open item in the plan (5a-1 decision).
  stopStream: {
    label: 'Stop this stream',
    hint: 'Stops converting this stream on the server; if it’s still playing, the player starts it again.',
    source: 'plan',
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
    // AR1; a guess with no year (TMDB lists none) reads "Yes, it’s {Title}" (planner, 3c-2).
    label: (title, year) => (year ? `Yes, it’s ${title} (${year})` : `Yes, it’s ${title}`),
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

/** The Playback section's "Now playing" card (AR4, `screens/04-playback.png`). */
export const playingNow = {
  title: 'Now playing', // 04, AR4 and the README's Playback section ("Playing now" is the To do card's)
  streams: (n) => `${n} ${n === 1 ? 'stream' : 'streams'}`, // 04: "1 stream"; the plural is the planner's
  empty: 'Nothing playing.', // README
  converting: 'converting on the fly', // AR4
  // The same honest line as the Transcoding card's Stop (`buttons.stopStream.hint`), not AR4's "Ends playback on the
  // iPad" (see there).
  stopHint: () => buttons.stopStream.hint,
  stopFailed: 'Couldn’t stop that stream. Try again.', // planner, 5a-1
  loadError: 'Couldn’t load what is playing.', // planner, 5a-1
  source:
    '04 and AR4 for the title, count and "converting on the fly"; the README for the empty line; planner 5a-1 for the rest',
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

/** The footer controls under a To do group. */
export const todoFooter = {
  allInSubtitles: 'All in Subtitles', // AR1; the arrow is drawn on wide screens only (AR6 has none)
  arrow: '→', // AR1
  showAll: 'Show all', // AR1; the count after it is drawn on wide screens only (AR6: "Show all")
  showFewer: 'Show fewer', // planner, 3c-2 (AR1 shows no way back after "Show all {n}")
  source: 'AR1 (AR6 for the phone forms); planner 3c-2 for "Show fewer"',
};

/** The To do rows' small lines and the load messages AR1 does not show. All planner, 3c-2 unless noted. */
export const todoRow = {
  bestGuess: (guess) => `best guess: ${guess}`, // AR1
  noGuess: 'best guess: none', // AR1
  files: (n) => `${n} ${n === 1 ? 'file' : 'files'}`,
  loadErrors: {
    attention: 'Couldn’t load the films that need sorting out.',
    full: 'Couldn’t load the films missing English subtitles.',
    forced: 'Couldn’t load the films with forced subtitles to find.',
  },
  // The phone group header (AR6) shows a count where the wide one shows the why-line.
  groupCount: {
    pickFilm: (n) => `${n} ${n === 1 ? 'file' : 'files'}`, // AR6
    other: (n) => `${n} ${n === 1 ? 'film' : 'films'}`, // AR6 (planner, 3c-2, for the groups it does not show)
  },
  // A guess that the file's own year did not give is marked with a question mark (AR1: "Heat · 1995?").
  guessedYear: (year) => `${year}?`,
  // The full-subtitles line (AR1); the language-less wording is the planner's, 3c-2.
  spoken: (language) => `Spoken: ${language}`,
  languageUnknown: 'Language not known yet',
  noneFound: 'none found automatically on OpenSubtitles',
  // "Yes, it's …" failures, worded for a row (the Fix match dialog's words talk about a dialog).
  confirmProblems: {
    gone: 'That film is no longer in the library. The list is being updated.',
    scan: 'A scan is running. Try again when it finishes.',
    tmdb: 'TMDB isn’t available right now. Try again in a moment.',
    other: 'Something went wrong. Try again.',
  },
  retry: 'Try again',
  loadingLabel: 'Loading',
  source: 'AR1 for "best guess: …"; planner 3c-2 for the rest',
};

/** "Showing {k} of {n}" under a group card on desktop. */
export const footerDesktop = (shown, total) => `Showing ${shown} of ${total}`;

/** "{k} of {n}" under a group card on phone (AR6). */
export const footerPhone = (shown, total) => `${shown} of ${total}`;

/** The link beside a desktop footer, "Show all {n}". */
export const showAll = (total) => `Show all ${total}`;

/** The muted count beside the "To do" heading (AR1: "9 things need you"; the singular is the planner's, 3c-1). */
export const todoCount = {
  plural: (n) => `${n} things need you`,
  singular: '1 thing needs you',
  source: 'AR1 (plural), planner 3c-1 (singular)',
};

/** The accessible names of the rail links that carry a count (AR1 shows only the visible pill; names are the planner's). */
export const railNames = {
  todo: (n) => `To do, ${n} ${n === 1 ? 'thing' : 'things'}`,
  subtitles: (n) => `Subtitles, ${n} unresolved`,
  playback: (n) => `Playback, ${n} live`,
  /** The visible pill on Playback (AR1). */
  live: (n) => `${n} live`,
  source: 'AR1 (pill), planner 3c-1 (accessible names)',
};

/** The three status cards under the To do intro: eyebrow, link text and the lines AR1 does not show. */
export const statusStrip = {
  library: { eyebrow: 'Library', link: 'Library →', source: 'AR1' },
  playback: { eyebrow: 'Playback', link: 'Playback →', source: 'AR1' },
  playingNow: { eyebrow: 'Playing now', link: 'See stream →', source: 'AR1' },
  scanned: (films, ago) => `${films} · scanned ${ago}`, // AR1
  neverScanned: (films) => `${films} · not scanned yet`, // planner, 3c-1
  scanFailed: (films, ago) => `${films} · last scan failed ${ago}`, // planner, 3c-1
  scanFailedUndated: (films) => `${films} · last scan failed`, // planner, 3c-1
  film: { one: 'film', other: 'films' }, // planner, 3c-1
  unknownDevice: 'Unknown device', // planner, 3c-1 (the Playback log said it first)
  // AR1 says "films played smoothly"; the planner's brief counts film sessions (3 minutes or more).
  playbackSome: (smooth, films) =>
    `Last 30 days: ${smooth} of ${films} film ${films === 1 ? 'session' : 'sessions'} played smoothly`,
  playbackNone: 'No films watched in the last 30 days', // planner, 3c-1
  nothingPlaying: 'Nothing playing', // AR1
  moreStreams: (k) => `+${k} more`, // planner, 3c-1
  loadError: 'Couldn’t load the status.', // planner, 3c-1
  retry: 'Try again', // planner, 3c-1
  source:
    'AR1 for the eyebrows, links, scanned and "Nothing playing"; planner 3c-1 for the rest (see comments)',
};
