// Dev-only: serves the test fixtures in place of the backend when VITE_FIXTURES=1.
import { matchesAnyName } from '../state/search.js';
import { ApiError } from './client.js';
import home from '../test/fixtures/home.json';
import films from '../test/fixtures/films.json';
import heat from '../test/fixtures/film-heat.json';
import bladeRunner from '../test/fixtures/film-blade-runner.json';
import wickerMan from '../test/fixtures/film-wicker-man.json';
import person from '../test/fixtures/person.json';
import adminScan from '../test/fixtures/admin-scan.json';
import adminAttention from '../test/fixtures/admin-attention.json';
import adminSummary from '../test/fixtures/admin-summary.json';
import adminGrouping from '../test/fixtures/admin-grouping.json';
import adminEditions from '../test/fixtures/admin-editions.json';
import adminMatch from '../test/fixtures/admin-match.json';
import adminPlex from '../test/fixtures/admin-plex.json';
import adminPlexCredits from '../test/fixtures/admin-plex-credits.json';
import adminTranscoding from '../test/fixtures/admin-transcoding.json';
import adminPlaybackHealth from '../test/fixtures/admin-playback-health.json';
import adminSubtitles from '../test/fixtures/admin-subtitles.json';
import adminForcedNeed from '../test/fixtures/admin-forced-need.json';
import adminFullNeed from '../test/fixtures/admin-full-need.json';
import adminOpenSubtitles from '../test/fixtures/admin-opensubtitles.json';
import collection from '../test/fixtures/collection.json';
import collections from '../test/fixtures/collections.json';
import session from '../test/fixtures/session.json';
import { FILM_SESSION_MIN_MS } from '../state/healthFormat.js';
import { buildFixtureSearchIndex } from './fixtureSearchIndex.js';

const PREVIOUS = {
  positionMs: 0,
  watched: false,
  watchedAt: null,
  hiddenFromContinueWatching: false,
  hiddenAt: null,
};
const details = { 1: heat, 6: bladeRunner, 8: wickerMan };
const MIN_MINUTES_LIMIT = 30;
let continueWatchingMinMinutes = 5;
const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const HEALTH_PARAMS = ['since', 'minWatchedMs', 'problems', 'page', 'size'];
const DEFAULT_HEALTH_SIZE = 20;
const CHART_SESSIONS = [
  {
    playbackId: 'c1000000-0000-4000-8000-000000000001',
    filmId: 6,
    filmTitle: 'Blade Runner',
    startedAt: '2026-10-20T20:00:00Z',
    watchedMs: 6_600_000,
    stallCount: 0,
    stallMs: 0,
    startupMs: 1200,
    environment: 'home',
    rungMs: { '1080p': 6_600_000 },
    userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)',
    touch: false,
  },
  {
    playbackId: 'c1000000-0000-4000-8000-000000000002',
    filmId: 8,
    filmTitle: 'The Wicker Man',
    startedAt: '2026-10-28T18:30:00Z',
    watchedMs: 60_000,
    stallCount: 0,
    stallMs: 0,
    startupMs: 900,
    environment: 'home',
    rungMs: { '720p': 60_000 },
    userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X)',
    touch: true,
  },
  {
    playbackId: 'c1000000-0000-4000-8000-000000000003',
    filmId: 1,
    filmTitle: 'Heat',
    startedAt: '2026-11-03T12:10:00Z',
    watchedMs: 4_320_000,
    stallCount: 3,
    stallMs: 21_000,
    startupMs: 3100,
    environment: 'away',
    rungMs: { '480p': 1_800_000, '360p': 2_520_000 },
    userAgent: 'Mozilla/5.0 (iPad; CPU OS 18_0 like Mac OS X)',
    touch: true,
  },
  {
    playbackId: 'c1000000-0000-4000-8000-000000000004',
    filmId: 1,
    filmTitle: 'Heat',
    startedAt: '2026-11-10T19:43:05Z',
    watchedMs: 4_320_000,
    stallCount: 2,
    stallMs: 9400,
    startupMs: 2380,
    environment: 'train',
    rungMs: { '480p': 1_468_800, '360p': 2_851_200 },
    userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)',
    touch: false,
  },
];

/**
 * The per-minute figures of a fixture session, as the player writes them: seven figures
 * `[minute, median kbit/s, rung played, ms moved, ms stalled, seconds buffered ahead, rung being fetched]` (rung codes
 * 1 original, 2 1080p, 3 720p, 4 480p, 5 360p), or the five of an older record (`seven` false) without the last two.
 * The speed swings with the minute, and a session that stalled stalls for 4.2 s in every minute whose number is 5 more
 * than a multiple of 17 (minutes 5, 22, 39, ...).
 */
function fixtureMinutes(chart, seven) {
  const count = Math.round(chart.watchedMs / 60_000);
  const code =
    Object.keys(chart.rungMs).length > 1
      ? 4
      : ({ original: 1, '1080p': 2, '720p': 3 }[Object.keys(chart.rungMs)[0]] ?? 4);
  return Array.from({ length: count }, (_, minute) => {
    const stalled = chart.stallCount > 0 && minute % 17 === 5 ? 4200 : 0;
    const row = [minute, 800 + ((minute * 37) % 9) * 150, code, 60_000 - stalled, stalled];
    return seven ? [...row, 8 + (minute % 5), code] : row;
  });
}

/** The whole record of one fixture session, as `GET /api/admin/playback-health/{id}` answers it. */
function healthRecord(playbackId) {
  const chart = CHART_SESSIONS.find((s) => s.playbackId === playbackId);
  if (chart) {
    const elapsedMs = chart.watchedMs + 30_000;
    return {
      playbackId,
      filmId: chart.filmId,
      filmTitle: chart.filmTitle,
      editionId: 10 + chart.filmId,
      startedAt: chart.startedAt,
      updatedAt: new Date(Date.parse(chart.startedAt) + elapsedMs).toISOString(),
      report: {
        seq: 9,
        editionId: 10 + chart.filmId,
        elapsedMs,
        environment: chart.environment,
        operator:
          chart.environment === 'train' ? 'AS12576 EE Limited' : 'AS5089 Virgin Media Limited',
        latencyMs: 48,
        engine: 'hlsjs',
        quality: 'auto',
        autoplayRefused: false,
        startupMs: chart.startupMs,
        watchedMs: chart.watchedMs,
        stallCount: chart.stallCount,
        stallMs: chart.stallMs,
        longestStallMs: chart.stallCount > 0 ? Math.round(chart.stallMs / chart.stallCount) : 0,
        seekCount: 0,
        seekWaitMs: 0,
        reloadCount: 0,
        reloadMs: 0,
        reconnectCount: 0,
        reconnectMs: 0,
        recoveries: 0,
        fellBack: false,
        stepsDown: 0,
        stepsUp: 0,
        netErrors: 0,
        timeouts: 0,
        kbpsLow: 800,
        kbpsMedian: 1500,
        kbpsHigh: 2000,
        kbpsSamples: 40,
        rungMs: chart.rungMs,
        // The first and third keep the five-figure rows an older player wrote.
        minutes: fixtureMinutes(chart, CHART_SESSIONS.indexOf(chart) % 2 === 1),
        client: { userAgent: chart.userAgent, screen: '1512x982', cores: 8, touch: chart.touch },
      },
    };
  }
  const listed = adminPlaybackHealth.playbacks.find((p) => p.playbackId === playbackId);
  if (!listed) throw new ApiError(404, 'No such playback');
  return { ...listed, report: { ...listed.report, minutes: [] } };
}

/** The fixture records with a problem; mirrors the stored has_problem column for them (Heat stalled and reconnected). */
const PROBLEM_PLAYBACKS = new Set(['3f6c1d2e-8a4b-4c7e-9b15-2d0e6f7a1c34']);

/** The filtered, paged list: the same filters and order as the server, `{ playbacks, total }`. */
function healthPage(params) {
  const since = params.get('since');
  const minWatched = params.has('minWatchedMs') ? Number(params.get('minWatchedMs')) : null;
  const problems = params.has('problems') ? params.get('problems') === 'true' : null;
  const page = Number(params.get('page') ?? 0);
  const size = Number(params.get('size') ?? DEFAULT_HEALTH_SIZE);
  const matching = adminPlaybackHealth.playbacks
    .filter((p) => !since || Date.parse(p.startedAt) >= Date.parse(since))
    .filter((p) => minWatched === null || p.report.watchedMs >= minWatched)
    .filter((p) => problems === null || PROBLEM_PLAYBACKS.has(p.playbackId) === problems)
    .toSorted((a, b) => Date.parse(b.startedAt) - Date.parse(a.startedAt));
  return { playbacks: matching.slice(page * size, page * size + size), total: matching.length };
}

/** Every film-length session since `since` (all when omitted), the newest last. */
function healthChart(params) {
  const since = params.get('since');
  return {
    sessions: CHART_SESSIONS.filter(
      (s) =>
        s.watchedMs >= FILM_SESSION_MIN_MS &&
        (!since || Date.parse(s.startedAt) >= Date.parse(since)),
    ),
  };
}

/** The minimum watch time in ms; a position of at least 1 ms is always needed. The single source for every card. */
const minWatchMs = () => Math.max(continueWatchingMinMinutes * 60000, 1);

/** The card with `inProgress` derived from its position and the current minimum, not the stored flag. */
const live = (card) => ({
  ...card,
  inProgress: !!card.progress && card.progress.positionMs >= minWatchMs(),
});

/** Home from the fixture's in-progress pool: hero, at most 12 after it, and the total, under the current minimum. */
function liveHome() {
  const continuing = [home.hero, ...home.continueWatching]
    .filter(Boolean)
    .map(live)
    .filter((f) => f.inProgress && !f.hiddenFromContinueWatching)
    .sort((a, b) => b.lastWatchedAt.localeCompare(a.lastWatchedAt));
  return {
    ...home,
    hero: continuing[0] ?? null,
    continueWatching: continuing.slice(1, 13),
    continueWatchingTotal: continuing.length,
    recentlyAdded: home.recentlyAdded.map(live),
    notWatchedInYears: home.notWatchedInYears.map(live),
    weeklyTheme: { ...home.weeklyTheme, films: home.weeklyTheme.films.map(live) },
  };
}

function livePerson() {
  return { ...person, films: person.films.map((entry) => ({ ...entry, film: live(entry.film) })) };
}

function liveCollection() {
  return {
    ...collection,
    parts: collection.parts.map((part) => (part.film ? { ...part, film: live(part.film) } : part)),
  };
}

function listFilms(search) {
  const q = search.get('q') || '';
  const sort = search.get('sort') || 'added';
  let list = films.films.map(live).filter((f) => matchesAnyName(q, [f.title, f.director]));
  // Show is exclusive, as on the server: In progress wins, then Watched, then Unwatched.
  const show = search.get('show');
  if (show === 'in-progress') list = list.filter((f) => f.inProgress);
  if (show === 'watched') list = list.filter((f) => f.watched && !f.inProgress);
  if (show === 'unwatched') list = list.filter((f) => !f.watched && !f.inProgress);
  if (search.get('decade')) {
    const decade = Number(search.get('decade'));
    list = list.filter((f) => f.year >= decade && f.year < decade + 10);
  }
  if (sort === 'title') list = [...list].sort((a, b) => a.title.localeCompare(b.title));
  if (sort === 'year') list = [...list].sort((a, b) => a.year - b.year);
  if (sort === 'last-watched') {
    // Newest first, never played last, ties by title.
    list = [...list].sort(
      (a, b) =>
        (b.lastWatchedAt ? 1 : 0) - (a.lastWatchedAt ? 1 : 0) ||
        (b.lastWatchedAt || '').localeCompare(a.lastWatchedAt || '') ||
        a.title.localeCompare(b.title),
    );
  }
  return { total: list.length, films: list, facets: films.facets };
}

/** The Admin settings: the fixture films at or above the minimum watch time count as in progress. */
function settingsView() {
  const inProgressCount = films.films.filter((f) => live(f).inProgress).length;
  return { continueWatchingMinMinutes, inProgressCount };
}

function updateSettings(body) {
  const minutes = body?.continueWatchingMinMinutes;
  if (!Number.isInteger(minutes) || minutes < 0 || minutes > MIN_MINUTES_LIMIT) {
    throw new ApiError(400, 'continueWatchingMinMinutes must be a whole number from 0 to 30');
  }
  continueWatchingMinMinutes = minutes;
  return settingsView();
}

/** The downloads removed so far, kept in memory: the list leaves them out, as the server would. */
const removedSubtitleIds = new Set();

function removeSubtitle(id) {
  const row = adminSubtitles.find((r) => r.id === id && !removedSubtitleIds.has(r.id));
  if (!row) throw new ApiError(404, 'No such subtitle file');
  if (row.origin !== 'OPENSUBTITLES') {
    throw new ApiError(400, 'Only a download can be removed');
  }
  removedSubtitleIds.add(id);
  return null;
}

/** Files attached by hand so far, by row id, kept in memory: the list shows them attached, as the server would. */
const attachedSubtitles = new Map();

/** The list as it stands: removed downloads left out, hand attachments laid over their rows. */
function subtitleList() {
  return adminSubtitles
    .filter((row) => !removedSubtitleIds.has(row.id))
    .map((row) => ({ ...row, ...attachedSubtitles.get(row.id) }));
}

/** The films a file was attached to by hand: their full-subtitle row then plays the owner's file. */
const filmsWithOwnFile = new Set();

/** The film and edition a fixture edition id belongs to, or null. */
function filmOfEdition(editionId) {
  for (const film of Object.values(details)) {
    const edition = film.editions.find((e) => e.id === editionId);
    if (edition) return { film, edition };
  }
  return null;
}

function attachSubtitle(id, body) {
  const row = adminSubtitles.find((r) => r.id === id && !removedSubtitleIds.has(r.id));
  if (!row) throw new ApiError(404, 'No such subtitle file');
  if (!Number.isInteger(body?.editionId)) throw new ApiError(400, 'editionId is required');
  const found = filmOfEdition(body.editionId);
  if (!found) throw new ApiError(404, 'No such edition');
  const part = body.part ?? null;
  attachedSubtitles.set(id, {
    state: 'matched',
    ownerAttached: true,
    filmId: found.film.id,
    filmTitle: found.film.title,
    editionId: found.edition.id,
    editionName: found.edition.name,
    parts: found.edition.parts ?? 1,
    part,
    whole: part === null,
  });
  filmsWithOwnFile.add(found.film.id);
  return subtitleList().find((r) => r.id === id);
}

const CHOICES = new Set(['NEEDED', 'NOT_NEEDED']);
/**
 * The owner's choice per film, kept in memory: absent means the fixture as loaded, NEEDED or NOT_NEEDED an explicit
 * choice, null an explicit "Automatic" (the baseline below, with no owner choice).
 */
const forcedChoices = new Map();
/** What the automatic rule says for a fixture film that is loaded with an owner choice. */
const AUTOMATIC_BASELINE = {
  7: { need: 'NOT_NEEDED', reason: 'ENGLISH_ONLY', unresolved: false, beingChecked: false },
};
/** A film that is not needed is in neither state; the server's rule is not rerun here. */
const NOT_IN_PLAY = { unresolved: false, beingChecked: false };
/**
 * What the server's rule would say for each fixture film if the owner said "needed": data worked out by hand from the
 * fixture's facts (a subtitle already plays, nothing online, files online with a due edition), not computed. It
 * assumes OpenSubtitles search and downloads are enabled, the usual dev setup.
 */
const FORCED_IF_NEEDED = {
  1: { unresolved: true, beingChecked: false, editionId: 11 },
  2: { unresolved: true, beingChecked: false, editionId: 101 },
  3: { unresolved: false, beingChecked: true, editionId: 31 },
  4: NOT_IN_PLAY,
  5: { unresolved: false, beingChecked: true, editionId: 51 },
  6: { unresolved: true, beingChecked: false, editionId: 61 },
  7: { unresolved: true, beingChecked: false, editionId: 71 },
};

/** The films whose forced subtitle a picker download gave them, kept in memory: a subtitle now plays. */
const filmsWithForcedDownload = new Set();

function forcedRow(loaded) {
  const row = forcedRowOfChoice(loaded);
  // A downloaded forced subtitle plays, so the film is settled: not unresolved, not being checked.
  return filmsWithForcedDownload.has(row.filmId)
    ? { ...row, hasForcedSubtitle: true, unresolved: false, beingChecked: false, editionId: null }
    : row;
}

function forcedRowOfChoice(loaded) {
  if (!forcedChoices.has(loaded.filmId)) return loaded;
  const choice = forcedChoices.get(loaded.filmId);
  if (choice) {
    const flags = choice === 'NEEDED' ? FORCED_IF_NEEDED[loaded.filmId] : NOT_IN_PLAY;
    // A row that is not unresolved or being checked has no picker edition, as the server leaves it.
    return {
      ...loaded,
      need: choice,
      reason: 'OWNER',
      ownerChoice: choice,
      editionId: null,
      ...flags,
    };
  }
  return { ...loaded, ...AUTOMATIC_BASELINE[loaded.filmId], ownerChoice: null };
}

function forcedNeedList() {
  const rows = adminForcedNeed.films.map(forcedRow);
  const count = (need) => rows.filter((r) => r.need === need).length;
  return {
    films: rows,
    counts: {
      needed: count('NEEDED'),
      neededMissing: rows.filter((r) => r.need === 'NEEDED' && !r.hasForcedSubtitle).length,
      unsure: count('UNSURE'),
      unknown: count('UNKNOWN'),
      notNeeded: count('NOT_NEEDED'),
      unresolved: rows.filter((r) => r.unresolved).length,
      beingChecked: rows.filter((r) => r.beingChecked).length,
    },
  };
}

function setForcedNeed(filmId, body) {
  const automatic = adminForcedNeed.films.find((f) => f.filmId === filmId);
  if (!automatic) throw new ApiError(404, 'No such film');
  const choice = body?.choice ?? null;
  if (choice !== null && !CHOICES.has(choice))
    throw new ApiError(400, 'choice must be NEEDED, NOT_NEEDED or null');
  forcedChoices.set(filmId, choice);
  return forcedRow(automatic);
}

/** English-original films outside the full-need list in the fixture library (they count as "in English"). */
const ENGLISH_OUT_OF_SCOPE = 530;

/** The owner's choice per film on full subtitles, kept in memory; a film without one has the fixture's own row. */
const fullChoices = new Map();

/** What the automatic rule says for a fixture film that is loaded with an owner choice (it is `OWNER` as loaded). */
const FULL_AUTOMATIC = { 6: { need: 'NEEDED', reason: 'ORIGINAL_AUDIO_ASSUMED' } };
/**
 * What the server's rule would say for each fixture film if the owner said "needed" (worked out by hand, as above).
 * Like the forced table, it assumes OpenSubtitles search and downloads are enabled, the usual dev setup: a film never
 * searched for full subtitles (Léon) is then being checked.
 */
const FULL_IF_NEEDED = {
  3: NOT_IN_PLAY,
  4: { unresolved: false, beingChecked: true },
  5: NOT_IN_PLAY,
  6: { unresolved: true, beingChecked: false },
  7: { unresolved: false, beingChecked: true },
};

/** The films a picker download gave a full subtitle, kept in memory: it plays now. */
const filmsWithFullDownload = new Set();

function fullRow(row) {
  const base = fullRowOfChoice(row);
  // A file attached by hand or picked from OpenSubtitles plays, so the film is settled: not unresolved, not being
  // checked.
  let settled = base;
  if (filmsWithFullDownload.has(row.filmId)) {
    settled = {
      ...base,
      fullSubtitle: { source: 'DOWNLOAD' },
      unresolved: false,
      beingChecked: false,
    };
  }
  if (filmsWithOwnFile.has(row.filmId)) {
    settled = {
      ...base,
      fullSubtitle: { source: 'SIDECAR' },
      unresolved: false,
      beingChecked: false,
    };
  }
  // The server's `subtitled` flag: needed, and a full subtitle plays (every fixture film shows in browse).
  return { ...settled, subtitled: settled.need === 'NEEDED' && Boolean(settled.fullSubtitle) };
}

function fullRowOfChoice(loaded) {
  if (!fullChoices.has(loaded.filmId)) return loaded;
  const choice = fullChoices.get(loaded.filmId);
  if (choice) {
    const flags = choice === 'NEEDED' ? FULL_IF_NEEDED[loaded.filmId] : NOT_IN_PLAY;
    return { ...loaded, need: choice, reason: 'OWNER', ownerChoice: choice, ...flags };
  }
  return { ...loaded, ...FULL_AUTOMATIC[loaded.filmId], ownerChoice: null };
}

function fullNeedList() {
  const rows = adminFullNeed.films.map(fullRow);
  const count = (need) => rows.filter((r) => r.need === need).length;
  return {
    films: rows,
    counts: {
      needed: count('NEEDED'),
      unsure: count('UNSURE'),
      unknown: count('UNKNOWN'),
      notNeeded: count('NOT_NEEDED'),
      unresolved: rows.filter((r) => r.unresolved).length,
      beingChecked: rows.filter((r) => r.beingChecked).length,
      subtitled: rows.filter((r) => r.subtitled).length,
      inEnglish: ENGLISH_OUT_OF_SCOPE + count('NOT_NEEDED'),
    },
  };
}

function setFullNeed(filmId, body) {
  const loaded = adminFullNeed.films.find((f) => f.filmId === filmId);
  if (!loaded) throw new ApiError(404, 'No such film');
  const choice = body?.choice ?? null;
  if (choice !== null && !CHOICES.has(choice))
    throw new ApiError(400, 'The choice must be NEEDED, NOT_NEEDED or null');
  fullChoices.set(filmId, choice);
  return fullRow(loaded);
}

/** An error the way the API answers one: the message and a JSON body `{ error }` (the client reads the body). */
function apiError(status, message) {
  return new ApiError(status, message, { error: message });
}

const OS_KINDS = new Set(['full', 'forced']);
const OS_MAX_FILES = 3;
const KNOWN_EDITION_IDS = new Set([
  ...adminSubtitles.map((row) => row.editionId),
  ...adminEditions.editions.map((edition) => edition.id),
  ...Object.values(details).flatMap((film) => film.editions.map((edition) => edition.id)),
]);
/** The files a picker search offered, and the ones downloaded since, per edition and kind, kept in memory. */
const offeredFiles = new Map();
const downloadedFiles = new Map();

function openSubtitlesKind(kind) {
  if (!OS_KINDS.has(kind)) throw apiError(400, 'The kind must be full or forced');
  return kind;
}

function openSubtitlesEdition(editionId) {
  if (!KNOWN_EDITION_IDS.has(editionId)) throw apiError(404, 'No such edition');
}

function filesLeft(key) {
  return OS_MAX_FILES - (downloadedFiles.get(key)?.size ?? 0);
}

/** The film an edition belongs to, as far as the fixtures say: `{ title, year }`, or Heat when they say nothing. */
function pickerFilm(editionId) {
  const row = [...adminFullNeed.films, ...adminForcedNeed.films].find(
    (r) => r.editionId === editionId,
  );
  if (row) return { title: row.title, year: row.year };
  const file = adminSubtitles.find((f) => f.editionId === editionId && f.filmTitle);
  if (file) return { title: file.filmTitle, year: null };
  const film = Object.values(details).find((d) => d.editions.some((e) => e.id === editionId));
  return film ? { title: film.title, year: film.year } : { title: 'Heat', year: 1995 };
}

/** The fixture results for a kind, with the release and file names made for the edition's own film. */
function openSubtitlesResults(editionId, kind) {
  const { title, year } = pickerFilm(editionId);
  const stem = [title.replace(/[^A-Za-z0-9]+/g, '.').replace(/^\.|\.$/g, ''), year]
    .filter(Boolean)
    .join('.');
  const rename = (name) => name && name.replace(/^Heat\.1995/, stem);
  return adminOpenSubtitles[kind].results.map((result) => ({
    ...result,
    release: rename(result.release),
    fileName: rename(result.fileName),
  }));
}

function searchOpenSubtitles(editionId, kind) {
  openSubtitlesKind(kind);
  openSubtitlesEdition(editionId);
  const key = `${editionId}:${kind}`;
  const found = adminOpenSubtitles[kind];
  offeredFiles.set(key, new Set(found.results.map((result) => result.fileId)));
  return {
    ...found,
    editionId,
    results: openSubtitlesResults(editionId, kind),
    downloadsLeft: filesLeft(key),
  };
}

/**
 * Two files of the dev mock answer with the errors the real server gives, so the picker's failure states can be tried
 * by hand: the lowest-ranked forced file (5013) hits the daily limit (429) and the second full file (6011) is one
 * OpenSubtitles charged for but could not deliver (502).
 */
const OS_FAILING_FILES = {
  5013: [429, 'OpenSubtitles daily limit reached — try again tomorrow.'],
  6011: [
    502,
    "OpenSubtitles counted this download against today's limit but could not deliver the file. Try again.",
  ],
};

/** The film whose row names this edition for the picker (as the list shows it now), settled by a picked file. */
function settleFilm(kind, editionId) {
  const full = kind === 'full';
  const rows = (full ? adminFullNeed.films : adminForcedNeed.films).map(full ? fullRow : forcedRow);
  const filmId = rows.find((row) => row.editionId === editionId)?.filmId;
  if (filmId !== undefined) (full ? filmsWithFullDownload : filmsWithForcedDownload).add(filmId);
}

function downloadOpenSubtitles(editionId, body) {
  const kind = openSubtitlesKind(body?.kind);
  if (!Number.isInteger(body?.fileId) || body.fileId <= 0) {
    throw apiError(400, 'A file id is required');
  }
  openSubtitlesEdition(editionId);
  const key = `${editionId}:${kind}`;
  if (!offeredFiles.get(key)?.has(body.fileId)) {
    throw apiError(400, 'That file was not in a recent search for this edition. Search again.');
  }
  if (OS_FAILING_FILES[body.fileId]) throw apiError(...OS_FAILING_FILES[body.fileId]);
  const taken = downloadedFiles.get(key) ?? new Set();
  if (taken.has(body.fileId)) throw apiError(409, "That file is already this edition's download.");
  downloadedFiles.set(key, taken.add(body.fileId));
  settleFilm(kind, editionId);
  const { release } = openSubtitlesResults(editionId, kind).find((r) => r.fileId === body.fileId);
  return { editionId, kind, fileId: body.fileId, release, downloadsLeft: filesLeft(key) };
}

const PLEX_NOT_CONFIGURED = {
  configured: false,
  reachable: false,
  running: false,
  progress: null,
  lastImport: null,
};
const PLEX_IMPORT_TOTAL = 40;
const PLEX_STEP = 10;
// Reads of the Plex status since an import was started with POST, or null when none is running.
let plexImportReads = null;

/**
 * The Plex status. Open the page with `?plex=unreachable`, `?plex=importing` or `?plex=none` (not configured) to see
 * those states; otherwise Plex is reachable with the last import of the fixture. "Import from Plex again" starts a
 * made-up import that advances by 10 of 40 films on each read and then ends.
 */
const plexRunning = (processed) => ({
  ...adminPlex,
  running: true,
  progress: { processed, total: PLEX_IMPORT_TOTAL, current: 'Heat' },
});

function plexStatus() {
  const variant = new URLSearchParams(globalThis.location?.search ?? '').get('plex');
  if (variant === 'none') return PLEX_NOT_CONFIGURED;
  if (variant === 'unreachable') return { ...adminPlex, reachable: false };
  if (variant === 'importing') return plexRunning(12);
  if (plexImportReads === null) return adminPlex;
  plexImportReads += 1;
  const processed = plexImportReads * PLEX_STEP;
  if (processed >= PLEX_IMPORT_TOTAL) {
    plexImportReads = null;
    return adminPlex;
  }
  return plexRunning(processed);
}

function startPlexImport() {
  plexImportReads = 0;
  return plexRunning(0);
}

export async function handle(method, path, body) {
  await delay(80);
  const url = new URL(path, 'http://fixtures.local');
  const route = url.pathname;
  let match;
  if (route === '/api/session') return method === 'GET' ? { authenticated: true } : { ok: true };
  if (route === '/api/home') return liveHome();
  if (route === '/api/admin/scan') return adminScan;
  if (route === '/api/admin/attention') return adminAttention;
  if (route === '/api/admin/summary') return adminSummary;
  if (route === '/api/admin/grouping-report') return adminGrouping;
  if (route === '/api/admin/plex') return plexStatus();
  if (route === '/api/admin/plex/import' && method === 'POST') return startPlexImport();
  if (route === '/api/admin/plex/credits') return adminPlexCredits;
  if (route === '/api/admin/plex/credits/import' && method === 'POST') {
    return { running: true, progress: { processed: 0, total: 0 }, lastImport: null };
  }
  if (/^\/api\/admin\/films\/\d+\/match$/.test(route)) {
    return method === 'POST' ? { filmId: 31, joined: false } : adminMatch;
  }
  if (/^\/api\/admin\/films\/\d+\/editions$/.test(route)) {
    return method === 'PUT' ? { filmId: 40, newFilmIds: [] } : adminEditions;
  }
  if (route === '/api/admin/settings') {
    return method === 'PUT' ? updateSettings(body) : settingsView();
  }
  if (route === '/api/admin/subtitles') {
    return subtitleList();
  }
  match = route.match(/^\/api\/admin\/subtitles\/(\d+)\/attach$/);
  if (match && method === 'POST') return attachSubtitle(Number(match[1]), body);
  match = route.match(/^\/api\/admin\/subtitles\/(\d+)$/);
  if (match && method === 'DELETE') return removeSubtitle(Number(match[1]));
  if (route === '/api/admin/forced-subtitles/films') return forcedNeedList();
  match = route.match(/^\/api\/admin\/films\/(\d+)\/forced-need$/);
  if (match && method === 'PUT') return setForcedNeed(Number(match[1]), body);
  if (route === '/api/admin/subtitles/full-need') return fullNeedList();
  match = route.match(/^\/api\/admin\/films\/(\d+)\/full-need$/);
  if (match && method === 'PUT') return setFullNeed(Number(match[1]), body);
  match = route.match(/^\/api\/admin\/editions\/(\d+)\/opensubtitles$/);
  if (match && method === 'GET') {
    return searchOpenSubtitles(Number(match[1]), url.searchParams.get('kind'));
  }
  match = route.match(/^\/api\/admin\/editions\/(\d+)\/opensubtitles\/download$/);
  if (match && method === 'POST') return downloadOpenSubtitles(Number(match[1]), body);
  if (route === '/api/admin/playback-health') {
    return HEALTH_PARAMS.some((name) => url.searchParams.has(name))
      ? healthPage(url.searchParams)
      : adminPlaybackHealth;
  }
  if (route === '/api/admin/playback-health/chart') return healthChart(url.searchParams);
  match = route.match(/^\/api\/admin\/playback-health\/([0-9a-f-]{36})$/);
  if (match) return healthRecord(match[1]);
  if (route === '/api/admin/transcoding') return adminTranscoding;
  if (/^\/api\/admin\/transcoding\/sessions\//.test(route)) return null;
  if (/^\/api\/editions\/\d+\/sessions$/.test(route)) return session;
  if (/^\/api\/sessions\//.test(route)) return null;
  if (route === '/api/films') return listFilms(url.searchParams);
  match = route.match(/^\/api\/films\/(\d+)$/);
  if (match) return details[match[1]] || heat;
  if (/^\/api\/people\/\d+$/.test(route)) return livePerson();
  if (route === '/api/search-index') return buildFixtureSearchIndex();
  if (route === '/api/collections') return collections;
  if (/^\/api\/collections\/\d+$/.test(route)) return liveCollection();
  if (/\/progress$/.test(route)) return { positionMs: body?.positionMs ?? 0, watched: false };
  if (/\/watched$/.test(route)) return { watched: !!body?.watched, previous: PREVIOUS };
  if (/\/continue-watching$/.test(route)) return { hidden: !!body?.hidden };
  if (/\/playback-state$/.test(route)) return body;
  if (/\/preferences$/.test(route)) return body;
  return null;
}
