import { describe, expect, it } from 'vitest';
import { ApiError } from '../api/client.js';
import { isBareScanLock, lockText } from './lockText.js';

const FALLBACK = 'A scan is running. Try again when it finishes.';
const refused = (error) => new ApiError(409, undefined, error === undefined ? null : { error });

describe('isBareScanLock', () => {
  it('is true for the bare scan line and for no text, false for any other holder', () => {
    expect(isBareScanLock(refused('A scan is running'))).toBe(true);
    expect(isBareScanLock(refused(undefined))).toBe(true);
    expect(isBareScanLock(refused('A Plex import is running'))).toBe(false);
  });
});

describe('lockText', () => {
  it('gives the screen’s own words for the bare scan line, with or without its full stop', () => {
    expect(lockText(refused('A scan is running'), FALLBACK)).toBe(FALLBACK);
    expect(lockText(refused('A scan is running.'), FALLBACK)).toBe(FALLBACK);
  });

  it('gives the screen’s own words when the server sent none', () => {
    expect(lockText(refused(undefined), FALLBACK)).toBe(FALLBACK);
    expect(lockText(refused('  '), FALLBACK)).toBe(FALLBACK);
    expect(lockText(new TypeError('offline'), FALLBACK)).toBe(FALLBACK);
  });

  it('uses any other server text, adding a full stop only when it lacks one', () => {
    expect(
      lockText(refused('Subtitles are being changed or checked; try again in a moment'), FALLBACK),
    ).toBe('Subtitles are being changed or checked; try again in a moment.');
    expect(lockText(refused('The library is busy. Wait.'), FALLBACK)).toBe(
      'The library is busy. Wait.',
    );
  });
});
