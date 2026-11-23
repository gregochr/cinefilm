import { describe, expect, it } from 'vitest';
import {
  bucketColour,
  bucketShares,
  chartSummary,
  differentPlace,
  chartTiles,
  columnLabel,
  environmentKey,
  environmentLetter,
  filterByEnvironment,
  filterBySource,
  median,
  minuteStrip,
  partGap,
  partTimes,
  percentText,
  sessionDotSize,
  sessionHeading,
  sessionParts,
  stalledNote,
  stallDotSize,
  startupText,
  STRIP,
  watchedTileLabel,
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
    // Shares that add up to 100 even when each would round the same way: 34 / 33 / 33, not 33 / 33 / 33.
    expect(
      columnLabel(session({ rungMs: { original: 1000, '1080p': 1000, '720p': 1000 } })),
    ).toContain('Original 34%, 1080p 33%, 720p 33%.');
  });

  it('summarises the chart, for one place or all, and when empty', () => {
    const list = [session(), session({ stallCount: 1, environment: 'home', startupMs: 3000 })];
    expect(chartSummary(list, { days: 30, environment: 'all' })).toBe(
      'Quality and stalls for each of the last 2 sessions in 30 days: 1 played without a stall, 1 stalled, typical start 2.0 s, 100% of time at Original or 1080p.',
    );
    expect(chartSummary([list[0]], { days: 90, environment: 'home' })).toContain(
      'the last 1 session in 90 days at home:',
    );
    expect(chartSummary([], { days: 30, environment: 'train' })).toBe(
      'No film sessions in the last 30 days on the train.',
    );
    expect(chartSummary([list[0]], { days: 7, environment: 'away' })).toContain('in 7 days away:');
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

  it('says "in 2 parts" after the watched time only for a viewing of more than one part', () => {
    const part = (id) => ({ playbackId: id, startedAt: '2026-11-10T19:43:05Z', endedAt: null });
    expect(sessionHeading(session({ parts: [part('a')] }), null).at(-1)).toBe('1h 12m watched');
    expect(sessionHeading(session({ parts: [part('a'), part('b')] }), null).at(-1)).toBe(
      '1h 12m watched in 2 parts',
    );
    expect(sessionHeading(session({ parts: [part('a'), part('b'), part('c')] }), null).at(-1)).toBe(
      '1h 12m watched in 3 parts',
    );
  });

  it('knows the colour of a bucket', () => {
    expect(bucketColour('original')).toBe('#EDE6DA');
    expect(bucketColour('nope')).toBeUndefined();
  });
});

describe('sessionParts', () => {
  it('is the row’s own parts, oldest first', () => {
    const parts = [{ playbackId: 'a' }, { playbackId: 'b' }];
    expect(sessionParts(session({ parts }))).toBe(parts);
  });

  it('makes one part from the row itself when an older server sends none (or an empty list)', () => {
    const expected = [
      {
        playbackId: 'p',
        startedAt: '2026-11-10T19:43:05Z',
        endedAt: null,
        watchedMs: 4_320_000,
        environment: 'home',
      },
    ];
    expect(sessionParts(session())).toEqual(expected);
    expect(sessionParts(session({ parts: [] }))).toEqual(expected);
    expect(sessionParts(session({ endedAt: '2026-11-10T21:00:00Z' }))[0].endedAt).toBe(
      '2026-11-10T21:00:00Z',
    );
  });
});

describe('partTimes', () => {
  it('writes the start and end clock times in London', () => {
    expect(
      partTimes({ startedAt: '2026-11-12T17:56:53.956Z', endedAt: '2026-11-12T20:33:53.080Z' }),
    ).toBe('17:56–20:33');
  });

  it('gives the end its date when it falls on a later day', () => {
    expect(partTimes({ startedAt: '2026-11-11T23:40:00Z', endedAt: '2026-11-12T01:10:00Z' })).toBe(
      '23:40–12 Nov 01:10',
    );
  });

  it('judges the day in London, not UTC (summer, 23:30 UTC is 00:30 the next day)', () => {
    expect(partTimes({ startedAt: '2026-07-01T22:30:00Z', endedAt: '2026-07-01T23:30:00Z' })).toBe(
      '23:30–2 Jul 00:30',
    );
  });

  it('shows only the start when the end is not known', () => {
    expect(partTimes({ startedAt: '2026-11-12T17:56:53Z', endedAt: null })).toBe('17:56');
    expect(partTimes({ startedAt: '2026-11-12T17:56:53Z' })).toBe('17:56');
  });
});

describe('partGap', () => {
  const at = (gapMs) => [
    { endedAt: '2026-11-12T17:56:47.761Z' },
    { startedAt: new Date(Date.parse('2026-11-12T17:56:47.761Z') + gapMs).toISOString() },
  ];

  it('says picked up again, in whole seconds, under a minute', () => {
    expect(partGap(...at(6_192))).toBe('Picked up again after 6 s');
    expect(partGap(...at(0))).toBe('Picked up again after 0 s');
    expect(partGap(...at(59_999))).toBe('Picked up again after 59 s');
  });

  it('says paused from exactly a minute', () => {
    expect(partGap(...at(60_000))).toBe('Paused 1m');
    expect(partGap(...at(4_800_000))).toBe('Paused 1h 20m');
  });

  it('treats an overlap as no gap', () => {
    expect(partGap(...at(-5_000))).toBe('Picked up again after 0 s');
  });

  it('has nothing to say without the earlier part’s end', () => {
    expect(partGap({ endedAt: null }, { startedAt: '2026-11-12T18:00:00Z' })).toBeNull();
    expect(partGap({ endedAt: 'x' }, { startedAt: 'y' })).toBeNull();
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

  it('skips a row whose minute is not a whole number and keeps the first of two rows for one minute', () => {
    const strip = minuteStrip([
      seven(0.5, 1000, 1),
      seven(1, 1000, 1),
      seven(1, 2000, 3),
      seven(2, 1000, 1),
    ]);

    expect(strip.bands.map((b) => [b.x, b.bucket])).toEqual([
      [333.3, 'original'],
      [666.7, 'original'],
    ]);
    expect(strip.stalls).toEqual([]);
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

describe('differentPlace', () => {
  const first = { environment: 'train', operator: 'Icomera US, Inc.' };

  it('is null for the same environment and operator, or when an operator is not yet known', () => {
    expect(differentPlace({ environment: 'train' }, 'Icomera US, Inc.', first)).toBeNull();
    expect(differentPlace({ environment: 'train' }, null, first)).toBeNull();
    expect(differentPlace({ environment: 'train' }, 'EE', { ...first, operator: null })).toBeNull();
  });

  it('names the place when the environment or the operator differs', () => {
    expect(differentPlace({ environment: 'home' }, null, first)).toBe('Home');
    expect(differentPlace({ environment: 'train' }, 'EE Limited', first)).toBe(
      'Train (EE Limited)',
    );
  });
});

describe('source', () => {
  const person = session({ playbackId: 'a', source: 'person' });
  const robot = session({ playbackId: 'b', source: 'robot' });
  const unmarked = session({ playbackId: 'c' });

  it('keeps the people (a session with no source is one), the robots, or both', () => {
    expect(filterBySource([person, robot, unmarked], 'person')).toEqual([person, unmarked]);
    expect(filterBySource([person, robot, unmarked], 'robot')).toEqual([robot]);
    expect(filterBySource([person, robot, unmarked], 'all')).toEqual([person, robot, unmarked]);
  });

  it('words the first tile for what is counted', () => {
    expect(watchedTileLabel('person')).toBe('sessions watched');
    expect(watchedTileLabel('robot')).toBe('headless runs');
    expect(watchedTileLabel('all')).toBe('sessions, headless included');
  });

  it('ends the summary, before its colon, with the note for the source', () => {
    const list = [session()];
    expect(chartSummary(list, { days: 30, environment: 'home', source: 'person' })).toContain(
      'in 30 days at home:',
    );
    expect(chartSummary(list, { days: 30, environment: 'home', source: 'robot' })).toContain(
      'in 30 days at home (headless runs only):',
    );
    expect(chartSummary(list, { days: 30, environment: 'all', source: 'all' })).toContain(
      'in 30 days (headless runs included):',
    );
    expect(chartSummary([], { days: 30, environment: 'all', source: 'robot' })).toBe(
      'No film sessions in the last 30 days (headless runs only).',
    );
  });

  it('names a robot device "Headless run" and ends its column label with a sentence "Headless run."', () => {
    const robotSession = session({ source: 'robot', userAgent: 'HeadlessChrome/150.0.0.0' });
    expect(sessionHeading(robotSession, null)[1]).toBe('Headless run');
    expect(columnLabel(robotSession)).toBe(
      'Heat, 10 Nov 19:43, Home. No stalls. Original 100%. Headless run.',
    );
    expect(sessionHeading(person, null)[1]).toBe('Mac');
  });
});
