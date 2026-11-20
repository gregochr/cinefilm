// The Christmas films admin API. No JSX. A refusal is an ApiError whose body carries the server's own words
// (`serverText`), e.g. the 409 for a write while a scan holds the library lock.
import { get, put } from './client.js';

const BASE = '/api/admin/christmas';

/** The whole screen's data: week, limits, keyword progress and the three lists. */
export const getChristmas = (options) => get(BASE, options);

/**
 * Sets the owner's choice for a TMDB id: `'INCLUDE'`, `'EXCLUDE'`, or null to delete it ("Put back", "Remove").
 * Answers with the same body as `getChristmas`.
 */
export const putChristmasChoice = (tmdbId, choice) =>
  put(`${BASE}/${encodeURIComponent(tmdbId)}`, { choice });

/**
 * Where a library film stands for Christmas week, for the film ids asked about: `[{ filmId, tmdbId|null, state }]`,
 * `state` one of `IN_KEYWORD | IN_ADDED | LEFT_OUT | NOT_IN | KEYWORDS_PENDING | NO_TMDB_ID`. No ids, no request.
 */
export const getChristmasStates = (filmIds, options) =>
  filmIds.length === 0
    ? Promise.resolve([])
    : get(`${BASE}/states?filmIds=${filmIds.map(encodeURIComponent).join(',')}`, options);
