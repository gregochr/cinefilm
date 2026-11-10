import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  FIXTURE_ETAG,
  LIBRARY_CHANGED_EVENT,
  SESSION_EXPIRED_EVENT,
  announceLibraryChanged,
  getWithEtag,
} from './client.js';
import { searchApi } from './resources.js';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
  vi.useRealTimers();
});

const INDEX = { films: [], collections: [], people: [] };
const ok = (body, etag) =>
  vi.fn(
    async () =>
      new Response(JSON.stringify(body), {
        status: 200,
        headers: etag ? { ETag: etag } : {},
      }),
  );

describe('getWithEtag', () => {
  it('sends no If-None-Match without an etag, and returns the data and the ETag header', async () => {
    const fetchMock = ok(INDEX, '"abc"');
    vi.stubGlobal('fetch', fetchMock);

    const result = await getWithEtag('/api/search-index');

    expect(result).toEqual({ data: INDEX, etag: '"abc"' });
    const [path, init] = fetchMock.mock.calls[0];
    expect(path).toBe('/api/search-index');
    expect(init.method).toBe('GET');
    expect(init.headers).toEqual({ Accept: 'application/json' });
    expect(init.credentials).toBe('same-origin');
  });

  it('sends the etag as If-None-Match', async () => {
    const fetchMock = ok(INDEX, '"def"');
    vi.stubGlobal('fetch', fetchMock);

    await getWithEtag('/api/search-index', '"abc"');

    expect(fetchMock.mock.calls[0][1].headers['If-None-Match']).toBe('"abc"');
  });

  it('answers { notModified: true } on a 304 and reads no body', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(null, { status: 304, headers: { ETag: '"abc"' } })),
    );

    expect(await getWithEtag('/api/search-index', '"abc"')).toEqual({ notModified: true });
  });

  it('has a null etag when the server sent none', async () => {
    vi.stubGlobal('fetch', ok(INDEX));

    expect(await getWithEtag('/api/search-index')).toEqual({ data: INDEX, etag: null });
  });

  it('has null data for an empty 200 body', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response('', { status: 200 })),
    );

    expect(await getWithEtag('/api/x')).toEqual({ data: null, etag: null });
  });

  it('emits the session-expired event on a 401 and throws', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(null, { status: 401 })),
    );
    const listener = vi.fn();
    window.addEventListener(SESSION_EXPIRED_EVENT, listener);

    await expect(getWithEtag('/api/search-index', '"abc"')).rejects.toMatchObject({
      status: 401,
      message: 'Unauthorised',
    });

    window.removeEventListener(SESSION_EXPIRED_EVENT, listener);
    expect(listener).toHaveBeenCalledTimes(1);
  });

  it('throws an ApiError carrying the status for any other failure', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(null, { status: 500 })),
    );

    await expect(getWithEtag('/api/search-index')).rejects.toMatchObject({
      name: 'ApiError',
      status: 500,
    });
  });

  it('passes the abort signal to fetch', async () => {
    const fetchMock = ok(INDEX);
    vi.stubGlobal('fetch', fetchMock);
    const controller = new AbortController();

    await getWithEtag('/api/search-index', null, { signal: controller.signal });

    expect(fetchMock.mock.calls[0][1].signal).toBe(controller.signal);
  });

  it('serves the fixture index with a fixed ETag in fixture mode, and 304s on that ETag', async () => {
    vi.stubEnv('VITE_FIXTURES', '1');

    const result = await getWithEtag('/api/search-index');
    expect(result.etag).toBe(FIXTURE_ETAG);
    expect(result.data.films.length).toBeGreaterThan(0);

    expect(await getWithEtag('/api/search-index', FIXTURE_ETAG)).toEqual({ notModified: true });
  });
});

describe('searchApi.index', () => {
  it('is a conditional GET of /api/search-index', async () => {
    const fetchMock = ok(INDEX, '"abc"');
    vi.stubGlobal('fetch', fetchMock);
    const controller = new AbortController();

    const result = await searchApi.index({ etag: '"old"', signal: controller.signal });

    expect(result).toEqual({ data: INDEX, etag: '"abc"' });
    const [path, init] = fetchMock.mock.calls[0];
    expect(path).toBe('/api/search-index');
    expect(init.headers['If-None-Match']).toBe('"old"');
    expect(init.signal).toBe(controller.signal);
  });

  it('works with no options', async () => {
    const fetchMock = ok(INDEX, '"abc"');
    vi.stubGlobal('fetch', fetchMock);

    await searchApi.index();

    expect(fetchMock.mock.calls[0][1].headers['If-None-Match']).toBeUndefined();
  });
});

describe('announceLibraryChanged', () => {
  it('emits cinefilm:library-changed on window', () => {
    const listener = vi.fn();
    window.addEventListener(LIBRARY_CHANGED_EVENT, listener);

    announceLibraryChanged();

    window.removeEventListener(LIBRARY_CHANGED_EVENT, listener);
    expect(LIBRARY_CHANGED_EVENT).toBe('cinefilm:library-changed');
    expect(listener).toHaveBeenCalledTimes(1);
  });
});
