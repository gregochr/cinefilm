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
import adminForcedNeed from '../test/fixtures/admin-forced-need.json';
import collection from '../test/fixtures/collection.json';
import collections from '../test/fixtures/collections.json';
import session from '../test/fixtures/session.json';
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

const CHOICES = new Set(['NEEDED', 'NOT_NEEDED']);
/** The owner's choice per film, kept in memory; the fixture's own need and reason stand in for "automatic". */
const forcedChoices = new Map();

function forcedRow(automatic) {
  const choice = forcedChoices.get(automatic.filmId);
  return choice
    ? { ...automatic, need: choice, reason: 'OWNER', ownerChoice: choice }
    : { ...automatic, ...(forcedChoices.has(automatic.filmId) ? { ownerChoice: null } : {}) };
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
  if (choice === null) forcedChoices.delete(filmId);
  else forcedChoices.set(filmId, choice);
  return forcedRow(automatic);
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
  if (route === '/api/admin/forced-subtitles/films') return forcedNeedList();
  match = route.match(/^\/api\/admin\/films\/(\d+)\/forced-need$/);
  if (match && method === 'PUT') return setForcedNeed(Number(match[1]), body);
  if (route === '/api/admin/playback-health') return adminPlaybackHealth;
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
