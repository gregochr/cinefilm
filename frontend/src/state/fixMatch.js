// Display helpers for the Fix match panel. No JSX: the Apple TV and native clients reuse them.
import { ApiError } from '../api/client.js';
import { formatRuntime } from '../player/time.js';
import { titleWithYear } from './scanFormat.js';

/** A four-digit year, or null when the box is empty or not a year. */
export function parseYear(text) {
  const trimmed = String(text ?? '').trim();
  return /^\d{4}$/.test(trimmed) ? Number(trimmed) : null;
}

/** What the panel says when a search or an apply fails. */
export function matchProblem(failure) {
  const status = failure instanceof ApiError ? failure.status : 0;
  if (status === 503) return 'TMDB isn’t available right now. Try again in a moment.';
  if (status === 409) return 'A scan is running. Try again when it finishes.';
  if (status === 400) return 'Type a title to search for.';
  if (status === 404) return 'That film is no longer there. Close this and look again.';
  return 'Something went wrong. Try again.';
}

/** "Dune (1984)". */
export function resultTitle(result) {
  return titleWithYear(result.title, result.year);
}

/** "Robin Hardy · Edward Woodward, Christopher Lee": director, then cast; empty when TMDB gave neither. */
export function resultPeople(result) {
  const cast = (result.cast ?? []).join(', ');
  return [result.director, cast].filter(Boolean).join(' · ');
}

/** "1h 33m", or an empty string when TMDB has no runtime. */
export function resultRuntime(result) {
  return result.runtimeMinutes ? formatRuntime(result.runtimeMinutes * 60000) : '';
}
