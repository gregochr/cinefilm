import { describe, expect, it } from 'vitest';
import {
  MAX_DISTANCE_M,
  MAX_FIX_AGE_S,
  MAX_HEADING_DIFF_DEG,
  MAX_SEGMENT_GAP_S,
  MIN_SPEED_MPS,
  matchFix,
  nextStretch,
  stretchesAhead,
} from './trackMatch.js';

// Made-up geometry in mid-ocean, around 0.5 N 20.0 W: a track runs due north along one meridian, so its
// bearing is exactly 0 and a fix east of it is exactly that many metres away.
const R = 6_371_000;
const RAD = Math.PI / 180;
const LAT0 = 0.5;
const LON0 = -20;
const metresNorth = (m) => LAT0 + m / R / RAD;
const lonEast = (m, lat = LAT0) => LON0 + m / (R * Math.cos(lat * RAD)) / RAD;
const point = (northM, t, eastM = 0) => ({
  latitude: metresNorth(northM),
  longitude: lonEast(eastM),
  courseDeg: 0,
  t,
});

// 1,000 m north in 100 s: 10 m/s along the track, a point every 50 s.
const straight = (stretches = []) => ({
  points: [point(0, 0), point(500, 50), point(1000, 100)],
  stretches,
});
const fixAt = (northM, over = {}) => ({
  latitude: metresNorth(northM),
  longitude: LON0,
  courseDeg: 0,
  speedMps: 20,
  ageS: 0,
  ...over,
});
const match = (tracks, fix, last = null) => matchFix(tracks, fix, last);
// Positions are interpolated in floating point: compare seconds to a microsecond.
const rounded = (a) => ({
  ...a,
  secondsTo: Math.round(a.secondsTo * 1e6) / 1e6,
  secondsToEnd: Math.round(a.secondsToEnd * 1e6) / 1e6,
});

describe('the numbers of decision 4', () => {
  it('has the documented constants', () => {
    expect([
      MAX_DISTANCE_M,
      MAX_HEADING_DIFF_DEG,
      MAX_SEGMENT_GAP_S,
      MIN_SPEED_MPS,
      MAX_FIX_AGE_S,
    ]).toEqual([300, 45, 60, 5, 60]);
  });

  it('matches a segment 299.9 m away and not one 300.1 m away', () => {
    const near = { ...fixAt(500), longitude: lonEast(299.9, metresNorth(500)) };
    const far = { ...fixAt(500), longitude: lonEast(300.1, metresNorth(500)) };
    const hit = match([straight()], near);
    expect(hit.fresh).toBe(true);
    expect(hit.match.places[0].distanceM).toBeCloseTo(299.9, 1);
    expect(match([straight()], far)).toEqual({ match: null, fresh: false });
  });

  it('measures to the end of a segment, not the line beyond it', () => {
    // 200 m beyond the end and 200 m east is 283 m away; 250 m beyond and east is 354 m.
    const inside = { ...fixAt(1200), longitude: lonEast(200, metresNorth(1200)) };
    const outside = { ...fixAt(1250), longitude: lonEast(250, metresNorth(1250)) };
    expect(match([straight()], inside).fresh).toBe(true);
    expect(match([straight()], outside).fresh).toBe(false);
  });

  it('also measures before the start of a segment', () => {
    const hit = match([straight()], fixAt(-200));
    expect(hit.match.places[0].along).toBe(0);
  });

  it('accepts a course 44.9 degrees off the segment and refuses 45.1, either side of north', () => {
    expect(match([straight()], fixAt(500, { courseDeg: 44.9 })).fresh).toBe(true);
    expect(match([straight()], fixAt(500, { courseDeg: 45.1 })).fresh).toBe(false);
    expect(match([straight()], fixAt(500, { courseDeg: 315.1 })).fresh).toBe(true);
    expect(match([straight()], fixAt(500, { courseDeg: 314.9 })).fresh).toBe(false);
  });

  it('reads a track backwards: a course within 45 degrees of its reverse matches, marked reversed', () => {
    const back = match([straight()], fixAt(500, { courseDeg: 180 }));
    expect(back.match.places[0]).toMatchObject({ track: 0, reversed: true, along: 50 });
    expect(match([straight()], fixAt(500, { courseDeg: 224.9 })).fresh).toBe(true);
    expect(match([straight()], fixAt(500, { courseDeg: 225.1 })).fresh).toBe(false);
    expect(match([straight()], fixAt(500, { courseDeg: 135.1 })).fresh).toBe(true);
    expect(match([straight()], fixAt(500, { courseDeg: 134.9 })).fresh).toBe(false);
  });

  it('marks a forward match not reversed, with the time interpolated along the segment', () => {
    const forward = match([straight()], fixAt(250));
    expect(forward.match.places[0]).toMatchObject({ reversed: false, along: 25 });
  });

  it('joins points 60 s apart and not 61 s apart', () => {
    const gap = (s) => ({ points: [point(0, 0), point(1000, s)], stretches: [] });
    expect(match([gap(60)], fixAt(500)).fresh).toBe(true);
    expect(match([gap(61)], fixAt(500)).fresh).toBe(false);
  });

  it('never joins points at the same time or out of order', () => {
    const same = { points: [point(0, 5), point(1000, 5)], stretches: [] };
    const backwards = { points: [point(0, 10), point(1000, 5)], stretches: [] };
    expect(match([same], fixAt(500)).fresh).toBe(false);
    expect(match([backwards], fixAt(500)).fresh).toBe(false);
  });

  it('matches a fix at 5 m/s and keeps the last match, with the heading ignored, at 4.9', () => {
    const last = match([straight()], fixAt(100)).match;
    const slow = match([straight()], fixAt(500, { speedMps: 4.9, courseDeg: 180 }), last);
    expect(slow.match).toBe(last);
    expect(slow.fresh).toBe(false);
    const exactly = match([straight()], fixAt(500, { speedMps: 5 }), last);
    expect(exactly.fresh).toBe(true);
    expect(exactly.match.places[0].along).toBe(50);
  });

  it('keeps nothing when slow with no earlier match, and a stopped train matches nothing new', () => {
    expect(match([straight()], fixAt(500, { speedMps: 0 }))).toEqual({ match: null, fresh: false });
  });

  it('uses a fix 60 s old and refuses one 60.1 s old, keeping the last match', () => {
    const last = match([straight()], fixAt(100)).match;
    expect(match([straight()], fixAt(500, { ageS: 60, speedMps: 5 }), last).fresh).toBe(true);
    const old = match([straight()], fixAt(500, { ageS: 60.1 }), last);
    expect(old.match).toBe(last);
    expect(old.fresh).toBe(false);
  });

  it('moves a younger fix on by speed x age along its course before matching', () => {
    // 400 m before the segment: out of reach; 10 s at 20 m/s later it is 200 m before it.
    expect(match([straight()], fixAt(-400)).fresh).toBe(false);
    expect(match([straight()], fixAt(-400, { ageS: 10 })).fresh).toBe(true);
    // 500 m on, 10 s at 20 m/s: the matched place is at 700 m, 70 s along.
    expect(match([straight()], fixAt(500, { ageS: 10 })).match.places[0].along).toBeCloseTo(70, 3);
  });

  it('moves a fix on along an east course too', () => {
    // A track running due east from its start; the fix is 400 m west of it, going east at 20 m/s.
    const east = {
      points: [point(0, 0), { ...point(0, 50), longitude: lonEast(500) }],
      stretches: [],
    };
    const fix = { ...fixAt(0, { courseDeg: 90 }), longitude: lonEast(-400) };
    expect(match([east], fix).fresh).toBe(false);
    const moved = match([east], { ...fix, ageS: 10 });
    expect(moved.match.places[0]).toMatchObject({ reversed: false, along: 0 });
    expect(moved.match.places[0].distanceM).toBeCloseTo(200, 1);
  });
});

describe('what is matched', () => {
  it('prefers the nearer of two segments of one track', () => {
    const track = {
      points: [
        point(0, 0, 100),
        point(500, 50, 100),
        point(1000, 100, 100),
        point(1000, 150, 50),
        point(500, 200, 50),
        point(0, 250, 50),
      ],
      stretches: [],
    };
    // The way up is 100 m east of the fix, the way back down 50 m east; the fix is heading south.
    const hit = match([track], fixAt(500, { courseDeg: 180 }));
    expect(hit.match.places[0].distanceM).toBeCloseTo(50, 1);
    expect(hit.match.places[0].along).toBe(200);
  });

  it('keeps the nearer of two segments when the farther one comes later', () => {
    const track = {
      points: [
        point(0, 0, 50),
        point(500, 50, 50),
        point(1000, 100, 50),
        point(1000, 150, 150),
        point(500, 200, 150),
      ],
      stretches: [],
    };
    const hit = match([track], fixAt(500));
    expect(hit.match.places[0].distanceM).toBeCloseTo(50, 1);
    expect(hit.match.places[0].along).toBe(50);
  });

  it('skips a nearer segment that runs the wrong way for a farther one that runs the right way', () => {
    const track = {
      // 50 m east and running east (90 degrees off), then, after a gap too long to join, a northbound
      // segment 200 m east.
      points: [point(500, 0, 50), point(500, 10, 100), point(0, 100, 200), point(500, 150, 200)],
      stretches: [],
    };
    const hit = match([track], fixAt(500));
    expect(hit.match.places[0].distanceM).toBeCloseTo(200, 1);
    expect(hit.match.places[0].along).toBe(150);
  });

  it('skips a segment under a metre long, which has no direction', () => {
    const stuck = { points: [point(500, 0), point(500.5, 10)], stretches: [] };
    expect(match([stuck], fixAt(500)).fresh).toBe(false);
  });

  it('handles tracks with fewer than two points, and an empty map', () => {
    expect(match([{ points: [point(0, 0)], stretches: [] }], fixAt(0)).fresh).toBe(false);
    expect(match([{ points: [], stretches: [] }], fixAt(0)).fresh).toBe(false);
    expect(match([], fixAt(0))).toEqual({ match: null, fresh: false });
  });

  it('puts a place for every track the fix lies on, and none for a track it does not', () => {
    const elsewhere = {
      points: [point(0, 0, 5000), point(1000, 100, 5000)],
      stretches: [],
    };
    const hit = match([straight(), elsewhere, straight()], fixAt(500));
    expect(hit.match.places.map((p) => p.track)).toEqual([0, 2]);
  });

  it('changes nothing when a moving fix has no match, returning the last match as it was', () => {
    const last = match([straight()], fixAt(100)).match;
    const nowhere = match([straight()], fixAt(500, { courseDeg: 90 }), last);
    expect(nowhere).toEqual({ match: last, fresh: false });
    expect(nowhere.match).toBe(last);
  });

  it('treats a fix with no age or speed as refused', () => {
    expect(match([straight()], { ...fixAt(500), ageS: NaN }).fresh).toBe(false);
    expect(match([straight()], { ...fixAt(500), speedMps: NaN }).fresh).toBe(false);
  });
});

describe('stretches ahead', () => {
  const stretches = [
    { from: 10, to: 20, need: 100 },
    { from: 60, to: 80, need: 500 },
  ];

  it('gives the time from the place to each stretch not yet passed, and its end', () => {
    const track = straight(stretches);
    const m = match([track], fixAt(250)).match; // 25 s along
    expect(stretchesAhead([track], m)).toEqual([
      { track: 0, stretch: 1, need: 500, secondsTo: 35, secondsToEnd: 55, begun: false },
    ]);
  });

  it('says a stretch has begun, with no time to it, while the place is inside it', () => {
    const track = straight(stretches);
    const m = match([track], fixAt(150)).match; // 15 s along, inside the first
    const [first, second] = stretchesAhead([track], m).map(rounded);
    expect(first).toMatchObject({ stretch: 0, secondsTo: 0, secondsToEnd: 5, begun: true });
    expect(second).toMatchObject({ stretch: 1, secondsTo: 45, begun: false });
  });

  it('counts a stretch as begun at its start and as still ahead at its end, gone just past it', () => {
    const track = straight([{ from: 20, to: 40, need: 1 }]);
    const at = (northM) =>
      stretchesAhead([track], match([track], fixAt(northM)).match).map(rounded);
    expect(at(199)[0]).toMatchObject({ secondsTo: 0.1, begun: false });
    expect(at(201)[0]).toMatchObject({ secondsTo: 0, begun: true });
    expect(at(399)[0]).toMatchObject({ secondsToEnd: 0.1, begun: true });
    expect(at(401)).toEqual([]);
  });

  it('reads a reversed track downwards: stretches at lower times are ahead', () => {
    const track = straight(stretches);
    const m = match([track], fixAt(700, { courseDeg: 180 })).match; // 70 s along, going down
    const ahead = stretchesAhead([track], m).map(rounded);
    expect(ahead.map((a) => a.stretch)).toEqual([1, 0]);
    expect(ahead[0]).toMatchObject({ secondsTo: 0, secondsToEnd: 10, begun: true });
    expect(ahead[1]).toMatchObject({ secondsTo: 50, secondsToEnd: 60, begun: false });
  });

  it('drops a stretch a reversed train has passed', () => {
    const track = straight(stretches);
    const m = match([track], fixAt(50, { courseDeg: 180 })).match; // 5 s along
    expect(stretchesAhead([track], m)).toEqual([]);
  });

  it('is empty with no match', () => {
    expect(stretchesAhead([straight(stretches)], null)).toEqual([]);
    expect(nextStretch([straight(stretches)], null)).toBeNull();
  });

  it('takes the shortest time any track took to a stretch', () => {
    const slow = {
      points: [point(0, 0), point(250, 50), point(500, 100), point(750, 150), point(1000, 200)],
      stretches: [{ from: 160, to: 180, need: 300 }],
    };
    const quick = straight([{ from: 60, to: 80, need: 400 }]);
    const tracks = [slow, quick];
    const m = match(tracks, fixAt(500)).match;
    // The slow track is 100 s along and took 60 s to its stretch; the quick one 50 s along, 10 s to its.
    expect(stretchesAhead(tracks, m).map((a) => a.secondsTo)).toEqual([10, 60]);
    expect(nextStretch(tracks, m)).toMatchObject({ track: 1, need: 400, secondsTo: 10 });
  });

  it('takes the shortest time when several tracks reach the same stretch at different speeds', () => {
    const mk = (end) => ({
      points: [point(0, 0), point(500, end / 2), point(1000, end)],
      stretches: [{ from: end * 0.8, to: end, need: 200 }],
    });
    const tracks = [mk(100), mk(50), mk(75)];
    const m = match(tracks, fixAt(500)).match;
    // To the stretch's start from the middle: 30, 15 and 22.5 s.
    expect(nextStretch(tracks, m).secondsTo).toBeCloseTo(15, 6);
    expect(nextStretch(tracks, m).track).toBe(1);
  });

  it('breaks a tie in time by the larger need', () => {
    const a = straight([{ from: 60, to: 70, need: 100 }]);
    const b = straight([{ from: 60, to: 70, need: 300 }]);
    const m = match([a, b], fixAt(500)).match;
    expect(nextStretch([a, b], m)).toMatchObject({ track: 1, need: 300 });
  });
});
