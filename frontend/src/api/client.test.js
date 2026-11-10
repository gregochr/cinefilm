import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  ApiError,
  SESSION_EXPIRED_EVENT,
  SIGNED_IN_EVENT,
  announceSignedIn,
  announceSignedOut,
  del,
  get,
  post,
  put,
  request,
} from './client.js';
import { imageUrl } from './images.js';
import { buildFilmsQuery } from './resources.js';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
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

describe('api client request shape', () => {
  it('sends JSON with an Accept header, and a Content-Type only when there is a body', async () => {
    const fetchMock = reply(200, {});
    vi.stubGlobal('fetch', fetchMock);
    await get('/api/home');
    await post('/api/session', { password: 'p' });
    const [getInit, postInit] = fetchMock.mock.calls.map((c) => c[1]);
    expect(getInit.headers).toEqual({ Accept: 'application/json' });
    expect(getInit.body).toBeUndefined();
    expect(postInit.headers['Content-Type']).toBe('application/json');
    expect(postInit.headers.Accept).toBe('application/json');
  });

  it('marks DELETE as mutating and sends no body', async () => {
    const fetchMock = reply(204);
    vi.stubGlobal('fetch', fetchMock);
    await del('/api/session');
    const [path, init] = fetchMock.mock.calls[0];
    expect(path).toBe('/api/session');
    expect(init.method).toBe('DELETE');
    expect(init.headers['X-CineFilm']).toBe('1');
    expect(init.body).toBeUndefined();
  });

  it('passes the abort signal through and sets keepalive only when asked', async () => {
    const fetchMock = reply(200, {});
    vi.stubGlobal('fetch', fetchMock);
    const controller = new AbortController();
    await request('PUT', '/api/editions/1/progress', {
      body: { positionMs: 1 },
      signal: controller.signal,
      keepalive: true,
    });
    await get('/api/home');
    const [first, second] = fetchMock.mock.calls.map((c) => c[1]);
    expect(first.signal).toBe(controller.signal);
    expect(first.keepalive).toBe(true);
    expect(second.keepalive).toBeUndefined();
  });

  it('returns null for a 200 with an empty body and parses a JSON body', async () => {
    vi.stubGlobal('fetch', reply(200));
    expect(await get('/api/x')).toBeNull();
    vi.stubGlobal('fetch', reply(200, { a: 1 }));
    expect(await get('/api/x')).toEqual({ a: 1 });
  });

  it('an API error carries its status and a default message, or the one given', () => {
    expect(new ApiError(503)).toMatchObject({
      name: 'ApiError',
      status: 503,
      message: 'Request failed (503)',
    });
    expect(new ApiError(401, 'Unauthorised').message).toBe('Unauthorised');
  });

  it('announces a sign-in, and does nothing where there is no window', () => {
    const listener = vi.fn();
    window.addEventListener(SIGNED_IN_EVENT, listener);
    announceSignedIn();
    window.removeEventListener(SIGNED_IN_EVENT, listener);
    expect(listener).toHaveBeenCalledTimes(1);
    vi.stubGlobal('window', undefined);
    expect(() => announceSignedIn()).not.toThrow();
  });

  it('serves from the fixture server instead of fetch when VITE_FIXTURES=1', async () => {
    vi.stubEnv('VITE_FIXTURES', '1');
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    await import('./fixtureServer.js'); // load it on the real clock; the call below only waits on its 80 ms delay
    vi.useFakeTimers();
    try {
      const pending = get('/api/session');
      await vi.advanceTimersByTimeAsync(100);
      expect(await pending).toEqual({ authenticated: true });
    } finally {
      vi.useRealTimers();
    }
    expect(fetchMock).not.toHaveBeenCalled();
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

describe('announceSignedOut', () => {
  it('dispatches the session-expired event with the signed-out reason', () => {
    const handler = vi.fn();
    window.addEventListener(SESSION_EXPIRED_EVENT, handler);
    announceSignedOut();
    window.removeEventListener(SESSION_EXPIRED_EVENT, handler);
    expect(handler).toHaveBeenCalledTimes(1);
    expect(handler.mock.calls[0][0].detail).toEqual({ reason: 'signed-out' });
  });
});

describe('api client: failed responses', () => {
  it('carries the JSON body of a failure (the field errors of a 400) on the error', async () => {
    const body = {
      error: 'Choose a default edition.',
      errors: [{ field: 'default', message: 'Choose a default edition.' }],
    };
    vi.stubGlobal('fetch', reply(400, body));

    await expect(put('/api/admin/films/1/editions', {})).rejects.toMatchObject({
      status: 400,
      body,
    });
  });

  it('has no body on the error when the failure sent none or sent something that is not JSON', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response('<html>nope</html>', { status: 502 })),
    );
    await expect(get('/api/x')).rejects.toMatchObject({ status: 502, body: null });
    vi.stubGlobal('fetch', reply(500));
    await expect(get('/api/x')).rejects.toMatchObject({ status: 500, body: null });
    expect(new ApiError(503).body).toBeNull();
  });
});
