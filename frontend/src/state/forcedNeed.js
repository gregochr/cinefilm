// Display and rule helpers for Admin → Subtitles → "Which films need them". No JSX: the Apple TV and native
// clients reuse them. The server decides each film's need; this only words what it sent.
import { ApiError } from '../api/client.js';
import { lockText } from './lockText.js';

/** The rows with one film's row replaced by the server's answer; the order is kept. */
export function applyRow(rows, row) {
  return rows.map((existing) => (existing.filmId === row.filmId ? row : existing));
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

/**
 * What is said beside a row when saving the owner's choice fails. A 409 shows the server's own words (what holds the
 * library lock), else the scan line.
 */
export function needProblem(failure) {
  if (failure instanceof ApiError && failure.status === 409) {
    return lockText(failure, 'A scan is running: try again when it finishes');
  }
  return "Couldn't save: try again";
}
