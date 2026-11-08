import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { vi } from 'vitest';
import App from '../App.jsx';
import collection from './fixtures/collection.json';
import filmsEmpty from './fixtures/films-empty.json';
import films from './fixtures/films.json';
import bladeRunner from './fixtures/film-blade-runner.json';
import heat from './fixtures/film-heat.json';
import wickerMan from './fixtures/film-wicker-man.json';
import adminAttention from './fixtures/admin-attention.json';
import adminScan from './fixtures/admin-scan.json';
import home from './fixtures/home.json';
import person from './fixtures/person.json';

export const fixtures = {
  home,
  films,
  filmsEmpty,
  heat,
  bladeRunner,
  wickerMan,
  person,
  collection,
  adminScan,
  adminAttention,
};

const json = (data, status = 200) =>
  new Response(data === null ? null : JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });

/** Default contract-shaped responses for every endpoint the app calls. */
export function defaultRoute(method, url) {
  const u = new URL(url, 'http://test.local');
  const path = u.pathname;
  if (path === '/api/home') return json(home);
  if (path === '/api/films') {
    const q = (u.searchParams.get('q') || '').toLowerCase();
    if (q === 'zzz') return json(filmsEmpty);
    const list = films.films.filter((f) => f.title.toLowerCase().includes(q));
    return json({ ...films, total: list.length, films: list });
  }
  if (path === '/api/films/1') return json(heat);
  if (path === '/api/films/6') return json(bladeRunner);
  if (path === '/api/films/8') return json(wickerMan);
  if (/^\/api\/people\//.test(path)) return json(person);
  if (/^\/api\/collections\//.test(path)) return json(collection);
  if (/\/progress$/.test(path)) return json({ positionMs: 0, watched: false });
  if (/\/watched$/.test(path)) return json({ watched: true });
  if (/\/preferences$/.test(path)) return json({});
  if (path === '/api/admin/scan') {
    return method === 'POST' ? json({ ...adminScan, running: true }, 202) : json(adminScan);
  }
  if (path === '/api/admin/attention') return json(adminAttention);
  if (path === '/api/session') return json({ authenticated: true });
  return json(null, 404);
}

/** Installs a fetch mock. `route(method, url, init)` may return a Response, else defaults apply. */
export function installFetch(route) {
  const mock = vi.fn(async (url, init = {}) => {
    const method = init.method || 'GET';
    return (route && route(method, url, init)) || defaultRoute(method, url);
  });
  vi.stubGlobal('fetch', mock);
  return mock;
}

export const respond = json;

export function renderApp(path = '/') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  );
}
