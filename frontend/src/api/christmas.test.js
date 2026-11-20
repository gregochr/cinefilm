import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, serverText } from './client.js';
import { getChristmas, getChristmasStates, putChristmasChoice } from './christmas.js';

afterEach(() => vi.unstubAllGlobals());

const reply = (status, body) =>
  vi.fn(async () => new Response(body === undefined ? null : JSON.stringify(body), { status }));

describe('christmas api', () => {
  it('reads the screen data with a plain GET', async () => {
    const fetchMock = reply(200, { inWeek: [] });
    vi.stubGlobal('fetch', fetchMock);
    expect(await getChristmas()).toEqual({ inWeek: [] });
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/admin/christmas');
    expect(init.method).toBe('GET');
    expect(init.headers['X-CineFilm']).toBeUndefined();
  });

  it('PUTs the choice with the CSRF header and returns the new body', async () => {
    const fetchMock = reply(200, { inWeek: [1] });
    vi.stubGlobal('fetch', fetchMock);
    expect(await putChristmasChoice(106, 'EXCLUDE')).toEqual({ inWeek: [1] });
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/admin/christmas/106');
    expect(init.method).toBe('PUT');
    expect(init.headers['X-CineFilm']).toBe('1');
    expect(init.body).toBe(JSON.stringify({ choice: 'EXCLUDE' }));
  });

  it('sends a null choice to delete the override', async () => {
    const fetchMock = reply(200, {});
    vi.stubGlobal('fetch', fetchMock);
    await putChristmasChoice(106, null);
    expect(fetchMock.mock.calls[0][1].body).toBe(JSON.stringify({ choice: null }));
  });

  it('asks for the states of film ids, joined by commas', async () => {
    const fetchMock = reply(200, [{ filmId: 1, tmdbId: 101, state: 'NOT_IN' }]);
    vi.stubGlobal('fetch', fetchMock);
    expect(await getChristmasStates([1, 2, 3])).toEqual([
      { filmId: 1, tmdbId: 101, state: 'NOT_IN' },
    ]);
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/admin/christmas/states?filmIds=1,2,3');
    expect(init.method).toBe('GET');
  });

  it('answers an empty list of ids without a request', async () => {
    const fetchMock = reply(200, []);
    vi.stubGlobal('fetch', fetchMock);
    expect(await getChristmasStates([])).toEqual([]);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('surfaces the 409 message of a write refused while a scan runs', async () => {
    vi.stubGlobal('fetch', reply(409, { error: 'A scan is running — try again when it finishes' }));
    const failure = await putChristmasChoice(106, 'EXCLUDE').catch((e) => e);
    expect(failure).toBeInstanceOf(ApiError);
    expect(failure.status).toBe(409);
    expect(serverText(failure)).toBe('A scan is running — try again when it finishes');
  });
});
