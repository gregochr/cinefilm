import { describe, expect, it } from 'vitest';
import {
  SUBTITLE_TABS,
  attachTarget,
  everythingElse,
  fileChoiceNote,
  fileChoices,
  filmTitle,
  foreignActions,
  foreignLine,
  forcedRowLine,
  isUnlabelled,
  tabAfterKey,
  unresolvedRows,
} from './adminSubtitles.js';

const full = (over = {}) => ({
  filmId: 6,
  title: 'Rashomon',
  year: 1950,
  need: 'NEEDED',
  reason: 'FOREIGN_AUDIO',
  originalLanguage: 'ja',
  fullSubtitle: null,
  unresolved: true,
  beingChecked: false,
  ...over,
});

describe('tabAfterKey', () => {
  it('steps with the arrows and wraps at both ends', () => {
    expect(tabAfterKey('foreign', 'ArrowRight')).toBe('forced');
    expect(tabAfterKey('forced', 'ArrowRight')).toBe('foreign');
    expect(tabAfterKey('forced', 'ArrowLeft')).toBe('foreign');
    expect(tabAfterKey('foreign', 'ArrowLeft')).toBe('forced');
  });

  it('jumps with Home and End, and ignores every other key', () => {
    expect(tabAfterKey('forced', 'Home')).toBe('foreign');
    expect(tabAfterKey('foreign', 'End')).toBe('forced');
    expect(tabAfterKey('foreign', 'Enter')).toBeNull();
    expect(SUBTITLE_TABS).toEqual(['foreign', 'forced']);
  });
});

describe('unresolvedRows', () => {
  it('keeps the rows the server flagged, in its order, and is null before the list is read', () => {
    const rows = [full({ filmId: 1 }), full({ filmId: 2, unresolved: false }), full({ filmId: 3 })];
    expect(unresolvedRows(rows).map((r) => r.filmId)).toEqual([1, 3]);
    expect(unresolvedRows([])).toEqual([]);
    expect(unresolvedRows(null)).toBeNull();
  });
});

describe('foreignLine', () => {
  it('names the language and says nothing was found', () => {
    expect(foreignLine(full())).toBe(
      'Spoken: Japanese · none found automatically on OpenSubtitles',
    );
  });

  it('says what plays instead of "none found" when a subtitle already plays', () => {
    expect(foreignLine(full({ fullSubtitle: { source: 'DOWNLOAD' } }))).toBe(
      'Spoken: Japanese · Playing: download (not checked yet)',
    );
  });

  it('says so when the owner called the film needed', () => {
    expect(foreignLine(full({ reason: 'OWNER' }))).toBe(
      'Spoken: Japanese · Set by you · none found automatically on OpenSubtitles',
    );
  });

  it('says the language is not known when TMDB gave none', () => {
    expect(foreignLine(full({ originalLanguage: null }))).toBe(
      'Language not known yet · none found automatically on OpenSubtitles',
    );
  });

  it('words unlabelled audio with the language that was assumed', () => {
    const row = full({ reason: 'ORIGINAL_AUDIO_ASSUMED', originalLanguage: 'de' });
    expect(foreignLine(row)).toBe(
      'Audio isn’t labelled · CineFilm assumed German, the original language TMDB lists',
    );
    expect(foreignLine({ ...row, originalLanguage: null })).toBe(
      'Audio isn’t labelled · CineFilm assumed the original language',
    );
  });
});

describe('forcedRowLine', () => {
  it("is the server's reason in words", () => {
    expect(forcedRowLine({ reason: 'OPENSUBTITLES', openSubtitlesForcedCount: 2 })).toBe(
      'OpenSubtitles has 2 foreign-parts-only files',
    );
  });
});

describe('foreignActions', () => {
  it('offers "Add my own file…" alone, and "It’s English" too for unlabelled audio', () => {
    expect(isUnlabelled(full())).toBe(false);
    expect(foreignActions(full())).toEqual(['addFile']);
    expect(isUnlabelled(full({ reason: 'ORIGINAL_AUDIO_ASSUMED' }))).toBe(true);
    expect(foreignActions(full({ reason: 'ORIGINAL_AUDIO_ASSUMED' }))).toEqual([
      'addFile',
      'itsEnglish',
    ]);
  });
});

describe('everythingElse', () => {
  const rows = [
    full({ filmId: 1, fullSubtitle: { source: 'SIDECAR' }, unresolved: false }),
    full({ filmId: 2, fullSubtitle: { source: 'DOWNLOAD' }, unresolved: false }),
    full({ filmId: 3, need: 'NOT_NEEDED', fullSubtitle: { source: 'SIDECAR' }, unresolved: false }),
    full({ filmId: 4 }),
  ];

  it('counts the foreign films whose English subtitles play, the other library films and the checks', () => {
    const figures = everythingElse({
      fullRows: rows,
      fullCounts: { beingChecked: 1 },
      forcedCounts: { beingChecked: 2 },
      libraryFilms: 10,
    });
    expect(figures.haveSubtitles).toBe(2);
    expect(figures.films.map((r) => r.filmId)).toEqual([1, 2]);
    expect(figures.inEnglish).toBe(6);
    expect(figures.beingChecked).toBe(3);
  });

  it('is null for each figure until what it needs has been read', () => {
    expect(
      everythingElse({ fullRows: null, fullCounts: null, forcedCounts: null, libraryFilms: null }),
    ).toEqual({ films: null, haveSubtitles: null, inEnglish: null, beingChecked: null });
    expect(
      everythingElse({ fullRows: rows, fullCounts: null, forcedCounts: null, libraryFilms: null })
        .inEnglish,
    ).toBeNull();
  });

  it('counts the checks of the one list that has been read, and never goes below zero', () => {
    const figures = everythingElse({
      fullRows: rows,
      fullCounts: null,
      forcedCounts: { beingChecked: 2 },
      libraryFilms: 1,
    });
    expect(figures.beingChecked).toBe(2);
    expect(figures.inEnglish).toBe(0);
  });

  it('treats a counts object without beingChecked as none', () => {
    expect(
      everythingElse({ fullRows: [], fullCounts: {}, forcedCounts: null, libraryFilms: 3 })
        .beingChecked,
    ).toBe(0);
  });
});

describe('filmTitle', () => {
  it('is the title with its year, or the title alone', () => {
    expect(filmTitle({ title: 'Rashomon', year: 1950 })).toBe('Rashomon (1950)');
    expect(filmTitle({ title: 'Rashomon', year: null })).toBe('Rashomon');
  });
});

describe('fileChoices', () => {
  const file = (over) => ({
    id: 1,
    kind: 'FULL',
    origin: 'FILE',
    presence: 'PRESENT',
    filmId: null,
    filmTitle: null,
    path: 'a.en.srt',
    ...over,
  });

  it("offers the owner's present full files not already on this film, unattached first", () => {
    const files = [
      file({ id: 1, filmId: 9, filmTitle: 'Other' }),
      file({ id: 2 }),
      file({ id: 3, filmId: 6 }),
      file({ id: 4, kind: 'FORCED' }),
      file({ id: 5, origin: 'OPENSUBTITLES' }),
      file({ id: 6, presence: 'MISSING' }),
    ];
    expect(fileChoices(files, 6).map((f) => f.id)).toEqual([2, 1]);
    expect(fileChoices(null, 6)).toEqual([]);
  });

  it('says where a file sits now', () => {
    expect(fileChoiceNote(file())).toBe('not attached to a film');
    expect(fileChoiceNote(file({ filmId: 9, filmTitle: 'Other' }))).toBe('on Other');
    expect(fileChoiceNote(file({ filmId: 9 }))).toBe('on another film');
  });
});

describe('attachTarget', () => {
  it('is null without an edition, and the edition with no part when it is one file', () => {
    expect(attachTarget(null, null)).toBeNull();
    expect(attachTarget({ id: 4, parts: 1 }, null)).toEqual({ editionId: 4, part: null });
  });

  it('takes the whole film or a part of a split edition, and nothing else', () => {
    const split = { id: 4, parts: 2 };
    expect(attachTarget(split, null)).toEqual({ editionId: 4, part: null });
    expect(attachTarget(split, 2)).toEqual({ editionId: 4, part: 2 });
    expect(attachTarget(split, 3)).toBeNull();
  });
});
