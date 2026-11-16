// scripts/replay/analyse.mjs: each sum over a run's data at its edges, and the lines they are told in, all from
// made-up figures; and `analyseRun` over small runs written to a temporary folder (no test reads what is in
// scripts/replay/out).
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { after, describe, test } from 'node:test';
import {
  aheadAt, analyseRun, deliveryAgainstTrace, describe as describeRun, idleGaps, linkByMinute, notPlaying,
  playheadLag, RUNS_DIR, rungsAndBytes, runDir, samplingGaps, screenAgainstLink, soundness, spellFigures, startOf,
  withFilmInHand,
} from '../../replay/analyse.mjs';
import { loadRecord } from '../../replay/trace.mjs';

// A sample taken `ms` into the replay.
const at = (ms, more = {}) => ({ ms, t: 0, ahead: 0, kbps: 1000, readyState: 4, paused: false, buffering: null, ...more });

describe('startOf', () => {
  test('is the record minute the summary\'s Replay line starts at', () => {
    assert.equal(startOf('Replay of record minutes 24 to 38 (18:20:53 to 18:35:53), sampled trace'), 24);
  });

  test('is found after warning lines, and is null when there is no such line', () => {
    assert.equal(startOf('WARNING: stopped early\nReplay of record minutes 7 to 9 (a)\nrest'), 7);
    assert.equal(startOf('Replay of record minutes 0 to 9 (a)'), 0);
    assert.equal(startOf('nothing of the kind'), null);
    assert.equal(startOf(''), null);
  });
});

describe('linkByMinute and aheadAt', () => {
  test('a sample belongs to the minute it was taken in: 59,999 ms is minute 0, 60,000 ms is minute 1', () => {
    const link = linkByMinute([at(59999, { kbps: 500 }), at(60000, { kbps: 900 })]);
    assert.deepEqual([...link], [[0, 500], [1, 900]]);
  });

  test('the last sample of a minute gives its figure', () => {
    const link = linkByMinute([at(1000, { kbps: 500 }), at(2000, { kbps: 600 })]);
    assert.equal(link.get(0), 600);
  });

  test('aheadAt is the film in hand at the nearest sample, rounded; at a tie, the earlier one', () => {
    const samples = [at(0, { ahead: 10.4 }), at(10000, { ahead: 20.6 })];
    assert.equal(aheadAt(samples, 4999), 10);
    assert.equal(aheadAt(samples, 5001), 21);
    assert.equal(aheadAt(samples, 5000), 10);
    assert.equal(aheadAt(samples, 999999), 21);
  });
});

describe('section 1: was the run sound', () => {
  test('a gap of 6,500 ms between samples is not long, 6,501 ms is; the longest is reported', () => {
    const gaps = samplingGaps([at(0), at(6500), at(13001), at(13002)]);
    assert.deepEqual(gaps, { samples: 4, longest: 6501, long: 1 });
    assert.deepEqual(samplingGaps([at(0), at(5000), at(11500)]), { samples: 3, longest: 6500, long: 0 });
    assert.deepEqual(samplingGaps([at(0)]), { samples: 1, longest: 0, long: 0 });
  });

  test('a sample is "not playing" after the first 20 s when it is buffering, not ready (under 3) or paused', () => {
    const buffering = at(30000, { buffering: 'Buffering' });
    const notReady = at(40000, { readyState: 2 });
    const paused = at(50000, { paused: true });
    const fine = at(60000);
    const noVideo = at(70000, { readyState: undefined });
    assert.deepEqual(notPlaying([buffering, notReady, paused, fine, noVideo]), [buffering, notReady, paused]);
  });

  test('exactly 20 s is still start-up, 20,001 ms is not', () => {
    assert.deepEqual(notPlaying([at(20000, { paused: true })]), []);
    assert.equal(notPlaying([at(20001, { paused: true })]).length, 1);
  });

  test('a pause with 5 s in hand is not a stall for the wrong reason, with 5.1 s it is', () => {
    assert.equal(withFilmInHand([at(1, { ahead: 5 })]), 0);
    assert.equal(withFilmInHand([at(1, { ahead: 5.1 }), at(2, { ahead: 50 }), at(3, { ahead: 0 })]), 2);
  });

  test('the playhead 0.5 s behind the clock in a step is on time, 0.6 s behind is not', () => {
    // Five samples five seconds apart that kept the clock, except that the last one's playhead is `lastT`.
    const run = (lastT) => [0, 5, 10, 15, 20].map((s, i) => at(s * 1000, { t: i === 4 ? lastT : s }));
    assert.equal(playheadLag(run(19.5)).behind, 0);
    assert.ok(Math.abs(playheadLag(run(19.4)).behind - 0.6) < 1e-9);
  });

  test('only the steps from the fourth sample on count, and the third sample to the last is the span', () => {
    const samples = [
      at(0, { t: 0 }), at(5000, { t: 1 }), at(10000, { t: 2 }), at(15000, { t: 7 }), at(20000, { t: 12 }),
    ];
    // The steps into samples 1 and 2 fell 4 s and 4 s behind; into 3 and 4 they kept the clock.
    assert.deepEqual(playheadLag(samples), { behind: 0, behindWithFilm: 0, moved: 10, wall: 10 });
  });

  test('a lag with over 10 s in hand at both ends of the step is counted separately; 10 s exactly is not', () => {
    const base = [at(0), at(5000), at(10000, { t: 10 })];
    const lagging = (before, after) => [
      ...base, at(15000, { t: 11, ahead: before }), at(20000, { t: 12, ahead: after }),
    ];
    // Each of the two steps (into the fourth sample and into the fifth) is 5 s of clock and 1 s of film: 4 s behind.
    // Only the second has film in hand at both ends, because the third sample has none.
    const withFilm = playheadLag(lagging(10.1, 10.1));
    assert.equal(withFilm.behind, 8);
    assert.equal(withFilm.behindWithFilm, 4);
    assert.equal(playheadLag(lagging(10, 10.1)).behindWithFilm, 0);
    assert.equal(playheadLag(lagging(10.1, 10)).behindWithFilm, 0);
  });

  test('fewer than three samples give nothing', () => {
    assert.deepEqual(playheadLag([at(0), at(5000)]), { behind: 0, behindWithFilm: 0, moved: 0, wall: 0 });
  });

  test('a minute is "well above the trace" above 5% and 60 kbit/s over it: 8,325,000 bytes is level, one more is over', () => {
    const link = new Map([[0, 1000], [1, 1000]]);
    const result = deliveryAgainstTrace([8325000, 8325001, 0], link, 0, 2);
    assert.equal(result.over, 1);
    assert.equal(result.delivered, 16650001);
    assert.equal(result.allowed, 15000000);
  });

  test('a thin minute is under 700 kbit/s, named by its record minute with the trace and what arrived', () => {
    const link = new Map([[0, 699], [1, 700], [2, 0]]);
    // 5,240,250 bytes in a minute is 698.7 kbit/s, which is 699 when rounded; 5,250,000 is 700.
    const result = deliveryAgainstTrace([5240250, 5250000, 0, 0], link, 24, 3);
    assert.deepEqual(result.thin, [{ minute: 24, rate: 699, got: 699 }, { minute: 26, rate: 0, got: 0 }]);
  });

  test('a minute the list of counts has no place for counts as 0 bytes', () => {
    const link = new Map([[0, 1000], [1, 1000]]);
    const result = deliveryAgainstTrace([5000], link, 0, 2);
    assert.equal(result.delivered, 5000);
    assert.equal(result.over, 0);
    assert.equal(deliveryAgainstTrace([], link, 0, 2).delivered, 0);
  });

  test('a minute with no sample has a trace of 0', () => {
    const result = deliveryAgainstTrace([1000000, 0], new Map(), 0, 1);
    assert.equal(result.over, 1);
    assert.deepEqual(result.thin, [{ minute: 0, rate: 0, got: 133 }]);
  });

  test('soundness puts the parts together over a run of two minutes', () => {
    const samples = [
      at(5000, { t: 5, kbps: 1000 }), at(10000, { t: 10 }), at(15000, { t: 15 }), at(70000, { t: 70, kbps: 800 }),
    ];
    // What the stand-in counted: 60,000 bytes in minute 0 and no place for minute 1, which counts as 0.
    const result = soundness({ samples, delivered: [60000, 0], start: 0 });
    assert.deepEqual(result.gaps, { samples: 4, longest: 55000, long: 1 });
    assert.deepEqual(result.notPlaying, []);
    assert.equal(result.withFilm, 0);
    assert.deepEqual(result.lag, { behind: 0, behindWithFilm: 0, moved: 55, wall: 55 });
    assert.deepEqual(result.delivery, { delivered: 60000, allowed: 7500000, over: 0, thin: [] });
  });

  test('delivery is compared over the minutes the run completed: 239,999 ms is three of them, 240,000 ms is four', () => {
    // Every minute is thin (100 kbit/s), so the thin list names each minute compared.
    const upTo = (ms) => [at(5000, { kbps: 100 }), at(ms, { kbps: 100 })];
    const delivered = [1, 2, 3, 4, 999]; // the fifth place is what was sent after the end
    const three = soundness({ samples: upTo(239999), delivered, start: 33 }).delivery;
    assert.deepEqual(three.thin.map((t) => t.minute), [33, 34, 35]);
    assert.equal(three.delivered, 6);
    const four = soundness({ samples: upTo(240000), delivered, start: 33 }).delivery;
    assert.deepEqual(four.thin.map((t) => t.minute), [33, 34, 35, 36]);
    assert.equal(four.delivered, 10);
  });
});

describe('section 2: gaps with nothing fetched', () => {
  const seg = (ms, doneMs, rung = '480p') => ({ kind: 'segment', ms, doneMs, rung });
  const samples = [at(0, { ahead: 10 }), at(61000, { ahead: 200 }), at(125000, { ahead: 30 })];
  const link = new Map([[0, 1000], [1, 2000], [2, 3000]]);

  test('a gap of exactly 30 s is not one, 30,001 ms is', () => {
    assert.deepEqual(idleGaps([seg(0, 1000), seg(31000, 500)], samples, link), []);
    assert.equal(idleGaps([seg(0, 1000), seg(31001, 500)], samples, link).length, 1);
  });

  test('a gap says its length, minutes, the rungs either side, the film in hand then and the link in each minute', () => {
    const gaps = idleGaps([seg(0, 1000, '480p'), seg(61500, 500, '240p')], samples, link);
    assert.deepEqual(gaps, [{
      gap: 60500, first: 0, last: 1, before: '480p', after: '240p', aheadBefore: 10, aheadAfter: 200, rates: [1000, 2000],
    }]);
  });

  test('segments are taken in the order they were asked for, and the longest-running one hides what is under it', () => {
    const requests = [seg(61501, 100, 'c'), seg(0, 100000, 'a'), seg(20000, 100, 'b')];
    // a runs to 100,000 ms, so b and c start inside it; the next gap is after c, which ends at 61,601 ms: nothing.
    assert.deepEqual(idleGaps(requests, samples, link), []);
    const late = idleGaps([...requests, seg(131000, 10, 'd')], samples, link);
    assert.equal(late.length, 1);
    assert.equal(late[0].gap, 31000);
    assert.equal(late[0].after, 'd');
  });

  test('a segment with neither an end nor a cut time is read as ending where it began', () => {
    const gaps = idleGaps([seg(0, undefined), seg(30001, 1)], samples, link);
    assert.equal(gaps[0].gap, 30001);
    // One fetched for 40 s with no cut time recorded, asked for again at 41 s: the gap is all of those 41 s.
    assert.equal(idleGaps([seg(0, undefined), seg(41000, 1)], samples, link)[0].gap, 41000);
  });

  test('a segment the player cut ends when it was cut: one fetched for 40 s and asked again at 41 s is no gap', () => {
    const cut = { kind: 'segment', ms: 0, cutMs: 40000, rung: '480p' };
    assert.deepEqual(idleGaps([cut, seg(41000, 500)], samples, link), []);
  });

  test('the end of a cut segment counts to the millisecond: a gap of 30,000 ms after it is none, 30,001 is one', () => {
    const cut = { kind: 'segment', ms: 10000, cutMs: 40000, rung: '480p' }; // cut at 50,000 ms on the replay clock
    assert.deepEqual(idleGaps([cut, seg(80000, 500)], samples, link), []);
    assert.equal(idleGaps([cut, seg(80001, 500)], samples, link)[0].gap, 30001);
  });

  test('a cut time that is not a time is ignored: the segment is read as ending where it began', () => {
    const finished = (ms) => ({ kind: 'segment', ms, doneMs: 1000, rung: '480p' });
    const cutAt = (cutMs) => ({ kind: 'segment', ms: 20000, aborted: true, doneMs: null, cutMs, rung: '480p' });
    // Damaged (-15,000): the second ends at its start, 20,000 ms, and the third starts 25 s later: no gap. Taken at
    // its face it would end at 5,000 ms and a gap of 40 s would be made up.
    assert.deepEqual(idleGaps([finished(0), cutAt(-15000), finished(45000)], samples, link), []);
    // A real cut time: it ends at 35,000 ms and the third starts 10 s later.
    assert.deepEqual(idleGaps([finished(0), cutAt(15000), finished(45000)], samples, link), []);
    // No cut time at all: read as ending where it began.
    assert.deepEqual(idleGaps([finished(0), cutAt(undefined), finished(45000)], samples, link), []);
  });

  test('a segment that finished ends at its end, whatever a cut time says', () => {
    const odd = { kind: 'segment', ms: 0, doneMs: 1000, cutMs: 90000, rung: '480p' };
    assert.equal(idleGaps([odd, seg(41000, 500)], samples, link)[0].gap, 40000);
  });

  test('a segment with no time among timed ones is left out: it neither hides a gap nor makes one', () => {
    // Taken at its face it would run from 0 to 100,000 ms and hide the gap after the first segment.
    const untimed = { kind: 'segment', ms: null, doneMs: 100000, rung: 'x' };
    const gaps = idleGaps([seg(0, 1000), untimed, seg(31001, 500)], samples, link);
    assert.equal(gaps.length, 1);
    assert.equal(gaps[0].gap, 30001);
  });

  test('requests that are not segments, or that have no time, are not looked at; none at all is no gap', () => {
    assert.deepEqual(idleGaps([{ kind: 'api', ms: 0 }, { kind: 'segment', ms: null }], samples, link), []);
    assert.deepEqual(idleGaps([], samples, link), []);
  });
});

describe('section 3: the six bad spells', () => {
  // Record minutes 5 to 7 are the first spell. 12 November's stalled ms in them: 1000, 2000, 4000.
  const record = { report: { minutes: [[5, 0, 5, 0, 1000], [6, 0, 5, 0, 2000], [7, 0, 5, 0, 4000]] } };
  // A replay row is [replay minute, kbit/s, rung played, moved, stalled ms, seconds ahead, rung fetched].
  const rows = (start) => [
    [4 - start, 0, 4, 0, 0, 90, 4], [5 - start, 0, 5, 0, 500, 60, 5], [6 - start, 0, 5, 0, 700, 20, 6],
    [7 - start, 0, 5, 0, 0, 40, 5],
  ];
  // Samples every 30 s from 1 s in to `minutes` whole minutes (and 1 s) in: that many minutes completed.
  const samplesTo = (minutes) => Array.from({ length: minutes * 2 + 1 }, (_, i) => (
    at(i * 30000 + 1000, { kbps: 100 * (Math.floor(i / 2) + 1) })
  ));

  test('a spell is described by its mean link, the bank going in, the lowest figure, the stalls now and then', () => {
    const report = { minutes: rows(0) };
    const [spell] = spellFigures({ report, samples: samplesTo(8), record, start: 0 });
    assert.deepEqual(spell, {
      first: 5, last: 7, meanLink: 700, goingIn: 90, lowest: 20, stalled: 1200, stalledThen: 7000,
      fetched: ['360p', '240p'],
    });
  });

  test('a spell needs the minute before it: it is left out when the run began at its first minute, in when one earlier', () => {
    assert.deepEqual(spellFigures({ report: { minutes: rows(5) }, samples: samplesTo(4), record, start: 5 }), []);
    const [spell] = spellFigures({ report: { minutes: rows(4) }, samples: samplesTo(5), record, start: 4 });
    assert.equal(spell.goingIn, 90);
    assert.equal(spell.first, 5);
  });

  test('a record minute with no row counts for 0 stalled seconds on 12 November, not for nothing at all', () => {
    const partial = { report: { minutes: [[5, 0, 5, 0, 1000], [6, 0, 5, 0, 2000]] } };
    const [spell] = spellFigures({ report: { minutes: rows(0) }, samples: samplesTo(8), record: partial, start: 0 });
    assert.equal(spell.stalledThen, 3000);
  });

  test('a minute the samples give no link figure for counts for 0 kbit/s in the mean', () => {
    // Samples in minutes 4, 6 and 7 only: minute 5 has none.
    const samples = [
      at(4 * 60000 + 1000, { kbps: 100 }), at(6 * 60000 + 1000, { kbps: 700 }), at(7 * 60000 + 1000, { kbps: 800 }),
      at(8 * 60000 + 1000, { kbps: 900 }),
    ];
    const [spell] = spellFigures({ report: { minutes: rows(0) }, samples, record, start: 0 });
    assert.equal(spell.meanLink, 500);
  });

  test('a spell is compared only when the run covered every minute of it: 479,999 ms is short of minutes 5 to 7', () => {
    const report = { minutes: rows(0) };
    const upTo = (ms) => [at(1000), at(ms)];
    assert.deepEqual(spellFigures({ report, samples: upTo(479999), record, start: 0 }), []);
    assert.equal(spellFigures({ report, samples: upTo(480000), record, start: 0 }).length, 1);
  });

  test('the same edge for a run that began at another record minute: minutes 5 to 7 from 4 need 240,000 ms', () => {
    const report = { minutes: rows(4) };
    const upTo = (ms) => [at(1000), at(ms)];
    assert.deepEqual(spellFigures({ report, samples: upTo(239999), record, start: 4 }), []);
    assert.equal(spellFigures({ report, samples: upTo(240000), record, start: 4 }).length, 1);
  });

  test('a spell is left out when the run ended before its last minute was over', () => {
    assert.deepEqual(spellFigures({ report: { minutes: rows(0) }, samples: samplesTo(7), record, start: 0 }), []);
    assert.equal(spellFigures({ report: { minutes: rows(0) }, samples: samplesTo(8), record, start: 0 }).length, 1);
  });

  test('a minute with no row is left out of the figures, and a spell with no rows at all has no lowest', () => {
    const report = { minutes: [[4, 0, 4, 0, 0, 90, 4], [5, 0, 5, 0, 500, 60, 5], [7, 0, 5, 0, 0, 40, 5]] };
    const [spell] = spellFigures({ report, samples: samplesTo(8), record, start: 0 });
    assert.equal(spell.stalled, 500);
    assert.equal(spell.stalledThen, 5000);
    const [empty] = spellFigures({ report: { minutes: [[4, 0, 4, 0, 0, 90, 4]] }, samples: samplesTo(8), record, start: 0 });
    assert.equal(empty.lowest, Infinity);
    assert.equal(empty.stalled, 0);
  });
});

describe('section 4: screen, rungs and bytes', () => {
  test('240p on screen counts five seconds a sample, and the link at 2,000 kbit/s is good, 1,999 is not', () => {
    const screen = screenAgainstLink([
      at(0, { pill: 'Auto - 240p', kbps: 1999 }), at(5000, { pill: 'Auto - 240p', kbps: 2000 }),
      at(10000, { pill: 'Auto - 480p', kbps: 5000 }), at(15000, { pill: null, kbps: 5000 }),
    ]);
    assert.equal(screen.floorShown, 10);
    assert.equal(screen.floorOnGoodLink, 5);
    assert.equal(screen.holdOnGoodLink, 0);
  });

  test('360p on screen with the link at 2,000 kbit/s or more is the hold on a good link', () => {
    const screen = screenAgainstLink([
      at(0, { pill: 'Auto - 360p', kbps: 2000 }), at(5000, { pill: 'Auto - 360p', kbps: 1999 }),
      at(10000, { pill: 'Auto - 360p', kbps: 4000 }),
    ]);
    assert.equal(screen.holdOnGoodLink, 10);
    assert.equal(screen.floorShown, 0);
  });

  test('the toasts caught are counted by their text, in the order first seen', () => {
    const screen = screenAgainstLink([
      at(0, { toast: 'Slow link' }), at(5000), at(10000, { toast: 'Back' }), at(15000, { toast: 'Slow link' }),
    ]);
    assert.deepEqual(screen.toasts, [['Slow link', 2], ['Back', 1]]);
  });

  test('segments are summed by rung in the order met, and those the player cut short are counted apart', () => {
    const requests = [
      { kind: 'api', sent: 999, rung: '480p' },
      { kind: 'segment', rung: '480p', sent: 600000, aborted: false },
      { kind: 'segment', rung: '240p', sent: 100, aborted: false },
      { kind: 'segment', rung: '480p', sent: 400000, aborted: true },
    ];
    assert.deepEqual(rungsAndBytes(requests), {
      bytes: [['480p', 1000000], ['240p', 100]], count: 3, cut: 1, cutBytes: 400000,
    });
    assert.deepEqual(rungsAndBytes([]), { bytes: [], count: 0, cut: 0, cutBytes: 0 });
  });
});

describe('describe: the lines of an analysis', () => {
  const record = {
    report: {
      stallCount: 155, stallMs: 2011000, longestStallMs: 192000, watchedMs: 6331000, stepsDown: 39, stepsUp: 38,
      timeouts: 3, rungMs: { '480p': 1800000, '360p': 4500000 },
      minutes: [[5, 0, 5, 0, 1000], [6, 0, 5, 0, 2000], [7, 0, 5, 0, 4000]],
    },
  };
  const report = {
    stallCount: 2, stallMs: 5000, longestStallMs: 3500, watchedMs: 470000, stepsDown: 1, stepsUp: 1, timeouts: 0,
    rungMs: { '480p': 400000, '240p': 70000 },
    minutes: [[4, 0, 4, 0, 0, 90, 4], [5, 0, 5, 0, 500, 60, 5], [6, 0, 5, 0, 700, 20, 6], [7, 0, 5, 0, 0, 40, 5]],
  };
  const samples = [];
  for (let s = 5; s <= 480; s += 5) {
    samples.push({
      ms: s * 1000, t: s - 1, ahead: 30, kbps: 1500, readyState: 4, paused: false, buffering: null,
      pill: s < 400 ? 'Auto - 480p' : 'Auto - 240p', toast: s === 100 ? 'Slow link' : null,
    });
  }
  const requests = [
    { kind: 'segment', rung: '480p', ms: 0, firstByteMs: 100, doneMs: 1000, sent: 600000, aborted: false },
    { kind: 'segment', rung: '240p', ms: 70000, firstByteMs: 100, doneMs: 1000, sent: 300000, aborted: true },
  ];

  test('every line, in order', () => {
    const delivered = [600000, 300000]; // as the stand-in counted them: 600,000 bytes in minute 0, 300,000 in minute 1
    const lines = describeRun({
      name: 'demo', heading: 'Replay of record minutes 0 to 7', samples, requests, delivered, report, record, start: 0,
    });
    assert.deepEqual(lines, [
      'demo: Replay of record minutes 0 to 7',
      '',
      '== 1. Was the run sound? ==',
      '  samples: 96, longest gap 5000 ms, gaps over 6.5 s: 0 (a long gap is a busy or sleeping machine)',
      '  samples not playing after the first 20 s: 0',
      '  of those, with more than 5 s of film in hand: 0 (anything but 0 is a stall for the wrong reason)',
      '  playhead: 465.0 s of film in 465 s of clock; fell behind by 0.0 s in all, 0.0 s of it with over 10 s in hand',
      '  delivered 1 MB of the 90 MB the trace allowed; minutes delivered well above the trace: 0',
      '  thin minutes (trace under 700 kbit/s), minute:trace/delivered: ',
      '',
      '== 2. Gaps of over 30 s with no segment being fetched ==',
      '    69 s, minutes 0-1: fetched 480p before and 240p after, ahead 30 s -> 30 s, link 1500, 1500',
      '  in all: 69 s',
      '',
      '== 3. The six bad spells ==',
      '  minutes 5-7: mean link 1500 kbit/s, banked going in 90 s, lowest 20 s, stalled 1 s (12 Nov 7 s), fetched 360p, 240p',
      '',
      '== 4. Screen, rungs and bytes ==',
      '  240p on screen 85 s, 0 s of it with the link at 2 Mbit/s or more; 360p on screen with the link at 2 Mbit/s or more: 0 s',
      '  toasts caught by the five-second samples: 1 x "Slow link"',
      '  replay: 2 stalls, 5 s stalled, longest 3.5 s, 470 s watched, steps down 1, up 1, timeouts 0',
      '          seconds on each rung: 480p 400, 240p 70',
      '  12 Nov: 155 stalls, 2011 s stalled, longest 192.0 s, 6331 s watched, steps down 39, up 38, timeouts 3',
      '          seconds on each rung: 480p 1800, 360p 4500',
      '  fetched: 480p 1 MB, 240p 0 MB; 2 segments, 1 cut short by the player (0.3 MB thrown away)',
    ]);
  });
});

describe('analyseRun', () => {
  test('refuses a name that is not a run name before looking at any file, with a usage message', () => {
    const usage = 'usage: node scripts/replay/analyse.mjs <run name> (letters, digits, dot, dash and underscore)';
    for (const bad of [undefined, '', '..', '../x', 'a/b', 'a b']) {
      assert.throws(() => analyseRun(bad), { message: usage }, String(bad));
    }
  });

  test('called as the program calls it, with no root, a run that has no folder is refused as a missing file', () => {
    // A name that cannot exist: this reads no run that is there.
    const name = `no-such-run-${process.pid}`;
    assert.throws(() => analyseRun(name), { message: 'requests.json is not in this run\'s folder' });
  });

  test('the default folder of runs is scripts/replay/out in this checkout, a folder git ignores', () => {
    const here = dirname(fileURLToPath(import.meta.url));
    assert.equal(RUNS_DIR, join(here, '..', '..', 'replay', 'out'));
  });

  test('a run\'s folder is under the runs folder unless another root is given: the one place the driver also uses', () => {
    assert.equal(runDir('smoke'), join(RUNS_DIR, 'smoke'));
    assert.equal(runDir('smoke', '/some/root'), join('/some/root', 'smoke'));
  });
});

describe('analyseRun on a run\'s files', () => {
  const tmp = mkdtempSync(join(tmpdir(), 'replay-'));
  after(() => rmSync(tmp, { recursive: true, force: true }));

  // A thin link (300 kbit/s), so that the thin-minutes line names the record minute the run began at.
  const samples = [5, 10, 15, 20, 60].map((s) => at(s * 1000, { t: s, ahead: 30, kbps: 300 }));
  const requests = [{ kind: 'segment', rung: '480p', ms: 0, firstByteMs: 10, doneMs: 500, sent: 1000, aborted: false }];
  const report = {
    stallCount: 0, stallMs: 0, longestStallMs: 0, watchedMs: 20000, stepsDown: 0, stepsUp: 0, timeouts: 0,
    rungMs: { '480p': 20000 }, minutes: [],
  };
  const HEADING = 'Replay of record minutes 24 to 24 (18:20:53 to 18:21:53), sampled trace';
  const NO_SUMMARY = 'WARNING: no summary line in this run: it is read as starting at record minute 0';
  let counter = 0;

  // Writes a run's files under the temporary root, a text for each file given (a missing one is not written), and
  // returns its name.
  function writeRun(files) {
    counter += 1;
    const name = `run-${counter}`;
    mkdirSync(join(tmp, name));
    for (const [file, text] of Object.entries(files)) {
      if (text !== undefined) writeFileSync(join(tmp, name, file), text);
    }
    return name;
  }

  const good = {
    'requests.json': JSON.stringify(requests),
    'delivered.json': JSON.stringify([1000]),
    'samples.json': JSON.stringify(samples),
    'health.json': JSON.stringify({ final: { report } }),
    'summary.txt': `${HEADING}\nrest\n`,
  };
  const expected = (name, heading, start) => describeRun({
    name, heading, samples, requests, delivered: [1000], report, record: loadRecord(), start,
  });

  const THIN = '  thin minutes (trace under 700 kbit/s), minute:trace/delivered: ';

  test('reads the run, and its start minute from its summary: the thin minute is named by its record minute', () => {
    const name = writeRun(good);
    const lines = analyseRun(name, tmp);
    assert.deepEqual(lines, expected(name, HEADING, 24));
    assert.ok(lines.includes(`${THIN}24:300/0`), 'minute 24 is named');
  });

  test('a run with no summary file is read from record minute 0, and says so in a first line of its own', () => {
    const name = writeRun({ ...good, 'summary.txt': undefined });
    const lines = analyseRun(name, tmp);
    assert.deepEqual(lines, [NO_SUMMARY, ...expected(name, '(no summary line)', 0)]);
    assert.ok(lines.includes(`${THIN}0:300/0`), 'minute 0 is named');
  });

  test('a run whose summary has no Replay line says so too', () => {
    const name = writeRun({ ...good, 'summary.txt': 'WARNING: stopped early\n' });
    const lines = analyseRun(name, tmp);
    assert.equal(lines[0], NO_SUMMARY);
    assert.equal(lines[1], `${name}: (no summary line)`);
    assert.ok(lines.includes(`${THIN}0:300/0`));
  });

  test('a run with a summary does not get the warning', () => {
    assert.notEqual(analyseRun(writeRun(good), tmp)[0], NO_SUMMARY);
  });

  test('a run with no health report, or a health file that says null, has nothing to analyse', () => {
    const message = 'no health report in this run: nothing to analyse';
    assert.throws(() => analyseRun(writeRun({ ...good, 'health.json': '{"final":null}' }), tmp), { message });
    assert.throws(() => analyseRun(writeRun({ ...good, 'health.json': '{}' }), tmp), { message });
    assert.throws(() => analyseRun(writeRun({ ...good, 'health.json': 'null' }), tmp), { message });
  });

  test('a run with no samples has nothing to analyse', () => {
    const message = 'no samples in this run: nothing to analyse';
    assert.throws(() => analyseRun(writeRun({ ...good, 'samples.json': '[]' }), tmp), { message });
  });

  test('a report that is not a plain object is refused: a list, a number, text', () => {
    const message = 'no health report in this run: nothing to analyse';
    for (const report of ['[]', '5', '"text"', '[{"minutes":[]}]']) {
      const health = `{"final":{"report":${report}}}`;
      assert.throws(() => analyseRun(writeRun({ ...good, 'health.json': health }), tmp), { message }, report);
    }
  });

  test('a report with no minute rows is read as having none', () => {
    const bare = { ...report };
    delete bare.minutes;
    const name = writeRun({ ...good, 'health.json': JSON.stringify({ final: { report: bare } }) });
    assert.deepEqual(analyseRun(name, tmp), expected(name, HEADING, 24));
  });

  test('minute rows that are not lists are left out, and a sample with no time is refused', () => {
    const odd = { ...report, minutes: [null, 5, 'x', { 0: 1 }] };
    const name = writeRun({ ...good, 'health.json': JSON.stringify({ final: { report: odd } }) });
    assert.deepEqual(analyseRun(name, tmp), expected(name, HEADING, 24));
    const message = 'samples.json holds a sample with no time, or one out of range';
    for (const bad of ['[{"kbps":1}]', '[{"ms":"5"}]', '[{"ms":null}]', '[{"ms":-1}]', '[{"ms":1000000001}]']) {
      assert.throws(() => analyseRun(writeRun({ ...good, 'samples.json': bad }), tmp), { message }, bad);
    }
  });

  test('a sample at 0 ms and one at 1,000,000,000 ms are the edges of what is accepted', () => {
    for (const ms of [0, 1000000000]) {
      const sample = JSON.stringify([{ ms, t: 0, ahead: 0, kbps: 300 }]);
      const name = writeRun({ ...good, 'samples.json': sample });
      assert.doesNotThrow(() => analyseRun(name, tmp), String(ms));
    }
  });

  test('samples.json or requests.json that is not a list of objects is refused by name', () => {
    const samplesMessage = 'samples.json is not a list of objects';
    for (const bad of ['{}', '[null]', '[1, 2]', '["x"]', '[[1]]', 'null']) {
      const name = writeRun({ ...good, 'samples.json': bad });
      assert.throws(() => analyseRun(name, tmp), { message: samplesMessage }, bad);
    }
    const requestsMessage = 'requests.json is not a list of objects';
    for (const bad of ['{}', '[null]', '[1, 2]', '["x"]', '[[1]]']) {
      const name = writeRun({ ...good, 'requests.json': bad });
      assert.throws(() => analyseRun(name, tmp), { message: requestsMessage }, bad);
    }
  });

  test('the delivery line is made from delivered.json: what it holds is what is delivered', () => {
    const name = writeRun({ ...good, 'delivered.json': '[999999999999]' });
    const lines = analyseRun(name, tmp);
    assert.ok(lines.includes('  delivered 1000000 MB of the 2 MB the trace allowed; minutes delivered well above the trace: 1'));
  });

  test('a run with no delivered.json is refused as a missing file', () => {
    const name = writeRun({ ...good, 'delivered.json': undefined });
    assert.throws(() => analyseRun(name, tmp), { message: 'delivered.json is not in this run\'s folder' });
  });

  test('a delivered.json that is not a list of byte counts of 0 or more is refused', () => {
    const message = 'delivered.json is not a list of byte counts';
    for (const bad of ['{}', 'null', '5', '[-1]', '["5"]', '[null]', '[1, "x"]', '[[1]]']) {
      const name = writeRun({ ...good, 'delivered.json': bad });
      assert.throws(() => analyseRun(name, tmp), { message }, bad);
    }
    const name = writeRun({ ...good, 'delivered.json': '[0, 5]' });
    assert.doesNotThrow(() => analyseRun(name, tmp));
  });

  test('a file that is not there, one that is not JSON and one that cannot be read are told apart', () => {
    assert.throws(() => analyseRun(writeRun({ ...good, 'requests.json': undefined }), tmp), {
      message: 'requests.json is not in this run\'s folder',
    });
    assert.throws(() => analyseRun(writeRun({ ...good, 'samples.json': '{ nope' }), tmp), {
      message: 'samples.json is not JSON',
    });
    const name = writeRun({ ...good, 'health.json': undefined });
    mkdirSync(join(tmp, name, 'health.json'));
    assert.throws(() => analyseRun(name, tmp), { message: 'health.json cannot be read' });
  });

  test('a run that has no folder is a missing file', () => {
    assert.throws(() => analyseRun('no-such-run', tmp), { message: 'requests.json is not in this run\'s folder' });
  });
});
