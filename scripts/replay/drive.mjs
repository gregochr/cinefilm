// Replays a stretch of the 12 November journey: the real CineFilm player (built from this checkout) watches the
// stand-in film in a headless Chrome while the stand-in delivers it at the pace the record's link managed.
//
//   node scripts/replay/drive.mjs --start 24 --minutes 15 --trace sampled --name smoke
//
//   --start     the record minute the replay begins at (0 is 17:56:53)            default 0
//   --minutes   how long to run, in minutes of real time                            default 15
//   --trace     sampled (clean minutes at the sampled speed) or cautious (0.6 of it)  default sampled
//               (flat:<kbit/s> is a steady link, for checking the rig itself)
//   --name      the folder under scripts/replay/out/ for the results; the default is
//               run-<start>-<minutes>-<trace>, with the colon of a flat trace written as a dash
//   --tmp       where the browser's throwaway profile is made                       default the system temp folder
//   --fill      media (padding inside the picture's stream, so the browser's buffer weighs what a real rung
//               would) or null (padding the player's demuxer drops)                 default media
//   --wire      how heavy a segment is, as a multiple of its rung's average bitrate (1 to 1.5); the default,
//               1.08, is the MPEG-TS wrapping that the trace's stalled minutes are worked out with
//   --rebuild   build the app again first (do this after the checkout changes)
//
// Ctrl-C (or SIGTERM) ends the replay early. Its results are written once the driver has looked at the film for the
// first time (five seconds in); before that nothing is written, the run's folder is not made, and an earlier run of
// the same name stays as it was. A second Ctrl-C quits at once (code 130), stops Chrome as far as it can and writes
// nothing. The exit code is 0 when the run ran to its end or a stop was asked for, even when the build, the making of
// the picture or Chrome died of the same Ctrl-C (in a terminal it reaches them too); 1 when it failed with no stop
// asked for (a refused option, a failed build, Chrome did not start or went away part-way).
//
// Output (only once there is something to write): scripts/replay/out/<name>/ holds health.json (the stand-in's
// figures of the viewing's last playback-health report), samples.json (every 5 s: playhead, buffer, what the screen says), requests.json (every
// answer the stand-in sent) and summary.txt (the table printed at the end).
//
// The options, the choice of the final report, the summary and the small helpers are tested; `main`, which starts
// Chrome and the build, cannot run in CI and is checked by hand (docs/engineering/home-replay.md).
import { spawn, spawnSync } from 'node:child_process';
import { accessSync, mkdirSync, writeFileSync } from 'node:fs';
import net from 'node:net';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { launchChrome, sleep } from './cdp.mjs';
import { rateAt } from './link.mjs';
import { makeTemplate, TEMPLATE_PATH } from './make-template.mjs';
import { startStandIn } from './standin.mjs';
import { isRunName, printable } from './text.mjs';
import { clockOf, loadRecord, rowsByMinute, traces, WIRE } from './trace.mjs';
import { loadTemplate } from './ts.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const FRONTEND = join(HERE, '..', '..', 'frontend');
const VITE = join(FRONTEND, 'node_modules', 'vite', 'bin', 'vite.js');
const DIST = join(HERE, 'dist');
const OUT = join(HERE, 'out');
const FILM = join(FRONTEND, 'src', 'test', 'fixtures', 'film-heat.json');
const PORT = 5301;
const SAMPLE_MS = 5000;
const STILL_MS = 20000; // a wait this much longer than a sample's own means the machine stood still
const LINE_LIMIT = 300;
const FLAT_MINUTES = 400;
const MAX_PAGE_ERRORS = 100;
const RUNGS = ['-', 'orig', '1080', '720', '480', '360', '240'];
const RUNG_IDS = ['original', '1080p', '720p', '480p', '360p', '240p'];

// ---- options -----------------------------------------------------------------------------------------------------

const VALUE_OPTIONS = new Set(['start', 'minutes', 'trace', 'name', 'fill', 'wire', 'tmp']);
const WHOLE = /^\d{1,9}$/;
const DECIMAL = /^\d{1,3}(?:\.\d{1,6})?$/;
const FLAT = /^flat:\d{1,7}$/;

// Reads the option at argv[i]: puts its raw text (or `true`, for --rebuild) in `given` and returns how many
// arguments it took. Refuses what it does not know, a repeat, and an option without its value.
function takeOption(argv, i, given) {
  const arg = argv[i];
  const name = arg.startsWith('--') ? arg.slice(2) : null;
  if (name !== 'rebuild' && !VALUE_OPTIONS.has(name)) throw new Error(`unknown option ${printable(arg, 40)}`);
  if (given.has(name)) throw new Error(`--${name} was given twice`);
  if (name === 'rebuild') {
    given.set(name, true);
    return 1;
  }
  const value = argv[i + 1];
  if (value === undefined || value.startsWith('--')) throw new Error(`--${name} needs a value`);
  given.set(name, value);
  return 2;
}

function readArgs(argv) {
  const given = new Map();
  let i = 0;
  while (i < argv.length) i += takeOption(argv, i, given);
  return given;
}

function whole(given, name, fallback, least) {
  const text = given.get(name);
  if (text === undefined) return fallback;
  if (!WHOLE.test(text) || Number(text) < least) {
    throw new Error(`--${name} must be a whole number, ${least} or more`);
  }
  return Number(text);
}

function oneOf(given, name, fallback, allowed) {
  const text = given.get(name) ?? fallback;
  if (!allowed.includes(text)) throw new Error(`--${name} must be ${allowed.join(' or ')}`);
  return text;
}

function traceOption(given) {
  const text = given.get('trace') ?? 'sampled';
  if (text !== 'sampled' && text !== 'cautious' && !FLAT.test(text)) {
    throw new Error('--trace must be sampled, cautious or flat:<whole kbit/s>');
  }
  return text;
}

function wireOption(given) {
  const text = given.get('wire');
  if (text === undefined) return WIRE;
  if (!DECIMAL.test(text) || Number(text) < 1 || Number(text) > 1.5) {
    throw new Error('--wire must be a number from 1 to 1.5');
  }
  return Number(text);
}

function nameOption(given, fallback) {
  const text = given.get('name') ?? fallback;
  if (!isRunName(text)) {
    throw new Error('--name must be 1 to 60 characters: letters, digits, dot, dash and underscore, and not . or ..');
  }
  return text;
}

function tmpOption(given) {
  const text = given.get('tmp') ?? tmpdir();
  if (text === '' || text.includes('\0')) throw new Error('--tmp must be a folder');
  return text;
}

/** The options from the command line's arguments (without the program's own two), checked. Throws on any refusal. */
export function options(argv) {
  const given = readArgs(argv);
  const start = whole(given, 'start', 0, 0);
  const minutes = whole(given, 'minutes', 15, 1);
  const trace = traceOption(given);
  return {
    start,
    minutes,
    trace,
    name: nameOption(given, `run-${start}-${minutes}-${trace.replace(':', '-')}`),
    tmp: tmpOption(given),
    fill: oneOf(given, 'fill', 'media', ['media', 'null']),
    wire: wireOption(given),
    rebuild: given.has('rebuild'),
  };
}

/**
 * The trace's kbit/s for each record minute, from the options and the record. Throws when `--start` is past it, or
 * when the run would not end within it (`--start` plus `--minutes` over its length).
 */
export function traceFor(opt, record) {
  const flat = /^flat:(\d+)$/.exec(opt.trace);
  let trace;
  if (flat) {
    trace = new Array(FLAT_MINUTES).fill(Number(flat[1]));
  } else {
    const both = traces(record);
    trace = opt.trace === 'cautious' ? both.cautious : both.sampled;
  }
  if (opt.start >= trace.length) throw new Error(`--start must be from 0 to ${trace.length - 1}`);
  if (opt.start + opt.minutes > trace.length) {
    throw new Error(`--start plus --minutes must be ${trace.length} or less`);
  }
  return trace;
}

// ---- the viewing's report -----------------------------------------------------------------------------------------

/**
 * The run's report from what the stand-in kept: among the entries that hold a report, the one with the highest `seq`
 * in the last viewing (the highest `viewing` number); a later entry wins a tie, and a report without a `seq` counts
 * as 0. Returns `{ final, readable, viewings }` (that entry, how many reports there were, how many viewings sent
 * one), or null when none was readable.
 */
export function pickFinal(healthReports) {
  const readable = healthReports.filter((entry) => entry.report);
  if (readable.length === 0) return null;
  const viewings = new Set(readable.map((entry) => entry.viewing));
  const last = Math.max(...viewings);
  let final = null;
  for (const entry of readable) {
    if (entry.viewing === last && (final === null || (entry.report.seq ?? 0) >= (final.report.seq ?? 0))) final = entry;
  }
  return { final, readable: readable.length, viewings: viewings.size };
}

// ---- the summary --------------------------------------------------------------------------------------------------

const pad = (value, width) => String(value).padStart(width);
const rungName = (code) => RUNGS[code] ?? '-';
const secondsOf = (ms) => Math.round(ms / 1000);

function headerLine(opt, record) {
  const span = `${clockOf(record, opt.start)} to ${clockOf(record, opt.start + opt.minutes)}`;
  const last = opt.start + opt.minutes - 1;
  return `Replay of record minutes ${opt.start} to ${last} (${span}), ${opt.trace} trace, ${opt.fill} padding, `
    + `segments at ${opt.wire} of their rung's average bitrate`;
}

function minuteLine({ m, record, trace, row, old, asked }) {
  const cut = asked.filter((r) => r.aborted).length;
  const cutNote = cut ? ` (${cut} cut)` : '';
  const rungs = [...new Set(asked.map((r) => r.rung))].join(',');
  const replayed = row
    ? [`${secondsOf(row[4])}`, rungName(row[2]), rungName(row[6]), `${row[5]}s`]
    : ['-', '-', '-', '-'];
  const before = old ? [`${secondsOf(old[4])}s`, rungName(old[2])] : ['-', '-'];
  const left = `${pad(m, 4)}   ${clockOf(record, m)} ${pad(rateAt(trace, m), 5)}   |`;
  const middle = `${pad(replayed[0], 5)}s ${pad(replayed[1], 6)} ${pad(replayed[2], 7)} ${pad(replayed[3], 5)}`;
  const asking = pad(`${asked.length}${cutNote} ${rungs}`, 14);
  return `${left}        ${middle}  ${asking} |        ${pad(before[0], 5)} ${pad(before[1], 6)}`;
}

function minuteLines({ opt, record, trace, report, requests }) {
  const original = rowsByMinute(record);
  const replayRows = new Map((report?.minutes ?? []).map((row) => [row[0], row]));
  const segments = requests.filter((r) => r.kind === 'segment');
  const lines = [];
  let replayStalled = 0;
  let originalStalled = 0;
  for (let i = 0; i < opt.minutes; i += 1) {
    const m = opt.start + i;
    const row = replayRows.get(i);
    const old = original.get(m);
    const asked = segments.filter((r) => r.ms !== null && Math.floor(r.ms / 60000) === i);
    lines.push(minuteLine({ m, record, trace, row, old, asked }));
    replayStalled += row ? row[4] : 0;
    originalStalled += old ? old[4] : 0;
  }
  lines.push('', `stalled in the replay: ${secondsOf(replayStalled)} s; `
    + `on 12 November in the same minutes: ${secondsOf(originalStalled)} s`);
  return lines;
}

function reportLines(report) {
  if (!report) return ['NO HEALTH REPORT ARRIVED: the figures above are not to be trusted.'];
  const rungs = Object.entries(report.rungMs ?? {}).map(([id, ms]) => `${id} ${secondsOf(ms)}`).join(', ');
  return [
    `the report: ${report.stallCount} stalls, ${secondsOf(report.stallMs)} s stalled, `
      + `${secondsOf(report.watchedMs)} s watched, longest stall ${secondsOf(report.longestStallMs)} s, `
      + `start-up ${report.startupMs} ms`,
    `            steps down ${report.stepsDown}, steps up ${report.stepsUp}, timeouts ${report.timeouts}, `
      + `network errors ${report.netErrors}, reconnects ${report.reconnectCount}, recoveries ${report.recoveries}, `
      + `fell back ${report.fellBack}, engine ${report.engine}, environment ${report.environment}`,
    `            time on each rung (s): ${rungs}`,
  ];
}

function requestLines(requests) {
  const lines = [];
  const session = requests.find((r) => r.kind === 'session');
  if (session) lines.push(`the session: probe ${session.probeKbps} kbit/s, start rung ${session.startRung}`);
  const segments = requests.filter((r) => r.kind === 'segment');
  const cut = segments.filter((r) => r.aborted).length;
  const byRung = RUNG_IDS
    .map((id) => ({ id, n: segments.filter((r) => r.rung === id).length }))
    .filter((entry) => entry.n > 0)
    .map((entry) => `${entry.id} ${entry.n}`)
    .join(', ');
  lines.push(`segments asked for: ${segments.length} (${cut} cut short by the player); by rung: ${byRung}`);
  return lines;
}

function endLines(samples, pageErrors) {
  const lines = [];
  const last = samples[samples.length - 1];
  if (last) lines.push(`at the end: playhead ${last.t} s, ${last.ahead} s buffered, the screen says "${last.pill ?? ''}"`);
  if (pageErrors.length) {
    lines.push(`page errors and warnings (${pageErrors.length}), first three:`);
    for (const error of pageErrors.slice(0, 3)) lines.push(`  ${error}`);
  }
  return lines;
}

function warningLines(warnings, viewings) {
  const lines = warnings.map((warning) => `WARNING: ${warning}`);
  if (viewings > 1) {
    lines.push(`WARNING: the player started ${viewings} viewings in this run: the report is the last one's, `
      + 'and the requests and samples are of all of them');
  }
  return lines;
}

/** The text of summary.txt: one printable line per line, none longer than 300 characters. */
export function summarise({
  opt, record, trace, final, viewings = 1, samples, requests, pageErrors, warnings = [],
}) {
  const report = final?.report ?? null;
  const lines = [
    ...warningLines(warnings, viewings),
    headerLine(opt, record),
    '',
    'minute clock     link   | replay: stalled played fetched ahead  asked(net) | 12 Nov: stalled played',
    ...minuteLines({ opt, record, trace, report, requests }),
    ...reportLines(report),
    ...requestLines(requests),
    ...endLines(samples, pageErrors),
  ];
  return lines.map((line) => printable(line, LINE_LIMIT)).join('\n');
}

// ---- what is read from the page ----------------------------------------------------------------------------------

// What is read from the page every five seconds. It only looks: the player is not touched.
const SAMPLE = `(() => {
  const v = document.querySelector('video');
  const text = (selector) => document.querySelector(selector)?.textContent?.trim() ?? null;
  if (!v) return { video: false, body: document.body.innerText.slice(0, 160) };
  let ahead = 0;
  const b = v.buffered;
  const t = v.currentTime;
  for (let i = 0; i < b.length; i += 1) {
    if (b.start(i) <= t + 0.5 && b.end(i) >= t) {
      let end = b.end(i);
      for (let j = i + 1; j < b.length && b.start(j) - end <= 0.5; j += 1) end = b.end(j);
      ahead = end - t;
      break;
    }
  }
  return {
    video: true, t: Math.round(t * 10) / 10, paused: v.paused, ended: v.ended, readyState: v.readyState,
    ahead: Math.round(ahead * 10) / 10, ranges: b.length, height: v.videoHeight,
    sound: (v.webkitAudioDecodedByteCount ?? 0) > 0,
    pill: text('.pill'), buffering: text('.buffering__text'), toast: text('.toast'),
    card: text('.slow-card__title'), reconnecting: /Reconnecting/.test(document.body.innerText),
  };
})()`;

// The "Where are you watching?" card cannot be answered by the app (the browser reaches no outside host, so it
// cannot name the network). A viewer would answer or wave it away: it is waved away once with "Not now".
const DISMISS = `(() => {
  const b = [...document.querySelectorAll('.slow-card__actions button')].find((x) => /not now/i.test(x.textContent));
  if (b) b.click();
  return !!b;
})()`;

const SAMPLE_NUMBERS = ['t', 'ahead', 'ranges', 'height', 'readyState'];
const SAMPLE_FLAGS = ['video', 'paused', 'ended', 'sound', 'reconnecting'];
const SAMPLE_TEXTS = ['pill', 'buffering', 'toast', 'card', 'body', 'error'];

/**
 * One sample as it is kept, rebuilt field by field from what the page said: numbers that are finite (else null),
 * flags that are true (else false), and texts through `printable` (200 characters). Anything else is dropped.
 */
export function cleanSample(raw) {
  const source = raw !== null && typeof raw === 'object' ? raw : {};
  const clean = {};
  copyKeys(clean, source, SAMPLE_FLAGS, (value) => value === true);
  copyKeys(clean, source, SAMPLE_NUMBERS, (value) => (Number.isFinite(value) ? value : null));
  copyKeys(clean, source, SAMPLE_TEXTS, (value) => (typeof value === 'string' ? printable(value, 200) : null));
  return clean;
}

// Copies the keys of `keys` that `source` has, each turned by `convert`. No key is made up, none but these is copied.
function copyKeys(clean, source, keys, convert) {
  for (const key of keys) {
    if (Object.hasOwn(source, key)) clean[key] = convert(source[key]);
  }
}

/** Keeps a text from the page (an exception, a console error) as a printable line of at most 300 characters. */
export function notePageError(errors, text) {
  if (errors.length < MAX_PAGE_ERRORS) errors.push(printable(text, 300));
}

/** The text of a console call's arguments: each one's value, else its description, else nothing; joined by spaces. */
export function consoleText(args) {
  const list = Array.isArray(args) ? args : [];
  return list.map((arg) => String(arg?.value ?? arg?.description ?? '')).join(' ');
}

/** The warning for a wait that took far longer than a sample's own (the machine slept), or null. */
export function standStillWarning(elapsedMs, replaySecond) {
  if (elapsedMs <= SAMPLE_MS + STILL_MS) return null;
  return `the machine stood still for ${Math.round(elapsedMs / 1000)} s at replay second ${replaySecond}: `
    + 'nothing after that is to be trusted';
}

// ---- stopping -----------------------------------------------------------------------------------------------------

/** A flag that a signal sets, and a wait that it cuts short. */
export function createStop() {
  let stopped = false;
  let wake = null;
  return {
    get stopped() {
      return stopped;
    },
    trigger() {
      stopped = true;
      if (wake) wake();
    },
    wait(ms) {
      return new Promise((resolve) => {
        const done = () => {
          clearTimeout(timer);
          wake = null;
          resolve();
        };
        const timer = setTimeout(done, ms);
        wake = done;
        if (stopped) done();
      });
    },
  };
}

// The first signal ends the replay (see the header for what is then written); a second one quits at once. (While the
// app is being built the process is waiting for the build, so a signal then is seen when the build has ended.)
function watchSignals(stop, held) {
  const onSignal = () => {
    if (stop.stopped) {
      if (held.kill) held.kill();
      process.exit(130);
    }
    say('stopping (a second Ctrl-C quits at once)');
    stop.trigger();
  };
  process.on('SIGINT', onSignal);
  process.on('SIGTERM', onSignal);
}

/**
 * Takes `port` on 127.0.0.1 and holds it until the caller closes the server it returns, so that a second replay
 * stops here, with an error, before it builds, makes a picture or starts a browser.
 */
export function claimPort(port) {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.once('error', (error) => reject(portError(port, error)));
    server.listen(port, '127.0.0.1', () => resolve(server));
  });
}

/**
 * The error to report for a failure to listen on `port`: for EADDRINUSE, the clear message of a second replay;
 * any other error as it is.
 */
export function portError(port, error) {
  if (error?.code === 'EADDRINUSE') return new Error(`port ${port} is in use: is another replay running?`);
  return error;
}

// The stand-in's own bind can still lose the port in the moment after the claim was let go.
async function startOnPort(settings) {
  try {
    return await startStandIn(settings);
  } catch (error) {
    throw portError(PORT, error);
  }
}

// ---- the build, the film's picture, the Mac ---------------------------------------------------------------------

function say(text) {
  console.log(printable(text, LINE_LIMIT));
}

/** What builds the app: this Node running Vite's own script, by absolute paths, in `frontend/`. */
export function buildCommand() {
  return {
    file: process.execPath,
    args: [VITE, 'build', '--outDir', DIST, '--emptyOutDir'],
    cwd: FRONTEND,
  };
}

function ensureBuild(rebuild) {
  if (!rebuild) {
    try {
      accessSync(join(DIST, 'index.html'));
      return;
    } catch {
      // Not built yet.
    }
  }
  say('building the app into scripts/replay/dist ...');
  const { file, args, cwd } = buildCommand();
  const built = spawnSync(file, args, { cwd, stdio: 'ignore' });
  if (built.status !== 0) throw new Error('the app did not build (run `npm ci` in frontend/ first)');
}

/**
 * The film's picture: loaded from `path`; made by `make` only when loading fails because the file is not there.
 * Any other failure (a file that is not a template) is the caller's.
 */
export async function templateFor({ path, make, load = loadTemplate, log = say }) {
  try {
    return load(path);
  } catch (error) {
    if (error?.code !== 'ENOENT') throw error;
  }
  log('making the stand-in film\'s picture ...');
  await make();
  return load(path);
}

// A replay runs on the wall clock, so the Mac must not doze off part-way: caffeinate ends by itself with this process.
function keepAwake() {
  const awake = spawn('/usr/bin/caffeinate', ['-i', '-w', String(process.pid)], { stdio: 'ignore' });
  awake.on('error', () => {
    // No caffeinate here: a sleep is still noticed by the warning in the summary.
  });
  awake.unref(); // it waits for this process to end, so this process must not wait for it
}

// ---- the replay ---------------------------------------------------------------------------------------------------

async function observe(page, standIn) {
  let sample;
  try {
    sample = cleanSample(await page.evaluate(SAMPLE));
  } catch (error) {
    sample = cleanSample({ video: false, error: String(error?.message ?? error) });
  }
  return { ms: standIn.clockMs(), minute: standIn.minute(), kbps: standIn.kbps(), ...sample };
}

async function dismissCard(page, sample, run) {
  if (run.cardDismissed || !/Where are you watching/.test(sample.card ?? '')) return;
  run.cardDismissed = true;
  await page.evaluate(DISMISS).catch(() => false);
}

/** The line printed every 30 s: the clock, the link, the playhead and what the screen says. */
export function progressLine(record, now) {
  let text = now.pill ?? '';
  if (now.buffering) text += ` | ${now.buffering}`;
  if (now.reconnecting) text += ' | Reconnecting';
  return `  ${clockOf(record, now.minute)} +${pad(Math.round(now.ms / 1000), 4)}s link ${pad(now.kbps, 5)} kbit/s | `
    + `playhead ${pad(now.t ?? '-', 6)} ahead ${pad(now.ahead ?? '-', 5)}s | ${text}`;
}

function listenToPage(page, run) {
  page.on('Runtime.exceptionThrown', (p) => {
    notePageError(run.pageErrors, p.exceptionDetails?.exception?.description ?? p.exceptionDetails?.text);
  });
  page.on('Runtime.consoleAPICalled', (p) => {
    if (p.type === 'error' || p.type === 'warning') notePageError(run.pageErrors, `${p.type}: ${consoleText(p.args)}`);
  });
}

// Looks at the page every five seconds until the time is up or a signal comes.
async function watch({ page, standIn, record, opt, stop, run }) {
  const endAt = Date.now() + opt.minutes * 60000;
  let lastLine = 0;
  let lastLoop = Date.now();
  while (Date.now() < endAt && !stop.stopped) {
    await stop.wait(Math.min(SAMPLE_MS, endAt - Date.now()));
    if (stop.stopped) break;
    const warning = standStillWarning(Date.now() - lastLoop, Math.round(standIn.clockMs() / 1000));
    if (warning) run.warnings.push(warning);
    lastLoop = Date.now();
    const now = await observe(page, standIn);
    run.samples.push(now);
    if (page.closed) throw new Error('Chrome went away');
    await dismissCard(page, now, run);
    if (Date.now() - lastLine >= 30000) {
      lastLine = Date.now();
      say(progressLine(record, now));
    }
  }
}

// The end: let the last report through whatever the link is doing, then leave the page so the player sends it.
async function sendLastReport(page, standIn) {
  standIn.finish();
  try {
    await page.send('Page.navigate', { url: `http://127.0.0.1:${standIn.port}/__replay/blank` });
    await sleep(3000);
  } catch {
    // The browser has gone (Ctrl-C reaches it too): the last report the player sent by itself is used.
  }
}

async function replay({ opt, record, standIn, stop, run, held }) {
  if (stop.stopped) return;
  held.chrome = await launchChrome({ profileRoot: opt.tmp, onSpawn: (kill) => { held.kill = kill; } });
  if (stop.stopped) return;
  const { page } = held.chrome;
  await page.send('Page.enable');
  await page.send('Runtime.enable');
  listenToPage(page, run);
  const last = opt.start + opt.minutes;
  say(`replaying record minutes ${opt.start} to ${last - 1} (${clockOf(record, opt.start)} to `
    + `${clockOf(record, last)}), ${opt.trace} trace, ${opt.minutes} minutes of real time`);
  standIn.startClock();
  await page.send('Page.navigate', { url: `http://127.0.0.1:${standIn.port}/films/1/play?start=0` });
  await watch({ page, standIn, record, opt, stop, run });
  await sendLastReport(page, standIn);
}

/** Whether a run has anything to write: only when the driver took at least one sample of the page. */
export function shouldWriteResults(sampleCount) {
  return sampleCount > 0;
}

/**
 * The exit code: 1 only when the run failed with no stop asked for; 0 when it ran to its end, or a stop was asked
 * for (an error after that, such as Chrome dying of the same Ctrl-C, is part of stopping).
 */
export function exitCodeFor({ failed, stopped }) {
  return failed && !stopped ? 1 : 0;
}

// Whether a stop was asked for. The wait lets a signal that arrived while the process was blocked (the build) or
// while a child died of the same Ctrl-C reach its handler first.
async function stopAsked(stop) {
  await sleep(100);
  return stop.stopped;
}

function writeResults({ outDir, picked, standIn, run, summary }) {
  mkdirSync(outDir, { recursive: true });
  const health = { sent: picked?.readable ?? 0, viewings: picked?.viewings ?? 0, final: picked?.final ?? null };
  writeFileSync(join(outDir, 'health.json'), JSON.stringify(health, null, 1));
  writeFileSync(join(outDir, 'samples.json'), JSON.stringify(run.samples));
  writeFileSync(join(outDir, 'requests.json'), JSON.stringify(standIn.requests));
  writeFileSync(join(outDir, 'summary.txt'), summary);
}

// Runs one replay and, if the driver looked at the film at least once, writes its results. Returns the exit code
// (see `exitCodeFor` and the header). `stop` and `held` belong to the caller, which installed the signal handlers.
async function main(argv, stop, held) {
  const opt = options(argv);
  const record = loadRecord();
  const trace = traceFor(opt, record);
  const claim = await claimPort(PORT);
  ensureBuild(opt.rebuild);
  const template = await templateFor({
    path: TEMPLATE_PATH,
    make: () => makeTemplate({ profileRoot: opt.tmp, onSpawn: (kill) => { held.kill = kill; } }),
  });
  held.kill = null; // the template's Chrome has gone
  keepAwake();
  const run = { samples: [], pageErrors: [], warnings: [], cardDismissed: false };
  await new Promise((resolve) => claim.close(resolve));
  const standIn = await startOnPort({
    port: PORT, distDir: DIST, filmPath: FILM, template, trace, startMinute: opt.start, fill: opt.fill, wire: opt.wire,
  });
  let failed = false;
  try {
    await replay({ opt, record, standIn, stop, run, held });
  } catch (error) {
    failed = true;
    if (!(await stopAsked(stop))) {
      console.error(printable(`the replay ended early: ${error?.message ?? error}`, LINE_LIMIT));
    }
  } finally {
    if (held.chrome) await held.chrome.close();
    await standIn.stop();
  }
  const code = exitCodeFor({ failed, stopped: stop.stopped });
  if (!shouldWriteResults(run.samples.length)) {
    say('nothing was written: the film had not started');
    return code;
  }
  if (stop.stopped) run.warnings.push(`stopped early by a signal at replay second ${secondsOf(standIn.clockMs() ?? 0)}`);
  const picked = pickFinal(standIn.healthReports);
  const summary = summarise({
    opt, record, trace, final: picked?.final ?? null, viewings: picked?.viewings ?? 1, samples: run.samples,
    requests: standIn.requests, pageErrors: run.pageErrors, warnings: run.warnings,
  });
  const outDir = join(OUT, opt.name);
  writeResults({ outDir, picked, standIn, run, summary });
  for (const line of summary.split('\n')) say(line);
  say(`results: ${outDir}`);
  return code;
}

// Leave at once when the work is done: nothing started here may hold the process open.
async function runDriver(argv) {
  const stop = createStop();
  const held = { chrome: null, kill: null };
  watchSignals(stop, held);
  try {
    return await main(argv, stop, held);
  } catch (error) {
    const stopped = await stopAsked(stop);
    if (!stopped) console.error(printable(error?.message ?? error, LINE_LIMIT));
    return exitCodeFor({ failed: true, stopped });
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  process.exit(await runDriver(process.argv.slice(2)));
}
