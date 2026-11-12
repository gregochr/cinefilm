import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { vi } from 'vitest';
import App from '../App.jsx';
import collection from './fixtures/collection.json';
import collections from './fixtures/collections.json';
import filmsEmpty from './fixtures/films-empty.json';
import films from './fixtures/films.json';
import bladeRunner from './fixtures/film-blade-runner.json';
import heat from './fixtures/film-heat.json';
import wickerMan from './fixtures/film-wicker-man.json';
import adminAttention from './fixtures/admin-attention.json';
import adminScan from './fixtures/admin-scan.json';
import adminGrouping from './fixtures/admin-grouping.json';
import adminEditions from './fixtures/admin-editions.json';
import adminMatch from './fixtures/admin-match.json';
import adminPlex from './fixtures/admin-plex.json';
import adminTranscoding from './fixtures/admin-transcoding.json';
import adminPlaybackHealth from './fixtures/admin-playback-health.json';
import adminSubtitles from './fixtures/admin-subtitles.json';
import adminForcedNeed from './fixtures/admin-forced-need.json';
import session from './fixtures/session.json';
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
  collections,
  adminScan,
  adminAttention,
  adminGrouping,
  adminEditions,
  adminMatch,
  adminPlex,
  adminTranscoding,
  adminPlaybackHealth,
  adminSubtitles,
  adminForcedNeed,
  session,
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
  if (path === '/api/search-index') return json({ films: [], collections: [], people: [] });
  if (path === '/api/collections') return json(collections);
  if (/^\/api\/collections\//.test(path)) return json(collection);
  if (/\/progress$/.test(path)) return json({ positionMs: 0, watched: false });
  if (/\/watched$/.test(path)) {
    return json({
      watched: true,
      previous: {
        positionMs: 3600000,
        watched: false,
        watchedAt: null,
        hiddenFromContinueWatching: false,
        hiddenAt: null,
      },
    });
  }
  if (/\/continue-watching$/.test(path)) return json({ hidden: true });
  if (/\/playback-state$/.test(path)) return json({});
  if (/\/preferences$/.test(path)) return json({});
  if (path === '/api/admin/scan') {
    return method === 'POST' ? json({ ...adminScan, running: true }, 202) : json(adminScan);
  }
  if (path === '/api/admin/ratings/refresh')
    return json({ ...adminScan, ratingsRunning: true }, 202);
  if (path === '/api/admin/attention') return json(adminAttention);
  if (path === '/api/admin/grouping-report') return json(adminGrouping);
  if (/^\/api\/admin\/films\/\d+\/match$/.test(path)) {
    return method === 'POST' ? json({ filmId: 31, joined: false }) : json(adminMatch);
  }
  if (/^\/api\/admin\/films\/\d+\/editions$/.test(path)) {
    return method === 'PUT' ? json({ filmId: 40, newFilmIds: [] }) : json(adminEditions);
  }
  if (path === '/api/admin/plex') {
    return method === 'POST'
      ? json({ ...adminPlex, running: true }, 202)
      : json({
          configured: false,
          reachable: false,
          running: false,
          progress: null,
          lastImport: null,
        });
  }
  if (path === '/api/admin/settings') {
    return json({ continueWatchingMinMinutes: 5, inProgressCount: 3 });
  }
  if (path === '/api/admin/playback-health') return json(adminPlaybackHealth);
  if (path === '/api/admin/subtitles') return json(adminSubtitles);
  if (path === '/api/admin/forced-subtitles/films') return json(adminForcedNeed);
  if (path === '/api/admin/transcoding') return json(adminTranscoding);
  if (/^\/api\/admin\/transcoding\/sessions\//.test(path)) return json(null, 204);
  if (/^\/api\/playback-health\/[^/]+$/.test(path)) return json(null, 204);
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
