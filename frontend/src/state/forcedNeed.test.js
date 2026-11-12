import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client.js';
import {
  DEFAULT_NEED_FILTER,
  NEED_FILTERS,
  OWNER_CHOICES,
  applyRow,
  filterRows,
  languageName,
  needCounts,
  needProblem,
  needTitle,
  needWords,
  reasonText,
  searchNote,
  resetLanguageNames,
  subtitleNote,
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

describe('filters', () => {
  it('start on Needed, none yet, then Unsure, Unknown, Not needed and All', () => {
    expect(NEED_FILTERS.map((f) => f.label)).toEqual([
      'Needed, none yet',
      'Unsure',
      'Unknown',
      'Not needed',
      'All',
    ]);
    expect(DEFAULT_NEED_FILTER).toBe('needed-missing');
  });

  it.each([
    ['needed-missing', [1]],
    ['unsure', [3]],
    ['unknown', [4]],
    ['not-needed', [5, 6]],
    ['all', [1, 2, 3, 4, 5, 6]],
    ['something-else', [1, 2, 3, 4, 5, 6]],
  ])('%s lists the films %j', (key, ids) => {
    expect(filterRows(ROWS, key).map((r) => r.filmId)).toEqual(ids);
  });

  it('counts each chip from the rows', () => {
    expect(needCounts(ROWS)).toEqual({
      'needed-missing': 1,
      unsure: 1,
      unknown: 1,
      'not-needed': 2,
      all: 6,
    });
  });

  it('recounts after a change: a film that now has a file leaves Needed, none yet', () => {
    const changed = applyRow(
      ROWS,
      row({ filmId: 1, need: 'NOT_NEEDED', ownerChoice: 'NOT_NEEDED' }),
    );
    expect(needCounts(changed)).toMatchObject({ 'needed-missing': 0, 'not-needed': 3, all: 6 });
  });
});

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
  it('words each need', () => {
    expect(['NEEDED', 'NOT_NEEDED', 'UNSURE', 'UNKNOWN', 'SURPRISE'].map(needWords)).toEqual([
      'Needed',
      'Not needed',
      'Unsure',
      'Unknown',
      'Unknown',
    ]);
  });

  it('offers Needed, Not needed and Automatic, which sends null', () => {
    expect(OWNER_CHOICES).toEqual([
      { value: 'NEEDED', label: 'Needed' },
      { value: 'NOT_NEEDED', label: 'Not needed' },
      { value: null, label: 'Automatic' },
    ]);
  });

  it('shows the title with the year when there is one', () => {
    expect(needTitle(row())).toBe('Taken (2008)');
    expect(needTitle(row({ year: null }))).toBe('Taken');
  });

  it('says whether a film that needs forced subtitles has one', () => {
    expect(subtitleNote(row({ hasForcedSubtitle: true }))).toBe('Plays');
    expect(subtitleNote(row())).toBe('None yet');
    expect(subtitleNote(row({ need: 'UNSURE' }))).toBeNull();
  });

  it.each([
    ['SIDECAR', 'Forced subtitle file attached'],
    ['EMBEDDED_FORCED', "Forced track inside the file (pictures: can't be shown yet)"],
    ['FOREIGN_LANGUAGE_FILM', 'Not in English originally: needs full subtitles'],
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

  it('notes "not searched yet" only for an unsure or unknown film that has not been searched', () => {
    const note = 'OpenSubtitles not searched yet';
    expect(searchNote(row({ need: 'UNSURE', openSubtitlesSearchedAt: null }))).toBe(note);
    expect(searchNote(row({ need: 'UNKNOWN', openSubtitlesSearchedAt: null }))).toBe(note);
    expect(searchNote(row({ need: 'UNKNOWN' }))).toBe(note);
    expect(
      searchNote(row({ need: 'UNSURE', openSubtitlesSearchedAt: '2026-11-12T06:00:00Z' })),
    ).toBeNull();
    expect(searchNote(row({ need: 'NEEDED', openSubtitlesSearchedAt: null }))).toBeNull();
    expect(searchNote(row({ need: 'NOT_NEEDED', openSubtitlesSearchedAt: null }))).toBeNull();
  });
});
