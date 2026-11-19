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
