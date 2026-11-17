// The train viewer's run (docs/engineering/train-viewer-plan.md, decisions 7 to 9): what the viewer reads from the
// player's page, what it presses in it, what it checks before it plays and what each end of a run is called. This part
// is the pieces; the run that uses them is added to this module in the next change. Everything here is a function
// of what it is given, so that it can be tested with no Chrome, no site and no clock.
import { SAMPLE, cleanSample, standStillWarning } from './drive.mjs';
import { printable } from './text.mjs';
import { PAGE_TEXT, readAnswer } from './viewer.mjs';

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
/** A server that is restarting (bad gateway, unavailable, gateway timeout) is waited for, not refused. */
const RESTARTING = new Set([502, 503, 504]);

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
  return readAnswer(await page.evaluate(PAGE_TEXT));
}

/**
 * The `attempt` for `waitUntilReady`: the session check, then the film's facts (`film` is the number), then the
 * opening of `<site>/films/<film>/play?start=0`, with `say` saying the film just before that page opens (once, however
 * often the loop goes round). `nextLoad()` gives a promise for the page's next load event, `giveUpOn(promise, ms)`
 * says whether it settled in time (both pages, the session's and the player's, must load in 30 s). A page that is
 * closed is a failure with `code: 'chrome-gone'`; a film that is not a number throws, whatever the page is doing; it
 * throws at once without `cancel`. An attempt that a signal (`cancel`) has given up on goes on to its next await at
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
      return (await giveUpOn(loaded, LOAD_MS)) ? true : null;
    });
    return opened === null ? MISS : { kind: 'ready', film: facts };
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
