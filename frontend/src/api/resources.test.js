import { afterEach, describe, expect, it, vi } from 'vitest';
import { imageUrl } from './images.js';
import {
  collectionsApi,
  editionsApi,
  filmsApi,
  homeApi,
  peopleApi,
  sessionApi,
} from './resources.js';

afterEach(() => vi.unstubAllGlobals());

// Resources are thin: they must hit the documented path with the documented verb and body.
function stubFetch() {
  const fetchMock = vi.fn(async () => new Response('{}', { status: 200 }));
  vi.stubGlobal('fetch', fetchMock);
  return () => {
    const [path, init] = fetchMock.mock.calls.at(-1);
    return { path, method: init.method, body: init.body && JSON.parse(init.body) };
  };
}

describe('API resources', () => {
  it('reads the home payload', async () => {
    const last = stubFetch();
    await homeApi.get();
    expect(last()).toMatchObject({ path: '/api/home', method: 'GET' });
  });

  it('lists and reads films, and saves preferences with PUT', async () => {
    const last = stubFetch();
    await filmsApi.list({ q: 'heat', show: '' });
    expect(last()).toMatchObject({ path: '/api/films?q=heat', method: 'GET' });
    await filmsApi.get(6);
    expect(last()).toMatchObject({ path: '/api/films/6', method: 'GET' });
    await filmsApi.savePreferences(6, { quality: 'original' });
    expect(last()).toEqual({
      path: '/api/films/6/preferences',
      method: 'PUT',
      body: { quality: 'original' },
    });
  });

  it('reads people and collections', async () => {
    const last = stubFetch();
    await peopleApi.get(11);
    expect(last()).toMatchObject({ path: '/api/people/11', method: 'GET' });
    await collectionsApi.get(2);
    expect(last()).toMatchObject({ path: '/api/collections/2', method: 'GET' });
  });

  it('streams an edition from its stream URL, reports progress by PUT and watched by POST', async () => {
    const last = stubFetch();
    expect(editionsApi.streamUrl(11)).toBe('/api/editions/11/stream');
    await editionsApi.reportProgress(11, 5000);
    expect(last()).toEqual({
      path: '/api/editions/11/progress',
      method: 'PUT',
      body: { positionMs: 5000 },
    });
    await editionsApi.setWatched(11, false);
    expect(last()).toEqual({
      path: '/api/editions/11/watched',
      method: 'POST',
      body: { watched: false },
    });
  });

  it('checks, signs in and signs out through /api/session', async () => {
    const last = stubFetch();
    await sessionApi.status();
    expect(last()).toMatchObject({ path: '/api/session', method: 'GET' });
    await sessionApi.signIn('changeme');
    expect(last()).toEqual({
      path: '/api/session',
      method: 'POST',
      body: { password: 'changeme' },
    });
    await sessionApi.signOut();
    expect(last()).toMatchObject({ path: '/api/session', method: 'DELETE' });
  });
});

describe('image URLs', () => {
  it('adds a missing leading slash and honours the size', () => {
    expect(imageUrl('abc.jpg', 'w500')).toBe('https://image.tmdb.org/t/p/w500/abc.jpg');
    expect(imageUrl('/abc.jpg')).toBe('https://image.tmdb.org/t/p/w342/abc.jpg');
  });

  it('has no URL for an empty path', () => {
    expect(imageUrl('')).toBeNull();
    expect(imageUrl(undefined)).toBeNull();
  });
});
