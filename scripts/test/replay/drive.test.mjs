// scripts/replay/drive.mjs: what can be tested without Chrome: the command line's options and each refusal, the
// choice of the run's final report, the summary line by line, the cleaning of what the page says, the helpers of the
// sampling loop, and how the app and the film's picture are got. The driver's own loop (`main`) is checked by hand.
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, isAbsolute, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { after, describe, test } from 'node:test';
import {
  buildCommand, cleanSample, createStop, notePageError, options, pickFinal, standStillWarning, summarise,
  templateFor, traceFor,
} from '../../replay/drive.mjs';
import { WIRE, loadRecord, traces } from '../../replay/trace.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..', '..', '..');
const tmp = mkdtempSync(join(tmpdir(), 'replay-'));
after(() => rmSync(tmp, { recursive: true, force: true }));

const refused = (argv, message) => assert.throws(() => options(argv), { message });

describe('options: what is given', () => {
  test('with nothing given: from minute 0 for 15 minutes on the sampled trace, media padding, the trace\'s own weight', () => {
    assert.deepEqual(options([]), {
      start: 0, minutes: 15, trace: 'sampled', name: 'run-0-15-sampled', tmp: tmpdir(), fill: 'media', wire: WIRE,
      rebuild: false,
    });
    assert.equal(WIRE, 1.08);
  });

  test('every option given', () => {
    const given = options([
      '--start', '24', '--minutes', '15', '--trace', 'cautious', '--fill', 'null', '--wire', '1.5',
      '--name', 'my.run_2-b', '--tmp', '/some/folder', '--rebuild',
    ]);
    assert.deepEqual(given, {
      start: 24, minutes: 15, trace: 'cautious', name: 'my.run_2-b', tmp: '/some/folder', fill: 'null', wire: 1.5,
      rebuild: true,
    });
  });

  test('the default name has the start, the minutes and the trace, with the colon of a flat trace made a dash', () => {
    assert.equal(options(['--start', '5', '--minutes', '2', '--trace', 'flat:3000']).name, 'run-5-2-flat-3000');
    assert.equal(options(['--trace', 'cautious']).name, 'run-0-15-cautious');
  });

  test('--rebuild takes no value: the argument after it is an option of its own', () => {
    const given = options(['--rebuild', '--minutes', '3']);
    assert.equal(given.rebuild, true);
    assert.equal(given.minutes, 3);
  });

  test('the edges that are allowed: start 0, one minute, weight 1 and 1.5 and 1.50, a name of 60 characters', () => {
    assert.equal(options(['--start', '0']).start, 0);
    assert.equal(options(['--minutes', '1']).minutes, 1);
    assert.equal(options(['--wire', '1']).wire, 1);
    assert.equal(options(['--wire', '1.50']).wire, 1.5);
    assert.equal(options(['--name', 'a'.repeat(60)]).name, 'a'.repeat(60));
    assert.equal(options(['--trace', 'flat:0']).trace, 'flat:0');
  });
});

describe('options: each refusal, with its exact message', () => {
  test('--start is a whole number from 0', () => {
    const message = '--start must be a whole number, 0 or more';
    for (const bad of ['-1', '1.5', 'abc', '', '1e3', ' 2', '0x10', '1234567890']) refused(['--start', bad], message);
  });

  test('--minutes is a whole number from 1', () => {
    const message = '--minutes must be a whole number, 1 or more';
    for (const bad of ['0', '-5', '2.5', 'x', '']) refused(['--minutes', bad], message);
  });

  test('--trace is sampled, cautious or flat: and a whole number of kbit/s', () => {
    const message = '--trace must be sampled, cautious or flat:<whole kbit/s>';
    for (const bad of ['Sampled', 'flat', 'flat:', 'flat:1.5', 'flat:-3', 'flat:12345678', 'steady', '']) {
      refused(['--trace', bad], message);
    }
  });

  test('--fill is media or null', () => {
    refused(['--fill', 'zero'], '--fill must be media or null');
    refused(['--fill', 'Media'], '--fill must be media or null');
  });

  test('--wire is a number from 1 to 1.5', () => {
    const message = '--wire must be a number from 1 to 1.5';
    for (const bad of ['0.99', '1.51', '2', '0', 'abc', '1e0', '', '1.', '-1', ' 1']) refused(['--wire', bad], message);
  });

  test('--name is 1 to 60 of letters, digits, dot, dash and underscore, and not . or ..', () => {
    const message = '--name must be 1 to 60 characters: letters, digits, dot, dash and underscore, and not . or ..';
    for (const bad of ['', '.', '..', 'a'.repeat(61), 'a/b', '../x', 'a b', 'a\nb', 'a\\b']) {
      refused(['--name', bad], message);
    }
  });

  test('--tmp is a folder: not empty, no null character', () => {
    refused(['--tmp', ''], '--tmp must be a folder');
    refused(['--tmp', 'a\0b'], '--tmp must be a folder');
  });

  test('an option that is not known is refused by name, cut and on one line; so is a bare word', () => {
    refused(['--bogus'], 'unknown option --bogus');
    refused(['--start', '3', 'extra'], 'unknown option extra');
    refused([`--${'x'.repeat(100)}`], `unknown option --${'x'.repeat(38)}`);
    refused(['--a\nb'], 'unknown option --a b');
    refused(['-s', '3'], 'unknown option -s');
  });

  test('an option without its value is refused, also when the next argument is another option', () => {
    refused(['--start'], '--start needs a value');
    refused(['--start', '--minutes', '3'], '--start needs a value');
    refused(['--minutes', '3', '--name'], '--name needs a value');
  });

  test('an option given twice is refused, not read as the last one', () => {
    refused(['--start', '1', '--start', '2'], '--start was given twice');
    refused(['--rebuild', '--rebuild'], '--rebuild was given twice');
  });
});

describe('traceFor', () => {
  const record = loadRecord();

  test('a flat trace is 400 minutes of the one figure', () => {
    const trace = traceFor(options(['--trace', 'flat:3000']), record);
    assert.equal(trace.length, 400);
    assert.deepEqual([...new Set(trace)], [3000]);
  });

  test('sampled and cautious are the record\'s two traces', () => {
    assert.deepEqual(traceFor(options(['--trace', 'sampled']), record), traces(record).sampled);
    assert.deepEqual(traceFor(options(['--trace', 'cautious']), record), traces(record).cautious);
    assert.notDeepEqual(traces(record).sampled, traces(record).cautious);
  });

  test('a start at the last minute of the trace is allowed, one past it is refused', () => {
    assert.equal(traces(record).sampled.length, 152);
    assert.equal(traceFor(options(['--start', '151']), record).length, 152);
    assert.throws(() => traceFor(options(['--start', '152']), record), { message: '--start must be from 0 to 151' });
    assert.throws(() => traceFor(options(['--start', '400', '--trace', 'flat:5']), record), {
      message: '--start must be from 0 to 399',
    });
  });
});

describe('pickFinal', () => {
  const entry = (viewing, seq, extra = {}) => ({ at: 1, viewing, report: { seq, ...extra } });

  test('with one viewing it is the report with the highest seq, whatever the order they came in', () => {
    const reports = [entry(0, 1), entry(0, 3), entry(0, 2)];
    assert.deepEqual(pickFinal(reports), { final: reports[1], readable: 3, viewings: 1 });
  });

  test('with two viewings it is the last viewing\'s highest, even when the first had a higher seq', () => {
    const reports = [entry(0, 9), entry(1, 2), entry(0, 8), entry(1, 4)];
    assert.deepEqual(pickFinal(reports), { final: reports[3], readable: 4, viewings: 2 });
  });

  test('the last viewing is the highest number, not the last entry in the list', () => {
    const reports = [entry(1, 5), entry(0, 9)];
    assert.deepEqual(pickFinal(reports), { final: reports[0], readable: 2, viewings: 2 });
  });

  test('a tie in seq goes to the later entry, and a report with no seq counts as 0', () => {
    const tied = [entry(0, 4, { n: 'first' }), entry(0, 4, { n: 'second' })];
    assert.equal(pickFinal(tied).final.report.n, 'second');
    const noSeq = [{ viewing: 0, report: { n: 'no seq' } }, entry(0, 0, { n: 'zero' })];
    assert.equal(pickFinal(noSeq).final.report.n, 'zero');
    const noSeqLater = [entry(0, 0, { n: 'zero' }), { viewing: 0, report: { n: 'no seq' } }];
    assert.equal(pickFinal(noSeqLater).final.report.n, 'no seq');
  });

  test('entries the stand-in could not read are not counted, and do not make a viewing', () => {
    const reports = [entry(0, 2), { at: 2, viewing: 1, unreadable: true, bytes: 12 }];
    assert.deepEqual(pickFinal(reports), { final: reports[0], readable: 1, viewings: 1 });
  });

  test('none, or none readable, gives null', () => {
    assert.equal(pickFinal([]), null);
    assert.equal(pickFinal([{ at: 1, viewing: 0, unreadable: true, bytes: 3 }]), null);
  });
});

// ---- summarise ---------------------------------------------------------------------------------------------------

// A record and a run of two minutes (record minutes 5 and 6) with made-up figures. A replay row is
// [minute, kbit/s, rung played, ms moved, ms stalled, seconds ahead, rung fetched]
// (rungs: 4 is 480p, 5 is 360p, 6 is 240p).
const RECORD = { startedAt: '2026-11-12T17:56:53.000Z', report: { minutes: [[5, 700, 5, 40000, 12000], [6, 2000, 4, 60000, 0]] } };
const OPT = { start: 5, minutes: 2, trace: 'sampled', fill: 'media', wire: 1.08 };
const TRACE = [0, 0, 0, 0, 0, 640, 2000];
const REPORT = {
  seq: 4, stallCount: 2, stallMs: 3000, watchedMs: 115000, longestStallMs: 2000, startupMs: 4100, stepsDown: 1,
  stepsUp: 0, timeouts: 0, netErrors: 0, reconnectCount: 0, recoveries: 0, fellBack: false, engine: 'hlsjs',
  environment: 'unknown', rungMs: { '480p': 55000, '240p': 60000 },
  minutes: [[0, 500, 5, 55000, 3000, 20, 4], [1, 800, 6, 60000, 0, 140, 6]],
};
const REQUESTS = [
  { kind: 'api', ms: 100 },
  { kind: 'session', ms: 200, probeKbps: 2238, startRung: '480p' },
  { kind: 'segment', ms: 1000, rung: '480p', aborted: false },
  { kind: 'segment', ms: 2000, rung: '480p', aborted: true },
  { kind: 'segment', ms: 61000, rung: '240p', aborted: false },
  { kind: 'segment', ms: null, rung: '360p', aborted: false },
];
const SAMPLES = [
  { ms: 5000, t: 4.9, ahead: 20, pill: 'Auto - 480p' },
  { ms: 115000, t: 100.2, ahead: 140.5, pill: 'Auto - 240p' },
];
const run = (more = {}) => summarise({
  opt: OPT, record: RECORD, trace: TRACE, final: { report: REPORT }, samples: SAMPLES, requests: REQUESTS,
  pageErrors: [], ...more,
}).split('\n');

const HEADER = 'Replay of record minutes 5 to 6 (18:01:53 to 18:03:53), sampled trace, media padding, '
  + 'segments at 1.08 of their rung\'s average bitrate';
const TABLE_HEAD = 'minute clock     link   | replay: stalled played fetched ahead  asked(net) | 12 Nov: stalled played';

describe('summarise', () => {
  test('a two-minute run with a report: every line', () => {
    assert.deepEqual(run({ pageErrors: ['first', 'second', 'third', 'fourth'], warnings: ['w1'] }), [
      'WARNING: w1',
      HEADER,
      '',
      TABLE_HEAD,
      '   5   18:01:53   640   |            3s    360     480   20s  2 (1 cut) 480p |          12s    360',
      '   6   18:02:53  2000   |            0s    240     240  140s          1 240p |           0s    480',
      '',
      'stalled in the replay: 3 s; on 12 November in the same minutes: 12 s',
      'the report: 2 stalls, 3 s stalled, 115 s watched, longest stall 2 s, start-up 4100 ms',
      '            steps down 1, steps up 0, timeouts 0, network errors 0, reconnects 0, recoveries 0, '
        + 'fell back false, engine hlsjs, environment unknown',
      '            time on each rung (s): 480p 55, 240p 60',
      'the session: probe 2238 kbit/s, start rung 480p',
      'segments asked for: 4 (1 cut short by the player); by rung: 480p 2, 360p 1, 240p 1',
      'at the end: playhead 100.2 s, 140.5 s buffered, the screen says "Auto - 240p"',
      'page errors and warnings (4), first three:',
      '  first',
      '  second',
      '  third',
    ]);
  });

  test('a segment asked for before the clock started is counted, but is in no minute\'s row', () => {
    const lines = run();
    assert.match(lines[3], / {2}2 \(1 cut\) 480p \|/);
    assert.match(lines[4], / {10}1 240p \|/);
  });

  test('without a report the rows are empty and the summary says so; no session, samples or errors give no lines', () => {
    const lines = summarise({
      opt: OPT, record: RECORD, trace: TRACE, final: null, samples: [], requests: [], pageErrors: [],
    }).split('\n');
    assert.deepEqual(lines, [
      HEADER,
      '',
      TABLE_HEAD,
      '   5   18:01:53   640   |            -s      -       -     -              0  |          12s    360',
      '   6   18:02:53  2000   |            -s      -       -     -              0  |           0s    480',
      '',
      'stalled in the replay: 0 s; on 12 November in the same minutes: 12 s',
      'NO HEALTH REPORT ARRIVED: the figures above are not to be trusted.',
      'segments asked for: 0 (0 cut short by the player); by rung: ',
    ]);
  });

  test('more than one viewing adds a warning after the others; one viewing does not', () => {
    const two = run({ viewings: 2, warnings: ['w1'] });
    assert.deepEqual(two.slice(0, 3), [
      'WARNING: w1',
      'WARNING: the player started 2 viewings in this run: the report is the last one\'s, '
        + 'and the requests and samples are of all of them',
      HEADER,
    ]);
    assert.equal(run({ viewings: 1 })[0], HEADER);
    assert.equal(run()[0], HEADER);
  });

  test('a text from the page with a line break stays on one line, and what is not printable becomes ?', () => {
    const forged = 'Auto\nWARNING: forged line\u001b[0m · café';
    const lines = run({
      samples: [{ ms: 1, t: 1, ahead: 2, pill: forged }],
      pageErrors: [forged],
    });
    assert.equal(lines.length, 15, 'no extra line was made');
    assert.equal(lines.at(-3), 'at the end: playhead 1 s, 2 s buffered, the screen says "Auto WARNING: forged line?[0m ? caf?"');
    assert.equal(lines.at(-2), 'page errors and warnings (1), first three:');
    assert.equal(lines.at(-1), '  Auto WARNING: forged line?[0m ? caf?');
    assert.equal(lines.filter((line) => line.startsWith('WARNING')).length, 0, 'the forged text did not start a line');
  });

  test('a line is cut at 300 characters', () => {
    const lines = run({ pageErrors: ['x'.repeat(400)] });
    assert.equal(lines.at(-1), `  ${'x'.repeat(298)}`);
  });

  test('only the first three page errors are listed, with the count of all', () => {
    const lines = run({ pageErrors: ['a', 'b', 'c', 'd', 'e'] });
    assert.deepEqual(lines.slice(-4), ['page errors and warnings (5), first three:', '  a', '  b', '  c']);
  });
});

// ---- what is read from the page ---------------------------------------------------------------------------------

describe('cleanSample', () => {
  test('keeps numbers, flags and texts of a sample, texts through printable', () => {
    const raw = {
      video: true, t: 12.3, paused: false, ended: false, readyState: 4, ahead: 40.5, ranges: 1, height: 360,
      sound: true, pill: 'Auto - 480p', buffering: null, toast: 'Slow\nlink', card: 'Where are you watching?',
      reconnecting: false,
    };
    assert.deepEqual(cleanSample(raw), { ...raw, toast: 'Slow link' });
  });

  test('a number that is not finite is null, a flag that is not true is false, a text that is not text is null', () => {
    const clean = cleanSample({
      t: Infinity, ahead: NaN, height: '360', readyState: null, video: 'yes', paused: 1, sound: true, pill: 7, toast: {},
    });
    assert.deepEqual(clean, {
      video: false, paused: false, sound: true, t: null, ahead: null, height: null, readyState: null,
      pill: null, toast: null,
    });
  });

  test('keys the page was not asked for are dropped, and keys it left out are not made up', () => {
    const clean = cleanSample(JSON.parse('{"video":false,"body":"nothing","extra":1,"__proto__":{"polluted":true}}'));
    assert.deepEqual(clean, { video: false, body: 'nothing' });
    assert.equal(Object.getPrototypeOf(clean), Object.prototype);
    assert.equal(({}).polluted, undefined);
  });

  test('a text is cut at 200 characters', () => {
    assert.equal(cleanSample({ pill: 'p'.repeat(201) }).pill, 'p'.repeat(200));
  });

  test('something that is not an object gives an empty sample', () => {
    assert.deepEqual(cleanSample(null), {});
    assert.deepEqual(cleanSample('text'), {});
    assert.deepEqual(cleanSample(undefined), {});
  });
});

describe('notePageError', () => {
  test('keeps the text as one printable line of at most 300 characters', () => {
    const errors = [];
    notePageError(errors, 'Error: bad\nstacké');
    notePageError(errors, 'y'.repeat(301));
    assert.deepEqual(errors, ['Error: bad stack?', 'y'.repeat(300)]);
  });

  test('keeps the first 100 and no more', () => {
    const errors = [];
    for (let i = 0; i < 150; i += 1) notePageError(errors, `error ${i}`);
    assert.equal(errors.length, 100);
    assert.equal(errors[99], 'error 99');
  });
});

describe('standStillWarning', () => {
  test('a wait of 25,000 ms or less is no warning, one millisecond more is', () => {
    assert.equal(standStillWarning(25000, 70), null);
    assert.equal(standStillWarning(5000, 70), null);
    assert.equal(
      standStillWarning(25001, 70),
      'the machine stood still for 25 s at replay second 70: nothing after that is to be trusted',
    );
  });
});

// ---- stopping ---------------------------------------------------------------------------------------------------

describe('createStop', () => {
  test('is not stopped until it is triggered, and stays stopped', () => {
    const stop = createStop();
    assert.equal(stop.stopped, false);
    stop.trigger();
    assert.equal(stop.stopped, true);
    stop.trigger();
    assert.equal(stop.stopped, true);
  });

  test('a wait that has begun ends when it is triggered, not when its time is up', async () => {
    const stop = createStop();
    const waiting = stop.wait(600000);
    stop.trigger();
    await waiting;
    assert.equal(stop.stopped, true);
  });

  test('a wait begun after it was triggered ends at once', async () => {
    const stop = createStop();
    stop.trigger();
    await stop.wait(600000);
  });

  test('a wait ends by itself when its time is up, and that is not a stop', async () => {
    const stop = createStop();
    await stop.wait(1);
    assert.equal(stop.stopped, false);
  });
});

// ---- the build and the film's picture ---------------------------------------------------------------------------

describe('buildCommand', () => {
  test('runs Vite\'s own script with this Node, by absolute paths, in frontend/, into scripts/replay/dist', () => {
    const { file, args, cwd } = buildCommand();
    assert.equal(file, process.execPath);
    assert.equal(isAbsolute(file), true);
    assert.deepEqual(args, [
      join(ROOT, 'frontend', 'node_modules', 'vite', 'bin', 'vite.js'),
      'build', '--outDir', join(ROOT, 'scripts', 'replay', 'dist'), '--emptyOutDir',
    ]);
    assert.equal(cwd, join(ROOT, 'frontend'));
  });
});

describe('templateFor', () => {
  const TEMPLATE_FILE = JSON.stringify({
    codec: 'avc1.64001e', width: 640, height: 360, fps: 2,
    frames: [{ key: true, b64: Buffer.from([1, 2, 3]).toString('base64') }, { key: false, b64: 'BAUG' }],
    silence: Buffer.from([9, 9]).toString('base64'),
  });

  test('a template that is there is loaded, and nothing is made', async () => {
    const dir = mkdtempSync(join(tmp, 'there-'));
    const path = join(dir, 'template.json');
    writeFileSync(path, TEMPLATE_FILE);
    const calls = [];
    const template = await templateFor({ path, make: () => calls.push('made'), log: (text) => calls.push(text) });
    assert.deepEqual(calls, []);
    assert.equal(template.frames.length, 2);
    assert.deepEqual(template.silence, Buffer.from([9, 9]));
  });

  test('when the file is not there it is made, once, and then loaded', async () => {
    const dir = mkdtempSync(join(tmp, 'missing-'));
    const path = join(dir, 'out', 'template.json');
    const calls = [];
    const template = await templateFor({
      path,
      make: () => {
        calls.push('made');
        mkdirSync(join(dir, 'out'));
        writeFileSync(path, TEMPLATE_FILE);
      },
      log: (text) => calls.push(text),
    });
    assert.deepEqual(calls, ['making the stand-in film\'s picture ...', 'made']);
    assert.equal(template.seconds, 1);
  });

  test('a file that is there but is not a template is not made over: the failure is passed on', async () => {
    const dir = mkdtempSync(join(tmp, 'corrupt-'));
    const path = join(dir, 'template.json');
    writeFileSync(path, '{ not json');
    let made = false;
    await assert.rejects(templateFor({ path, make: () => { made = true; }, log: () => undefined }), SyntaxError);
    assert.equal(made, false);
  });

  test('when making does not leave a file, the second load fails and says so', async () => {
    const path = join(mkdtempSync(join(tmp, 'nothing-')), 'template.json');
    await assert.rejects(
      templateFor({ path, make: () => undefined, log: () => undefined }),
      { code: 'ENOENT' },
    );
  });
});
