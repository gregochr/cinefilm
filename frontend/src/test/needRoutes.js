import { fixtures, respond } from './mockApi.jsx';

/** A full-need row shaped like the API: the fixture's Rashomon, with what a test varies laid over it. */
export const fullRow = (over = {}) => ({
  ...structuredClone(fixtures.adminFullNeed.films[3]),
  ...over,
});

/** A forced-need row shaped like the API: the fixture's Taken, with what a test varies laid over it. */
export const forcedRow = (over = {}) => ({
  ...structuredClone(fixtures.adminForcedNeed.films[1]),
  ...over,
});

/** Unlabelled audio: NEEDED because the original language was assumed (`ORIGINAL_AUDIO_ASSUMED`). */
export const unlabelledRow = (over = {}) =>
  fullRow({
    filmId: 8,
    title: 'Downfall',
    year: 2004,
    reason: 'ORIGINAL_AUDIO_ASSUMED',
    ownerChoice: null,
    originalLanguage: 'de',
    editions: [{ name: 'Original', audio: null }],
    ...over,
  });

const counts = (rows) => ({
  unresolved: rows.filter((r) => r.unresolved).length,
  beingChecked: rows.filter((r) => r.beingChecked).length,
});

/**
 * A stateful fake of the two need lists and their owner-choice routes, for the fetch boundary (`installFetch`):
 * a choice is saved the way the server saves it (the film is `OWNER`'s and no longer unresolved or being checked),
 * recorded in `calls`, and every later read of the list shows it, so a reload keeps it. `fail` is a function
 * `(kind) => Response | null` that turns a PUT into a failure; `gate` holds PUTs until the test lets them through.
 */
export function serveNeeds({ full = [], forced = [], fail = () => null, gate } = {}) {
  const lists = { full: structuredClone(full), forced: structuredClone(forced) };
  const calls = [];
  const reads = { full: 0, forced: 0 };
  const route = (method, url, init) => {
    const path = new URL(url, 'http://test.local').pathname;
    if (path === '/api/admin/subtitles/full-need') {
      reads.full += 1;
      return respond({ films: lists.full, counts: counts(lists.full) });
    }
    if (path === '/api/admin/forced-subtitles/films') {
      reads.forced += 1;
      return respond({ films: lists.forced, counts: counts(lists.forced) });
    }
    const put = path.match(/^\/api\/admin\/films\/(\d+)\/(full|forced)-need$/);
    if (!put || method !== 'PUT') return null;
    const [, id, kind] = put;
    const body = JSON.parse(init.body);
    calls.push({ filmId: Number(id), kind, body });
    // A promise: a PUT may be held by `gate`, and a route that answers `null` must stay synchronous (`installFetch`).
    return (async () => {
      if (gate) await gate();
      const failure = fail(kind);
      if (failure) return failure;
      const rows = lists[kind];
      const at = rows.findIndex((r) => r.filmId === Number(id));
      rows[at] = {
        ...rows[at],
        need: body.choice,
        reason: 'OWNER',
        ownerChoice: body.choice,
        unresolved: false,
        beingChecked: false,
      };
      return respond(rows[at]);
    })();
  };
  return { route, calls, reads, lists };
}
