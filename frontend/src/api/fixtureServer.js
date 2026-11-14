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
import adminGrouping from '../test/fixtures/admin-grouping.json';
import adminEditions from '../test/fixtures/admin-editions.json';
import adminMatch from '../test/fixtures/admin-match.json';
import adminTranscoding from '../test/fixtures/admin-transcoding.json';
import adminPlaybackHealth from '../test/fixtures/admin-playback-health.json';
import adminSubtitles from '../test/fixtures/admin-subtitles.json';
import adminForcedNeed from '../test/fixtures/admin-forced-need.json';
import adminFullNeed from '../test/fixtures/admin-full-need.json';
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
  },
];

const hasProblem = (report) =>
  [report.stallCount, report.reconnectCount, report.netErrors, report.timeouts].some(
    (n) => n > 0,
  ) || report.fellBack === true;

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
    .filter((p) => problems === null || hasProblem(p.report) === problems)
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

const CHOICES = new Set(['NEEDED', 'NOT_NEEDED']);
/**
 * The owner's choice per film, kept in memory: absent means the fixture as loaded, NEEDED or NOT_NEEDED an explicit
 * choice, null an explicit "Automatic" (the baseline below, with no owner choice).
 */
const forcedChoices = new Map();
/** What the automatic rule says for a fixture film that is loaded with an owner choice. */
const AUTOMATIC_BASELINE = { 7: { need: 'NOT_NEEDED', reason: 'ENGLISH_ONLY' } };

function forcedRow(loaded) {
  if (!forcedChoices.has(loaded.filmId)) return loaded;
  const choice = forcedChoices.get(loaded.filmId);
  if (choice) return { ...loaded, need: choice, reason: 'OWNER', ownerChoice: choice };
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

/** The owner's choice per film on full subtitles, kept in memory; a film without one has the fixture's own row. */
const fullChoices = new Map();

/** What the automatic rule says for a fixture film that is loaded with an owner choice (it is `OWNER` as loaded). */
const FULL_AUTOMATIC = { 6: { need: 'NEEDED', reason: 'ORIGINAL_AUDIO_ASSUMED' } };

function fullRow(loaded) {
  if (!fullChoices.has(loaded.filmId)) return loaded;
  const choice = fullChoices.get(loaded.filmId);
  if (choice) return { ...loaded, need: choice, reason: 'OWNER', ownerChoice: choice };
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

export async function handle(method, path, body) {
  await delay(80);
  const url = new URL(path, 'http://fixtures.local');
  const route = url.pathname;
  let match;
  if (route === '/api/session') return method === 'GET' ? { authenticated: true } : { ok: true };
  if (route === '/api/home') return liveHome();
  if (route === '/api/admin/scan') return adminScan;
  if (route === '/api/admin/attention') return adminAttention;
  if (route === '/api/admin/grouping-report') return adminGrouping;
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
    return adminSubtitles.filter((row) => !removedSubtitleIds.has(row.id));
  }
  match = route.match(/^\/api\/admin\/subtitles\/(\d+)$/);
  if (match && method === 'DELETE') return removeSubtitle(Number(match[1]));
  if (route === '/api/admin/forced-subtitles/films') return forcedNeedList();
  match = route.match(/^\/api\/admin\/films\/(\d+)\/forced-need$/);
  if (match && method === 'PUT') return setForcedNeed(Number(match[1]), body);
  if (route === '/api/admin/subtitles/full-need') return fullNeedList();
  match = route.match(/^\/api\/admin\/films\/(\d+)\/full-need$/);
  if (match && method === 'PUT') return setFullNeed(Number(match[1]), body);
  if (route === '/api/admin/playback-health') {
    return HEALTH_PARAMS.some((name) => url.searchParams.has(name))
      ? healthPage(url.searchParams)
      : adminPlaybackHealth;
  }
  if (route === '/api/admin/playback-health/chart') return healthChart(url.searchParams);
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
