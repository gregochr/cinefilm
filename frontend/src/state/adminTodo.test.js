import { describe, expect, it } from 'vitest';
import {
  TODO_GROUP_KEYS,
  TODO_ROW_CAP,
  bestGuess,
  buildTodoGroups,
  filesLine,
  footerFor,
  forcedLine,
  fullLine,
  groupOfKind,
  guessTitle,
  matchLine,
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
  it('is the candidate with the highest score, not simply the first', () => {
    const wicker = attention.items[0];
    expect(bestGuess(wicker).tmdbId).toBe(16307);
    const reordered = { ...wicker, candidates: [...wicker.candidates].reverse() };
    expect(bestGuess(reordered).tmdbId).toBe(16307);
  });

  it('is null with no candidates, and the first of equal scores', () => {
    expect(bestGuess(item('needs-match'))).toBeNull();
    expect(bestGuess({ candidates: undefined })).toBeNull();
    const tie = item('needs-match', 1, {
      candidates: [
        { tmdbId: 1, title: 'A', year: 1, score: 50 },
        { tmdbId: 2, title: 'B', year: 2, score: 50 },
      ],
    });
    expect(bestGuess(tie).tmdbId).toBe(1);
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

  it('words a full-subtitles row from the audio and the subtitle that plays', () => {
    const rashomon = fullNeed.films.find((r) => r.title === 'Rashomon');
    expect(fullLine(rashomon)).toBe('None yet');
    expect(fullLine(fullNeed.films.find((r) => r.title === 'Das Boot'))).toBe(
      'Audio: German · Playing: your file',
    );
  });

  it('words a forced row with the server’s reason', () => {
    expect(forcedLine(forcedNeed.films.find((r) => r.title === 'Taken'))).toBe(
      'OpenSubtitles has 2 foreign-parts-only files',
    );
  });
});
