import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  HOME_KBPS,
  MAX_SAMPLES,
  REMEMBER_MS,
  SPEED_KEY,
  addSample,
  forgetSpeed,
  fragmentKbps,
  lowerMiddle,
  median,
  networkGeneration,
  recallSpeed,
  rememberSpeed,
  resetThroughputState,
} from './throughput.js';

describe('median', () => {
  it('is null with no samples', () => {
    expect(median([])).toBeNull();
  });

  it('takes the middle of an odd count regardless of order', () => {
    expect(median([30000, 1000, 5000])).toBe(5000);
  });

  it('averages the middle two of an even count', () => {
    expect(median([1000, 4000, 2000, 9000])).toBe(3000);
  });

  it('is not moved by one outlier (a single fast fragment does not make the link home)', () => {
    expect(median([2000, 2500, 900000])).toBe(2500);
  });

  it('does not reorder the caller’s array', () => {
    const samples = [3, 1, 2];
    median(samples);
    expect(samples).toEqual([3, 1, 2]);
  });
});

describe('addSample', () => {
  it('appends a valid sample', () => {
    expect(addSample([1000], 2000)).toEqual([1000, 2000]);
  });

  it.each([0, -5, NaN, Infinity, undefined, null])('drops %s', (bad) => {
    const samples = [1000];
    expect(addSample(samples, bad)).toBe(samples);
  });

  it('keeps only the newest nine', () => {
    const full = Array.from({ length: MAX_SAMPLES }, (_, i) => i + 1);
    expect(addSample(full, 10)).toEqual([2, 3, 4, 5, 6, 7, 8, 9, 10]);
  });

  it('keeps a window of the size asked for', () => {
    expect(addSample([1, 2], 3, 2)).toEqual([2, 3]);
  });
});

describe('Home threshold', () => {
  it('is 20 Mbps', () => {
    expect(HOME_KBPS).toBe(20000);
  });
});

describe('fragmentKbps', () => {
  it('counts from the first byte to the end', () => {
    // 1,250,000 bytes = 10,000 kbit in 500 ms = 20,000 kbit/s
    expect(fragmentKbps({ loaded: 1_250_000, loading: { start: 0, first: 100, end: 600 } })).toBe(
      20000,
    );
  });

  it('counts from the start when there was no first-byte time', () => {
    expect(fragmentKbps({ loaded: 125_000, loading: { start: 100, first: 0, end: 600 } })).toBe(
      2000,
    );
  });

  it('is null for no bytes, no elapsed time or no stats', () => {
    expect(fragmentKbps({ loaded: 0, loading: { start: 0, first: 1, end: 9 } })).toBeNull();
    expect(fragmentKbps({ loaded: 10, loading: { start: 5, first: 5, end: 5 } })).toBeNull();
    expect(fragmentKbps({ loaded: 10 })).toBeNull();
    expect(fragmentKbps(undefined)).toBeNull();
  });
});

describe('lowerMiddle', () => {
  it('is null with no samples', () => {
    expect(lowerMiddle([])).toBeNull();
  });

  it('takes the middle of an odd count regardless of order', () => {
    expect(lowerMiddle([30000, 1000, 5000])).toBe(5000);
  });

  it('takes the lower of the middle two for an even count: 100,000 and 500 give 500', () => {
    expect(lowerMiddle([100000, 500])).toBe(500);
    expect(lowerMiddle([4000, 1000, 9000, 2000])).toBe(2000);
    // median() is unchanged: it still averages the middle two.
    expect(median([1000, 4000, 2000, 9000])).toBe(3000);
  });

  it('does not reorder the caller’s array', () => {
    const samples = [3, 1, 2];
    lowerMiddle(samples);
    expect(samples).toEqual([3, 1, 2]);
  });
});

describe('the remembered speed', () => {
  beforeEach(() => resetThroughputState());
  afterEach(() => vi.unstubAllGlobals());

  const NOW = 1_000_000;
  const store = (value) => sessionStorage.setItem(SPEED_KEY, JSON.stringify(value));

  it('is read back as the whole number that was written, with its time', () => {
    rememberSpeed(2500.4, NOW);
    expect(JSON.parse(sessionStorage.getItem(SPEED_KEY))).toEqual({ kbps: 2500, at: NOW });
    expect(recallSpeed(NOW)).toBe(2500);
  });

  it('writes the current time by default and reads against the current time by default', () => {
    vi.useFakeTimers();
    try {
      vi.setSystemTime(5000);
      rememberSpeed(1200);
      expect(JSON.parse(sessionStorage.getItem(SPEED_KEY)).at).toBe(5000);
      vi.setSystemTime(5000 + REMEMBER_MS - 1);
      expect(recallSpeed()).toBe(1200);
      vi.setSystemTime(5000 + REMEMBER_MS);
      expect(recallSpeed()).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  it.each([
    [0, 3000],
    [599_999, 3000],
    [600_000, null],
    [-1, null],
  ])('an entry %i ms old reads as %s', (age, expected) => {
    store({ kbps: 3000, at: NOW - age });
    expect(recallSpeed(NOW)).toBe(expected);
    expect(REMEMBER_MS).toBe(600_000);
  });

  it.each([
    ['missing', undefined],
    ['not JSON', 'nonsense'],
    ['zero', { kbps: 0, at: NOW }],
    ['negative', { kbps: -5, at: NOW }],
    ['not a number', { kbps: 'fast', at: NOW }],
    ['without a time', { kbps: 3000 }],
    ['a bare number', 7],
  ])('an entry that is %s is read as nothing', (_name, entry) => {
    if (entry === 'nonsense') sessionStorage.setItem(SPEED_KEY, 'nonsense');
    else if (entry !== undefined) store(entry);
    expect(recallSpeed(NOW)).toBeNull();
  });

  it('survives a storage that throws on read, on write and on remove', () => {
    const blocked = () => {
      throw new Error('blocked');
    };
    vi.stubGlobal('sessionStorage', { getItem: blocked, setItem: blocked, removeItem: blocked });
    expect(() => rememberSpeed(3000, NOW)).not.toThrow();
    expect(recallSpeed(NOW)).toBeNull();
    expect(() => forgetSpeed()).not.toThrow();
  });
});

describe('a network change forgets the speed', () => {
  beforeEach(() => resetThroughputState());

  it.each(['offline', 'online'])(
    'on %s: removes the remembered speed and moves the counter',
    (name) => {
      rememberSpeed(3000);
      const before = networkGeneration();
      window.dispatchEvent(new Event(name));
      expect(sessionStorage.getItem(SPEED_KEY)).toBeNull();
      expect(networkGeneration()).toBe(before + 1);
    },
  );

  it('the reset puts the counter and the speed back', () => {
    window.dispatchEvent(new Event('offline'));
    rememberSpeed(3000);
    resetThroughputState();
    expect(networkGeneration()).toBe(0);
    expect(sessionStorage.getItem(SPEED_KEY)).toBeNull();
  });
});
