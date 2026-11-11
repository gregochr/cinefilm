import { StrictMode } from 'react';
import { renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  REPORT_INTERVAL_MS,
  SEND_TIMEOUT_MS,
  START_GRACE_MS,
  newPlaybackId,
  useHealthReport,
} from './useHealthReport.js';

const ID = '0b9ab1a4-6d5e-4f3c-8a21-3c1f2d4e5a60';
const URL = `/api/playback-health/${ID}`;
const ok = () => new Response(null, { status: 204 });

beforeEach(() => {
  vi.useFakeTimers();
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' });
});

// Stubs fetch; `handler(url, init, n)` answers the nth call (default 204).
function stubFetch(handler = ok) {
  const fn = vi.fn((url, init) => handler(url, init, fn.mock.calls.length));
  vi.stubGlobal('fetch', fn);
  return fn;
}
// An answer that never comes, until the request is aborted.
const hang = (_url, init) =>
  new Promise((_resolve, reject) => {
    init.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')));
  });
const bodies = (fn) => fn.mock.calls.map(([, init]) => JSON.parse(init.body));
const mount = (props = {}, options = {}) =>
  renderHook((p) => useHealthReport(p), {
    initialProps: {
      editionId: 11,
      quality: 'auto',
      openedAt: Date.now(),
      now: () => Date.now(),
      makeId: () => ID,
      ...props,
    },
    ...options,
  });
const advance = (ms) => vi.advanceTimersByTimeAsync(ms);
const hidden = (value) =>
  Object.defineProperty(document, 'visibilityState', { configurable: true, value });

describe('the sends', () => {
  it('sends a PUT with the report at the first frame, at once', async () => {
    const fetchMock = stubFetch();
    const { result } = mount();
    expect(fetchMock).not.toHaveBeenCalled();
    await advance(2380);
    result.current.playing();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe(URL);
    expect(init.method).toBe('PUT');
    expect(init.headers['X-CineFilm']).toBe('1');
    expect(init.keepalive).toBeUndefined();
    expect(JSON.parse(init.body)).toMatchObject({
      seq: 1,
      editionId: 11,
      startupMs: 2380,
      environment: 'unknown',
      operator: null,
      latencyMs: null,
    });
  });

  it('sends only once for the first frame, not again at the next playing', async () => {
    const fetchMock = stubFetch();
    const { result } = mount();
    result.current.playing();
    result.current.playing();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('sends 20 s after mounting when there is still no first frame', async () => {
    const fetchMock = stubFetch();
    mount();
    await advance(START_GRACE_MS - 1);
    expect(fetchMock).not.toHaveBeenCalled();
    await advance(1);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(bodies(fetchMock)[0]).toMatchObject({ seq: 1, startupMs: null });
  });

  it('does not send at 20 s when the first frame has come', async () => {
    const fetchMock = stubFetch();
    const { result } = mount();
    await advance(5000);
    result.current.playing();
    result.current.paused();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    await advance(START_GRACE_MS);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('sends again at the first frame that follows the 20 s send', async () => {
    const fetchMock = stubFetch();
    const { result } = mount();
    await advance(START_GRACE_MS);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    await advance(3000);
    result.current.playing();
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(bodies(fetchMock)[1]).toMatchObject({ seq: 2, startupMs: 23000 });
  });

  it('sends every 60 s only when something has changed', async () => {
    const fetchMock = stubFetch();
    const { result } = mount();
    result.current.playing();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    await advance(REPORT_INTERVAL_MS - 1);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    await advance(1); // the picture moved for 1.5 s since: a change
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(bodies(fetchMock)[1]).toMatchObject({ seq: 2, watchedMs: 1500 });
    await advance(REPORT_INTERVAL_MS * 3); // nothing since
    expect(fetchMock).toHaveBeenCalledTimes(2);
    result.current.engine('hlsjs');
    await advance(REPORT_INTERVAL_MS);
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(bodies(fetchMock)[2]).toMatchObject({ seq: 3, engine: 'hlsjs' });
  });

  it('sends nothing for an idle, paused player', async () => {
    const fetchMock = stubFetch();
    const { result } = mount();
    result.current.playing();
    result.current.paused();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    await advance(REPORT_INTERVAL_MS * 5);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('starts no second timed send while one is in flight', async () => {
    const fetchMock = stubFetch(hang);
    const { result } = mount();
    await advance(50000); // the 20 s send has gone out and been aborted at 35 s
    expect(fetchMock).toHaveBeenCalledTimes(1);
    result.current.playing(); // goes out at 50 s and hangs until 65 s
    expect(fetchMock).toHaveBeenCalledTimes(2);
    await advance(10000); // 60 s: the tick finds a send in flight
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('aborts a timed send at 15 s, which frees the next tick', async () => {
    const fetchMock = stubFetch(hang);
    const { result } = mount();
    result.current.playing();
    const { signal } = fetchMock.mock.calls[0][1];
    await advance(SEND_TIMEOUT_MS - 1);
    expect(signal.aborted).toBe(false);
    await advance(1);
    expect(signal.aborted).toBe(true);
    await advance(REPORT_INTERVAL_MS); // the tick at 60 s: nothing in flight any more
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(bodies(fetchMock)[1].seq).toBe(2);
  });

  it('retries a failed send at the next tick because the version still differs', async () => {
    const fetchMock = stubFetch((_url, _init, n) =>
      n === 1 ? Promise.reject(new TypeError('Failed to fetch')) : ok(),
    );
    const { result } = mount();
    result.current.playing();
    result.current.paused();
    await advance(REPORT_INTERVAL_MS);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    await advance(REPORT_INTERVAL_MS * 3); // that one succeeded: nothing more
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('does not retry after a send that succeeded', async () => {
    const fetchMock = stubFetch();
    const { result } = mount();
    result.current.playing();
    result.current.paused();
    await advance(REPORT_INTERVAL_MS * 3);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('a send that resolves late does not take back a newer one that already succeeded', async () => {
    const pending = [];
    const fetchMock = stubFetch(
      () =>
        new Promise((resolve) => {
          pending.push(() => resolve(ok()));
        }),
    );
    const { result } = mount();
    result.current.playing(); // A: the first frame
    result.current.paused();
    result.current.engine('hlsjs');
    window.dispatchEvent(new Event('pagehide')); // B: newer
    expect(fetchMock).toHaveBeenCalledTimes(2);
    pending[1]();
    await advance(0);
    pending[0](); // A resolves last
    await advance(0);
    window.dispatchEvent(new Event('pagehide')); // nothing is new since B
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
});

describe('a send that finishes', () => {
  it('frees its abort timer and the next tick at once', async () => {
    stubFetch();
    const { result } = mount();
    const base = vi.getTimerCount();
    result.current.playing();
    await advance(0);
    expect(vi.getTimerCount()).toBe(base);
  });

  it('lets the tick at 60 s send even when the first-frame send was at 50 s', async () => {
    const fetchMock = stubFetch();
    const { result } = mount();
    await advance(50000);
    result.current.playing();
    await advance(10000);
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it('is forgotten at 15 s even when fetch ignores the abort', async () => {
    const fetchMock = stubFetch(() => new Promise(() => {}));
    const { result } = mount();
    result.current.playing();
    await advance(SEND_TIMEOUT_MS);
    await advance(REPORT_INTERVAL_MS - SEND_TIMEOUT_MS);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
});

describe('failures', () => {
  it('swallows a rejection that carries no error at all, and keeps sending', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const error = vi.spyOn(console, 'error').mockImplementation(() => {});
    const fetchMock = stubFetch(() => Promise.reject(undefined));
    const { result, unmount } = mount();
    expect(() => result.current.playing()).not.toThrow();
    await advance(0);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    await advance(REPORT_INTERVAL_MS); // nothing succeeded, so the tick sends again
    expect(fetchMock).toHaveBeenCalledTimes(2);
    unmount(); // and so does leaving
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(fetchMock.mock.calls[2][1].keepalive).toBe(true);
    await advance(0);
    expect(warn).not.toHaveBeenCalled();
    expect(error).not.toHaveBeenCalled();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('warns once per viewing about a 400, and about nothing else', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const fetchMock = stubFetch(
      () => new Response('{"error":"Invalid health report"}', { status: 400 }),
    );
    const { result } = mount();
    result.current.playing();
    await advance(0);
    expect(warn).toHaveBeenCalledTimes(1);
    await advance(REPORT_INTERVAL_MS);
    await advance(REPORT_INTERVAL_MS);
    expect(fetchMock).toHaveBeenCalledTimes(3); // the first frame, then the ticks at 60 s and 120 s
    expect(warn).toHaveBeenCalledTimes(1);
  });

  it('does not warn about a 500, a network failure or a 401', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const error = vi.spyOn(console, 'error').mockImplementation(() => {});
    const log = vi.spyOn(console, 'log').mockImplementation(() => {});
    const answers = [
      () => new Response(null, { status: 500 }),
      () => Promise.reject(new TypeError('Failed to fetch')),
      () => new Response(null, { status: 401 }),
    ];
    stubFetch((_url, _init, n) => answers[n - 1]());
    const { result } = mount();
    result.current.playing();
    await advance(0);
    result.current.engine('a');
    window.dispatchEvent(new Event('pagehide'));
    await advance(0);
    result.current.engine('b');
    window.dispatchEvent(new Event('pagehide'));
    await advance(0);
    expect(warn).not.toHaveBeenCalled();
    expect(error).not.toHaveBeenCalled();
    expect(log).not.toHaveBeenCalled();
  });

  it('swallows a send when fetch is gone altogether, and sends again once it is back', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const error = vi.spyOn(console, 'error').mockImplementation(() => {});
    vi.stubGlobal('fetch', undefined);
    const { result, unmount } = mount();
    expect(() => result.current.playing()).not.toThrow();
    await advance(START_GRACE_MS + REPORT_INTERVAL_MS);
    expect(() => window.dispatchEvent(new Event('pagehide'))).not.toThrow();
    expect(vi.getTimerCount()).toBe(1); // the interval is still armed; no abort timer is left over
    const fetchMock = stubFetch();
    await advance(REPORT_INTERVAL_MS);
    expect(fetchMock).toHaveBeenCalledTimes(1); // nothing had succeeded, so the tick sends
    unmount();
    await advance(0);
    expect(warn).not.toHaveBeenCalled();
    expect(error).not.toHaveBeenCalled();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('swallows a fetch that throws at once, and keeps sending', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const error = vi.spyOn(console, 'error').mockImplementation(() => {});
    const fetchMock = stubFetch(() => {
      throw new Error('boom');
    });
    const { result, unmount } = mount();
    expect(() => result.current.playing()).not.toThrow();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    await advance(REPORT_INTERVAL_MS);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    unmount();
    expect(fetchMock).toHaveBeenCalledTimes(3);
    await advance(0);
    expect(warn).not.toHaveBeenCalled();
    expect(error).not.toHaveBeenCalled();
    expect(vi.getTimerCount()).toBe(0);
  });
});

describe('leaving', () => {
  it('sends with keepalive on pagehide when something changed, and not otherwise', async () => {
    const fetchMock = stubFetch();
    const { result } = mount();
    result.current.playing();
    result.current.paused();
    await advance(0);
    window.dispatchEvent(new Event('pagehide')); // nothing since the last send
    expect(fetchMock).toHaveBeenCalledTimes(1);
    result.current.engine('hlsjs');
    window.dispatchEvent(new Event('pagehide'));
    expect(fetchMock).toHaveBeenCalledTimes(2);
    const [, init] = fetchMock.mock.calls[1];
    expect(init.keepalive).toBe(true);
    expect(init.signal).toBeUndefined();
    expect(JSON.parse(init.body)).toMatchObject({ seq: 2, engine: 'hlsjs' });
  });

  it('sends with keepalive when the page is hidden, and not when it becomes visible', async () => {
    const fetchMock = stubFetch();
    const { result } = mount();
    result.current.playing();
    result.current.paused();
    await advance(0);
    result.current.recovered();
    hidden('visible');
    document.dispatchEvent(new Event('visibilitychange'));
    expect(fetchMock).toHaveBeenCalledTimes(1);
    hidden('hidden');
    document.dispatchEvent(new Event('visibilitychange'));
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(fetchMock.mock.calls[1][1].keepalive).toBe(true);
  });

  it('sends with keepalive when the player is left, only when something changed', async () => {
    const fetchMock = stubFetch();
    const { result, unmount } = mount();
    result.current.playing();
    result.current.paused();
    await advance(0);
    result.current.fellBack();
    unmount();
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(fetchMock.mock.calls[1][1].keepalive).toBe(true);
    expect(bodies(fetchMock)[1]).toMatchObject({ fellBack: true });

    const quiet = stubFetch();
    const second = mount();
    second.unmount();
    expect(quiet).not.toHaveBeenCalled();
  });

  it('leaving before the first frame sends the viewing, which has already changed', async () => {
    const fetchMock = stubFetch();
    const { result, unmount } = mount();
    result.current.engine('hlsjs');
    unmount();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0][1].keepalive).toBe(true);
  });

  it('keeps no timer, no listener and no send alive after unmount', async () => {
    const fetchMock = stubFetch(hang);
    const { result, unmount } = mount();
    result.current.playing();
    const { signal } = fetchMock.mock.calls[0][1];
    unmount();
    expect(signal.aborted).toBe(true);
    expect(vi.getTimerCount()).toBe(0);
    const calls = fetchMock.mock.calls.length;
    await advance(REPORT_INTERVAL_MS * 10);
    window.dispatchEvent(new Event('pagehide'));
    hidden('hidden');
    document.dispatchEvent(new Event('visibilitychange'));
    expect(fetchMock).toHaveBeenCalledTimes(calls);
    // The first frame after unmount sends nothing either.
    result.current.playing();
    expect(fetchMock).toHaveBeenCalledTimes(calls);
  });

  it('a send that ends after unmount changes nothing', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    stubFetch(
      () =>
        new Promise((resolve) =>
          setTimeout(() => resolve(new Response(null, { status: 400 })), 1000),
        ),
    );
    const { result, unmount } = mount();
    result.current.engine('hlsjs');
    unmount(); // the exit send is on its way
    await advance(2000);
    expect(warn).not.toHaveBeenCalled();
  });
});

describe('the object it returns', () => {
  it('is the same object on every render, and is never able to throw', async () => {
    stubFetch();
    let boom = false;
    const now = () => {
      if (boom) throw new Error('clock failed');
      return Date.now();
    };
    const { result, rerender } = mount({}, {});
    const first = result.current;
    rerender({
      editionId: 11,
      quality: '720p',
      openedAt: 0,
      now: () => Date.now(),
      makeId: () => ID,
    });
    expect(result.current).toBe(first);
    const view = renderHook(() =>
      useHealthReport({ editionId: 11, quality: 'auto', openedAt: 0, now, makeId: () => ID }),
    );
    boom = true;
    const api = view.result.current;
    expect(() => {
      api.play();
      api.playRefused();
      api.playing();
      api.canPlay(true);
      api.paused();
      api.waiting();
      api.seek();
      api.seeking();
      api.seeking();
      api.seeked();
      api.ended();
      api.sourceChanged();
      api.beat(1);
      api.rung('720p', 720);
      api.quality('720p');
      api.engine('hlsjs');
      api.environment({});
      api.reconnecting(true);
      api.recovered();
      api.fellBack();
      api.networkError({ timeout: true });
      api.throughput(100);
    }).not.toThrow();
    await advance(START_GRACE_MS + REPORT_INTERVAL_MS);
    expect(() => view.unmount()).not.toThrow();
    window.dispatchEvent(new Event('pagehide'));
  });

  it('exposes only the counter’s inputs', () => {
    stubFetch();
    const { result } = mount();
    expect(Object.keys(result.current).sort()).toEqual([
      'beat',
      'canPlay',
      'ended',
      'engine',
      'environment',
      'fellBack',
      'networkError',
      'paused',
      'play',
      'playRefused',
      'playing',
      'quality',
      'reconnecting',
      'recovered',
      'rung',
      'seek',
      'seeked',
      'seeking',
      'sourceChanged',
      'throughput',
      'waiting',
    ]);
  });

  it('sends the quality it was given, and a changed one, without restarting its timers', async () => {
    const fetchMock = stubFetch();
    const { result, rerender } = mount({ quality: '720p' });
    result.current.playing();
    result.current.paused();
    expect(bodies(fetchMock)[0].quality).toBe('720p');
    const timers = vi.getTimerCount();
    const calls = fetchMock.mock.calls.length;
    rerender({
      editionId: 11,
      quality: 'auto',
      openedAt: 0,
      now: () => Date.now(),
      makeId: () => ID,
    });
    expect(vi.getTimerCount()).toBe(timers);
    expect(fetchMock).toHaveBeenCalledTimes(calls);
    await advance(REPORT_INTERVAL_MS);
    expect(bodies(fetchMock).at(-1).quality).toBe('auto');
  });

  it('uses the browser’s own clock and ids when none are given', async () => {
    const fetchMock = stubFetch();
    const { result } = renderHook(() =>
      useHealthReport({ editionId: 11, quality: 'auto', openedAt: 0 }),
    );
    result.current.playing();
    expect(fetchMock.mock.calls[0][0]).toMatch(
      /^\/api\/playback-health\/[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
    );
    const { client } = bodies(fetchMock)[0];
    expect(client.screen).toMatch(/^\d{1,5}x\d{1,5}$/);
    expect(client.userAgent).toContain('jsdom');
    expect(client.cores).toBe(navigator.hardwareConcurrency);
  });

  it('restarts when the edition changes: the old viewing is sent as it is left', async () => {
    const fetchMock = stubFetch();
    const { result, rerender } = mount();
    result.current.engine('hlsjs');
    rerender({
      editionId: 12,
      quality: 'auto',
      openedAt: 0,
      now: () => Date.now(),
      makeId: () => ID,
    });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0][1].keepalive).toBe(true);
  });

  it('describes a browser with no navigator and no screen', async () => {
    const fetchMock = stubFetch();
    vi.stubGlobal('navigator', undefined);
    vi.stubGlobal('screen', undefined);
    const { result } = mount();
    result.current.playing();
    expect(bodies(fetchMock)[0].client).toEqual({ userAgent: '', screen: '0x0', cores: null });
  });
});

describe('under StrictMode', () => {
  it('still sends at the first frame and at 20 s, from one record', async () => {
    const fetchMock = stubFetch();
    const { result } = mount({}, { wrapper: StrictMode });
    await advance(START_GRACE_MS);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    result.current.playing();
    expect(fetchMock).toHaveBeenCalledTimes(2);
    const urls = new Set(fetchMock.mock.calls.map(([url]) => url));
    expect(urls.size).toBe(1);
    await advance(REPORT_INTERVAL_MS);
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it('keeps one timer set after the second mount', () => {
    stubFetch();
    const plain = mount();
    const timers = vi.getTimerCount();
    plain.unmount();
    mount({}, { wrapper: StrictMode });
    expect(vi.getTimerCount()).toBe(timers);
  });
});

describe('newPlaybackId', () => {
  const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

  it('is crypto.randomUUID where there is one', () => {
    vi.stubGlobal('crypto', { randomUUID: () => ID });
    expect(newPlaybackId()).toBe(ID);
  });

  it('builds a lower-case version 4 id from getRandomValues where randomUUID is missing', () => {
    vi.stubGlobal('crypto', {
      getRandomValues: (bytes) => {
        bytes.fill(255);
        return bytes;
      },
    });
    const id = newPlaybackId();
    expect(id).toBe('ffffffff-ffff-4fff-bfff-ffffffffffff');
    expect(id).toMatch(UUID);
    expect(id[14]).toBe('4'); // the version nibble
    expect('89ab').toContain(id[19]); // the variant nibble
  });

  it('forces the version and variant bits on whatever the bytes are', () => {
    vi.stubGlobal('crypto', {
      getRandomValues: (bytes) => bytes, // all zeros
    });
    expect(newPlaybackId()).toBe('00000000-0000-4000-8000-000000000000');
  });

  it('also falls back when randomUUID throws', () => {
    vi.stubGlobal('crypto', {
      randomUUID: () => {
        throw new Error('insecure context');
      },
      getRandomValues: (bytes) => bytes,
    });
    expect(newPlaybackId()).toMatch(UUID);
  });

  it('is null when neither exists, or both throw', () => {
    vi.stubGlobal('crypto', undefined);
    expect(newPlaybackId()).toBeNull();
    vi.stubGlobal('crypto', {
      randomUUID: () => {
        throw new Error('no');
      },
      getRandomValues: () => {
        throw new Error('no');
      },
    });
    expect(newPlaybackId()).toBeNull();
  });
});

describe('when the recorder cannot be made', () => {
  const INPUT_CALLS = (api) => {
    api.play();
    api.playRefused();
    api.playing();
    api.canPlay(true);
    api.paused();
    api.waiting();
    api.seek();
    api.seeked();
    api.ended();
    api.sourceChanged();
    api.beat(1);
    api.rung('720p', 720);
    api.quality('720p');
    api.engine('hlsjs');
    api.environment({});
    api.reconnecting(true);
    api.recovered();
    api.fellBack();
    api.networkError({ timeout: true });
    api.throughput(100);
  };

  it.each([
    [
      'makeId throws',
      () => {
        throw new Error('no id');
      },
    ],
    ['makeId returns nothing', () => undefined],
    ['makeId returns null', () => null],
    ['makeId returns an empty string', () => ''],
  ])('is inert when %s: no timer, no listener, no send, inputs callable', async (_name, makeId) => {
    const fetchMock = stubFetch();
    const add = vi.spyOn(window, 'addEventListener');
    const addDoc = vi.spyOn(document, 'addEventListener');
    const { result, rerender, unmount } = mount({ makeId });
    const first = result.current;
    expect(vi.getTimerCount()).toBe(0);
    expect(add).not.toHaveBeenCalledWith('pagehide', expect.anything());
    expect(addDoc).not.toHaveBeenCalledWith('visibilitychange', expect.anything());
    expect(() => INPUT_CALLS(result.current)).not.toThrow();
    rerender({ editionId: 11, quality: '720p', openedAt: 0, now: () => Date.now(), makeId });
    expect(result.current).toBe(first);
    await advance(START_GRACE_MS + REPORT_INTERVAL_MS * 2);
    window.dispatchEvent(new Event('pagehide'));
    hidden('hidden');
    document.dispatchEvent(new Event('visibilitychange'));
    unmount();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('is inert when the clock or the client cannot be read while it is made', () => {
    vi.stubGlobal('navigator', {
      get userAgent() {
        throw new Error('no navigator');
      },
    });
    const fetchMock = stubFetch();
    const { result, unmount } = mount();
    expect(() => INPUT_CALLS(result.current)).not.toThrow();
    expect(vi.getTimerCount()).toBe(0);
    unmount();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('has the same inputs as the working recorder', () => {
    stubFetch();
    const working = mount();
    const inert = mount({ makeId: () => undefined });
    expect(Object.keys(inert.result.current).sort()).toEqual(
      Object.keys(working.result.current).sort(),
    );
  });
});
