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
    editionId: 82,
    ...over,
  });

const needCount = (rows, need) => rows.filter((r) => r.need === need).length;

/** The full list's `counts`, in the API's shape; `english` is the English films outside the list (always counted). */
const fullCounts = (rows, english) => ({
  needed: needCount(rows, 'NEEDED'),
  unsure: needCount(rows, 'UNSURE'),
  unknown: needCount(rows, 'UNKNOWN'),
  notNeeded: needCount(rows, 'NOT_NEEDED'),
  unresolved: rows.filter((r) => r.unresolved).length,
  beingChecked: rows.filter((r) => r.beingChecked).length,
  subtitled: rows.filter((r) => r.subtitled).length,
  inEnglish: english + needCount(rows, 'NOT_NEEDED'),
});

/** The forced list's `counts`, in the API's shape. */
const forcedCounts = (rows) => ({
  needed: needCount(rows, 'NEEDED'),
  neededMissing: rows.filter((r) => r.need === 'NEEDED' && !r.hasForcedSubtitle).length,
  unsure: needCount(rows, 'UNSURE'),
  unknown: needCount(rows, 'UNKNOWN'),
  notNeeded: needCount(rows, 'NOT_NEEDED'),
  unresolved: rows.filter((r) => r.unresolved).length,
  beingChecked: rows.filter((r) => r.beingChecked).length,
});

/**
 * A stateful fake of the two need lists and their owner-choice routes, for the fetch boundary (`installFetch`):
 * a choice is saved the way the server saves it (the film is `OWNER`'s and no longer unresolved or being checked),
 * recorded in `calls`, and every later read of the list shows it, so a reload keeps it. `fail` is a function
 * `(kind) => Response | null` that turns a PUT into a failure; `english` is how many English films the full list
 * does not hold (they count as "in English"); `gate` holds PUTs until the test lets them through.
 *
 * It also serves the picker: a search answers the OpenSubtitles fixture for the edition and kind, and a download
 * (recorded in `downloads`) settles the film whose row names that edition, the way the server's rules would (a full
 * subtitle plays, or a forced one is served), so every later read of the list leaves the row out. `failDownload` is a
 * function `(body) => Response | null` that turns a download into a failure; `searchGate` holds searches.
 */
export function serveNeeds({
  full = [],
  forced = [],
  english = 0,
  fail = () => null,
  gate,
  failDownload = () => null,
  searchGate,
} = {}) {
  const lists = { full: structuredClone(full), forced: structuredClone(forced) };
  // The rows as the server first sent them: giving a choice back restores what the automatic rule said.
  const original = { full: structuredClone(full), forced: structuredClone(forced) };
  const calls = [];
  const downloads = [];
  const reads = { full: 0, forced: 0 };
  const route = (method, url, init) => {
    const path = new URL(url, 'http://test.local').pathname;
    if (path === '/api/admin/subtitles/full-need') {
      reads.full += 1;
      return respond({ films: lists.full, counts: fullCounts(lists.full, english) });
    }
    if (path === '/api/admin/forced-subtitles/films') {
      reads.forced += 1;
      return respond({ films: lists.forced, counts: forcedCounts(lists.forced) });
    }
    const picker = path.match(/^\/api\/admin\/editions\/(\d+)\/opensubtitles(\/download)?$/);
    if (picker) {
      const editionId = Number(picker[1]);
      if (!picker[2]) {
        const kind = new URL(url, 'http://test.local').searchParams.get('kind');
        const found = { ...structuredClone(fixtures.adminOpenSubtitles[kind]), editionId };
        return searchGate ? searchGate().then(() => respond(found)) : respond(found);
      }
      const body = JSON.parse(init.body);
      downloads.push({ editionId, ...body });
      return (async () => {
        const failure = failDownload(body);
        if (failure) return failure;
        const rows = lists[body.kind === 'full' ? 'full' : 'forced'];
        const row = rows.find((r) => r.editionId === editionId);
        if (row) {
          Object.assign(
            row,
            { unresolved: false, beingChecked: false },
            body.kind === 'full'
              ? { fullSubtitle: { source: 'DOWNLOAD' }, subtitled: true }
              : { hasForcedSubtitle: true, editionId: null },
          );
        }
        const { release } = fixtures.adminOpenSubtitles[body.kind].results.find(
          (r) => r.fileId === body.fileId,
        );
        return respond({ editionId, ...body, release, downloadsLeft: 2 });
      })();
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
      // Giving the choice back (`null`) restores the row as first sent (its need, reason and flags), with no choice.
      // A row that began as OWNER's therefore comes back with `reason: 'OWNER'` and a `null` choice.
      rows[at] =
        body.choice === null
          ? { ...original[kind][at], ownerChoice: null }
          : {
              ...rows[at],
              need: body.choice,
              reason: 'OWNER',
              ownerChoice: body.choice,
              unresolved: false,
              beingChecked: false,
              ...(kind === 'full'
                ? { subtitled: body.choice === 'NEEDED' && Boolean(rows[at].fullSubtitle) }
                : {}),
            };
      return respond(rows[at]);
    })();
  };
  return { route, calls, downloads, reads, lists };
}
