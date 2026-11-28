import { afterEach, describe, expect, it, vi } from 'vitest';
import { loadLinkMap, readLinkMap } from './linkMap.js';

afterEach(() => vi.unstubAllGlobals());

// Made-up places in mid-ocean, around 0.5 N 20 W.
const pt = (t, over = {}) => ({
  latitude: 0.5,
  longitude: -20 + t / 1000,
  courseDeg: 90,
  t,
  ...over,
});
const track = (over = {}) => ({
  points: [pt(0), pt(10), pt(20)],
  stretches: [{ from: 5, to: 15, need: 300 }],
  ...over,
});
const answer = (tracks) => ({ tracks });

function stubFetch(body, status = 200) {
  const fetchMock = vi.fn(async () => new Response(JSON.stringify(body), { status }));
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

describe('loadLinkMap: the request', () => {
  it('is a plain GET of the map with the session cookie, no body and no CSRF header', async () => {
    const fetchMock = stubFetch(answer([track()]));
    const controller = new AbortController();
    await loadLinkMap({ signal: controller.signal });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [path, init] = fetchMock.mock.calls[0];
    expect(path).toBe('/api/journeys/link-map');
    expect(init).toMatchObject({
      method: 'GET',
      credentials: 'same-origin',
      signal: controller.signal,
    });
    expect(init.body).toBeUndefined();
    expect(init.headers).toEqual({ Accept: 'application/json' });
  });

  it('returns the tracks of a good answer', async () => {
    stubFetch(answer([track()]));
    expect(await loadLinkMap()).toEqual([track()]);
  });

  it('writes nothing to any storage', async () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem');
    stubFetch(answer([track()]));
    await loadLinkMap();
    expect(setItem).not.toHaveBeenCalled();
    setItem.mockRestore();
  });
});

describe('loadLinkMap: failures give []', () => {
  it('on a 401, and tells the app the session expired like any other call', async () => {
    const listener = vi.fn();
    window.addEventListener('cinefilm:session-expired', listener);
    stubFetch({}, 401);
    expect(await loadLinkMap()).toEqual([]);
    expect(listener).toHaveBeenCalledTimes(1);
    window.removeEventListener('cinefilm:session-expired', listener);
  });

  it.each([404, 500])('on a %i', async (status) => {
    stubFetch({ error: 'no' }, status);
    expect(await loadLinkMap()).toEqual([]);
  });

  it('when the network fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('network')));
    expect(await loadLinkMap()).toEqual([]);
  });

  it('when the request is aborted', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new DOMException('aborted', 'AbortError')));
    expect(await loadLinkMap({ signal: AbortSignal.abort() })).toEqual([]);
  });

  it('when the body is not JSON', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response('<html>', { status: 200 })),
    );
    expect(await loadLinkMap()).toEqual([]);
  });
});

describe('readLinkMap', () => {
  it.each([null, undefined, 'x', 7, [], {}, { tracks: null }, { tracks: {} }])(
    'gives [] for %j',
    (value) => {
      expect(readLinkMap(value)).toEqual([]);
    },
  );

  it('drops a track with fewer than 2 good points, and keeps one with exactly 2', () => {
    const kept = track({ points: [pt(0), pt(10)] });
    const one = track({ points: [pt(0)] });
    const oneGood = track({ points: [pt(0), pt(10, { latitude: 'x' })] });
    const none = track({ points: [] });
    const notList = track({ points: 'x' });
    expect(readLinkMap(answer([one, kept, oneGood, none, notList, null, 4]))).toEqual([kept]);
  });

  it.each([
    ['a latitude out of range', { latitude: 90.1 }],
    ['a latitude below range', { latitude: -90.1 }],
    ['a longitude out of range', { longitude: 180.1 }],
    ['a longitude below range', { longitude: -180.1 }],
    ['a latitude that is text', { latitude: '0.5' }],
    ['a longitude that is not finite', { longitude: Infinity }],
    ['a negative time', { t: -1 }],
    ['a time that is text', { t: '5' }],
    ['a course that is text', { courseDeg: '90' }],
    ['a course that is not finite', { courseDeg: NaN }],
    ['a course that is missing', { courseDeg: undefined }],
  ])('drops a point with %s', (_name, over) => {
    const bad = pt(5, over);
    const [read] = readLinkMap(answer([track({ points: [pt(0), bad, pt(10), pt(20)] })]));
    expect(read.points.map((p) => p.t)).toEqual([0, 10, 20]);
  });

  it('drops a point that is null or not an object', () => {
    const [read] = readLinkMap(answer([track({ points: [null, 'x', pt(0), pt(10)] })]));
    expect(read.points.map((p) => p.t)).toEqual([0, 10]);
  });

  it('keeps the boundaries: poles, the date line, a time of 0 and a null course', () => {
    const edge = [
      pt(0, { latitude: 90, longitude: 180, courseDeg: null }),
      pt(1, { latitude: -90, longitude: -180, courseDeg: 0 }),
    ];
    expect(readLinkMap(answer([track({ points: edge })]))[0].points).toEqual(edge);
  });

  it('keeps only the named fields of a point', () => {
    const [read] = readLinkMap(
      answer([track({ points: [pt(0, { id: 7 }), pt(1, { journey: 'x' })] })]),
    );
    expect(Object.keys(read.points[0]).sort()).toEqual(['courseDeg', 'latitude', 'longitude', 't']);
  });

  it('puts points in time order', () => {
    const [read] = readLinkMap(answer([track({ points: [pt(20), pt(0), pt(10)] })]));
    expect(read.points.map((p) => p.t)).toEqual([0, 10, 20]);
  });

  it.each([
    ['from after to', { from: 20, to: 10, need: 300 }],
    ['a negative from', { from: -1, to: 10, need: 300 }],
    ['a need of 0', { from: 1, to: 10, need: 0 }],
    ['a negative need', { from: 1, to: 10, need: -5 }],
    ['text', { from: '1', to: 10, need: 300 }],
    ['a missing need', { from: 1, to: 10 }],
    ['a need that is not finite', { from: 1, to: 10, need: Infinity }],
    ['null', null],
    ['a number', 3],
  ])('drops a stretch with %s', (_name, bad) => {
    const good = { from: 5, to: 15, need: 300 };
    const [read] = readLinkMap(answer([track({ stretches: [bad, good] })]));
    expect(read.stretches).toEqual([good]);
  });

  it('keeps a stretch whose from and to are equal, and a track with no stretches or a bad list', () => {
    const point = { from: 5, to: 5, need: 1 };
    expect(readLinkMap(answer([track({ stretches: [point] })]))[0].stretches).toEqual([point]);
    expect(readLinkMap(answer([track({ stretches: [] })]))[0].stretches).toEqual([]);
    expect(readLinkMap(answer([track({ stretches: undefined })]))[0].stretches).toEqual([]);
    expect(readLinkMap(answer([track({ stretches: 'x' })]))[0].stretches).toEqual([]);
  });
});
