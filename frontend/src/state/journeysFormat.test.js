import { describe, expect, it } from 'vitest';
import { ApiError } from '../api/client.js';
import {
  importFailure,
  importToast,
  journeyLines,
  loggedRange,
  problemText,
  readyLine,
  sentRows as rowsFor,
} from './journeysFormat.js';

const summary = (over = {}) => ({
  rounds: 363,
  from: Date.UTC(2030, 2, 4, 22, 0),
  to: Date.UTC(2030, 2, 4, 23, 15),
  units: ['Unit-001'],
  modems: [100, 101],
  loggerVersion: 3,
  hasDevice: true,
  travelClass: 'standard',
  unreadableLines: 0,
  leftOut: [],
  ...over,
});
const sentRows = (s, request = { sourceFile: 'example-log.jsonl' }) => rowsFor(s, request);
const valueOf = (rows, term) => rows.find((r) => r.term === term)?.value;

describe('readyLine', () => {
  it('counts the rounds', () => {
    expect(readyLine(summary())).toBe('Ready to import: 363 rounds');
  });

  it('says 1 round for one', () => {
    expect(readyLine(summary({ rounds: 1 }))).toBe('Ready to import: 1 round');
  });

  it('writes a thousand with its comma', () => {
    expect(readyLine(summary({ rounds: 1234 }))).toBe('Ready to import: 1,234 rounds');
  });
});

describe('loggedRange', () => {
  it('gives one day once when both ends are on it', () => {
    expect(loggedRange(Date.UTC(2030, 2, 4, 22, 0), Date.UTC(2030, 2, 4, 23, 15))).toBe(
      '4 Mar 2030, 22:00 to 23:15',
    );
  });

  it('writes both days when the ends are on different days', () => {
    expect(loggedRange(Date.UTC(2030, 2, 4, 22, 0), Date.UTC(2030, 2, 5, 0, 47))).toBe(
      '4 Mar 2030, 22:00 to 5 Mar 2030, 00:47',
    );
  });

  it('reads Britain’s clock in summer, an hour after UTC', () => {
    expect(loggedRange(Date.UTC(2030, 5, 4, 21, 0), Date.UTC(2030, 5, 4, 22, 15))).toBe(
      '4 Jun 2030, 22:00 to 23:15',
    );
  });

  it('puts the end on the next day when only Britain’s clock has passed midnight', () => {
    expect(loggedRange(Date.UTC(2030, 5, 4, 22, 30), Date.UTC(2030, 5, 4, 23, 15))).toBe(
      '4 Jun 2030, 23:30 to 5 Jun 2030, 00:15',
    );
  });

  it('writes every month in three letters', () => {
    const months = Array.from({ length: 12 }, (_, m) =>
      loggedRange(Date.UTC(2030, m, 15, 12), Date.UTC(2030, m, 15, 13)),
    );
    expect(months.map((text) => text.split(' ')[1])).toEqual([
      'Jan',
      'Feb',
      'Mar',
      'Apr',
      'May',
      'Jun',
      'Jul',
      'Aug',
      'Sep',
      'Oct',
      'Nov',
      'Dec',
    ]);
  });
});

describe('sentRows', () => {
  it('lists the terms in order with their values', () => {
    expect(sentRows(summary())).toEqual([
      { term: 'File name', value: 'example-log.jsonl' },
      { term: 'Rounds', value: '363' },
      { term: 'Logged', value: '4 Mar 2030, 22:00 to 23:15' },
      { term: 'Train units', value: 'Unit-001' },
      { term: 'Modems', value: '2' },
      { term: 'Logger version', value: '3' },
      { term: 'Laptop', value: 'Its model, chip and memory' },
      { term: 'Class', value: 'Standard' },
      { term: 'Left out', value: 'Nothing' },
    ]);
  });

  it('says Not sent when the request carries no file name', () => {
    expect(valueOf(sentRows(summary(), {}), 'File name')).toBe('Not sent');
  });

  it('joins several units with a comma and a space', () => {
    expect(valueOf(sentRows(summary({ units: ['Unit-001', 'Unit-002'] })), 'Train units')).toBe(
      'Unit-001, Unit-002',
    );
  });

  it('says None named when no unit is named', () => {
    expect(valueOf(sentRows(summary({ units: [] })), 'Train units')).toBe('None named');
  });

  it('counts the modems, none included', () => {
    expect(valueOf(sentRows(summary({ modems: [] })), 'Modems')).toBe('0');
    expect(valueOf(sentRows(summary({ modems: [100] })), 'Modems')).toBe('1');
  });

  it('says whether the laptop is in the log', () => {
    expect(valueOf(sentRows(summary({ hasDevice: false })), 'Laptop')).toBe('Not in this log');
  });

  it('names the class, or says it was not answered', () => {
    expect(valueOf(sentRows(summary({ travelClass: 'first' })), 'Class')).toBe('First');
    expect(valueOf(sentRows(summary({ travelClass: null })), 'Class')).toBe('Not answered');
  });

  it('lists what was left out with its counts, in the given order', () => {
    const leftOut = [
      { field: 'wifiRate', count: 2 },
      { field: 'speedMps', count: 1 },
    ];
    expect(valueOf(sentRows(summary({ leftOut })), 'Left out')).toBe('wifiRate (2), speedMps (1)');
  });

  it('shows the lines not read only when there are some', () => {
    expect(sentRows(summary({ unreadableLines: 0 })).map((r) => r.term)).not.toContain(
      'Lines not read',
    );
    expect(valueOf(sentRows(summary({ unreadableLines: 3 })), 'Lines not read')).toBe('3');
  });
});

describe('problemText', () => {
  it.each([
    ['empty', 'That file has no logger lines in it.'],
    ['noStart', 'That file has no start line, so it isn’t a train feed log.'],
    ['unknownVersion', 'That log is from a logger version this page doesn’t know.'],
    ['noRounds', 'That log has no rounds in it.'],
    ['roundsNotRising', 'That log’s rounds are out of order.'],
    ['tooManyRounds', 'That log has more than 2,000 rounds. One import takes 2,000 at most.'],
    ['tooBig', 'That log is too big to send.'],
  ])('says %s in one sentence', (problem, text) => {
    expect(problemText(problem)).toBe(text);
  });
});

describe('importFailure', () => {
  it('leaves a 401 to the sign-in card, with no text', () => {
    expect(importFailure(new ApiError(401, 'no'))).toEqual({ kind: 'signin', text: null });
  });

  it('says a 400 was refused and nothing stored', () => {
    expect(importFailure(new ApiError(400, 'bad', { error: 'Invalid request' }))).toEqual({
      kind: 'alert',
      text: 'The server refused this log. Nothing was stored.',
    });
  });

  it('says a 409 "Already imported" is already imported', () => {
    expect(importFailure(new ApiError(409, 'x', { error: 'Already imported' }))).toEqual({
      kind: 'alert',
      text: 'This log is already imported.',
    });
  });

  it('says any other 409 is another import running', () => {
    expect(importFailure(new ApiError(409, 'x', { error: 'Busy' }))).toEqual({
      kind: 'alert',
      text: 'Another import was running. Try again.',
    });
    expect(importFailure(new ApiError(409, 'x'))).toEqual({
      kind: 'alert',
      text: 'Another import was running. Try again.',
    });
  });

  it('says a 500 and a lost connection could not import', () => {
    const text = 'Couldn’t import the log. Try again.';
    expect(importFailure(new ApiError(500, 'x'))).toEqual({ kind: 'alert', text });
    expect(importFailure(new TypeError('Failed to fetch'))).toEqual({ kind: 'alert', text });
  });
});

describe('importToast', () => {
  it('sums one journey', () => {
    expect(importToast({ journeys: [{ samples: 363, linked: 2 }] })).toBe(
      'Imported 1 journey: 363 rounds, 2 viewings linked',
    );
  });

  it('sums the counts over two journeys', () => {
    expect(
      importToast({
        journeys: [
          { samples: 100, linked: 1 },
          { samples: 50, linked: 2 },
        ],
      }),
    ).toBe('Imported 2 journeys: 150 rounds, 3 viewings linked');
  });

  it('says 1 round and 1 viewing linked for one each', () => {
    expect(importToast({ journeys: [{ samples: 1, linked: 1 }] })).toBe(
      'Imported 1 journey: 1 round, 1 viewing linked',
    );
  });

  it('says no viewings linked for none', () => {
    expect(importToast({ journeys: [{ samples: 5, linked: 0 }] })).toBe(
      'Imported 1 journey: 5 rounds, no viewings linked',
    );
  });
});

const stored = (over = {}) => ({
  id: 12,
  runStartedMs: 1898891940000,
  segment: 0,
  trainUnit: 'Unit-001',
  travelClass: 'standard',
  osVersion: '15.1',
  loggerVersion: 3,
  sourceFile: 'train-feed-example.jsonl',
  importedAt: '2030-03-05T09:00:00Z',
  device: { model: 'ExampleBook1,1', cpu: 'Example chip', cores: 8, threads: 8, memoryBytes: 1 },
  samples: 363,
  from: '2030-03-04T22:00:00Z',
  to: '2030-03-05T00:47:30Z',
  linked: 2,
  train: { uid: 'X12345', date: '2030-03-04', headcode: '9Z99' },
  ...over,
});
const linesOf = (journey, others = []) => journeyLines(journey, [journey, ...others]);

describe('journeyLines', () => {
  it('writes every line of a journey that has everything', () => {
    expect(linesOf(stored())).toEqual({
      title: '4 Mar 2030, 22:00 to 5 Mar 2030, 00:47',
      second: 'Unit-001 · Standard · 363 rounds · 2 viewings',
      third: 'Train 9Z99 on 4 Mar 2030',
      fourth: 'train-feed-example.jsonl · ExampleBook1,1',
    });
  });

  it('says No samples for a journey with none, and still counts its rounds', () => {
    const lines = linesOf(stored({ from: null, to: null, samples: 0, linked: 0 }));
    expect(lines.title).toBe('No samples');
    expect(lines.second).toBe('Unit-001 · Standard · 0 rounds · no viewings');
  });

  it('says Unit not named when there is no unit', () => {
    expect(linesOf(stored({ trainUnit: null })).second).toBe(
      'Unit not named · Standard · 363 rounds · 2 viewings',
    );
  });

  it('leaves the class out when there is none and writes First', () => {
    expect(linesOf(stored({ travelClass: null })).second).toBe(
      'Unit-001 · 363 rounds · 2 viewings',
    );
    expect(linesOf(stored({ travelClass: 'first' })).second).toBe(
      'Unit-001 · First · 363 rounds · 2 viewings',
    );
  });

  it('says 1 round and 1 viewing, and no viewings', () => {
    expect(linesOf(stored({ samples: 1, linked: 1 })).second).toBe(
      'Unit-001 · Standard · 1 round · 1 viewing',
    );
    expect(linesOf(stored({ linked: 0 })).second).toBe(
      'Unit-001 · Standard · 363 rounds · no viewings',
    );
  });

  it('numbers the segments of one run from part 1, and leaves the part out of a journey alone', () => {
    const first = stored({ id: 12, segment: 0 });
    const second = stored({ id: 13, segment: 1 });
    expect(linesOf(first, [second]).second).toBe(
      'Unit-001 · part 1 · Standard · 363 rounds · 2 viewings',
    );
    expect(linesOf(second, [first]).second).toBe(
      'Unit-001 · part 2 · Standard · 363 rounds · 2 viewings',
    );
    expect(linesOf(first, [stored({ id: 14, runStartedMs: 1898000000000 })]).second).toBe(
      'Unit-001 · Standard · 363 rounds · 2 viewings',
    );
  });

  it('says No train yet when it has none', () => {
    expect(linesOf(stored({ train: null })).third).toBe('No train yet');
  });

  it('writes a summer running date as it is given', () => {
    const train = { uid: 'X12345', date: '2030-07-15', headcode: '9Z99' };
    expect(linesOf(stored({ train })).third).toBe('Train 9Z99 on 15 Jul 2030');
  });

  it('writes the fourth line from the file and the model, from either, or leaves it null', () => {
    expect(linesOf(stored({ device: null })).fourth).toBe('train-feed-example.jsonl');
    expect(linesOf(stored({ sourceFile: null })).fourth).toBe('ExampleBook1,1');
    expect(linesOf(stored({ sourceFile: null, device: null })).fourth).toBeNull();
  });
});
