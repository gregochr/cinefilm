// Every word on an Admin button, hint and section header, in one place. No JSX: the Apple TV and native clients reuse it.
// Each entry is { label, busyLabel?, hint?, source }; a `hint` is a string, or a function of the row's device name
// where the words depend on it ("Stop this stream"). `source` says where the string comes from:
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
  leaveUnmatched: {
    label: 'Leave unmatched',
    hint: 'Stop listing this film here. It stays in your library.',
    source: 'planner',
  },
  putBack: {
    label: 'Put back',
    hint: 'List this film under To do again so you can match it.',
    source: 'planner',
  },
  sortEditions: {
    label: 'Sort out editions…',
    hint: 'Choose which file is which cut, the part order and the default.',
    source: '07',
  },
  scanLibrary: {
    label: 'Scan library now',
    busyLabel: 'Scanning…',
    started: 'Scan started.', // announced (visually hidden) when a press starts a scan and the progress replaces the button
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
  // The label and the hint are 07's and AR4's ("Ends playback on the {device}; it can resume from here"). They are
  // true since Stop ends the playback on the device (owner 2026-11-15, the server answers 410 and the player offers
  // "Play again" from where it was). `hint(device)` takes the described device and says "that device" for an
  // unknown one; "the" is left out because a described device ("Safari on iPad") already reads as a name.
  stopStream: {
    label: 'Stop this stream',
    hint: (device) =>
      `Ends playback on ${device && device !== statusStrip.unknownDevice ? device : 'that device'}; it can resume from here`,
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
  // "Add my own file…" on a Subtitles row: the film is fixed, so 07's hint ("Picks which film…") would be wrong.
  addFileToFilm: {
    label: 'Add my own file…',
    hint: 'Picks one of your subtitle files for this film',
    source: 'planner, 4a',
  },
  // Unlabelled audio (AR2, row "Downfall"). "find subtitles" opens the picker (4c); AR2 shows no hint under it.
  itsInLanguage: {
    label: (language) => `It’s in ${language} — find subtitles`,
    source: 'AR2',
  },
  itsEnglish: {
    label: 'It’s English',
    hint: '“It’s English” marks it done and stops asking',
    source: 'AR2',
  },
  noneExist: {
    label: 'No English subtitles exist',
    hint: 'Marks it done and stops searching',
    source: 'owner 2026-11-17',
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
  // `buttons.stopStream.hint` for one row's device.
  stopHint: (device) => buttons.stopStream.hint(device),
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
  sourcesLabel: 'Watched or headless', // planner, journey-data-plan
  sources: [
    { key: 'person', label: 'Watched' }, // planner, journey-data-plan
    { key: 'robot', label: 'Headless' }, // planner, journey-data-plan
    { key: 'all', label: 'Both' }, // planner, journey-data-plan
  ],
  // The first tile's words and the summary's note, by the control above, so they stay true of what is counted.
  watchedBySource: {
    person: 'sessions watched', // planner, 5a-2 (AR4: "films watched")
    robot: 'headless runs', // planner, journey-data-plan
    all: 'sessions, headless included', // planner, journey-data-plan
  },
  summaryNotes: {
    person: '',
    robot: ' (headless runs only)', // planner, journey-data-plan
    all: ' (headless runs included)', // planner, journey-data-plan
  },
  headlessRun: 'Headless run', // planner, journey-data-plan
  tiles: {
    watched: 'sessions watched', // planner, 5a-2 (AR4: "films watched")
    withoutStall: 'played without a stall', // AR4
    stalled: 'stalled', // AR4
    typicalStart: 'typical start', // AR4
    topRungs: 'of time at Original or 1080p', // AR4
  },
  // AR4 says "(all on the train)"; the other places are the planner's wording (5a-2).
  allOn: { train: 'all on the train', home: 'all at home', away: 'all away' },
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
  stripLabel: (film, times) =>
    `Speed and quality minute by minute for ${film}${times ? `, ${times}` : ''}`, // AR4
  stripAxis: 'line = speed · strip = quality', // AR4
  stripStart: '0:00', // AR4
  loadingStripLabel: 'Loading the minute by minute view', // planner, 5a-2
  stripError: 'Couldn’t load that session’s minutes.', // planner, 5a-2
  stripErrorFor: (times) => `Couldn’t load the minutes for ${times}.`, // planner, joined viewings
  noMinutes: 'No minute by minute figures for this session.', // planner, 5a-2
  // The accessible names of a part's controls, told apart by its times when a viewing has more than one part.
  named: (base, times) => (times ? `${base}, ${times}` : base), // planner, joined viewings
  pickedUp: (seconds) => `Picked up again after ${seconds} s`, // planner, joined viewings
  paused: (time) => `Paused ${time}`, // planner, joined viewings
  source:
    'AR4 and 04 for the title, filters, tiles, legend, hint and strip words; the planner for the rest (5a-2)',
};

/**
 * The Playback section's "Playback log" card (AR4, `screens/04-playback.png`). Strings marked "planner" are not on
 * the board.
 */
export const playbackLog = {
  title: 'Playback log', // AR4
  why: 'Every time play was pressed · newest first · keeps the last 2,000', // AR4
  filtersLabel: 'Which plays to show', // planner, 5b
  filters: [
    { key: 'all', label: 'All' }, // AR4
    { key: 'films', label: 'Films only (3 min+)' }, // AR4
    { key: 'problems', label: 'With problems' }, // AR4
    { key: 'robots', label: 'Headless runs' }, // planner, journey-data-plan
  ],
  headlessRun: 'Headless run', // planner, journey-data-plan
  tableLabel: 'Playbacks, newest first', // planner, 5b
  columns: ['Started', 'Film', 'Watched', 'Where', 'Start', 'Stalls', 'Quality'], // AR4 (the eighth column, the buttons, has no heading)
  actionsColumn: 'Details', // planner, 5b: the screen-reader heading of AR4's unlabelled column
  browsing: 'browsing', // AR4
  details: 'Details', // AR4
  hideDetails: 'Hide details', // AR4
  // The accessible name of a row's Details button: its visible word, then which play (planner, 5b).
  buttonName: (label, film, started) => `${label}: ${film}, ${started}`,
  copyReport: 'Copy raw report', // AR4
  copied: 'Copied', // README ("Copied" toast)
  copyFailed: 'Couldn’t copy the report. Your browser did not allow it.', // planner, 5b
  showing: (shown, total) => `Showing ${shown} of ${total}`, // AR4
  showMore: 'Show 50 more', // AR4
  empty: {
    all: 'No playbacks recorded yet.', // planner, 5b (the old Playback health card's line)
    films: 'No plays of 3 minutes or more.', // planner, 5b
    problems: 'No plays with problems.', // planner, 5b
    robots: 'No headless runs.', // planner, journey-data-plan
  },
  loadingLabel: 'Loading the playback log', // planner, 5b
  loadError: 'Couldn’t load the playback log.', // planner, 5b
  moreError: 'Couldn’t load more. Try again.', // planner, 5b
  detailLabels: {
    player: 'Player', // AR4
    network: 'Network', // AR4
    quality: 'Quality', // AR4
    interruptions: 'Interruptions', // AR4
  },
  source:
    'AR4 and the README for the card, columns, filters, pill, details and toast; the planner for the rest (5b)',
};

/** The Playback section's "Conversion" card (AR4). "Pre-made versions" is not shown: nothing in the API counts them. */
export const conversion = {
  title: 'Conversion', // AR4
  seekIndex: 'Seek index', // AR4
  streamCache: 'Stream cache', // planner, 5b: the old Transcoding card's label; AR4 does not show it
  loadingLabel: 'Loading the conversion state', // planner, 5b
  loadError: 'Couldn’t load the conversion state.', // planner, 5b
  source: 'AR4 for the title and the Seek index label; the planner for the rest (5b)',
};

/** The Playback section's "Remembered networks" card (AR4). */
export const networks = {
  title: 'Remembered networks', // AR4
  // AR4's "This browser starts at the quality that worked last time" is not true (the start quality is measured each
  // time; this browser only remembers where you said you were watching).
  why: 'This browser remembers where you said you were watching', // planner, 5b
  empty: 'No networks remembered yet.', // planner, 5b
  forgetFailed: 'Couldn’t forget that network. Try again.', // planner, 5b
  source: 'AR4 for the title; the planner for the rest (5b)',
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
  // "Leave unmatched" failures (planner): a 404 is the same "gone" line.
  leaveProblems: {
    scan: 'A scan is running — try again when it finishes.',
    stale: 'That film is already settled. The list is being updated.',
    other: 'Couldn’t leave it — try again.',
  },
  // "Put back" failures (planner): a 404 is the same "gone" line.
  putBackProblems: {
    scan: 'A scan is running — try again when it finishes.',
    stale: 'This film is no longer left unmatched. The list is being updated.',
    other: 'Couldn’t put it back — try again.',
  },
  // The section below the groups for the films the owner left unmatched (planner).
  leftUnmatched: {
    heading: (n) => `Left unmatched (${n})`,
    why: 'Films you chose not to match. Put one back to see it under To do again.',
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
    empty: 'The last scan changed nothing.', // planner, 6
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
    tryAgain: 'Try again', // planner, 6
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

// How an owner choice reads in "Your choices"; NOT_NEEDED is the default wording.
const CHOICE_WORDS = {
  NEEDED: 'needed',
  NOT_NEEDED: 'not needed',
  NONE_EXIST: 'no English subtitles exist',
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
    // planner, 8b-2: the Fix panel's picker offers files of every kind
    noneAny:
      'None of your subtitle files are waiting. Put the file beside the film and scan the library; it will appear here.',
    // planner, 8b-2: shown instead of sending an attach that would change nothing (and clear the file's check)
    unchanged: 'That file is already there. Pick another edition or part.',
    none: 'No full subtitle files are waiting. Put the file beside the film and scan the library; it will appear here.',
    loading: 'Looking for subtitle files…',
    loadError: 'Couldn’t load the subtitle files.',
    editionsError: 'Couldn’t load that film’s editions.',
    attachedTo: (film) => `on ${film}`,
    // planner, 8b-2: a file already on the film, so it can be moved to another edition or part
    onThisFilm: (where) => (where ? `on this film · ${where}` : 'on this film'),
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
      `${kind === 'full' ? 'Full subtitles' : 'Forced subtitles'}: you said ${CHOICE_WORDS[choice] ?? 'not needed'}`,
    letDecideLabel: buttons.letCineFilmDecide.label, // 07
    letDecide: (title, kind) =>
      `${buttons.letCineFilmDecide.label}: ${title}, ${kind === 'full' ? 'full' : 'forced'} subtitles`,
    note: [
      { text: 'Subtitles look wrong on a film? Fix them from that film’s page (' },
      { text: 'Subtitles → Fix…', bold: true },
      {
        text: ') or in the player — that’s where timing, replacing a file and “Subtitles are wrong” now live, next to the film you’re watching.',
      },
    ], // AR2, except the player's action is “Subtitles are wrong” (AR7), not AR2's “This is wrong” (8a).
  },
  files: {
    heading: 'Subtitle files', // planner, 4a
    showMore: (left) => `Show more (${left} left)`, // planner, 4a
    offset: (shift) => `Timing shifted ${shift}`, // planner, 8b-2: the offset the old per-file Timing row showed (only when not 0)
  },
  source: 'AR2; Q3 for the forced tab; planner 4a for the rest (see comments)',
};

/** The Settings section (AR5, with the two switches dropped by the owner's answer Q2 left out). */
export const settingsPage = {
  continueLabel: 'Continue watching starts after', // AR5
  continueHint:
    'Films watched for less than this don’t appear in Continue watching, so trying a film doesn’t clutter it. 0–30 minutes.', // AR5
  continueUnit: 'min', // AR5
  continueCardLabel: 'Continue watching', // planner, 7 (AR5's card has no heading; this names its region)
  signedInLabel: 'Signed in on this browser', // AR5 (AR5's "Since 2 Nov." is left out: no sign-in date is kept)
  loadingLabel: 'the settings', // planner, 7 (read after "Loading ")
  loadError: 'Couldn’t load the settings.', // planner, 7 (from the existing Settings card)
  saveError: 'Could not save the setting.', // planner, 7 (existing; used when the server sent no message)
  saved: 'Saved', // planner, 7 (existing)
  credit: 'This product uses the TMDB API but is not endorsed or certified by TMDB.', // AR5
  // planner, settings credits (the other outside services; each line is text around linked parts)
  opensInNewTab: ' (opens in a new tab)', // planner, settings credits (appended to each credit link's name in its aria-label; the leading space keeps the two apart)
  dataCreditsLabel: 'Data credits', // planner, settings credits (names the list)
  dataCredits: [
    {
      key: 'opensubtitles',
      parts: [
        { text: 'Subtitles from ' },
        { text: 'OpenSubtitles.com', href: 'https://www.opensubtitles.com' },
        { text: '.' },
      ],
    },
    {
      key: 'omdb',
      parts: [
        { text: 'IMDb and Rotten Tomatoes ratings from ' },
        { text: 'OMDb', href: 'https://www.omdbapi.com' },
        { text: ', licensed ' },
        { text: 'CC BY-NC 4.0', href: 'https://creativecommons.org/licenses/by-nc/4.0/' },
        { text: '.' },
      ],
    },
    {
      key: 'ipinfo',
      parts: [
        { text: 'Network lookup by ' },
        { text: 'IPinfo', href: 'https://ipinfo.io' },
        { text: '.' },
      ],
    },
  ], // planner, settings credits
};

/**
 * The OpenSubtitles picker (4c). AR1, AR2 and the README give the buttons that open it (in `buttons`) and say "a picker
 * of OpenSubtitles results"; no board draws the dialog, so every string here is "planner, 4c" except the 429 text,
 * which is the server's own (`EditionOpenSubtitlesController.QUOTA`) and is the answer when a 429 carries none.
 */
export const subtitlePicker = {
  eyebrow: {
    full: 'English subtitles from OpenSubtitles',
    forced: 'Forced subtitles from OpenSubtitles',
  }, // planner, 4c
  lead: {
    full: 'Best matches for this cut come first. Choosing one downloads it and it plays with the film.',
    forced:
      'Only files that hold the foreign lines. Choosing one downloads it and it plays with the film.',
  }, // planner, 4c
  editionSuffix: (name) => `· ${name}`, // planner, 4c: which cut, for a film with several
  loading: 'Searching OpenSubtitles…', // planner, 4c
  searchError: 'Couldn’t search OpenSubtitles. Try again.', // planner, 4c
  downloadError: 'Couldn’t download that file. Try again.', // planner, 4c
  quota: 'OpenSubtitles daily limit reached — try again tomorrow.', // the server's text
  retry: 'Try again', // planner, 4c (the same words as the Subtitles lists)
  empty: 'OpenSubtitles has no files for this film yet.', // planner, 4c
  resultsLabel: 'OpenSubtitles results', // planner, 4c
  choose: { label: 'Use this file', busyLabel: 'Downloading…' }, // planner, 4c
  chooseName: (name) => `Use this file: ${name}`, // planner, 4c
  cancel: 'Cancel', // planner, 4c
  close: 'Close', // planner, 4c
  unnamed: (fileId) => `File ${fileId}`, // planner, 4c
  detail: {
    downloads: (n) => `${n.toLocaleString('en-GB')} ${n === 1 ? 'download' : 'downloads'}`,
    fps: (fps) => `${fps} fps`,
    trusted: 'Trusted uploader',
    hearingImpaired: 'Hearing impaired',
    uploaded: (year) => `Uploaded ${year}`,
  }, // planner, 4c
  left: (n) => `${n} more ${n === 1 ? 'file' : 'files'} can be added for this edition`, // planner, 4c
  source: 'planner, 4c (the 429 text is the server’s)',
};
