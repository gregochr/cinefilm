// The look-ahead's input to the film bank (train look-ahead plan, 2b; outside-bank plan, decision 2). Pure: the
// clock is passed in, nothing is stored beyond the last fresh match held in the object, nothing is sent.
// Output contract is `bankInput` in hlsjsEngine.js: `{ need, timeToStretch, stretchBegun }` or null.
import { stretchesAhead, MAX_FIX_AGE_S, MIN_SPEED_MPS } from './trackMatch.js';

/** The rule lapses when the feed has not answered for longer than this (exactly this long still counts). */
export const LAPSE_MS = 300_000;
/** A stretch further away than this (and not begun) gives no input. Same as the engine's `BANK_ARM_WITHIN_S`. */
export const ARM_WITHIN_S = 1800;
/** Inside a stretch, a stretch starting within this many seconds raises the need (decision 7). */
export const NEXT_WITHIN_S = 1200;

/**
 * @typedef {Object} BankInput
 * @property {number} need F, seconds of film to hold ahead
 * @property {number} timeToStretch seconds to the stretch; 0 or less once it has begun
 * @property {boolean} stretchBegun
 */

/**
 * A holder of the last fresh match (with the tracks it was matched against), turning it into the bank's input at
 * any later time.
 *
 * A fix in date (age within the limit) that is too slow to match means the train is stopped on the line: the feed
 * is answering, so the lapse clock restarts, and the advance along the track freezes until a fix moves again.
 * A fix too old, or no fix, refreshes nothing.
 *
 * @returns {{
 *   noteMatch: (result: { match: object|null, fresh: boolean }, fix: object, tracks: object[], nowMs: number) => void,
 *   input: (nowMs: number) => BankInput|null,
 * }}
 */
export function createLookaheadInput() {
  let match = null;
  let tracks = null;
  let answeredMs = null; // last time the feed answered for this match (a fresh match or a stop)
  let baseMs = 0; // elapsed time counts from here ...
  let offsetS = 0; // ... plus this
  let frozen = false;
  let stoppedSeenMs = -Infinity; // the time of the latest in-date slow fix: the train was still stopped then

  const elapsedS = (nowMs) => (frozen ? offsetS : offsetS + (nowMs - baseMs) / 1000);

  return {
    /** Takes each `matchFix` result with the fix and the tracks it was made from, and when. */
    noteMatch(result, fix, tracksUsed, nowMs) {
      if (result.fresh) {
        match = result.match;
        tracks = tracksUsed;
        answeredMs = nowMs;
        baseMs = nowMs;
        stoppedSeenMs = -Infinity;
        offsetS = 0;
        frozen = false;
        return;
      }
      if (match !== null && nowMs - answeredMs > LAPSE_MS) match = null; // lapsed: only a fresh match revives it
      if (match === null || !(fix.ageS <= MAX_FIX_AGE_S)) return;
      if (!(fix.speedMps >= MIN_SPEED_MPS)) {
        // Stopped on the line: answer the lapse, and stop the clock where the train stopped (the fix is `ageS`
        // old), never earlier than the advance already made.
        if (!frozen) {
          offsetS = Math.max(offsetS, elapsedS(nowMs) - fix.ageS);
          frozen = true;
        }
        answeredMs = nowMs;
        stoppedSeenMs = nowMs - fix.ageS * 1000;
      } else if (frozen) {
        // Moving again but off the map: advance again from the frozen value (nothing refreshed), counting from
        // when the fix says the train moved, but never from before the last time it was seen stopped.
        baseMs = Math.max(stoppedSeenMs, nowMs - fix.ageS * 1000);
        frozen = false;
      }
    },

    /** The bank's input at `nowMs`, or null (no recent answer, no stretch ahead, or none within 30 minutes). */
    input(nowMs) {
      if (answeredMs === null || nowMs - answeredMs > LAPSE_MS) return null;
      const e = elapsedS(nowMs);
      const ahead = stretchesAhead(tracks, match)
        .map((s) => ({ need: s.need, to: s.secondsTo - e, end: s.secondsToEnd - e }))
        .filter((s) => s.end >= 0); // the rest are passed
      const first = ahead[0];
      if (first === undefined) return null;
      if (first.to <= 0) {
        // Begun: the next stretch starting within 20 minutes counts too.
        const need = Math.max(...ahead.filter((s) => s.to <= NEXT_WITHIN_S).map((s) => s.need));
        return { need, timeToStretch: first.to, stretchBegun: true };
      }
      // Sorted by time to the stretch, so every later one is no nearer.
      if (first.to > ARM_WITHIN_S) return null;
      return { need: first.need, timeToStretch: first.to, stretchBegun: false };
    },
  };
}
