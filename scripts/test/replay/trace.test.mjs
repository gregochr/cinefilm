// scripts/replay/trace.mjs: the 12 November link rebuilt from its minute rows. The rules are tried on made-up
// records with exact figures; the committed record is checked against the figures train-link-plan.md reached.
import assert from 'node:assert/strict';
import { mkdtempSync, readdirSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { after, describe, test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import {
  CAUTIOUS_FACTOR, RECORD_PATH, SPELLS, WIRE, buildTrace, clockOf, loadRecord, rowsByMinute, spellAverage, traces,
} from '../../replay/trace.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const TRACE_PROGRAM = join(HERE, '..', '..', 'replay', 'trace.mjs');
const tmp = mkdtempSync(join(tmpdir(), 'replay-'));
after(() => rmSync(tmp, { recursive: true, force: true }));

// A record of the given rows: [minute, median kbit/s, rung code, ms moved, ms stalled].
const recordOf = (rows, startedAt = '2026-11-12T17:56:53.956938Z') => ({ startedAt, report: { minutes: rows } });
const sampled = (rows) => buildTrace(recordOf(rows));
const cautious = (rows) => buildTrace(recordOf(rows), { cleanFactor: CAUTIOUS_FACTOR });

describe('a clean minute', () => {
  test('takes the sampled figure, and the cautious trace 0.6 of it, rounded', () => {
    assert.deepEqual(sampled([[0, 1000, 4, 60000, 0]]), [1000]);
    assert.deepEqual(cautious([[0, 1000, 4, 60000, 0]]), [600]);
    assert.deepEqual(cautious([[0, 1001, 4, 60000, 0]]), [601]); // 600.6
    assert.deepEqual(cautious([[0, 1004, 4, 60000, 0]]), [602]); // 602.4
    assert.equal(CAUTIOUS_FACTOR, 0.6);
  });

  test('that has a sample but little stall is still clean at 4,999 ms stalled', () => {
    assert.deepEqual(sampled([[0, 500, 5, 45001, 4999]]), [500]);
  });
});

describe('a stalled minute', () => {
  test('is the seconds moved over 60 times the rung\'s rate times 1.08: 5,000 ms of 360p is 63', () => {
    assert.equal(WIRE, 1.08);
    assert.deepEqual(sampled([[0, 9999, 5, 5000, 45000]]), [63]); // 5/60 x 696 x 1.08 = 62.64
    assert.deepEqual(cautious([[0, 9999, 5, 5000, 45000]]), [63]);
  });

  test('uses the wire rate of each rung of the old ladder', () => {
    const half = (rung) => sampled([[0, 9999, rung, 30000, 30000]])[0];
    assert.equal(half(1), 5469); // original: 10128 x 1.08 / 2
    assert.equal(half(2), 2769); // 1080p: 5128
    assert.equal(half(3), 1419); // 720p: 2628
    assert.equal(half(4), 609); // 480p: 1128
    assert.equal(half(5), 376); // 360p: 696
    assert.equal(half(6), 268); // 240p: 496
  });

  test('is read as 360p when the rung is not one the old ladder had', () => {
    assert.deepEqual(sampled([[0, 9999, 9, 5000, 45000]]), [63]);
    assert.deepEqual(sampled([[0, 9999, 0, 5000, 45000]]), [63]);
  });

  test('begins at 5,000 ms stalled: 4,999 is clean, 5,000 is stalled', () => {
    assert.deepEqual(sampled([[0, 500, 5, 45001, 4999]]), [500]);
    assert.deepEqual(sampled([[0, 500, 5, 45000, 5000]]), [564]); // 45/60 x 696 x 1.08 = 563.76
  });

  test('is not a paused minute: 49,999 ms moved plus stalled is clean, 50,000 is stalled', () => {
    assert.deepEqual(sampled([[0, 500, 5, 44999, 5000]]), [500]);
    assert.deepEqual(sampled([[0, 500, 5, 45000, 5000]]), [564]);
  });

  test('that moved nothing carried nothing', () => {
    assert.deepEqual(sampled([[0, 9999, 5, 0, 60000]]), [0]);
    assert.deepEqual(sampled([[0, 9999, 5, 0, 50000]]), [0]);
  });

  test('is left alone by the cautious trace, which scales only clean minutes', () => {
    const rows = [[0, 1000, 5, 30000, 30000], [1, 1000, 5, 60000, 0]];
    assert.deepEqual(sampled(rows), [376, 1000]);
    assert.deepEqual(cautious(rows), [376, 600]);
  });
});

describe('minutes with no figure take the lower neighbour', () => {
  test('a clean minute with no sample', () => {
    assert.deepEqual(sampled([[0, 800, 4, 60000, 0], [1, 0, 4, 60000, 0], [2, 300, 4, 60000, 0]]), [800, 300, 300]);
    assert.deepEqual(sampled([[0, 300, 4, 60000, 0], [1, 0, 4, 60000, 0], [2, 800, 4, 60000, 0]]), [300, 300, 800]);
  });

  test('a minute the record has no row for', () => {
    assert.deepEqual(sampled([[0, 800, 4, 60000, 0], [2, 300, 4, 60000, 0]]), [800, 300, 300]);
  });

  test('a run of them takes the nearest figure each side, then the lower', () => {
    assert.deepEqual(sampled([[0, 900, 4, 60000, 0], [3, 400, 4, 60000, 0]]), [900, 400, 400, 400]);
    assert.deepEqual(sampled([[0, 400, 4, 60000, 0], [4, 900, 4, 60000, 0]]), [400, 400, 400, 400, 900]);
  });

  test('with one neighbour, that one', () => {
    assert.deepEqual(sampled([[0, 0, 4, 60000, 0], [1, 500, 4, 60000, 0]]), [500, 500]);
    assert.deepEqual(sampled([[0, 500, 4, 60000, 0], [1, 0, 4, 60000, 0]]), [500, 500]);
  });

  test('with none, 0', () => {
    assert.deepEqual(sampled([[0, 0, 4, 60000, 0]]), [0]);
    assert.deepEqual(sampled([[0, 0, 4, 60000, 0], [1, 0, 4, 60000, 0]]), [0, 0]);
  });

  test('a stalled minute that carried nothing is a figure of 0 and fills nobody else', () => {
    assert.deepEqual(sampled([[0, 700, 4, 60000, 0], [1, 0, 5, 0, 60000], [2, 0, 4, 60000, 0]]), [700, 0, 0]);
  });
});

describe('clockOf', () => {
  test('gives the clock time of a record minute, across midnight', () => {
    const record = recordOf([], '2026-11-12T23:59:30.000Z');
    assert.equal(clockOf(record, 0), '23:59:30');
    assert.equal(clockOf(record, 1), '00:00:30');
    assert.equal(clockOf(record, 90), '01:29:30');
  });

  test('keeps the record\'s fractions of a second out of the clock', () => {
    assert.equal(clockOf(recordOf([]), 0), '17:56:53');
    assert.equal(clockOf(recordOf([]), 143), '20:19:53');
  });
});

describe('the committed record', () => {
  const record = loadRecord();
  const both = traces(record);
  const sum = (trace) => trace.reduce((a, b) => a + b, 0);

  test('holds exactly what the replay reads and nothing that identifies the viewing', () => {
    assert.deepEqual(Object.keys(record), ['about', 'startedAt', 'report']);
    assert.deepEqual(Object.keys(record.report), [
      'watchedMs', 'stallCount', 'stallMs', 'longestStallMs', 'stepsDown', 'stepsUp', 'timeouts', 'rungMs', 'minutes',
    ]);
    assert.equal(record.startedAt, '2026-11-12T17:56:53.956938Z');
    const text = readFileSync(RECORD_PATH, 'utf8');
    for (const word of ['playbackId', 'filmId', 'filmTitle', 'operator', 'userAgent', 'screen', 'cores', 'client']) {
      assert.equal(text.includes(`"${word}":`), false, word); // as a key
    }
  });

  test('keeps its eight totals as they were', () => {
    assert.deepEqual(record.report.rungMs, { '720p': 16465, '480p': 1806901, '360p': 4507951 });
    assert.equal(record.report.watchedMs, 6331318);
    assert.equal(record.report.stallCount, 155);
    assert.equal(record.report.stallMs, 2011169);
    assert.equal(record.report.longestStallMs, 192100);
    assert.equal(record.report.stepsDown, 39);
    assert.equal(record.report.stepsUp, 38);
    assert.equal(record.report.timeouts, 12);
  });

  test('has 144 minute rows, one a line, and the traces run 152 minutes', () => {
    assert.equal(record.report.minutes.length, 144);
    assert.equal(rowsByMinute(record).size, 144);
    assert.equal(both.sampled.length, 152);
    assert.equal(both.cautious.length, 152);
    const lines = readFileSync(RECORD_PATH, 'utf8').split('\n').filter((line) => /^ {6}\[\d+,/.test(line));
    assert.equal(lines.length, 144);
  });

  test('has the sampled and cautious figures of minutes 0, 5, 34, 111, 112 and 143', () => {
    const minutes = [0, 5, 34, 111, 112, 143];
    assert.deepEqual(minutes.map((n) => both.sampled[n]), [4030, 61, 0, 2687, 2541, 4425]);
    assert.deepEqual(minutes.map((n) => both.cautious[n]), [2418, 61, 0, 1612, 1525, 2655]);
  });

  test('sums to 276,966 and 172,721 kbit/s over its minutes', () => {
    assert.equal(sum(both.sampled), 276966);
    assert.equal(sum(both.cautious), 172721);
  });

  test('averages 303.519 and 290.904 kbit/s inside the six bad spells', () => {
    assert.deepEqual(SPELLS, [[5, 7], [12, 23], [27, 37], [52, 61], [81, 85], [100, 110]]);
    assert.equal(Math.round(spellAverage(both.sampled) * 1000) / 1000, 303.519);
    assert.equal(Math.round(spellAverage(both.cautious) * 1000) / 1000, 290.904);
  });

  test('points RECORD_PATH at the committed file', () => {
    assert.equal(RECORD_PATH, join(HERE, '..', '..', 'replay', 'record', 'train-2026-11-12.json'));
  });
});

describe('run as a program', () => {
  const run = spawnSync(process.execPath, [TRACE_PROGRAM], { cwd: tmp, encoding: 'utf8' });
  const lines = run.stdout.split('\n');

  test('prints both traces, one line a minute, and writes no file', () => {
    assert.equal(run.status, 0);
    assert.equal(run.stderr, '');
    assert.equal(lines.length, 156); // header, 152 minutes, two summaries and the end of the last line
    assert.deepEqual(readdirSync(tmp), []);
  });

  test('prints the header, a minute\'s line and the two summaries', () => {
    assert.equal(lines[0], 'minute clock     moved stalled sampled  ->  sampled-trace cautious-trace (kbit/s)');
    assert.equal(lines[1], '   0   17:56:53    54       0    4030          4030     2418');
    assert.equal(lines[35], '  34   18:30:53     0      60       0             0        0');
    assert.equal(lines[153], 'sampled: mean 1822 kbit/s over 152 minutes; inside the six spells 304 kbit/s (expected about 300)');
    assert.equal(lines[154], 'cautious: mean 1136 kbit/s over 152 minutes; inside the six spells 291 kbit/s (expected about 300)');
  });

  test('prints a dash for a minute the record has no row for', () => {
    assert.equal(lines[144], ' 143   20:19:53     -       -       -          4425     2655');
  });
});
