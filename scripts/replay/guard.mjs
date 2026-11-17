// The train viewer's guard (docs/engineering/train-viewer-plan.md, decision 2). While the viewer runs, Chrome pauses
// each request the page makes to the site and this module decides what happens to it. The rule is an allow-list:
// a `GET` or `HEAD` goes through, so do three writes (start a stream, end it, the playback record, of which a copy is
// kept first); a progress save is answered here in the shape the server answers; every other write is answered here
// with a 204. Nothing else is let through. It keeps the owner's watch history as it was; it is not a security
// boundary. It keeps no header, no cookie and no body but the playback records' own.
import { printable } from './text.mjs';

/** What becomes of a paused request. */
export const PASS = 'pass';
export const RECORD = 'record';
export const PROGRESS = 'progress';
export const HELD = 'held';

/** A record's body is read only up to this many characters; at most this many viewings are kept. */
export const MAX_RECORD_CHARS = 65_536;
export const MAX_VIEWINGS = 50;
/** The list of what was held is cut at this many distinct entries, the method at this many characters. */
export const MAX_HELD_ENTRIES = 200;
export const MAX_METHOD_CHARS = 16;
const MAX_SHAPE_CHARS = 200;

const UUID = '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}';
const START_STREAM = /^\/api\/editions\/\d{1,12}\/sessions$/;
const END_STREAM = new RegExp(`^/api/sessions/${UUID}$`);
const RECORD_PATH = new RegExp(`^/api/playback-health/(${UUID})$`);
const SAVE_PROGRESS = /^\/api\/editions\/\d{1,12}\/progress$/;
const UUID_ANYWHERE = new RegExp(UUID, 'gi');

/**
 * The address's path when it is exactly `origin` plus a path, with no user name, query, fragment, dot segment or
 * other rewriting (what Chrome gives is already so written); null otherwise, or when it does not parse.
 */
function plainPath(url, origin) {
  try {
    const parsed = new URL(url);
    if (parsed.origin !== origin || url !== `${origin}${parsed.pathname}`) return null;
    return parsed.pathname;
  } catch {
    return null;
  }
}

/**
 * What to do with a request: `PASS`, `RECORD`, `PROGRESS` or `HELD`. The method is compared exactly as Chrome gives
 * it. A write is one of the allowed three only on the site's own origin, with the path in exactly its shape and no
 * query. A `GET` or `HEAD` goes through whatever its address: the page only reads with them.
 */
export function decide(method, url, origin) {
  if (method === 'GET' || method === 'HEAD') return PASS;
  if (method !== 'POST' && method !== 'PUT' && method !== 'DELETE') return HELD;
  const path = plainPath(url, origin);
  if (path === null) return HELD;
  if (method === 'POST' && START_STREAM.test(path)) return PASS;
  if (method === 'DELETE' && END_STREAM.test(path)) return PASS;
  if (method === 'PUT' && RECORD_PATH.test(path)) return RECORD;
  if (method === 'PUT' && SAVE_PROGRESS.test(path)) return PROGRESS;
  return HELD;
}

/** The viewing's id in the address of a request `decide` called `RECORD`. */
export function recordId(url) {
  return RECORD_PATH.exec(new URL(url).pathname)[1];
}

/** The position a progress save asked for: its body's `positionMs` when that is a finite number of 0 or more. */
export function savedPosition(body) {
  try {
    const value = JSON.parse(body)?.positionMs;
    if (typeof value === 'number' && Number.isFinite(value) && value >= 0) return Math.floor(value);
  } catch {
    // a body that is missing or not JSON: the save is still answered
  }
  return 0;
}

/**
 * An answer to a progress save in the shape the server answers (`ProgressResponse`), as `Fetch.fulfillRequest` takes
 * it. Not the server's own answer: that one is a 400 for a position that is missing or negative, 0 once the credits
 * are reached, and `watched: true` for a film already watched.
 */
export function progressAnswer(body) {
  const json = JSON.stringify({ positionMs: savedPosition(body), watched: false });
  return {
    responseCode: 200,
    responseHeaders: [{ name: 'Content-Type', value: 'application/json' }],
    body: Buffer.from(json).toString('base64'),
  };
}

/** What any other held request is answered with: 204 and no body. */
export function heldAnswer() {
  return { responseCode: 204, responseHeaders: [] };
}

/**
 * The record's body as an object when it can be kept (a string of at most 64 KiB holding a JSON object), else null.
 * The copy is what the player sent, whether or not the server kept it: the server refuses a body over 20,000
 * characters or a `seq` under 1, and the guard reads up to 65,536 on purpose, since its copy is the fuller one when
 * the link is bad.
 */
export function readRecord(body) {
  if (typeof body !== 'string' || body.length > MAX_RECORD_CHARS) return null;
  try {
    const parsed = JSON.parse(body);
    return typeof parsed === 'object' && parsed !== null && !Array.isArray(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

/**
 * Whether `next` is the later record of a viewing than `kept`: the one with the higher whole-number `seq` (of two
 * equal ones the first stays, as at the server, which replaces a row only for a higher `seq`); one with a `seq` beats
 * one without; with neither, the one that came last.
 */
export function supersedes(next, kept) {
  const nextSeq = Number.isInteger(next.seq);
  const keptSeq = Number.isInteger(kept.seq);
  if (!nextSeq) return !keptSeq;
  return !keptSeq || next.seq > kept.seq;
}

/** The path as a shape: every UUID written `<id>` and then every run of digits `N`, as printable text and cut. */
export function shapeOf(url) {
  let path;
  try {
    path = new URL(url).pathname;
  } catch {
    path = '(unreadable address)';
  }
  return printable(path.replaceAll(UUID_ANYWHERE, '<id>').replaceAll(/\d+/g, 'N'), MAX_SHAPE_CHARS);
}

/** Keeps what the guard reports. Nothing here holds a header, a cookie or the body of a request but a record's. */
function makeLedger() {
  const held = new Map();
  const viewings = new Map();
  const counts = { heldDropped: 0, notRead: 0, failed: 0, rejected: 0 };
  return {
    countHeld(method, url) {
      const entry = { method: printable(method, MAX_METHOD_CHARS), shape: shapeOf(url), count: 1 };
      const key = `${entry.method} ${entry.shape}`;
      if (held.has(key)) held.get(key).count += 1;
      else if (held.size < MAX_HELD_ENTRIES) held.set(key, entry);
      else counts.heldDropped += 1;
    },
    keepRecord(id, body) {
      const record = readRecord(body);
      if (record === null || (!viewings.has(id) && viewings.size >= MAX_VIEWINGS)) {
        counts.notRead += 1;
        return;
      }
      const viewing = viewings.get(id);
      if (viewing === undefined) viewings.set(id, { id, count: 1, latest: record });
      else {
        viewing.count += 1;
        if (supersedes(record, viewing.latest)) viewing.latest = record;
      }
    },
    count(name) {
      counts[name] += 1;
    },
    report() {
      return {
        held: [...held.values()].map((entry) => ({ ...entry })),
        heldDropped: counts.heldDropped,
        records: structuredClone([...viewings.values()]),
        notRead: counts.notRead,
        failed: counts.failed,
        rejected: counts.rejected,
      };
    },
  };
}

/**
 * Puts the guard on a page (the `send` and `on` of `cdp.mjs`): registers the listener, then asks Chrome to pause every
 * request to the site and resolves once Chrome has said yes; the caller opens no address before that. Each paused
 * request that names itself (a string `requestId`) is answered exactly once: continued when `decide` says it may go (a
 * record after its copy is taken), answered here when it is held, and failed when deciding or copying throws; an
 * event with no `requestId` cannot be answered and is counted as failed. A send Chrome rejects is counted and never
 * retried, and none escapes as an unhandled rejection; a `continueRequest` or `fulfillRequest` that is rejected leaves
 * that request paused for good, which almost always means Chrome has gone. Resolves `{ report }`: `report()` is a deep
 * copy of what was held (`held`, `heldDropped`), the records kept by viewing in the order first seen (`records`: what
 * the player sent, kept or not by the server), and the counts of records not read, requests failed and sends
 * rejected. `judge` is `decide`; a test may replace it.
 */
export async function attachGuard(page, { site, judge = decide }) {
  const origin = new URL(site).origin;
  const ledger = makeLedger();

  // Asks Chrome. Whatever goes wrong with the answer is counted here: Chrome may have gone away.
  function ask(method, params) {
    try {
      Promise.resolve(page.send(method, params)).catch(() => ledger.count('rejected'));
    } catch {
      ledger.count('rejected');
    }
  }

  // What to send for one paused request. Throws when it cannot be decided or copied.
  function answerFor(request, requestId) {
    const outcome = judge(request.method, request.url, origin);
    if (outcome === PASS) return ['Fetch.continueRequest', { requestId }];
    if (outcome === RECORD) {
      ledger.keepRecord(recordId(request.url), request.postData);
      return ['Fetch.continueRequest', { requestId }];
    }
    ledger.countHeld(request.method, request.url);
    const answer = outcome === PROGRESS ? progressAnswer(request.postData) : heldAnswer();
    return ['Fetch.fulfillRequest', { requestId, ...answer }];
  }

  page.on('Fetch.requestPaused', (params) => {
    const requestId = params?.requestId;
    if (typeof requestId !== 'string') {
      ledger.count('failed');
      return;
    }
    try {
      ask(...answerFor(params.request, requestId));
    } catch {
      ledger.count('failed');
      ask('Fetch.failRequest', { requestId, errorReason: 'Failed' });
    }
  });
  await page.send('Fetch.enable', { patterns: [{ urlPattern: `${origin}/*`, requestStage: 'Request' }] });
  return { report: ledger.report };
}
