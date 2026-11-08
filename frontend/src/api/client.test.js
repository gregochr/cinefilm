import { afterEach, describe, expect, it, vi } from 'vitest';
import { SESSION_EXPIRED_EVENT, get, post, put } from './client.js';
import { imageUrl } from './images.js';
import { buildFilmsQuery } from './resources.js';

afterEach(() => {
  vi.unstubAllGlobals();
});

const reply = (status, body) =>
  vi.fn(async () => new Response(body === undefined ? null : JSON.stringify(body), { status }));

describe('api client', () => {
  it('adds X-CineFilm on mutating requests only', async () => {
    const fetchMock = reply(200, {});
    vi.stubGlobal('fetch', fetchMock);
    await get('/api/home');
    await put('/api/editions/1/progress', { positionMs: 5 });
    await post('/api/editions/1/watched', { watched: true });
    const [getInit, putInit, postInit] = fetchMock.mock.calls.map((c) => c[1]);
    expect(getInit.headers['X-CineFilm']).toBeUndefined();
    expect(putInit.headers['X-CineFilm']).toBe('1');
    expect(postInit.headers['X-CineFilm']).toBe('1');
    expect(putInit.credentials).toBe('same-origin');
    expect(putInit.body).toBe(JSON.stringify({ positionMs: 5 }));
  });

  it('emits the session-expired event on a 401', async () => {
    vi.stubGlobal('fetch', reply(401));
    const listener = vi.fn();
    window.addEventListener(SESSION_EXPIRED_EVENT, listener);
    await expect(get('/api/home')).rejects.toMatchObject({ status: 401 });
    window.removeEventListener(SESSION_EXPIRED_EVENT, listener);
    expect(listener).toHaveBeenCalledTimes(1);
  });

  it('does not treat a wrong password as an expired session', async () => {
    vi.stubGlobal('fetch', reply(401));
    const listener = vi.fn();
    window.addEventListener(SESSION_EXPIRED_EVENT, listener);
    await expect(post('/api/session', { password: 'x' })).rejects.toMatchObject({ status: 401 });
    window.removeEventListener(SESSION_EXPIRED_EVENT, listener);
    expect(listener).not.toHaveBeenCalled();
  });

  it('returns null for 204 and throws on other errors', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(null, { status: 204 })),
    );
    expect(await get('/api/x')).toBeNull();
    vi.stubGlobal('fetch', reply(500));
    await expect(get('/api/x')).rejects.toMatchObject({ status: 500 });
  });
});

describe('helpers', () => {
  it('builds TMDB URLs and tolerates null paths', () => {
    expect(imageUrl('/abc.jpg', 'w342')).toBe('https://image.tmdb.org/t/p/w342/abc.jpg');
    expect(imageUrl(null, 'w342')).toBeNull();
  });
  it('builds the films query, skipping empty values', () => {
    expect(buildFilmsQuery({ q: 'heat', sort: 'title', genre: '', decade: undefined })).toBe(
      '?q=heat&sort=title',
    );
    expect(buildFilmsQuery({})).toBe('');
  });
});
