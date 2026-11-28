import { describe, expect, it } from 'vitest';
import { matchFix, stretchesAhead } from './trackMatch.js';

// Exact limits, in made-up geometry at 0 N 0 E (cos 0 = 1) and, for the longitude correction, at 60 N 20 W.
// The metres a coordinate stands for are worked out here the way the matcher works them out, so a coordinate
// can be found that comes to exactly a limit, and the next one up to just over it.
const R = 6_371_000;
const RAD = Math.PI / 180;
const M = (deg) => deg * RAD * R;
const degFor = (metres) => {
  let deg = metres / RAD / R;
  for (let k = 0; k < 64 && M(deg) < metres; k += 1) deg *= 1 + Number.EPSILON;
  for (let k = 0; k < 64 && M(deg * (1 - Number.EPSILON)) >= metres; k += 1) {
    deg *= 1 - Number.EPSILON;
  }
  return deg;
};
const up = (deg) => deg * (1 + Number.EPSILON);
const origin = (over = {}) => ({
  latitude: 0,
  longitude: 0,
  courseDeg: 0,
  speedMps: 20,
  ageS: 0,
  ...over,
});
const pt = (latitude, longitude, t) => ({ latitude, longitude, courseDeg: 0, t });
const match = (tracks, fix) => matchFix(tracks, fix, null);

// A due-north segment through longitude `east`, 1,000 m either side of the fix's latitude.
const meridian = (east) => ({
  points: [pt(-degFor(1000), east, 0), pt(degFor(1000), east, 40)],
  stretches: [],
});

describe('exact limits', () => {
  it('finds coordinates for exact metres, so the tests below test what they say', () => {
    expect(M(degFor(300))).toBe(300);
    expect(M(degFor(1))).toBe(1);
    expect(M(up(degFor(300)))).toBeGreaterThan(300);
  });

  it('matches a segment at exactly 300 m and refuses 300.01 m and the next coordinate up', () => {
    const exact = match([meridian(degFor(300))], origin());
    expect(exact.fresh).toBe(true);
    expect(exact.match.places[0].distanceM).toBe(300);
    expect(match([meridian(up(degFor(300)))], origin()).fresh).toBe(false);
    expect(match([meridian(degFor(300.01))], origin()).fresh).toBe(false);
    expect(match([meridian(degFor(299.99))], origin()).fresh).toBe(true);
  });

  it('accepts a course exactly 45 degrees off the segment, forward and reversed, and not 45.01', () => {
    const track = meridian(0);
    expect(match([track], origin({ courseDeg: 45 })).match.places[0].reversed).toBe(false);
    expect(match([track], origin({ courseDeg: 315 })).match.places[0].reversed).toBe(false);
    expect(match([track], origin({ courseDeg: 135 })).match.places[0].reversed).toBe(true);
    expect(match([track], origin({ courseDeg: 225 })).match.places[0].reversed).toBe(true);
    for (const course of [45.01, 134.99, 225.01, 314.99]) {
      expect(match([track], origin({ courseDeg: course })).fresh).toBe(false);
    }
  });

  it('takes a segment of exactly 1.0 m and one of 1.5 m, and skips one of 0.5 m', () => {
    const along = (metres) => ({
      points: [pt(0, 0, 0), pt(degFor(metres), 0, 10)],
      stretches: [],
    });
    expect(match([along(1)], origin()).fresh).toBe(true);
    expect(match([along(1.5)], origin()).fresh).toBe(true);
    expect(match([along(0.5)], origin()).fresh).toBe(false);
  });

  it('gives a stretch beginning exactly at the place as begun, and one ending exactly there as still ahead', () => {
    // The fix is on the vertex at 50 s of a track north from 0 N, one point every 50 s.
    const track = {
      points: [pt(0, 0, 0), pt(degFor(500), 0, 50), pt(degFor(1000), 0, 100)],
      stretches: [
        { from: 50, to: 80, need: 1 },
        { from: 20, to: 50, need: 2 },
        { from: 20, to: 49.5, need: 3 },
        { from: 50.5, to: 80, need: 4 },
      ],
    };
    const at = (courseDeg) => match([track], origin({ latitude: degFor(500), courseDeg })).match;
    const byNeed = (list) => Object.fromEntries(list.map((a) => [a.need, a]));
    const forward = at(0);
    expect(forward.places[0].along).toBe(50);
    const f = byNeed(stretchesAhead([track], forward));
    expect(f[1]).toMatchObject({ secondsTo: 0, begun: true });
    expect(f[2]).toMatchObject({ secondsToEnd: 0, begun: true });
    expect(f[3]).toBeUndefined();
    expect(f[4]).toMatchObject({ secondsTo: 0.5, begun: false });
    const b = byNeed(stretchesAhead([track], at(180)));
    expect(b[2]).toMatchObject({ secondsTo: 0, begun: true });
    expect(b[1]).toMatchObject({ secondsToEnd: 0, begun: true });
    expect(b[4]).toBeUndefined();
    expect(b[3]).toMatchObject({ secondsTo: 0.5, begun: false });
  });
});

describe('moving a fix on at a high latitude', () => {
  it('uses the shorter degree of longitude there when it moves a fix east', () => {
    const lat = 60;
    const lonAt = (m) => -20 + m / (R * Math.cos(lat * RAD)) / RAD;
    // A track running due east from its start, 500 m long.
    const east = {
      points: [
        { latitude: lat, longitude: lonAt(0), courseDeg: 90, t: 0 },
        { latitude: lat, longitude: lonAt(500), courseDeg: 90, t: 50 },
      ],
      stretches: [],
    };
    // 500 m west of the start, going east at 30 m/s: 10 s on it is 300 m nearer, 200 m short of the start.
    const fix = { latitude: lat, longitude: lonAt(-500), courseDeg: 90, speedMps: 30, ageS: 10 };
    expect(match([east], { ...fix, ageS: 0 }).fresh).toBe(false);
    const moved = match([east], fix);
    expect(moved.fresh).toBe(true);
    expect(moved.match.places[0].distanceM).toBeCloseTo(200, 1);
  });
});

describe('values that are not numbers', () => {
  it('matches nothing on a track point with no latitude or longitude', () => {
    const noLatitude = {
      points: [{ longitude: 0, courseDeg: 0, t: 0 }, pt(degFor(500), 0, 50)],
      stretches: [],
    };
    const noLongitude = {
      points: [pt(0, 0, 0), { latitude: degFor(500), courseDeg: 0, t: 50 }],
      stretches: [],
    };
    expect(match([noLatitude], origin()).fresh).toBe(false);
    expect(match([noLongitude], origin()).fresh).toBe(false);
  });

  it('matches nothing for a fix with a NaN position or course', () => {
    expect(match([meridian(0)], origin({ latitude: NaN })).fresh).toBe(false);
    expect(match([meridian(0)], origin({ courseDeg: NaN })).fresh).toBe(false);
  });
});
