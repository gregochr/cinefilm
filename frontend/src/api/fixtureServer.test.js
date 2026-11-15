import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from './client.js';
import { handle } from './fixtureServer.js';

beforeEach(() => vi.useFakeTimers());
afterEach(() => {
  vi.useRealTimers();
  window.history.replaceState(null, '', '/');
});

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
    const attention = await call('GET', '/api/admin/attention');
    expect(attention.items.length).toBeGreaterThan(0);
    const confident = attention.items.filter((i) => i.confident);
    expect(confident.map((i) => i.title)).toEqual(['Suspiria']);
    expect(confident[0].candidates[0].score).toBe(100);
    expect(attention.items.filter((i) => i.kind === 'needs-match' && !i.confident)).toHaveLength(1);
  });

  it('serves the Admin summary', async () => {
    const summary = await call('GET', '/api/admin/summary');
    expect(summary.todo).toEqual({
      pickFilm: 2,
      severalFiles: 2,
      fullMissing: 1,
      forcedQuestion: 1,
    });
    expect(summary.subtitlesUnresolved).toBe(2);
    expect(summary.playingNow.map((p) => p.filmTitle)).toEqual(['Heat']);
    expect(summary.playingNow[0].touch).toBe(false);
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
    expect(playbacks.map((p) => p.filmTitle)).toEqual([
      'Heat',
      'Heat',
      'Blade Runner',
      'The Wicker Man',
      'The Wicker Man',
    ]);
    expect(playbacks[0].report.environment).toBe('home');
    expect(playbacks[1].report.environment).toBe('train');
  });

  it('serves the filtered, paged playback health list with a total', async () => {
    const all = await call('GET', '/api/admin/playback-health?page=0');
    expect(all.total).toBe(5);
    expect(all.playbacks.map((p) => p.filmTitle)).toEqual([
      'Heat',
      'Heat',
      'Blade Runner',
      'The Wicker Man',
      'The Wicker Man',
    ]);
    const longOnes = await call('GET', '/api/admin/playback-health?minWatchedMs=180000');
    expect(longOnes.total).toBe(3);
    const withProblems = await call('GET', '/api/admin/playback-health?problems=true');
    expect(withProblems.total).toBe(1);
    expect(withProblems.playbacks[0].filmTitle).toBe('Heat');
    const without = await call('GET', '/api/admin/playback-health?problems=false');
    // The last session stepped down twice and nothing else went wrong: a step down is not a problem.
    expect(without.playbacks.map((p) => p.playbackId)).toEqual([
      '7d1e4a92-3b5c-4f08-a6d1-9c2e0b8f4a17',
      'a91e5b07-4d3c-42f8-8e6a-7c1b9d0f5e22',
      'c07d8e19-5b2a-4f61-a3d4-1e8f6b9c2a50',
      '5b2e9a40-7c1d-4e83-8f26-0d4a1c7e9b35',
    ]);
    const paged = await call('GET', '/api/admin/playback-health?size=1&page=1');
    expect(paged.playbacks).toHaveLength(1);
    expect(paged.playbacks[0].playbackId).toBe('3f6c1d2e-8a4b-4c7e-9b15-2d0e6f7a1c34');
    expect(paged.total).toBe(5);
    const since = await call('GET', '/api/admin/playback-health?since=2999-01-01T00:00:00Z');
    expect(since).toEqual({ playbacks: [], total: 0 });
  });

  it('serves the playback health chart without the session under three minutes', async () => {
    const { sessions } = await call('GET', '/api/admin/playback-health/chart');
    expect(sessions).toHaveLength(3);
    expect(sessions.every((s) => s.watchedMs >= 180_000)).toBe(true);
    expect(sessions.map((s) => s.environment)).toEqual(['home', 'away', 'train']);
    expect(sessions[2]).toMatchObject({ stallCount: 2, rungMs: { '480p': 1_468_800 } });
    const recent = await call('GET', '/api/admin/playback-health/chart?since=2026-11-01T00:00:00Z');
    expect(recent.sessions).toHaveLength(2);
  });

  it('carries the touch hint on the chart rows', async () => {
    const { sessions } = await call('GET', '/api/admin/playback-health/chart');
    expect(sessions.map((s) => s.touch)).toEqual([false, true, false]);
  });

  it('serves one full record with per-minute rows of seven figures, or five in an older one', async () => {
    const seven = await call(
      'GET',
      '/api/admin/playback-health/c1000000-0000-4000-8000-000000000004',
    );
    expect(seven).toMatchObject({
      filmTitle: 'Heat',
      report: { environment: 'train', client: { touch: false } },
    });
    expect(seven.report.minutes).toHaveLength(72);
    expect(seven.report.minutes.every((row) => row.length === 7)).toBe(true);
    expect(seven.report.minutes.some((row) => row[4] > 0)).toBe(true);
    const five = await call(
      'GET',
      '/api/admin/playback-health/c1000000-0000-4000-8000-000000000003',
    );
    expect(five.report.client.touch).toBe(true);
    expect(five.report.minutes.every((row) => row.length === 5)).toBe(true);
  });

  it('serves a listed record with no minutes and answers 404 for an unknown one', async () => {
    const listed = await call(
      'GET',
      '/api/admin/playback-health/7d1e4a92-3b5c-4f08-a6d1-9c2e0b8f4a17',
    );
    expect(listed.filmTitle).toBe('Heat');
    expect(listed.report.minutes).toEqual([]);
    const pending = handle(
      'GET',
      '/api/admin/playback-health/00000000-0000-4000-8000-000000000000',
    );
    const outcome = expect(pending).rejects.toMatchObject({ status: 404 });
    await vi.advanceTimersByTimeAsync(80);
    await outcome;
  });

  it('creates and removes a playback session', async () => {
    const session = await call('POST', '/api/editions/11/sessions', { quality: 'auto' });
    expect(session.masterUrl).toBe(`/api/sessions/${session.sessionId}/master.m3u8`);
    expect(session.renditions.map((r) => r.id)).toEqual([
      'original',
      '720p',
      '480p',
      '360p',
      '240p',
    ]);
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

  it('serves the subtitle files with every check state, and the scan status with the search fields', async () => {
    const files = await call('GET', '/api/admin/subtitles');
    expect(files.map((f) => f.check?.verdict ?? null)).toEqual([
      'AGREES',
      null,
      'CHECK',
      'CHECK',
      null,
      'AGREES',
      'CHECK',
      null,
      null,
    ]);
    const scan = await call('GET', '/api/admin/scan');
    expect(scan).toMatchObject({ openSubtitlesEnabled: true, openSubtitlesRunning: false });
  });

  it('removes a download on DELETE, refuses the owner’s file, and 404s a row that is gone', async () => {
    const failure = async (id) => {
      const pending = handle('DELETE', `/api/admin/subtitles/${id}`).catch((e) => e);
      await vi.advanceTimersByTimeAsync(80);
      return pending;
    };
    const before = await call('GET', '/api/admin/subtitles');
    expect(before.map((f) => f.id)).toContain(8);

    expect(await call('DELETE', '/api/admin/subtitles/8')).toBeNull();
    const after = await call('GET', '/api/admin/subtitles');
    expect(after.map((f) => f.id)).not.toContain(8);

    expect((await failure(8)).status).toBe(404);
    expect((await failure(1)).status).toBe(400);
    expect((await failure(999)).status).toBe(404);
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
      unresolved: 1,
      beingChecked: 1,
    });

    const chosen = await call('PUT', '/api/admin/films/2/forced-need', { choice: 'NOT_NEEDED' });
    expect(chosen).toMatchObject({
      filmId: 2,
      need: 'NOT_NEEDED',
      reason: 'OWNER',
      ownerChoice: 'NOT_NEEDED',
      unresolved: false,
      beingChecked: false,
    });
    const after = await call('GET', '/api/admin/forced-subtitles/films');
    expect(after.films.find((f) => f.filmId === 2).need).toBe('NOT_NEEDED');
    expect(after.counts).toMatchObject({
      needed: 2,
      neededMissing: 1,
      notNeeded: 3,
      unresolved: 0,
      beingChecked: 1,
    });

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
      unresolved: false,
      beingChecked: false,
    });
    const list = await call('GET', '/api/admin/forced-subtitles/films');
    expect(list.films.find((f) => f.filmId === 7)).toEqual(cleared);

    const again = await call('PUT', '/api/admin/films/7/forced-need', { choice: 'NEEDED' });
    expect(again).toMatchObject({
      ownerChoice: 'NEEDED',
      need: 'NEEDED',
      reason: 'OWNER',
      unresolved: true,
      beingChecked: false,
    });
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

  it('serves the full-subtitles list, and a PUT applies the owner’s choice and Automatic restores the rule', async () => {
    const before = await call('GET', '/api/admin/subtitles/full-need');
    expect(titles(before)).toEqual(['Das Boot', 'Léon', 'Amélie', 'Rashomon', 'Come and See']);
    expect(before.counts).toEqual({
      needed: 3,
      unsure: 0,
      unknown: 1,
      notNeeded: 1,
      unresolved: 1,
      beingChecked: 1,
      subtitled: 1,
      inEnglish: 531,
    });

    const chosen = await call('PUT', '/api/admin/films/3/full-need', { choice: 'NOT_NEEDED' });
    expect(chosen).toMatchObject({
      need: 'NOT_NEEDED',
      reason: 'OWNER',
      ownerChoice: 'NOT_NEEDED',
      unresolved: false,
      beingChecked: false,
    });
    const after = await call('GET', '/api/admin/subtitles/full-need');
    expect(after.films.find((f) => f.filmId === 3)).toEqual(chosen);
    expect(after.counts).toMatchObject({
      needed: 2,
      notNeeded: 2,
      unresolved: 1,
      beingChecked: 1,
      // Das Boot was settled and is now not needed: one fewer subtitled film, one more in English.
      subtitled: 0,
      inEnglish: 532,
    });

    const automatic = await call('PUT', '/api/admin/films/3/full-need', { choice: null });
    expect(automatic).toEqual(before.films.find((f) => f.filmId === 3));
    // A film loaded with an owner's choice goes back to what the rule says for it.
    expect(await call('PUT', '/api/admin/films/6/full-need', {})).toMatchObject({
      need: 'NEEDED',
      reason: 'ORIGINAL_AUDIO_ASSUMED',
      ownerChoice: null,
    });
  });

  it('gives a full row the flags the server would for the owner’s choice, and Automatic restores the loaded ones', async () => {
    const loaded = (await call('GET', '/api/admin/subtitles/full-need')).films.find(
      (f) => f.filmId === 7,
    );
    expect(loaded).toMatchObject({ unresolved: false, beingChecked: true, originalLanguage: 'ru' });

    expect(
      await call('PUT', '/api/admin/films/7/full-need', { choice: 'NOT_NEEDED' }),
    ).toMatchObject({ unresolved: false, beingChecked: false });
    expect(await call('PUT', '/api/admin/films/4/full-need', { choice: 'NEEDED' })).toMatchObject({
      need: 'NEEDED',
      unresolved: false,
      beingChecked: true,
    });
    expect(await call('PUT', '/api/admin/films/6/full-need', { choice: 'NEEDED' })).toMatchObject({
      unresolved: true,
      beingChecked: false,
    });
    expect(await call('PUT', '/api/admin/films/7/full-need', { choice: 'NEEDED' })).toMatchObject({
      unresolved: false,
      beingChecked: true,
    });
    expect(await call('PUT', '/api/admin/films/7/full-need', { choice: null })).toMatchObject({
      unresolved: false,
      beingChecked: true,
    });
    await call('PUT', '/api/admin/films/4/full-need', { choice: null });
  });

  it('refuses a full-subtitles choice for an unknown film (404) or a bad value (400)', async () => {
    const refused = async (path, body) => {
      const pending = handle('PUT', path, body).catch((error) => error);
      await vi.advanceTimersByTimeAsync(80);
      return pending;
    };
    expect(await refused('/api/admin/films/99/full-need', { choice: 'NEEDED' })).toMatchObject({
      status: 404,
    });
    expect(await refused('/api/admin/films/3/full-need', { choice: 'MAYBE' })).toMatchObject({
      status: 400,
    });
  });

  describe('the OpenSubtitles picker', () => {
    const refused = async (method, path, body) => {
      const pending = handle(method, path, body).catch((error) => error);
      await vi.advanceTimersByTimeAsync(80);
      return pending;
    };
    const fileIds = (search) => search.results.map((r) => r.fileId);

    it('searches by kind and answers the shape the API does, for the edition asked about', async () => {
      const forced = await call('GET', '/api/admin/editions/101/opensubtitles?kind=forced');
      expect(forced).toMatchObject({
        editionId: 101,
        kind: 'forced',
        imdbId: 'tt0113277',
        downloadsLeft: 3,
      });
      expect(fileIds(forced)).toEqual([5012, 5011, 5013]);
      expect(forced.results[0]).toEqual({
        fileId: 5012,
        release: 'Taken.2008.Directors.Cut.BluRay.x264',
        fileName: 'Taken.2008.Directors.Cut.BluRay.x264.srt',
        fps: 23.976,
        downloadCount: 4210,
        fromTrusted: true,
        hearingImpaired: false,
        uploadDate: '2019-05-14T09:12:00Z',
      });
      expect(forced.results[1].fileName).toBeNull();
      expect(forced.results[2]).toMatchObject({
        fps: null,
        uploadDate: null,
        hearingImpaired: true,
      });
      const full = await call('GET', '/api/admin/editions/102/opensubtitles?kind=full');
      expect(full).toMatchObject({ editionId: 102, kind: 'full' });
      expect(fileIds(full)).toEqual([6012, 6011]);
      // The names are made for the edition's own film, whatever the fixture file calls them.
      expect(full.results.map((r) => r.release)).toEqual([
        'Rashomon.1950.Directors.Cut.BluRay.x264',
        'Rashomon.1950.BluRay.SDH',
      ]);
      expect(forced.results[1].fileName).toBeNull();
      const unknownFilm = await call('GET', '/api/admin/editions/103/opensubtitles?kind=full');
      expect(unknownFilm.results[0].release).toBe('Heat.1995.Directors.Cut.BluRay.x264');
    });

    it('downloads only a file the last search offered, once, and counts what is left', async () => {
      const download = (editionId, body) =>
        call('POST', `/api/admin/editions/${editionId}/opensubtitles/download`, body);
      await call('GET', '/api/admin/editions/103/opensubtitles?kind=forced');

      expect(await download(103, { fileId: 5012, kind: 'forced' })).toEqual({
        editionId: 103,
        kind: 'forced',
        fileId: 5012,
        release: 'Heat.1995.Directors.Cut.BluRay.x264',
        downloadsLeft: 2,
      });
      expect(await download(103, { fileId: 5011, kind: 'forced' })).toMatchObject({
        downloadsLeft: 1,
      });
      expect(await call('GET', '/api/admin/editions/103/opensubtitles?kind=forced')).toMatchObject({
        downloadsLeft: 1,
      });
      expect(
        await refused('POST', '/api/admin/editions/103/opensubtitles/download', {
          fileId: 5012,
          kind: 'forced',
        }),
      ).toMatchObject({ status: 409, message: "That file is already this edition's download." });
    });

    it('refuses a file the search did not offer for that edition and kind (400)', async () => {
      await call('GET', '/api/admin/editions/101/opensubtitles?kind=forced');
      const notOffered = {
        status: 400,
        message: 'That file was not in a recent search for this edition. Search again.',
      };
      const url = '/api/admin/editions/101/opensubtitles/download';
      expect(await refused('POST', url, { fileId: 6012, kind: 'forced' })).toMatchObject(
        notOffered,
      );
      expect(await refused('POST', url, { fileId: 5012, kind: 'full' })).toMatchObject(notOffered);
      expect(
        await refused('POST', '/api/admin/editions/102/opensubtitles/download', {
          fileId: 5013,
          kind: 'forced',
        }),
      ).toMatchObject(notOffered);
    });

    it('refuses a bad kind or file id (400) and an unknown edition (404)', async () => {
      expect(await refused('GET', '/api/admin/editions/101/opensubtitles')).toMatchObject({
        status: 400,
        message: 'The kind must be full or forced',
      });
      expect(await refused('GET', '/api/admin/editions/101/opensubtitles?kind=FULL')).toMatchObject(
        {
          status: 400,
        },
      );
      expect(await refused('GET', '/api/admin/editions/999/opensubtitles?kind=full')).toMatchObject(
        {
          status: 404,
          message: 'No such edition',
        },
      );
      const url = '/api/admin/editions/101/opensubtitles/download';
      expect(await refused('POST', url, { fileId: 5012, kind: 'both' })).toMatchObject({
        status: 400,
        message: 'The kind must be full or forced',
      });
      expect(await refused('POST', url)).toMatchObject({ status: 400 });
      expect(await refused('POST', url, { kind: 'forced' })).toMatchObject({
        status: 400,
        message: 'A file id is required',
      });
      expect(await refused('POST', url, { fileId: 0, kind: 'forced' })).toMatchObject({
        status: 400,
      });
      expect(
        await refused('POST', '/api/admin/editions/999/opensubtitles/download', {
          fileId: 5012,
          kind: 'forced',
        }),
      ).toMatchObject({ status: 404, message: 'No such edition' });
    });
  });

  describe('the picker’s failures and what a download settles in the lists', () => {
    const refused = async (method, path, body) => {
      const pending = handle(method, path, body).catch((error) => error);
      await vi.advanceTimersByTimeAsync(80);
      return pending;
    };

    it('answers the daily limit (429) for the lowest-ranked forced file, with the server’s text in the body', async () => {
      await call('GET', '/api/admin/editions/101/opensubtitles?kind=forced');

      const failure = await refused('POST', '/api/admin/editions/101/opensubtitles/download', {
        fileId: 5013,
        kind: 'forced',
      });

      expect(failure).toBeInstanceOf(ApiError);
      expect(failure.status).toBe(429);
      expect(failure.body).toEqual({
        error: 'OpenSubtitles daily limit reached — try again tomorrow.',
      });
      // It was refused, not downloaded: the file can still be asked for, and nothing was counted.
      expect(await call('GET', '/api/admin/editions/101/opensubtitles?kind=forced')).toMatchObject({
        downloadsLeft: 3,
      });
    });

    it('answers a charged download that could not be delivered (502) for the second full file', async () => {
      await call('GET', '/api/admin/editions/102/opensubtitles?kind=full');

      const failure = await refused('POST', '/api/admin/editions/102/opensubtitles/download', {
        fileId: 6011,
        kind: 'full',
      });

      expect(failure.status).toBe(502);
      expect(failure.body).toEqual({
        error:
          "OpenSubtitles counted this download against today's limit but could not deliver the file. Try again.",
      });
    });

    it('carries the error text of its other refusals in a body too, as the API does', async () => {
      const failure = await refused('GET', '/api/admin/editions/999/opensubtitles?kind=full');

      expect(failure.body).toEqual({ error: 'No such edition' });
    });

    it('settles a forced row once a file is downloaded for the edition it names, and drops its edition', async () => {
      const before = (await call('GET', '/api/admin/forced-subtitles/films')).films.find(
        (f) => f.filmId === 3,
      );
      expect(before).toMatchObject({ editionId: 31, beingChecked: true, hasForcedSubtitle: false });
      await call('GET', '/api/admin/editions/31/opensubtitles?kind=forced');

      await call('POST', '/api/admin/editions/31/opensubtitles/download', {
        fileId: 5012,
        kind: 'forced',
      });

      const list = await call('GET', '/api/admin/forced-subtitles/films');
      expect(list.films.find((f) => f.filmId === 3)).toMatchObject({
        editionId: null,
        beingChecked: false,
        unresolved: false,
        hasForcedSubtitle: true,
      });
      expect(list.counts.beingChecked).toBe(0);
    });

    it('settles a full row the same way: a downloaded subtitle plays, so it is no longer being checked', async () => {
      const before = (await call('GET', '/api/admin/subtitles/full-need')).films.find(
        (f) => f.filmId === 7,
      );
      expect(before).toMatchObject({ editionId: 71, beingChecked: true, subtitled: false });
      await call('GET', '/api/admin/editions/71/opensubtitles?kind=full');

      await call('POST', '/api/admin/editions/71/opensubtitles/download', {
        fileId: 6012,
        kind: 'full',
      });

      const list = await call('GET', '/api/admin/subtitles/full-need');
      expect(list.films.find((f) => f.filmId === 7)).toMatchObject({
        fullSubtitle: { source: 'DOWNLOAD' },
        beingChecked: false,
        unresolved: false,
        subtitled: true,
      });
    });

    it('gives a film the owner called needed the edition the picker searches', async () => {
      const chosen = await call('PUT', '/api/admin/films/1/forced-need', { choice: 'NEEDED' });

      expect(chosen).toMatchObject({ editionId: 11, unresolved: true });
      const cleared = await call('PUT', '/api/admin/films/1/forced-need', { choice: 'NOT_NEEDED' });
      expect(cleared.editionId).toBeNull();
    });
  });

  // Last: it changes what the later reads of the full-need list say, and the module keeps that state.
  it('attaches a file by hand on POST like the real route: the row names the film and edition, and the film’s need is settled', async () => {
    const failure = async (id, body) => {
      const pending = handle('POST', `/api/admin/subtitles/${id}/attach`, body).catch((e) => e);
      await vi.advanceTimersByTimeAsync(80);
      return pending;
    };
    const before = await call('GET', '/api/admin/subtitles/full-need');
    expect(before.films.find((f) => f.filmId === 6)).toMatchObject({
      fullSubtitle: null,
      unresolved: true,
    });

    await call('POST', '/api/admin/subtitles/1/verify');
    const answer = await call('POST', '/api/admin/subtitles/1/attach', {
      editionId: 62,
      part: null,
    });

    // Moving a file takes its check away.
    expect(answer.verified).toBe(false);
    expect(answer).toMatchObject({
      id: 1,
      state: 'matched',
      ownerAttached: true,
      filmId: 6,
      filmTitle: 'Blade Runner',
      editionId: 62,
      editionName: "Director's Cut",
      parts: 1,
      part: null,
      whole: true,
    });
    const listed = (await call('GET', '/api/admin/subtitles')).find((f) => f.id === 1);
    expect(listed).toEqual(answer);
    const after = await call('GET', '/api/admin/subtitles/full-need');
    expect(after.films.find((f) => f.filmId === 6)).toMatchObject({
      fullSubtitle: { source: 'SIDECAR' },
      unresolved: false,
      beingChecked: false,
    });
    expect(after.counts.unresolved).toBe(before.counts.unresolved - 1);
    expect(after.counts.subtitled).toBe(before.counts.subtitled + 1);

    const part = await call('POST', '/api/admin/subtitles/1/attach', { editionId: 11, part: 2 });
    expect(part).toMatchObject({ filmId: 1, editionName: 'Theatrical Cut', part: 2, whole: false });
    expect((await failure(1, {})).status).toBe(400);
    expect((await failure(1, { editionId: 999 })).status).toBe(404);
    expect((await failure(999, { editionId: 11 })).status).toBe(404);
  });

  describe('the Plex import', () => {
    const at = (query) => window.history.replaceState(null, '', `/admin/library${query}`);

    it('is reachable with its last import by default', async () => {
      const plex = await call('GET', '/api/admin/plex');
      expect(plex).toMatchObject({ configured: true, reachable: true, running: false });
      expect(plex.lastImport.report.paired).toBe(1037);
    });

    it('is unreachable, importing or not configured when the page asks (?plex=)', async () => {
      at('?plex=unreachable');
      expect(await call('GET', '/api/admin/plex')).toMatchObject({
        configured: true,
        reachable: false,
      });
      at('?plex=importing');
      expect(await call('GET', '/api/admin/plex')).toMatchObject({
        running: true,
        progress: { processed: 12, total: 40 },
      });
      at('?plex=none');
      expect(await call('GET', '/api/admin/plex')).toMatchObject({
        configured: false,
        lastImport: null,
      });
    });

    it('starts an import that advances ten films a read and then ends', async () => {
      expect(await call('POST', '/api/admin/plex/import')).toMatchObject({
        running: true,
        progress: { processed: 0 },
      });
      expect((await call('GET', '/api/admin/plex')).progress.processed).toBe(10);
      expect((await call('GET', '/api/admin/plex')).progress.processed).toBe(20);
      expect((await call('GET', '/api/admin/plex')).progress.processed).toBe(30);
      expect(await call('GET', '/api/admin/plex')).toMatchObject({
        running: false,
        progress: null,
      });
      expect(await call('GET', '/api/admin/plex')).toMatchObject({ running: false });
    });

    it('serves the credits status, and a started credits import as running', async () => {
      expect((await call('GET', '/api/admin/plex/credits')).lastImport.report.imported).toBe(860);
      expect(await call('POST', '/api/admin/plex/credits/import')).toMatchObject({
        running: true,
        lastImport: null,
      });
    });
  });

  // After the attach test: it works on row 1, which that test attached by hand.
  describe('the per-file tools of Film detail', () => {
    const failure = async (method, path, body) => {
      const pending = handle(method, path, body).catch((e) => e);
      await vi.advanceTimersByTimeAsync(80);
      return pending;
    };

    it('gives a hand-attached file back to automatic matching, and refuses a file that was not', async () => {
      const answer = await call('POST', '/api/admin/subtitles/1/detach');
      expect(answer).toMatchObject({
        id: 1,
        state: 'unmatched',
        ownerAttached: false,
        filmId: null,
        editionId: null,
      });
      expect((await call('GET', '/api/admin/subtitles')).find((f) => f.id === 1)).toEqual(answer);

      const again = await failure('POST', '/api/admin/subtitles/1/detach');
      expect([again.status, again.body]).toEqual([
        400,
        { error: 'That file was not attached by hand' },
      ]);
      expect((await failure('POST', '/api/admin/subtitles/999/detach')).status).toBe(404);
    });

    it('answers a verify as the server recomputes the row: a checked file no longer reads as replaced by a download, and does again once unchecked', async () => {
      const before = (await call('GET', '/api/admin/subtitles')).find((f) => f.id === 7);
      expect(before.replacedByDownload).toBe(true);

      const checked = await call('POST', '/api/admin/subtitles/7/verify');
      expect(checked).toMatchObject({ verified: true, replacedByDownload: false, served: true });
      const undone = await call('DELETE', '/api/admin/subtitles/7/verify');
      expect(undone).toMatchObject({ verified: false, replacedByDownload: true, served: false });
    });

    it('marks a file as checked on POST verify and unmarks it on DELETE', async () => {
      const checked = await call('POST', '/api/admin/subtitles/7/verify');
      expect(checked).toMatchObject({ id: 7, verified: true });
      expect((await call('GET', '/api/admin/subtitles')).find((f) => f.id === 7).verified).toBe(
        true,
      );

      const undone = await call('DELETE', '/api/admin/subtitles/7/verify');
      expect(undone).toMatchObject({ id: 7, verified: false });
      expect((await failure('POST', '/api/admin/subtitles/999/verify')).status).toBe(404);
    });

    it('clears a file’s check when its timing is set, as the server’s change does', async () => {
      await call('POST', '/api/admin/subtitles/3/verify');
      const answer = await call('PUT', '/api/admin/subtitles/3/offset', { offsetMs: 0 });

      expect(answer.verified).toBe(false);
    });

    it('sets a file’s timing as the owner’s word, moves its version on, and refuses what the server refuses', async () => {
      const before = (await call('GET', '/api/admin/subtitles')).find((f) => f.id === 3);

      const answer = await call('PUT', '/api/admin/subtitles/3/offset', { offsetMs: 500 });

      expect(answer).toMatchObject({ id: 3, offsetMs: 500, offsetSource: 'OWNER' });
      expect(answer.version).toBeGreaterThan(before.version);
      expect((await call('GET', '/api/admin/subtitles')).find((f) => f.id === 3).offsetMs).toBe(
        500,
      );
      expect((await failure('PUT', '/api/admin/subtitles/3/offset', {})).status).toBe(400);
      expect(
        (await failure('PUT', '/api/admin/subtitles/3/offset', { offsetMs: 600001 })).status,
      ).toBe(400);
      expect(
        (await failure('PUT', '/api/admin/subtitles/999/offset', { offsetMs: 1 })).status,
      ).toBe(404);
      await call('PUT', '/api/admin/subtitles/3/offset', { offsetMs: before.offsetMs });
    });

    it('serves a few lines of a file on its timeline, and 404s a file that is gone', async () => {
      const cues = await call('GET', '/api/admin/subtitles/3/cues');
      expect(cues.offsetMs).toBe(0);
      expect(cues.cues).toHaveLength(3);
      expect(cues.cues[0]).toEqual({
        startMs: 60000,
        endMs: 62500,
        text: 'Line 1 of Taken (2008)/Taken.en.forced.srt',
      });
      expect((await failure('GET', '/api/admin/subtitles/999/cues')).status).toBe(404);
    });

    it('lets the owner’s file play again when the download that replaced it is removed', async () => {
      const before = (await call('GET', '/api/admin/subtitles')).find((f) => f.id === 7);
      expect(before).toMatchObject({
        served: false,
        replacedByDownload: true,
        servedReason: 'Not playing: an OpenSubtitles download replaced it',
      });

      await call('DELETE', '/api/admin/subtitles/6');

      const after = (await call('GET', '/api/admin/subtitles')).find((f) => f.id === 7);
      expect(after).toMatchObject({
        served: true,
        replacedByDownload: false,
        servedReason: 'Playing: your file',
      });
    });
  });
});
