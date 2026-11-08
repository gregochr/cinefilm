// Dev-only: serves the test fixtures in place of the backend when VITE_FIXTURES=1.
import home from '../test/fixtures/home.json';
import films from '../test/fixtures/films.json';
import heat from '../test/fixtures/film-heat.json';
import bladeRunner from '../test/fixtures/film-blade-runner.json';
import wickerMan from '../test/fixtures/film-wicker-man.json';
import person from '../test/fixtures/person.json';
import collection from '../test/fixtures/collection.json';

const details = { 1: heat, 6: bladeRunner, 8: wickerMan };
const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function listFilms(search) {
  const q = (search.get('q') || '').toLowerCase();
  const sort = search.get('sort') || 'added';
  let list = films.films.filter((f) => f.title.toLowerCase().includes(q));
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
  if (route === '/api/films') return listFilms(url.searchParams);
  let match = route.match(/^\/api\/films\/(\d+)$/);
  if (match) return details[match[1]] || heat;
  if (/^\/api\/people\/\d+$/.test(route)) return person;
  if (/^\/api\/collections\/\d+$/.test(route)) return collection;
  if (/\/progress$/.test(route)) return { positionMs: body?.positionMs ?? 0, watched: false };
  if (/\/watched$/.test(route)) return { watched: !!body?.watched };
  if (/\/preferences$/.test(route)) return body;
  return null;
}
