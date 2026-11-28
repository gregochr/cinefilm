// The bank's decider for the requests hls.js makes (outside-bank-plan.md, decisions 4 and 8): the object the bank
// loader (`bankLoader.js`) asks about every media fragment load. It answers from the bank, waits on the front's
// one fetch of a position (the same server request serves hls.js and the bank), or lets the inner loader have it.
// No hls.js, no JSX: the clock and timers are passed in. Nothing installs it yet (B5b wires it).

/** The text of the retryable 503 raised for a fragment held at another rung; the engine's ERROR handler reads it. */
export const BANK_RUNG_TEXT = 'bank-rung';
const STATUS_TEXT = { 503: 'Service Unavailable', 404: 'Not Found', 410: 'Gone' };

/**
 * Options:
 * - `bank`: the segment bank; `front`: the bank front (`bankFront.js`).
 * - `levelOfRung(rungId)` and `rungOfLevel(level)`: the engine's maps between hls.js level indexes and rung ids;
 *   `rungOfLevel` returns null for a level the bank does not keep (above 480p).
 * - `wrongRung(level)`: the engine's `bankWrongRung`, called with the level the fragment is held at, before the
 *   request fails.
 * - `onRelease()`: called once, synchronously, when the bank is turning off and hls.js asks for a fragment it does
 *   not hold, before that request goes to the inner loader (B5b releases the manual level there; the decider then
 *   goes to mode 'off').
 * - `firstByteMs` (10 s) and `loadMs` (120 s): hls.js's own first-byte and load limits for a fragment, counted
 *   from hls.js's request; past them the request gets `timeout` and the front's fetch carries on.
 * - `setTimer(fn, ms)` and `clearTimer(id)`.
 *
 * Mode, set by B5b with `setMode(mode)`:
 * - 'off' (the start): nothing is held, `armed()` is false, every request passes.
 * - 'armed': `armed()` is true, so the loader copies what the inner loader loads to `fragmentLoaded`; every
 *   request passes.
 * - 'on': the front is started; requests are decided as below.
 * - 'turningOff': B5b has called `front.finish()` when leaving 'on' (the decider never ends it and sends it no
 *   update then): the fetch in flight finishes and a request for that fragment waits on it as usual; held film is
 *   still answered, any other request for a fragment not held calls `onRelease()` and passes, and the mode
 *   becomes 'off'.
 *
 * Rules for a request while 'on' (`pos` is `front.position().seq`):
 * - held at the requested rung: answered from the bank; held at another rung: `wrongRung(heldLevel)`, then the
 *   request fails with `{ code: 503, text: BANK_RUNG_TEXT }`; nothing goes to the server. Neither resumes the
 *   front.
 * - the rung boundary: `sn` held at another rung while the bank holds `sn + 1` at the asked rung is hls.js's
 *   re-ask after a level change (it takes `sn` again when its buffered end falls short of the cut). It is not
 *   failed, which would send hls.js back to `sn + 1` at the other level in a loop: `sn` is fetched at the asked
 *   rung, as a gap of one (stored over the held copy of `sn` only), and the front goes back to its own rung and
 *   past the held run when hls.js asks for the held `sn + 1`. Before that fetch, a boundary copy of `sn` at the
 *   asked rung (`bank.getBoundary`, fetched by the front after the first segment of a new rung) answers it with
 *   no request, and a copy still being fetched is waited on (`front.boundaryFetching`, `front.subscribeBoundary`: one
 *   server request serves hls.js too). While turning off, a boundary request releases like any other, unless it is the fragment the
 *   front has in flight.
 * - not held and `sn === pos`: waits on the front's fetch of it (`front.subscribe` and `front.fetchNow`: exactly
 *   one server request). What the front does after it (carry on, or not at F or the limit) is the front's.
 * - not held and `sn < pos` with held film above `sn` (a gap behind the front): the front is moved to `sn` and
 *   the request waits as above; the front then waits (a gap is held: `update` hands it a need of 0) until hls.js asks for film held beyond
 *   the gap, `endGap()` is called (a seek or a clear), or the mode changes; the front then returns to the end of
 *   the held film. A quiet spell does not count.
 * - a gap also ends, with the need restored, on a request for a fragment not held with no held film above it
 *   (hls.js skips film it already has; held film may have been dropped behind the playhead).
 * - any other request not held (beyond the front, or behind it with no held film above): `front.start({ seq: sn })`
 *   and the request waits as above, so every media request has the one fetcher and the front carries on past it.
 *   `pass` is only for 'off', 'armed' and 'turningOff'.
 * A waiting request: a first byte or progress ends its first-byte limit; a 503, 404 or 410 answer to the fetch
 * (also a 503 Retry-After wait already under way) is handed over at once as that error; the fetch's own timeout
 * ends the wait with `timeout` at once; a network error is not (the limits count). If the front drops the position
 * (a stop, a start or a move) the wait ends with `timeout` and hls.js asks again.
 *
 * B5b must call: `setMode`, `update({ filmAhead, need })` in place of `front.update` (the decider forwards it),
 * `setRung(rung)` in place of `front.setRung` (a rung chosen during a boundary fetch is kept for the return),
 * `endGap()` on a seek or clear, and pass `front`'s own `onStopped` and `onSessionLost`. `fragmentLoaded` also
 * tells the front a successful server answer happened (`front.serverAnswered()`), which resumes a paused front.
 */
export function createBankRequests({
  bank,
  front,
  levelOfRung,
  rungOfLevel,
  wrongRung = () => {},
  onRelease = () => {},
  firstByteMs = 10000,
  loadMs = 120000,
  setTimer = (fn, ms) => setTimeout(fn, ms),
  clearTimer = (id) => clearTimeout(id),
}) {
  let mode = 'off';
  let gap = false;
  let gapHighest = -1;
  // The front's own rung while a rung-boundary fetch (below) has it at the asked rung; null otherwise.
  let ownRung = null;
  let lastUpdate = null;
  // The frag of each request passed to the inner loader, for `fragmentLoaded` (which is not given its film times).
  const passed = new Map();

  const isArmed = () => mode !== 'off' && mode !== 'turningOff';

  function frontUpdate(input) {
    if (input !== null) front.update(input);
  }

  function beginGap(sn, rung, outOfOrder = false) {
    if (!gap) {
      gap = true;
      // Before start(): it pumps at once, and a fetch of its own is not wanted while the gap is held.
      frontUpdate(lastUpdate && { ...lastUpdate, need: 0 });
    }
    gapHighest = Math.max(gapHighest, sn);
    front.start({ seq: sn, rung, outOfOrder });
  }

  function clearGap() {
    gap = false;
    gapHighest = -1;
    if (ownRung !== null) front.setRung(ownRung);
    ownRung = null;
  }

  function endGap(nextSeq = null) {
    if (!gap) return;
    const rung = ownRung ?? front.chosenRung();
    clearGap();
    // Move the front first, while its need is still 0, so only the onward fetch starts, never one at the old spot.
    if (nextSeq !== null) front.start({ seq: nextSeq, rung });
    frontUpdate(lastUpdate);
  }

  // Answers from a held segment (a copy), or null when it is held at another rung (`wrongRung` is told first).
  function fromHeld(request, held) {
    const heldLevel = levelOfRung(held.rung);
    if (heldLevel === request.level) {
      return { action: 'answer', bytes: held.bytes, timings: held.timings };
    }
    wrongRung(heldLevel);
    return null;
  }

  // The first request for film not held while turning off: the manual level is released and hls.js loads it itself.
  function release() {
    onRelease();
    mode = 'off';
    passed.clear();
    return { action: 'pass' };
  }

  const rungError = () => ({ code: 503, text: BANK_RUNG_TEXT });

  // `boundary`: the request waits on the boundary copy the front is fetching, not on the next position's fetch.
  function wait(request, reply, boundary = false) {
    const { sn } = request;
    let finished = false;
    let unsubscribe = null;
    let firstByteTimer = null;
    let loadTimer = null;
    const stopFirstByte = () => {
      if (firstByteTimer !== null) clearTimer(firstByteTimer);
      firstByteTimer = null;
    };
    // Ends the wait: no more timers, no more events. `unsubscribe` is not yet set when the front replays a wait
    // from inside `subscribe`; the code after it ends the subscription then.
    const teardown = () => {
      finished = true;
      stopFirstByte();
      clearTimer(loadTimer);
      unsubscribe?.();
    };
    const finish = (deliver) => {
      if (finished) return;
      teardown();
      deliver();
    };
    const fail = (status) => finish(() => reply.error({ code: status, text: STATUS_TEXT[status] }));
    const timeout = () => finish(() => reply.timeout());
    firstByteTimer = setTimer(timeout, firstByteMs);
    loadTimer = setTimer(timeout, loadMs);
    // A Retry-After wait already under way is told to the listener before subscribe returns.
    unsubscribe = (boundary ? front.subscribeBoundary : front.subscribe)(sn, {
      onFirstByte: stopFirstByte,
      onProgress: stopFirstByte,
      onDone: (segment) =>
        finish(() => {
          // No segment to read (a copy the store did not keep and was not handed): an ordinary failure.
          if (!segment?.bytes) {
            reply.error({ code: 503, text: STATUS_TEXT[503] });
            return;
          }
          const decided = fromHeld(request, segment);
          if (decided === null) reply.error(rungError());
          else reply.success(decided.bytes, decided.timings);
        }),
      onServerAnswer: (status) => {
        if (status === 503 || status === 404 || status === 410) fail(status);
      },
      onError: (kind) => {
        if (kind === 'timeout') timeout();
      },
      onAbort: timeout,
    });
    if (finished) {
      unsubscribe();
      return { action: 'wait', cancel: () => {} };
    }
    if (!boundary) front.fetchNow();
    return {
      action: 'wait',
      cancel: () => {
        if (!finished) teardown();
      },
    };
  }

  // One server request for `sn` at the asked rung through the front's one fetcher, held as a gap of one: the front
  // goes back to its own rung and to the end of the held run when hls.js asks for the held `sn + 1`.
  function fetchAtAskedRung(request, reply, askedRung) {
    ownRung ??= front.chosenRung();
    beginGap(request.sn, askedRung, true);
    return wait(request, reply);
  }

  return {
    setMode(next) {
      if (next === mode) return;
      mode = next;
      if (next !== 'on') clearGap(); // B5b stops the front; no update is sent to it
      if (next === 'off') passed.clear();
    },
    armed: isArmed,
    update(input) {
      lastUpdate = input;
      if (mode === 'turningOff') return; // the front is stopped: it is sent nothing
      front.update(gap ? { ...input, need: 0 } : input);
    },
    endGap: () => endGap(),
    /**
     * The front's rung (the bank's choice): call this, not `front.setRung`. While a rung-boundary fetch holds the
     * front on the asked rung it is kept for when the front goes back to its own; otherwise it is `front.setRung`.
     */
    setRung(rung) {
      if (ownRung !== null) ownRung = rung;
      else front.setRung(rung);
    },

    fragmentRequest(request, reply) {
      const { sn, frag } = request;
      if (mode === 'off' || mode === 'armed') {
        if (mode === 'armed') passed.set(sn, frag);
        return { action: 'pass' };
      }
      const held = bank.getSegment(sn);
      if (held !== null) {
        if (gap && sn > gapHighest) endGap(bank.nextAfterRun(held.start));
        // The rung boundary: hls.js re-asks `sn` at the level of the held `sn + 1` after a level change, and its
        // fragment finder takes `sn` again when the buffered end falls short of the cut. Failing it would send
        // hls.js back to `sn + 1` at the other level in a loop: the boundary copy answers it, or, with none,
        // `sn` is fetched at the asked rung, once.
        const askedRung = rungOfLevel(request.level);
        if (askedRung !== null && held.rung !== askedRung) {
          const copy = bank.getBoundary(sn, askedRung);
          if (copy !== null) return { action: 'answer', bytes: copy.bytes, timings: copy.timings };
          const inFlight = front.boundaryFetching();
          // The copy is on its way: the one fetch serves hls.js too.
          if (inFlight?.seq === sn && inFlight.rung === askedRung)
            return wait(request, reply, true);
          if (bank.rungOf(sn + 1) === askedRung) {
            if (mode === 'turningOff') {
              // The front fetches nothing new: only the fetch it has in flight can serve it.
              return front.fetching() === sn ? wait(request, reply) : release();
            }
            return fetchAtAskedRung(request, reply, askedRung);
          }
        }
        const decided = fromHeld(request, held);
        if (decided !== null) return decided;
        reply.error(rungError());
        return { action: 'wait' };
      }
      if (mode === 'turningOff') return front.fetching() === sn ? wait(request, reply) : release();
      const pos = front.position();
      // The front's own rung: while a boundary fetch has it on the asked rung, the one it will return to.
      const rung = ownRung ?? pos.rung;
      const heldAbove = bank.hasSegmentAbove(sn);
      // A gap holds only while held film lies above what hls.js asks for: hls.js skips film it already has, and
      // held film may have been dropped behind the playhead. Otherwise the gap ends, and the need is restored.
      const endsGap = gap && !heldAbove;
      if (endsGap) clearGap();
      if (sn < pos.seq && heldAbove) beginGap(sn, rung);
      else if (sn !== pos.seq) front.start({ seq: sn, rung }); // the one fetcher goes there
      if (endsGap) frontUpdate(lastUpdate);
      if (gap) gapHighest = Math.max(gapHighest, sn);
      return wait(request, reply);
    },

    fragmentLoaded({ sn, level, bytes, timings }) {
      front.serverAnswered();
      const frag = passed.get(sn);
      passed.delete(sn);
      const rung = rungOfLevel(level);
      if (frag === undefined || rung === null) return;
      bank.putSegment({ seq: sn, rung, start: frag.start, end: frag.end, bytes, timings });
    },

    playlistText(context) {
      if (mode !== 'on' && mode !== 'turningOff') return null;
      const rung = rungOfLevel(context.level);
      return rung === null ? null : bank.getPlaylist(rung);
    },
  };
}
