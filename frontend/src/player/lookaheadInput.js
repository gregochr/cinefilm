// The look-ahead's input to the film bank (train look-ahead plan, 2b; outside-bank plan, decision 2). Pure: the
// clock is passed in, nothing is stored beyond the last fresh match held in the object, nothing is sent.
// Output contract is `bankInput` in hlsjsEngine.js: `{ need, timeToStretch, stretchBegun }` or null.
import { stretchesAhead } from './trackMatch.js';

/** The rule lapses when the last fresh match is older than this (exactly this old still counts). */
export const LAPSE_MS = 300_000;
/** A stretch further away than this (and not begun) gives no input. Same as the engine's `BANK_ARM_WITHIN_S`. */
export const ARM_WITHIN_S = 1800;

/**
 * @typedef {Object} BankInput
 * @property {number} need F, seconds of film to hold ahead
 * @property {number} timeToStretch seconds to the stretch; 0 or less once it has begun
 * @property {boolean} stretchBegun
 */

/**
 * A holder of the last fresh match, turning it into the bank's input at any later time.
 *
 * @returns {{
 *   noteMatch: (result: { match: object|null, fresh: boolean }, nowMs: number) => void,
 *   input: (tracks: object[], nowMs: number) => BankInput|null,
 * }}
 */
export function createLookaheadInput() {
  let match = null;
  let matchedAtMs = null;

  return {
    /** Records a fresh match and when it was made; a result that is not fresh changes nothing. */
    noteMatch(result, nowMs) {
      if (!result.fresh) return;
      match = result.match;
      matchedAtMs = nowMs;
    },

    /** The bank's input at `nowMs`, or null (no recent match, no stretch ahead, or none within 30 minutes). */
    input(tracks, nowMs) {
      if (matchedAtMs === null || nowMs - matchedAtMs > LAPSE_MS) return null;
      const elapsedS = (nowMs - matchedAtMs) / 1000;
      for (const s of stretchesAhead(tracks, match)) {
        if (s.secondsToEnd - elapsedS < 0) continue; // passed: look at the next
        const timeToStretch = s.secondsTo - elapsedS;
        if (timeToStretch <= 0) return { need: s.need, timeToStretch, stretchBegun: true };
        // Sorted by time to the stretch, so every later one is no nearer.
        if (timeToStretch > ARM_WITHIN_S) return null;
        return { need: s.need, timeToStretch, stretchBegun: false };
      }
      return null;
    },
  };
}
