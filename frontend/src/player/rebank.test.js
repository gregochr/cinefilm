import { describe, expect, it } from 'vitest';
import {
  REBANK_COOLDOWN_MS,
  REBANK_DONE_AT_S,
  REBANK_HEADROOM,
  REBANK_KEEP_S,
  REBANK_MIN_AHEAD_S,
  REBANK_MIN_RATES,
  REBANK_QUIET_MS,
  REBANK_UNDO_BELOW_S,
  rebankDecision,
} from './rebank.js';

// 240p, 360p, 480p, 720p: the 480p level is index 2, the highest a re-bank may reach.
const levels = [
  { height: 240, bitrate: 364_000 },
  { height: 360, bitrate: 816_000 },
  { height: 480, bitrate: 1_328_000 },
  { height: 720, bitrate: 3_128_000 },
];
const NEED_360 = 2448; // 3 x the 360p level's peak, kbit/s

// Ten-second fragments from `offset` to 490 s, all at `level`.
const frags = (level, offset = 0) =>
  Array.from({ length: 49 }, (_, i) => ({
    start: offset + i * 10,
    end: offset + i * 10 + 10,
    level,
  }));

const base = {
  levels,
  applied: 2,
  fetched: 2, // hls.js is already fetching at or above every rung these tests re-bank to
  banked: frags(0),
  playhead: 0,
  aheadS: 480,
  headroom: [NEED_360, NEED_360, NEED_360],
  inFlight: false,
  troubled: false,
  now: 1_000_000,
};
const decide = (over = {}) => rebankDecision({ ...base, ...over });

describe('rebankDecision', () => {
  it('names the constants the plan fixes', () => {
    expect(REBANK_MIN_AHEAD_S).toBe(470);
    expect(REBANK_KEEP_S).toBe(300);
    expect(REBANK_UNDO_BELOW_S).toBe(270);
    expect(REBANK_DONE_AT_S).toBe(460);
    expect(REBANK_QUIET_MS).toBe(120_000);
    expect(REBANK_COOLDOWN_MS).toBe(600_000);
    expect(REBANK_HEADROOM).toBe(3);
    expect(REBANK_MIN_RATES).toBe(3);
  });

  it('goes one rung up from the first fragment 300 s ahead', () => {
    expect(decide()).toEqual({ level: 1, from: 300 });
  });

  it.each([
    [469.9, null],
    [470, { level: 1, from: 300 }],
  ])('needs a bank of 470 s: %f s ahead', (aheadS, expected) => {
    expect(decide({ aheadS })).toEqual(expected);
  });

  describe('the film kept in front of the playhead', () => {
    it('starts at a fragment that begins 300 s ahead', () => {
      expect(decide({ banked: frags(0) }).from).toBe(300);
    });

    it('skips a fragment that begins 0.1 s short of 300 s ahead', () => {
      // Starts at 9.9, 19.9 ... 299.9, 309.9: the fragment at 299.9 is inside the kept film.
      expect(decide({ banked: frags(0, 9.9) }).from).toBe(309.9);
    });

    it('never starts mid-fragment: the start is the next fragment start after playhead + 300', () => {
      expect(decide({ playhead: 5.5 }).from).toBe(310);
    });

    it('does nothing when no fragment begins 300 s ahead', () => {
      expect(decide({ banked: frags(0).slice(0, 30) })).toBeNull();
    });
  });

  describe('every fragment past the start is at one rung', () => {
    it('says no when one fragment past the start is at another rung', () => {
      const banked = frags(0).map((f) => (f.start === 400 ? { ...f, level: 1 } : f));
      expect(decide({ banked })).toBeNull();
    });

    it('does not mind another rung in the kept film in front of the start', () => {
      const banked = frags(0).map((f) => (f.start < 300 ? { ...f, level: 1 } : f));
      expect(decide({ banked })).toEqual({ level: 1, from: 300 });
    });

    it('takes the rung after the one banked, whichever it is', () => {
      expect(decide({ banked: frags(1) })).toBeNull(); // 480p would need 3 x 1,328 kbit/s
      expect(decide({ banked: frags(1), headroom: [3984, 3984, 3984] })).toEqual({
        level: 2,
        from: 300,
      });
    });
  });

  it('says no while a fragment is in flight', () => {
    expect(decide({ inFlight: true })).toBeNull();
  });

  it('says no after fragment trouble until a fragment has loaded', () => {
    expect(decide({ troubled: true })).toBeNull();
  });

  describe('never above 480p', () => {
    it('says no when the next rung is above the 480p level', () => {
      const banked = frags(2);
      // hls.js is already on the next rung (3), so only the 480p limit can refuse.
      const high = { applied: 3, fetched: 3, headroom: [20_000, 20_000, 20_000] };
      expect(decide({ banked, ...high })).toBeNull();
    });

    it('stops at the 360p level of a ladder with no 480p rung', () => {
      const ladder = [levels[0], levels[1], levels[3]];
      const high = { headroom: [20_000, 20_000, 20_000], applied: 2 };
      expect(decide({ levels: ladder, banked: frags(0), ...high })).toEqual({
        level: 1,
        from: 300,
      });
      expect(decide({ levels: ladder, banked: frags(1), ...high })).toBeNull();
    });

    it('says no when there is no rung above', () => {
      const ladder = [levels[0]];
      expect(decide({ levels: ladder, applied: 1 })).toBeNull();
    });
  });

  describe('the cap Auto is under', () => {
    it('must already allow the next rung', () => {
      expect(decide({ applied: 0 })).toBeNull();
      expect(decide({ applied: 1 })).toEqual({ level: 1, from: 300 });
    });

    it('counts as the lowest level before it is known', () => {
      expect(decide({ applied: null })).toBeNull();
    });
  });

  describe('the level hls.js is fetching', () => {
    // The banked rung is 0, so the new rung is 1.
    it.each([
      ['not known yet (null)', null, null],
      ['one below the new rung', 0, null],
      ['the new rung', 1, { level: 1, from: 300 }],
      ['above the new rung', 2, { level: 1, from: 300 }],
    ])('is %s', (_name, fetched, expected) => {
      expect(decide({ fetched })).toEqual(expected);
    });

    it('is read against whichever rung is banked: banked at 1, the next rung is 2', () => {
      expect(decide({ banked: frags(1), headroom: [3984, 3984, 3984], fetched: 1 })).toBeNull();
      expect(decide({ banked: frags(1), headroom: [3984, 3984, 3984], fetched: 2 })).toEqual({
        level: 2,
        from: 300,
      });
    });
  });

  describe('headroom', () => {
    it.each([
      [NEED_360 - 0.01, null],
      [NEED_360, { level: 1, from: 300 }],
    ])('needs 3 x the next rung’s peak: the lower middle is %f kbit/s', (kbps, expected) => {
      expect(decide({ headroom: [kbps, kbps, kbps] })).toEqual(expected);
    });

    it('reads the peak (maxBitrate) of the rung when the level has one', () => {
      const peaked = levels.map((l, i) => (i === 1 ? { ...l, maxBitrate: 1_000_000 } : l));
      expect(decide({ levels: peaked, headroom: [2999, 2999, 2999] })).toBeNull();
      expect(decide({ levels: peaked, headroom: [3000, 3000, 3000] })).toEqual({
        level: 1,
        from: 300,
      });
    });

    it('needs three rates: two are not enough', () => {
      expect(decide({ headroom: [20_000, 20_000] })).toBeNull();
      expect(decide({ headroom: [20_000, 20_000, 20_000] })).toEqual({ level: 1, from: 300 });
    });

    it('reads the lower middle, not the best, of the rates', () => {
      expect(decide({ headroom: [100, 100, 20_000] })).toBeNull();
      expect(decide({ headroom: [100, NEED_360, NEED_360] })).toEqual({ level: 1, from: 300 });
    });
  });

  describe.each([['lastTroubleAt'], ['lastStepDownAt'], ['lastRebankAt']])(
    'the quiet time after %s',
    (key) => {
      it.each([
        [REBANK_QUIET_MS - 0.1, null],
        [REBANK_QUIET_MS, { level: 1, from: 300 }],
      ])('is 120 s: %f ms since', (ms, expected) => {
        expect(decide({ [key]: base.now - ms })).toEqual(expected);
      });
    },
  );

  describe('the cool-down after an undo', () => {
    it.each([
      [599_999, null],
      [600_000, { level: 1, from: 300 }],
    ])('is 10 minutes: %i ms since', (ms, expected) => {
      expect(decide({ lastUndoAt: base.now - ms })).toEqual(expected);
    });
  });
});
