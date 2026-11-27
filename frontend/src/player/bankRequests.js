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
 * - 'turningOff': B5b has stopped the front (`front.stop()` when leaving 'on'; the decider never stops it and
 *   sends it no update then); held film is still answered, a request for a fragment not held calls
 *   `onRelease()` and passes, and the mode becomes 'off'.
 *
 * Rules for a request while 'on' (`pos` is `front.position().seq`):
 * - held at the requested rung: answered from the bank; held at another rung: `wrongRung(heldLevel)`, then the
 *   request fails with `{ code: 503, text: BANK_RUNG_TEXT }`; nothing goes to the server. Neither resumes the
 *   front.
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
  let lastUpdate = null;
  // The frag of each request passed to the inner loader, for `fragmentLoaded` (which is not given its film times).
  const passed = new Map();

  const isArmed = () => mode !== 'off' && mode !== 'turningOff';

  function frontUpdate(input) {
    if (input !== null) front.update(input);
  }

  function beginGap(sn, rung) {
    if (!gap) {
      gap = true;
      // Before start(): it pumps at once, and a fetch of its own is not wanted while the gap is held.
      frontUpdate(lastUpdate && { ...lastUpdate, need: 0 });
    }
    gapHighest = Math.max(gapHighest, sn);
    front.start({ seq: sn, rung });
  }

  function clearGap() {
    gap = false;
    gapHighest = -1;
  }

  function endGap(nextSeq = null) {
    if (!gap) return;
    clearGap();
    // Move the front first, while its need is still 0, so only the onward fetch starts, never one at the old spot.
    if (nextSeq !== null) front.start({ seq: nextSeq, rung: front.position().rung });
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

  const rungError = () => ({ code: 503, text: BANK_RUNG_TEXT });

  function wait(request, reply) {
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
    unsubscribe = front.subscribe(sn, {
      onFirstByte: stopFirstByte,
      onProgress: stopFirstByte,
      onDone: (segment) =>
        finish(() => {
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
    front.fetchNow();
    return {
      action: 'wait',
      cancel: () => {
        if (!finished) teardown();
      },
    };
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
      front.update(gap ? { ...input, need: 0 } : input);
    },
    endGap: () => endGap(),

    fragmentRequest(request, reply) {
      const { sn, frag } = request;
      if (mode === 'off' || mode === 'armed') {
        if (mode === 'armed') passed.set(sn, frag);
        return { action: 'pass' };
      }
      const held = bank.getSegment(sn);
      if (held !== null) {
        const decided = fromHeld(request, held);
        if (gap && sn > gapHighest) endGap(bank.nextAfterRun(held.start));
        if (decided !== null) return decided;
        reply.error(rungError());
        return { action: 'wait' };
      }
      if (mode === 'turningOff') {
        onRelease();
        mode = 'off';
        passed.clear();
        return { action: 'pass' };
      }
      const pos = front.position();
      const heldAbove = bank.hasSegmentAbove(sn);
      // A gap holds only while held film lies above what hls.js asks for: hls.js skips film it already has, and
      // held film may have been dropped behind the playhead. Otherwise the gap ends, and the need is restored.
      const endsGap = gap && !heldAbove;
      if (endsGap) clearGap();
      if (sn < pos.seq && heldAbove) beginGap(sn, pos.rung);
      else if (sn !== pos.seq) front.start({ seq: sn, rung: pos.rung }); // the one fetcher goes there
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
