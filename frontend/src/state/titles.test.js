import { describe, expect, it } from 'vitest';
import { captionTitle, joinMeta, labelTitle } from './titles.js';

describe('captionTitle', () => {
  it('joins title and year with the middle dot', () => {
    expect(captionTitle('Heat', 1995)).toBe('Heat · 1995');
  });

  it('is just the title, with no dot, when the year is unknown', () => {
    expect(captionTitle('Heat', null)).toBe('Heat');
    expect(captionTitle('Heat', undefined)).toBe('Heat');
  });
});

describe('labelTitle', () => {
  it('joins title and year with a comma', () => {
    expect(labelTitle('Heat', 1995)).toBe('Heat, 1995');
  });

  it('is just the title when the year is unknown', () => {
    expect(labelTitle('Heat', null)).toBe('Heat');
  });
});

describe('joinMeta', () => {
  it('joins what exists with the middle dot and drops what does not', () => {
    expect(joinMeta(1995, '2h 50m')).toBe('1995 · 2h 50m');
    expect(joinMeta(null, '2h 50m')).toBe('2h 50m');
    expect(joinMeta(1995, undefined, '')).toBe('1995');
    expect(joinMeta()).toBe('');
  });
});
