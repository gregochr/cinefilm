// The train viewer: it opens the real CineFilm player on the real site, in a Chrome with no window, and lets it play,
// so that a train journey gives a playback record with nobody watching. It is a measuring tool run by hand, as the
// home replay is: not part of the app, in no image (docs/engineering/train-viewer-plan.md).
//
// This version only signs in; the run comes in a later change.
//
//   node scripts/replay/viewer.mjs --sign-in [--site https://cinefilm.chrisjgregory.dev]
//
//   --sign-in   opens an ordinary Chrome window on the site, on the viewer's own profile. Sign in there, come back
//               and press Return (quitting that Chrome with Cmd-Q does the same). The viewer then keeps the sign-in
//               (35 seconds: Chrome writes a new cookie to disk at its next 30-second commit), ends that Chrome and
//               has a headless one open <site>/api/session to say whether the site takes the profile as signed in.
//               The exit code is 0 when it does, 1 when it does not, when the site did not answer or when the
//               answer was not understood.
//   --site      the site's address: https://<host>[:port], or http://127.0.0.1[:port] or http://localhost[:port]
//               (a stand-in on this machine). A sign-in belongs to one host, so another address needs its own.
//
// What is kept, where: ~/cinefilm-viewer/ (readable by the owner alone) holds profile/, the Chrome profile whose
// cookie is the sign-in (encrypted by the login keychain: no start of Chrome on it passes --use-mock-keychain), and
// lock, a file holding the id of the process using the profile, so that two viewers do not run together; a lock whose
// process has gone is taken over. No Chrome starts while Chrome's own lock in the profile names a live process: that
// is what finally keeps two Chromes off the profile. Removing profile/ signs the viewer out of this laptop. The
// window is started with no debugging port or pipe, so this script cannot read the page while the password is typed,
// and the profile is made with Chrome's offer to save passwords turned off. The script never reads, prints or stores
// a cookie or a password.
//
// Ctrl-C (or SIGTERM, or the terminal closing) ends the sign-in: both Chromes are stopped, the lock is let go and the
// exit code is 130. A second one quits at once, after a hard stop of both.
//
// The options, the lock, the folders, the answer and the order of the steps are tested, with the window, Chrome, the
// keyboard and the clock made up; `main`, which starts the real Chrome, cannot run in CI and is checked by hand.
import {
  chmodSync, linkSync, mkdirSync, readFileSync, readlinkSync, renameSync, rmSync, writeFileSync,
} from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { createInterface } from 'node:readline';
import { fileURLToPath } from 'node:url';
import { launchChrome, openWindow, within } from './cdp.mjs';
import { printable } from './text.mjs';

export const DEFAULT_SITE = 'https://cinefilm.chrisjgregory.dev';
/** Chrome writes a new cookie to disk at its next 30-second commit; a Chrome ended sooner loses it. */
export const SETTLE_MS = 35000;
const LINE_LIMIT = 300;
const LOAD_TIMEOUT_MS = 30000;
const FIRST_PREFERENCES = '{"credentials_enable_service":false,"profile":{"password_manager_enabled":false}}';
const OWNER_DIR = 0o700;
const OWNER_FILE = 0o600;

// ---- options ------------------------------------------------------------------------------------------------------

// The address as `https://<host>[:port]`, with nothing after it but a slash: no user name, path, query or fragment.
const SITE_SHAPE = /^https?:\/\/[^/?#@\\\s]+\/?$/i;
const LOCAL_HOSTS = new Set(['127.0.0.1', 'localhost']);

/** The origin (scheme, host, port, no trailing slash) of a `--site` value; throws on what is not accepted. */
export function siteOrigin(text) {
  const refused = new Error('--site must be https://<host>[:port], or http://127.0.0.1 or http://localhost here');
  if (!SITE_SHAPE.test(text)) throw refused;
  let url;
  try {
    url = new URL(text);
  } catch {
    throw refused;
  }
  if (url.protocol === 'http:' && !LOCAL_HOSTS.has(url.hostname)) throw refused;
  return url.origin;
}

/** The options from the command line's arguments (without the program's own two), checked. Throws on any refusal. */
export function options(argv) {
  const given = new Map();
  for (let i = 0; i < argv.length; ) {
    const name = argv[i].startsWith('--') ? argv[i].slice(2) : null;
    if (name !== 'sign-in' && name !== 'site') throw new Error(`unknown option ${printable(argv[i], 40)}`);
    if (given.has(name)) throw new Error(`--${name} was given twice`);
    if (name === 'sign-in') {
      given.set(name, true);
      i += 1;
      continue;
    }
    const value = argv[i + 1];
    if (value === undefined || value.startsWith('--')) throw new Error(`--${name} needs a value`);
    given.set(name, value);
    i += 2;
  }
  return { signIn: given.has('sign-in'), site: siteOrigin(given.get('site') ?? DEFAULT_SITE) };
}

// ---- the folders, the first preferences, the lock -----------------------------------------------------------------

/** Makes `dir` (and what is above it), and sets it to the owner alone whatever it was before. */
function ownerDir(dir) {
  mkdirSync(dir, { recursive: true, mode: OWNER_DIR });
  chmodSync(dir, OWNER_DIR);
}

/** The folders under the viewer's home: `{ profile, lock }`, as paths. */
export function viewerPaths(viewerHome) {
  return { profile: join(viewerHome, 'profile'), lock: join(viewerHome, 'lock') };
}

/**
 * Makes the viewer's home and its `profile/` for the owner alone (mode 700, narrowed when they were wider), and, when
 * the profile has no preferences yet, writes the ones that turn off Chrome's offer to save passwords. A preferences
 * file that is there is never touched.
 */
export function makeFolders(viewerHome) {
  const { profile } = viewerPaths(viewerHome);
  ownerDir(viewerHome);
  ownerDir(profile);
  const defaults = join(profile, 'Default');
  ownerDir(defaults);
  try {
    writeFileSync(join(defaults, 'Preferences'), FIRST_PREFERENCES, { flag: 'wx', mode: OWNER_FILE });
  } catch (error) {
    if (error?.code !== 'EEXIST') throw error;
  }
}

/** Whether a process with this id is there: signalling it with 0 works, or is refused for want of permission. */
export function processAlive(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    return error?.code === 'EPERM';
  }
}

// The process id a lock file holds; null when the file is there but holds anything else, and undefined when there is
// no file (`takeLock` depends on the difference: a file that is not a process id is taken over, no file is a lock
// that has just been let go).
function holder(lock) {
  try {
    const text = readFileSync(lock, 'utf8').trim();
    return /^\d{1,10}$/.test(text) && Number(text) > 0 ? Number(text) : null;
  } catch (error) {
    if (error?.code === 'ENOENT') return undefined;
    throw error;
  }
}

// A fresh take in one step, so that no reader ever sees an empty lock: `pid` is written to a temporary file beside
// the lock, which is hard-linked onto it (that fails with EEXIST when the lock is there), and the temporary file is
// removed.
function createLock(lock, pid, linkFile) {
  const temporary = `${lock}.${pid}.tmp`;
  writeFileSync(temporary, String(pid), { mode: OWNER_FILE });
  try {
    linkFile(temporary, lock);
    return true;
  } catch (error) {
    if (error?.code === 'EEXIST') return false;
    throw error;
  } finally {
    rmSync(temporary, { force: true });
  }
}

/**
 * Takes the lock on the profile: creates `lock` whole and exclusively, holding `pid`. When it is there and the process
 * it names is alive (`isAlive`), throws, naming that process. When that process has gone, or the file holds no process
 * id, the lock is taken over: `pid` is written to a temporary file beside it, which is renamed onto `lock`, and `lock`
 * is read back (`readHolder`); a start that finds another process's id throws as if the lock were held. The read-back
 * narrows the race between two starts that both found a stale lock and does not close it: the tool is started by
 * hand, and Chrome's own lock in the profile, checked before every start of Chrome (`checkProfileFree`), is the final
 * guard. Returns `release()`, which removes the file only while it still holds `pid`.
 */
export function takeLock(viewerHome, {
  pid = process.pid, isAlive = processAlive, readHolder = holder, linkFile = linkSync,
} = {}) {
  ownerDir(viewerHome);
  const { lock } = viewerPaths(viewerHome);
  const release = () => (readHolder(lock) === pid ? rmSync(lock, { force: true }) : undefined);
  if (createLock(lock, pid, linkFile)) return release;
  const other = readHolder(lock);
  if (other && isAlive(other)) throw new Error(`another viewer is running (process ${other})`);
  const temporary = `${lock}.${pid}.tmp`;
  writeFileSync(temporary, String(pid), { mode: OWNER_FILE });
  renameSync(temporary, lock);
  const now = readHolder(lock);
  if (now !== pid) throw new Error(`another viewer has just taken the lock${now ? ` (process ${now})` : ''}`);
  return release;
}

/**
 * Refuses while Chrome's own lock in the profile names a live process: a window left behind by a viewer that was
 * killed still uses the profile. Chrome keeps a symbolic link `SingletonLock` whose target ends `-<process id>`; it
 * is read, never followed. No link, a target that does not parse, or a process that has gone: go on.
 */
export function checkProfileFree(profile, { isAlive = processAlive } = {}) {
  let target;
  try {
    target = readlinkSync(join(profile, 'SingletonLock'));
  } catch (error) {
    if (error?.code === 'ENOENT' || error?.code === 'EINVAL') return;
    throw error;
  }
  const pid = /-(\d{1,10})$/.exec(target)?.[1];
  if (pid && Number(pid) > 0 && isAlive(Number(pid))) {
    throw new Error(
      `a Chrome is still using the viewer's profile (process ${pid}): quit it first, or if none is running, `
      + `remove the link ${join(profile, 'SingletonLock')}`,
    );
  }
}

// ---- the check ----------------------------------------------------------------------------------------------------

/**
 * What `<site>/api/session` said, read from its text: the server answers `{"authenticated":true}` or
 * `{"authenticated":false}` (the `Status` record of `SessionController`). An object whose one key is `authenticated`,
 * a boolean, is `signed-in` or `signed-out`; anything else is `unknown`, the old `signedIn` shape included.
 */
export function readAnswer(text) {
  let answer;
  try {
    answer = JSON.parse(String(text).trim());
  } catch {
    return 'unknown';
  }
  const keys = answer !== null && typeof answer === 'object' ? Object.keys(answer) : [];
  if (keys.length !== 1 || keys[0] !== 'authenticated' || typeof answer.authenticated !== 'boolean') {
    return 'unknown';
  }
  return answer.authenticated ? 'signed-in' : 'signed-out';
}

/** The plain line to say and the exit code for each answer (`no-answer` is a failed navigation). */
export function resultOf(answer, site) {
  switch (answer) {
    case 'signed-in':
      return { line: 'Signed in. The sign-in lasts until the server restarts or about 30 days pass.', code: 0 };
    case 'signed-out':
      return {
        line: 'Not signed in. Run --sign-in again, and press Return only once the site shows the films.', code: 1,
      };
    case 'no-answer':
      return { line: `The site did not answer at ${site}, so the sign-in could not be checked.`, code: 1 };
    default:
      return { line: 'The site\'s answer was not understood, so the sign-in could not be checked.', code: 1 };
  }
}

/**
 * What the page says, as one expression for `evaluate`: the text of `<pre>` directly in the body when it is there (a
 * browser's JSON viewer may add its own words to the body), else the body's text, else nothing.
 */
export const PAGE_TEXT = '(() => { const pre = document.querySelector("body > pre"); '
  + 'if (pre) return pre.textContent; return document.body ? document.body.innerText : ""; })()';

/**
 * Has headless Chrome open `<site>/api/session` (a JSON document: none of the app runs, so nothing can be written)
 * on the kept profile, reads its text and stops Chrome. Returns `signed-in`, `signed-out`, `unknown` or `no-answer`
 * (the navigation failed, or the page did not load in 30 s). `onSpawn` is passed to `launch` (the hard stop).
 */
export async function checkSession({ site, profile, launch = launchChrome, onSpawn }) {
  const chrome = await launch({ profile, channel: 'pipe', ownGroup: true, onSpawn });
  try {
    const { page } = chrome;
    const loaded = new Promise((resolve) => page.on('Page.loadEventFired', resolve));
    await page.send('Page.enable');
    const navigation = await page.send('Page.navigate', { url: `${site}/api/session` });
    if (navigation?.errorText) return 'no-answer';
    if (!(await within(loaded, LOAD_TIMEOUT_MS))) return 'no-answer';
    return readAnswer(await page.evaluate(PAGE_TEXT));
  } finally {
    await chrome.close();
  }
}

// ---- stopping -----------------------------------------------------------------------------------------------------

/** A flag that a signal sets, a promise that settles then, and a wait that it cuts short. */
export function createCancel() {
  let cancelled = false;
  let wake = null;
  let settle;
  const promise = new Promise((resolve) => {
    settle = resolve;
  });
  return {
    promise,
    get cancelled() {
      return cancelled;
    },
    trigger() {
      cancelled = true;
      settle();
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
        if (cancelled) done();
      });
    },
  };
}

/**
 * Resolves when a line has been read from `input` (Return), or when it has ended (an ended input counts as Return).
 * Resolves once, and lets go of the input.
 */
export function waitForReturn(input) {
  return new Promise((resolve) => {
    const lines = createInterface({ input });
    const done = () => {
      lines.close();
      resolve();
    };
    lines.once('line', done);
    lines.once('close', resolve);
  });
}

// ---- the sign-in --------------------------------------------------------------------------------------------------

// Stops what the sign-in started and has not stopped: the window (kindly, and hard if that fails), the check's Chrome
// (hard), then the lock. Each field is cleared only when its own step is done: stopping the window can take six
// seconds, and a second signal in that time must still find all three held (see `watchSignals`).
async function cleanUp(held) {
  try {
    const { window } = held;
    if (window) await window.stop().catch(() => window.kill());
    held.window = null;
  } finally {
    try {
      held.chromeKill?.();
      held.chromeKill = null;
    } finally {
      held.release?.();
      held.release = null;
    }
  }
}

// Return, the window's Chrome going, or a signal: whichever comes first.
async function waitForOwner({ waitReturn, window, cancel }) {
  return Promise.race([
    waitReturn().then(() => 'return'),
    window.exited.then(() => 'window'),
    cancel.promise.then(() => 'cancelled'),
  ]);
}

// The check, given up on when a signal comes (its Chrome is then stopped by `cleanUp`).
async function checkUnlessCancelled({ site, profile, held, check, cancel }) {
  const checking = check({ site, profile, onSpawn: (kill) => {
    held.chromeKill = kill;
  } });
  checking.catch(() => undefined);
  const answer = await Promise.race([checking, cancel.promise.then(() => 'cancelled')]);
  if (answer !== 'cancelled') held.chromeKill = null;
  return answer;
}

/**
 * The whole sign-in, with what it touches passed in: `say` (a line), `open` (the window, as `openWindow`), `check` (as
 * `checkSession`), `waitReturn`, `wait(ms)`, `cancel` (as `createCancel`), `isAlive`, `held` (where what a second
 * signal must stop is kept). Returns the exit code: 0 signed in, 1 not or not checked, 130 when a signal ended it.
 * Whatever happens, both Chromes are stopped and the lock is let go before it returns.
 */
export async function signIn(deps) {
  const {
    viewerHome, site, say, open = openWindow, check = checkSession, waitReturn, cancel, isAlive, pid,
    wait = (ms) => cancel.wait(ms), held = {},
  } = deps;
  try {
    held.release = takeLock(viewerHome, { isAlive, pid });
    makeFolders(viewerHome);
    const { profile } = viewerPaths(viewerHome);
    checkProfileFree(profile, { isAlive });
    say(`A Chrome window is opening on ${site}. Sign in there with your password.`);
    say('Then come back here and press Return once the site shows the films');
    say('(quitting that Chrome with Cmd-Q does the same).');
    held.window = open({ profile, address: site });
    const first = await waitForOwner({ waitReturn, window: held.window, cancel });
    if (first === 'return') {
      say(`Keeping the sign-in: this takes ${SETTLE_MS / 1000} seconds. Leave the window as it is.`);
      await wait(SETTLE_MS);
    }
    if (cancel.cancelled) return 130;
    await held.window.stop();
    held.window = null;
    if (cancel.cancelled) return 130; // a signal while the window was being stopped: no Chrome on the profile now
    checkProfileFree(profile, { isAlive });
    const answer = await checkUnlessCancelled({ site, profile, held, check, cancel });
    if (answer === 'cancelled') return 130;
    const result = resultOf(answer, site);
    say(result.line);
    return result.code;
  } catch (error) {
    say(`The sign-in failed: ${error?.message ?? error}`);
    return cancel.cancelled ? 130 : 1;
  } finally {
    await cleanUp(held);
  }
}

/**
 * The first signal (`SIGINT`, `SIGTERM` or `SIGHUP`) ends the sign-in; a second one quits at once, after a hard stop of
 * what is held (also while `cleanUp` is still stopping the window). `exit` and `listen` are there for tests.
 */
export function watchSignals(cancel, held, say, {
  exit = (code) => process.exit(code), listen = (name, fn) => process.on(name, fn),
} = {}) {
  const onSignal = () => {
    if (cancel.cancelled) {
      held.window?.kill();
      held.chromeKill?.();
      held.release?.();
      exit(130);
      return;
    }
    say('stopping (a second Ctrl-C quits at once)');
    cancel.trigger();
  };
  for (const name of ['SIGINT', 'SIGTERM', 'SIGHUP']) listen(name, onSignal);
}

/**
 * What an `uncaughtException` or an `unhandledRejection` does: a hard stop of both Chromes and the lock let go (each
 * step on its own, so that one failing does not skip the rest), a line, then `exit(1)`. Returns the handler.
 */
export function crashHandler(held, { say: tell, exit }) {
  return (error) => {
    for (const step of [() => held.window?.kill(), () => held.chromeKill?.(), () => held.release?.()]) {
      try {
        step();
      } catch {
        // The rest still run.
      }
    }
    try {
      tell(`The viewer stopped on an unexpected error: ${error?.message ?? error}`);
    } catch {
      // The terminal may be gone: the exit still happens.
    }
    exit(1);
  };
}

function say(text) {
  console.log(printable(text, LINE_LIMIT));
}

async function main(argv) {
  let opt;
  try {
    opt = options(argv);
  } catch (error) {
    console.error(printable(error.message, LINE_LIMIT));
    return 1;
  }
  if (!opt.signIn) {
    say('This version only signs in: run it with --sign-in.');
    return 1;
  }
  const cancel = createCancel();
  const held = {};
  watchSignals(cancel, held, say);
  const crashed = crashHandler(held, { say, exit: (code) => process.exit(code) });
  process.on('uncaughtException', crashed);
  process.on('unhandledRejection', crashed);
  return signIn({
    viewerHome: join(homedir(), 'cinefilm-viewer'), site: opt.site, say, waitReturn: () => waitForReturn(process.stdin),
    cancel, held,
  });
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  process.exit(await main(process.argv.slice(2)));
}
