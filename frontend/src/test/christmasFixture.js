import christmas from './fixtures/admin-christmas.json';

/**
 * A fresh copy of `fixtures/admin-christmas.json`, a `GET /api/admin/christmas` body shaped as the contract says: 14
 * films in (Trading Places added by the owner, so two fall below the cut), one added, three left out; the first two
 * rows have a TMDB poster path and a stored `/api/art/` path.
 */
export const christmasFixture = () => structuredClone(christmas);
