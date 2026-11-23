import { describe, expect, it } from 'vitest';
import {
  ARM_LASTS_S,
  ARM_MIN_AHEAD_S,
  RELEASE_LASTS_S,
  RELEASE_MIN_AHEAD_S,
  createFloorHold,
  lastsS,
} from './floorHold.js';

// The 360p variant's BANDWIDTH in kbit/s: Rung.peakBandwidth(), 600k * 1.2 + 96k audio.
const RATE = (600_000 * 1.2 + 96_000) / 1000;

describe('lastsS', () => {
  it('is the seconds in hand over the share of the film the link does not carry', () => {
    expect(lastsS(300, 408, RATE)).toBeCloseTo(600, 6); // half the rate: twice as long
    expect(lastsS(240, 0, RATE)).toBe(240); // nothing arrives: the bank as it is
    expect(lastsS(227, 642, RATE)).toBeCloseTo(1_064.55, 1); // minute 40 of the 23 Nov run
  });

  it('is unbounded when the link is as fast as the film or faster', () => {
    expect(lastsS(240, RATE, RATE)).toBe(Infinity);
    expect(lastsS(240, 4_143, RATE)).toBe(Infinity);
  });
});

describe('createFloorHold', () => {
  const fast = { armKbps: 3_000, releaseKbps: 3_000, rateKbps: RATE };

  it('has the plan’s thresholds', () => {
    expect(ARM_LASTS_S).toBe(900);
    expect(RELEASE_LASTS_S).toBe(600);
    expect(ARM_MIN_AHEAD_S).toBe(240);
    expect(RELEASE_MIN_AHEAD_S).toBe(180);
  });

  it('starts released and stays released with no samples', () => {
    expect(createFloorHold().update({ aheadS: 0, rateKbps: RATE })).toBe(false);
    expect(createFloorHold().update({ aheadS: 480, rateKbps: RATE })).toBe(false);
  });

  it('arms at 240 s in hand and not at 239 s', () => {
    expect(createFloorHold().update({ ...fast, aheadS: 239 })).toBe(false);
    expect(createFloorHold().update({ ...fast, aheadS: 240 })).toBe(true);
  });

  it('arms when the bank would last 900 s at 360p and not when it would last less', () => {
    // At 240 s in hand it lasts 900 s on a link of 816 * (1 - 240/900) = 598.4 kbit/s.
    const kbps = RATE * (1 - 240 / 900);
    expect(
      createFloorHold().update({ aheadS: 240, armKbps: kbps, releaseKbps: null, rateKbps: RATE }),
    ).toBe(true);
    expect(
      createFloorHold().update({
        aheadS: 240,
        armKbps: kbps - 1,
        releaseKbps: null,
        rateKbps: RATE,
      }),
    ).toBe(false);
  });

  it('does not arm on a link that has no samples to read', () => {
    expect(
      createFloorHold().update({ aheadS: 480, armKbps: null, releaseKbps: 3_000, rateKbps: RATE }),
    ).toBe(false);
  });

  it('counts a link exactly at the 360p rate as unbounded', () => {
    expect(
      createFloorHold().update({ aheadS: 240, armKbps: RATE, releaseKbps: RATE, rateKbps: RATE }),
    ).toBe(true);
  });

  it('stays armed from 180 s in hand and releases below it, whatever the samples say', () => {
    const hold = createFloorHold();
    hold.update({ ...fast, aheadS: 300 });
    expect(hold.update({ ...fast, aheadS: 240 })).toBe(true);
    expect(hold.update({ ...fast, aheadS: 181 })).toBe(true);
    expect(hold.update({ ...fast, aheadS: 180 })).toBe(true);
    expect(hold.update({ ...fast, aheadS: 179 })).toBe(false);
  });

  it('does not re-arm between 180 s and 239 s once released', () => {
    const hold = createFloorHold();
    hold.update({ ...fast, aheadS: 300 });
    hold.update({ ...fast, aheadS: 100 });
    expect(hold.update({ ...fast, aheadS: 200 })).toBe(false);
    expect(hold.update({ ...fast, aheadS: 240 })).toBe(true);
  });

  it('releases when the bank would last under 600 s at the lowest recent speed', () => {
    const hold = createFloorHold();
    hold.update({ ...fast, aheadS: 300 });
    // At 300 s in hand it lasts 600 s on a link of 408 kbit/s.
    expect(hold.update({ ...fast, aheadS: 300, releaseKbps: 408 })).toBe(true);
    expect(hold.update({ ...fast, aheadS: 300, releaseKbps: 407 })).toBe(false);
  });

  it('lets the release win over the arming when both read', () => {
    expect(
      createFloorHold().update({ aheadS: 300, armKbps: 3_000, releaseKbps: 0, rateKbps: RATE }),
    ).toBe(false);
  });

  it('ignores a release speed that is unknown', () => {
    const hold = createFloorHold();
    hold.update({ ...fast, aheadS: 300 });
    expect(hold.update({ ...fast, aheadS: 300, releaseKbps: null })).toBe(true);
    expect(hold.update({ ...fast, aheadS: 300, releaseKbps: Number.NaN })).toBe(true);
  });

  it('stays armed through a sequence shaped like minutes 32 to 48 of the 23 Nov run', () => {
    const hold = createFloorHold();
    // [aheadS, kbit/s]: the bank at the 256 s the 720p budget allows, then the link slowing and the bank refilling.
    const minutes = [
      [250, 2_600],
      [245, 3_000],
      [242, 2_800],
      [227, 642],
      [300, 915],
      [335, 775],
      [327, 480],
      [330, 1_870],
    ];
    for (const [aheadS, kbps] of minutes) {
      hold.update({ aheadS, armKbps: kbps, releaseKbps: kbps, rateKbps: RATE });
    }
    expect(hold.update({ aheadS: 330, armKbps: 1_870, releaseKbps: 1_870, rateKbps: RATE })).toBe(
      true,
    );
  });

  it('releases on a single fragment well under the minute’s median', () => {
    const hold = createFloorHold();
    hold.update({ ...fast, aheadS: 300 });
    // 227 s in hand at 300 kbit/s lasts about 359 s.
    expect(hold.update({ aheadS: 227, armKbps: 3_000, releaseKbps: 300, rateKbps: RATE })).toBe(
      false,
    );
  });

  it.each([[Number.NaN], [undefined], [-5], [Infinity]])('counts an aheadS of %s as 0', (value) => {
    const hold = createFloorHold();
    hold.update({ ...fast, aheadS: 300 });
    expect(hold.update({ ...fast, aheadS: value })).toBe(false);
  });

  it.each([[Number.NaN], [undefined], [0], [-1]])(
    'is released without a usable rate (%s)',
    (rateKbps) => {
      const hold = createFloorHold();
      hold.update({ ...fast, aheadS: 300 });
      expect(hold.update({ aheadS: 300, armKbps: 3_000, releaseKbps: 3_000, rateKbps })).toBe(
        false,
      );
    },
  );

  it('counts an arming speed that is not a number as unknown', () => {
    expect(
      createFloorHold().update({
        aheadS: 300,
        armKbps: Number.NaN,
        releaseKbps: null,
        rateKbps: RATE,
      }),
    ).toBe(false);
    expect(
      createFloorHold().update({ aheadS: 300, armKbps: -1, releaseKbps: null, rateKbps: RATE }),
    ).toBe(false);
  });

  it('uses stale fast samples only while 180 s are in hand: 181 s holds, 179 s does not', () => {
    const hold = createFloorHold();
    hold.update({ ...fast, aheadS: 300 });
    expect(hold.update({ ...fast, aheadS: 181 })).toBe(true);
    expect(hold.update({ ...fast, aheadS: 179 })).toBe(false);
  });

  it('reset releases at once, and it arms again only on the next reading', () => {
    const hold = createFloorHold();
    hold.update({ ...fast, aheadS: 300 });
    hold.reset();
    expect(hold.update({ aheadS: 300, rateKbps: RATE })).toBe(false);
    expect(hold.update({ ...fast, aheadS: 300 })).toBe(true);
  });
});
