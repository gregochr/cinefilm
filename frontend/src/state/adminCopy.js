// Every word on an Admin button, hint and section header, in one place. No JSX: the Apple TV and native clients reuse it.
// Each entry is { label, busyLabel?, hint?, source }. `source` says where the string comes from:
//   '07'      docs/design/admin-redesign/screens/07-buttons-renamed.png (reference/AR7-Buttons.dc.html)
//   'AR1'..'AR7'  that board
//   'README'  docs/design/admin-redesign/README.md
//   'plan'    docs/engineering/admin-redesign-plan.md (the owner's answers, 2026-11-14)
//   'Q3'      the owner's answer Q3 (the forced tab lists NEEDED films with no forced subtitle found)
//   'planner, 4a'  wording the planner added for the Subtitles section, where no board shows it
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
  // "Add my own file…" on a Subtitles row: the film is fixed, so 07's hint ("Picks which film…") would be wrong.
  addFileToFilm: {
    label: 'Add my own file…',
    hint: 'Picks one of your subtitle files for this film',
    source: 'planner, 4a',
  },
  useItAnyway: {
    label: 'Use it anyway',
    hint: 'Plays that file with the film; you can adjust timing while watching',
    source: 'AR1',
  },
  searchAgain: { label: 'Search again…', source: 'AR1' },
  // Unlabelled audio (AR2, row "Downfall"). "find subtitles" opens the picker, so it arrives with 4c.
  itsInLanguage: {
    label: (language) => `It’s in ${language} — find subtitles`,
    source: 'AR2',
  },
  itsEnglish: {
    label: 'It’s English',
    hint: '“It’s English” marks it done and stops asking',
    source: 'AR2',
  },
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

/**
 * The Playback section's "How films played" card (AR4, `screens/04-playback.png`). Strings marked "planner" are not
 * on the board: the board says "films watched" where a film can count twice, so the tile counts sessions.
 */
export const playbackChart = {
  title: 'How films played', // AR4
  why: 'Each film you sat down to watch (3 min or more)', // AR4
  ranges: [
    { days: 30, label: '30 days' }, // AR4
    { days: 90, label: '90 days' }, // AR4
  ],
  rangesLabel: 'How far back', // planner, 5a-2
  environmentsLabel: 'Where it was watched', // planner, 5a-2
  environments: [
    { key: 'all', label: 'All' }, // AR4
    { key: 'home', label: 'Home' }, // AR4
    { key: 'away', label: 'Away' }, // AR4
    { key: 'train', label: 'Train' }, // AR4
  ],
  tiles: {
    watched: 'sessions watched', // planner, 5a-2 (AR4: "films watched")
    withoutStall: 'played without a stall', // AR4
    stalled: 'stalled', // AR4
    typicalStart: 'typical start', // AR4
    topRungs: 'of time at Original or 1080p', // AR4
  },
  // AR4 says "(all on the train)"; the other places are the planner's wording (5a-2).
  allOn: { train: 'all on the train', home: 'all at home', away: 'all away' },
  noStalls: 'No stalls', // planner, 5a-2
  noQuality: 'No quality recorded', // planner, 5a-2
  stallsAxis: 'stalls', // AR4
  legend: {
    stalled: 'Stalled (bigger = longer)', // AR4
    letters: 'H Home · A Away · T Train', // AR4
  },
  hint: 'Hover or tap a column to see that film below. Plays under 3 minutes count as browsing and aren’t charted — they’re still in the log.', // AR4
  chartLabel: 'Film sessions, oldest to newest', // planner, 5a-2 (AR4: "... for each of the last 28 films watched")
  empty: 'No film sessions in this range.', // planner, 5a-2
  summary: (count, days, where, figures) =>
    `Quality and stalls for each of the last ${count} ${count === 1 ? 'session' : 'sessions'} in ${days} days${where}: ${figures}.`, // planner, 5a-2
  summaryEmpty: (days, where) => `No film sessions in the last ${days} days${where}.`, // planner, 5a-2
  loadingLabel: 'Loading how films played', // planner, 5a-2
  loadError: 'Couldn’t load how films played.', // planner, 5a-2
  retry: 'Try again', // planner, 5a-2
  selectedLabel: 'Selected film session', // planner, 5a-2
  stripLabel: (film) => `Speed and quality minute by minute for ${film}`, // AR4
  stripAxis: 'line = speed · strip = quality', // AR4
  stripStart: '0:00', // AR4
  loadingStripLabel: 'Loading the minute by minute view', // planner, 5a-2
  stripError: 'Couldn’t load that session’s minutes.', // planner, 5a-2
  noMinutes: 'No minute by minute figures for this session.', // planner, 5a-2
  source:
    'AR4 and 04 for the title, filters, tiles, legend, hint and strip words; the planner for the rest (5a-2)',
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
  noneFound: 'nothing found automatically on OpenSubtitles', // AR2 (AR1 says "none found"; one string for both pages)
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

/**
 * The Library section (README "3 · Library", AR3, `screens/03-library.png`). Strings marked "planner, 6" are not on a
 * board. Button labels and hints are in `buttons` (07). The board's "Download TMDB credits" is not built (Q1), and its
 * hints for "Refresh all ratings", "Import from Plex again" and "Import credits from Plex" give way to 07's and the
 * existing, accurate ones: Plex's credits markers say where the end credits start, not who is in the film.
 */
export const libraryPage = {
  scan: {
    title: 'Library scan', // AR3
    lastScan: (ago, took) => `Last scan ${ago} · took ${took}`, // README
    neverScanned: 'Not scanned yet', // planner, 6
    countsLabel: 'Library counts', // existing
    openList: (label, value) => `${label}: ${value}, open the list`, // planner, 6
  },
  changes: {
    summary: 'What the last scan changed', // AR3
    counts: (split, joined, flagged) => `${split} split, ${joined} joined, ${flagged} flagged`, // README
    listLabel: 'Grouping changes', // existing
    empty: 'Nothing has been grouped or split yet.', // existing
    loadError: 'Couldn’t load what the last scan changed.', // planner, 6
    showMore: (n) => `Show ${n} more`, // planner, 6
  },
  ratings: {
    title: 'Ratings', // AR3
    updated: (day) => `IMDb and Rotten Tomatoes · updated ${day}`, // README
    neverUpdated: 'IMDb and Rotten Tomatoes · not fetched yet', // planner, 6
    needsKey: 'Ratings need an OMDb API key', // existing
  },
  plex: {
    title: 'Import from Plex', // AR3
    lastImport: (line) => `Last import ${line}`, // AR3
    neverImported: 'Not imported yet', // planner, 6
    server: 'Plex server', // AR3
    broughtAcross: 'Brought across', // AR3
    nothingYet: 'Nothing yet', // planner, 6
    reachable: 'Reachable', // AR3 (lower case there)
    notReachable: 'Not reachable', // planner, 6
    importing: 'Importing', // planner, 6
    lastReport: 'Last report', // AR3
    loadError: 'Couldn’t load the Plex import status.', // planner, 6
  },
  cast: {
    title: 'Cast & crew', // AR3
    // README; the empty library and the singular are the planner's, 6.
    count: (withCredits, total) =>
      total === 0
        ? 'No films in the library yet'
        : `${withCredits} of ${total} ${total === 1 ? 'film has' : 'films have'} credits`,
  },
  files: {
    back: '‹ Library', // planner, 6
    empty: 'Nothing here.', // planner, 6
    loadError: 'Couldn’t load these files.', // planner, 6
  },
  source: 'README 3 and AR3; planner 6 for the rest (see comments)',
};

/** The Subtitles section (AR2), with the wording AR2 does not show marked "planner, 4a". */
export const subtitlesPage = {
  // AR2's lead; `bold` parts are <b> on the board.
  lead: [
    { text: 'You only need two kinds, both in English: ' },
    { text: 'full subtitles', bold: true },
    { text: ' for films not in English, and ' },
    { text: 'forced subtitles', bold: true },
    {
      text: ' (just the foreign lines) for English films with scenes in another language. CineFilm finds and downloads both by itself; this page lists only what it couldn’t.',
    },
  ],
  tabsLabel: 'Subtitle lists', // planner, 4a
  tabs: {
    foreign: 'Foreign-language films', // AR2
    forced: 'Forced subtitles not found', // Q3 (AR2's "English films with foreign scenes" is replaced)
  },
  panels: {
    foreign: { heading: 'Need English subtitles' }, // AR2
    forced: { heading: 'Need forced subtitles' }, // planner, 4a
  },
  filmsCount: (n) => `${n} ${n === 1 ? 'film' : 'films'}`, // AR2 "4 films"; the singular is the planner's, 4a
  empty: {
    foreign: 'Nothing here. Every foreign-language film has English subtitles or is being checked.', // planner, 4a
    forced: 'Nothing here. No English film is waiting for forced subtitles.', // planner, 4a
  },
  loadErrors: {
    foreign: 'Couldn’t load the foreign-language films.',
    forced: 'Couldn’t load the films with forced subtitles to find.',
  }, // planner, 4a
  refreshErrors: {
    foreign: 'Couldn’t refresh the foreign-language films.',
    forced: 'Couldn’t refresh the films with forced subtitles to find.',
  }, // planner, 4a
  retry: 'Try again', // planner, 4a
  // The unlabelled-audio line. AR2 says "CineFilm assumed English", which is the reverse of what the server does
  // (it assumes the original language, so the film is needed), so the line says what actually happened.
  unlabelled: (language) =>
    language
      ? `Audio isn’t labelled · CineFilm assumed ${language}, the original language TMDB lists`
      : 'Audio isn’t labelled · CineFilm assumed the original language',
  addFile: {
    heading: (title) => `Add a subtitle file for ${title}`,
    file: 'Subtitle file',
    edition: 'Edition',
    timedFor: 'Timed for',
    attach: 'Attach',
    attaching: 'Attaching…',
    cancel: 'Cancel',
    loadingEditions: 'Loading that film’s editions…',
    noEdition: 'This film has no edition a subtitle file can be added to.',
    none: 'No full subtitle files are waiting. Put the file beside the film and scan the library; it will appear here.',
    loading: 'Looking for subtitle files…',
    loadError: 'Couldn’t load the subtitle files.',
    editionsError: 'Couldn’t load that film’s editions.',
    attachedTo: (film) => `on ${film}`,
    unattached: 'not attached to a film',
  }, // planner, 4a
  else: {
    heading: 'Everything else', // AR2
    haveSubtitles: (n) =>
      n === 1 ? 'foreign film has English subtitles' : 'foreign films have English subtitles', // AR2
    inEnglish: (n) => (n === 1 ? 'film is in English' : 'films are in English'), // AR2
    beingCheckedLabel: 'being checked online now', // AR2
    showFilms: (n) => (n === 1 ? 'Show the 1 film' : `Show the ${n} films`), // AR2; the singular is the planner's, 4a
    hideFilms: (n) => (n === 1 ? 'Hide the 1 film' : `Hide the ${n} films`), // planner, 4a
    showFiles: 'Show all subtitle files', // AR2
    hideFiles: 'Hide the subtitle files', // planner, 4a
    filmsList: 'Foreign films with English subtitles', // planner, 4a
    // Undo for the owner's choices (planner, 4a): the old panels were the only way back from "It's English" and friends.
    yourChoices: (n) => `Your choices (${n})`,
    yourChoicesList: 'Your choices',
    choiceLine: (kind, choice) =>
      `${kind === 'full' ? 'Full subtitles' : 'Forced subtitles'}: you said ${choice === 'NEEDED' ? 'needed' : 'not needed'}`,
    letDecideLabel: buttons.letCineFilmDecide.label, // 07
    letDecide: (title, kind) =>
      `${buttons.letCineFilmDecide.label}: ${title}, ${kind === 'full' ? 'full' : 'forced'} subtitles`,
    note: [
      { text: 'Subtitles look wrong on a film? Fix them from that film’s page (' },
      { text: 'Subtitles → Fix…', bold: true },
      {
        text: ') or in the player — that’s where timing, replacing a file and “This is wrong” now live, next to the film you’re watching.',
      },
    ], // AR2. Until Film detail has them, the per-file tools stay under "Show all subtitle files" (plan, 4a).
  },
  files: {
    heading: 'Subtitle files', // planner, 4a
    showMore: (left) => `Show more (${left} left)`, // planner, 4a
  },
  source: 'AR2; Q3 for the forced tab; planner 4a for the rest (see comments)',
};
