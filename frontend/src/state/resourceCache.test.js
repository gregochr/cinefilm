import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  SESSION_EXPIRED_EVENT,
  announceLibraryChanged,
  announceSignedOut,
  del,
  post,
  put,
  request,
} from '../api/client.js';
import {
  MAX_AGE_MS,
  MAX_ENTRIES,
  beginRequest,
  clearResourceCache,
  commit,
  lookup,
  setResourceCacheClock,
  SETTLE_WAIT_MS,
  whenSettled,
} from './resourceCache.js';

let time;
beforeEach(() => {
  clearResourceCache();
  time = 1_000_000;
  setResourceCacheClock(() => time);
  vi.stubGlobal('fetch', async () => new Response(null, { status: 204 }));
});
afterEach(() => vi.unstubAllGlobals());

const store = (key, data = { key }) => commit(key, beginRequest(), data);

describe('resourceCache entries', () => {
  it('returns what was stored, as the same object', () => {
    const data = { a: 1 };
    store('k', data);
    expect(lookup('k').data).toBe(data);
    expect(lookup('missing')).toBeNull();
  });

  it('keeps at most 12 entries and evicts the least recently used', () => {
    for (let i = 0; i < MAX_ENTRIES; i += 1) store(`k${i}`);
    expect(lookup('k0')).not.toBeNull(); // k0 is now the most recently used
    store('k12'); // the 13th entry evicts k1, the least recently used
    expect(lookup('k1')).toBeNull();
    expect(lookup('k0')).not.toBeNull();
    expect(lookup('k12')).not.toBeNull();
    expect(lookup('k2')).not.toBeNull();
  });

  it('holds exactly 12 entries without evicting', () => {
    for (let i = 0; i < MAX_ENTRIES; i += 1) store(`k${i}`);
    for (let i = 0; i < MAX_ENTRIES; i += 1) expect(lookup(`k${i}`)).not.toBeNull();
  });

  it('shows an entry 29:59.999 old and drops one of 30:00.000', () => {
    store('k');
    time += MAX_AGE_MS - 1;
    expect(lookup('k')).not.toBeNull();
    time += 1;
    expect(lookup('k')).toBeNull();
    time -= 1;
    expect(lookup('k')).toBeNull(); // dropped when read, not merely hidden
  });

  it('never shows an entry from the future (the clock went back)', () => {
    store('k');
    time -= 1;
    expect(lookup('k')).toBeNull();
  });

  it('with a shorter maxAgeMs, hides an older entry without dropping it', () => {
    store('k');
    time += 60_000;
    expect(lookup('k', 60_000)).toBeNull();
    expect(lookup('k')).not.toBeNull();
    time -= 1;
    expect(lookup('k', 60_000)).not.toBeNull();
  });

  it('a JSON-equal confirmation refreshes the time and keeps the stored object', () => {
    const first = { a: [1] };
    store('k', first);
    time += MAX_AGE_MS - 1;
    const result = commit('k', beginRequest(), { a: [1] });
    expect(result).toMatchObject({ stored: true, json: '{"a":[1]}' });
    expect(result.data).toBe(first);
    time += MAX_AGE_MS - 1;
    expect(lookup('k').data).toBe(first);
  });

  it('a different answer replaces the stored one', () => {
    store('k', { a: 1 });
    const next = { a: 2 };
    expect(commit('k', beginRequest(), next).data).toBe(next);
    expect(lookup('k').data).toBe(next);
  });
});

describe('resourceCache storing rule', () => {
  it('refuses an answer when an invalidating change started since the request began', async () => {
    const token = beginRequest();
    await put('/api/editions/1/continue-watching', {}).catch(() => {});
    const result = commit('k', token, { a: 1 });
    expect(result.stored).toBe(false);
    expect(lookup('k')).toBeNull();
  });

  it('refuses an answer whose request began while a change was in flight, even if it lands before that ends', async () => {
    let release;
    vi.stubGlobal('fetch', () => new Promise((resolve) => (release = resolve)));
    const mutation = put('/api/editions/1/progress', { positionMs: 1 });
    const token = beginRequest();
    expect(commit('k', token, { a: 1 }).stored).toBe(false);
    release(new Response(null, { status: 204 }));
    await mutation;
    // After the end the cache accepts requests that begin from then on, and refuses none for this reason.
    expect(commit('k', beginRequest(), { a: 1 }).stored).toBe(true);
  });

  it('accepts an answer when nothing was invalidated in between', () => {
    const token = beginRequest();
    expect(commit('k', token, { a: 1 }).stored).toBe(true);
  });

  it('a refused answer still reports its JSON', () => {
    const token = beginRequest();
    announceLibraryChanged();
    expect(commit('k', token, { a: 1 })).toEqual({
      stored: false,
      json: '{"a":1}',
      data: { a: 1 },
    });
  });

  it('clearResourceCache forgets mutations in flight', async () => {
    vi.stubGlobal('fetch', () => new Promise(() => {}));
    void put('/api/editions/1/progress', {});
    expect(beginRequest().clean).toBe(false);
    clearResourceCache();
    expect(beginRequest().clean).toBe(true);
  });
});

// Runs `call` against a fetch that answers `status`, reporting the cache at the start and after the end.
async function around(call, { status = 204 } = {}) {
  let atStart;
  vi.stubGlobal('fetch', async () => {
    atStart = { token: beginRequest(), entries: ['a', 'film:1', 'film:2'].map((k) => lookup(k)) };
    return new Response(null, { status });
  });
  seed();
  const generation = beginRequest().generation;
  await call().catch(() => {});
  return { atStart, generation, after: beginRequest() };
}

function seed() {
  ['a', 'film:1', 'film:2'].forEach((k) => store(k));
}
const survivors = () => ['a', 'film:1', 'film:2'].filter((k) => lookup(k) !== null);

describe('resourceCache invalidation table', () => {
  it('a preference save drops only its own film, at the start and again at the end', async () => {
    const { atStart, generation, after } = await around(() =>
      put('/api/films/1/preferences', { audioStreamId: 1 }),
    );
    expect(atStart.entries.map((e) => e !== null)).toEqual([true, false, true]);
    expect(atStart.token.clean).toBe(false); // counted while in flight
    expect(survivors()).toEqual(['a', 'film:2']);
    expect(after.generation).toBe(generation + 2); // start and end each moved it
    expect(after.clean).toBe(true);
  });

  it('a preference save for a film does not touch a path that merely resembles it', async () => {
    await around(() => put('/api/films/1/preferences/extra', {}));
    expect(survivors()).toEqual([]); // not the preferences row, so the unknown row: everything
  });

  it('a preference save with a query string still drops only its film', async () => {
    await around(() => request('PUT', '/api/films/2/preferences?x=1', { body: {} }));
    expect(survivors()).toEqual(['a', 'film:1']);
  });

  it.each([
    ['POST', '/api/editions/5/sessions', (p) => post(p, {})],
    ['DELETE', '/api/sessions/abc', (p) => del(p)],
    ['PUT', '/api/playback-health/abc', (p) => put(p, {})],
  ])('%s %s changes nothing: no drop, no bump, not counted', async (method, path, send) => {
    const { atStart, generation, after } = await around(() => send(path));
    expect(atStart.entries.every((e) => e !== null)).toBe(true);
    expect(atStart.token.clean).toBe(true);
    expect(survivors()).toEqual(['a', 'film:1', 'film:2']);
    expect(after.generation).toBe(generation);
  });

  it.each([
    ['POST', '/api/editions/5/watched'],
    ['PUT', '/api/editions/5/progress'],
    ['PUT', '/api/editions/5/continue-watching'],
    ['PUT', '/api/editions/5/playback-state'],
    ['POST', '/api/session'],
    ['DELETE', '/api/session'],
    ['POST', '/api/admin/scan'],
    ['PATCH', '/api/anything'],
    ['DELETE', '/api/admin/transcoding/sessions/9'],
    ['PUT', '/api/sessions/abc'], // not the DELETE row
    ['DELETE', '/api/playback-health/abc'], // not the PUT row
    ['POST', '/api/editions/5/sessions/extra'],
    ['PUT', '/api/editions/5/sessions'],
  ])('%s %s drops everything, bumps and counts, at both phases', async (method, path) => {
    const { atStart, generation, after } = await around(() => request(method, path, { body: {} }));
    expect(atStart.entries.every((e) => e === null)).toBe(true);
    expect(atStart.token.clean).toBe(false);
    expect(survivors()).toEqual([]);
    expect(after.generation).toBe(generation + 2);
    expect(after.clean).toBe(true);
  });

  it('the end phase clears what a request stored while it was in flight, for a failed mutation too', async () => {
    let finish;
    vi.stubGlobal('fetch', () => new Promise((_, reject) => (finish = reject)));
    const mutation = post('/api/editions/5/watched', {});
    store('late'); // refused: began while in flight
    expect(lookup('late')).toBeNull();
    finish(new Error('offline'));
    await expect(mutation).rejects.toThrow('offline');
    expect(beginRequest().clean).toBe(true);
  });
});

describe('resourceCache events', () => {
  it.each([
    ['the library changed', () => announceLibraryChanged()],
    ['the session expired', () => window.dispatchEvent(new CustomEvent(SESSION_EXPIRED_EVENT))],
    ['the user signed out', () => announceSignedOut()],
  ])('%s: clears everything and bumps the generation', (_, fire) => {
    seed();
    const token = beginRequest();
    fire();
    expect(survivors()).toEqual([]);
    expect(commit('a', token, { a: 1 }).stored).toBe(false);
  });
});

describe('whenSettled', () => {
  const state = async (promise) => {
    let done = false;
    void promise.then(() => (done = true));
    await Promise.resolve();
    await Promise.resolve();
    return done;
  };

  it('resolves at once when nothing is in flight', async () => {
    expect(await whenSettled()).toBe(true);
  });

  it('waits for the last end of two overlapping mutations', async () => {
    const ends = [];
    vi.stubGlobal('fetch', () => new Promise((resolve) => ends.push(resolve)));
    const first = put('/api/editions/1/progress', {});
    const second = put('/api/editions/2/progress', {});
    const waiting = whenSettled();
    ends[0](new Response(null, { status: 204 }));
    await first;
    expect(await state(waiting)).toBe(false);
    ends[1](new Response(null, { status: 204 }));
    await second;
    expect(await state(waiting)).toBe(true);
    expect(await waiting).toBe(true);
  });

  it('resolves on clearResourceCache()', async () => {
    vi.stubGlobal('fetch', () => new Promise(() => {}));
    void put('/api/editions/1/progress', {});
    const waiting = whenSettled();
    expect(await state(waiting)).toBe(false);
    clearResourceCache();
    expect(await state(waiting)).toBe(true);
  });

  it('gives up waiting for a mutation that hangs after SETTLE_WAIT_MS', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    try {
      vi.stubGlobal('fetch', () => new Promise(() => {}));
      void put('/api/editions/1/progress', {});
      const waiting = whenSettled();
      await vi.advanceTimersByTimeAsync(SETTLE_WAIT_MS - 1);
      expect(await state(waiting)).toBe(false);
      await vi.advanceTimersByTimeAsync(1);
      expect(await state(waiting)).toBe(true);
      expect(await waiting).toBe(false); // gave up, not settled
    } finally {
      vi.useRealTimers();
    }
  });

  it('is not released by a library change: the mutation is still in flight', async () => {
    vi.stubGlobal('fetch', () => new Promise(() => {}));
    void put('/api/editions/1/progress', {});
    const waiting = whenSettled();
    announceLibraryChanged();
    expect(await state(waiting)).toBe(false);
  });
});
