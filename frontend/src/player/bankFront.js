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
 *   answer other than 200 (the front goes on handling it itself; after a 404 or 410 it is the subscribers' last
 *   event, with no `onAbort`), `onError('network' | 'timeout')` and
 *   `onAbort()`, once, when `stop()`, `start()` or `movePast()` drops the position (the subscription ends). Nothing
 *   else is called for an aborted fetch, a first byte already past is not replayed, and a listener must not
 *   throw. A subscriber to a position that is in a Retry-After wait at that moment hears that 503 at once, before
 *   `subscribe` returns, then the retry's own events; a network-error or timeout wait and a pause's 503 are not
 *   replayed. A forced retry goes ahead only while the position has a subscriber. `start()` at the position of a
 *   fetch now with subscribers keeps them and carries the fetch on with one new request. If a listener's callback
 *   stops, starts or moves the front, the listeners after it hear only their `onAbort`. A listener that subscribes
 *   from inside `onDone` stays subscribed for the next fetch of that position (or its `onAbort`).
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
  // A segment the front has just fetched at a new rung leaves the one before it to fetch at that rung (a boundary
  // copy, `segmentBank.js`): { seq, rung }, or null.
  let boundaryDue = null;
  let finishing = false; // `finish()` was called: nothing new is fetched
  // The next fetch is out of order (`start({ outOfOrder })`: a request hls.js waits on at a rung it asked for):
  // it is not measured and leaves no boundary copy due.
  let outOfOrder = false;
  const boundaryListeners = new Map(); // seq -> listeners of a boundary fetch of it (`subscribeBoundary`)

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

  // A listener hears an event only while it is still subscribed: one that a callback of another listener has
  // dropped (a stop, a start or a move inside it) has heard its `onAbort` and nothing after it.
  function emit(seq, name, ...args) {
    const set = listeners.get(seq);
    if (set === undefined) return;
    for (const l of [...set]) {
      if (listeners.get(seq) === set && set.has(l)) l[name]?.(...args);
    }
  }

  // Forgets the subscribers of every position `dropped` names and returns their sets. They are told with
  // abortSubscribers once the front's new state is set, so a callback that calls back in (a fetch now from its
  // onAbort, a stop) sees the new position, and no set is reached twice.
  function detachSubscribers(dropped) {
    const detached = [];
    for (const [seq, set] of [...listeners]) {
      if (!dropped(seq)) continue;
      listeners.delete(seq);
      detached.push(set);
    }
    return detached;
  }

  function abortSubscribers(detached) {
    for (const set of detached) {
      for (const l of [...set]) {
        if (set.delete(l)) l.onAbort?.();
      }
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

  function emitBoundary(seq, name, ...args) {
    const set = boundaryListeners.get(seq);
    if (set === undefined) return;
    for (const l of [...set]) {
      if (boundaryListeners.get(seq) === set && set.has(l)) l[name]?.(...args);
    }
  }

  // The boundary fetch under way is cut: its subscribers hear `onAbort`.
  function abortBoundary() {
    const sets = [...boundaryListeners.values()];
    boundaryListeners.clear();
    abortSubscribers(sets);
  }

  // A new position drops the segment fetch in flight; a playlist fetch is not about a position and goes on.
  function resetPosition() {
    abortBoundary(); // the copy due is forgotten below, in flight or not: whoever waits on it is cut loose
    if (current?.segment) {
      current.cancel();
      current = null;
      busy = false;
    }
    boundaryDue = null;
    outOfOrder = false;
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
  async function request(url, seq, boundary = false) {
    const controller = new AbortController();
    const ctx = { aborted: false, segment: seq !== null, boundary };
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
      if (seq === null || ctx.aborted) return;
      if (boundary) emitBoundary(seq, name, ...args);
      else emit(seq, name, ...args);
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

  // `answer` is `{ seq, name, args }` for a wait a new subscriber is told of; null for any other.
  function later(ms, answer = null) {
    lastAnswer = answer;
    retryTimer = setTimer(() => {
      retryTimer = null;
      lastAnswer = null;
      // A forced retry goes ahead only while someone is waiting on the position; so does a retry while finishing.
      if ((forced || finishing) && listeners.get(next)?.size > 0) {
        fetchSegment();
      } else {
        forced = false; // nobody waits any more
        pump();
      }
    }, ms);
  }

  function failed(res, seq) {
    if (finishing && res.outcome !== 'status') {
      if (listeners.get(seq)?.size > 0) later(NETWORK_RETRY_MS);
    } else if (res.outcome !== 'status') {
      later(NETWORK_RETRY_MS);
    } else if (res.status === 410) {
      listeners.delete(seq); // the answer was the subscribers' last event
      stop();
      onStopped();
    } else if (res.status === 404) {
      listeners.delete(seq);
      stop();
      onSessionLost();
    } else if (res.status === 503 && res.retryAfter > 0) {
      if (finishing && !(listeners.get(seq)?.size > 0)) return; // nobody waits: stop at once
      later(Math.min(res.retryAfter * 1000 * 2 ** backoffs, RETRY_AFTER_CAP_MS), {
        seq,
        name: 'onServerAnswer',
        args: [res.status, res.headers],
      });
      backoffs += 1;
    } else if (res.status === 503) {
      forced = false; // only retries of a fetch now bypass the pause
      pause();
    } else {
      later(NETWORK_RETRY_MS);
    }
  }

  // A boundary copy that fails is dropped, never retried: a 404 or 410 stops the front and a plain 503 pauses it, as
  // any fetch's; whoever waits on the copy is cut loose at once (`onAbort`: hls.js asks again, and the decider
  // fetches the segment on demand, or releases while turning off). The front then goes on, a request waiting on the
  // next position first, so a copy that keeps failing can never hold it up.
  function failedBoundary(res, seq) {
    if (res.outcome === 'status' && [404, 410].includes(res.status)) {
      boundaryListeners.delete(seq);
      failed(res, seq);
      return;
    }
    const plain503 = res.outcome === 'status' && res.status === 503 && !(res.retryAfter > 0);
    if (plain503) failed(res, seq);
    abortBoundary();
    boundaryDue = null;
    if (!plain503) afterBoundary();
  }

  // The front goes on after a boundary fetch: a request waiting on the next position was held up by it and goes
  // first, whatever the wants say.
  function afterBoundary() {
    if (forced && !finishing && listeners.get(next)?.size > 0) fetchSegment();
    else pump();
  }

  async function run(url, seq, done, boundary = false) {
    busy = true;
    const pending = request(url, seq, boundary);
    syncWatch(); // a segment fetch in flight counts even when film is no longer wanted
    const res = await pending;
    if (res === CANCELLED) return;
    busy = false;
    if (res.outcome === 'ok') {
      backoffs = 0;
      done(res);
      pump();
    } else {
      if (boundary) failedBoundary(res, seq);
      else failed(res, seq);
      syncWatch();
    }
  }

  // The window's entry for a completed fetch (decision 7) and its throughput for the health record.
  function record(rung, segment, res) {
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
  }

  function report(timings, res) {
    const kbps = fragmentKbps({ loading: timings, loaded: res.bytes.byteLength });
    if (kbps !== null) onThroughput(kbps);
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
      const outOfTurn = outOfOrder; // not measured, and no boundary copy: it is behind or beside the run
      outOfOrder = false;
      if (!outOfTurn) record(rung, segment, res);
      const { start, end } = segment;
      bank.putSegment({ seq, rung, start, end, bytes: res.bytes, timings });
      report(timings, res);
      lockedRung = null;
      forced = false;
      next = seq + 1;
      const before = bank.rungOf(seq - 1);
      if (!outOfTurn && before !== null && before !== rung) {
        boundaryDue = { seq: seq - 1, rung };
      }
      filmAhead += segment.end - segment.start; // until the next update(): no overshoot of F
      // One listener at a time, each leaving the set before it is called, so a stop, start or move inside one
      // aborts only those not yet called; one that subscribes from inside onDone stays for the next fetch.
      const set = listeners.get(seq);
      for (const l of [...(set ?? [])]) {
        if (listeners.get(seq) !== set || !set.delete(l)) continue;
        l.onDone?.(bank.getSegment(seq));
      }
      if (set?.size === 0 && listeners.get(seq) === set) listeners.delete(seq);
      resetWatch();
    });
  }

  // True while a boundary copy is still due: its segment is held at another rung and has no copy at this one.
  function boundaryNeeded() {
    if (boundaryDue === null) return false;
    const { seq, rung } = boundaryDue;
    const held = bank.rungOf(seq);
    // Still right behind the front: hls.js asks for it again only just after a change of level.
    const behind = next - seq <= 3;
    if (!behind || held === null || held === rung || bank.boundaryRungOf(seq) === rung)
      boundaryDue = null;
    return boundaryDue !== null;
  }

  // The segment before one just fetched at a new rung, at that rung, through the same one fetcher: hls.js asks for
  // it again after a change of level. A copy that has not landed by the time the front is three segments past it
  // (`boundaryNeeded`) is dropped: fetching it then would send the server's playhead backwards, and the decider's
  // on-demand fetch covers a later re-ask. Its failure is `failedBoundary`'s.
  function fetchBoundary() {
    const { seq, rung } = boundaryDue;
    const segment = segmentsOf(rung)?.get(seq);
    if (segment === undefined) {
      boundaryDue = null;
      return;
    }
    void run(
      segment.url,
      seq,
      (res) => {
        endPause();
        const timings = { start: res.startedAt, first: res.firstAt, end: res.endAt };
        bank.putBoundary(seq, rung, res.bytes, timings);
        report(timings, res);
        boundaryDue = null;
        // One listener at a time, each leaving the set before it is called.
        const set = boundaryListeners.get(seq);
        for (const l of [...(set ?? [])]) {
          if (boundaryListeners.get(seq) !== set || !set.delete(l)) continue;
          l.onDone?.(bank.getBoundary(seq, rung));
        }
        if (set?.size === 0 && boundaryListeners.get(seq) === set) boundaryListeners.delete(seq);
        resetWatch();
        afterBoundary();
      },
      true,
    );
  }

  function forceFetch() {
    if (stopped || finishing || !armed || !running) return;
    if (!eligible.every((r) => bank.getPlaylist(r.id) !== null)) return;
    forced = true; // a retry of this position goes ahead whatever the wants and the pause say
    if (busy && current?.boundary && boundaryListeners.size === 0) {
      // A request waits on the position: the boundary copy gives way (it stays due), unless hls.js waits on it.
      current.cancel();
      current = null;
      busy = false;
    }
    if (!busy && retryTimer === null) fetchSegment();
  }

  function pump() {
    if (stopped || finishing || busy || paused || retryTimer !== null || !armed) return;
    const missing = eligible.find((r) => bank.getPlaylist(r.id) === null);
    if (missing !== undefined) {
      // run() never rejects, as above.
      void run(missing.playlistUrl, null, (res) => {
        endPause(); // a successful answer to any request ends a pause
        const text = new TextDecoder().decode(res.bytes);
        bank.putPlaylist(missing.id, text);
      });
    } else if (running && wants() && boundaryNeeded()) {
      fetchBoundary();
    } else if (running && wants()) {
      forced = false; // an ordinary fetch: its retries obey the wants and the pause
      fetchSegment();
    }
  }

  function stop() {
    stopped = true;
    boundaryDue = null;
    current?.cancel();
    current = null;
    busy = false;
    resetPosition();
    if (pauseTimer !== null) clearTimer(pauseTimer);
    pauseTimer = null;
    abortSubscribers(detachSubscribers(() => true));
    abortBoundary();
  }

  return {
    arm() {
      armed = true;
      pump();
    },
    start({ seq, rung, outOfOrder: offTurn = false }) {
      if (stopped || finishing) return;
      // A fetch now for subscribers at this very position carries on, with one new request.
      const keep = forced && seq === next && listeners.get(seq)?.size > 0;
      resetPosition();
      const dropped = detachSubscribers((q) => q !== seq);
      next = seq;
      outOfOrder = offTurn;
      wantedRung = clamp(rung);
      running = true;
      syncWatch();
      abortSubscribers(dropped);
      // An onAbort may have unsubscribed the kept listener or moved the front: check again.
      if (keep && next === seq && listeners.get(seq)?.size > 0) forceFetch();
      pump();
    },
    movePast(seq) {
      if (stopped || finishing || seq < next) return;
      resetPosition();
      const dropped = detachSubscribers((q) => q <= seq);
      next = seq + 1;
      abortSubscribers(dropped);
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
    /**
     * Ends the front without cutting what is under way: no new fetch is started (no pump, no boundary copy, no
     * retry for a position nobody waits on), a fetch in flight runs to its end (its subscribers hear it as
     * ever and a success is stored), and a retry of that same position (a 503 with Retry-After, a network error)
     * goes ahead only while a subscriber still waits. `stop()` is the cut; only a destroy uses it.
     */
    finish() {
      if (stopped || finishing) return;
      finishing = true;
      if (retryTimer !== null && !(listeners.get(next)?.size > 0)) {
        clearTimer(retryTimer);
        retryTimer = null;
        lastAnswer = null;
        lockedRung = null;
      }
    },
    /** True once `finish()` was called and nothing is left under way: no fetch in flight, no retry pending. */
    isFinished: () => finishing && !busy && retryTimer === null,
    /** The sequence number of the segment fetch under way now (a retry wait included), or null. */
    fetching: () =>
      lockedRung !== null &&
      ((busy && current?.segment && !current.boundary) || retryTimer !== null)
        ? next
        : null,
    /** `{ seq, rung }` of the boundary copy being fetched now, or null. */
    boundaryFetching: () =>
      busy && current?.boundary && boundaryDue !== null
        ? { seq: boundaryDue.seq, rung: boundaryDue.rung }
        : null,
    /**
     * Like `subscribe`, for the boundary copy being fetched (`boundaryFetching`): `onFirstByte`, `onProgress`,
     * `onServerAnswer`, `onError`, `onDone(copy)` (a fresh `bank.getBoundary`) and `onAbort` (the fetch cut by a
     * start, a move or a stop). Returns the function that unsubscribes.
     */
    subscribeBoundary(seq, listener) {
      const set = boundaryListeners.get(seq) ?? new Set();
      set.add(listener);
      boundaryListeners.set(seq, set);
      return () => {
        if (!set.delete(listener) || set.size > 0 || boundaryListeners.get(seq) !== set) return;
        boundaryListeners.delete(seq);
      };
    },
    serverAnswered: resume,
    pause,
    isPaused: () => paused,
    position: () => ({ seq: next, rung: lockedRung ?? wantedRung }),
    /** The rung the front has chosen (`start`, `setRung`), not the one a fetch in flight is locked to. */
    chosenRung: () => wantedRung,
    fetchNow: forceFetch,
    subscribe(seq, listener) {
      const set = listeners.get(seq) ?? new Set();
      set.add(listener);
      listeners.set(seq, set);
      if (retryTimer !== null && lastAnswer?.seq === seq)
        listener[lastAnswer.name]?.(...lastAnswer.args);
      return () => {
        // A stale call (after onAbort, or a second one) changes nothing: the position may have new subscribers.
        if (!set.delete(listener) || set.size > 0 || listeners.get(seq) !== set) return;
        listeners.delete(seq);
        if (seq === next) forced = false; // nobody waits: no more retries bypass the checks
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
