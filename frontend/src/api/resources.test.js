import { afterEach, describe, expect, it, vi } from 'vitest';
import { imageUrl } from './images.js';
import {
  adminApi,
  collectionsApi,
  editionsApi,
  filmsApi,
  homeApi,
  mediaFilesApi,
  peopleApi,
  sessionApi,
  sessionsApi,
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
    await collectionsApi.list();
    expect(last()).toMatchObject({ path: '/api/collections', method: 'GET' });
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
    await editionsApi.setHiddenFromContinueWatching(11, true);
    expect(last()).toEqual({
      path: '/api/editions/11/continue-watching',
      method: 'PUT',
      body: { hidden: true },
    });
    const snapshot = {
      positionMs: 5000,
      watched: true,
      watchedAt: '2024-03-01T10:00:00Z',
      hiddenFromContinueWatching: false,
      hiddenAt: null,
    };
    await editionsApi.restoreState(11, snapshot);
    expect(last()).toEqual({
      path: '/api/editions/11/playback-state',
      method: 'PUT',
      body: snapshot,
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

  it('reads the scan status and attention list, and starts a scan with POST', async () => {
    const last = stubFetch();
    await adminApi.scan();
    expect(last()).toMatchObject({ path: '/api/admin/scan', method: 'GET' });
    await adminApi.startScan();
    expect(last()).toEqual({ path: '/api/admin/scan', method: 'POST', body: undefined });
    await adminApi.startRatingsRefresh();
    expect(last()).toEqual({ path: '/api/admin/ratings/refresh', method: 'POST', body: undefined });
    await adminApi.attention();
    expect(last()).toMatchObject({ path: '/api/admin/attention', method: 'GET' });
  });

  it('builds the WebVTT url of a subtitle stream from the media file id and stream index', () => {
    expect(mediaFilesApi.subtitleUrl(501, 3)).toBe('/api/media-files/501/subtitles/3.vtt');
  });

  it('reads the transcoding state and stops a session with DELETE', async () => {
    const last = stubFetch();
    await adminApi.transcoding();
    expect(last()).toMatchObject({ path: '/api/admin/transcoding', method: 'GET' });
    await adminApi.stopTranscodingSession('abc');
    expect(last()).toEqual({
      path: '/api/admin/transcoding/sessions/abc',
      method: 'DELETE',
      body: undefined,
    });
  });

  it('creates a playback session with POST and removes it with DELETE', async () => {
    const last = stubFetch();
    await sessionsApi.create(11, { quality: 'auto', startMs: 5000, probeKbps: 8000 });
    expect(last()).toEqual({
      path: '/api/editions/11/sessions',
      method: 'POST',
      body: { quality: 'auto', startMs: 5000, probeKbps: 8000 },
    });
    await sessionsApi.remove('abc', { keepalive: true });
    expect(last()).toEqual({ path: '/api/sessions/abc', method: 'DELETE', body: undefined });
  });

  it('sends the session DELETE with keepalive and the CSRF header', async () => {
    const fetchMock = vi.fn(async () => new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetchMock);
    await sessionsApi.remove('abc', { keepalive: true });
    expect(fetchMock.mock.calls[0][1]).toMatchObject({
      keepalive: true,
      headers: expect.objectContaining({ 'X-CineFilm': '1' }),
    });
  });
});

describe('imageUrl for artwork stored by the server', () => {
  it('passes a /api/art/ path through unchanged, whatever the size', () => {
    const path = `/api/art/${'a'.repeat(64)}.jpg`;
    expect(imageUrl(path)).toBe(path);
    expect(imageUrl(path, 'w500')).toBe(path);
  });

  it('still builds TMDB URLs for other paths, including ones that merely contain api/art', () => {
    expect(imageUrl('/x/api/art/a.jpg')).toBe('https://image.tmdb.org/t/p/w342/x/api/art/a.jpg');
    expect(imageUrl('/api/artist.jpg')).toBe('https://image.tmdb.org/t/p/w342/api/artist.jpg');
  });
});
