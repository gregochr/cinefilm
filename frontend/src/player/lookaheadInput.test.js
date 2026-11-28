import { describe, expect, it } from 'vitest';
import { BANK_ARM_WITHIN_S } from './hlsjsEngine.js';
import { matchFix } from './trackMatch.js';
import { ARM_WITHIN_S, LAPSE_MS, createLookaheadInput } from './lookaheadInput.js';

// Made-up track due east along 0.5 N, a point every 10 s and 0.001 degrees (about 111 m) apart.
const points = Array.from({ length: 400 }, (_, k) => ({
  latitude: 0.5,
  longitude: -20 + k * 0.001,
  courseDeg: 90,
  t: k * 10,
}));
const track = (stretches) => ({ points, stretches });
const fixAt = (t, courseDeg = 90) => ({
  latitude: 0.5,
  longitude: -20 + (t / 10) * 0.001,
  courseDeg,
  speedMps: 11,
  ageS: 0,
});
const T0 = 1_000_000;

/** An input object that matched a fix at `along` seconds on the track at time T0. */
function matched(tracks, along, courseDeg = 90) {
  const li = createLookaheadInput();
  const result = matchFix(tracks, fixAt(along, courseDeg));
  expect(result.fresh).toBe(true);
  li.noteMatch(result, T0);
  return li;
}

describe('constants', () => {
  it('pin the plan and the engine', () => {
    expect(LAPSE_MS).toBe(300_000);
    expect(ARM_WITHIN_S).toBe(BANK_ARM_WITHIN_S);
  });
});

describe('lapse', () => {
  const tracks = [track([{ from: 1000, to: 1500, need: 600 }])];

  it('is null with no fresh match ever', () => {
    expect(createLookaheadInput().input(tracks, T0)).toBeNull();
  });

  it('is null when only a stale result was noted', () => {
    const li = createLookaheadInput();
    li.noteMatch({ match: null, fresh: false }, T0);
    expect(li.input(tracks, T0)).toBeNull();
  });

  it('holds at exactly 300 s and lapses just over', () => {
    const li = matched(tracks, 500);
    expect(li.input(tracks, T0 + LAPSE_MS)).toEqual({
      need: 600,
      timeToStretch: 200,
      stretchBegun: false,
    });
    expect(li.input(tracks, T0 + LAPSE_MS + 1)).toBeNull();
  });

  it('is not refreshed by a stale result', () => {
    const li = matched(tracks, 500);
    const stale = { match: matchFix(tracks, fixAt(500)).match, fresh: false };
    li.noteMatch(stale, T0 + 200_000);
    expect(li.input(tracks, T0 + LAPSE_MS + 1)).toBeNull();
  });

  it('is refreshed by a later fresh match', () => {
    const li = matched(tracks, 500);
    li.noteMatch(matchFix(tracks, fixAt(600)), T0 + 200_000);
    expect(li.input(tracks, T0 + 400_000)).toEqual({
      need: 600,
      timeToStretch: 200,
      stretchBegun: false,
    });
  });
});

describe('advancing between fixes', () => {
  const tracks = [track([{ from: 1000, to: 1500, need: 600 }])];

  it('reduces the time to the stretch by the seconds since the match', () => {
    const li = matched(tracks, 500);
    expect(li.input(tracks, T0)).toEqual({ need: 600, timeToStretch: 500, stretchBegun: false });
    expect(li.input(tracks, T0 + 30_500)).toEqual({
      need: 600,
      timeToStretch: 469.5,
      stretchBegun: false,
    });
  });

  it('sets stretchBegun the moment the time reaches 0', () => {
    const li = matched(tracks, 980);
    expect(li.input(tracks, T0 + 19_999)).toEqual({
      need: 600,
      timeToStretch: expect.closeTo(0.001, 6),
      stretchBegun: false,
    });
    expect(li.input(tracks, T0 + 20_000)).toEqual({
      need: 600,
      timeToStretch: 0,
      stretchBegun: true,
    });
    expect(li.input(tracks, T0 + 30_000)).toEqual({
      need: 600,
      timeToStretch: -10,
      stretchBegun: true,
    });
  });

  it('keeps a stretch already begun at the match at 0 or below', () => {
    const li = matched(tracks, 1200);
    expect(li.input(tracks, T0)).toEqual({ need: 600, timeToStretch: 0, stretchBegun: true });
    expect(li.input(tracks, T0 + 5000)).toEqual({
      need: 600,
      timeToStretch: -5,
      stretchBegun: true,
    });
  });
});

describe('the stretch passing', () => {
  const tracks = [
    track([
      { from: 1000, to: 1100, need: 600 },
      { from: 1500, to: 1700, need: 900 },
    ]),
  ];

  it('still holds when its end is reached, and is passed just below', () => {
    const li = matched(tracks, 1000);
    expect(li.input(tracks, T0 + 100_000)).toEqual({
      need: 600,
      timeToStretch: -100,
      stretchBegun: true,
    });
    expect(li.input(tracks, T0 + 100_001)).toEqual({
      need: 900,
      timeToStretch: expect.closeTo(399.999, 6),
      stretchBegun: false,
    });
  });

  it('is null when no later stretch exists', () => {
    const only = [track([{ from: 1000, to: 1100, need: 600 }])];
    const li = matched(only, 1000);
    expect(li.input(only, T0 + 100_000)).not.toBeNull();
    expect(li.input(only, T0 + 100_001)).toBeNull();
  });

  it('is null when there is no stretch ahead at all', () => {
    const none = [track([])];
    expect(matched(none, 500).input(none, T0)).toBeNull();
  });
});

describe('the 30 minute window', () => {
  it('gives input at 1800 s away', () => {
    const tracks = [track([{ from: 2300, to: 2500, need: 600 }])];
    expect(matched(tracks, 500).input(tracks, T0)).toEqual({
      need: 600,
      timeToStretch: 1800,
      stretchBegun: false,
    });
  });

  it('gives none at 1801 s away', () => {
    const tracks = [track([{ from: 2301, to: 2500, need: 600 }])];
    expect(matched(tracks, 500).input(tracks, T0)).toBeNull();
  });
});

describe('several tracks and a reversed track', () => {
  it('takes the soonest of two tracks giving the same stretch at different times', () => {
    const tracks = [
      track([{ from: 1200, to: 1700, need: 700 }]),
      track([{ from: 1000, to: 1500, need: 600 }]),
    ];
    expect(matched(tracks, 500).input(tracks, T0)).toEqual({
      need: 600,
      timeToStretch: 500,
      stretchBegun: false,
    });
  });

  it('counts down the track for a train going against it', () => {
    const tracks = [track([{ from: 300, to: 400, need: 450 }])];
    const li = matched(tracks, 1000, 270);
    expect(li.input(tracks, T0)).toEqual({ need: 450, timeToStretch: 600, stretchBegun: false });
    expect(li.input(tracks, T0 + 100_000)).toEqual({
      need: 450,
      timeToStretch: 500,
      stretchBegun: false,
    });
  });
});
