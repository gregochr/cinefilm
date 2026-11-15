import { describe, expect, it } from 'vitest';
import { ApiError } from '../api/client.js';
import { settingsPage } from './adminCopy.js';
import {
  MAX_MINUTES,
  MIN_MINUTES,
  RANGE_ERROR,
  describeInProgress,
  parseMinutes,
  saveMessage,
} from './useSettings.js';

describe('parseMinutes', () => {
  it.each([
    ['0', 0],
    ['1', 1],
    ['29', 29],
    ['30', 30],
    [' 7 ', 7],
    ['007', 7],
  ])('reads "%s" as %s', (text, minutes) => {
    expect(parseMinutes(text)).toBe(minutes);
  });

  it.each(['-1', '31', '', '  ', 'abc', '2.5', '1e1', '1000'])('rejects "%s"', (text) => {
    expect(parseMinutes(text)).toBeNull();
  });

  it('keeps the bounds and the range message in step', () => {
    expect([MIN_MINUTES, MAX_MINUTES]).toEqual([0, 30]);
    expect(RANGE_ERROR).toBe('Enter a whole number of minutes from 0 to 30.');
  });
});

describe('describeInProgress', () => {
  it('is singular for one film and plural otherwise', () => {
    expect(describeInProgress(1)).toBe('1 film currently in progress');
    expect(describeInProgress(0)).toBe('0 films currently in progress');
    expect(describeInProgress(12)).toBe('12 films currently in progress');
  });
});

describe('saveMessage', () => {
  it("is the server's own error text, exactly", () => {
    const failure = new ApiError(400, 'Bad Request', {
      error: 'Minimum watched must be 30 or less.',
    });
    expect(saveMessage(failure)).toBe('Minimum watched must be 30 or less.');
  });

  it.each([
    ['no body', new ApiError(500, 'x')],
    ['a blank error', new ApiError(500, 'x', { error: '   ' })],
    ['a non-text error', new ApiError(500, 'x', { error: 5 })],
    ['a network failure', new TypeError('Failed to fetch')],
    ['nothing', undefined],
  ])('falls back to the generic line for %s', (_name, failure) => {
    expect(saveMessage(failure)).toBe(settingsPage.saveError);
  });
});
