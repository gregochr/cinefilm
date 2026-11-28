// The stand-in for CineFilm's server during a replay: it serves the real app (a build of it, read once at
// start-up), answers the few API calls the Player screen makes, and plays one film whose delivery follows the
// link trace. It listens on 127.0.0.1 only and has no sign-in.
//
// The film is a 1080p source as CineFilm would ladder it (Ladder.java, Rung.java): the original copied at
// 10 Mbit/s, then 720p, 480p, 360p and the 240p floor. Playlists are written as PlaylistWriter.java writes them.
// Segments are four seconds of MPEG-TS built by ts.mjs, each with sound as heavy as its rung's audio.
//
// With `encoderSpeed` set, the server's one encoder playhead per session is modelled (playhead.mjs, which says which
// Java it mirrors): a segment is answered when the encoder has made it, a request far from the playhead restarts the
// encoder there, and a segment not made within six seconds gets 503. Left out, every segment is there at once.
//
// The link (link.mjs): every API answer, playlist, segment and the speed probe passes through one shared allowance
// that is refilled at the trace's rate for the current replay minute (nothing at all when it is 0). Not even an
// answer's headers leave before the allowance lets its first bytes go, and each answer waits the round-trip time
// first. The app's own files (the page, its scripts and fonts) are served at once: loading the app is not under
// test. As bytes pass through the link they are counted by replay minute (`delivered`), from the moment the clock
// starts; an answer that is released at once after `finish()` is not counted.
//
// What a request says is never a file path, nothing of it is sent back but the position a progress call reports (as
// a number), and its log and its results hold only figures and words from fixed lists: the build is a table read at
// start-up, the request log holds the kind of route that matched and never an address, and a health report is
// copied field by field from lists of names written here (docs/engineering/home-replay.md, "What the stand-in keeps
// of what the player sends").
import http from 'node:http';
import { randomUUID } from 'node:crypto';
import { readdirSync, readFileSync } from 'node:fs';
import { extname, join, relative, sep } from 'node:path';
import { createByteCounter, createLink, minuteAt, rateAt } from './link.mjs';
import { WIRE } from './trace.mjs';
import { createPlayhead, WAIT_MAX_MS } from './playhead.mjs';

/** How often a held request looks again: PollingSegmentWaiter.POLL (a test reads the Java). */
export const POLL_MS = 100;
import { buildSegment } from './ts.mjs';

const HEADROOM = 1.25; // StartRung.java
const FLOOR_VIDEO = 250_000;
const OPEN_BPS = 400e6; // the link before the replay clock starts
const TICK_MS = 10;
const MAX_BODY = 64 * 1024;
const MAX_MINUTES = 1000;
const PROBE_SIZE = 512 * 1024 * 1024;
const MEDIA_KINDS = new Set(['master', 'playlist', 'segment', 'probe']);
const METHODS = ['GET', 'HEAD', 'POST', 'PUT', 'DELETE', 'PATCH', 'OPTIONS'];

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.woff': 'font/woff', '.woff2': 'font/woff2', '.webmanifest': 'application/manifest+json',
  '.json': 'application/json',
};

// What a health report may keep (HealthDtos.HealthReport): figures, flags and words from fixed lists.
const REPORT_NUMBERS = [
  'seq', 'editionId', 'elapsedMs', 'latencyMs', 'startupMs', 'watchedMs', 'stallCount', 'stallMs', 'longestStallMs',
  'seekCount', 'seekWaitMs', 'reloadCount', 'reloadMs', 'reconnectCount', 'reconnectMs', 'recoveries', 'stepsDown',
  'stepsUp', 'netErrors', 'timeouts', 'kbpsLow', 'kbpsMedian', 'kbpsHigh', 'kbpsSamples',
  'startPositionMs', 'endPositionMs', 'rebanksStarted', 'rebanksDone', 'rebanksUndone', 'marksForgotten',
  'lookaheadMinutes', 'bankRungChanges', 'bankBypassRequests',
];
// A look-ahead stretch (HealthDtos.LookaheadStretch): two figures and a rung name or null, no more than 50 of them.
const STRETCH_RUNGS = ['original', '1080p', '720p', '480p', '360p', '240p'];
const MAX_STRETCHES = 50;
const REPORT_FLAGS = ['autoplayRefused', 'fellBack'];
const REPORT_WORDS = [
  ['engine', ['hlsjs', 'native', 'direct']],
  ['environment', ['home', 'train', 'away', 'unknown']],
  ['quality', ['auto', 'original', '1080p', '720p', '480p', '360p', '240p']],
];

const ZEROS = Buffer.alloc(64 * 1024);
const EMPTY = Buffer.alloc(0);
const NULL_BODY = Buffer.from('null');
const INVALID_REQUEST = { error: 'Invalid request' }; // what the real server answers to a request it cannot read

function audioBitrate({ id, copy, video }) {
  if (!copy && video <= FLOOR_VIDEO) return 64_000;
  return id === '360p' ? 96_000 : 128_000;
}

function codecsFor(height) {
  if (height > 720) return 'avc1.640028,mp4a.40.2';
  return height > 480 ? 'avc1.64001f,mp4a.40.2' : 'avc1.64001e,mp4a.40.2';
}

export function ladder(sourceBitrate) {
  const steps = [
    { id: 'original', height: 1080, video: sourceBitrate, copy: true },
    { id: '720p', height: 720, video: 2_500_000, copy: false },
    { id: '480p', height: 480, video: 1_000_000, copy: false },
    { id: '360p', height: 360, video: 600_000, copy: false },
    { id: '240p', height: 240, video: FLOOR_VIDEO, copy: false },
  ];
  return steps.map((step) => {
    const audio = audioBitrate(step);
    return {
      ...step,
      audio,
      average: step.video + audio,
      peak: Math.round(step.video * (step.copy ? 1.5 : 1.2)) + audio,
      width: Math.floor(Math.round((step.height * 1920) / 1080) / 2) * 2,
      codecs: codecsFor(step.height),
    };
  });
}

/** StartRung.pick: the highest rung whose peak times 1.25 fits the probe, else the lowest; no probe, the top. */
export function startRung(rungs, probeKbps) {
  if (probeKbps === null || probeKbps === undefined) return rungs[0];
  return rungs.find((r) => r.peak * HEADROOM <= probeKbps * 1000) ?? rungs[rungs.length - 1];
}

export function masterPlaylist(rungs, start, pinned) {
  const listed = pinned ? rungs.filter((r) => r.id === pinned) : [start, ...rungs.filter((r) => r !== start)];
  let out = '#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-INDEPENDENT-SEGMENTS\n';
  for (const r of listed) {
    out += `#EXT-X-STREAM-INF:BANDWIDTH=${r.peak},AVERAGE-BANDWIDTH=${r.average},RESOLUTION=${r.width}x${r.height},CODECS="${r.codecs}"\n${r.id}/index.m3u8\n`;
  }
  return out;
}

export function mediaPlaylist(segments, segmentMs) {
  const seconds = `${Math.floor(segmentMs / 1000)}.${String(segmentMs % 1000).padStart(3, '0')}`;
  let out = `#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-TARGETDURATION:${Math.ceil(segmentMs / 1000)}\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-INDEPENDENT-SEGMENTS\n`;
  for (let n = 0; n < segments; n += 1) out += `#EXTINF:${seconds},\nseg-${n}.ts\n`;
  return `${out}#EXT-X-ENDLIST\n`;
}

// ---- the clock and the link ---------------------------------------------------------------------------------

const clockMs = (env) => (env.t0 === null ? null : Date.now() - env.t0);
const minuteNow = (env) => (env.t0 === null ? null : minuteAt(env.startMinute, Date.now() - env.t0));
const rateBps = (env) => (env.t0 === null ? OPEN_BPS : rateAt(env.trace, minuteNow(env)) * 1000);

// One answer: `size` bytes of body (`chunk(offset, n)` gives them) that the link releases after the round trip.
// It is the link's job: `left()` and `send(n)`.
class Answer {
  constructor(env, res, spec, record, began = performance.now()) {
    this.env = env;
    this.res = res;
    this.spec = spec;
    this.record = record;
    this.size = spec.size;
    this.offset = 0;
    this.headed = false;
    this.closed = false;
    this.began = began;
    this.cancelAlive = null;
  }

  left() {
    return this.closed ? 0 : this.size - this.offset;
  }

  head() {
    if (this.headed) return;
    this.headed = true;
    this.res.writeHead(this.spec.status, { 'Cache-Control': 'no-store', 'Content-Length': this.size, ...this.spec.headers });
    this.record.firstByteMs = Math.round(performance.now() - this.began);
  }

  send(n) {
    this.head();
    this.res.write(this.spec.chunk(this.offset, n));
    this.env.counter.add(clockMs(this.env), n); // the clock's time is null before it starts: nothing is counted then
    this.offset += n;
    this.record.sent = this.offset;
    if (this.offset >= this.size) this.complete();
  }

  complete() {
    this.res.end();
    this.record.doneMs = Math.round(performance.now() - this.began);
  }

  // The player went away, or the stand-in stopped: the job leaves the link and the log says whether it was cut.
  close() {
    if (this.offset < this.size || (this.size === 0 && !this.headed)) {
      this.record.aborted = true;
      this.record.cutMs = Math.round(performance.now() - this.began); // like doneMs, from the start of the request
    }
    this.closed = true;
    this.env.link.remove(this);
    if (this.cancelAlive) this.cancelAlive();
  }

  // The replay is over: what the player says as it leaves is not held up by the link.
  releaseAtOnce() {
    this.head();
    this.res.end(this.size ? this.spec.chunk(0, this.size) : undefined);
    this.offset = this.size;
    this.record.sent = this.size;
    this.record.doneMs = 0;
  }

  afterRoundTrip() {
    if (this.closed) return;
    if (this.size > 0) {
      this.env.link.add(this);
      return;
    }
    // Nothing to send but the headers: they still wait for the link to be alive.
    this.cancelAlive = this.env.link.whenAlive(() => {
      this.head();
      this.complete();
    });
  }
}

function methodOf(req) {
  return METHODS.find((m) => m === req.method) ?? 'other';
}

// `ctx.arrival` is set for a request that was held before it was answered: its times and its readings run from the
// moment it arrived, not from the moment the wait ended.
function startAnswer({ env, req, res, arrival }, spec) {
  const record = {
    at: arrival?.at ?? Date.now(), ms: arrival ? arrival.ms : clockMs(env), minute: arrival ? arrival.minute : minuteNow(env),
    method: methodOf(req), kind: spec.kind,
    status: spec.status, bytes: spec.size, sent: 0, firstByteMs: null, doneMs: null, aborted: false, ...spec.detail,
  };
  env.requests.push(record);
  if (res.destroyed) {
    // The player went while the request was still being read: there is nobody to answer.
    record.aborted = true;
    return;
  }
  const answer = new Answer(env, res, spec, record, arrival?.began);
  res.on('close', () => answer.close());
  if (env.open && !MEDIA_KINDS.has(spec.kind)) {
    answer.releaseAtOnce();
  } else {
    setTimeout(() => answer.afterRoundTrip(), env.rttMs);
  }
}

function sendBuffer(ctx, buffer, spec = {}) {
  startAnswer(ctx, {
    status: 200, headers: {}, kind: 'api', detail: {}, ...spec,
    size: buffer.length, chunk: (offset, n) => buffer.subarray(offset, offset + n),
  });
}

function sendJson(ctx, value, spec = {}) {
  sendBuffer(ctx, Buffer.from(JSON.stringify(value)), {
    ...spec, headers: { ...spec.headers, 'Content-Type': 'application/json' },
  });
}

function noContent(ctx, kind) {
  startAnswer(ctx, { status: 204, headers: {}, kind, detail: {}, size: 0, chunk: () => EMPTY });
}

function refuse(ctx) {
  sendJson(ctx, INVALID_REQUEST, { status: 400, kind: 'refused' });
}

// ---- reading what a request sends ---------------------------------------------------------------------------

// The body, up to 64 KB: `text` and the `bytes` it had, or `tooLarge` (nothing is kept past the limit), or `cut`
// when the player went before all of it arrived: what came is then not a body and nothing acts on it.
function readBody(req) {
  return new Promise((resolve) => {
    const parts = [];
    let bytes = 0;
    let finished = false;
    const finish = (tooLarge) => {
      if (finished) return;
      finished = true;
      resolve({
        text: tooLarge ? '' : Buffer.concat(parts).toString('utf8'), bytes, tooLarge, cut: !tooLarge && !req.complete,
      });
    };
    req.on('data', (part) => {
      if (finished) return;
      bytes += part.length;
      if (bytes > MAX_BODY) finish(true);
      else parts.push(part);
    });
    req.on('end', () => finish(false));
    // A player that goes mid-body ends the request with a close (and an error nobody needs to hear).
    req.on('close', () => finish(false));
  });
}

// The parsed body: `value` is undefined when there is none or it is not JSON.
async function readJson(req) {
  const body = await readBody(req);
  if (body.tooLarge) return { tooLarge: true, cut: false, value: undefined };
  return { tooLarge: false, cut: body.cut, value: parseJson(body.text) };
}

function parseJson(text) {
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

const isObject = (value) => typeof value === 'object' && value !== null && !Array.isArray(value);
const isNumber = (value) => typeof value === 'number' && Number.isFinite(value);
const validWire = (value) => typeof value === 'number' && value >= 1 && value <= 1.5;
const oneOf = (list, value) => list.find((word) => word === value) ?? 'other';

// ---- the routes ---------------------------------------------------------------------------------------------

function replayState({ env, res }) {
  res.writeHead(200, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({
    clockMs: clockMs(env), minute: minuteNow(env), kbps: rateBps(env) / 1000, open: env.open,
    active: env.link.size(), waiting: env.waiting, requests: env.requests.length, reports: env.healthReports.length,
  }));
}

function replayBlank({ res }) {
  res.writeHead(200, { 'Content-Type': 'text/html' });
  res.end('<!doctype html><title>blank</title>');
}

function apiSession(ctx) {
  sendJson(ctx, ctx.req.method === 'GET' ? { authenticated: true } : { ok: true });
}

function apiFilm(ctx) {
  sendJson(ctx, ctx.env.film);
}

// The quality ids CineFilm knows (Ladder.QUALITY_IDS).
const QUALITY_IDS = ['auto', 'original', '1080p', '720p', '480p', '360p', '240p'];

// SessionService.create's three messages are used as they are: "Invalid quality" for a quality that is missing or
// is no id CineFilm knows, "Quality not available for this film" (a 400 there too) for a known id this ladder lacks,
// "Invalid probe" for a negative probe. What the stand-in cannot read (a quality that is not text, a probe that is
// not a number, one that is not finite or is outside the safe-integer range) is "Invalid request"; how the real
// server's JSON reader treats those, and a fraction (taken as it is here), was not checked.
function qualityRefusal(quality, rungs) {
  if (quality === undefined || quality === null) return 'Invalid quality';
  if (typeof quality !== 'string') return INVALID_REQUEST.error;
  if (!QUALITY_IDS.includes(quality)) return 'Invalid quality';
  if (quality !== 'auto' && !rungs.some((r) => r.id === quality)) return 'Quality not available for this film';
  return null;
}

function probeRefusal(probe) {
  if (probe === undefined || probe === null) return null;
  if (typeof probe !== 'number' || Math.abs(probe) > Number.MAX_SAFE_INTEGER) { // Infinity is beyond it too
    return INVALID_REQUEST.error;
  }
  return probe < 0 ? 'Invalid probe' : null;
}

// SessionService.create, :125-128: "startMs is outside the film" for a start before 0 or at or past the film's end.
// CreateRequest.startMs is a Long: a JSON integer, a decimal cut toward zero, or absent (0). The real server also
// coerces some strings (Jackson's default mapper); the app never sends one, so the stand-in does not model it and
// refuses any string. A number beyond Long's range is unreadable, as it is there. NaN means unreadable.
function startMsOf(value) {
  if (value === undefined || value === null) return 0;
  if (typeof value !== 'number' || !Number.isFinite(value) || Math.abs(value) >= 2 ** 63) return Number.NaN;
  return Math.trunc(value) + 0; // + 0: no negative zero
}

// SessionService.create, :125-128: "startMs is outside the film" for a start before 0 or at or past the film's end.
function startRefusal(value, env) {
  const startMs = startMsOf(value);
  if (Number.isNaN(startMs)) return INVALID_REQUEST.error;
  return startMs < 0 || startMs >= env.segmentCount * env.segmentMs ? 'startMs is outside the film' : null;
}

function sessionRefusal(body, rungs) {
  return qualityRefusal(body.quality, rungs) ?? probeRefusal(body.probeKbps);
}

async function createSession(ctx) {
  const { env } = ctx;
  const body = await readJson(ctx.req);
  // A body that was cut off makes no session, whatever its first bytes said.
  if (body.tooLarge || body.cut || !isObject(body.value)) {
    refuse(ctx);
    return;
  }
  const refusal = sessionRefusal(body.value, env.rungs) ?? startRefusal(body.value.startMs, env);
  if (refusal) {
    sendJson(ctx, { error: refusal }, { status: 400, kind: 'session' });
    return;
  }
  const pinned = env.rungs.find((r) => r.id === body.value.quality) ?? null;
  const probe = body.value.probeKbps ?? null;
  const start = pinned ?? startRung(env.rungs, probe);
  const id = randomUUID();
  const encoder = env.encoderSpeed === null ? null : createPlayhead({
    segmentCount: env.segmentCount, segmentMs: env.segmentMs, speed: env.encoderSpeed, now: () => Date.now(),
    startSegment: Math.floor(startMsOf(body.value.startMs) / env.segmentMs), startupMs: env.encoderStartMs,
  });
  env.sessions.set(id, { start, pinned: pinned ? pinned.id : null, encoder });
  sendJson(ctx, {
    sessionId: id, masterUrl: `/api/sessions/${id}/master.m3u8`, startRung: start.id,
    renditions: env.rungs.map((r) => ({ id: r.id, height: r.height, bitrate: r.video, copy: r.copy })),
  }, { kind: 'session', detail: { probeKbps: probe, startRung: start.id, quality: pinned ? pinned.id : 'auto' } });
}

// The speed probe reads the start of the original. A direct play of it would not work: it is not a film.
function probe(ctx) {
  startAnswer(ctx, {
    status: 206, size: PROBE_SIZE, kind: 'probe', detail: {},
    chunk: (offset, n) => ZEROS.subarray(0, n), // n is never above 64 KB: that is what the link sends at once
    headers: { 'Content-Type': 'video/mp4', 'Content-Range': `bytes 0-${PROBE_SIZE - 1}/${PROBE_SIZE}`, 'Accept-Ranges': 'bytes' },
  });
}

function sessionMaster(ctx, { session }) {
  const body = Buffer.from(masterPlaylist(ctx.env.rungs, session.start, session.pinned));
  sendBuffer(ctx, body, { headers: { 'Content-Type': 'application/vnd.apple.mpegurl' }, kind: 'master' });
  return true;
}

function sessionPlaylist(ctx, { match }) {
  const rung = ctx.env.rungs.find((r) => r.id === match[1]);
  if (!rung) return false;
  const body = Buffer.from(mediaPlaylist(ctx.env.segmentCount, ctx.env.segmentMs));
  sendBuffer(ctx, body, { headers: { 'Content-Type': 'application/vnd.apple.mpegurl' }, kind: 'playlist', detail: { rung: rung.id } });
  return true;
}

function sendSegment(ctx, rung, n, detail) {
  const { env } = ctx;
  const bytes = Math.round((rung.average * env.wire * env.segmentMs) / 8000);
  sendBuffer(ctx, buildSegment(env.template, n, bytes, env.fill, rung.audio / 1000), {
    headers: { 'Content-Type': 'video/mp2t' }, kind: 'segment', detail: { rung: rung.id, segment: n, ...detail },
  });
}

// ControllerAdvice for SegmentNotReadyException (HlsSessionController.java:75-78): 503 with Retry-After: 1.
function sendNotReady(ctx, rung, n, detail) {
  sendJson(ctx, { error: 'Segment not ready' }, {
    status: 503, headers: { 'Retry-After': '1' }, kind: 'segment', detail: { rung: rung.id, segment: n, ...detail },
  });
}

// Waits for the encoder to make the segment, as SessionManager.segmentLive does: until it is made, or six seconds
// from the request's arrival, or when no run is going, or when the session has gone (SessionManager.settled,
// :437-441), whichever a poll finds first: the server's waiter looks every 100 ms (PollingSegmentWaiter.java:14,33-35),
// so a stop that a later request causes ends every held wait within one poll. A session that was DELETEd is a 404
// "No such session" (:439-441). The answer says how long it waited and, on the request that restarted the encoder, why.
function waitForSegment(ctx, { encoder, rung, n, arrived, restart, deleted, live }) {
  let timer = null;
  let answered = false;
  ctx.env.waiting += 1;
  const answer = (made, gone = false) => {
    if (answered) return;
    answered = true;
    ctx.env.waiting -= 1;
    clearTimeout(timer);
    const waited = Date.now() - arrived;
    const detail = {
      ...(restart ? { restart } : {}), ...(deleted ? { deleted: true } : {}), ...(waited > 0 ? { waitMs: waited } : {}),
    };
    try {
      if (gone) sendJson(ctx, { error: 'No such session' }, { status: 404, kind: 'segment', detail: { rung: rung.id, segment: n, ...detail } });
      else if (made) sendSegment(ctx, rung, n, detail);
      else sendNotReady(ctx, rung, n, detail);
    } catch {
      fail(ctx.res); // from a timer, nothing above catches it
    }
  };
  const check = () => {
    if (answered) return;
    if (!live()) {
      answer(false, true);
      return;
    }
    if (encoder.complete(n)) {
      answer(true);
      return;
    }
    const left = ctx.env.waitMaxMs - (Date.now() - arrived);
    if (left <= 0 || !encoder.running()) {
      answer(false);
      return;
    }
    timer = setTimeout(check, Math.min(POLL_MS, left));
  };
  // The player went before the answer: the request is logged as it would be, cut short.
  ctx.res.on('close', () => answer(false));
  check();
}

function sessionSegment(ctx, { session, match, id }) {
  const { env } = ctx;
  const rung = env.rungs.find((r) => r.id === match[1]);
  const n = Number(match[2]);
  if (!rung || n >= env.segmentCount) return false;
  if (!session.encoder) {
    sendSegment(ctx, rung, n, {});
    return true;
  }
  const arrived = Date.now();
  ctx.arrival = { at: arrived, ms: clockMs(env), minute: minuteNow(env), began: performance.now() };
  const { restart, deleted } = session.encoder.request(n);
  waitForSegment(ctx, {
    encoder: session.encoder, rung, n, arrived, restart, deleted, live: () => env.sessions.get(id) === session,
  });
  return true;
}

const SESSION_ROUTES = [
  { pattern: /^\/master\.m3u8$/, handle: sessionMaster },
  { pattern: /^\/([a-z0-9]+)\/index\.m3u8$/, handle: sessionPlaylist },
  { pattern: /^\/([a-z0-9]+)\/seg-(\d+)\.ts$/, handle: sessionSegment },
];

function sessionRoute(ctx, match) {
  const session = ctx.env.sessions.get(match[1]);
  if (ctx.req.method === 'DELETE') {
    ctx.env.sessions.delete(match[1]);
    noContent(ctx, 'session-end');
    return;
  }
  if (!session) {
    sendJson(ctx, { error: 'No such session' }, { status: 404 });
    return;
  }
  const rest = match[2] ?? '';
  for (const route of SESSION_ROUTES) {
    const found = route.pattern.exec(rest);
    // A route returns false when the address named something it does not have (a rung, a segment).
    if (found && route.handle(ctx, { session, match: found, id: match[1] })) return;
  }
  sendJson(ctx, { error: 'Not found' }, { status: 404 });
}

// A playback id is a number in the order the stand-in met them. The id is held only in this table, to number the
// viewings, for the length of the run; it never reaches the log, the results or a response.
function viewingOf(env, id) {
  if (!env.viewings.has(id)) env.viewings.set(id, env.viewings.size);
  return env.viewings.get(id);
}

function readRungMs(source, rungs) {
  if (!isObject(source)) return null;
  const kept = {};
  for (const { id } of rungs) {
    if (isNumber(source[id])) kept[id] = source[id];
  }
  return kept;
}

// Entries with two figures and a rung name or null; the real server refuses a report holding any other.
function readStretches(source) {
  if (!Array.isArray(source)) return null;
  return source
    .filter((e) => isObject(e) && isNumber(e.needS) && isNumber(e.aheadS) && (e.rung === null || STRETCH_RUNGS.includes(e.rung)))
    .slice(0, MAX_STRETCHES)
    .map((e) => ({ needS: e.needS, aheadS: e.aheadS, rung: e.rung }));
}

const isMinuteRow = (row) => Array.isArray(row) && (row.length === 5 || row.length === 7) && row.every(isNumber);

// A fresh object, field by field from the lists above: nothing else the player sent is kept.
function readReport(value, rungs) {
  const report = {};
  for (const name of REPORT_NUMBERS) {
    if (isNumber(value[name])) report[name] = value[name];
  }
  for (const name of REPORT_FLAGS) {
    if (typeof value[name] === 'boolean') report[name] = value[name];
  }
  for (const [name, words] of REPORT_WORDS) {
    if (value[name] !== undefined && value[name] !== null) report[name] = oneOf(words, value[name]);
  }
  const rungMs = readRungMs(value.rungMs, rungs);
  if (rungMs) report.rungMs = rungMs;
  const stretches = readStretches(value.lookaheadStretches);
  if (stretches) report.lookaheadStretches = stretches;
  if (Array.isArray(value.minutes)) {
    report.minutes = value.minutes.filter(isMinuteRow).slice(0, MAX_MINUTES).map((row) => [...row]);
  }
  return report;
}

async function healthReport(ctx, match) {
  const { env } = ctx;
  const body = await readBody(ctx.req);
  if (body.tooLarge) {
    refuse(ctx);
    return;
  }
  if (body.cut) {
    // The player went mid-body: the request is only logged, as cut short. No report is kept, no viewing numbered.
    noContent(ctx, 'health');
    return;
  }
  const at = Date.now();
  const viewing = viewingOf(env, match[1]);
  const value = parseJson(body.text);
  env.healthReports.push(isObject(value)
    ? { at, ms: clockMs(env), minute: minuteNow(env), viewing, report: readReport(value, env.rungs) }
    : { at, viewing, unreadable: true, bytes: body.bytes });
  noContent(ctx, 'health');
}

async function progress(ctx) {
  const body = await readJson(ctx.req);
  if (body.tooLarge) {
    refuse(ctx);
    return;
  }
  const sent = body.cut ? undefined : body.value?.positionMs;
  const position = typeof sent === 'number' && Number.isFinite(sent) ? sent : null;
  sendJson(ctx, { positionMs: position ?? 0, watched: false }, { kind: 'progress', detail: { positionMs: position } });
}

// A subtitle track, a still, or anything else the player might ask for is not part of the replay. Preferences,
// playback state and the like are accepted and answered with a fixed null, whatever they said.
function otherApi(ctx) {
  if (ctx.req.method === 'GET') {
    sendJson(ctx, { error: 'Not found' }, { status: 404, kind: 'other' });
    return;
  }
  ctx.req.resume();
  sendBuffer(ctx, NULL_BODY, { headers: { 'Content-Type': 'application/json' }, kind: 'other' });
}

// A file of the build when the address names one; an address with no extension is one of the app's own routes.
function serveStatic({ env, res, path }) {
  const file = env.files.get(path);
  if (file) {
    res.writeHead(200, { 'Content-Type': file.type, 'Cache-Control': 'no-store' });
    res.end(file.body);
  } else if (extname(path)) {
    res.writeHead(404, { 'Content-Type': 'text/plain', 'Cache-Control': 'no-store' });
    res.end('Not found');
  } else {
    res.writeHead(200, { 'Content-Type': MIME['.html'], 'Cache-Control': 'no-store' });
    res.end(env.index);
  }
}

const ROUTES = [
  { pattern: /^\/__replay\/state$/, handle: replayState },
  { pattern: /^\/__replay\/blank$/, handle: replayBlank },
  { pattern: /^\/api\/session$/, handle: apiSession },
  { pattern: /^\/api\/films\/\d+$/, handle: apiFilm },
  { pattern: /^\/api\/editions\/\d+\/sessions$/, method: 'POST', handle: createSession },
  { pattern: /^\/api\/editions\/\d+\/stream$/, handle: probe },
  { pattern: /^\/api\/sessions\/([0-9a-f-]{36})(\/.*)?$/, handle: sessionRoute },
  { pattern: /^\/api\/playback-health\/([0-9a-f-]{36})$/, method: 'PUT', handle: healthReport },
  { pattern: /^\/api\/.*\/progress$/, handle: progress },
  { pattern: /^\/api\//, handle: otherApi },
  { pattern: /^/, handle: serveStatic },
];

async function handle(env, req, res) {
  let path;
  try {
    path = new URL(req.url, 'http://localhost').pathname;
  } catch {
    res.writeHead(400, { 'Content-Type': 'text/plain', 'Cache-Control': 'no-store' });
    res.end('Bad request');
    return;
  }
  const ctx = { env, req, res, path };
  for (const route of ROUTES) {
    const match = route.pattern.exec(path);
    if (match && (!route.method || route.method === req.method)) {
      await route.handle(ctx, match);
      return;
    }
  }
}

// An error nobody expected: a fixed text, never the error's own.
function fail(res) {
  if (res.headersSent) {
    res.destroy();
    return;
  }
  res.writeHead(500, { 'Content-Type': 'text/plain', 'Cache-Control': 'no-store' });
  res.end('Internal error');
}

// ---- starting it --------------------------------------------------------------------------------------------

// Every file of the build, by the address it is served at.
function readBuild(distDir) {
  const files = new Map();
  for (const entry of readdirSync(distDir, { recursive: true, withFileTypes: true })) {
    if (!entry.isFile()) continue;
    const full = join(entry.parentPath, entry.name);
    const address = `/${relative(distDir, full).split(sep).join('/')}`;
    files.set(address, { body: readFileSync(full), type: MIME[extname(address)] ?? 'application/octet-stream' });
  }
  return files;
}

// The fixture film, with nothing to resume and no subtitle tracks to fetch.
function readFilm(filmPath) {
  const film = JSON.parse(readFileSync(filmPath, 'utf8'));
  const edition = film.editions[0];
  edition.positionMs = 0;
  edition.subtitleStreams = [];
  film.editions = [edition];
  if (film.progress) film.progress.positionMs = 0;
  film.preferences = { ...film.preferences, editionId: edition.id, quality: 'auto', subtitle: 'off' };
  return film;
}

function listen(server, port) {
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, '127.0.0.1', resolve);
  });
}

/**
 * Starts the stand-in. `trace` is kbit/s for each record minute; the replay clock starts at `startMinute` when
 * `startClock()` is called (the driver calls it as it opens the player) and runs in real time. `wire` scales every
 * segment's size: 1 is the rung's average bitrate alone; the default, WIRE, adds the MPEG-TS wrapping that trace.mjs
 * assumes when it turns a stalled minute's film into kbit/s, so film and trace then weigh the same. `port: 0` takes
 * any free port; the answer says which. `encoderSpeed` (how many times faster than real time the server's encoder
 * runs; null leaves it out) models the server's one playhead per session (playhead.mjs); `encoderStartMs` is the time from an encoder run's start to its first segment (default 0); `waitMaxMs` is how long a
 * request waits for a segment before 503, the server's six seconds unless a test shortens it.
 */
export async function startStandIn({
  port, distDir, filmPath, template, trace, startMinute = 0, rttMs = 115, sourceBitrate = 10_000_000, fill = 'media',
  wire = WIRE, encoderSpeed = null, encoderStartMs = 0, waitMaxMs = WAIT_MAX_MS,
}) {
  if (!validWire(wire)) throw new Error('wire is a number from 1 to 1.5');
  if (encoderSpeed !== null && !(Number.isFinite(encoderSpeed) && encoderSpeed > 0)) {
    throw new Error('encoderSpeed is null or a number above 0');
  }
  if (!(Number.isFinite(encoderStartMs) && encoderStartMs >= 0)) throw new Error('encoderStartMs is a number from 0');
  if (!(Number.isFinite(waitMaxMs) && waitMaxMs > 0)) throw new Error('waitMaxMs is a number above 0');
  const files = readBuild(distDir);
  const index = files.get('/index.html')?.body;
  if (!index) throw new Error('the build holds no index.html');
  const film = readFilm(filmPath);
  const rungs = ladder(sourceBitrate);
  const segmentMs = Math.round(template.seconds * 1000);
  const env = {
    rungs, segmentMs, segmentCount: Math.floor(film.editions[0].runtimeMs / segmentMs), film, template, trace,
    startMinute, rttMs, fill, wire, encoderSpeed, encoderStartMs, waitMaxMs, files, index, t0: null, open: false, link: null, waiting: 0,
    sessions: new Map(), viewings: new Map(), requests: [], healthReports: [], counter: createByteCounter(),
  };
  env.link = createLink(() => rateBps(env));
  let lastTick = performance.now();
  const ticker = setInterval(() => {
    const now = performance.now();
    env.link.advance(now - lastTick);
    lastTick = now;
  }, TICK_MS);
  const server = http.createServer((req, res) => {
    handle(env, req, res).catch(() => fail(res));
  });
  server.keepAliveTimeout = 120000;
  try {
    await listen(server, port);
  } catch (error) {
    clearInterval(ticker);
    throw error;
  }
  const bound = server.address();

  return {
    rungs, segmentMs, segmentCount: env.segmentCount, requests: env.requests, healthReports: env.healthReports,
    port: bound.port, address: bound.address,
    // The link takes the trace's rate at once: what the open link had saved is cut to what that rate may save (a
    // twentieth of a second of it or 3,000 bytes, whichever is more; nothing at all when the rate is 0), not spent
    // on the first answers.
    startClock() {
      env.t0 = Date.now();
      env.link.advance(0);
    },
    /** Bytes the link let through in each replay minute so far (see `createByteCounter`). */
    get delivered() {
      return env.counter.list();
    },
    clockMs: () => clockMs(env),
    minute: () => minuteNow(env),
    kbps: () => rateBps(env) / 1000,
    /** The replay is over: API answers go straight through, so the last report is not lost to a dead minute. */
    finish() {
      env.open = true;
    },
    async stop() {
      clearInterval(ticker);
      server.closeAllConnections();
      await new Promise((resolve) => server.close(resolve));
    },
  };
}
