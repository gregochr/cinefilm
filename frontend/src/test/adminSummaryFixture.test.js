import { describe, expect, it } from 'vitest';
import attention from './fixtures/admin-attention.json';
import forcedNeed from './fixtures/admin-forced-need.json';
import fullNeed from './fixtures/admin-full-need.json';
import playbackHealth from './fixtures/admin-playback-health.json';
import scan from './fixtures/admin-scan.json';
import summary from './fixtures/admin-summary.json';
import transcoding from './fixtures/admin-transcoding.json';
import { FILM_SESSION_MIN_MS } from '../state/healthFormat.js';

const DAY_MS = 86_400_000;
// The reference "now" the playback figures are read against. The health fixture's sessions started between 6 and 10
// Nov 2026 (the playing-now row started at 14 Nov but watched under three minutes, so it is no film session), so any "now" from 10 Nov to 6 Dec 2026 keeps all of them inside the 30 days; the test pins one.
const REFERENCE_NOW = Date.parse('2026-11-14T12:00:00Z');

const countKind = (...kinds) => attention.items.filter((i) => kinds.includes(i.kind)).length;

/**
 * `admin-summary.json` stands in for `GET /api/admin/summary`. Its figures must agree with the fixtures of the
 * routes the server reads them from; the rules themselves live on the server, so this only compares precomputed
 * counts and the server's own `kind`.
 */
describe('the Admin summary fixture', () => {
  it('has exactly the fields of the contract', () => {
    expect(Object.keys(summary).sort()).toEqual(
      ['library', 'live', 'playback', 'playingNow', 'subtitlesUnresolved', 'todo'].sort(),
    );
    expect(Object.keys(summary.todo).sort()).toEqual(
      ['forcedQuestion', 'fullMissing', 'pickFilm', 'severalFiles'].sort(),
    );
    expect(Object.keys(summary.library).sort()).toEqual([
      'films',
      'lastScanError',
      'lastScanFinishedAt',
    ]);
    expect(Object.keys(summary.playback).sort()).toEqual(['films', 'smooth']);
    for (const entry of summary.playingNow) {
      expect(Object.keys(entry).sort()).toEqual(
        ['device', 'editionId', 'environment', 'filmTitle', 'playbackId', 'quality'].sort(),
      );
    }
  });

  it('counts the same attention items the attention fixture holds', () => {
    expect(summary.todo.pickFilm).toBe(countKind('needs-match'));
    expect(summary.todo.severalFiles).toBe(countKind('multiple-files', 'parts-mismatch'));
    // The fixture has items of the excluded kinds, so a count of everything would be wrong.
    expect(attention.items.length).toBeGreaterThan(
      summary.todo.pickFilm + summary.todo.severalFiles,
    );
  });

  it('takes the subtitle counts from the need fixtures', () => {
    expect(summary.todo.fullMissing).toBe(fullNeed.counts.unresolved);
    expect(summary.todo.forcedQuestion).toBe(forcedNeed.counts.unresolved);
    expect(summary.subtitlesUnresolved).toBe(
      summary.todo.fullMissing + summary.todo.forcedQuestion,
    );
  });

  it('has as many live sessions as the transcoding fixture', () => {
    expect(summary.live).toBe(transcoding.sessions.length);
  });

  it('reports the scan fixture’s film count and last finish time', () => {
    expect(summary.library.films).toBe(scan.counts.films);
    expect(summary.library.lastScanFinishedAt).toBe(scan.lastScan.finishedAt);
    expect(summary.library.lastScanError).toBe(scan.lastScan.error);
  });

  it('counts the health fixture’s film sessions that started in the 30 days before the reference time', () => {
    const since = REFERENCE_NOW - 30 * DAY_MS;
    const filmSessions = playbackHealth.playbacks.filter(
      (row) => Date.parse(row.startedAt) >= since && row.report.watchedMs >= FILM_SESSION_MIN_MS,
    );
    expect(summary.playback.films).toBe(filmSessions.length);
    // `smooth` is the film sessions the server marks as having no problem. The fixture rows carry no `hasProblem`
    // flag (the server derives it), so the number is fixed by hand: the 10 Nov session stalled, reconnected and
    // had network errors, the other two film sessions had none of those.
    expect(filmSessions.filter((row) => row.report.stallCount > 0)).toHaveLength(1);
    expect(summary.playback.smooth).toBe(filmSessions.length - 1);
  });

  it('lists only viewings the health fixture holds, with the same film, edition and environment', () => {
    for (const entry of summary.playingNow) {
      const row = playbackHealth.playbacks.find((p) => p.playbackId === entry.playbackId);
      expect(row).toBeDefined();
      expect(row.editionId).toBe(entry.editionId);
      expect(row.filmTitle).toBe(entry.filmTitle);
      expect(row.report.environment).toBe(entry.environment);
      expect(row.report.client.userAgent).toBe(entry.device);
    }
  });

  it('has one playing-now entry, for a film the transcoding fixture has live, at the rung it plays', () => {
    expect(summary.playingNow).toHaveLength(1);
    const [entry] = summary.playingNow;
    const live = transcoding.sessions.find((s) => s.editionId === entry.editionId);
    expect(entry.filmTitle).toBe(live.filmTitle);
    expect(entry.quality).toBe(live.rung);
    expect(['home', 'train', 'away', null]).toContain(entry.environment);
  });
});
