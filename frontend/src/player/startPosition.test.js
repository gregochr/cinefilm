import { describe, expect, it } from 'vitest';
import { parseStartAt, startPosition } from './resume.js';

describe('parseStartAt (the `at` address value, in edition milliseconds)', () => {
  it('reads a whole number of milliseconds', () => {
    expect(parseStartAt('2196778')).toBe(2196778);
    expect(parseStartAt('0')).toBe(0);
  });

  it('drops a fraction', () => {
    expect(parseStartAt('1500.9')).toBe(1500);
  });

  it('is null when there is no value or it is not a time', () => {
    expect(parseStartAt(null)).toBeNull();
    expect(parseStartAt(undefined)).toBeNull();
    expect(parseStartAt('')).toBeNull();
    expect(parseStartAt('  ')).toBeNull();
    expect(parseStartAt('soon')).toBeNull();
    expect(parseStartAt('-1')).toBeNull();
    expect(parseStartAt('Infinity')).toBeNull();
  });
});

describe('startPosition', () => {
  it('starts at the explicit time, not at the resume point and without the 10 s rewind', () => {
    expect(startPosition({ at: 2_000_000, savedMs: 3_600_000, runtimeMs: 6_000_000 })).toBe(
      2_000_000,
    );
  });

  it('an explicit 0 is the start of the film, not "no time given"', () => {
    expect(startPosition({ at: 0, savedMs: 3_600_000, runtimeMs: 6_000_000 })).toBe(0);
  });

  it('beats start=0 too', () => {
    expect(startPosition({ at: 5000, startAtZero: true, savedMs: 0, runtimeMs: 6_000_000 })).toBe(
      5000,
    );
  });

  it('keeps an explicit time inside the film, exactly at its end and one millisecond past it', () => {
    expect(startPosition({ at: 6_000_000, runtimeMs: 6_000_000 })).toBe(6_000_000);
    expect(startPosition({ at: 6_000_001, runtimeMs: 6_000_000 })).toBe(6_000_000);
  });

  it('uses the time as given when the runtime is unknown', () => {
    expect(startPosition({ at: 9_000_000, runtimeMs: 0 })).toBe(9_000_000);
    expect(startPosition({ at: 9_000_000 })).toBe(9_000_000);
  });

  it('without a time starts at 0 when asked and otherwise at the resume point', () => {
    expect(startPosition({ startAtZero: true, savedMs: 3_600_000 })).toBe(0);
    expect(startPosition({ savedMs: 3_600_000 })).toBe(3_590_000);
    expect(startPosition({ savedMs: 5000 })).toBe(0);
    expect(startPosition({})).toBe(0);
  });
});
