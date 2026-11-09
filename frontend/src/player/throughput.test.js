import { describe, expect, it } from 'vitest';
import { HOME_KBPS, MAX_SAMPLES, addSample, fragmentKbps, median } from './throughput.js';

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
