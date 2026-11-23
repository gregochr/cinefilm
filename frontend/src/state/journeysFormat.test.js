import { describe, expect, it } from 'vitest';
import { ApiError } from '../api/client.js';
import {
  importFailure,
  importToast,
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
