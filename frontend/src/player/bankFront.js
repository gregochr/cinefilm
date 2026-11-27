// The bank's front (outside-bank-plan.md, decisions 4, 7 and 8): the bank's own fetcher of film ahead. One request
// at a time, in film order, one segment each into the store. The server has one playhead per session and restarts
// its encoder for any request behind or far beyond it, so the front never asks out of order and never asks twice
// at once. No hls.js, no JSX: `fetch` and the clock are passed in.
import { RECONNECT_MS } from './hlsjsEngine.js';
import { NO_PROGRESS_MS } from './noProgressLoader.js';
import { fragmentKbps } from './throughput.js';

export const FRONT_MAX_HEIGHT = 480;
export const FIRST_BYTE_MS = 10000;
export const NETWORK_RETRY_MS = 2000;
export const RETRY_AFTER_CAP_MS = 5000;
export const WINDOW_S = 60;

const CANCELLED = { outcome: 'cancelled' };

function parsePlaylist(text, playlistUrl) {
  const base = new URL(playlistUrl, globalThis.location.href);
  const segments = new Map();
  let seq = 0;
  let start = 0;
  let duration = null;
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (line.startsWith('#EXT-X-MEDIA-SEQUENCE:')) {
      seq = Number(line.slice('#EXT-X-MEDIA-SEQUENCE:'.length));
    } else if (line.startsWith('#EXTINF:')) {
      duration = parseFloat(line.slice('#EXTINF:'.length));
    } else if (line !== '' && !line.startsWith('#') && duration !== null) {
      segments.set(seq, { url: new URL(line, base).href, start, end: start + duration });
      seq += 1;
      start += duration;
      duration = null;
    }
  }
  return segments;
}

/**
 * `rungs` are `{ id, height, kbps, playlistUrl }`; the front uses those up to 480p. `bank` is a segment bank.
 * `onThroughput(kbps)` follows each completed fetch; `onStopped()` a 410, `onSessionLost()` a 404.
 * Calls: `arm()` fetches the playlists; `start({ seq, rung })` begins fetching at `seq`; `movePast(seq)` says
 * film up to and including `seq` is held or asked for elsewhere; `update({ filmAhead, need })` tells it the film
 * ahead of the playhead and the need F (call it again as the bank drops film, for the limit); `setRung(id)`
 * applies from the next fetch; `serverAnswered()` says a request elsewhere had a successful answer; `window()` is
 * decision 7's measurement; `stop()` ends everything.
 */
export function createBankFront({
  rungs,
  bank,
  fetch: fetchFn = (...args) => globalThis.fetch(...args),
  now = () => Date.now(),
  setTimer = (fn, ms) => setTimeout(fn, ms),
  clearTimer = (id) => clearTimeout(id),
  reconnectMs = RECONNECT_MS,
  onThroughput = () => {},
  onStopped = () => {},
  onSessionLost = () => {},
}) {
  const eligible = rungs.filter((r) => r.height <= FRONT_MAX_HEIGHT);
  const byId = new Map(eligible.map((r) => [r.id, r]));
  const parsed = new Map();
  const entries = [];
  let armed = false;
  let running = false;
  let stopped = false;
  let busy = false;
  let paused = false;
  let current = null;
  let retryTimer = null;
  let pauseTimer = null;
  let next = 0;
  let wantedRung = null;
  let lockedRung = null;
  let waited = 0; // seconds waiting on the position being fetched: failed attempts, first bytes, retry waits
  let backoffs = 0;
  let filmAhead = 0;
  let need = 0;

  const clamp = (id) => (byId.has(id) ? id : eligible[eligible.length - 1]?.id);
  const wants = () => filmAhead < need && !bank.atLimit();

  function resetPosition() {
    current?.cancel();
    current = null;
    busy = false;
    if (retryTimer !== null) clearTimer(retryTimer);
    retryTimer = null;
    lockedRung = null;
    waited = 0;
    backoffs = 0;
  }

  async function attempt(url, ctx, controller, limit) {
    const startedAt = now();
    limit(FIRST_BYTE_MS);
    const response = await fetchFn(url, {
      credentials: 'same-origin',
      cache: 'no-store',
      signal: controller.signal,
    });
    if (response.status !== 200) {
      const header = Number(response.headers?.get?.('Retry-After'));
      return { outcome: 'status', status: response.status, retryAfter: header, startedAt };
    }
    const reader = response.body.getReader();
    const chunks = [];
    let firstAt = null;
    for (;;) {
      const { done, value } = await reader.read();
      if (done || ctx.aborted) break;
      firstAt ??= now();
      chunks.push(value);
      limit(NO_PROGRESS_MS);
    }
    const endAt = now();
    const bytes = new Uint8Array(chunks.reduce((n, c) => n + c.byteLength, 0));
    let at = 0;
    for (const c of chunks) {
      bytes.set(c, at);
      at += c.byteLength;
    }
    return { outcome: 'ok', bytes: bytes.buffer, startedAt, firstAt: firstAt ?? endAt, endAt };
  }

  // One request. Whatever its answer, an aborted one is reported as cancelled and nothing of it is used.
  async function request(url) {
    const controller = new AbortController();
    const ctx = { aborted: false };
    let timer = null;
    let timedOut = false;
    const stopTimer = () => {
      if (timer !== null) clearTimer(timer);
      timer = null;
    };
    const limit = (ms) => {
      stopTimer();
      timer = setTimer(() => {
        timedOut = true;
        controller.abort();
      }, ms);
    };
    ctx.cancel = () => {
      ctx.aborted = true;
      stopTimer();
      controller.abort();
    };
    current = ctx;
    const startedAt = now();
    let result;
    try {
      result = await attempt(url, ctx, controller, limit);
    } catch {
      result = { outcome: timedOut ? 'timeout' : 'network', startedAt };
    }
    stopTimer();
    return ctx.aborted ? CANCELLED : result;
  }

  function resume() {
    if (pauseTimer !== null) clearTimer(pauseTimer);
    pauseTimer = null;
    if (!paused) return;
    paused = false;
    pump();
  }

  function pause() {
    paused = true;
    // Runs from the pause itself: nothing during the pause restarts it.
    pauseTimer ??= setTimer(resume, reconnectMs);
  }

  function later(ms) {
    waited += ms / 1000;
    retryTimer = setTimer(() => {
      retryTimer = null;
      pump();
    }, ms);
  }

  function failed(res) {
    waited += (now() - res.startedAt) / 1000;
    if (res.outcome !== 'status') {
      later(NETWORK_RETRY_MS);
    } else if (res.status === 410) {
      stop();
      onStopped();
    } else if (res.status === 404) {
      stop();
      onSessionLost();
    } else if (res.status === 503 && res.retryAfter > 0) {
      later(Math.min(res.retryAfter * 1000 * 2 ** backoffs, RETRY_AFTER_CAP_MS));
      backoffs += 1;
    } else if (res.status === 503) {
      pause();
    } else {
      later(NETWORK_RETRY_MS);
    }
  }

  async function run(url, done) {
    busy = true;
    const res = await request(url);
    if (res === CANCELLED) return;
    busy = false;
    if (res.outcome === 'ok') {
      done(res);
      waited = 0;
      backoffs = 0;
      pump();
    } else {
      failed(res);
    }
  }

  function fetchSegment() {
    const rung = lockedRung ?? wantedRung;
    const segment = parsed.get(rung)?.get(next);
    if (segment === undefined) return; // the end of the film: nothing more to fetch
    lockedRung = rung;
    const seq = next;
    run(segment.url, (res) => {
      const timings = { start: res.startedAt, first: res.firstAt, end: res.endAt };
      const wait = waited + (res.firstAt - res.startedAt) / 1000;
      entries.push({
        rung,
        film: segment.end - segment.start,
        w: wait,
        d: (res.endAt - res.firstAt) / 1000,
      });
      if (entries.length > 200) entries.shift();
      const { start, end } = segment;
      bank.putSegment({ seq, rung, start, end, bytes: res.bytes, timings });
      const kbps = fragmentKbps({ loading: timings, loaded: res.bytes.byteLength });
      if (kbps !== null) onThroughput(kbps);
      lockedRung = null;
      next = seq + 1;
    });
  }

  function pump() {
    if (stopped || busy || paused || retryTimer !== null || !armed) return;
    const missing = eligible.find((r) => bank.getPlaylist(r.id) === null);
    if (missing !== undefined) {
      run(missing.playlistUrl, (res) => {
        const text = new TextDecoder().decode(res.bytes);
        parsed.set(missing.id, parsePlaylist(text, missing.playlistUrl));
        bank.putPlaylist(missing.id, text);
      });
    } else if (running && wants()) {
      fetchSegment();
    }
  }

  function stop() {
    stopped = true;
    resetPosition();
    if (pauseTimer !== null) clearTimer(pauseTimer);
    pauseTimer = null;
  }

  return {
    arm() {
      armed = true;
      pump();
    },
    start({ seq, rung }) {
      if (stopped) return;
      resetPosition();
      next = seq;
      wantedRung = clamp(rung);
      running = true;
      pump();
    },
    movePast(seq) {
      if (stopped || seq < next) return;
      resetPosition();
      next = seq + 1;
      pump();
    },
    update(input) {
      filmAhead = input.filmAhead;
      need = input.need;
      pump();
    },
    setRung(id) {
      wantedRung = clamp(id);
    },
    serverAnswered: resume,
    /** `{ rung, rate, w, d, measuredS, measuredWallS }`: per film second, over the last 60 s of fetching. */
    window() {
      const rung = entries.at(-1)?.rung ?? null;
      let wall = 0;
      let from = entries.length;
      while (from > 0 && entries[from - 1].rung === rung && wall < WINDOW_S) {
        from -= 1;
        wall += entries[from].w + entries[from].d;
      }
      const held = entries.slice(from);
      const film = held.reduce((n, e) => n + e.film, 0);
      const sum = (key) => held.reduce((n, e) => n + e[key], 0);
      return {
        rung,
        rate: byId.get(rung)?.kbps ?? null,
        w: film > 0 ? sum('w') / film : 0,
        d: film > 0 ? sum('d') / film : 0,
        measuredS: film,
        measuredWallS: wall,
      };
    },
    stop,
  };
}
