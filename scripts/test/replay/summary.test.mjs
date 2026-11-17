// scripts/replay/summary.mjs: the lines of summary.txt as a pure function of the results a run keeps. No files.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { stamp, summarise } from '../../replay/summary.mjs';

// The summary writes local time and UTC: this file runs in a zone that is not UTC, so that a summary which wrote one of
// them twice shows. Nothing above this line makes a date.
process.env.TZ = 'America/New_York';

// 17 November 2026, 14:05:09 where the test runs (the summary writes local time and UTC).
const AT = new Date(2026, 10, 17, 14, 5, 9).getTime();
const local = '2026-11-17 14:05:09';
const utc = new Date(AT).toISOString().slice(0, 19).replace('T', ' ');

const RECORD = {
  seq: 9, watchedMs: 3600000, stallCount: 3, stallMs: 45000, longestStallMs: 20000, startupMs: 1200, stepsDown: 4,
  stepsUp: 2, reconnectCount: 1, netErrors: 5, timeouts: 2, fellBack: false, engine: 'hls.js', environment: 'train',
  operator: 'EE', rungMs: { '720p': 1000000, '360p': 2000000 },
};

// A whole run's results as `viewing.mjs` keeps them.
function results(changes = {}) {
  return {
    samples: [
      { at: AT, sinceMs: 5000, t: 1.2, direct: false },
      { at: AT + 40000, sinceMs: 45000, t: 6.2, direct: true },
      { at: AT + 45000, sinceMs: 50000, t: 46, direct: true },
    ],
    health: {
      sent: 7, notRead: 1,
      viewings: [{ id: 'a', count: 1, latest: { seq: 1, watchedMs: 5000 } }, { id: 'b', count: 6, latest: RECORD }],
    },
    held: {
      run: { film: 42, title: 'Heat', site: 'http://127.0.0.1:5301', beganAt: AT, runMs: 50000, ended: 'minutes' },
      held: [{ method: 'POST', shape: '/api/editions/N/watched', count: 2 }],
      heldDropped: 0, failed: 0, rejected: 1, presses: { place: 'On a train', retries: 2 },
      pageErrors: ['first error'], warnings: ['the machine stood still'],
    },
    ...changes,
  };
}

describe('summarise', () => {
  test('a whole run reads as the film, the record, the samples, the presses, what was held, the errors', () => {
    assert.deepEqual(summarise(results()), [
      'Film 42 Heat',
      'Site: http://127.0.0.1:5301',
      `Began ${local} (${utc} UTC), played 50 s from the film's opening, ended: minutes`,
      'Playback records: 7 sent, 1 could not be read, 2 viewings',
      'Latest record of the last viewing (6 sent):',
      '  time watched 3600 s; stalls 3, time stalled 45 s, longest 20 s; start-up 1200 ms',
      '  steps down 4, steps up 2; reconnects 1; network errors 5, timeouts 2; fell back to direct play: no',
      '  engine hls.js; environment train; operator EE',
      '  time on each rung: 720p 1000 s, 360p 2000 s',
      'Samples: 3, longest gap 40 s, 2 showed direct play',
      'Playhead: 1.2 s at +5 s, then 46 s at +50 s',
      'Presses: place card On a train, "Try again" 2 times',
      'Held back:',
      '  POST /api/editions/N/watched: 2',
      'Guard: failed 0, rejected sends 1, entries dropped 0',
      'Page errors: 1',
      '  first error',
      'WARNING: the machine stood still',
    ]);
  });

  test('the time is local and UTC, and a time that is not one is unknown', () => {
    assert.notEqual(local, utc, 'the test runs in a zone that is not UTC');
    assert.equal(stamp(AT), `${local} (${utc} UTC)`);
    assert.match(stamp(AT), /^2026-11-17 14:05:09 \(2026-11-17 19:05:09 UTC\)$/);
    for (const bad of [undefined, null, 'x', Number.NaN, Infinity, 8.64e15 + 1]) assert.equal(stamp(bad), 'unknown');
  });

  test('a run with no record says so and leaves out the figures', () => {
    const lines = summarise(results({ health: { sent: 0, notRead: 0, viewings: [] } }));
    assert.ok(lines.includes('Playback records: 0 sent, 0 could not be read, 0 viewings'));
    assert.ok(lines.includes('No playback record could be read: there are no figures of one.'));
    assert.equal(lines.some((line) => line.includes('time watched') || line.includes('Latest record')), false);
  });

  test('with two viewings the figures are the last one\'s, not the first\'s', () => {
    const lines = summarise(results()).join('\n');
    assert.match(lines, /Latest record of the last viewing \(6 sent\)/);
    assert.equal(lines.includes('time watched 5 s'), false);
  });

  test('a record with fields missing or of the wrong type gives only the fields that are right', () => {
    const wrong = {
      watchedMs: '3600000', stallCount: Number.NaN, stallMs: null, longestStallMs: {}, startupMs: [5], stepsDown: 4,
      stepsUp: true, reconnectCount: undefined, netErrors: 'x', timeouts: 2, fellBack: 'no', engine: 7,
      environment: ['train'], operator: '', rungMs: { '720p': 'many', '360p': 2000000, junk: null },
    };
    const viewings = [{ id: 'a', count: 1, latest: wrong }];
    const lines = summarise(results({ health: { sent: 1, notRead: 0, viewings } }));
    const text = lines.join('\n');
    assert.match(text, /^  steps down 4; timeouts 2$/m);
    assert.match(text, /^  time on each rung: 360p 2000 s$/m);
    for (const gone of ['time watched', 'stalls', 'start-up', 'steps up', 'reconnects', 'network errors', 'fell back',
      'engine', 'environment', 'operator', 'null', 'undefined', 'NaN']) {
      assert.equal(text.includes(gone), false, gone);
    }
  });

  test('a record that is not an object, and a viewing with no count, are not figures', () => {
    for (const latest of [null, 'text', 5, [1], undefined]) {
      const lines = summarise(results({ health: { sent: 1, notRead: 0, viewings: [{ id: 'a', latest }] } }));
      assert.ok(lines.includes('Latest record of the last viewing (? sent):'), String(latest));
      const figures = lines.filter((line) => line.startsWith('  ') && !/POST|error/.test(line));
      assert.deepEqual(figures, []);
    }
  });

  test('every line is one printable line of at most 300 characters, whatever the results hold', () => {
    const nasty = `a\nb\u001b[31mé${'x'.repeat(400)}`;
    const dirty = results();
    dirty.held.run = { film: 7, title: nasty, site: nasty, beganAt: AT, runMs: 1000, ended: nasty };
    dirty.held.held = [{ method: nasty, shape: nasty, count: 1 }];
    dirty.held.pageErrors = [nasty, `${nasty}\r\nforged line`];
    dirty.held.warnings = [nasty];
    dirty.held.presses = { place: nasty, retries: 1 };
    const latest = { ...RECORD, engine: nasty, operator: nasty, environment: nasty, rungMs: { [nasty]: 1000 } };
    dirty.health.viewings = [{ id: 'a', count: 1, latest }];
    const lines = summarise(dirty);
    assert.ok(lines.length > 15);
    for (const line of lines) assert.match(line, /^[\x20-\x7e]{0,300}$/, line.slice(0, 60));
  });

  test('samples: how many, the longest gap, how many showed direct play, the playhead first and last', () => {
    const none = summarise(results({ samples: [] }));
    assert.ok(none.includes('Samples: none'));
    assert.equal(none.some((line) => line.startsWith('Playhead')), false);
    const one = summarise(results({ samples: [{ at: AT, sinceMs: 5000, t: 3, direct: true }] }));
    assert.ok(one.includes('Samples: 1, 1 showed direct play'));
    assert.ok(one.includes('Playhead: 3 s at +5 s, then 3 s at +5 s'));
    const odd = [{ at: AT, direct: 'yes' }, { at: AT + 5000 }, { at: AT + 100000 }, 'x', null];
    const gaps = summarise(results({ samples: odd }));
    assert.ok(gaps.includes('Samples: 3, longest gap 95 s, 0 showed direct play'));
    assert.equal(gaps.some((line) => line.startsWith('Playhead')), false);
  });

  test('the longest gap is the longest of all of them, not the last one', () => {
    const odd = [AT, AT + 5000, AT + 95000, AT + 100000].map((at) => ({ at }));
    assert.ok(summarise(results({ samples: odd })).includes('Samples: 4, longest gap 90 s, 0 showed direct play'));
  });

  test('a summary names a viewing and a time in the singular when there is one', () => {
    const viewings = [{ id: 'a', count: 1, latest: {} }];
    const one = summarise(results({ health: { sent: 1, notRead: 0, viewings } }));
    assert.ok(one.includes('Playback records: 1 sent, 0 could not be read, 1 viewing'));
    const held = { ...results().held, presses: { place: null, retries: 1 } };
    assert.ok(summarise(results({ held })).includes('Presses: place card not pressed, "Try again" 1 time'));
  });

  test('the caps: a dozen rungs, a title of 120 characters, and page errors that are text only', () => {
    const rungMs = Object.fromEntries(Array.from({ length: 15 }, (_, n) => [`r${n}`, 1000]));
    const viewings = [{ id: 'a', count: 1, latest: { rungMs } }];
    const record = summarise(results({ health: { sent: 1, notRead: 0, viewings } }));
    const rungs = record.find((line) => line.includes('time on each rung'));
    assert.equal(rungs.split(', ').length, 12);
    assert.equal(rungs.includes('r12'), false);
    const run = { ...results().held.run, title: 'T'.repeat(200) };
    assert.equal(summarise(results({ held: { ...results().held, run } }))[0], `Film 42 ${'T'.repeat(120)}`);
    const held = { ...results().held, pageErrors: [{ message: 'x' }, 5, null, 'only this one', ['a']] };
    const lines = summarise(results({ held }));
    assert.ok(lines.includes('Page errors: 1'));
    assert.ok(lines.includes('  only this one'));
    assert.equal(lines.some((line) => line.includes('[object Object]')), false);
  });

  test('what was held: each kind with its count, a long list cut, the guard\'s counts', () => {
    const many = Array.from({ length: 25 }, (_, n) => ({ method: 'PATCH', shape: `/p${n}`, count: n + 1 }));
    const held = { ...results().held, held: many, heldDropped: 4, failed: 2, rejected: 3 };
    const lines = summarise(results({ held }));
    assert.equal(lines.filter((line) => line.startsWith('  PATCH')).length, 20);
    assert.ok(lines.includes('  and 5 more kinds'));
    assert.ok(lines.includes('Guard: failed 2, rejected sends 3, entries dropped 4'));
    const quiet = summarise(results({ held: { ...results().held, held: [] } }));
    assert.ok(quiet.includes('Held back: nothing'));
  });

  test('only the first ten page errors are listed, with their count; every warning is', () => {
    const errors = Array.from({ length: 12 }, (_, n) => `error ${n}`);
    const lines = summarise(results({ held: { ...results().held, pageErrors: errors, warnings: ['one', 'two'] } }));
    assert.ok(lines.includes('Page errors: 12'));
    assert.equal(lines.filter((line) => /^ {2}error \d+$/.test(line)).length, 10);
    assert.equal(lines.includes('  error 10'), false);
    assert.deepEqual(lines.filter((line) => line.startsWith('WARNING')), ['WARNING: one', 'WARNING: two']);
  });

  test('the presses say what was pressed, or that the card was not', () => {
    const held = (presses) => summarise(results({ held: { ...results().held, presses } }));
    assert.ok(held({ place: null, retries: 0 }).includes('Presses: place card not pressed, "Try again" 0 times'));
    assert.ok(held(undefined).includes('Presses: place card not pressed, "Try again" 0 times'));
    assert.ok(held({ place: 'Not now', retries: 'x' }).includes('Presses: place card Not now, "Try again" 0 times'));
  });

  test('results that are missing or not objects give a summary that does not throw and guesses nothing', () => {
    for (const input of [undefined, null, 'text', 5, [], {}, { samples: 5, health: 'x', held: [] }]) {
      const lines = summarise(input);
      assert.ok(lines.includes('Film unknown'), JSON.stringify(input));
      assert.ok(lines.includes('Site: unknown'));
      assert.ok(lines.includes('Samples: none'));
      assert.ok(lines.includes('Held back: nothing'));
      assert.equal(lines.some((line) => /null|undefined|NaN/.test(line)), false);
    }
  });
});
