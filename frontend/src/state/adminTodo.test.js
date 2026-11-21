import { describe, expect, it } from 'vitest';
import { ApiError } from '../api/client.js';
import {
  TODO_GROUP_KEYS,
  TODO_ROW_CAP,
  bestGuess,
  buildTodoGroups,
  filesLine,
  footerFor,
  groupOfKind,
  guessTitle,
  matchLine,
  confirmProblem,
  leaveProblem,
  rowTitle,
  visibleRows,
} from './adminTodo.js';
import attention from '../test/fixtures/admin-attention.json';
import forcedNeed from '../test/fixtures/admin-forced-need.json';
import fullNeed from '../test/fixtures/admin-full-need.json';

const item = (kind, n = 1, over = {}) => ({
  kind,
  filmId: n,
  title: `Film ${n}`,
  year: 2000,
  detail: 'why',
  files: [`f${n}.m4v`],
  candidates: [],
  confident: false,
  ...over,
});
const rows = (count, make) => Array.from({ length: count }, (_, i) => make(i + 1));
const group = (key, count) => ({ key, rows: rows(count, (n) => ({ filmId: n })), total: count });

describe('groupOfKind', () => {
  it.each([
    ['needs-match', 'pickFilm'],
    ['multiple-files', 'severalFiles'],
    ['parts-mismatch', 'severalFiles'],
  ])('puts %s in %s', (kind, key) => {
    expect(groupOfKind(kind)).toBe(key);
  });

  it.each(['protected', 'needs-conversion', 'unreadable', 'something-new'])(
    'leaves %s out: it is not a To do item',
    (kind) => {
      expect(groupOfKind(kind)).toBeNull();
    },
  );
});

describe('buildTodoGroups', () => {
  it('routes the attention fixture by kind, in the order of the groups, dropping the excluded kinds', () => {
    const groups = buildTodoGroups({ attention: attention.items, full: null, forced: null });

    expect(groups.map((g) => [g.key, g.total])).toEqual([
      ['pickFilm', 2],
      ['severalFiles', 2],
    ]);
    expect(groups[0].rows.map((r) => r.filmId)).toEqual([31, 34]);
    expect(groups[1].rows.map((r) => r.filmId)).toEqual([6, 40]);
    const listed = groups.flatMap((g) => g.rows.map((r) => r.kind));
    expect(listed).not.toContain('protected');
    expect(listed).not.toContain('needs-conversion');
  });

  it('takes the subtitle rows the server flagged unresolved, and nothing it did not', () => {
    const groups = buildTodoGroups({
      attention: [],
      full: fullNeed.films,
      forced: forcedNeed.films,
    });

    expect(groups.map((g) => [g.key, g.rows.map((r) => r.title)])).toEqual([
      ['fullMissing', ['Rashomon']],
      ['forcedQuestion', ['Taken']],
    ]);
    // Being checked online is not "unresolved": those films wait without a To do row.
    expect(fullNeed.films.some((r) => r.beingChecked && !r.unresolved)).toBe(true);
  });

  it('does not re-derive the rule: a NEEDED row without the flag is not listed', () => {
    const row = { ...fullNeed.films[0], need: 'NEEDED', fullSubtitle: null, unresolved: false };

    expect(buildTodoGroups({ attention: [], full: [row], forced: [] })).toEqual([]);
  });

  it('omits empty groups, and treats a list that has not loaded as empty', () => {
    expect(buildTodoGroups({ attention: null, full: null, forced: null })).toEqual([]);
    const groups = buildTodoGroups({
      attention: [item('multiple-files')],
      full: [],
      forced: null,
    });
    expect(groups.map((g) => g.key)).toEqual(['severalFiles']);
  });

  it('keeps the order of the keys', () => {
    expect(TODO_GROUP_KEYS).toEqual(['pickFilm', 'severalFiles', 'fullMissing', 'forcedQuestion']);
  });
});

describe('the cap of five rows', () => {
  it('is five', () => {
    expect(TODO_ROW_CAP).toBe(5);
  });

  it.each([
    [4, 4],
    [5, 5],
    [6, 5],
  ])('shows %i rows as %i until expanded', (total, shown) => {
    expect(visibleRows(group('pickFilm', total), false)).toHaveLength(shown);
  });

  it('shows every row once expanded', () => {
    expect(visibleRows(group('pickFilm', 6), true)).toHaveLength(6);
    expect(visibleRows(group('pickFilm', 40), true)).toHaveLength(40);
  });

  it('keeps the server order when it cuts', () => {
    expect(visibleRows(group('fullMissing', 7), false).map((r) => r.filmId)).toEqual([
      1, 2, 3, 4, 5,
    ]);
  });
});

describe('footerFor', () => {
  it.each(['pickFilm', 'severalFiles'])('gives %s no footer at 4 or 5 rows', (key) => {
    expect(footerFor(group(key, 4), false)).toBeNull();
    expect(footerFor(group(key, 5), false)).toBeNull();
  });

  it.each(['pickFilm', 'severalFiles'])('gives %s "Show all" only above 5', (key) => {
    expect(footerFor(group(key, 6), false)).toEqual({ shown: 5, total: 6, control: 'show-all' });
  });

  it('turns "Show all" into "Show fewer" once expanded', () => {
    expect(footerFor(group('pickFilm', 6), true)).toEqual({
      shown: 6,
      total: 6,
      control: 'show-fewer',
    });
  });

  it.each(['fullMissing', 'forcedQuestion'])(
    'always links %s to Subtitles, at 1, 5 and 6 rows alike, never offering Show all',
    (key) => {
      expect(footerFor(group(key, 1), false)).toEqual({ shown: 1, total: 1, control: 'subtitles' });
      expect(footerFor(group(key, 5), false)).toEqual({ shown: 5, total: 5, control: 'subtitles' });
      expect(footerFor(group(key, 6), false)).toEqual({ shown: 5, total: 6, control: 'subtitles' });
    },
  );
});

describe('bestGuess and guessTitle', () => {
  it('is the first candidate, which the server ranks best first', () => {
    expect(bestGuess(attention.items[0]).tmdbId).toBe(16307);
  });

  it('is null with no candidates', () => {
    expect(bestGuess(item('needs-match'))).toBeNull();
    expect(bestGuess({ candidates: undefined })).toBeNull();
  });

  it('words the guess with its year when there is one', () => {
    expect(guessTitle(attention.items[0])).toBe('The Wicker Man (1973)');
    const noYear = item('needs-match', 1, {
      candidates: [{ tmdbId: 1, title: 'Dune', year: null, score: 90 }],
    });
    expect(guessTitle(noYear)).toBe('Dune');
    expect(guessTitle(item('needs-match'))).toBeNull();
  });
});

describe('the lines under a row', () => {
  it('words a match row with the file and the best guess, never a percentage', () => {
    expect(matchLine(attention.items[0])).toBe(
      'The Wicker Man.m4v · best guess: The Wicker Man (1973)',
    );
    expect(matchLine(item('needs-match', 1, { files: ['a.m4v', 'b.m4v'] }))).toBe(
      'a.m4v · b.m4v · best guess: none',
    );
    expect(matchLine(attention.items[0])).not.toMatch(/%|sure/);
  });

  it('words a several-files row with the count and the scanner’s suggestion', () => {
    expect(filesLine(attention.items[3])).toBe(
      '2 files · 2 untagged versions of one film, runtimes differ by 22%',
    );
    expect(filesLine(item('multiple-files', 1, { files: ['a.m4v'], detail: 'one' }))).toBe(
      '1 file · one',
    );
  });
});

describe('rowTitle', () => {
  it('gives the title and the film’s own year', () => {
    expect(rowTitle(attention.items[5])).toEqual({ title: 'Suspiria', year: '1977' });
    expect(rowTitle(fullNeed.films[0])).toEqual({ title: 'Das Boot', year: '1981' });
  });

  it('marks the best guess’s year with a question mark when the film has none of its own', () => {
    expect(rowTitle(attention.items[0])).toEqual({ title: 'The Wicker Man', year: '1973?' });
  });

  it('gives no year when there is nothing to show, and never guesses for another kind', () => {
    expect(rowTitle(item('needs-match', 1, { year: null }))).toEqual({
      title: 'Film 1',
      year: null,
    });
    const other = item('multiple-files', 1, {
      year: null,
      candidates: [{ tmdbId: 1, title: 'A', year: 1999, score: 10 }],
    });
    expect(rowTitle(other)).toEqual({ title: 'Film 1', year: null });
  });
});

describe('confirmProblem', () => {
  it('says the film is gone for a 404, and asks for a re-read', () => {
    expect(confirmProblem(new ApiError(404))).toEqual({
      message: 'That film is no longer in the library. The list is being updated.',
      gone: true,
    });
  });

  it.each([
    [409, 'A scan is running. Try again when it finishes.'],
    [503, 'TMDB isn’t available right now. Try again in a moment.'],
    [400, 'Something went wrong. Try again.'],
    [500, 'Something went wrong. Try again.'],
  ])('words a %i for the row without a re-read', (status, message) => {
    expect(confirmProblem(new ApiError(status))).toEqual({ message, gone: false });
  });

  it('shows the server’s own words for a 409 when it sent some', () => {
    expect(
      confirmProblem(
        new ApiError(409, undefined, { error: 'The library is busy; try again in a moment' }),
      ),
    ).toEqual({ message: 'The library is busy; try again in a moment.', gone: false });
  });

  it('keeps its own guidance for the server’s bare scan line', () => {
    expect(confirmProblem(new ApiError(409, undefined, { error: 'A scan is running' }))).toEqual({
      message: 'A scan is running. Try again when it finishes.',
      gone: false,
    });
  });

  it('is generic for a failure that is not an API answer', () => {
    expect(confirmProblem(new TypeError('network'))).toEqual({
      message: 'Something went wrong. Try again.',
      gone: false,
    });
  });

  it('never uses the Fix match dialog’s words', () => {
    for (const status of [400, 404, 409, 500, 503]) {
      expect(confirmProblem(new ApiError(status)).message).not.toMatch(/Close this|Type a title/);
    }
  });
});

describe('leaveProblem', () => {
  it('says the film is gone for a 404, and asks for a re-read', () => {
    expect(leaveProblem(new ApiError(404))).toEqual({
      message: 'That film is no longer in the library. The list is being updated.',
      gone: true,
    });
  });

  it.each([
    [409, 'A scan is running — try again when it finishes.'],
    [400, 'Couldn’t leave it — try again.'],
    [500, 'Couldn’t leave it — try again.'],
    [503, 'Couldn’t leave it — try again.'],
  ])('words a %i for the row without a re-read', (status, message) => {
    expect(leaveProblem(new ApiError(status))).toEqual({ message, gone: false });
  });

  it('treats "not waiting for a match" as a stale row: says so and asks for a re-read', () => {
    expect(
      leaveProblem(new ApiError(409, undefined, { error: 'This film is not waiting for a match' })),
    ).toEqual({ message: 'That film is already settled. The list is being updated.', gone: true });
  });

  it('keeps its own words for the server’s bare scan line and uses the server’s text for any other 409', () => {
    expect(leaveProblem(new ApiError(409, undefined, { error: 'A scan is running' })).message).toBe(
      'A scan is running — try again when it finishes.',
    );
    expect(
      leaveProblem(
        new ApiError(409, undefined, { error: 'The library is busy; try again in a moment' }),
      ),
    ).toEqual({ message: 'The library is busy; try again in a moment.', gone: false });
  });

  it('says it was not a network error for anything that is not an ApiError', () => {
    expect(leaveProblem(new Error('offline'))).toEqual({
      message: 'Couldn’t leave it — try again.',
      gone: false,
    });
  });
});
