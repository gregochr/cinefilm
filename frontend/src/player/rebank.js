// Replace part of a full bank one rung up (docs/engineering/step-up-bank-plan.md, PR B). When the bank is full at
// one rung below what the link could carry, the film from playhead + REBANK_KEEP_S onwards is thrown away and
// fetched again a rung higher, never above 480p. Pure: the engine feeds it the levels, the banked fragments, the
// playhead and the times of the last trouble, step down, re-bank and undo. It starts only once hls.js is already
// fetching the new rung: a raised `minAutoBitrate` does not make hls.js step up (when no level between the floor
// and the cap is eligible it keeps its load level), and in Auto hls.js names its level on every tick, so a level
// below the new rung means it has refused it (docs/engineering/stuck-rung-plan.md).
import { champagneBase } from './champagne.js';
import { lowerMiddle } from './throughput.js';

/** The bank is at least this many seconds ahead before a re-bank. */
export const REBANK_MIN_AHEAD_S = 470;
/** The film this many seconds ahead of the playhead is never touched. */
export const REBANK_KEEP_S = 300;
/** A re-bank in progress is undone when the bank falls under this many seconds. */
export const REBANK_UNDO_BELOW_S = 270;
/** A re-bank is done once the bank is back to this many seconds. */
export const REBANK_DONE_AT_S = 460;
/** No re-bank within this long (ms) of trouble, a step down or the last re-bank. */
export const REBANK_QUIET_MS = 120000;
/** No re-bank within this long (ms) of an undo. */
export const REBANK_COOLDOWN_MS = 600000;
/** The link's headroom against the new rung's peak bitrate. */
export const REBANK_HEADROOM = 3;
/** Fewest headroom rates the decision reads. */
export const REBANK_MIN_RATES = 3;

const peakOf = (level) => level.maxBitrate || level.bitrate;
// Milliseconds since `at`; Infinity when the thing never happened.
const since = (now, at) => (Number.isFinite(at) ? now - at : Infinity);

/**
 * Whether to re-bank now: null, or `{ level, from }`: fetch again at `level` (one above the banked one), the
 * flush starting at `from`, the start of the first banked fragment at or after playhead + REBANK_KEEP_S (a flush
 * that starts mid-fragment brings that fragment back at the old rung). `levels` are hls.js levels lowest first,
 * `applied` the cap Auto is under, `fetched` the level hls.js is fetching (null before the first LEVEL_SWITCHING;
 * it must already be at `level` or above: a raised floor does not make hls.js step up, so if it is not on the new
 * rung it has refused it and the film would come back at the old one), `banked` the fragments `{ start, end, level }`, `headroom` the recent
 * first-byte fragment rates in kbit/s, `inFlight` whether a fragment is loading, `troubled` fragment trouble
 * since the last load, and `lastTroubleAt`, `lastStepDownAt`, `lastRebankAt`, `lastUndoAt` times in ms (null
 * when it never happened) read against `now`. Every condition must hold.
 */
export function rebankDecision({
  levels,
  applied,
  fetched = null,
  banked,
  playhead,
  aheadS,
  headroom,
  inFlight,
  troubled,
  now,
  lastTroubleAt = null,
  lastStepDownAt = null,
  lastRebankAt = null,
  lastUndoAt = null,
}) {
  if (!(aheadS >= REBANK_MIN_AHEAD_S) || inFlight || troubled) return null;
  if (since(now, lastUndoAt) < REBANK_COOLDOWN_MS) return null;
  const quiet = Math.min(
    since(now, lastTroubleAt),
    since(now, lastStepDownAt),
    since(now, lastRebankAt),
  );
  if (quiet < REBANK_QUIET_MS) return null;
  const keepFrom = playhead + REBANK_KEEP_S;
  let from = null;
  for (const f of banked) {
    if (f.start >= keepFrom && (from === null || f.start < from)) from = f.start;
  }
  if (from === null) return null;
  const past = banked.filter((f) => f.end > from);
  const n = past[0].level;
  if (!past.every((f) => f.level === n)) return null;
  const next = n + 1;
  if (!levels[next] || next > champagneBase(levels) || (applied ?? 0) < next) return null;
  if (fetched == null || fetched < next) return null;
  if (headroom.length < REBANK_MIN_RATES) return null;
  const kbps = lowerMiddle(headroom);
  if (!(kbps >= (REBANK_HEADROOM * peakOf(levels[next])) / 1000)) return null;
  return { level: next, from };
}
