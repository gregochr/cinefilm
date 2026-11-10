// Dev-only: serves the test fixtures in place of the backend when VITE_FIXTURES=1.
import { matchesAnyName } from '../state/search.js';
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
const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function listFilms(search) {
  const q = search.get('q') || '';
  const sort = search.get('sort') || 'added';
  let list = films.films.filter((f) => matchesAnyName(q, [f.title, f.director]));
  const show = search.get('show');
  if (show === 'watched') list = list.filter((f) => f.watched);
  if (show === 'unwatched') list = list.filter((f) => !f.watched);
  if (search.get('decade')) {
    const decade = Number(search.get('decade'));
    list = list.filter((f) => f.year >= decade && f.year < decade + 10);
  }
  if (sort === 'title') list = [...list].sort((a, b) => a.title.localeCompare(b.title));
  if (sort === 'year') list = [...list].sort((a, b) => a.year - b.year);
  return { total: list.length, films: list, facets: films.facets };
}

export async function handle(method, path, body) {
  await delay(80);
  const url = new URL(path, 'http://fixtures.local');
  const route = url.pathname;
  if (route === '/api/session') return method === 'GET' ? { authenticated: true } : { ok: true };
  if (route === '/api/home') return home;
  if (route === '/api/admin/scan') return adminScan;
  if (route === '/api/admin/attention') return adminAttention;
  if (route === '/api/admin/grouping-report') return adminGrouping;
  if (/^\/api\/admin\/films\/\d+\/match$/.test(route)) {
    return method === 'POST' ? { filmId: 31, joined: false } : adminMatch;
  }
  if (/^\/api\/admin\/films\/\d+\/editions$/.test(route)) {
    return method === 'PUT' ? { filmId: 40, newFilmIds: [] } : adminEditions;
  }
  if (route === '/api/admin/playback-health') return adminPlaybackHealth;
  if (route === '/api/admin/transcoding') return adminTranscoding;
  if (/^\/api\/admin\/transcoding\/sessions\//.test(route)) return null;
  if (/^\/api\/editions\/\d+\/sessions$/.test(route)) return session;
  if (/^\/api\/sessions\//.test(route)) return null;
  if (route === '/api/films') return listFilms(url.searchParams);
  let match = route.match(/^\/api\/films\/(\d+)$/);
  if (match) return details[match[1]] || heat;
  if (/^\/api\/people\/\d+$/.test(route)) return person;
  if (route === '/api/search-index') return buildFixtureSearchIndex();
  if (route === '/api/collections') return collections;
  if (/^\/api\/collections\/\d+$/.test(route)) return collection;
  if (/\/progress$/.test(route)) return { positionMs: body?.positionMs ?? 0, watched: false };
  if (/\/watched$/.test(route)) return { watched: !!body?.watched, previous: PREVIOUS };
  if (/\/continue-watching$/.test(route)) return { hidden: !!body?.hidden };
  if (/\/playback-state$/.test(route)) return body;
  if (/\/preferences$/.test(route)) return body;
  return null;
}
