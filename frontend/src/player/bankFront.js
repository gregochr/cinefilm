// The bank's front (outside-bank-plan.md, decisions 4, 7 and 8): the bank's own fetcher of film ahead. One request
// at a time, in film order, one segment each into the store. The server has one playhead per session and restarts
// its encoder for any request behind or far beyond it, so the front never asks out of order and never asks twice
// at once. No hls.js, no JSX: `fetch` and the clock are passed in.
import { NO_PROGRESS_MS } from './noProgressLoader.js';
import { RECONNECT_MS } from './reconnect.js';
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
      duration = Number.parseFloat(line.slice('#EXTINF:'.length));
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
 *
 * Hooks for the requests hls.js waits on (decisions 4 and 8), used by the bank loader:
 * - `pause()` is the pause of a plain 503 (a fatal network error from hls.js): the timer runs from the first
 *   pause and nothing restarts it; `isPaused()` says whether it is on. The time paused counts as waiting in the
 *   window's next entry.
 * - `position()` is `{ seq, rung }`, what the front will fetch next.
 * - `fetchNow()` fetches that position at once, whether or not the front is paused, wants film or is at its
 *   limit, with one server request; nothing new when a fetch is in flight or a retry is pending (that retry then
 *   goes ahead whatever the wants and the pause say, as do the retries of this fetch, and only those), or when it
 *   is not armed and started. A pause is left as it is (its timer untouched) unless the fetch succeeds, which
 *   resumes the front.
 * - `subscribe(seq, listener)` attaches to the front's fetch of `seq`, in flight now or the next one: the same
 *   server request serves both. The listener may have `onFirstByte()`, `onProgress(loadedBytes)`, `onDone(segment)`
 *   (a fresh `bank.getSegment(seq)`, after which the subscription ends), `onServerAnswer(status, headers)` for any
 *   answer other than 200 (the front goes on handling it itself), `onError('network' | 'timeout')` and
 *   `onAbort()`, once, when `stop()`, `start()` or `movePast()` drops the position (the subscription ends). Nothing
 *   else is called for an aborted fetch, a first byte already past is not replayed, and a listener must not
 *   throw. A subscriber to a position that is in a Retry-After or network-error wait at that moment hears that
 *   answer or error at once, before `subscribe` returns, then the retry's own events; never a pause's answer.
 *   Returns the function that unsubscribes.
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
  const listeners = new Map();
  const entries = [];
  let armed = false;
  let running = false;
  let stopped = false;
  let busy = false;
  let paused = false;
  let current = null;
  let retryTimer = null;
  let pauseTimer = null;
  // The stopwatch of decision 7: wall seconds since the last completed entry (or the last new position) in which
  // the front wanted film or had a segment fetch in flight, whether it was waiting, retrying, paused or fetching.
  let watch = 0;
  let watchMark = null; // when the running stretch began; null while it is stopped
  let next = 0;
  let wantedRung = null;
  let lockedRung = null;
  let backoffs = 0;
  let forced = false; // a fetch now is under way: its retries bypass the wants and pause checks
  let lastAnswer = null; // the answer or error that started the retry wait now pending: { seq, name, args }
  let filmAhead = 0;
  let need = 0;

  // The rungs come in the server's order (best first); the front's best is the greatest height up to 480p.
  const best = eligible.reduce(
    (a, r) => (a === undefined || r.height > a.height ? r : a),
    undefined,
  );
  const clamp = (id) => (byId.has(id) ? id : best?.id);
  const wants = () => filmAhead < need && !bank.atLimit();

  function syncWatch() {
    const on = !stopped && running && (wants() || (busy && current?.segment === true));
    if (on && watchMark === null) {
      watchMark = now();
    } else if (!on && watchMark !== null) {
      watch += (now() - watchMark) / 1000;
      watchMark = null;
    }
  }

  function emit(seq, name, ...args) {
    for (const l of [...(listeners.get(seq) ?? [])]) l[name]?.(...args);
  }

  // Tells the subscribers of every position `dropped` names that their fetch is gone, and forgets them.
  function dropSubscribers(dropped) {
    for (const [seq, set] of [...listeners]) {
      if (!dropped(seq)) continue;
      listeners.delete(seq);
      for (const l of [...set]) l.onAbort?.();
    }
  }

  const elapsed = () => watch + (watchMark === null ? 0 : (now() - watchMark) / 1000);

  function resetWatch() {
    watch = 0;
    watchMark = null;
    syncWatch();
  }

  // The parsed segments of a rung: from its playlist as fetched now, or as the bank already holds it (a new front
  // over a bank that kept its playlists). Undefined only when the playlist is not held.
  function segmentsOf(id) {
    if (!parsed.has(id)) {
      const text = bank.getPlaylist(id);
      if (text === null) return undefined;
      parsed.set(id, parsePlaylist(text, byId.get(id).playlistUrl));
    }
    return parsed.get(id);
  }

  // A new position drops the segment fetch in flight; a playlist fetch is not about a position and goes on.
  function resetPosition() {
    if (current?.segment) {
      current.cancel();
      current = null;
      busy = false;
    }
    if (retryTimer !== null) clearTimer(retryTimer);
    retryTimer = null;
    lockedRung = null;
    backoffs = 0;
    forced = false;
    lastAnswer = null;
    resetWatch();
  }

  async function attempt(url, { ctx, controller, limit, notify }) {
    const startedAt = now();
    limit(FIRST_BYTE_MS);
    const response = await fetchFn(url, {
      credentials: 'same-origin',
      cache: 'no-store',
      signal: controller.signal,
    });
    if (response.status !== 200) {
      const header = Number(response.headers?.get?.('Retry-After'));
      notify('onServerAnswer', response.status, response.headers);
      const { status, headers } = response;
      return { outcome: 'status', status, headers, retryAfter: header, startedAt };
    }
    const reader = response.body.getReader();
    const chunks = [];
    let firstAt = null;
    let loaded = 0;
    for (;;) {
      const { done, value } = await reader.read();
      if (done || ctx.aborted) break;
      if (firstAt === null) {
        firstAt = now();
        notify('onFirstByte');
      }
      chunks.push(value);
      loaded += value.byteLength;
      notify('onProgress', loaded);
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
  async function request(url, seq) {
    const controller = new AbortController();
    const ctx = { aborted: false, segment: seq !== null };
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
    const notify = (name, ...args) => {
      if (seq !== null && !ctx.aborted) emit(seq, name, ...args);
    };
    current = ctx;
    const startedAt = now();
    let result;
    try {
      result = await attempt(url, { ctx, controller, limit, notify });
    } catch {
      result = { outcome: timedOut ? 'timeout' : 'network', startedAt };
      notify('onError', result.outcome);
    }
    stopTimer();
    return ctx.aborted ? CANCELLED : result;
  }

  function endPause() {
    if (pauseTimer !== null) clearTimer(pauseTimer);
    pauseTimer = null;
    if (!paused) return;
    paused = false;
  }

  function resume() {
    const wasPaused = paused;
    endPause();
    if (wasPaused) pump();
  }

  function pause() {
    if (paused) return; // the timer runs from the first pause: nothing during it restarts it
    paused = true;
    pauseTimer = setTimer(resume, reconnectMs);
  }

  function later(ms, seq, name, ...args) {
    lastAnswer = seq === null ? null : { seq, name, args };
    retryTimer = setTimer(() => {
      retryTimer = null;
      lastAnswer = null;
      if (forced) fetchSegment();
      else pump();
    }, ms);
  }

  function failed(res, seq) {
    if (res.outcome !== 'status') {
      later(NETWORK_RETRY_MS, seq, 'onError', res.outcome);
    } else if (res.status === 410) {
      stop();
      onStopped();
    } else if (res.status === 404) {
      stop();
      onSessionLost();
    } else if (res.status === 503 && res.retryAfter > 0) {
      later(
        Math.min(res.retryAfter * 1000 * 2 ** backoffs, RETRY_AFTER_CAP_MS),
        seq,
        'onServerAnswer',
        res.status,
        res.headers,
      );
      backoffs += 1;
    } else if (res.status === 503) {
      forced = false; // only retries of a fetch now bypass the pause
      pause();
    } else {
      later(NETWORK_RETRY_MS, seq, 'onServerAnswer', res.status, res.headers);
    }
  }

  async function run(url, seq, done) {
    busy = true;
    const pending = request(url, seq);
    syncWatch(); // a segment fetch in flight counts even when film is no longer wanted
    const res = await pending;
    if (res === CANCELLED) return;
    busy = false;
    if (res.outcome === 'ok') {
      backoffs = 0;
      done(res);
      pump();
    } else {
      failed(res, seq);
      syncWatch();
    }
  }

  function fetchSegment() {
    const rung = lockedRung ?? wantedRung;
    const segments = segmentsOf(rung);
    if (segments === undefined) return;
    const segment = segments.get(next);
    if (segment === undefined) return; // the end of the film: nothing more to fetch
    lockedRung = rung;
    const seq = next;
    // Deliberately not awaited: request() settles every failure of the fetch as an outcome, so a rejection here
    // could only be a throw from the caller's onThroughput, which should surface, not be swallowed.
    void run(segment.url, seq, (res) => {
      endPause(); // a successful answer ends a pause
      const timings = { start: res.startedAt, first: res.firstAt, end: res.endAt };
      // The transfer is first byte to end; everything else the stopwatch holds is waiting. A fetch in flight when
      // film stopped being wanted still counts whole.
      const d = (res.endAt - res.firstAt) / 1000;
      const wall = Math.max(elapsed(), d);
      // A new rung starts a new measurement. Dropping old entries is memory upkeep only: window() itself stops
      // at WINDOW_S of wall time, so nothing here changes a measurement. Entries go only once the newer ones hold
      // WINDOW_S (no count cap: a fast link can fit many entries in the window).
      if (entries.at(-1)?.rung !== rung) entries.length = 0;
      entries.push({ rung, film: segment.end - segment.start, w: wall - d, d });
      let held = 0;
      let oldest = entries.length;
      while (oldest > 0 && held < WINDOW_S) {
        oldest -= 1;
        held += entries[oldest].w + entries[oldest].d;
      }
      entries.splice(0, oldest);
      const { start, end } = segment;
      bank.putSegment({ seq, rung, start, end, bytes: res.bytes, timings });
      const kbps = fragmentKbps({ loading: timings, loaded: res.bytes.byteLength });
      if (kbps !== null) onThroughput(kbps);
      lockedRung = null;
      forced = false;
      next = seq + 1;
      filmAhead += segment.end - segment.start; // until the next update(): no overshoot of F
      emit(seq, 'onDone', bank.getSegment(seq));
      listeners.delete(seq);
      resetWatch();
    });
  }

  function pump() {
    if (stopped || busy || paused || retryTimer !== null || !armed) return;
    const missing = eligible.find((r) => bank.getPlaylist(r.id) === null);
    if (missing !== undefined) {
      // run() never rejects, as above.
      void run(missing.playlistUrl, null, (res) => {
        endPause(); // a successful answer to any request ends a pause
        const text = new TextDecoder().decode(res.bytes);
        bank.putPlaylist(missing.id, text);
      });
    } else if (running && wants()) {
      forced = false; // an ordinary fetch: its retries obey the wants and the pause
      fetchSegment();
    }
  }

  function stop() {
    stopped = true;
    current?.cancel();
    current = null;
    busy = false;
    resetPosition();
    if (pauseTimer !== null) clearTimer(pauseTimer);
    pauseTimer = null;
    dropSubscribers(() => true);
  }

  return {
    arm() {
      armed = true;
      pump();
    },
    start({ seq, rung }) {
      if (stopped) return;
      resetPosition();
      dropSubscribers((q) => q !== seq);
      next = seq;
      wantedRung = clamp(rung);
      running = true;
      syncWatch();
      pump();
    },
    movePast(seq) {
      if (stopped || seq < next) return;
      resetPosition();
      dropSubscribers((q) => q <= seq);
      next = seq + 1;
      pump();
    },
    update(input) {
      filmAhead = input.filmAhead;
      need = input.need;
      syncWatch();
      pump();
    },
    setRung(id) {
      wantedRung = clamp(id);
    },
    serverAnswered: resume,
    pause,
    isPaused: () => paused,
    position: () => ({ seq: next, rung: lockedRung ?? wantedRung }),
    fetchNow() {
      if (stopped || !armed || !running) return;
      if (!eligible.every((r) => bank.getPlaylist(r.id) !== null)) return;
      forced = true; // a retry of this position goes ahead whatever the wants and the pause say
      if (!busy && retryTimer === null) fetchSegment();
    },
    subscribe(seq, listener) {
      const set = listeners.get(seq) ?? new Set();
      set.add(listener);
      listeners.set(seq, set);
      if (retryTimer !== null && lastAnswer?.seq === seq)
        listener[lastAnswer.name]?.(...lastAnswer.args);
      return () => {
        set.delete(listener);
        if (set.size === 0 && listeners.get(seq) === set) listeners.delete(seq);
      };
    },
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
