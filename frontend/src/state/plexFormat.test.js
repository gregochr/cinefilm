import { describe, expect, it } from 'vitest';
import {
  broughtAcross,
  describeLastImport,
  moreText,
  plexHeaderLine,
  plexProgressText,
  plexServerState,
  reportLists,
  reportTiles,
} from './plexFormat.js';

const report = {
  paired: 10,
  merged: 1,
  editionsMoved: 2,
  metadataApplied: 9,
  postersStored: 8,
  backdropsStored: 7,
  watchedImported: 6,
  resumePointsImported: 5,
  keptNewer: 4,
  onDiskNotInPlex: 3,
  failures: 0,
  artworkFailures: 0,
  invalidArtPaths: 0,
  tmdbUnavailable: 0,
  failedFilms: [],
  notOnDisk: [],
  notOnDiskTotal: 0,
  collections: [],
  collectionsTotal: 0,
};

describe('plexServerState', () => {
  it('says Importing while running, otherwise whether Plex answers', () => {
    expect(plexServerState({ running: true, reachable: true })).toBe('Importing');
    expect(plexServerState({ running: true, reachable: false })).toBe('Importing');
    expect(plexServerState({ running: false, reachable: true })).toBe('Reachable');
    expect(plexServerState({ running: false, reachable: false })).toBe('Not reachable');
  });
});

describe('plexHeaderLine', () => {
  it('says when the last import ran and how many films it paired', () => {
    expect(
      plexHeaderLine({ finishedAt: '2026-11-08T14:09:30Z', error: null, report: { paired: 1037 } }),
    ).toBe('Last import 08 Nov 14:09 · 1037 films paired');
  });

  it('gives the error of an import that stopped', () => {
    expect(
      plexHeaderLine({ finishedAt: '2026-11-08T14:09:30Z', error: 'Plex is not reachable' }),
    ).toBe('Last import 08 Nov 14:09 · Plex is not reachable');
  });

  it('says not imported yet before the first import', () => {
    expect(plexHeaderLine(null)).toBe('Not imported yet');
  });
});

describe('broughtAcross', () => {
  it('lists paired, watched, resume points and posters from the report', () => {
    expect(broughtAcross({ report })).toBe('10 paired · 6 watched · 5 resume points · 8 posters');
  });

  it('says nothing yet with no import, or an import that stopped before it could report', () => {
    expect(broughtAcross(null)).toBe('Nothing yet');
    expect(broughtAcross({ error: 'stopped', report: null })).toBe('Nothing yet');
  });
});

describe('describeLastImport', () => {
  it('says Never before the first import', () => {
    expect(describeLastImport(null)).toBe('Never');
  });

  it('gives the time and how many films paired', () => {
    expect(
      describeLastImport({
        finishedAt: '2026-11-08T14:09:30Z',
        error: null,
        report: { paired: 1037 },
      }),
    ).toBe('08 Nov 14:09 · 1037 films paired');
  });

  it('gives the error of an import that stopped', () => {
    expect(
      describeLastImport({
        finishedAt: '2026-11-08T14:09:30Z',
        error: 'Plex is not reachable',
        report: null,
      }),
    ).toBe('08 Nov 14:09 · Plex is not reachable');
  });

  it('counts zero when there is neither an error nor a report', () => {
    expect(
      describeLastImport({ finishedAt: '2026-11-08T14:09:30Z', error: null, report: null }),
    ).toBe('08 Nov 14:09 · 0 films paired');
  });
});

describe('plexProgressText', () => {
  it('shows films handled, or a neutral line while Plex is read', () => {
    expect(plexProgressText({ processed: 12, total: 40 })).toBe('12 / 40 films');
    expect(plexProgressText({ processed: 0, total: 0 })).toBe('Reading Plex…');
  });
});

describe('reportTiles', () => {
  it('lists the ten counts in order and hides problem tiles that are zero', () => {
    const tiles = reportTiles(report);
    expect(tiles.map((t) => `${t.label}${t.value}`)).toEqual([
      'Paired10',
      'Merged1',
      'Editions moved2',
      'Metadata applied9',
      'Posters stored8',
      'Backdrops stored7',
      'Watched6',
      'Resume points5',
      'Kept (CineFilm newer)4',
      'On disk, not in Plex3',
    ]);
  });

  it('adds a tile for each kind of problem that happened', () => {
    const tiles = reportTiles({
      ...report,
      failures: 1,
      artworkFailures: 2,
      invalidArtPaths: 3,
      tmdbUnavailable: 4,
    });
    expect(tiles.slice(10).map((t) => `${t.label}${t.value}`)).toEqual([
      'Failed1',
      'Artwork failed2',
      'Artwork paths refused3',
      'TMDB unavailable4',
    ]);
  });
});

describe('reportLists and moreText', () => {
  it('leaves empty lists out and keeps each with its real total', () => {
    const lists = reportLists({
      ...report,
      failures: 1,
      failedFilms: ['Kes'],
      notOnDisk: ['A', 'B'],
      notOnDiskTotal: 50,
    });
    expect(lists.map((l) => l.key)).toEqual(['failedFilms', 'notOnDisk']);
    expect(lists[1]).toMatchObject({ title: 'In Plex, not on disk', total: 50 });
  });

  it('says how many more there are beyond a capped list, and nothing when all are shown', () => {
    expect(moreText({ items: ['A', 'B'], total: 50 })).toBe('and 48 more');
    expect(moreText({ items: ['A', 'B'], total: 2 })).toBeNull();
    expect(moreText({ items: ['A', 'B'], total: 1 })).toBeNull();
  });
});
