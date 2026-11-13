// Display and rule helpers for Admin → Subtitles → "Full subtitles". No JSX: the Apple TV and native clients reuse
// them. The server decides each film's need; this only filters, words and counts what it sent.

/** The filter chips, in the order they are shown; `matches` says which rows a chip lists. */
export const FULL_NEED_FILTERS = [
  { key: 'needed', label: 'Needed', matches: (row) => row.need === 'NEEDED' },
  // A film TMDB has not been asked about has no answer yet, so it waits with the unsure ones.
  {
    key: 'unsure',
    label: 'Unsure',
    matches: (row) => row.need === 'UNSURE' || row.need === 'UNKNOWN',
  },
  { key: 'not-needed', label: 'Not needed', matches: (row) => row.need === 'NOT_NEEDED' },
];

/** The chip shown first. */
export const DEFAULT_FULL_NEED_FILTER = 'needed';

/**
 * The chip a list opens on, picked once from its counts: Needed, but when nothing is needed the first chip with films
 * (Unsure, then Not needed), so it never opens on "No films here" with films a tap away. Not recomputed on a refresh.
 */
export function initialFullNeedFilter(counts) {
  if (counts.needed > 0) return DEFAULT_FULL_NEED_FILTER;
  if (counts.unsure > 0) return 'unsure';
  if (counts['not-needed'] > 0) return 'not-needed';
  return DEFAULT_FULL_NEED_FILTER;
}

/** The rows a filter lists (none for an unknown key). */
export function filterFullNeed(rows, key) {
  const filter = FULL_NEED_FILTERS.find((f) => f.key === key);
  return filter ? rows.filter(filter.matches) : [];
}

/** How many rows each chip lists, by chip key; recomputed from the rows so a change needs no refetch. */
export function fullNeedCounts(rows) {
  return Object.fromEntries(FULL_NEED_FILTERS.map((f) => [f.key, rows.filter(f.matches).length]));
}

const NEED_WORDS = {
  NEEDED: 'Needed',
  NOT_NEEDED: 'Not needed',
  UNSURE: 'Unsure',
  UNKNOWN: 'Unsure',
};

/** "Needed", "Not needed" or "Unsure" (a film with no answer yet counts as unsure). */
export function fullNeedWords(need) {
  return NEED_WORDS[need] ?? 'Unsure';
}

const REASON_WORDS = {
  FOREIGN_AUDIO: 'Audio is in another language',
  ENGLISH_AUDIO: 'Audio is English',
  UNLABELLED_AUDIO: 'Audio language not labelled',
  ENGLISH_FILM: 'English-language film',
  OWNER: 'Set by you',
  NO_LANGUAGES: 'Waiting for TMDB',
};

/** The reason for a film's need in plain words; empty for a reason this screen does not know. */
export function fullNeedReason(row) {
  return REASON_WORDS[row.reason] ?? '';
}

/**
 * Where a film's full English subtitles come from, in one line: the owner's file, a download that plays unchecked, or
 * none yet. The server says which plays (`fullSubtitle.source`); nothing is derived here.
 */
export function fullSubtitleLine(row) {
  const source = row.fullSubtitle?.source;
  if (source === 'SIDECAR') return 'Playing: your file';
  if (source === 'DOWNLOAD') return 'Playing: download (not checked yet)';
  return 'None yet';
}

/** The language label of one edition's audio as shown: "French", or "not labelled" when the file says nothing. */
function audioWords(audio) {
  return audio && audio.trim() ? audio.trim() : 'not labelled';
}

/**
 * The audio of the film's copies: "Audio: French" for one edition, "Audio: Theatrical Cut: French; Director's Cut:
 * English" for several; empty when no edition is present.
 */
export function audioNote(row) {
  const editions = row.editions ?? [];
  if (editions.length === 0) return '';
  if (editions.length === 1) return `Audio: ${audioWords(editions[0].audio)}`;
  return `Audio: ${editions.map((e) => `${e.name}: ${audioWords(e.audio)}`).join('; ')}`;
}
