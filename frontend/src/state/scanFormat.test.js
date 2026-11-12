import { describe, expect, it } from 'vitest';
import {
  countTiles,
  describeLastScan,
  describeSubtitleSearch,
  formatScanTime,
  kindLabel,
  progressPercent,
  progressText,
  titleWithYear,
} from './scanFormat.js';

describe('formatScanTime', () => {
  it('writes day, month and 24-hour time in London, as the handover comp shows it', () => {
    expect(formatScanTime('2026-11-08T12:41:10Z')).toBe('08 Nov 12:41');
  });

  it('uses London time in summer, an hour ahead of UTC', () => {
    expect(formatScanTime('2026-07-01T23:30:00Z')).toBe('02 Jul 00:30');
  });
});

describe('describeLastScan', () => {
  it('says Never before the first scan', () => {
    expect(describeLastScan(null)).toBe('Never');
  });

  it('reads like the comp: time, new and missing', () => {
    expect(
      describeLastScan({ finishedAt: '2026-11-08T12:41:10Z', newFiles: 2, missingFiles: 0 }),
    ).toBe('08 Nov 12:41 · 2 new, 0 missing');
  });
});

describe('countTiles', () => {
  const counts = {
    films: 538,
    newFiles: 2,
    missing: 1,
    protectedFiles: 3,
    needsConversion: 4,
    needsMatch: 5,
    unreadable: 0,
  };

  it('lists films, new, missing, protected, needs conversion and need a match, in that order', () => {
    expect(countTiles(counts)).toEqual([
      { key: 'films', label: 'Films', value: 538 },
      { key: 'newFiles', label: 'New', value: 2 },
      { key: 'missing', label: 'Missing', value: 1 },
      { key: 'protectedFiles', label: 'Protected', value: 3 },
      { key: 'needsConversion', label: 'Needs conversion', value: 4 },
      { key: 'needsMatch', label: 'Need a match', value: 5 },
    ]);
  });

  it('adds Unreadable only when there is one', () => {
    expect(countTiles({ ...counts, unreadable: 1 }).at(-1)).toEqual({
      key: 'unreadable',
      label: 'Unreadable',
      value: 1,
    });
    expect(countTiles(counts).some((t) => t.key === 'unreadable')).toBe(false);
  });
});

describe('progress', () => {
  it('reads processed over total', () => {
    expect(progressText({ processed: 12, total: 40 })).toBe('12 / 40 files');
  });

  it('says it is looking through the folder before the total is known', () => {
    expect(progressText({ processed: 0, total: 0 })).toBe('Looking through the folder…');
  });

  it('turns processed over total into a rounded percentage, clamped to 100', () => {
    expect(progressPercent({ processed: 1, total: 3 })).toBe(33);
    expect(progressPercent({ processed: 2, total: 3 })).toBe(67);
    expect(progressPercent({ processed: 40, total: 40 })).toBe(100);
    expect(progressPercent({ processed: 50, total: 40 })).toBe(100);
    expect(progressPercent({ processed: 0, total: 40 })).toBe(0);
    expect(progressPercent({ processed: 5, total: 0 })).toBe(0);
  });
});

describe('attention labels', () => {
  it('names every kind of entry', () => {
    expect(kindLabel('needs-match')).toBe('Unmatched');
    expect(kindLabel('protected')).toBe('Protected');
    expect(kindLabel('needs-conversion')).toBe('Needs conversion');
    expect(kindLabel('unreadable')).toBe('Unreadable');
    expect(kindLabel('multiple-files')).toBe('Several files');
  });

  it('shows a kind it does not know as it is', () => {
    expect(kindLabel('something-new')).toBe('something-new');
  });

  it('adds the year to a title when there is one', () => {
    expect(titleWithYear('Dune', 1984)).toBe('Dune (1984)');
    expect(titleWithYear('Dune', null)).toBe('Dune');
  });
});

describe('describeSubtitleSearch', () => {
  const state = (over = {}) => ({
    enabled: true,
    running: false,
    lastSearchedAt: '2026-11-12T06:00:00Z',
    ...over,
  });

  it('says when subtitles were last searched', () => {
    expect(describeSubtitleSearch(state(), formatScanTime)).toBe('Subtitles searched 12 Nov 06:00');
  });

  it('says searching while a search runs, whatever the last time was', () => {
    expect(describeSubtitleSearch(state({ running: true }), formatScanTime)).toBe(
      'Searching for subtitles…',
    );
  });

  it('says not searched yet before the first search', () => {
    expect(describeSubtitleSearch(state({ lastSearchedAt: null }), formatScanTime)).toBe(
      'Subtitles not searched yet',
    );
  });

  it('says nothing when the search is off', () => {
    expect(
      describeSubtitleSearch(state({ enabled: false, running: true }), formatScanTime),
    ).toBeNull();
  });
});
