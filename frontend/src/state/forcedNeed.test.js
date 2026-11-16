import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client.js';
import {
  applyRow,
  languageName,
  needProblem,
  reasonText,
  resetLanguageNames,
} from './forcedNeed.js';

afterEach(() => {
  vi.unstubAllGlobals();
  resetLanguageNames();
});

const row = (over = {}) => ({
  filmId: 1,
  title: 'Taken',
  year: 2008,
  need: 'NEEDED',
  reason: 'OTHER_LANGUAGES',
  ownerChoice: null,
  originalLanguage: 'en',
  spokenLanguages: ['en', 'fr', 'ru'],
  hasForcedSubtitle: false,
  ...over,
});

const ROWS = [
  row({ filmId: 1 }),
  row({ filmId: 2, hasForcedSubtitle: true }),
  row({ filmId: 3, need: 'UNSURE' }),
  row({ filmId: 4, need: 'UNKNOWN' }),
  row({ filmId: 5, need: 'NOT_NEEDED' }),
  row({ filmId: 6, need: 'NOT_NEEDED' }),
];

describe('applyRow', () => {
  it('replaces the film’s row in place and leaves the others and their order alone', () => {
    const answer = row({ filmId: 3, need: 'NEEDED', ownerChoice: 'NEEDED' });
    const result = applyRow(ROWS, answer);
    expect(result.map((r) => r.filmId)).toEqual([1, 2, 3, 4, 5, 6]);
    expect(result[2]).toBe(answer);
    expect(result[0]).toBe(ROWS[0]);
  });

  it('ignores an answer for a film that is not listed', () => {
    expect(applyRow(ROWS, row({ filmId: 99 }))).toEqual(ROWS);
  });
});

describe('wording', () => {
  it.each([
    ['SIDECAR', 'Forced subtitle file attached'],
    ['EMBEDDED_FORCED', "Forced track inside the file (pictures: can't be shown yet)"],
    ['FOREIGN_LANGUAGE_FILM', 'Not in English: full subtitles cover it'],
    ['NO_FORCED_ONLINE', 'No forced subtitles exist online'],
    ['ENGLISH_ONLY', 'English only (TMDB)'],
    ['OTHER_LANGUAGES', 'Also spoken: French, Russian (TMDB)'],
    ['NO_SPOKEN_LANGUAGES', 'TMDB lists no spoken languages'],
    ['NO_LANGUAGES', "TMDB hasn't been asked yet"],
    ['OWNER', 'Your choice'],
    ['OPENSUBTITLES', 'OpenSubtitles has foreign-parts-only subtitles'],
    ['SOMETHING_NEW', ''],
  ])('words the reason %s', (reason, text) => {
    expect(reasonText(row({ reason }))).toBe(text);
  });

  it('leaves English out of the also-spoken languages, whatever its case', () => {
    expect(reasonText(row({ spokenLanguages: ['EN', 'de'] }))).toBe('Also spoken: German (TMDB)');
  });

  it('copes with an also-spoken film that lists no other language', () => {
    expect(reasonText(row({ spokenLanguages: ['en'] }))).toBe(
      'Also spoken: other languages (TMDB)',
    );
    expect(reasonText(row({ spokenLanguages: null }))).toBe('Also spoken: other languages (TMDB)');
  });
});

describe('languageName', () => {
  it('names a language in English', () => {
    expect(languageName('fr')).toBe('French');
  });

  it('falls back to the code when the runtime returns nothing', () => {
    vi.stubGlobal('Intl', {
      ...Intl,
      DisplayNames: class {
        of() {
          return undefined;
        }
      },
    });
    expect(languageName('xx')).toBe('xx');
  });

  it('falls back to the code when the runtime throws', () => {
    vi.stubGlobal('Intl', {
      ...Intl,
      DisplayNames: class {
        constructor() {
          throw new RangeError('unsupported');
        }
      },
    });
    expect(languageName('fr')).toBe('fr');
  });
});

describe('needProblem', () => {
  it('says a scan is running for a 409', () => {
    expect(needProblem(new ApiError(409))).toBe('A scan is running: try again when it finishes');
  });

  it('shows the server’s own words for a 409 when it sent some, and the scan line when it did not', () => {
    expect(
      needProblem(
        new ApiError(409, undefined, {
          error: 'Subtitles are being changed or checked; try again in a moment',
        }),
      ),
    ).toBe('Subtitles are being changed or checked; try again in a moment.');
    expect(needProblem(new ApiError(409, undefined, { error: '  ' }))).toBe(
      'A scan is running: try again when it finishes',
    );
  });

  it('keeps its own guidance for the server’s bare scan line', () => {
    expect(needProblem(new ApiError(409, undefined, { error: 'A scan is running' }))).toBe(
      'A scan is running: try again when it finishes',
    );
  });

  it.each([new ApiError(500), new ApiError(404), new TypeError('offline')])(
    'says it could not save for any other failure (%s)',
    (failure) => {
      expect(needProblem(failure)).toBe("Couldn't save: try again");
    },
  );
});

describe('OpenSubtitles evidence', () => {
  it('counts the foreign-parts-only files in the reason, singular and plural', () => {
    const os = (n) => row({ reason: 'OPENSUBTITLES', openSubtitlesForcedCount: n });
    expect(reasonText(os(7))).toBe('OpenSubtitles has 7 foreign-parts-only files');
    expect(reasonText(os(1))).toBe('OpenSubtitles has 1 foreign-parts-only file');
  });

  it('falls back to the plain reason when the count is not known or is none', () => {
    const plain = 'OpenSubtitles has foreign-parts-only subtitles';
    expect(reasonText(row({ reason: 'OPENSUBTITLES' }))).toBe(plain);
    expect(reasonText(row({ reason: 'OPENSUBTITLES', openSubtitlesForcedCount: 0 }))).toBe(plain);
  });
});
