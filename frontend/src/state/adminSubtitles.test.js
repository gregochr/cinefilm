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
  forcedActions,
  forcedRowLine,
  isUnlabelled,
  ownerChoices,
  pickerAction,
  pickerButton,
  pickerTarget,
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
  editionId: 102,
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
      'Spoken: Japanese · nothing found automatically on OpenSubtitles',
    );
  });

  it('says what plays instead of "none found" when a subtitle already plays', () => {
    expect(foreignLine(full({ fullSubtitle: { source: 'DOWNLOAD' } }))).toBe(
      'Spoken: Japanese · Playing: download (not checked yet)',
    );
  });

  it('says so when the owner called the film needed', () => {
    expect(foreignLine(full({ reason: 'OWNER' }))).toBe(
      'Spoken: Japanese · Set by you · nothing found automatically on OpenSubtitles',
    );
  });

  it('says the language is not known when TMDB gave none', () => {
    expect(foreignLine(full({ originalLanguage: null }))).toBe(
      'Language not known yet · nothing found automatically on OpenSubtitles',
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

describe('pickerAction', () => {
  it('is the plain search for a full row, and the forced search for a forced row', () => {
    expect(pickerAction(full(), 'full')).toBe('search');
    expect(pickerAction(full(), 'forced')).toBe('findForced');
  });

  it('names the language for unlabelled audio when the original language is known', () => {
    const unlabelled = full({ reason: 'ORIGINAL_AUDIO_ASSUMED', originalLanguage: 'de' });
    expect(pickerAction(unlabelled, 'full')).toBe('itsInLanguage');
    expect(pickerAction({ ...unlabelled, originalLanguage: null }, 'full')).toBe('search');
    expect(pickerAction({ ...unlabelled, originalLanguage: '' }, 'full')).toBe('search');
  });

  it('is null for a row with no edition to search, for either kind', () => {
    expect(pickerAction(full({ editionId: null }), 'full')).toBeNull();
    expect(pickerAction(full({ editionId: undefined }), 'forced')).toBeNull();
  });
});

describe('pickerButton', () => {
  it('words each action, with the board’s hint for the search and none for the others', () => {
    expect(pickerButton('search', full())).toEqual({
      label: 'Search for English subtitles…',
      hint: 'Shows OpenSubtitles results for this film so you can pick one',
    });
    expect(pickerButton('findForced', full())).toEqual({
      label: 'Find forced subtitles…',
      hint: undefined,
    });
    expect(pickerButton('itsInLanguage', full({ originalLanguage: 'de' }))).toEqual({
      label: 'It’s in German — find subtitles',
    });
  });
});

describe('pickerTarget', () => {
  it('carries the film for the title, the row’s edition and the kind', () => {
    expect(pickerTarget(full(), 'full')).toEqual({
      film: { id: 6, title: 'Rashomon', year: 1950 },
      editionId: 102,
      editionName: null,
      kind: 'full',
    });
  });

  it('carries the edition name the server sent for a film with several editions', () => {
    expect(pickerTarget(full({ editionName: 'Director’s Cut' }), 'full').editionName).toBe(
      'Director’s Cut',
    );
  });
});

describe('foreignActions', () => {
  it('offers the search then "Add my own file…", and "It’s English" too for unlabelled audio', () => {
    expect(isUnlabelled(full())).toBe(false);
    expect(foreignActions(full())).toEqual(['search', 'addFile']);
    expect(isUnlabelled(full({ reason: 'ORIGINAL_AUDIO_ASSUMED' }))).toBe(true);
    expect(foreignActions(full({ reason: 'ORIGINAL_AUDIO_ASSUMED' }))).toEqual([
      'itsInLanguage',
      'addFile',
      'itsEnglish',
    ]);
  });

  it('leaves the search out of a row with no edition', () => {
    expect(foreignActions(full({ editionId: null }))).toEqual(['addFile']);
    expect(foreignActions(full({ editionId: null, reason: 'ORIGINAL_AUDIO_ASSUMED' }))).toEqual([
      'addFile',
      'itsEnglish',
    ]);
  });
});

describe('forcedActions', () => {
  it('offers "Find forced subtitles…" then "No foreign scenes", or the second alone without an edition', () => {
    expect(forcedActions(full())).toEqual(['findForced', 'noForeignScenes']);
    expect(forcedActions(full({ editionId: null }))).toEqual(['noForeignScenes']);
  });
});

describe('everythingElse', () => {
  const rows = [
    full({ filmId: 1, fullSubtitle: { source: 'SIDECAR' }, unresolved: false, subtitled: true }),
    full({ filmId: 2, fullSubtitle: { source: 'DOWNLOAD' }, unresolved: false, subtitled: true }),
    // A hidden film that plays a file: the server does not flag it, so it is not listed.
    full({ filmId: 3, fullSubtitle: { source: 'SIDECAR' }, unresolved: false, subtitled: false }),
    full({ filmId: 4 }),
  ];
  const fullCounts = { beingChecked: 1, subtitled: 70, inEnglish: 1341 };
  const forcedCounts = { beingChecked: 2 };

  it('reads the two figures from the server’s counts, never from the rows, and adds the checks', () => {
    const figures = everythingElse({ fullRows: rows, fullCounts, forcedCounts });

    // The rows would say 2 and 4: the server's counts (browsable films, the whole library) win.
    expect(figures.haveSubtitles).toBe(70);
    expect(figures.inEnglish).toBe(1341);
    expect(figures.beingChecked).toBe(3);
    // The read-only list is the rows the server flagged.
    expect(figures.films.map((r) => r.filmId)).toEqual([1, 2]);
  });

  it('is null for each figure until what it needs has been read', () => {
    expect(everythingElse({ fullRows: null, fullCounts: null, forcedCounts: null })).toEqual({
      films: null,
      haveSubtitles: null,
      inEnglish: null,
      beingChecked: null,
    });
    const onlyFull = everythingElse({ fullRows: rows, fullCounts, forcedCounts: null });
    expect(onlyFull.haveSubtitles).toBe(70);
    expect(onlyFull.inEnglish).toBe(1341);
  });

  it('waits for both lists before it says how many are being checked', () => {
    expect(
      everythingElse({ fullRows: rows, fullCounts, forcedCounts: null }).beingChecked,
    ).toBeNull();
    expect(
      everythingElse({ fullRows: null, fullCounts: null, forcedCounts }).beingChecked,
    ).toBeNull();
  });

  it('shows zero as zero', () => {
    const figures = everythingElse({
      fullRows: [],
      fullCounts: { beingChecked: 0, subtitled: 0, inEnglish: 0 },
      forcedCounts: { beingChecked: 0 },
    });
    expect(figures).toMatchObject({ haveSubtitles: 0, inEnglish: 0, beingChecked: 0, films: [] });
  });
});

describe('ownerChoices', () => {
  it('lists the films the server says the owner chose for, full first, each list in its order', () => {
    const fullRows = [
      full({ filmId: 1, ownerChoice: 'NOT_NEEDED' }),
      full({ filmId: 2, ownerChoice: null }),
      full({ filmId: 3, ownerChoice: 'NEEDED' }),
    ];
    const forcedRows = [{ filmId: 9, title: 'Taken', ownerChoice: 'NOT_NEEDED' }, { filmId: 8 }];

    const choices = ownerChoices({ fullRows, forcedRows });

    expect(choices.map((c) => [c.kind, c.row.filmId])).toEqual([
      ['full', 1],
      ['full', 3],
      ['forced', 9],
    ]);
  });

  it('adds nothing for a list that has not been read', () => {
    expect(ownerChoices({ fullRows: null, forcedRows: null })).toEqual([]);
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
