import { describe, expect, it } from 'vitest';
import {
  creditsProgressText,
  creditsSkipLines,
  creditsTiles,
  describeLastCreditsImport,
} from './plexCreditsFormat.js';

const report = {
  plexFilms: 1087,
  matchedFilms: 1030,
  imported: 860,
  unmatched: 2,
  skipped: [
    { reason: 'MULTI_PART', description: 'Films split into parts', count: 3 },
    { reason: 'FILE_CHANGED', description: 'Files that changed during the import', count: 1 },
  ],
};

describe('creditsProgressText', () => {
  it('says how many films have been asked about', () => {
    expect(creditsProgressText({ processed: 12, total: 40 })).toBe('12 / 40 films');
  });

  it('is neutral while Plex is still being read', () => {
    expect(creditsProgressText({ processed: 0, total: 0 })).toBe('Reading Plex…');
  });
});

describe('describeLastCreditsImport', () => {
  it('says Not run yet before the first import', () => {
    expect(describeLastCreditsImport(null)).toBe('Not run yet');
    expect(describeLastCreditsImport(undefined)).toBe('Not run yet');
  });

  it('names the time and the markers stored', () => {
    const last = { finishedAt: '2026-11-13T14:20:00Z', error: null, report };
    expect(describeLastCreditsImport(last)).toBe('13 Nov 14:20 · 860 markers stored');
  });

  it('names the error when the import stopped', () => {
    const last = {
      finishedAt: '2026-11-13T14:20:00Z',
      error: 'Plex is not reachable',
      report: null,
    };
    expect(describeLastCreditsImport(last)).toBe('13 Nov 14:20 · Plex is not reachable');
  });

  it('counts zero markers when there is neither an error nor a report', () => {
    expect(describeLastCreditsImport({ finishedAt: '2026-11-13T14:20:00Z' })).toBe(
      '13 Nov 14:20 · 0 markers stored',
    );
  });
});

describe('creditsTiles', () => {
  it('lists the counts in block order', () => {
    expect(creditsTiles(report)).toEqual([
      { key: 'plexFilms', label: 'Plex films', value: 1087 },
      { key: 'matchedFilms', label: 'Matched', value: 1030 },
      { key: 'imported', label: 'Stored', value: 860 },
      { key: 'unmatched', label: 'Unmatched files', value: 2 },
    ]);
  });
});

describe('creditsSkipLines', () => {
  it('writes one line per reason, the count first', () => {
    expect(creditsSkipLines(report)).toEqual([
      { key: 'MULTI_PART', text: 'Films split into parts: 3' },
      { key: 'FILE_CHANGED', text: 'Files that changed during the import: 1' },
    ]);
  });

  it('is empty when nothing was skipped', () => {
    expect(creditsSkipLines({ ...report, skipped: [] })).toEqual([]);
  });
});
