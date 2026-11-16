// A small client for Chrome's debugging protocol: start a headless Chrome with its own throwaway profile, and
// talk to one page. Node has WebSocket and fetch built in, so nothing is installed.
//
// Launching Chrome and the socket need the installed Chrome and cannot run in CI; they are checked by hand
// (docs/engineering/home-replay.md). The matching of replies to requests and of events to listeners is `routeMessage`,
// and the bookkeeping of requests that are waiting is `track` and `failAll`: all three take plain values, so they are
// tested with made-up messages.
import { spawn } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { join } from 'node:path';

export const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const REQUEST_TIMEOUT_MS = 30000;
const SOCKET_OPEN = 1;
export const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * Handles one message from Chrome, given as the text that arrived. `pending` maps a request's number to
 * `{ method, resolve, reject }`, `listeners` maps an event's method to a list of functions.
 *  - A reply with a result settles its request and removes it from `pending`; a reply with an error rejects it, with
 *    an error that names the method.
 *  - An event goes to every listener of its method; a listener that throws does not stop the others.
 * Returns what the message was: `reply`, `error`, `event`, `unknown` (a reply to a request nobody waits for any
 * more) or `ignored` (not JSON, or not an object).
 */
export function routeMessage(raw, pending, listeners) {
  let message;
  try {
    message = JSON.parse(raw);
  } catch {
    return 'ignored';
  }
  if (message === null || typeof message !== 'object') return 'ignored';
  if (message.id === undefined) return deliverEvent(message, listeners);
  const waiter = pending.get(message.id);
  if (!waiter) return 'unknown';
  pending.delete(message.id);
  if (message.error) {
    waiter.reject(new Error(`${waiter.method}: ${JSON.stringify(message.error)}`));
    return 'error';
  }
  waiter.resolve(message.result);
  return 'reply';
}

function deliverEvent(message, listeners) {
  for (const listener of listeners.get(message.method) ?? []) {
    try {
      listener(message.params);
    } catch {
      // A listener's own failure is its own: the others still hear the event.
    }
  }
  return 'event';
}

/**
 * Registers request `id` as waiting and returns the promise of its reply. It rejects, naming the method, when no
 * reply came within `timeoutMs`; the request is then no longer waiting, so a reply that comes later is `unknown`.
 */
export function track(pending, id, method, timeoutMs) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      pending.delete(id);
      reject(new Error(`${method}: no reply within ${timeoutMs} ms`));
    }, timeoutMs);
    pending.set(id, {
      method,
      resolve: (result) => {
        clearTimeout(timer);
        resolve(result);
      },
      reject: (error) => {
        clearTimeout(timer);
        reject(error);
      },
    });
  });
}

/** Rejects every request still waiting, with `error`, and forgets them: Chrome has gone and nothing will reply. */
export function failAll(pending, error) {
  const waiting = [...pending.values()];
  pending.clear();
  for (const waiter of waiting) waiter.reject(error);
}

/**
 * The address of the page's debugging socket from Chrome's list of targets (`/json`), rebuilt from the port and the
 * target's id so that nothing else in what Chrome sent is used as an address. Null when there is no page.
 */
export function pageSocketUrl(targets, port) {
  if (!Array.isArray(targets)) return null;
  for (const target of targets) {
    if (target?.type !== 'page') continue;
    const id = /\/devtools\/page\/([A-Za-z0-9-]+)$/.exec(target.webSocketDebuggerUrl)?.[1];
    if (id) return `ws://127.0.0.1:${port}/devtools/page/${id}`;
  }
  return null;
}

/**
 * The port Chrome chose, from the text of the `DevToolsActivePort` file it writes into its profile: the first line,
 * a whole number from 1 to 65535, when the second line is there and begins `/devtools/browser/` (Chrome writes the
 * two together, so a file with the first line alone may be half written). Null for anything else.
 */
export function parseActivePort(text) {
  const [first, second] = String(text).split('\n').map((line) => line.trim());
  if (!second?.startsWith('/devtools/browser/')) return null;
  if (!/^\d{1,5}$/.test(first)) return null;
  const port = Number(first);
  return port >= 1 && port <= 65535 ? port : null;
}

// The port from the profile's file, or null while the file is not there yet.
function readPort(profile) {
  try {
    return parseActivePort(readFileSync(join(profile, 'DevToolsActivePort'), 'utf8'));
  } catch (error) {
    if (error?.code === 'ENOENT') return null;
    throw error;
  }
}

// Waits for a freshly started Chrome to say which port it opened and to list its page: 100 tries, 200 ms apart,
// fewer if it has already gone. Only the Chrome that was started with this profile writes this profile's file.
async function waitForPage(profile, chromeGone) {
  for (let i = 0; i < 100 && !chromeGone(); i += 1) {
    const port = readPort(profile);
    if (port) {
      try {
        const url = pageSocketUrl(await (await fetch(`http://127.0.0.1:${port}/json`)).json(), port);
        if (url) return url;
      } catch {
        // Not up yet.
      }
    }
    await sleep(200);
  }
  return null;
}

// Whether `promise` settles within `ms`; the timer does not outlive the answer.
async function within(promise, ms) {
  let timer;
  const timeout = new Promise((resolve) => {
    timer = setTimeout(resolve, ms, false);
  });
  try {
    return await Promise.race([promise.then(() => true), timeout]);
  } finally {
    clearTimeout(timer);
  }
}

/**
 * Stops a process and removes its profile. `kill(signal)` sends a signal, `exited` is a promise that settles when
 * the process has gone. The order: a first kill; a wait of `graceMs` for it to go; if it has not, `SIGKILL` and a
 * wait of `hardMs`; then `removeProfile()`, whose failure is not passed on (a profile still being written to may be
 * left behind).
 */
export async function stopChrome({ kill, exited, removeProfile, graceMs = 3000, hardMs = 3000 }) {
  kill('SIGTERM');
  if (!(await within(exited, graceMs))) {
    kill('SIGKILL');
    await within(exited, hardMs);
  }
  try {
    removeProfile();
  } catch {
    // Left behind: the temporary folder is cleared by the system in time.
  }
}

/**
 * Starts headless Chrome. `profileRoot` is where its throwaway profile is made and removed. Chrome picks its own
 * debugging port (`--remote-debugging-port=0`) and writes it into the profile, so the rig only ever talks to the
 * Chrome it started. No host name but `localhost` resolves in it, so the app's one outside request (the network
 * operator lookup) fails as it would with no internet; an address written as numbers is not blocked.
 *
 * `onSpawn(kill)` is called at once, right after Chrome is spawned and before anything is awaited, with a
 * synchronous function that kills it for good and removes the profile as best it can, so that whoever starts Chrome
 * can stop it during its start-up too, and after it. The result has `page` and `close()` (stops Chrome with a grace
 * period, see `stopChrome`).
 */
export async function launchChrome({ profileRoot, extraArgs = [], onSpawn }) {
  const profile = mkdtempSync(join(profileRoot, 'replay-chrome-'));
  const removeProfile = () => rmSync(profile, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 });
  const proc = spawn(CHROME, [
    '--headless=new', '--no-first-run', '--no-default-browser-check', '--disable-background-networking',
    '--disable-component-update', '--disable-sync', '--mute-audio',
    // A page nobody is looking at has its timers slowed to once a second; hls.js runs on timers.
    '--disable-background-timer-throttling', '--disable-renderer-backgrounding',
    '--disable-backgrounding-occluded-windows',
    '--autoplay-policy=no-user-gesture-required',
    '--host-resolver-rules=MAP * ~NOTFOUND , EXCLUDE 127.0.0.1 , EXCLUDE localhost',
    '--window-size=1280,800',
    `--user-data-dir=${profile}`, '--remote-debugging-port=0',
    ...extraArgs, 'about:blank',
  ], { stdio: 'ignore' });
  // Chrome may still be writing to its profile after SIGKILL (its child processes), so the profile may be left.
  const hardStop = () => {
    proc.kill('SIGKILL');
    try {
      removeProfile();
    } catch {
      // Left behind.
    }
  };
  if (onSpawn) onSpawn(hardStop);
  let gone = false;
  const exited = new Promise((resolve) => {
    const onGone = () => {
      gone = true;
      resolve();
    };
    proc.on('error', onGone);
    proc.on('exit', onGone);
  });
  const stop = () => stopChrome({ kill: (signal) => proc.kill(signal), exited, removeProfile });
  let page = null;
  try {
    const wsUrl = await waitForPage(profile, () => gone);
    if (!wsUrl) throw new Error('Chrome did not start');
    page = await connect(wsUrl);
  } catch (error) {
    await stop();
    throw error;
  }
  return {
    page,
    async close() {
      page.close();
      await stop();
    },
  };
}

async function connect(wsUrl) {
  const ws = new WebSocket(wsUrl);
  await new Promise((resolve, reject) => {
    ws.onopen = resolve;
    ws.onerror = () => reject(new Error('could not connect to Chrome'));
  });
  let nextId = 1;
  let closed = false;
  const pending = new Map();
  const listeners = new Map();
  ws.onmessage = (event) => {
    routeMessage(event.data, pending, listeners);
  };
  ws.onclose = () => {
    closed = true;
    failAll(pending, new Error('Chrome closed the connection'));
  };
  const send = (method, params = {}) => {
    if (closed || ws.readyState !== SOCKET_OPEN) return Promise.reject(new Error(`${method}: Chrome is not connected`));
    const id = nextId;
    nextId += 1;
    const reply = track(pending, id, method, REQUEST_TIMEOUT_MS);
    ws.send(JSON.stringify({ id, method, params }));
    return reply;
  };
  return {
    send,
    /** True once Chrome has closed the connection (it went away, or `close` was called). */
    get closed() {
      return closed;
    },
    on(method, fn) {
      if (!listeners.has(method)) listeners.set(method, []);
      listeners.get(method).push(fn);
    },
    /** Runs an expression in the page and returns its value (promises are awaited). */
    async evaluate(expression) {
      const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
      if (r.exceptionDetails) {
        throw new Error(`page: ${JSON.stringify(r.exceptionDetails.exception?.description ?? r.exceptionDetails).slice(0, 600)}`);
      }
      return r.result.value;
    },
    close() {
      ws.close();
    },
  };
}
