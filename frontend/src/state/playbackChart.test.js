import { describe, expect, it } from 'vitest';
import {
  bucketColour,
  chartSummary,
  chartTiles,
  columnLabel,
  environmentKey,
  environmentLetter,
  filterByEnvironment,
  median,
  minuteStrip,
  percentText,
  sessionDotSize,
  bucketShares,
  sessionHeading,
  stalledNote,
  startupText,
  stallDotSize,
  STRIP,
} from './playbackChart.js';

const session = (over = {}) => ({
  playbackId: 'p',
  filmTitle: 'Heat',
  startedAt: '2026-11-10T19:43:05Z',
  watchedMs: 4_320_000,
  stallCount: 0,
  stallMs: 0,
  startupMs: 1000,
  environment: 'home',
  rungMs: { original: 1000 },
  userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)',
  ...over,
});

describe('environment', () => {
  it('names the three places and everything else unknown', () => {
    expect(['home', 'away', 'train', 'unknown', null, undefined, 'x'].map(environmentKey)).toEqual([
      'home',
      'away',
      'train',
      'unknown',
      'unknown',
      'unknown',
      'unknown',
    ]);
    expect([
      environmentLetter('home'),
      environmentLetter('away'),
      environmentLetter('train'),
    ]).toEqual(['H', 'A', 'T']);
    expect(environmentLetter('unknown')).toBe('?');
    expect(environmentLetter(null)).toBe('?');
  });

  it('keeps every session under All, an unknown one only there', () => {
    const list = [
      session({ playbackId: 'h', environment: 'home' }),
      session({ playbackId: 't', environment: 'train' }),
      session({ playbackId: 'u', environment: 'unknown' }),
      session({ playbackId: 'n', environment: undefined }),
    ];
    expect(filterByEnvironment(list, 'all')).toHaveLength(4);
    expect(filterByEnvironment(list, 'home').map((s) => s.playbackId)).toEqual(['h']);
    expect(filterByEnvironment(list, 'train').map((s) => s.playbackId)).toEqual(['t']);
    expect(filterByEnvironment(list, 'away')).toEqual([]);
  });
});

describe('bucketShares', () => {
  it('splits the time into the four buckets, 480p and below together', () => {
    expect(
      bucketShares({
        original: 100,
        '1080p': 100,
        '720p': 100,
        '480p': 50,
        '360p': 25,
        '240p': 25,
      }),
    ).toEqual({
      original: 0.25,
      p1080: 0.25,
      p720: 0.25,
      low: 0.25,
    });
  });

  it('adds up to one whenever there is time', () => {
    const shares = bucketShares({ original: 1, '720p': 2, '360p': 4 });
    expect(Object.values(shares).reduce((a, b) => a + b, 0)).toBeCloseTo(1, 10);
  });

  it('is all zero for no time, no rungs or no report', () => {
    const zero = { original: 0, p1080: 0, p720: 0, low: 0 };
    expect(bucketShares({})).toEqual(zero);
    expect(bucketShares({ original: 0 })).toEqual(zero);
    expect(bucketShares(null)).toEqual(zero);
    expect(bucketShares(undefined)).toEqual(zero);
  });

  it('leaves out a rung the ladder does not know, and a bad figure', () => {
    expect(bucketShares({ original: 10, auto: 90, '720p': -5, '1080p': 'x' })).toEqual({
      original: 1,
      p1080: 0,
      p720: 0,
      low: 0,
    });
  });
});

describe('stallDotSize', () => {
  it('is no dot for no stalling, and grows at 5 s and 20 s', () => {
    expect(stallDotSize(0)).toBe(0);
    expect(stallDotSize(1)).toBe(8);
    expect(stallDotSize(4_999)).toBe(8);
    expect(stallDotSize(5_000)).toBe(12);
    expect(stallDotSize(5_001)).toBe(12);
    expect(stallDotSize(19_999)).toBe(12);
    expect(stallDotSize(20_000)).toBe(16);
    expect(stallDotSize(20_001)).toBe(16);
  });

  it('draws nothing for a missing or negative figure', () => {
    expect([undefined, null, NaN, -1].map(stallDotSize)).toEqual([0, 0, 0, 0]);
  });
});

describe('sessionDotSize', () => {
  it('shows a dot for every session that stalled, sized by its stall time', () => {
    expect(sessionDotSize(session({ stallCount: 2, stallMs: 9400 }))).toBe(12);
    expect(sessionDotSize(session({ stallCount: 1, stallMs: 25_000 }))).toBe(16);
  });

  it('gives a stall that left no measured time the smallest dot, as the stalled tile counts it', () => {
    const row = session({ stallCount: 1, stallMs: 0 });
    expect(sessionDotSize(row)).toBe(8);
    expect(chartTiles([row]).stalled).toBe(1);
  });

  it('shows none for a session without a stall, however long its stall time says', () => {
    expect(sessionDotSize(session({ stallCount: 0, stallMs: 0 }))).toBe(0);
    expect(sessionDotSize(session({ stallCount: 0, stallMs: 9000 }))).toBe(0);
    expect(sessionDotSize(session({ stallCount: undefined }))).toBe(0);
  });
});

describe('median', () => {
  it('takes the middle of an odd count, however it is ordered', () => {
    expect(median([9, 1, 5])).toBe(5);
  });
  it('takes the mean of the two middle values of an even count', () => {
    expect(median([4, 1, 3, 2])).toBe(2.5);
  });
  it('is null for none, and does not reorder its argument', () => {
    expect(median([])).toBeNull();
    const values = [3, 1, 2];
    median(values);
    expect(values).toEqual([3, 1, 2]);
  });
});

describe('chartTiles', () => {
  it('counts sessions, with and without a stall', () => {
    const tiles = chartTiles([
      session(),
      session({ stallCount: 1, stallMs: 2000, environment: 'train' }),
      session({ stallCount: 2, stallMs: 9000, environment: 'train' }),
    ]);
    expect(tiles).toMatchObject({
      watched: 3,
      withoutStall: 1,
      stalled: 2,
      stalledEnvironment: 'train',
    });
  });

  it('names the place when one stalled session or several share it', () => {
    expect(chartTiles([session({ stallCount: 1, environment: 'away' })]).stalledEnvironment).toBe(
      'away',
    );
    expect(
      chartTiles([
        session({ stallCount: 1, environment: 'home' }),
        session({ stallCount: 1, environment: 'home' }),
        session({ environment: 'train' }),
      ]).stalledEnvironment,
    ).toBe('home');
  });

  it('names no place for stalled sessions in different places, none stalled, or an unknown one', () => {
    expect(
      chartTiles([
        session({ stallCount: 1, environment: 'home' }),
        session({ stallCount: 1, environment: 'train' }),
      ]).stalledEnvironment,
    ).toBeNull();
    expect(chartTiles([session()]).stalledEnvironment).toBeNull();
    expect(
      chartTiles([session({ stallCount: 1, environment: 'unknown' })]).stalledEnvironment,
    ).toBeNull();
  });

  it('takes the typical start from the sessions that started', () => {
    expect(
      chartTiles([
        session({ startupMs: 1000 }),
        session({ startupMs: null }),
        session({ startupMs: 3000 }),
        session({ startupMs: 2000 }),
        session({ startupMs: 4000 }),
      ]).medianStartupMs,
    ).toBe(2500);
    expect(chartTiles([session({ startupMs: null })]).medianStartupMs).toBeNull();
  });

  it('weighs the time at Original or 1080p over every session', () => {
    expect(
      chartTiles([
        session({ rungMs: { original: 600, '1080p': 200 } }),
        session({ rungMs: { '720p': 100, '480p': 100 } }),
      ]).topRungPercent,
    ).toBe(80); // by time (800 of 1000 ms), not the mean of 100% and 0%
  });

  it('has no figures for no sessions, and no percentage for no time', () => {
    expect(chartTiles([])).toEqual({
      watched: 0,
      withoutStall: 0,
      stalled: 0,
      stalledEnvironment: null,
      medianStartupMs: null,
      topRungPercent: null,
    });
    expect(chartTiles([session({ rungMs: null })]).topRungPercent).toBeNull();
  });
});

describe('words', () => {
  it('formats the start, the percentage and the note', () => {
    expect(startupText(1600)).toBe('1.6 s');
    expect(startupText(null)).toBe('–');
    expect(percentText(86)).toBe('86%');
    expect(percentText(null)).toBe('–');
    expect(stalledNote('train')).toBe('all on the train');
    expect(stalledNote('home')).toBe('all at home');
    expect(stalledNote('away')).toBe('all away');
    expect(stalledNote(null)).toBeNull();
  });

  it('says each column in a sentence', () => {
    const label = columnLabel(
      session({
        stallCount: 2,
        stallMs: 9400,
        environment: 'train',
        rungMs: { '480p': 1, '360p': 2, original: 7 },
      }),
    );
    expect(label).toBe(
      'Heat, 10 Nov 19:43, Train. 2 stalls, 9 s. Original 70%, 480p or lower 30%.',
    );
    expect(
      columnLabel(session({ stallCount: 1, stallMs: 1000, environment: undefined, rungMs: null })),
    ).toBe('Heat, 10 Nov 19:43, Unknown. 1 stall, 1 s. No quality recorded.');
    expect(columnLabel(session())).toBe('Heat, 10 Nov 19:43, Home. No stalls. Original 100%.');
  });

  it('summarises the chart, for one place or all, and when empty', () => {
    const list = [session(), session({ stallCount: 1, environment: 'home', startupMs: 3000 })];
    expect(chartSummary(list, { days: 30, environment: 'all' })).toBe(
      'Quality and stalls for each of the last 2 sessions in 30 days: 1 played without a stall, 1 stalled, typical start 2.0 s, 100% of time at Original or 1080p.',
    );
    expect(chartSummary([list[0]], { days: 90, environment: 'home' })).toContain(
      'the last 1 session in 90 days on Home:',
    );
    expect(chartSummary([], { days: 30, environment: 'train' })).toBe(
      'No film sessions in the last 30 days on Train.',
    );
  });

  it('heads a session with its date, device, place and time watched', () => {
    expect(sessionHeading(session({ environment: 'train', touch: true }), 'EE Limited')).toEqual([
      '10 Nov 19:43',
      'iPad',
      'Train (EE Limited)',
      '1h 12m watched',
    ]);
    expect(sessionHeading(session({ userAgent: null, environment: 'x' }), null)).toEqual([
      '10 Nov 19:43',
      'Unknown device',
      'Unknown',
      '1h 12m watched',
    ]);
  });

  it('knows the colour of a bucket', () => {
    expect(bucketColour('original')).toBe('#EDE6DA');
    expect(bucketColour('nope')).toBeUndefined();
  });
});

describe('minuteStrip', () => {
  const seven = (minute, kbps, rung, stalled = 0) => [
    minute,
    kbps,
    rung,
    60000 - stalled,
    stalled,
    9,
    rung,
  ];
  const five = (minute, kbps, rung, stalled = 0) => [minute, kbps, rung, 60000 - stalled, stalled];

  it('reads rows of seven figures', () => {
    const strip = minuteStrip([seven(0, 1000, 1), seven(1, 2000, 3, 4000), seven(2, 500, 5)]);
    expect(strip.count).toBe(3);
    expect(strip.durationLabel).toBe('0:03');
    expect(strip.bands.map((b) => b.bucket)).toEqual(['original', 'p720', 'low']);
    expect(strip.stalls).toEqual([{ x: 500, size: 8, minute: 1 }]);
    expect(strip.line).toBe('M166.7 56L166.7 56L500 24L833.3 72');
  });

  it('reads rows of five figures to the same strip', () => {
    const rows = [
      [0, 1000, 1, 60000, 0],
      [1, 2000, 3, 56000, 4000],
    ];
    const old = minuteStrip(rows);
    const current = minuteStrip(rows.map((r) => [...r, 9, r[2]]));
    expect(old).toEqual(current);
    expect(old.stalls).toHaveLength(1);
    expect(minuteStrip([five(0, 1000, 2)]).bands[0].bucket).toBe('p1080');
  });

  it('breaks the line at a minute with no speed, and draws a lone minute as a point', () => {
    const strip = minuteStrip([
      seven(0, 1000, 1),
      seven(1, 0, 1),
      seven(2, 1000, 1),
      seven(3, 1000, 1),
    ]);
    expect(strip.line.match(/M/g)).toHaveLength(2);
    expect(strip.line.startsWith('M')).toBe(true);
    expect(strip.line).toMatch(/^M125 24L125 24M/);
  });

  it('has no line when no minute has a speed, and no band for an unknown rung', () => {
    const strip = minuteStrip([seven(0, 0, 0), seven(1, 0, 0)]);
    expect(strip.line).toBe('');
    expect(strip.bands).toEqual([]);
  });

  it('draws minutes in order, leaves a gap for a missing one and sizes the dots like the chart', () => {
    const strip = minuteStrip([seven(4, 1000, 1, 25_000), seven(0, 1000, 1, 6_000)]);
    expect(strip.count).toBe(5);
    expect(strip.bands.map((b) => b.x)).toEqual([0, 800]);
    expect(strip.stalls.map((d) => [d.minute, d.size])).toEqual([
      [0, 12],
      [4, 16],
    ]);
    expect(strip.line.match(/M/g)).toHaveLength(2);
  });

  it('formats an hour and a half as h:mm', () => {
    expect(minuteStrip([seven(108, 1000, 1)]).durationLabel).toBe('1:49');
  });

  it('skips a row that is not a row of five or seven numbers', () => {
    const strip = minuteStrip([
      seven(0, 1000, 1),
      [1, 1000, 1],
      'x',
      null,
      [2, 1000, 1, 1, 'z'],
      [3, 1000, 1, 1, 1, 1],
      seven(-1, 1000, 1),
    ]);
    expect(strip.count).toBe(1);
  });

  it('is null for no readable row, or no list at all', () => {
    expect(minuteStrip([])).toBeNull();
    expect(minuteStrip(undefined)).toBeNull();
    expect(minuteStrip([[1, 2]])).toBeNull();
  });

  it('keeps every shape inside the strip', () => {
    const strip = minuteStrip([seven(0, 5000, 1, 30_000), seven(1, 100, 2)]);
    expect(strip.bands.every((b) => b.x + b.width <= STRIP.width)).toBe(true);
    expect(strip.line).toContain('L');
  });
});
