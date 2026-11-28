// Made-up geometry for the look-ahead's tests, in mid-ocean around 0.5 N 20.0 W, far from any real line: one track
// runs due north along a meridian at 10 m/s with a bad stretch from 600 s to 700 s needing 300 s of film ahead.
const R = 6_371_000;
const RAD = Math.PI / 180;
export const LAT0 = 0.5;
export const LON0 = -20;
export const metresNorth = (m) => LAT0 + m / R / RAD;

/** A track of 101 points, one every 10 s and 100 m, with one stretch (600 s to 700 s, need 300 s). */
export const northTrack = () => ({
  points: Array.from({ length: 101 }, (_, i) => ({
    latitude: metresNorth(i * 100),
    longitude: LON0,
    courseDeg: 0,
    t: i * 10,
  })),
  stretches: [{ from: 600, to: 700, need: 300 }],
});

/** The feed's answer text for a train `northM` metres along the track, heading north. */
export const feedText = (northM = 0, { age = 0, speed = 20 } = {}) =>
  `cb({"latitude":"${metresNorth(northM).toFixed(7)}","longitude":"${LON0}","cmg":"0","speed":"${speed}",` +
  `"age":"${age}","time":"1800000000","mode":"1",});`;

/** What the engine is handed with the train at the start of the track, `elapsedS` seconds after the answer. */
export const inputAtStart = (elapsedS = 0) => ({
  need: 300,
  timeToStretch: 600 - elapsedS,
  stretchBegun: false,
});
