import { describe, expect, it } from 'vitest';
import { MIN_MEASURED_S, chooseBankRung } from './bankRung.js';

// Rates come from the caller; these are round numbers for hand arithmetic (kbit/s).
const C240 = { id: '240p', rate: 250 };
const C360 = { id: '360p', rate: 500 };
const C480 = { id: '480p', rate: 1000 };

// Fetching at 360p (R = 500), 100 s of film ahead, 200 s to the stretch, F = 300 s, 60 s measured.
// projection(c) = 100 + 200 x (1 / (w + d x rate(c) / 500) - 1).
function input(over = {}) {
  return {
    filmAhead: 100,
    timeToStretch: 200,
    need: 300,
    stretchBegun: false,
    atLimit: false,
    w: 0.25,
    d: 0.125,
    measuredS: 60,
    rate: 500,
    candidates: [C240, C360, C480],
    ...over,
  };
}

const projectionOf = (result, id) => result.projections.find((p) => p.id === id).projection;

describe('the projection against F (decision 7)', () => {
  it('chooses 480p when its projection is exactly F', () => {
    // 480p: 0.25 + 0.125 x 2 = 0.5; 1/0.5 - 1 = 1; 100 + 200 x 1 = 300 = F.
    const r = chooseBankRung(input());
    expect(r.id).toBe('480p');
    expect(projectionOf(r, '480p')).toBe(300);
    // 360p: 0.25 + 0.125 = 0.375; 1/0.375 - 1 = 1.6667; 100 + 333.3 = 433.3.
    expect(projectionOf(r, '360p')).toBeCloseTo(433.33, 1);
    // 240p: 0.25 + 0.0625 = 0.3125; 1/0.3125 - 1 = 2.2; 100 + 440 = 540.
    expect(projectionOf(r, '240p')).toBeCloseTo(540, 6);
  });

  it('chooses 360p when 480p falls just under F', () => {
    // d = 0.14: den 0.25 + 0.28 = 0.53; 1/0.53 - 1 = 0.8868; 100 + 177.36 = 277.36 < 300.
    const r = chooseBankRung(input({ d: 0.14 }));
    expect(projectionOf(r, '480p')).toBeCloseTo(277.36, 1);
    expect(r.id).toBe('360p');
  });

  it('chooses 480p when it is just over F', () => {
    // d = 0.11: den 0.25 + 0.22 = 0.47; 1/0.47 - 1 = 1.1277; 100 + 225.53 = 325.53 >= 300.
    const r = chooseBankRung(input({ d: 0.11 }));
    expect(projectionOf(r, '480p')).toBeCloseTo(325.53, 1);
    expect(r.id).toBe('480p');
  });
});

describe('what the window is made of', () => {
  it('wait-dominated: the rung hardly matters, none reaches F, the highest projection (240p) is kept', () => {
    // w = 0.8, d = 0.01. 240p den 0.805 -> 100 + 200 x 0.2422 = 148.4; 360p den 0.81 -> 146.9;
    // 480p den 0.82 -> 143.9. None reaches 300.
    const r = chooseBankRung(input({ w: 0.8, d: 0.01 }));
    expect(projectionOf(r, '240p')).toBeCloseTo(148.45, 1);
    expect(projectionOf(r, '360p')).toBeCloseTo(146.9, 1);
    expect(projectionOf(r, '480p')).toBeCloseTo(143.9, 1);
    expect(r.id).toBe('240p');
  });

  it('transfer-dominated: only the lowest rung reaches F, and it is chosen', () => {
    // w = 0, d = 0.9. 240p den 0.45 -> 100 + 200 x 1.2222 = 344.4; 360p den 0.9 -> 122.2; 480p den 1.8 -> 11.1.
    const r = chooseBankRung(input({ w: 0, d: 0.9 }));
    expect(projectionOf(r, '240p')).toBeCloseTo(344.44, 1);
    expect(projectionOf(r, '360p')).toBeCloseTo(122.22, 1);
    expect(projectionOf(r, '480p')).toBeCloseTo(11.11, 1);
    expect(r.id).toBe('240p');
  });

  it('a window containing one 503 wait costs the highest rung its place', () => {
    // Without the wait (w = 0, d = 0.25): 480p den 0.5 -> 300 = F, chosen.
    expect(chooseBankRung(input({ w: 0, d: 0.25 })).id).toBe('480p');
    // With 0.2 s of waiting per film second: 480p den 0.2 + 0.5 = 0.7 -> 100 + 85.7 = 185.7 < 300;
    // 360p den 0.2 + 0.25 = 0.45 -> 100 + 244.4 = 344.4 reaches F.
    const r = chooseBankRung(input({ w: 0.2, d: 0.25 }));
    expect(projectionOf(r, '480p')).toBeCloseTo(185.71, 1);
    expect(projectionOf(r, '360p')).toBeCloseTo(344.44, 1);
    expect(r.id).toBe('360p');
  });

  it('a 240p start on a fast link chooses 480p', () => {
    // R = 250, w = d = 0.05, film ahead 50, 600 s to go. 480p ratio 4: den 0.05 + 0.2 = 0.25;
    // 1/0.25 - 1 = 3; 50 + 1800 = 1850 >= 300.
    const r = chooseBankRung(
      input({ filmAhead: 50, timeToStretch: 600, w: 0.05, d: 0.05, rate: 250 }),
    );
    expect(r.id).toBe('480p');
    expect(projectionOf(r, '480p')).toBeCloseTo(1850, 6);
  });
});

describe('no choice', () => {
  it('inside a stretch', () => {
    expect(chooseBankRung(input({ stretchBegun: true }))).toBeNull();
  });

  it('at F and above it, but one second below F still chooses', () => {
    expect(chooseBankRung(input({ filmAhead: 300 }))).toBeNull();
    expect(chooseBankRung(input({ filmAhead: 450 }))).toBeNull();
    expect(chooseBankRung(input({ filmAhead: 299 }))).not.toBeNull();
  });

  it('at the limit', () => {
    expect(chooseBankRung(input({ atLimit: true }))).toBeNull();
  });

  it('before 20 s of fetching is measured: 19.9 s no, 20 s yes', () => {
    expect(MIN_MEASURED_S).toBe(20);
    expect(chooseBankRung(input({ measuredS: 19.9 }))).toBeNull();
    expect(chooseBankRung(input({ measuredS: 20 }))).not.toBeNull();
    expect(chooseBankRung(input({ measuredS: undefined }))).toBeNull();
  });
});

describe('guarding the arithmetic', () => {
  it('no measurements (w and d both 0) keep the rung rather than divide by zero', () => {
    expect(chooseBankRung(input({ w: 0, d: 0 }))).toBeNull();
  });

  it('negative, missing or non-finite measurements keep the rung', () => {
    expect(chooseBankRung(input({ w: -0.1, d: 0.3 }))).toBeNull();
    expect(chooseBankRung(input({ w: 0.1, d: -0.3 }))).toBeNull();
    expect(chooseBankRung(input({ w: NaN, d: 0.3 }))).toBeNull();
    expect(chooseBankRung(input({ w: undefined, d: undefined }))).toBeNull();
  });

  it('a fetched rung with no usable rate keeps the rung', () => {
    expect(chooseBankRung(input({ rate: 0 }))).toBeNull();
    expect(chooseBankRung(input({ rate: NaN }))).toBeNull();
  });

  it('skips candidates without a usable rate and returns null when none is left', () => {
    const r = chooseBankRung(input({ candidates: [{ id: 'x', rate: 0 }, C360, C480] }));
    expect(r.projections.map((p) => p.id)).toEqual(['360p', '480p']);
    expect(chooseBankRung(input({ candidates: [{ id: 'x', rate: 0 }] }))).toBeNull();
    expect(chooseBankRung(input({ candidates: [] }))).toBeNull();
  });

  it("sorts candidates by rate without touching the caller's list, and every projection is finite", () => {
    const candidates = [C480, C240, C360];
    const r = chooseBankRung(input({ candidates }));
    expect(r.projections.map((p) => p.id)).toEqual(['240p', '360p', '480p']);
    expect(candidates).toEqual([C480, C240, C360]);
    expect(r.projections.every((p) => Number.isFinite(p.projection))).toBe(true);
  });

  it('with no time to the stretch every projection is film ahead and the highest rung wins the tie', () => {
    // 100 + 0 x (...) = 100 < 300 for each; equal projections take the higher rate.
    const r = chooseBankRung(input({ timeToStretch: 0 }));
    expect(r.projections.map((p) => p.projection)).toEqual([100, 100, 100]);
    expect(r.id).toBe('480p');
  });
});

describe('the next stretch is chosen afresh', () => {
  it('keeps nothing between calls: the same input answers the same after a different stretch', () => {
    const first = input();
    const slowNext = input({ filmAhead: 20, timeToStretch: 400, w: 0.6, d: 0.9 });
    const before = chooseBankRung(first);
    // Stretch 2: 240p den 0.6 + 0.9 x 0.5 = 1.05; 1/1.05 - 1 = -0.0476; 20 - 19.05 = 0.95; none reaches 300,
    // so the highest projection (240p) is chosen, whatever stretch 1 chose.
    const next = chooseBankRung(slowNext);
    expect(before.id).toBe('480p');
    expect(next.id).toBe('240p');
    expect(projectionOf(next, '240p')).toBeCloseTo(0.95, 2);
    expect(chooseBankRung(first)).toEqual(before);
    expect(chooseBankRung(slowNext)).toEqual(next);
  });
});
