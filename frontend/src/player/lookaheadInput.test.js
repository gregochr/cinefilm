import { describe, expect, it } from 'vitest';
import { BANK_ARM_WITHIN_S } from './hlsjsEngine.js';
import { matchFix } from './trackMatch.js';
import { ARM_WITHIN_S, LAPSE_MS, NEXT_WITHIN_S, createLookaheadInput } from './lookaheadInput.js';

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
const slowFix = (t, ageS = 0) => ({ ...fixAt(t), speedMps: 2, ageS });
const T0 = 1_000_000;
const sec = (s) => T0 + s * 1000;

/** Feeds one fix through the real matcher, as the player will, and notes the result. */
function feed(li, tracks, fix, nowMs, last = null) {
  const result = matchFix(tracks, fix, last);
  li.noteMatch(result, fix, tracks, nowMs);
  return result;
}

/** An input object that matched a fix at `along` seconds on the track at time T0. */
function matched(tracks, along, courseDeg = 90) {
  const li = createLookaheadInput();
  const result = feed(li, tracks, fixAt(along, courseDeg), T0);
  expect(result.fresh).toBe(true);
  return li;
}

const begun = (need, timeToStretch) => ({ need, timeToStretch, stretchBegun: true });
const ahead = (need, timeToStretch) => ({ need, timeToStretch, stretchBegun: false });

describe('constants', () => {
  it('pin the plan and the engine', () => {
    expect(LAPSE_MS).toBe(300_000);
    expect(ARM_WITHIN_S).toBe(BANK_ARM_WITHIN_S);
    expect(NEXT_WITHIN_S).toBe(1200);
  });
});

describe('lapse', () => {
  const tracks = [track([{ from: 1000, to: 1500, need: 600 }])];

  it('is null with no fresh match ever', () => {
    expect(createLookaheadInput().input(T0)).toBeNull();
  });

  it('is null when only a result with no match was noted', () => {
    const li = createLookaheadInput();
    li.noteMatch({ match: null, fresh: false }, fixAt(500), tracks, T0);
    li.noteMatch({ match: null, fresh: false }, slowFix(500), tracks, T0);
    expect(li.input(T0)).toBeNull();
  });

  it('holds at exactly 300 s and lapses just over', () => {
    const li = matched(tracks, 500);
    expect(li.input(sec(300))).toEqual(ahead(600, 200));
    expect(li.input(sec(300) + 1)).toBeNull();
  });

  it('is not refreshed by a result that is not fresh', () => {
    const li = matched(tracks, 500);
    feed(li, tracks, fixAt(500, 0), sec(200)); // off the line's heading: no match
    expect(li.input(sec(300) + 1)).toBeNull();
  });

  it('is refreshed by a later fresh match', () => {
    const li = matched(tracks, 500);
    feed(li, tracks, fixAt(600), sec(200));
    expect(li.input(sec(400))).toEqual(ahead(600, 200));
  });
});

describe('the held match keeps its own tracks', () => {
  it('ignores a swapped map on a result that is not fresh', () => {
    const tracks = [track([{ from: 1000, to: 1500, need: 600 }])];
    const li = matched(tracks, 500);
    const swapped = [];
    expect(() => feed(li, swapped, fixAt(500, 0), sec(10))).not.toThrow();
    expect(() =>
      li.noteMatch({ match: null, fresh: false }, fixAt(500), swapped, sec(10)),
    ).not.toThrow();
    expect(li.input(sec(10))).toEqual(ahead(600, 490));
  });

  it('takes the new map with a fresh match', () => {
    const li = matched([track([{ from: 1000, to: 1500, need: 600 }])], 500);
    const next = [track([{ from: 900, to: 950, need: 300 }])];
    feed(li, next, fixAt(500), sec(10));
    expect(li.input(sec(10))).toEqual(ahead(300, 400));
  });
});

describe('advancing between fixes', () => {
  const tracks = [track([{ from: 1000, to: 1500, need: 600 }])];

  it('reduces the time to the stretch by the seconds since the match', () => {
    const li = matched(tracks, 500);
    expect(li.input(T0)).toEqual(ahead(600, 500));
    expect(li.input(sec(30.5))).toEqual(ahead(600, 469.5));
  });

  it('sets stretchBegun the moment the time reaches 0', () => {
    const li = matched(tracks, 980);
    expect(li.input(sec(20) - 1)).toEqual(ahead(600, expect.closeTo(0.001, 6)));
    expect(li.input(sec(20))).toEqual(begun(600, 0));
    expect(li.input(sec(30))).toEqual(begun(600, -10));
  });

  it('keeps a stretch already begun at the match at 0 or below', () => {
    const li = matched(tracks, 1200);
    expect(li.input(T0)).toEqual(begun(600, 0));
    expect(li.input(sec(5))).toEqual(begun(600, -5));
  });
});

describe('a train stopped on the line', () => {
  const tracks = [track([{ from: 1000, to: 1500, need: 600 }])];

  it('stays inside a stretch for ten minutes, with no lapse', () => {
    const li = matched(tracks, 1100);
    for (let s = 30; s <= 600; s += 30) feed(li, tracks, slowFix(1100), sec(s));
    expect(li.input(sec(600))).toEqual(begun(600, -30));
  });

  it('is not passed when stopped just before the stretch end', () => {
    const li = matched(tracks, 1480);
    feed(li, tracks, slowFix(1480), sec(10));
    feed(li, tracks, slowFix(1480), sec(40));
    expect(li.input(sec(500))).toBeNull(); // 300 s since the last answer: lapsed
    expect(li.input(sec(340))).toEqual(begun(600, -10)); // still there, end 10 s ahead
  });

  it('freezes the time to a stretch still ahead', () => {
    const li = matched(tracks, 500);
    feed(li, tracks, slowFix(500), sec(20));
    expect(li.input(sec(20))).toEqual(ahead(600, 480));
    expect(li.input(sec(200))).toEqual(ahead(600, 480));
  });

  it('advances again from the frozen value when moving again', () => {
    const li = matched(tracks, 500);
    feed(li, tracks, slowFix(500), sec(20));
    feed(li, tracks, fixAt(500, 0), sec(200)); // moving, but off the line's heading
    expect(li.input(sec(200))).toEqual(ahead(600, 480));
    expect(li.input(sec(210))).toEqual(ahead(600, 470));
    expect(li.input(sec(320) + 1)).toBeNull(); // the move did not refresh the lapse (last answer at 20 s)
  });

  it('resets on the next fresh match', () => {
    const li = matched(tracks, 500);
    feed(li, tracks, slowFix(500), sec(20));
    feed(li, tracks, fixAt(520), sec(200));
    expect(li.input(sec(210))).toEqual(ahead(600, 470));
  });

  it('does not freeze, or refresh, on a fix too old', () => {
    const li = matched(tracks, 500);
    feed(li, tracks, slowFix(500, 61), sec(20));
    expect(li.input(sec(100))).toEqual(ahead(600, 400));
    expect(li.input(sec(300) + 1)).toBeNull();
  });

  it('freezes only until its own last answer: a stale fix in the stop extends nothing', () => {
    const li = matched(tracks, 500);
    feed(li, tracks, slowFix(500), sec(20));
    feed(li, tracks, slowFix(500, 61), sec(200));
    expect(li.input(sec(320))).toEqual(ahead(600, 480));
    expect(li.input(sec(320) + 1)).toBeNull();
  });

  it('counts a fix of exactly 60 s old as in date', () => {
    const li = matched(tracks, 500);
    feed(li, tracks, slowFix(500, 60), sec(20));
    expect(li.input(sec(100))).toEqual(ahead(600, 480));
  });
});

describe('the stretch passing', () => {
  const tracks = [
    track([
      { from: 1000, to: 1100, need: 600 },
      { from: 2000, to: 2200, need: 900 },
    ]),
  ];

  it('still holds when its end is reached, and is passed just below', () => {
    const li = matched(tracks, 1000);
    expect(li.input(sec(100))).toEqual(begun(900, -100));
    expect(li.input(sec(100) + 1)).toEqual(ahead(900, expect.closeTo(899.999, 6)));
  });

  it('is null when no later stretch exists', () => {
    const only = [track([{ from: 1000, to: 1100, need: 600 }])];
    const li = matched(only, 1000);
    expect(li.input(sec(100))).not.toBeNull();
    expect(li.input(sec(100) + 1)).toBeNull();
  });

  it('is null when there is no stretch ahead at all', () => {
    const none = [track([])];
    expect(matched(none, 500).input(T0)).toBeNull();
  });
});

describe('the need inside a stretch', () => {
  const two = (fromB) => [
    track([
      { from: 1000, to: 1100, need: 600 },
      { from: fromB, to: fromB + 100, need: 900 },
    ]),
  ];

  it('is the larger of the stretch and one starting at exactly 1200 s', () => {
    const tracks = two(2250);
    expect(matched(tracks, 1050).input(T0)).toEqual(begun(900, 0));
  });

  it('leaves out one starting at 1201 s', () => {
    const tracks = two(2251);
    expect(matched(tracks, 1050).input(T0)).toEqual(begun(600, 0));
  });

  it('counts the next stretch from now, as time passes', () => {
    const tracks = two(2260);
    const li = matched(tracks, 1050);
    expect(li.input(sec(10) - 1)).toEqual(begun(600, expect.closeTo(-9.999, 6)));
    expect(li.input(sec(10))).toEqual(begun(900, -10));
  });

  it('keeps the smaller need when the next stretch asks for less', () => {
    const tracks = [
      track([
        { from: 1000, to: 1100, need: 600 },
        { from: 1500, to: 1600, need: 300 },
      ]),
    ];
    expect(matched(tracks, 1050).input(T0)).toEqual(begun(600, 0));
  });

  it('is the stretch own need before it begins, whatever follows', () => {
    const tracks = two(1200);
    expect(matched(tracks, 500).input(T0)).toEqual(ahead(600, 500));
  });
});

describe('the 30 minute window', () => {
  it('gives input at 1800 s away', () => {
    const tracks = [track([{ from: 2300, to: 2500, need: 600 }])];
    expect(matched(tracks, 500).input(T0)).toEqual(ahead(600, 1800));
  });

  it('gives none at 1801 s away', () => {
    const tracks = [track([{ from: 2301, to: 2500, need: 600 }])];
    expect(matched(tracks, 500).input(T0)).toBeNull();
  });
});

describe('several tracks and a reversed track', () => {
  it('takes the soonest of two tracks giving the same stretch at different times', () => {
    const tracks = [
      track([{ from: 1200, to: 1700, need: 700 }]),
      track([{ from: 1000, to: 1500, need: 600 }]),
    ];
    expect(matched(tracks, 500).input(T0)).toEqual(ahead(600, 500));
  });

  it('counts down the track for a train going against it', () => {
    const tracks = [track([{ from: 300, to: 400, need: 450 }])];
    const li = matched(tracks, 1000, 270);
    expect(li.input(T0)).toEqual(ahead(450, 600));
    expect(li.input(sec(100))).toEqual(ahead(450, 500));
  });
});
