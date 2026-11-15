import { describe, expect, it } from 'vitest';
import {
  GROUPING_PAGE_SIZE,
  countTiles,
  describeCreditsCount,
  describeGroupingCounts,
  describeScanHeader,
  describeSubtitleSearch,
  formatDuration,
  formatScanDay,
  formatScanTime,
  groupingCounts,
  groupingPage,
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

describe('formatScanDay', () => {
  it('writes the day without a leading zero, in London', () => {
    expect(formatScanDay('2026-11-09T05:35:00Z')).toBe('9 Nov');
    expect(formatScanDay('2026-07-01T23:30:00Z')).toBe('2 Jul');
  });
});

describe('formatDuration', () => {
  const at = (seconds) =>
    formatDuration(
      '2026-11-08T12:00:00Z',
      new Date(Date.parse('2026-11-08T12:00:00Z') + seconds * 1000),
    );

  it.each([
    [0, '0s'],
    [48, '48s'],
    [59, '59s'],
    [60, '1m 0s'],
    [108, '1m 48s'],
    [3599, '59m 59s'],
    [3600, '1h 0m'],
    [3900, '1h 5m'],
  ])('writes %i seconds as %s', (seconds, text) => {
    expect(at(seconds)).toBe(text);
  });

  it('reads a finish before the start as 0s', () => {
    expect(formatDuration('2026-11-08T12:00:10Z', '2026-11-08T12:00:00Z')).toBe('0s');
  });
});

describe('describeScanHeader', () => {
  const lastScan = { startedAt: '2026-11-08T12:40:02Z', finishedAt: '2026-11-08T12:41:50Z' };

  it('says when the last scan finished and how long it took', () => {
    expect(describeScanHeader(lastScan, Date.parse('2026-11-08T12:53:50Z'))).toBe(
      'Last scan 12 min ago · took 1m 48s',
    );
  });

  it('says not scanned yet before the first scan', () => {
    expect(describeScanHeader(null, Date.now())).toBe('Not scanned yet');
  });
});

describe('grouping report helpers', () => {
  const change = (kind, i = 0) => ({ kind, happenedAt: `2026-11-08T12:41:0${i}Z`, summary: kind });
  const many = (n) => Array.from({ length: n }, (_, i) => change('join', i % 10));

  it('counts changes by kind and ignores a kind it does not know', () => {
    const items = [change('split'), change('split'), change('join'), change('flag'), change('odd')];
    expect(groupingCounts(items)).toEqual({ split: 2, join: 1, flag: 1 });
    expect(describeGroupingCounts(items)).toBe('2 split, 1 joined, 1 flagged');
    expect(describeGroupingCounts([])).toBe('0 split, 0 joined, 0 flagged');
  });

  it('pages at 25: 24 and 25 show everything, 26 offers one more', () => {
    expect(GROUPING_PAGE_SIZE).toBe(25);
    expect(groupingPage(many(24), 25)).toMatchObject({ moreLabel: null });
    expect(groupingPage(many(24), 25).visible).toHaveLength(24);
    expect(groupingPage(many(25), 25)).toMatchObject({ moreLabel: null });
    expect(groupingPage(many(25), 25).visible).toHaveLength(25);
    const page = groupingPage(many(26), 25);
    expect(page.visible).toHaveLength(25);
    expect(page.moreLabel).toBe('Show 1 more');
  });

  it('offers at most 25 more, and stops at the end', () => {
    expect(groupingPage(many(80), 25).moreLabel).toBe('Show 25 more');
    expect(groupingPage(many(80), 50).moreLabel).toBe('Show 25 more');
    expect(groupingPage(many(80), 75).moreLabel).toBe('Show 5 more');
    expect(groupingPage(many(80), 100)).toMatchObject({ moreLabel: null });
    expect(groupingPage(many(80), 100).visible).toHaveLength(80);
  });
});

describe('describeCreditsCount', () => {
  it('reads "{n} of {total} films have credits"', () => {
    expect(describeCreditsCount({ films: 538, filmsWithCredits: 530 })).toBe(
      '530 of 538 films have credits',
    );
  });

  it('keeps a zero count', () => {
    expect(describeCreditsCount({ films: 12, filmsWithCredits: 0 })).toBe(
      '0 of 12 films have credits',
    );
  });

  it('says there are no films when the library is empty', () => {
    expect(describeCreditsCount({ films: 0, filmsWithCredits: 0 })).toBe(
      'No films in the library yet',
    );
  });

  it('uses the singular for one film', () => {
    expect(describeCreditsCount({ films: 1, filmsWithCredits: 1 })).toBe('1 of 1 film has credits');
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
      {
        key: 'protectedFiles',
        label: 'Can’t play (protected)',
        value: 3,
        to: '/admin/library/files/protected',
      },
      {
        key: 'needsConversion',
        label: 'Needs conversion',
        value: 4,
        to: '/admin/library/files/needs-conversion',
      },
      { key: 'needsMatch', label: 'Need a match', value: 5 },
    ]);
  });

  it('adds Unreadable only when there is one', () => {
    expect(countTiles({ ...counts, unreadable: 1 }).at(-1)).toEqual({
      key: 'unreadable',
      label: 'Unreadable',
      value: 1,
      to: '/admin/library/files/unreadable',
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
