import { describe, expect, it } from 'vitest';
import { placeholderColor } from './placeholder.js';

describe('placeholderColor pinned values', () => {
  it('keeps each film on the colour it has always had', () => {
    expect(placeholderColor(1, 'Heat')).toBe('#2B3A47');
    expect(placeholderColor(2, 'Alien')).toBe('#3A3D33');
    expect(placeholderColor(3, 'Zodiac')).toBe('#5A2F4E');
    expect(placeholderColor(null)).toBe('#2F4F45');
  });
});
