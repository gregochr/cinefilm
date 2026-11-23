import { describe, expect, it } from 'vitest';
import {
  BANK_MARGIN_S,
  MIN_SAMPLES,
  STAY_FACTOR,
  STEP_UP_FACTOR,
  bankTargetS,
  champagneBase,
  champagneCeiling,
} from './champagne.js';

// The real rates, from Rung.java: a transcoded rung's peak is its target x 1.2 + audio, a copy rung's its
// average x 1.5 + audio; the average is the target + audio (AVERAGE-BANDWIDTH).
const level = (height, video, audio, copy = false) => ({
  height,
  averageBitrate: video + audio,
  bitrate: Math.round(video * (copy ? 1.5 : 1.2)) + audio,
  maxBitrate: Math.round(video * (copy ? 1.5 : 1.2)) + audio,
});
const l240 = level(240, 250_000, 64_000); // average 314k, peak 364k
const l360 = level(360, 600_000, 96_000); // 696k, 816k
const l480 = level(480, 1_000_000, 128_000); // 1.128M, 1.328M
const l720 = level(720, 2_500_000, 128_000); // 2.628M, 3.128M
const l1080 = level(1080, 5_000_000, 128_000); // 5.128M, 6.128M
const copy15 = level(1080, 15_000_000, 128_000, true); // 15.128M, 22.628M

const bank = { budgetBytes: 100_000_000, minS: 120, maxS: 480 };
const ladder = [l240, l360, l480, l720, l1080];
const ceiling = (over) =>
  champagneCeiling({
    levels: ladder,
    applied: 2,
    count: 9,
    aheadS: 480,
    kbps: 100_000,
    troubled: false,
    bank,
    ...over,
  });

describe('bankTargetS: hls.js’s own forward target', () => {
  it('is 480 s on the low rungs, about 256 s at 720p, about 130 s at 1080p and 120 s on a heavy copy', () => {
    expect(bankTargetS(l480.maxBitrate, bank)).toBe(480);
    expect(Math.round(bankTargetS(l720.maxBitrate, bank))).toBe(256);
    expect(Math.round(bankTargetS(l1080.maxBitrate, bank))).toBe(131);
    expect(bankTargetS(copy15.maxBitrate, bank)).toBe(120);
  });

  it('is the most when a level has no bitrate', () => {
    expect(bankTargetS(0, bank)).toBe(480);
  });
});

describe('champagneBase: the level Auto always has', () => {
  it('is the 480p level of a ladder that has one', () => {
    expect(champagneBase(ladder)).toBe(2);
  });

  it('is the highest level at 480 lines or less when there is no 480p rung', () => {
    expect(champagneBase([l240, l360, l720, l1080])).toBe(1);
    expect(champagneBase([l360, l720, l1080])).toBe(0);
  });

  it('is the top of a ladder that never goes above 480p', () => {
    expect(champagneBase([l240, l360, l480])).toBe(2);
  });

  it('is the lowest level when every level is above 480 lines or the ladder is empty', () => {
    expect(champagneBase([l720, l1080])).toBe(0);
    expect(champagneBase([])).toBe(0);
  });

  it('counts a level with no height as one of the low ones', () => {
    expect(champagneBase([{ bitrate: 1 }, l720])).toBe(0);
  });
});

describe('champagneCeiling: headroom decides the rungs above 480p', () => {
  it('names its factors: up at 3 times the average, stay at 2 times, 30 s short of the target', () => {
    expect(STEP_UP_FACTOR).toBe(3);
    expect(STAY_FACTOR).toBe(2);
    expect(BANK_MARGIN_S).toBe(30);
  });

  it('steps up to 720p at 3 x its average (7,884 kbit/s) and not a kbit/s under', () => {
    expect(ceiling({ kbps: 7884 })).toBe(3);
    expect(ceiling({ kbps: 7883 })).toBe(2);
  });

  it('steps up to 1080p only at 3 x its average (15,384 kbit/s), from 720p', () => {
    expect(ceiling({ applied: 3, kbps: 15384 })).toBe(4);
    expect(ceiling({ applied: 3, kbps: 15383 })).toBe(3);
  });

  it('grants one new rung at a time: only the rung above the cap in force, however fast the link', () => {
    expect(ceiling({ applied: 2, kbps: 100_000 })).toBe(3);
    expect(ceiling({ applied: 3, kbps: 100_000 })).toBe(4);
  });

  it('does not grant 1080p past a bank that is not full for 720p, even when the ceiling had room', () => {
    expect(ceiling({ applied: 3, aheadS: 225, kbps: 100_000 })).toBe(3);
    expect(ceiling({ applied: 3, aheadS: 226, kbps: 100_000 })).toBe(4);
  });

  it('needs 3 rates before any step up above 480p, and none to stay', () => {
    expect(MIN_SAMPLES).toBe(3);
    expect(ceiling({ count: 2, kbps: 20_000 })).toBe(2);
    expect(ceiling({ count: 3, kbps: 20_000 })).toBe(3);
    expect(ceiling({ count: 1, applied: 3, kbps: 20_000 })).toBe(3);
  });

  it('steps up only with the bank full for the rung stepped from: 450 s at 480p, not 449', () => {
    expect(ceiling({ aheadS: 450, kbps: 8000 })).toBe(3);
    expect(ceiling({ aheadS: 449, kbps: 8000 })).toBe(2);
  });

  it('judges the bank by the rung below, not by the rung stepped to', () => {
    // 720p's own target is 256 s (226 s full): enough in hand for 1080p from 720p, not for 720p from 480p.
    expect(ceiling({ applied: 3, aheadS: 226, kbps: 20000 })).toBe(4);
    expect(ceiling({ applied: 3, aheadS: 225, kbps: 20000 })).toBe(3);
    expect(ceiling({ applied: 2, aheadS: 300, kbps: 20000 })).toBe(2);
  });

  it('keeps a rung it already has at 2 x its average (5,256 kbit/s for 720p) and no lower', () => {
    expect(ceiling({ applied: 3, aheadS: 0, kbps: 5256 })).toBe(3);
    expect(ceiling({ applied: 3, aheadS: 0, kbps: 5255 })).toBe(2);
  });

  it('falls to the highest rung that still passes, not straight to 480p', () => {
    expect(ceiling({ applied: 4, aheadS: 0, kbps: 10256 })).toBe(4); // 2 x 1080p's average
    expect(ceiling({ applied: 4, aheadS: 0, kbps: 10255 })).toBe(3);
    expect(ceiling({ applied: 4, aheadS: 0, kbps: 5256 })).toBe(3);
    expect(ceiling({ applied: 4, aheadS: 0, kbps: 5255 })).toBe(2);
  });

  it('climbs through every rung: 1080p is not reached past a 720p that fails', () => {
    expect(ceiling({ aheadS: 300, kbps: 100_000 })).toBe(2);
  });

  it('is the 480p level with no rate yet, or when the rate is no number or not positive', () => {
    expect(ceiling({ kbps: null })).toBe(2);
    expect(ceiling({ kbps: NaN })).toBe(2);
    expect(ceiling({ kbps: 0 })).toBe(2);
  });

  it('is the 480p level whatever the rate after fragment trouble, even for a rung held', () => {
    expect(ceiling({ applied: 4, troubled: true, kbps: 100_000 })).toBe(2);
  });

  it('on a ladder with no 240p bottom, still climbs from the 480p level', () => {
    const levels = [l360, l480, l720, l1080];
    expect(ceiling({ levels, applied: 1, kbps: 7884 })).toBe(2);
    expect(ceiling({ levels, applied: 1, kbps: 7883 })).toBe(1);
  });

  it('on a ladder with no 480p rung, climbs from the 360p level and judges its bank', () => {
    const levels = [l240, l360, l720, l1080];
    expect(ceiling({ levels, applied: 1, aheadS: 450, kbps: 7884 })).toBe(2);
    expect(ceiling({ levels, applied: 1, aheadS: 449, kbps: 7884 })).toBe(1);
  });

  it('on a ladder that stops at 480p, is the top', () => {
    expect(ceiling({ levels: [l240, l360, l480], kbps: 100 })).toBe(2);
    expect(ceiling({ levels: [l240, l360, l480], kbps: null })).toBe(2);
  });

  it('reaches a 15 Mbit/s copy rung only at 3 x its average (45,384 kbit/s), not a kbit/s under', () => {
    const levels = [l240, l360, l480, l720, copy15];
    expect(ceiling({ levels, applied: 3, aheadS: 480, kbps: 55_000 })).toBe(4);
    expect(ceiling({ levels, applied: 3, aheadS: 480, kbps: 45_384 })).toBe(4);
    expect(ceiling({ levels, applied: 3, aheadS: 480, kbps: 45_383 })).toBe(3);
  });

  it('holds a copy rung down to 2 x (30,256 kbit/s)', () => {
    const levels = [l240, l360, l480, l720, copy15];
    expect(ceiling({ levels, applied: 4, aheadS: 0, kbps: 30_256 })).toBe(4);
    expect(ceiling({ levels, applied: 4, aheadS: 0, kbps: 30_255 })).toBe(3);
  });

  it('judges a level with no average by its bitrate', () => {
    const levels = [l480, { height: 720, bitrate: 3_000_000 }];
    expect(ceiling({ levels, applied: 0, kbps: 9000 })).toBe(1);
    expect(ceiling({ levels, applied: 0, kbps: 8999 })).toBe(0);
  });

  it('takes the ceiling to the lowest rung when the whole ladder is above 480 lines', () => {
    expect(ceiling({ levels: [l720, l1080], applied: 0, kbps: 100_000, aheadS: 480 })).toBe(1);
    expect(ceiling({ levels: [l720, l1080], applied: 0, kbps: null })).toBe(0);
  });
});
