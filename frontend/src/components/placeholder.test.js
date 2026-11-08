import { describe, expect, it } from 'vitest';
import { initials, placeholderColor } from './placeholder.js';

describe('placeholderColor', () => {
  it('is stable for the same film and a hex colour from the palette', () => {
    expect(placeholderColor(1, 'Heat')).toBe(placeholderColor(1, 'Heat'));
    expect(placeholderColor(1, 'Heat')).toMatch(/^#[0-9A-F]{6}$/);
  });

  it('depends on both the id and the title', () => {
    const colours = new Set([
      placeholderColor(1, 'Heat'),
      placeholderColor(2, 'Heat'),
      placeholderColor(1, 'Alien'),
      placeholderColor(3, 'Zodiac'),
      placeholderColor(4, 'Brazil'),
      placeholderColor(5, 'Seven'),
    ]);
    expect(colours.size).toBeGreaterThan(2);
  });

  it('copes with a missing id and a missing title', () => {
    expect(placeholderColor(undefined)).toMatch(/^#[0-9A-F]{6}$/);
    expect(placeholderColor(null, undefined)).toBe(placeholderColor(undefined));
  });
});

describe('initials', () => {
  it('takes the first letter of the first two words, upper-cased', () => {
    expect(initials('robert de niro')).toBe('RD');
    expect(initials('Michael Mann')).toBe('MM');
  });

  it('copes with one word, extra spaces and nothing', () => {
    expect(initials('Cher')).toBe('C');
    expect(initials('  Tilda   Swinton ')).toBe('TS');
    expect(initials('')).toBe('');
    expect(initials()).toBe('');
  });
});
