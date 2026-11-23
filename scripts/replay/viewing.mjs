// The train viewer's run (docs/engineering/train-viewer-plan.md, decisions 2, 3 and 7 to 10): the real player on the
// real site in a headless Chrome on the viewer's signed-in profile, with the guard (guard.mjs) holding back what the
// page would write, looked at every five seconds until the film ends, `--minutes` is up, or something ends it. What
// is checked before it plays, what it presses, what ends it, how it is ended and the run's signals are all here; the
// options, the sign-in, the lock and the crash handler are in viewer.mjs, which starts this. Everything the run
// touches is passed in (`deps`), so that its order can be tested with no Chrome, no site and no clock. The run keeps
// what it saw in memory (`held.run`) and writes it to ~/cinefilm-viewer/runs/ as it goes; `held.writeNow(word)` is the
// last write, which a second signal, a crash and the end call.
import { closeSync, fsyncSync, openSync, renameSync, rmSync, writeSync } from 'node:fs';
import { open, rename, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { launchChrome, sleep, within } from './cdp.mjs';
import { SAMPLE, cleanSample, consoleText, keepAwake, notePageError, standStillWarning } from './drive.mjs';
import { attachGuard } from './guard.mjs';
import { attachSegments } from './segments.mjs';
import { summarise } from './summary.mjs';
import { printable } from './text.mjs';
import {
  PAGE_TEXT, checkProfileFree, makeFolders, ownerDir, processAlive, readAnswer, takeLock, viewerPaths,
} from './viewer.mjs';

export const SITE_RETRY_MS = 15000;
export const SITE_WAIT_MS = 600000;
/** A page that has not loaded in this long is no answer. */
export const LOAD_MS = 30000;
const TITLE_CHARS = 120;
const QUALITY_CHARS = 20;

/** What the "Where are you watching?" card's title says. */
export const PLACE_CARD = /Where are you watching/;

/** The label of the "Where are you watching?" card's button for each `--place` (none given: "Not now"). */
export const PLACE_BUTTON = { train: 'On a train', away: 'Hotel or away', none: 'Not now' };

/** How a run ended: the word kept in the results, the exit code (a signal is 130 before the film was opened). */
export const ENDS = {
  'film-ended': { code: 0, line: 'The film ended.' },
  minutes: { code: 0, line: '--minutes was reached.' },
  signal: { code: 0, line: 'Stopped by a signal.' },
  'signed-out': {
    code: 1,
    line: 'The player shows "Signed out". A release or restart of the server signs every browser out: '
      + 'run --sign-in again.',
  },
  'stopped-from-admin': { code: 1, line: 'Playback was stopped from Admin.' },
  'chrome-gone': { code: 1, line: 'Chrome went away.' },
  // The word is kept from when only a crash was looked for; it also covers a page that is hung.
  'page-crashed': { code: 1, line: 'The player\'s page stopped answering.' },
  refused: { code: 1, line: 'The run did not start.' },
  failed: { code: 1, line: 'The run failed.' },
};

/** The exit code of a run that ended `word`; a signal before the film was opened is 130. */
export function exitCode(word, opened) {
  if (word === 'signal') return opened ? 0 : 130;
  return (ENDS[word] ?? ENDS.failed).code;
}

// ---- what is read from the page and pressed in it -----------------------------------------------------------------

// The player's two "Try again" buttons: on the card "This film can't be played right now", and in the state shown when
// the film could not be loaded. Evaluates to the button, or null. Nothing else on a page is ever found by it.
const FIND_RETRY = `(() => {
  const again = (root, selector) => [...root.querySelectorAll(selector)]
    .find((b) => b.textContent.trim() === 'Try again');
  for (const card of document.querySelectorAll('.slow-card')) {
    const title = card.querySelector('.slow-card__title')?.textContent ?? '';
    const button = /can.t be played right now/i.test(title) ? again(card, '.slow-card__actions button') : null;
    if (button) return button;
  }
  return again(document, '.player .state__actions button') ?? null;
})()`;

/**
 * What is read from the page every five seconds: what the replay reads (`drive.mjs`), and four things of the viewer's
 * own: `direct` (the video's current source is there and does not begin `blob:`: the original file is played
 * directly), `signedOut` (the "Signed out" card), `stopped` ("Playback was stopped from Admin") and `retry` (one of
 * the two "Try again" buttons is there). It only looks.
 */
export const VIEWER_SAMPLE = `(() => {
  const base = ${SAMPLE};
  const video = document.querySelector('video');
  const source = video ? video.currentSrc : '';
  const has = (selector, pattern) => [...document.querySelectorAll(selector)]
    .some((e) => pattern.test(e.textContent.trim()));
  return {
    ...base,
    direct: typeof source === 'string' && source !== '' && !source.startsWith('blob:'),
    signedOut: has('.signin__title', /^Signed out$/),
    stopped: has('.slow-card__text', /^Playback was stopped from Admin/),
    retry: ${FIND_RETRY} !== null,
  };
})()`;

/** The expression that presses the button named `label` on a card's actions; true when it was there. */
export const pressPlace = (label) => `(() => {
  const b = [...document.querySelectorAll('.slow-card__actions button')]
    .find((x) => x.textContent.trim() === ${JSON.stringify(label)});
  if (b) b.click();
  return !!b;
})()`;

/** The expression that presses a "Try again" of the two (and nothing else); true when it was there. */
export const PRESS_RETRY = `(() => { const b = ${FIND_RETRY}; if (b) b.click(); return !!b; })()`;

const SAMPLE_FLAGS = ['direct', 'signedOut', 'stopped', 'retry'];

/**
 * One sample as it is kept: the replay's `cleanSample` of what the page said, the viewer's four flags (true or false),
 * the milliseconds since the film was opened (`sinceMs`) and the time of day in milliseconds since 1970 (`at`).
 */
export function cleanViewerSample(raw, { sinceMs, at }) {
  const source = raw !== null && typeof raw === 'object' ? raw : {};
  const clean = { sinceMs: Number.isFinite(sinceMs) ? Math.round(sinceMs) : null, at: Number.isFinite(at) ? at : null };
  Object.assign(clean, cleanSample(source));
  for (const key of SAMPLE_FLAGS) clean[key] = source[key] === true;
  return clean;
}

/** What ends a run in a sample, as a word of `ENDS`, or null. */
export function endOf(sample) {
  if (sample.signedOut) return 'signed-out';
  if (sample.stopped) return 'stopped-from-admin';
  if (sample.ended) return 'film-ended';
  return null;
}

const two = (n) => String(n).padStart(2, '0');

/** The local time of day, `HH:MM:SS`, of a time in milliseconds since 1970. */
export function timeOfDay(at) {
  const date = new Date(at);
  return `${two(date.getHours())}:${two(date.getMinutes())}:${two(date.getSeconds())}`;
}

/** The line printed every 30 s: the time of day, the seconds in, the playhead, the film in hand, the screen. */
export function progressLine(sample) {
  let text = sample.pill ?? '';
  if (sample.buffering) text += ` | ${sample.buffering}`;
  if (sample.reconnecting) text += ' | Reconnecting';
  if (sample.direct) text += ' | DIRECT PLAY';
  const seconds = sample.sinceMs === null ? '-' : Math.round(sample.sinceMs / 1000);
  return `  ${timeOfDay(sample.at)} +${seconds}s playhead ${sample.t ?? '-'} in hand ${sample.ahead ?? '-'}s | ${text}`;
}

/**
 * The warning for a wait that took far longer than a sample's own (the laptop slept: the replay's rule,
 * `standStillWarning`), in the viewer's own words, or null. A laptop that wakes goes on watching, and what it sees
 * afterwards is good: only the time it stood still is lost.
 */
export function standStill(elapsedMs, secondsIn) {
  if (standStillWarning(elapsedMs, secondsIn) === null) return null;
  return `the laptop stood still for ${Math.round(elapsedMs / 1000)} s at second ${secondsIn} of the run, `
    + 'and nothing was watched in that time';
}

// ---- the film ------------------------------------------------------------------------------------------------------

/**
 * The expression that asks the site for the film and hands back only `{ status, title, runtimeMs, quality }` (just
 * `status` when it is not 200): the runtime is the edition the player would play (the one `preferences.editionId`
 * names, else the first), the quality is `preferences.quality`. Nothing else of the film leaves the page. Throws
 * unless `film` is 1 to 9 digits.
 */
export function filmExpression(film) {
  // The number is pasted into code that runs on a signed-in page: it is checked here, whoever calls.
  const text = typeof film === 'number' || typeof film === 'string' ? String(film) : '';
  if (!/^[1-9]\d{0,8}$/.test(text)) throw new Error('the film must be a number of 1 to 9 digits, from 1');
  return `(async () => {
  try {
    const answer = await fetch('/api/films/${film}');
    if (answer.status !== 200) return { status: answer.status };
    const film = await answer.json();
    const editions = Array.isArray(film.editions) ? film.editions : [];
    const edition = editions.find((e) => e.id === film.preferences?.editionId) ?? editions[0];
    return {
      status: 200, title: film.title, runtimeMs: edition?.runtimeMs, quality: film.preferences?.quality,
    };
  } catch {
    return { status: 0 };
  }
})()`;
}

/** The film's facts rebuilt field by field from what the page said (anything not as expected is null; status 0). */
export function cleanFilm(raw) {
  const source = raw !== null && typeof raw === 'object' ? raw : {};
  const text = (value, limit) => (typeof value === 'string' ? printable(value, limit) : null);
  return {
    status: Number.isInteger(source.status) ? source.status : 0,
    title: text(source.title, TITLE_CHARS),
    runtimeMs: Number.isFinite(source.runtimeMs) && source.runtimeMs >= 0 ? source.runtimeMs : null,
    quality: text(source.quality, QUALITY_CHARS),
  };
}

/** The refusal (one plain line) for the facts of a film the site did answer for, or null. */
export function filmRefusal(film, number) {
  if (film.status === 404) return `There is no film ${number} on the site.`;
  if (film.status !== 200) return `The site would not give film ${number} (status ${film.status}).`;
  if (film.quality !== null && film.quality !== 'auto') {
    return `Film ${number} is set to the quality "${film.quality}" by hand: set it back to Auto on its page, `
      + 'or the run would not measure the player\'s rules.';
  }
  return null;
}

/** "1 h 52 min" for a length in milliseconds; "under a minute"; a length that is not there, or 0, is not known. */
export function lengthOf(ms) {
  if (!(ms > 0)) return 'length not known';
  const minutes = Math.round(ms / 60000);
  if (minutes < 1) return 'under a minute';
  return minutes >= 60 ? `${Math.floor(minutes / 60)} h ${minutes % 60} min` : `${minutes} min`;
}

// ---- the site, before it plays --------------------------------------------------------------------------------------

/** What each real answer of `/api/session` that is not "signed in" says (and the run does not start). */
export const SESSION_REFUSALS = {
  'signed-out': 'Not signed in. Run --sign-in first.',
  unknown: 'The site\'s answer was not understood, so the sign-in could not be checked.',
};

/**
 * Everything that comes before the film plays is one loop that a dead link sends round again. `attempt()` runs the
 * session check, the film's facts and the opening of the player's page, once, and gives `{ kind: 'ready', film }`,
 * `{ kind: 'refused', line }` (a real answer: signed out, not understood, no such film, another status, a film pinned
 * by hand) or `{ kind: 'no-answer' }`. No answer is said once and tried again after 15 s (`wait`), from the start; ten
 * minutes after the first one (by `now`) it gives `{ kind: 'gave-up' }`. A signal (`cancel`, as `createCancel`) gives
 * `{ kind: 'cancelled' }` at once, even while an attempt is still waiting on a dead link. Anything else `attempt`
 * gives is passed on, and so is its throwing.
 */
export async function waitUntilReady({ attempt, wait, now, say, cancel }) {
  let firstMiss = null;
  for (;;) {
    if (cancel.cancelled) return { kind: 'cancelled' };
    const pending = attempt();
    // `race` goes on listening to an attempt given up on for a signal, so its later failure (Chrome is closed under
    // it) is handled and is nobody's news.
    const outcome = await Promise.race([pending, cancel.promise.then(() => ({ kind: 'cancelled' }))]);
    if (outcome.kind === 'cancelled' || cancel.cancelled) return { kind: 'cancelled' };
    if (outcome.kind !== 'no-answer') return outcome;
    if (firstMiss === null) {
      firstMiss = now();
      say('The site is not answering: waiting for it (trying again every 15 seconds, for up to 10 minutes).');
    }
    if (now() - firstMiss >= SITE_WAIT_MS) return { kind: 'gave-up' };
    await wait(SITE_RETRY_MS);
  }
}

const MISS = { kind: 'no-answer' };
/**
 * A server that is restarting (bad gateway, unavailable, gateway timeout) is waited for, not refused: on the film's
 * fetch, and on the two pages, whose loaded document is read for its status (a gateway that answers with a body is a
 * document that loads, with no `errorText`; with no body it is an `errorText`, already a miss).
 */
const RESTARTING = new Set([502, 503, 504]);

/** The HTTP status of the document the page has loaded (what Chrome keeps for its navigation), or null. */
export const STATUS_EXPRESSION = '(performance.getEntriesByType("navigation")[0]?.responseStatus ?? null)';

/** What the page said its status was, rebuilt: a whole number from 100 to 599, else null (it gave none). */
export function cleanStatus(raw) {
  return Number.isInteger(raw) && raw >= 100 && raw <= 599 ? raw : null;
}

// A step that gets no answer on a dead link (the navigation fails, or Chrome does not answer in time, which rejects)
// is a miss, null. When the page is closed it is Chrome that has gone: that is a failure at once.
async function orMiss(page, action) {
  try {
    return await action();
  } catch (error) {
    if (page.closed) throw Object.assign(new Error('Chrome went away'), { code: 'chrome-gone', cause: error });
    return null;
  }
}

// What `/api/session` says, as the sign-in's check reads it, or null: the navigation failed, or the page did not load
// in 30 s.
async function askSession({ page, site, nextLoad, giveUpOn, cancel }) {
  if (cancel.cancelled) return null;
  const loaded = nextLoad();
  const navigation = await page.send('Page.navigate', { url: `${site}/api/session` });
  if (navigation?.errorText) return null;
  if (!(await giveUpOn(loaded, LOAD_MS))) return null;
  // A gateway's error page loads like any other: a restarting server is no answer, whatever its text says.
  if (RESTARTING.has(cleanStatus(await page.evaluate(STATUS_EXPRESSION)))) return null;
  return readAnswer(await page.evaluate(PAGE_TEXT));
}

/**
 * The `attempt` for `waitUntilReady`: the session check, then the film's facts (`film` is the number), then the
 * opening of `<site>/films/<film>/play?start=0`, with `say` saying the film just before that page opens (once, however
 * often the loop goes round). `nextLoad()` gives a promise for the page's next load event, `giveUpOn(promise, ms)`
 * says whether it settled in time (both pages, the session's and the player's, must load in 30 s). A page that is
 * closed is a failure with `code: 'chrome-gone'`; a film that is not a number throws, whatever the page is doing; it
 * throws at once without `cancel`. Both pages count as answered only when the loaded document's status says so: a
 * restarting server (502, 503, 504: `RESTARTING`) is no answer on either, and the player's page with another status
 * of 400 or more is a refusal. An attempt that a signal (`cancel`) has given up on goes on to its next await at
 * most: it says nothing and sends no navigation once the signal is set (it is checked straight before each).
 */
export function makeAttempt({ page, site, film, nextLoad, giveUpOn, say, cancel }) {
  if (!cancel) throw new Error('makeAttempt needs the run\'s cancel');
  let announced = false;
  return async () => {
    // Built before any step: a film that is not a number is a mistake, never a dead link to wait out.
    const expression = filmExpression(film);
    const answer = await orMiss(page, () => askSession({ page, site, nextLoad, giveUpOn, cancel }));
    if (answer === null || cancel.cancelled) return MISS;
    if (answer !== 'signed-in') return { kind: 'refused', line: SESSION_REFUSALS[answer] };
    const facts = await orMiss(page, async () => cleanFilm(await page.evaluate(expression)));
    if (facts === null || facts.status === 0 || RESTARTING.has(facts.status) || cancel.cancelled) return MISS;
    const refusal = filmRefusal(facts, film);
    if (refusal) return { kind: 'refused', line: refusal };
    if (!announced) {
      announced = true;
      say(`Film ${film}: ${facts.title ?? '(no title)'}, ${lengthOf(facts.runtimeMs)}, on ${site}. `
        + 'Ctrl-C ends the run and writes the results.');
    }
    // The page is open when it has loaded: a document that arrives and a link that dies before the app's script does
    // is a blank page, with nothing to sample and no "Try again" to press.
    const opened = await orMiss(page, async () => {
      if (cancel.cancelled) return null;
      const loaded = nextLoad();
      const navigation = await page.send('Page.navigate', { url: `${site}/films/${film}/play?start=0` });
      if (navigation?.errorText) return null;
      if (!(await giveUpOn(loaded, LOAD_MS))) return null;
      // What loaded may be a gateway's error page: a restarting server is no answer, another error is a refusal.
      const status = cleanStatus(await page.evaluate(STATUS_EXPRESSION));
      if (RESTARTING.has(status)) return null;
      if (status !== null && status >= 400) {
        return { line: `The site would not open the player's page (status ${status}).` };
      }
      return {};
    });
    if (opened === null) return MISS;
    return opened.line ? { kind: 'refused', line: opened.line } : { kind: 'ready', film: facts };
  };
}

/** At most this many waiters are kept: an attempt that gives up on a load leaves its waiter behind. */
const MAX_WAITERS = 16;

/**
 * A function that gives a promise for the page's next load event (the listener is registered once). One load settles
 * every waiter there is, once; a waiter made afterwards waits for the next load. The oldest waiters beyond the last 16
 * are dropped (they never settle). `next.pending()` says how many are waiting.
 */
export function loadWaiter(page) {
  let waiting = [];
  page.on('Page.loadEventFired', () => {
    const now = waiting;
    waiting = [];
    for (const resolve of now) resolve();
  });
  const next = () => new Promise((resolve) => {
    waiting.push(resolve);
    if (waiting.length > MAX_WAITERS) waiting.shift();
  });
  next.pending = () => waiting.length;
  return next;
}

// ---- the run's constants --------------------------------------------------------------------------------------------

export const SAMPLE_MS = 5000;
export const LINE_MS = 30000;
/** How long the page, sent away, is given for its closing sends to be dealt with before Chrome is ended. */
export const END_WAIT_MS = 3000;
/** A look at the page gets this long to answer; this many looks in a row that could not be read end the run. */
export const LOOK_MS = 10000;
export const UNREAD_LIMIT = 3;
const NOT_AT_ALL = Number.POSITIVE_INFINITY;
const OWNER_FILE = 0o600;
/** The results are written again when this long has passed since the last write (by the run's own clock). */
export const KEEP_MS = 60000;

// ---- the results ---------------------------------------------------------------------------------------------------

const realFs = { openSync, writeSync, fsyncSync, closeSync };
const realAsyncFs = { open, rename, rm };

/**
 * Writes `text` to `file` and forces it to the disk before the file is closed (open, write until every byte is
 * written, fsync, close): a rename that reaches the disk before the data, or a write that stopped short, would leave
 * a bad file where the last good one stood. An error while closing does not hide the error that came first. `fs` is
 * for tests.
 */
export function writeSynced(file, text, fs = realFs) {
  const bytes = Buffer.from(text);
  const descriptor = fs.openSync(file, 'w', OWNER_FILE);
  try {
    let done = 0;
    while (done < bytes.length) {
      const wrote = fs.writeSync(descriptor, bytes, done, bytes.length - done);
      if (!(wrote > 0)) throw new Error('a write made no progress');
      done += wrote;
    }
    fs.fsyncSync(descriptor);
  } catch (error) {
    try {
      fs.closeSync(descriptor);
    } catch {
      // The first error is the one to report.
    }
    throw error;
  }
  fs.closeSync(descriptor);
}

/** The same without holding the process up (open, write the whole text, sync, close). `afs` is for tests. */
export async function writeSyncedAsync(file, text, afs = realAsyncFs) {
  const handle = await afs.open(file, 'w', OWNER_FILE);
  try {
    await handle.writeFile(text);
    await handle.sync();
  } catch (error) {
    await handle.close().catch(() => undefined);
    throw error;
  }
  await handle.close();
}

const realIo = { write: writeSynced, rename: renameSync, remove: (file) => rmSync(file, { force: true }) };

/** The folder name of a run: the local time it began, `YYYYMMDD-HHMMSS` (the form the logger's file name uses). */
export function runName(at) {
  const d = new Date(at);
  return `${d.getFullYear()}${two(d.getMonth() + 1)}${two(d.getDate())}-${two(d.getHours())}${two(d.getMinutes())}`
    + two(d.getSeconds());
}

// A file written whole: to a temporary name beside it (forced to the disk), then renamed onto the real one, so that a
// death in the middle of a write leaves the last good file. No temporary file is left when it fails.
function writePrivate(file, text, io) {
  const temporary = `${file}.tmp`;
  try {
    io.write(temporary, text);
    io.rename(temporary, file);
  } catch (error) {
    try {
      io.remove(temporary);
    } catch {
      // The first error is the one to report.
    }
    throw error;
  }
}

// The same, without holding the process up, with a temporary name of its own (a write made at the end, or by a second
// signal or a crash, cannot collide with one that is out). `superseded()` says the last write has been made: this one
// then renames nothing, so that an older file never lands over the last one.
async function writePrivateAsync(file, text, afs, superseded) {
  const temporary = `${file}.async.tmp`;
  try {
    await writeSyncedAsync(temporary, text, afs);
    if (superseded()) {
      await afs.rm(temporary, { force: true });
      return;
    }
    await afs.rename(temporary, file);
  } catch (error) {
    await afs.rm(temporary, { force: true }).catch(() => undefined);
    throw error;
  }
}

// What a write holds: the four files' text (built whole before anything is written), and the summary's lines.
function resultFiles({ run, report, segmentReport, facts, final }) {
  const readable = report.records.reduce((sum, viewing) => sum + viewing.count, 0);
  const files = {
    samples: run.samples,
    health: {
      sent: readable + report.notRead,
      notRead: report.notRead,
      viewings: report.records.map(({ id, count, latest }) => ({ id, count, latest })),
    },
    held: {
      run: { ...facts, ended: final ? run.ended : null },
      held: report.held,
      heldDropped: report.heldDropped,
      failed: report.failed,
      rejected: report.rejected,
      presses: run.presses,
      pageErrors: run.pageErrors,
      warnings: run.warnings,
    },
    segments: segmentReport,
  };
  const texts = Object.entries(files).map(([name, data]) => [`${name}.json`, JSON.stringify(data, null, 1)]);
  return { texts, lines: final ? summarise(files) : [] };
}

/**
 * Writes `samples.json`, `health.json`, `held.json` and `segments.json` (each whole) in a folder of `runs/` named from the time the run
 * began (never from an option): folder 700, files 600. They hold the samples, the playback records as the app sent
 * them (the guard's copy), what was held, the presses, the page's errors, the warnings and how the run ended: `ended`
 * is null until the last write (`final`), which also writes `summary.txt`. No header, no cookie, nothing of any other
 * request. Synchronous. Returns `{ dir, lines }` (the folder, and the summary's lines when final); throws when it
 * cannot be written. `io` (`write(file, text)`, `rename(from, to)`, `remove(file)`) is for tests.
 */
export function writeResults({
  runsDir, began, run, report, segmentReport = NO_SEGMENTS, facts, final = true, io = realIo,
}) {
  const dir = join(runsDir, runName(began));
  ownerDir(dir);
  const { texts, lines } = resultFiles({ run, report, segmentReport, facts, final });
  for (const [name, text] of texts) writePrivate(join(dir, name), text, io);
  if (final) writePrivate(join(dir, 'summary.txt'), `${lines.join('\n')}\n`, io);
  return { dir, lines };
}

/**
 * The write made while the run goes: the same four files (not the last write), written without holding the process
 * up, so that the guard goes on answering the page's paused requests meanwhile. `superseded()` says the last write has
 * been made (see `writePrivateAsync`). `afs` is for tests.
 */
export async function writeResultsAsync({
  runsDir, began, run, report, segmentReport = NO_SEGMENTS, facts, afs = realAsyncFs, superseded = () => false,
}) {
  const dir = join(runsDir, runName(began));
  ownerDir(dir);
  const { texts } = resultFiles({ run, report, segmentReport, facts, final: false });
  for (const [name, text] of texts) await writePrivateAsync(join(dir, name), text, afs, superseded);
  return { dir, lines: [] };
}

const NO_SEGMENTS = { cap: 0, capped: false, dropped: 0, playlists: 0, unseen: 0, segments: [] };
const NO_REPORT = { held: [], heldDropped: 0, records: [], notRead: 0, failed: 0, rejected: 0 };

/**
 * The writing of the results as the run goes. `keep()` is awaited after each sample: it writes (asynchronously, one at
 * a time, never a second while one is out) with the first sample, then whenever a minute has passed since the last
 * write; a write that fails is one warning kept with the run (not one a minute) and the run goes on. `final(word)` is
 * the last write: synchronous (it must finish before the process ends), once however many of the crash handler, a
 * second signal and the end call it, and only when there is at least one sample (`word` says how the run ended, used
 * only while it has no ending yet); its failure is thrown.
 */
export function resultsWriter(ctx, { began, runsDir }) {
  const state = { last: null, done: false, warned: false, busy: false };
  const parts = () => {
    const { run, opt, deps } = ctx;
    const facts = {
      film: opt.film, title: run.film?.title ?? null, runtimeMs: run.film?.runtimeMs ?? null, site: opt.site,
      beganAt: began, runMs: ctx.opened === null ? null : Math.round(deps.clock.mono() - ctx.opened),
    };
    return {
      runsDir, began, run, report: ctx.guard?.report() ?? NO_REPORT,
      segmentReport: ctx.segments?.report() ?? NO_SEGMENTS, facts,
    };
  };
  return {
    async keep() {
      const now = ctx.deps.clock.mono();
      if (state.done || state.busy || (state.last !== null && now - state.last < KEEP_MS)) return;
      state.last = now;
      state.busy = true;
      try {
        await writeResultsAsync({ ...parts(), afs: ctx.deps.afs, superseded: () => state.done });
      } catch (error) {
        if (!state.warned) {
          ctx.run.warnings.push(printable(`the results could not be written: ${error?.message ?? error}`, 300));
        }
        state.warned = true;
      } finally {
        state.busy = false;
      }
    },
    // The last write also clears the temporary files of a mid-run write that may be out, and clears them BEFORE it
    // writes: a rename and a removal cannot interleave, so an out write's rename either came before the removal (the
    // last write then lands over it) or comes after (it finds no file and fails, and its own catch tidies up). Each
    // removal is best effort. Still possible: an out write whose `open` runs after both removals, in a process that
    // ends before that write tidies up, leaves one `.async.tmp` behind; it is harmless.
    final(word) {
      const { run } = ctx;
      if (state.done) return null;
      if (run.ended === null && word) run.ended = word;
      if (run.samples.length === 0) return null;
      state.done = true;
      const io = { ...realIo, ...ctx.deps.io };
      const sweep = () => {
        for (const name of ['samples', 'health', 'held', 'segments']) {
          try {
            io.remove(join(runsDir, runName(began), `${name}.json.async.tmp`));
          } catch {
            // Best effort: the others, and the last write, go on.
          }
        }
      };
      sweep();
      try {
        return writeResults({ ...parts(), final: true, io });
      } finally {
        sweep();
      }
    },
  };
}

// ---- the end -------------------------------------------------------------------------------------------------------

/**
 * Ends Chrome (decision 3), in this order: the page, with the guard still attached, is sent to `about:blank`; the
 * closing sends it makes are given three seconds to be dealt with; only then is Chrome closed. When the page cannot be
 * sent away (it is closed, the send rejects or throws, or Chrome answers it with an `errorText`) Chrome is
 * hard-stopped before anything else, then closed. Never throws.
 */
export async function leave({ chrome, held, pause = sleep }) {
  const hardStop = () => {
    try {
      held.chromeKill?.();
    } catch {
      // Nothing more can be done about a Chrome that will not die.
    }
  };
  if (!chrome) {
    hardStop();
    return;
  }
  let away = false;
  try {
    if (!chrome.page.closed) {
      const navigation = await chrome.page.send('Page.navigate', { url: 'about:blank' });
      // An answer with an `errorText` means the player's page is still there, and so are its closing sends.
      away = !navigation?.errorText;
    }
  } catch {
    // Not sent away: handled just below.
  }
  if (away) await pause(END_WAIT_MS);
  else hardStop();
  try {
    await chrome.close();
  } catch {
    hardStop();
  }
  held.chromeKill = null;
}

// What a second signal does, in this order: a hard stop of Chrome, the results written as far as there are any
// (`held.writeNow(word)`: `word` says how the run ended, for a run that has not), the lock let go. Each step on its
// own, so that one failing skips none of the others.
function stopAtOnce(held, word) {
  for (const step of [() => held.chromeKill?.(), () => held.writeNow?.(word), () => held.release?.()]) {
    try {
      step();
    } catch {
      // The rest still run.
    }
  }
}

/**
 * The signals of a run (`SIGINT`, `SIGTERM`, `SIGHUP`). The first ends the run in order (`cancel`); a second quits at
 * once, but not before what the run has seen is kept: a hard stop of Chrome, the results written as far as there are
 * any (`held.writeNow('signal')`), the lock let go, then `exit(130)`. It does so whatever the run is doing,
 * in the three seconds it gives the page's closing sends too. `exit` and `listen` are there for tests.
 */
export function watchRunSignals(cancel, held, say, {
  exit = (code) => process.exit(code), listen = (name, fn) => process.on(name, fn),
} = {}) {
  const onSignal = () => {
    if (cancel.cancelled) {
      stopAtOnce(held, 'signal');
      exit(130);
      return;
    }
    try {
      say('stopping (a second Ctrl-C quits at once)');
    } catch {
      // The terminal may be gone (SIGHUP): the run is still ended.
    }
    cancel.trigger();
  };
  for (const name of ['SIGINT', 'SIGTERM', 'SIGHUP']) listen(name, onSignal);
}

// ---- the run -------------------------------------------------------------------------------------------------------

// Notes the page's errors as the replay does (at most 100, each through `printable`).
function listenToPage(page, run) {
  page.on('Runtime.exceptionThrown', (p) => {
    notePageError(run.pageErrors, p.exceptionDetails?.exception?.description ?? p.exceptionDetails?.text);
  });
  page.on('Runtime.consoleAPICalled', (p) => {
    if (p.type === 'error' || p.type === 'warning') notePageError(run.pageErrors, `${p.type}: ${consoleText(p.args)}`);
  });
}

// One look at the page, given 10 s to answer. It reads the page itself (no request to the site), so a look that fails
// or is not answered is a page that is not answering, never a dead link: a renderer that has crashed leaves the pipe
// open and an `evaluate` on it is simply never answered. Gives `{ sample, read }`; a look that could not be read is a
// sample with its error text, as in the replay.
async function observe({ page, deps }, opened) {
  const look = (async () => page.evaluate(VIEWER_SAMPLE))().then((value) => ({ value }), (error) => ({ error }));
  let raw = { video: false, error: `no answer in ${LOOK_MS / 1000} s` };
  let read = false;
  if (await deps.within(look, LOOK_MS)) {
    const answer = await look;
    read = !('error' in answer);
    raw = read ? answer.value : { video: false, error: String(answer.error?.message ?? answer.error) };
  }
  const sample = cleanViewerSample(raw, { sinceMs: deps.clock.mono() - opened, at: deps.clock.wall() });
  return { sample, read };
}

// A press: true when the button was there. A page that cannot be reached is no press.
async function press(page, expression) {
  try {
    return (await page.evaluate(expression)) === true;
  } catch {
    return false;
  }
}

// What the viewer presses, and nothing else: the "Where are you watching?" card once (the `--place` answer, else "Not
// now"), and a "Try again" of the two at most once a sample.
async function pressWhatIsAsked({ page, run, opt }, sample) {
  if (!run.placeTried && PLACE_CARD.test(sample.card ?? '')) {
    run.placeTried = true;
    const label = PLACE_BUTTON[opt.place ?? 'none'];
    if (await press(page, pressPlace(label))) run.presses.place = label;
  }
  if (sample.retry && (await press(page, PRESS_RETRY))) run.presses.retries += 1;
}

// Looks every five seconds until something ends the run; gives the word of `ENDS`.
async function watchFilm(ctx, opened) {
  const { deps, run, page, opt } = ctx;
  const { clock, wait, say, cancel } = deps;
  const endAt = opt.minutes === null ? NOT_AT_ALL : opened + opt.minutes * 60000;
  let lastLine = -NOT_AT_ALL;
  let lastLoop = clock.wall();
  for (;;) {
    await wait(Math.min(SAMPLE_MS, Math.max(0, endAt - clock.mono())));
    if (cancel.cancelled) return 'signal';
    const warning = standStill(clock.wall() - lastLoop, Math.round((clock.mono() - opened) / 1000));
    if (warning) run.warnings.push(warning);
    lastLoop = clock.wall();
    const { sample, read } = await observe(ctx, opened);
    run.samples.push(sample);
    await ctx.keep?.();
    run.unread = read ? 0 : run.unread + 1;
    const over = page.closed ? 'chrome-gone' : endOf(sample);
    if (over) return over;
    // Three looks in a row that could not be read: the page has stopped answering, and the viewer does not reload it.
    if (run.unread >= UNREAD_LIMIT) return 'page-crashed';
    // A look can take 10 seconds: a signal that came meanwhile is not followed by one more press and line.
    if (cancel.cancelled) return 'signal';
    await pressWhatIsAsked(ctx, sample);
    if (clock.mono() - lastLine >= LINE_MS) {
      lastLine = clock.mono();
      say(progressLine(sample));
    }
    if (clock.mono() >= endAt) return 'minutes';
  }
}

// What a loop that did not end in `ready` means for the run: the word of `ENDS` (after saying why), or null to play.
function endBeforePlaying(outcome, say) {
  if (outcome.kind === 'ready') return null;
  if (outcome.kind === 'cancelled') return 'signal';
  say(outcome.kind === 'gave-up' ? 'The site did not answer in 10 minutes.' : outcome.line);
  return 'refused';
}

// Starts Chrome and the guard (before any address is opened), then does the checks and plays. Sets `run.ended`.
async function play(ctx) {
  const { deps, run, opt, held } = ctx;
  const { say, clock } = deps;
  const chrome = await deps.launch({
    profile: viewerPaths(deps.viewerHome).profile, channel: 'pipe', ownGroup: true,
    onSpawn: (kill) => {
      held.chromeKill = kill;
    },
  });
  ctx.chrome = chrome;
  ctx.page = chrome.page;
  ctx.guard = await deps.guard(ctx.page, { site: opt.site });
  ctx.segments = await deps.segments(ctx.page, { site: opt.site });
  await ctx.page.send('Page.enable');
  await ctx.page.send('Runtime.enable');
  listenToPage(ctx.page, run);
  // Every round of the wait navigates, which gives a page whose renderer has died a new one: nothing special is needed.
  const outcome = deps.cancel.cancelled ? { kind: 'cancelled' } : await waitUntilReady({
    attempt: makeAttempt({
      page: ctx.page, site: opt.site, film: opt.film, nextLoad: loadWaiter(ctx.page), giveUpOn: deps.within, say,
      cancel: deps.cancel,
    }),
    wait: deps.wait, now: clock.mono, say, cancel: deps.cancel,
  });
  const stopped = endBeforePlaying(outcome, say);
  if (stopped) {
    run.ended = stopped;
    return;
  }
  run.film = outcome.film;
  ctx.opened = clock.mono();
  run.ended = await watchFilm(ctx, ctx.opened);
}

/**
 * The whole run, with what it touches passed in (`deps`): `opt` (the options), `viewerHome`, `say`, `cancel`
 * (as `createCancel`), `held` (where what a signal or a crash must stop is kept), `clock` (`mono` and `wall`), `wait`
 * (cut short by a signal), `pause` (not), and the steps: `launch`, `guard`, `lock`, `folders`, `free`, `awake`,
 * `within`, `writeNow`, `io`. Returns the exit code. Whatever happens, Chrome is ended in the order of `leave`, the
 * results are written (when there is at least one sample) and the lock is let go before it returns.
 */
export async function viewing(deps) {
  const { opt, viewerHome, held, pid, isAlive = processAlive } = deps;
  const full = withDefaults(deps);
  const { say } = full;
  const run = {
    samples: [], pageErrors: [], warnings: [], presses: { place: null, retries: 0 }, placeTried: false, ended: null,
    film: null, unread: 0,
  };
  const ctx = {
    deps: full, run, opt, held, chrome: null, page: null, guard: null, segments: null, opened: null,
  };
  held.run = run;
  const writer = resultsWriter(ctx, { began: full.clock.wall(), runsDir: join(viewerHome, 'runs') });
  held.writeNow = deps.writeNow ?? writer.final; // (`deps.writeNow` is a test's own writer)
  ctx.keep = deps.writeNow ? undefined : writer.keep;
  try {
    held.release = full.lock(viewerHome, { isAlive, pid });
    full.folders(viewerHome);
    ownerDir(join(viewerHome, 'runs'));
    full.free(viewerPaths(viewerHome).profile, { isAlive });
    full.awake();
    await play(ctx);
  } catch (error) {
    say(`The run failed: ${error?.message ?? error}`);
    run.ended = endOfFailure(error, deps.cancel);
  } finally {
    await leave({ chrome: ctx.chrome, held, pause: full.pause });
  }
  return finish({ ctx, say });
}

// How a run that threw ended: by a signal if one had come, Chrome gone if the error says so, else failed.
function endOfFailure(error, cancel) {
  if (cancel.cancelled) return 'signal';
  return error?.code === 'chrome-gone' ? 'chrome-gone' : 'failed';
}

// What a caller passes, with the real thing for what it leaves out; printing that throws is quiet.
function withDefaults(deps) {
  const { cancel } = deps;
  return {
    ...deps,
    say: (text) => {
      try {
        deps.say(text);
      } catch {
        // The terminal may be gone: printing never stops a run.
      }
    },
    clock: deps.clock ?? { mono: () => performance.now(), wall: () => Date.now() },
    wait: deps.wait ?? ((ms) => cancel.wait(ms)),
    within: deps.within ?? within,
    launch: deps.launch ?? launchChrome,
    guard: deps.guard ?? attachGuard,
    segments: deps.segments ?? attachSegments,
    lock: deps.lock ?? takeLock,
    folders: deps.folders ?? makeFolders,
    free: deps.free ?? checkProfileFree,
    awake: deps.awake ?? keepAwake,
    pause: deps.pause ?? sleep,
  };
}

// After Chrome has gone: the last write (`held.writeNow`), how the run ended, and the lock let go, in that order.
// Gives the exit code.
function finish({ ctx, say }) {
  const { run, held } = ctx;
  let code = exitCode(run.ended, ctx.opened !== null);
  try {
    const written = held.writeNow?.();
    if (written?.dir) {
      for (const line of written.lines) say(line);
      say(`results: ${written.dir}`);
    } else if (run.samples.length === 0) {
      say('nothing was written: no sample was taken');
    }
  } catch (error) {
    say(`the results could not be written: ${error?.message ?? error}`);
    code = 1;
  }
  say(`${(ENDS[run.ended] ?? ENDS.failed).line} ${run.samples.length} samples taken.`);
  try {
    held.release?.();
  } catch {
    // Left for the next start to take over.
  }
  held.release = null;
  return code;
}
