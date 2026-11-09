import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { handle } from './fixtureServer.js';

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

// The server answers after an 80 ms pretend latency, so every call is settled on the fake clock.
async function call(method, path, body) {
  const pending = handle(method, path, body);
  await vi.advanceTimersByTimeAsync(80);
  return pending;
}

const titles = (list) => list.films.map((f) => f.title);

describe('fixture server', () => {
  it('answers the session endpoints', async () => {
    expect(await call('GET', '/api/session')).toEqual({ authenticated: true });
    expect(await call('POST', '/api/session', { password: 'x' })).toEqual({ ok: true });
  });

  it('takes 80 ms to answer, like a (fast) network', async () => {
    const pending = handle('GET', '/api/home');
    let settled = false;
    pending.then(() => {
      settled = true;
    });
    await vi.advanceTimersByTimeAsync(79);
    expect(settled).toBe(false);
    await vi.advanceTimersByTimeAsync(1);
    expect(settled).toBe(true);
  });

  it('serves the home payload', async () => {
    const home = await call('GET', '/api/home');
    expect(home.hero.title).toBe('Heat');
  });

  describe('the film list', () => {
    it('lists every film with total and facets by default', async () => {
      const all = await call('GET', '/api/films');
      expect(all.total).toBe(all.films.length);
      expect(all.facets).toBeDefined();
    });

    it('searches titles case-insensitively', async () => {
      expect(titles(await call('GET', '/api/films?q=HEAT'))).toEqual(['Heat']);
    });

    it('filters watched and unwatched', async () => {
      const watched = await call('GET', '/api/films?show=watched');
      const unwatched = await call('GET', '/api/films?show=unwatched');
      expect(watched.films.every((f) => f.watched)).toBe(true);
      expect(unwatched.films.every((f) => !f.watched)).toBe(true);
      const all = await call('GET', '/api/films');
      expect(watched.total + unwatched.total).toBe(all.total);
    });

    it('filters a decade as [decade, decade + 10)', async () => {
      const seventies = await call('GET', '/api/films?decade=1970');
      expect(seventies.total).toBeGreaterThan(0);
      expect(seventies.films.every((f) => f.year >= 1970 && f.year < 1980)).toBe(true);
    });

    it('sorts by title and by year', async () => {
      const byTitle = titles(await call('GET', '/api/films?sort=title'));
      expect(byTitle).toEqual([...byTitle].sort((a, b) => a.localeCompare(b)));
      const years = (await call('GET', '/api/films?sort=year')).films.map((f) => f.year);
      expect(years).toEqual([...years].sort((a, b) => a - b));
    });
  });

  it('serves film details, falling back to Heat for an unknown id', async () => {
    expect((await call('GET', '/api/films/6')).title).toBe('Blade Runner');
    expect((await call('GET', '/api/films/8')).title).toBe('The Wicker Man');
    expect((await call('GET', '/api/films/999')).title).toBe('Heat');
  });

  it('serves people and collections', async () => {
    expect((await call('GET', '/api/people/11')).name).toBe('Robert De Niro');
    expect((await call('GET', '/api/collections/1')).name).toBe('Alien');
  });

  it('echoes progress, watched and preference writes', async () => {
    expect(await call('PUT', '/api/editions/11/progress', { positionMs: 5000 })).toEqual({
      positionMs: 5000,
      watched: false,
    });
    expect(await call('PUT', '/api/editions/11/progress')).toEqual({
      positionMs: 0,
      watched: false,
    });
    const previous = {
      positionMs: 0,
      watched: false,
      watchedAt: null,
      hiddenFromContinueWatching: false,
      hiddenAt: null,
    };
    expect(await call('POST', '/api/editions/11/watched', { watched: true })).toEqual({
      watched: true,
      previous,
    });
    expect(await call('POST', '/api/editions/11/watched')).toEqual({ watched: false, previous });
    expect(await call('PUT', '/api/editions/11/continue-watching', { hidden: true })).toEqual({
      hidden: true,
    });
    expect(await call('PUT', '/api/editions/11/continue-watching')).toEqual({ hidden: false });
    expect(await call('PUT', '/api/editions/11/playback-state', previous)).toEqual(previous);
    expect(await call('PUT', '/api/films/1/preferences', { quality: 'auto' })).toEqual({
      quality: 'auto',
    });
  });

  it('answers null for anything else', async () => {
    expect(await call('GET', '/api/nothing')).toBeNull();
  });

  it('serves the Admin scan status and attention list', async () => {
    expect((await call('GET', '/api/admin/scan')).folder).toBe('/media');
    expect((await call('GET', '/api/admin/attention')).items.length).toBeGreaterThan(0);
  });

  it('serves the Admin transcoding state and stops a session', async () => {
    const state = await call('GET', '/api/admin/transcoding');
    expect(state.encoder).toBe('vaapi');
    expect(state.sessions[0].filmTitle).toBe('Heat');
    expect(
      await call('DELETE', `/api/admin/transcoding/sessions/${state.sessions[0].id}`),
    ).toBeNull();
  });

  it('creates and removes a playback session', async () => {
    const session = await call('POST', '/api/editions/11/sessions', { quality: 'auto' });
    expect(session.masterUrl).toBe(`/api/sessions/${session.sessionId}/master.m3u8`);
    expect(session.renditions.map((r) => r.id)).toEqual(['original', '720p', '480p', '360p']);
    expect(await call('DELETE', `/api/sessions/${session.sessionId}`)).toBeNull();
  });
});
