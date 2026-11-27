// Decision 7 of the outside-bank plan: which rung the bank's front fetches at before a bad stretch.
// Pure: no state between calls, so the next stretch is always chosen afresh. The caller times the calls
// (the first after MIN_MEASURED_S of wall time spent fetching, then once a minute); this says whether a
// choice may be made now and what it is.

export const MIN_MEASURED_S = 20;

const usable = (n) => Number.isFinite(n) && n > 0;
const atLeast0 = (n) => Number.isFinite(n) && n >= 0;

/**
 * `filmAhead` (finite, 0 or more), `timeToStretch` (finite, 0 or more) and `need` (F, finite, above 0) are
 * seconds; `stretchBegun` and `atLimit` are booleans. `w` and `d` are the window's waiting and transfer time
 * per film second fetched at the rung R whose rate is `rate`; `measuredWallS` is the seconds of fetching
 * (wall time, not film seconds) the window holds. `candidates` are `{ id, rate }` for every rung up to 480p,
 * rates from hls.js's levels.
 *
 * Returns `null` (keep the front's rung) when `need`, `filmAhead` or `timeToStretch` is not usable, the
 * stretch has begun, film ahead already reaches F, the bank is at its limit, under MIN_MEASURED_S of fetching
 * is measured, nothing was measured (`w + d` not above 0, or a rate that
 * is not positive: no division by zero), or no candidate has a usable rate. Otherwise `{ id, projections }`:
 * the highest rung whose projected film ahead at the stretch's start reaches F, or when none does the rung
 * with the highest projection (the higher rate on a tie); `projections` lists `{ id, projection }` per
 * candidate, lowest rate first.
 */
export function chooseBankRung({
  filmAhead,
  timeToStretch,
  need,
  stretchBegun,
  atLimit,
  w,
  d,
  measuredWallS,
  rate,
  candidates,
}) {
  if (!usable(need) || !atLeast0(filmAhead) || !atLeast0(timeToStretch)) return null;
  if (stretchBegun || atLimit || filmAhead >= need || !(measuredWallS >= MIN_MEASURED_S))
    return null;
  if (!usable(rate) || !(w >= 0) || !(d >= 0) || !(w + d > 0)) return null;
  const rungs = candidates.filter((c) => usable(c.rate)).sort((a, b) => a.rate - b.rate);
  if (rungs.length === 0) return null;

  const projections = rungs.map((c) => ({
    id: c.id,
    rate: c.rate,
    // Film fetched per second of wall time is 1 / (w + d × rate(c) / rate(R)); above 1 the bank grows.
    projection: filmAhead + timeToStretch * (1 / (w + (d * c.rate) / rate) - 1),
  }));
  let best = projections[0];
  for (const p of projections) {
    if (best.projection < need ? p.projection >= best.projection : p.projection >= need) best = p;
  }
  return {
    id: best.id,
    projections: projections.map(({ id, projection }) => ({ id, projection })),
  };
}
