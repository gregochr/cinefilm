// A small client for Chrome's debugging protocol: start a headless Chrome (with its own throwaway profile, or a kept
// one), or a plain window, and talk to one page over a port or a pipe. Node has WebSocket and fetch built in, so
// nothing is installed.
//
// Starting the real Chrome and the real socket need the installed Chrome and cannot run in CI; they are checked by
// hand (docs/engineering/home-replay.md). `launchChrome` and `openWindow` are tested with a made-up spawn and
// made-up streams. The matching of replies to requests and of events to listeners is `routeMessage`,
// and the bookkeeping of requests that are waiting is `track` and `failAll`: all three take plain values, so they are
// tested with made-up messages. The same goes for Chrome's three argument lists (`chromeArgs`) and the cutting of the
// debugging pipe's bytes into messages (`splitFrames`); the pipe's page (`openPipe`) is tested over fake streams.
// The pipe is the viewer's channel (docs/engineering/train-viewer-plan.md): nothing listens on any port.
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
export async function within(promise, ms) {
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
 * left behind). With no `removeProfile` (a kept profile) nothing is removed.
 */
export async function stopChrome({ kill, exited, removeProfile, graceMs = 3000, hardMs = 3000 }) {
  kill('SIGTERM');
  if (!(await within(exited, graceMs))) {
    kill('SIGKILL');
    await within(exited, hardMs);
  }
  try {
    removeProfile?.();
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
 *
 * Three options, all off by default (the replay uses none):
 *  - `profile`: a kept profile folder, used as it is and never removed, not by `close()` and not by the hard stop;
 *    `profileRoot` is then not used.
 *  - `channel: 'pipe'`: the viewer's arguments, and the page is talked to over `--remote-debugging-pipe` (no port,
 *    no `DevToolsActivePort`, no `/json`).
 *  - `ownGroup: true`: Chrome leads its own process group, so a Ctrl-C in the terminal does not reach it, and the
 *    hard stop kills the whole group.
 * A `channel` that is neither `'port'` nor `'pipe'` is refused, and so is a kept profile without the pipe (it is a
 * signed-in profile and never gets a debugging port), before anything is started.
 * `spawnProcess`, `signalProcess` (`process.kill`'s shape) and `pipeOptions` (`openPipe`'s tries) are for tests.
 */
export async function launchChrome({
  profileRoot, extraArgs = [], onSpawn, profile: kept, channel = 'port', ownGroup = false,
  spawnProcess = spawn, signalProcess = process.kill, pipeOptions,
}) {
  if (channel !== 'port' && channel !== 'pipe') throw new Error(`unknown channel: ${String(channel)}`);
  if (kept !== undefined && (typeof kept !== 'string' || kept === '')) {
    throw new Error('a kept profile must be the name of a folder');
  }
  if (kept !== undefined && channel !== 'pipe') {
    throw new Error('a kept profile is a signed-in one: it is only started with the pipe');
  }
  const profile = kept ?? mkdtempSync(join(profileRoot, 'replay-chrome-'));
  const removeProfile = kept
    ? undefined
    : () => rmSync(profile, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 });
  const piped = channel === 'pipe';
  const proc = spawnProcess(CHROME, chromeArgs(piped ? 'viewer' : 'replay', { profile, extraArgs }), {
    stdio: piped ? ['ignore', 'ignore', 'ignore', 'pipe', 'pipe'] : 'ignore',
    detached: ownGroup,
  });
  // A group is killed as a whole, so Chrome's helpers go with it; a process that has already gone is not an error.
  const kill = (signal) => {
    if (!ownGroup || signal !== 'SIGKILL' || !proc.pid) {
      proc.kill(signal);
      return;
    }
    try {
      signalProcess(-proc.pid, signal);
    } catch {
      // Already gone.
    }
  };
  // Chrome may still be writing to its profile after SIGKILL (its child processes), so the profile may be left.
  const hardStop = () => {
    kill('SIGKILL');
    try {
      removeProfile?.();
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
  const stop = () => stopChrome({ kill, exited, removeProfile });
  let page = null;
  try {
    page = piped
      ? await openPipe(proc.stdio[3], proc.stdio[4], pipeOptions)
      : await connectToPort(profile, () => gone);
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

/**
 * Chrome's argument list for one of three ways of starting it (`kind`):
 *  - `replay`: headless, a throwaway `profile`, `--remote-debugging-port=0`, and no host name but `localhost`
 *    resolving; `extraArgs` and `about:blank` come last.
 *  - `viewer`: the same, but every host resolves and the channel is `--remote-debugging-pipe` (nothing listens).
 *  - `window`: a normal window on `address` with a kept `profile`: no `--headless`, no debugging port or pipe, no
 *    muting. Nothing in it lets a program read the page.
 * No list holds `--use-mock-keychain`: with it a cookie in a kept profile is as good as plain text, and a start
 * with the other setting would delete it.
 */
export function chromeArgs(kind, { profile, extraArgs = [], address } = {}) {
  if (kind === 'window') {
    if (typeof address !== 'string' || address === '') throw new Error('the window needs an address to open');
    return ['--no-first-run', '--no-default-browser-check', '--disable-sync', `--user-data-dir=${profile}`, address];
  }
  if (kind !== 'replay' && kind !== 'viewer') throw new Error(`unknown way of starting Chrome: ${kind}`);
  const replay = kind === 'replay';
  return [
    '--headless=new', '--no-first-run', '--no-default-browser-check', '--disable-background-networking',
    '--disable-component-update', '--disable-sync', '--mute-audio',
    // A page nobody is looking at has its timers slowed to once a second; hls.js runs on timers.
    '--disable-background-timer-throttling', '--disable-renderer-backgrounding',
    '--disable-backgrounding-occluded-windows',
    '--autoplay-policy=no-user-gesture-required',
    ...(replay ? ['--host-resolver-rules=MAP * ~NOTFOUND , EXCLUDE 127.0.0.1 , EXCLUDE localhost'] : []),
    '--window-size=1280,800',
    `--user-data-dir=${profile}`, replay ? '--remote-debugging-port=0' : '--remote-debugging-pipe',
    ...extraArgs, 'about:blank',
  ];
}

async function connectToPort(profile, chromeGone) {
  const wsUrl = await waitForPage(profile, chromeGone);
  if (!wsUrl) throw new Error('Chrome did not start');
  return connect(wsUrl);
}

/**
 * Starts Chrome as a normal window, for a person to use: `address` opens in it, `profile` is kept as it is. Nothing
 * is attached to it: no debugging port or pipe, no output read. Returns `{ exited, stop, kill }`: `exited` settles
 * when that Chrome has gone, `stop()` ends it (`SIGTERM`, then `SIGKILL` if it lingers) and never removes the
 * profile, `kill()` is the synchronous hard stop.
 */
export function openWindow({ profile, address, spawnProcess = spawn }) {
  const proc = spawnProcess(CHROME, chromeArgs('window', { profile, address }), { stdio: 'ignore' });
  const exited = new Promise((resolve) => {
    proc.on('error', resolve);
    proc.on('exit', resolve);
  });
  return {
    exited,
    stop: () => stopChrome({ kill: (signal) => proc.kill(signal), exited }),
    kill: () => {
      proc.kill('SIGKILL');
    },
  };
}

// ---- the debugging pipe ------------------------------------------------------------------------------------------

/** The most a single message may hold before its NUL: a channel that sends more is failed, not buffered for ever. */
export const MAX_MESSAGE_BYTES = 16 * 1024 * 1024;

/**
 * Cuts the bytes Chrome writes to the pipe into messages (each ends with one NUL byte). `held` is what was left
 * of the earlier chunks, `chunk` the bytes that just arrived. Returns `{ messages, rest }`: the complete messages, as
 * text (an empty one between two NULs is dropped), and the part that has no NUL yet, to be passed as `held` next
 * time. Throws when a message is over `limit` bytes, with or without its NUL.
 */
export function splitFrames(held, chunk, limit = MAX_MESSAGE_BYTES) {
  let rest = chunk.length === 0 ? held : Buffer.concat([held, chunk]);
  const messages = [];
  for (let end = rest.indexOf(0); end !== -1; end = rest.indexOf(0)) {
    if (end > limit) throw new Error('Chrome sent a message that is too long');
    if (end > 0) messages.push(rest.subarray(0, end).toString('utf8'));
    rest = rest.subarray(end + 1);
  }
  if (rest.length > limit) throw new Error('Chrome sent a message that is too long');
  return { messages, rest };
}

// Whether a message is for this page: a reply (it has an id) always, an event only when it carries the session's id.
export function forSession(raw, sessionId) {
  try {
    const message = JSON.parse(raw);
    return message === null || typeof message !== 'object' || message.id !== undefined
      || (sessionId !== null && message.sessionId === sessionId);
  } catch {
    return true; // Not JSON: `routeMessage` skips it.
  }
}

/**
 * The page behind a debugging pipe. `toChrome` is the stream Chrome reads commands from (its descriptor 3),
 * `fromChrome` the one it writes answers and events to (descriptor 4). Over the pipe one talks to the browser, not to
 * a page: this asks `Target.getTargets` (`tries` times, `delayMs` apart) until a page is there, attaches to it, and
 * from then on every command carries the session's id and a listener hears only the events of that session. The
 * result has the socket page's `send`, `on`, `evaluate`, `closed` and `close`.
 */
export async function openPipe(toChrome, fromChrome, { tries = 100, delayMs = 100 } = {}) {
  let nextId = 1;
  let closed = false;
  let sessionId = null;
  let held = Buffer.alloc(0);
  const pending = new Map();
  const listeners = new Map();
  const fail = (error) => {
    closed = true;
    failAll(pending, error);
  };
  const post = (method, params, session) => {
    if (closed) return Promise.reject(new Error(`${method}: Chrome is not connected`));
    const id = nextId;
    nextId += 1;
    const reply = track(pending, id, method, REQUEST_TIMEOUT_MS);
    const message = { id, method, params, ...(session ? { sessionId: session } : {}) };
    toChrome.write(`${JSON.stringify(message)}\0`, (error) => {
      if (error) fail(error);
    });
    return reply;
  };
  fromChrome.on('data', (chunk) => {
    if (closed) return; // A failed channel is not read any more: the rest of a too long message is not a message.
    try {
      const framed = splitFrames(held, chunk);
      held = framed.rest;
      for (const raw of framed.messages) {
        if (forSession(raw, sessionId)) routeMessage(raw, pending, listeners);
      }
    } catch (error) {
      held = Buffer.alloc(0);
      fail(error);
    }
  });
  const ended = () => fail(new Error('Chrome closed the connection'));
  for (const event of ['end', 'close', 'error']) fromChrome.on(event, ended);
  toChrome.on('error', ended);
  const send = (method, params = {}) => post(method, params, sessionId);
  const target = await findPage((method) => post(method, {}, null), tries, delayMs);
  ({ sessionId } = await post('Target.attachToTarget', { targetId: target.targetId, flatten: true }, null));
  return {
    send,
    /** True once the pipe has ended or failed (Chrome went away, or `close` was called). */
    get closed() {
      return closed;
    },
    on(method, fn) {
      if (!listeners.has(method)) listeners.set(method, []);
      listeners.get(method).push(fn);
    },
    evaluate: (expression) => evaluateIn(send, expression),
    close() {
      fail(new Error('Chrome closed the connection'));
      toChrome.destroy();
      fromChrome.destroy();
    },
  };
}

// Asks the browser for its targets until one is a page; throws when none came in `tries` tries.
async function findPage(ask, tries, delayMs) {
  for (let i = 0; i < tries; i += 1) {
    const { targetInfos } = await ask('Target.getTargets');
    const page = Array.isArray(targetInfos) ? targetInfos.find((info) => info?.type === 'page') : null;
    if (typeof page?.targetId === 'string') return page;
    await sleep(delayMs);
  }
  throw new Error('Chrome did not start');
}

async function evaluateIn(send, expression) {
  const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (r.exceptionDetails) {
    const what = r.exceptionDetails.exception?.description ?? r.exceptionDetails;
    throw new Error(`page: ${JSON.stringify(what).slice(0, 600)}`);
  }
  return r.result.value;
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
    evaluate: (expression) => evaluateIn(send, expression),
    close() {
      ws.close();
    },
  };
}
