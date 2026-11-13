// Display and rule helpers for Admin → Subtitles → "Which films need them". No JSX: the Apple TV and native
// clients reuse them. The server decides each film's need; this only filters, words and counts what it sent.
import { ApiError } from '../api/client.js';
import { titleWithYear } from './scanFormat.js';

/** How many rows are shown before "Show more". */
export const PAGE_SIZE = 100;

const isNeededMissing = (row) => row.need === 'NEEDED' && !row.hasForcedSubtitle;

/** The filter chips, in the order they are shown; `matches` says which rows a chip lists. */
export const NEED_FILTERS = [
  { key: 'needed-missing', label: 'Needed, none yet', matches: isNeededMissing },
  { key: 'unsure', label: 'Checking online', matches: (row) => row.need === 'UNSURE' },
  { key: 'unknown', label: 'Unknown', matches: (row) => row.need === 'UNKNOWN' },
  { key: 'not-needed', label: 'Not needed', matches: (row) => row.need === 'NOT_NEEDED' },
  { key: 'all', label: 'All', matches: () => true },
];

/** The chip shown first. */
export const DEFAULT_NEED_FILTER = 'needed-missing';

/** The rows a filter lists (all of them for an unknown key). */
export function filterRows(rows, key) {
  const filter = NEED_FILTERS.find((f) => f.key === key) ?? NEED_FILTERS[NEED_FILTERS.length - 1];
  return rows.filter(filter.matches);
}

/** How many rows each chip lists, by chip key; recomputed from the rows so a change needs no refetch. */
export function needCounts(rows) {
  return Object.fromEntries(NEED_FILTERS.map((f) => [f.key, rows.filter(f.matches).length]));
}

/** The rows with one film's row replaced by the server's answer; the order is kept. */
export function applyRow(rows, row) {
  return rows.map((existing) => (existing.filmId === row.filmId ? row : existing));
}

const NEED_WORDS = {
  NEEDED: 'Needed',
  NOT_NEEDED: 'Not needed',
  UNSURE: 'Checking online',
  UNKNOWN: 'Unknown',
};

/** "Needed", "Not needed", "Checking online" (not searched online yet), "Unknown". */
export function needWords(need) {
  return NEED_WORDS[need] ?? 'Unknown';
}

/** The owner's three choices, in the order they are shown; `value` is what the API takes (null = automatic). */
export const OWNER_CHOICES = [
  { value: 'NEEDED', label: 'Needed' },
  { value: 'NOT_NEEDED', label: 'Not needed' },
  { value: null, label: 'Automatic' },
];

/** "Heat (1995)". */
export function needTitle(row) {
  return titleWithYear(row.title, row.year);
}

/** "Plays" or "None yet" for a film that needs forced subtitles; null for any other. */
export function subtitleNote(row) {
  if (row.need !== 'NEEDED') return null;
  return row.hasForcedSubtitle ? 'Plays' : 'None yet';
}

let displayNames;

/** The English name of a language code ("fr" is "French"); the code itself when the runtime cannot name it. */
export function languageName(code) {
  try {
    displayNames ??= new Intl.DisplayNames(['en'], { type: 'language' });
    return displayNames.of(code) || code;
  } catch {
    return code;
  }
}

/** Forgets the cached name lookup (for tests that swap `Intl.DisplayNames`). */
export function resetLanguageNames() {
  displayNames = undefined;
}

/** "French, Russian": the spoken languages other than English, named. */
function otherLanguages(codes) {
  return (codes ?? [])
    .filter((code) => code.toLowerCase() !== 'en')
    .map(languageName)
    .join(', ');
}

/** The reason for a film's need in plain words; empty for a reason this screen does not know. */
export function reasonText(row) {
  switch (row.reason) {
    case 'SIDECAR':
      return 'Forced subtitle file attached';
    case 'EMBEDDED_FORCED':
      return "Forced track inside the file (pictures: can't be shown yet)";
    case 'FOREIGN_LANGUAGE_FILM':
      return 'Not in English: full subtitles cover it';
    case 'NO_FORCED_ONLINE':
      return 'No forced subtitles exist online';
    case 'ENGLISH_ONLY':
      return 'English only (TMDB)';
    case 'OTHER_LANGUAGES':
      return `Also spoken: ${otherLanguages(row.spokenLanguages) || 'other languages'} (TMDB)`;
    case 'NO_SPOKEN_LANGUAGES':
      return 'TMDB lists no spoken languages';
    case 'NO_LANGUAGES':
      return "TMDB hasn't been asked yet";
    case 'OWNER':
      return 'Your choice';
    case 'OPENSUBTITLES': {
      const n = row.openSubtitlesForcedCount;
      if (!(n > 0)) return 'OpenSubtitles has foreign-parts-only subtitles';
      return `OpenSubtitles has ${n} foreign-parts-only ${n === 1 ? 'file' : 'files'}`;
    }
    default:
      return '';
  }
}

/** What is said beside a row when saving the owner's choice fails; a running scan holds the library lock. */
export function needProblem(failure) {
  if (failure instanceof ApiError && failure.status === 409) {
    return 'A scan is running: try again when it finishes';
  }
  return "Couldn't save: try again";
}

/** "OpenSubtitles not searched yet" for an unsure or unknown film that has not been searched; null for any other. */
export function searchNote(row) {
  const open = row.need === 'UNSURE' || row.need === 'UNKNOWN';
  return open && row.openSubtitlesSearchedAt == null ? 'OpenSubtitles not searched yet' : null;
}
