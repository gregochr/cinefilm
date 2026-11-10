import { describe, expect, it } from 'vitest';
import { ApiError } from '../api/client.js';
import { matchProblem, parseYear, resultPeople, resultRuntime, resultTitle } from './fixMatch.js';
import { changeLabel } from './scanFormat.js';

describe('fixMatch helpers', () => {
  it('reads only a four-digit year', () => {
    expect(parseYear('1973')).toBe(1973);
    expect(parseYear(' 2006 ')).toBe(2006);
    expect(parseYear('')).toBeNull();
    expect(parseYear('73')).toBeNull();
    expect(parseYear('abcd')).toBeNull();
    expect(parseYear(undefined)).toBeNull();
  });

  it('words each failure for the panel', () => {
    expect(matchProblem(new ApiError(503))).toMatch(/TMDB isn.t available/);
    expect(matchProblem(new ApiError(409))).toBe('A scan is running. Try again when it finishes.');
    expect(matchProblem(new ApiError(400))).toBe('Type a title to search for.');
    expect(matchProblem(new ApiError(404))).toMatch(/no longer there/);
    expect(matchProblem(new ApiError(500))).toBe('Something went wrong. Try again.');
    expect(matchProblem(new Error('network'))).toBe('Something went wrong. Try again.');
  });

  it('describes a result', () => {
    const result = {
      title: 'The Wicker Man',
      year: 1973,
      runtimeMinutes: 88,
      director: 'Robin Hardy',
      cast: ['Edward Woodward', 'Christopher Lee'],
    };
    expect(resultTitle(result)).toBe('The Wicker Man (1973)');
    expect(resultPeople(result)).toBe('Robin Hardy · Edward Woodward, Christopher Lee');
    expect(resultRuntime(result)).toBe('1h 28m');
    expect(resultPeople({ director: null, cast: ['A'] })).toBe('A');
    expect(resultPeople({ director: 'B' })).toBe('B');
    expect(resultPeople({ director: null })).toBe('');
    expect(resultRuntime({ runtimeMinutes: null })).toBe('');
    expect(resultTitle({ title: 'X', year: null })).toBe('X');
  });

  it('labels grouping changes', () => {
    expect(changeLabel('split')).toBe('Split');
    expect(changeLabel('join')).toBe('Joined');
    expect(changeLabel('flag')).toBe('Check');
    expect(changeLabel('other')).toBe('other');
  });
});
