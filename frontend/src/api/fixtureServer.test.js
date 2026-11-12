import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from './client.js';
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
    expect(home.hero.inProgress).toBe(true);
    expect(home.continueWatchingTotal).toBe(1 + home.continueWatching.length);
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

    it('filters in progress, watched and unwatched: exclusive, and together every film', async () => {
      const inProgress = await call('GET', '/api/films?show=in-progress');
      const watched = await call('GET', '/api/films?show=watched');
      const unwatched = await call('GET', '/api/films?show=unwatched');
      expect(inProgress.total).toBeGreaterThan(0);
      expect(inProgress.films.every((f) => f.inProgress)).toBe(true);
      expect(watched.films.every((f) => f.watched && !f.inProgress)).toBe(true);
      expect(unwatched.films.every((f) => !f.watched && !f.inProgress)).toBe(true);
      const all = await call('GET', '/api/films');
      expect(inProgress.total + watched.total + unwatched.total).toBe(all.total);
    });

    it('gives every card the in-progress fields', async () => {
      const { films } = await call('GET', '/api/films');
      films.forEach((f) => {
        expect(typeof f.inProgress).toBe('boolean');
        expect(typeof f.hiddenFromContinueWatching).toBe('boolean');
        expect(f.lastWatchedAt === null || typeof f.lastWatchedAt === 'string').toBe(true);
      });
    });

    it('sorts by last watched: newest first, never played last, ties by title', async () => {
      const { films } = await call('GET', '/api/films?sort=last-watched');
      const stamps = films.map((f) => f.lastWatchedAt);
      const played = stamps.filter(Boolean);
      expect(stamps.slice(0, played.length)).toEqual([...played].sort().reverse());
      expect(stamps.slice(played.length).every((t) => t === null)).toBe(true);
      const never = films.slice(played.length).map((f) => f.title);
      expect(never).toEqual([...never].sort((a, b) => a.localeCompare(b)));
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
    expect((await call('GET', '/api/collections/1')).name).toBe('Alien Collection');
    expect((await call('GET', '/api/collections')).filmCount).toBe(27);
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

  it('serves the Admin playback health list', async () => {
    const { playbacks } = await call('GET', '/api/admin/playback-health');
    expect(playbacks.map((p) => p.filmTitle)).toEqual(['Heat', 'Blade Runner', 'The Wicker Man']);
    expect(playbacks[0].report.environment).toBe('train');
  });

  it('creates and removes a playback session', async () => {
    const session = await call('POST', '/api/editions/11/sessions', { quality: 'auto' });
    expect(session.masterUrl).toBe(`/api/sessions/${session.sessionId}/master.m3u8`);
    expect(session.renditions.map((r) => r.id)).toEqual(['original', '720p', '480p', '360p']);
    expect(await call('DELETE', `/api/sessions/${session.sessionId}`)).toBeNull();
  });

  it('serves the compact search index, with the sample entries', async () => {
    const index = await call('GET', '/api/search-index');
    expect(Object.keys(index)).toEqual(['films', 'collections', 'people']);
    expect(index.films.map((f) => f.t)).toEqual(expect.arrayContaining(['Heat', 'Warrior']));
    expect(index.people.map((p) => p.n)).toContain('Tom Hanks');
  });
});

describe('fixture server: Edit editions', () => {
  it('serves a film’s files with the suggestion, and accepts a save', async () => {
    const view = await call('GET', '/api/admin/films/40/editions');
    expect(view.files).toHaveLength(3);
    expect(view.suggestion.reason).toBe('From the edition tags and part numbers');
    expect(await call('PUT', '/api/admin/films/40/editions', {})).toEqual({
      filmId: 40,
      newFilmIds: [],
    });
  });
});

describe('fixture server: Admin settings', () => {
  afterEach(async () => {
    await call('PUT', '/api/admin/settings', { continueWatchingMinMinutes: 5 });
  });

  it('starts at five minutes with the fixture films in progress counted', async () => {
    expect(await call('GET', '/api/admin/settings')).toEqual({
      continueWatchingMinMinutes: 5,
      inProgressCount: 5,
    });
  });

  it('recomputes the count from the new minimum', async () => {
    expect(await call('PUT', '/api/admin/settings', { continueWatchingMinMinutes: 30 })).toEqual({
      continueWatchingMinMinutes: 30,
      inProgressCount: 4,
    });
    expect((await call('GET', '/api/admin/settings')).continueWatchingMinMinutes).toBe(30);
    expect(await call('PUT', '/api/admin/settings', { continueWatchingMinMinutes: 0 })).toEqual({
      continueWatchingMinMinutes: 0,
      inProgressCount: 5,
    });
  });

  it('moves the Library filter, Home and the other cards with the minimum', async () => {
    const inProgressTitles = async () =>
      titles(await call('GET', '/api/films?show=in-progress&sort=last-watched'));
    expect(await inProgressTitles()).toEqual([
      'Heat',
      'Arrival',
      'Chinatown',
      'Paris, Texas',
      'Moon',
    ]);
    const before = await call('GET', '/api/home');
    expect(before.continueWatchingTotal).toBe(5);

    await call('PUT', '/api/admin/settings', { continueWatchingMinMinutes: 30 });

    expect(await inProgressTitles()).toEqual(['Heat', 'Arrival', 'Paris, Texas', 'Moon']);
    const home = await call('GET', '/api/home');
    expect(home.hero.title).toBe('Heat');
    expect(home.continueWatching.map((f) => f.title)).toEqual(['Arrival', 'Paris, Texas', 'Moon']);
    expect(home.continueWatchingTotal).toBe(4);
    const unwatched = await call('GET', '/api/films?show=unwatched');
    expect(titles(unwatched)).toContain('Chinatown');
    const chinatown = (await call('GET', '/api/films?q=chinatown')).films[0];
    expect(chinatown.inProgress).toBe(false);
    expect(chinatown.progress).not.toBeNull();
    const person = await call('GET', '/api/people/11');
    person.films.forEach((entry) => {
      expect(entry.film.inProgress).toBe(
        !!entry.film.progress && entry.film.progress.positionMs >= 30 * 60000,
      );
    });
    (await call('GET', '/api/collections/1')).parts
      .filter((part) => part.film)
      .forEach((part) => expect(part.film.inProgress).toBe(false));

    await call('PUT', '/api/admin/settings', { continueWatchingMinMinutes: 0 });

    expect(await inProgressTitles()).toHaveLength(5);
    expect((await call('GET', '/api/home')).continueWatchingTotal).toBe(5);
  });

  // The rejection is caught before the fake clock advances, so it is never unhandled.
  async function failure(body) {
    const pending = handle('PUT', '/api/admin/settings', body).catch((error) => error);
    await vi.advanceTimersByTimeAsync(80);
    return pending;
  }

  it.each([-1, 31, 2.5, null, undefined, '5'])('refuses %s with a 400', async (minutes) => {
    const error = await failure({ continueWatchingMinMinutes: minutes });
    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(400);
  });

  it('refuses a missing body with a 400', async () => {
    expect((await failure(undefined)).status).toBe(400);
  });

  it('keeps the old minimum after a refused change', async () => {
    await failure({ continueWatchingMinMinutes: 99 });
    expect((await call('GET', '/api/admin/settings')).continueWatchingMinMinutes).toBe(5);
  });

  it('serves the forced-subtitle list, and a PUT applies the owner’s choice and Automatic restores the rule', async () => {
    const before = await call('GET', '/api/admin/forced-subtitles/films');
    expect(before.films).toHaveLength(7);
    expect(before.counts).toEqual({
      needed: 3,
      neededMissing: 2,
      unsure: 1,
      unknown: 1,
      notNeeded: 2,
    });

    const chosen = await call('PUT', '/api/admin/films/2/forced-need', { choice: 'NOT_NEEDED' });
    expect(chosen).toMatchObject({
      filmId: 2,
      need: 'NOT_NEEDED',
      reason: 'OWNER',
      ownerChoice: 'NOT_NEEDED',
    });
    const after = await call('GET', '/api/admin/forced-subtitles/films');
    expect(after.films.find((f) => f.filmId === 2).need).toBe('NOT_NEEDED');
    expect(after.counts).toMatchObject({ needed: 2, neededMissing: 1, notNeeded: 3 });

    const automatic = await call('PUT', '/api/admin/films/2/forced-need', { choice: null });
    expect(automatic).toEqual(before.films.find((f) => f.filmId === 2));
    expect(await call('PUT', '/api/admin/films/2/forced-need', {})).toEqual(automatic);
  });

  it('clears a choice the fixture is loaded with: Automatic gives the baseline, and choosing again works', async () => {
    const loaded = (await call('GET', '/api/admin/forced-subtitles/films')).films.find(
      (f) => f.filmId === 7,
    );
    expect(loaded).toMatchObject({ ownerChoice: 'NOT_NEEDED', reason: 'OWNER' });

    const cleared = await call('PUT', '/api/admin/films/7/forced-need', { choice: null });
    expect(cleared).toMatchObject({
      ownerChoice: null,
      need: 'NOT_NEEDED',
      reason: 'ENGLISH_ONLY',
    });
    const list = await call('GET', '/api/admin/forced-subtitles/films');
    expect(list.films.find((f) => f.filmId === 7)).toEqual(cleared);

    const again = await call('PUT', '/api/admin/films/7/forced-need', { choice: 'NEEDED' });
    expect(again).toMatchObject({ ownerChoice: 'NEEDED', need: 'NEEDED', reason: 'OWNER' });
  });

  it('refuses a choice for an unknown film (404) or a bad value (400)', async () => {
    const refused = async (path, body) => {
      const pending = handle('PUT', path, body).catch((error) => error);
      await vi.advanceTimersByTimeAsync(80);
      return pending;
    };
    expect(await refused('/api/admin/films/99/forced-need', { choice: 'NEEDED' })).toMatchObject({
      status: 404,
    });
    expect(await refused('/api/admin/films/2/forced-need', { choice: 'MAYBE' })).toMatchObject({
      status: 400,
    });
  });
});
