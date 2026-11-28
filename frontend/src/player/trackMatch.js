// Decision 4 of the train look-ahead plan: where a fix lies on the stored tracks. Pure: no state between calls
// (the caller passes the last match back), nothing stored, nothing sent.
//
// The map, as decision 2 has it and the server will serve it (PR 1b), has no journey ids, dates or train ids:
//
/**
 * @typedef {Object} TrackPoint
 * @property {number} latitude degrees
 * @property {number} longitude degrees
 * @property {number} courseDeg degrees clockwise from north, as recorded
 * @property {number} t seconds from the track's start
 *
 * @typedef {Object} Stretch
 * @property {number} from seconds along the track where the bad stretch starts
 * @property {number} to seconds along the track where it ends (from or later)
 * @property {number} need F, seconds of film to hold ahead for it
 *
 * @typedef {Object} Track
 * @property {TrackPoint[]} points in time order
 * @property {Stretch[]} stretches
 *
 * @typedef {Object} Place a fix on one track
 * @property {number} track index into the map's tracks
 * @property {number} along seconds from the track's start, interpolated on the matched segment
 * @property {boolean} reversed true when the train goes against the track's own direction
 * @property {number} distanceM metres from the fix to the segment
 *
 * @typedef {Object} Match
 * @property {Place[]} places the nearest qualifying segment of every track that has one (at least one)
 *
 * @typedef {Object} Fix
 * @property {number} latitude
 * @property {number} longitude
 * @property {number} courseDeg
 * @property {number} speedMps
 * @property {number} ageS
 */

export const MAX_DISTANCE_M = 300;
export const MAX_HEADING_DIFF_DEG = 45;
export const MAX_SEGMENT_GAP_S = 60;
export const MIN_SPEED_MPS = 5;
export const MAX_FIX_AGE_S = 60;
/** Segments shorter than this have no direction to compare with and are skipped. */
const MIN_SEGMENT_M = 1;
const EARTH_RADIUS_M = 6_371_000;
const RAD = Math.PI / 180;

/** Smallest absolute difference of two bearings, 0 to 180. */
const headingDiff = (a, b) => {
  const d = Math.abs(a - b) % 360;
  return d > 180 ? 360 - d : d;
};

/** The fix `speed x age` metres further along its course (spherical approximation; ages are under a minute). */
function advance(fix) {
  const metres = fix.speedMps * fix.ageS;
  const course = fix.courseDeg * RAD;
  return {
    latitude: fix.latitude + (metres * Math.cos(course)) / EARTH_RADIUS_M / RAD,
    longitude:
      fix.longitude +
      (metres * Math.sin(course)) / (EARTH_RADIUS_M * Math.cos(fix.latitude * RAD)) / RAD,
  };
}

/** The nearest qualifying segment of one track to the position, or null. */
function nearestOnTrack(points, at, courseDeg) {
  const cosLat = Math.cos(at.latitude * RAD);
  // Metres east and north of the position, flat around it (a few hundred metres across).
  const toXY = (p) => [
    (p.longitude - at.longitude) * RAD * EARTH_RADIUS_M * cosLat,
    (p.latitude - at.latitude) * RAD * EARTH_RADIUS_M,
  ];
  let best = null;
  for (let i = 0; i + 1 < points.length; i += 1) {
    const dt = points[i + 1].t - points[i].t;
    if (!(dt > 0 && dt <= MAX_SEGMENT_GAP_S)) continue;
    const [ax, ay] = toXY(points[i]);
    const [bx, by] = toXY(points[i + 1]);
    const dx = bx - ax;
    const dy = by - ay;
    const length2 = dx * dx + dy * dy;
    if (length2 < MIN_SEGMENT_M * MIN_SEGMENT_M) continue;
    const f = Math.min(1, Math.max(0, -(ax * dx + ay * dy) / length2));
    const distanceM = Math.hypot(ax + f * dx, ay + f * dy);
    if (distanceM > MAX_DISTANCE_M || (best !== null && distanceM >= best.distanceM)) continue;
    const bearing = (Math.atan2(dx, dy) / RAD + 360) % 360;
    const forward = headingDiff(courseDeg, bearing) <= MAX_HEADING_DIFF_DEG;
    if (!forward && headingDiff(courseDeg, bearing + 180) > MAX_HEADING_DIFF_DEG) continue;
    best = { distanceM, along: points[i].t + f * dt, reversed: !forward };
  }
  return best;
}

/**
 * Where the fix lies on the map. Returns `{ match, fresh }`: `fresh` is true only when this call made a new match;
 * otherwise `match` is `last` untouched. That is the case for a fix older than 60 s (refused; exactly 60 s is
 * used), for a speed under 5 m/s (the heading is not trusted, so the last match is kept; exactly 5 is matched),
 * and for a fix with no segment within 300 m whose direction, or its reverse, is within 45 degrees of the
 * course (nothing changes). A younger fix is moved on by speed x age first.
 *
 * @param {Track[]} tracks
 * @param {Fix} fix
 * @param {Match|null} [last]
 * @returns {{ match: Match|null, fresh: boolean }}
 */
export function matchFix(tracks, fix, last = null) {
  const kept = { match: last, fresh: false };
  if (!(fix.ageS <= MAX_FIX_AGE_S) || !(fix.speedMps >= MIN_SPEED_MPS)) return kept;
  const at = advance(fix);
  const places = [];
  tracks.forEach((track, index) => {
    const found = nearestOnTrack(track.points, at, fix.courseDeg);
    if (found !== null) places.push({ track: index, ...found });
  });
  return places.length === 0 ? kept : { match: { places }, fresh: true };
}

/**
 * Every stretch not yet passed on the tracks the match lies on, as
 * `{ track, stretch, need, secondsTo, secondsToEnd, begun }`. Times are what the track itself took: from the
 * place to the stretch's start (0 when begun) and to its end, counting down the track for a train going
 * against it. Sorted by `secondsTo`, then the larger need first.
 *
 * @param {Track[]} tracks
 * @param {Match|null} match
 */
export function stretchesAhead(tracks, match) {
  if (match === null) return [];
  const out = [];
  for (const place of match.places) {
    tracks[place.track].stretches.forEach((s, stretch) => {
      const toStart = place.reversed ? place.along - s.to : s.from - place.along;
      const toEnd = place.reversed ? place.along - s.from : s.to - place.along;
      if (toEnd < 0) return;
      out.push({
        track: place.track,
        stretch,
        need: s.need,
        secondsTo: Math.max(0, toStart),
        secondsToEnd: toEnd,
        begun: toStart <= 0,
      });
    });
  }
  return out.sort((a, b) => a.secondsTo - b.secondsTo || b.need - a.need);
}

/**
 * The soonest stretch: the shortest time any track took from the place to a stretch. Null when none is ahead.
 */
export function nextStretch(tracks, match) {
  return stretchesAhead(tracks, match)[0] ?? null;
}
