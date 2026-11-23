// Above 480p the byte budget cannot hold the full eight minutes, so a depth test cannot gate the climb (hls.js
// would not fetch until the bank was under its target, the test would fail and the cap would flip). The test
// is headroom instead: the link's recent rate against the rung's AVERAGE bitrate
// (docs/engineering/step-up-bank-plan.md, PR A). Pure: the engine feeds it the levels, the bank and the rates.

/** Rungs taller than this are the champagne rungs; 480p is the highest rung with no cost in depth. */
export const CHAMPAGNE_ABOVE_HEIGHT = 480;
/** A rung above 480p is stepped up to at this many times its average bitrate in headroom or more. */
export const STEP_UP_FACTOR = 3;
/** And stayed on at this many times or more (hysteresis). */
export const STAY_FACTOR = 2;
/** A bank counts as full this many seconds short of hls.js's own target for the level fetched. */
export const BANK_MARGIN_S = 30;
/** Rates needed before any step up above 480p (staying on a rung needs none). */
export const MIN_SAMPLES = 3;

/**
 * Seconds of film hls.js keeps ahead while fetching a level of `maxBitrate` (bit/s): its own target,
 * `min(max(8 × budgetBytes / maxBitrate, minS), maxS)` (`base-stream-controller.ts`, `getMaxBufferLength`).
 */
export function bankTargetS(maxBitrate, { budgetBytes, minS, maxS }) {
  const byBudget = maxBitrate > 0 ? (8 * budgetBytes) / maxBitrate : maxS;
  return Math.min(Math.max(byBudget, minS), maxS);
}

// The average bitrate of a level in bit/s: the variant's AVERAGE-BANDWIDTH (hls.js `averageBitrate`), else its
// bitrate. The peak is hls.js's `maxBitrate`, else the bitrate.
const averageOf = (level) => level.averageBitrate || level.bitrate;
const peakOf = (level) => level.maxBitrate || level.bitrate;

/** The level Auto may always use: the highest at 480p or lower (index 0 when there is none). */
export function champagneBase(levels) {
  let base = 0;
  levels.forEach((level, index) => {
    if (!(level.height > CHAMPAGNE_ABOVE_HEIGHT)) base = index;
  });
  return base;
}

/**
 * The highest level index Auto may reach: the 480p level (or the ladder's top, if lower) plus every rung above
 * it, climbing one rung at a time, whose test passes. `levels` are hls.js levels, lowest first. A rung up to
 * `applied` (the cap Auto is under) is kept at STAY_FACTOR times its average; a new one needs STEP_UP_FACTOR
 * times, at least MIN_SAMPLES rates (`count`), is only the rung directly above `applied` (one new rung at a
 * time), and needs `aheadS` seconds in hand of the bank hls.js fills at the rung below it (`bank`:
 * `{ budgetBytes, minS, maxS }`). `kbps` is the lower middle of the recent first-byte fragment rates (null when
 * there are none); `troubled` is fragment trouble since the last load. With no rate or with trouble the
 * ceiling is the 480p level.
 */
export function champagneCeiling({ levels, applied, count, aheadS, kbps, troubled, bank }) {
  const base = champagneBase(levels);
  if (troubled || !Number.isFinite(kbps) || kbps <= 0) return base;
  let ceiling = base;
  for (let i = base + 1; i < levels.length; i += 1) {
    const ratio = (kbps * 1000) / averageOf(levels[i]);
    const held = i <= applied;
    if (!held && (i > applied + 1 || count < MIN_SAMPLES)) break; // one new rung at a time, on evidence
    const full = aheadS >= bankTargetS(peakOf(levels[i - 1]), bank) - BANK_MARGIN_S;
    const passes = held ? ratio >= STAY_FACTOR : ratio >= STEP_UP_FACTOR && full;
    if (!passes) break;
    ceiling = i;
  }
  return ceiling;
}
