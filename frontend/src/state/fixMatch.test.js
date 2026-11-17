import { describe, expect, it } from 'vitest';
import { ApiError } from '../api/client.js';
import {
  fileLine,
  matchProblem,
  parseYear,
  resultPeople,
  resultRuntime,
  resultTitle,
  suggestionNote,
} from './fixMatch.js';
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
    expect(
      matchProblem(
        new ApiError(409, undefined, { error: 'The library is busy; try again in a moment' }),
      ),
    ).toBe('The library is busy; try again in a moment.');
    expect(matchProblem(new ApiError(409, undefined, { error: 'A scan is running' }))).toBe(
      'A scan is running. Try again when it finishes.',
    );
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

  it('puts the file length after the names, and leaves it out when unknown', () => {
    expect(fileLine(['Enchanted.m4v'], 6420000)).toBe('File: Enchanted.m4v (1h 47m)');
    expect(fileLine(['A.m4v', 'B.m4v'], 3300000)).toBe('File: A.m4v · B.m4v (55m)');
    expect(fileLine(['Enchanted.m4v'], null)).toBe('File: Enchanted.m4v');
    expect(fileLine(['Enchanted.m4v'], 0)).toBe('File: Enchanted.m4v');
  });

  it('words the suggestion note for each reason and gives none otherwise', () => {
    expect(suggestionNote('runtime')).toBe('Same length as your file');
    expect(suggestionNote('guess')).toBe('CineFilm’s best guess');
    expect(suggestionNote(null)).toBe('');
    expect(suggestionNote(undefined)).toBe('');
  });
});
