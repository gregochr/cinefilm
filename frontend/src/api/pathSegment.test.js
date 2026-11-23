import { describe, expect, it } from 'vitest';
import { pathSegment } from './pathSegment.js';

describe('pathSegment', () => {
  it('leaves a number as it is', () => {
    expect(pathSegment(6)).toBe('6');
  });

  it('leaves a UUID as it is', () => {
    expect(pathSegment('3f2b8c1e-5d4a-4e6f-9a7b-0c1d2e3f4a5b')).toBe(
      '3f2b8c1e-5d4a-4e6f-9a7b-0c1d2e3f4a5b',
    );
  });

  it('encodes a slash, so a value cannot add a segment', () => {
    expect(pathSegment('7/preferences')).toBe('7%2Fpreferences');
  });

  it('encodes a question mark, a hash and a percent sign', () => {
    expect(pathSegment('a?b#c%d')).toBe('a%3Fb%23c%25d');
  });

  it('refuses a single dot', () => {
    expect(() => pathSegment('.')).toThrow(new RangeError('Not a path segment: .'));
  });

  it('refuses a double dot', () => {
    expect(() => pathSegment('..')).toThrow(new RangeError('Not a path segment: ..'));
  });

  it('keeps a value that only starts with dots', () => {
    expect(pathSegment('..x')).toBe('..x');
  });
});
