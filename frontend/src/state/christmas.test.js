import { describe, expect, it } from 'vitest';
import {
  EMPTY_NONE_TEXT,
  EMPTY_PENDING_ADD_TEXT,
  RULE_TEXT,
  addResultRow,
  resultState,
  resultWrite,
  FAILED_CHANGE_TEXT,
  INVERSE_TOAST,
  SCAN_RUNNING_TEXT,
  chipFor,
  cutIndex,
  cutText,
  ownerFillsRow,
  emptyKind,
  emptyPendingText,
  PREVIEW_CAPTION,
  PREVIEW_LABEL,
  PREVIEW_TITLE,
  fallbackText,
  previewRow,
  failureText,
  filmsText,
  keywordLine,
  keywordPercent,
  keywordsPending,
  meterCaption,
  meterSegments,
  rowAction,
  rowKind,
  rowNote,
  shortDate,
  statusText,
  tabCounts,
  tabRows,
  yearSuffix,
  keywordsStalled,
  KEYWORDS_NOT_FETCHING_TEXT,
  toastText,
  weekText,
} from './christmas.js';

const week = { start: '2026-12-14', end: '2026-12-25' };
const films = (n) =>
  Array.from({ length: n }, (_, i) => ({ tmdbId: i, title: `F${i}`, reason: 'KEYWORD' }));
const body = (over = {}) => ({
  week,
  limits: { max: 12, min: 5 },
  keywords: { fetched: 10, total: 10, running: false },
  inWeek: films(14),
  added: [],
  leftOut: [],
  fallsBack: false,
  ...over,
});

describe('week and counts wording', () => {
  it('writes the week as Mon 14 Dec to Fri 25 Dec', () => {
    expect(weekText(week)).toBe('Mon 14 Dec to Fri 25 Dec');
  });

  it('reads an ISO date as a calendar day across a year end', () => {
    expect(shortDate('2027-01-01')).toBe('Fri 1 Jan');
    expect(weekText({ start: '2027-12-13', end: '2027-12-19' })).toBe('Mon 13 Dec to Sun 19 Dec');
  });

  it('says 1 film, 0 films, n films and groups thousands', () => {
    expect(filmsText(1)).toBe('1 film');
    expect(filmsText(0)).toBe('0 films');
    expect(filmsText(14)).toBe('14 films');
    expect(filmsText(1374)).toBe('1,374 films');
  });
});

describe('tabs and the cut', () => {
  it('counts each list and returns its rows', () => {
    const data = body({ inWeek: films(3), added: films(1), leftOut: films(2) });
    expect(tabCounts(data)).toEqual({ inWeek: 3, added: 1, leftOut: 2 });
    expect(tabRows(data, 'leftOut')).toBe(data.leftOut);
  });

  it.each([
    [11, 11],
    [12, 12],
    [13, 12],
    [14, 12],
    [0, 0],
  ])('with %i rows the cut is after %i', (n, expected) => {
    expect(cutIndex(films(n), 12)).toBe(expected);
  });

  it('says "This 1" for one film over the limit and "These n" for more', () => {
    expect(cutText(12, 1)).toBe(
      "Home shows 12 a day. This 1 isn't in today's dozen; it comes up on other days.",
    );
    expect(cutText(12, 1, true)).toBe("Home shows 12 a day. This 1 isn't in today's dozen.");
  });

  it('writes the cut line', () => {
    expect(cutText(12, 2)).toBe(
      "Home shows 12 a day. These 2 aren't in today's dozen; they come up on other days.",
    );
  });

  it('warns that the rest never show when the owner’s own films fill the row', () => {
    expect(cutText(12, 2, false, true)).toBe(
      'Home shows 12. These 2 will never show: your own films fill the 12.',
    );
    expect(cutText(12, 1, true, true)).toBe('Home shows 12. This 1 will never show.');
  });

  it('knows when the owner’s INCLUDE films alone fill the row', () => {
    const rows = (included) =>
      Array.from({ length: 14 }, (_, i) => ({
        filmId: i,
        override: i < included ? 'INCLUDE' : null,
      }));
    expect(ownerFillsRow(rows(11), 12)).toBe(false);
    expect(ownerFillsRow(rows(12), 12)).toBe(true);
    expect(ownerFillsRow(rows(14), 12)).toBe(true);
    expect(ownerFillsRow(rows(0), 12)).toBe(false);
  });
});

describe('rows', () => {
  const keyword = { reason: 'KEYWORD', title: 'A', year: 1990 };
  const added = { reason: 'ADDED', title: 'B', year: null };
  const left = { title: 'C', year: 2001, taggedByTmdb: true };

  it('knows a row by its reason', () => {
    expect([keyword, added, left].map(rowKind)).toEqual(['keyword', 'added', 'leftOut']);
  });

  it('gives each kind its one action, choice and undo choice', () => {
    expect(rowAction(keyword)).toMatchObject({
      label: 'Leave out',
      choice: 'EXCLUDE',
      previous: null,
      toast: 'leave',
    });
    expect(rowAction(added)).toMatchObject({
      label: 'Remove',
      choice: null,
      previous: 'INCLUDE',
      toast: 'remove',
    });
    expect(rowAction(left)).toMatchObject({
      label: 'Put back',
      choice: null,
      previous: 'EXCLUDE',
      toast: 'putback',
    });
  });

  it('undoes a Leave out to INCLUDE when another film sharing the TMDB id was included by the owner', () => {
    const tagged = { reason: 'KEYWORD', tmdbId: 104, title: 'A' };
    const untagged = { reason: 'ADDED', tmdbId: 104, title: 'B' };
    expect(rowAction(tagged, [untagged])).toMatchObject({
      label: 'Leave out',
      choice: 'EXCLUDE',
      previous: 'INCLUDE',
    });
    // A different TMDB id in Added, or none, leaves the previous choice empty.
    expect(rowAction(tagged, [{ ...untagged, tmdbId: 105 }]).previous).toBeNull();
    expect(rowAction(tagged, []).previous).toBeNull();
    // Remove and Put back do not depend on the Added list.
    expect(rowAction(untagged, [untagged]).previous).toBe('INCLUDE');
    expect(rowAction({ title: 'C', tmdbId: 104 }, [untagged]).previous).toBe('EXCLUDE');
  });

  it('undoes a Leave out to INCLUDE when the keyword row carries a stored INCLUDE override, else to null', () => {
    const hidden = { reason: 'KEYWORD', tmdbId: 7, title: 'A', override: 'INCLUDE' };
    expect(rowAction(hidden, [])).toMatchObject({ choice: 'EXCLUDE', previous: 'INCLUDE' });
    expect(rowAction({ ...hidden, override: null }, []).previous).toBeNull();
    expect(resultWrite('IN_KEYWORD', 7, [], 'INCLUDE').previous).toBe('INCLUDE');
    expect(resultWrite('IN_KEYWORD', 7, []).previous).toBeNull();
  });

  it('chips each kind', () => {
    expect(chipFor(keyword)).toEqual({ label: 'TMDB keyword', variant: 'keyword' });
    expect(chipFor(added)).toEqual({ label: 'Added by me', variant: 'added' });
    expect(chipFor(left)).toEqual({ label: 'Left out by me', variant: 'left-out' });
  });

  it('adds the extra lines only in the Added and Left out tabs', () => {
    expect(rowNote(keyword, 'inWeek')).toBeNull();
    expect(rowNote(added, 'added')).toBe(
      "TMDB doesn't tag it, so it's only in because you added it.",
    );
    expect(rowNote(left, 'leftOut')).toBe('TMDB tags it “christmas”. Put back and it shows again.');
    expect(rowNote({ ...left, taggedByTmdb: false }, 'leftOut')).toBe(
      "TMDB doesn't tag it, so Put back takes it off this screen.",
    );
  });

  it('writes " · year", leaving off a missing year', () => {
    expect(yearSuffix(keyword)).toBe(' · 1990');
    expect(yearSuffix(added)).toBe('');
  });
});

describe('toasts and failures', () => {
  it('words each change', () => {
    expect(toastText('leave', 'Die Hard')).toBe("Left out Die Hard. It won't show at Christmas.");
    expect(toastText('putback', 'Die Hard')).toBe("Put back Die Hard. It'll show at Christmas.");
    expect(toastText('add', 'Die Hard')).toBe('Added Die Hard.');
    expect(toastText('remove', 'Die Hard')).toBe('Removed Die Hard.');
  });

  it('pairs each toast kind with its opposite', () => {
    expect(INVERSE_TOAST).toEqual({
      leave: 'putback',
      putback: 'leave',
      add: 'remove',
      remove: 'add',
    });
  });

  it('prefers the server words, then the scan text for a 409, then a plain failure', () => {
    expect(failureText(409, 'Held by a scan')).toBe('Held by a scan');
    expect(failureText(409, null)).toBe(SCAN_RUNNING_TEXT);
    expect(failureText(500, null)).toBe(FAILED_CHANGE_TEXT);
    expect(failureText(0, null)).toBe(FAILED_CHANGE_TEXT);
  });
});

describe('status card', () => {
  it('says how many films show and when', () => {
    expect(statusText(body())).toEqual({
      bold: '14 films',
      rest: ' will show in Christmas week (Mon 14 Dec to Fri 25 Dec).',
      hint: 'Home shows up to 12: films you added first, then a different mix each day. Under 5 and it falls back to the usual weekly theme.',
    });
  });

  it('says 1 film', () => {
    expect(statusText(body({ inWeek: films(1) })).bold).toBe('1 film');
  });

  it('explains the fall back under 5 and how many more are needed', () => {
    expect(statusText(body({ inWeek: films(3), fallsBack: true }))).toEqual({
      bold: '3 films',
      rest: ' are in, which is under 5, so Christmas week shows the usual weekly theme instead.',
      hint: 'Add 2 more and Home shows Christmas films.',
    });
    const one = statusText(body({ inWeek: films(1), fallsBack: true }));
    expect(one.rest).toMatch(/^ is in, /);
    expect(one.hint).toBe('Add 4 more and Home shows Christmas films.');
  });
});

describe('meter', () => {
  it('fills a slot per film and leaves the rest empty', () => {
    const segments = meterSegments(5, 12);
    expect(segments).toHaveLength(12);
    expect(segments.filter((s) => s === 'filled')).toHaveLength(5);
    expect(segments.filter((s) => s === 'empty')).toHaveLength(7);
  });

  it('is exactly full at 12, with no hollow segment', () => {
    expect(meterSegments(12, 12).every((s) => s === 'filled')).toBe(true);
    expect(meterSegments(12, 12)).toHaveLength(12);
  });

  it('adds one hollow segment per film over 12', () => {
    const segments = meterSegments(14, 12);
    expect(segments).toHaveLength(14);
    expect(segments.slice(12)).toEqual(['over', 'over']);
    expect(segments.slice(0, 12).every((s) => s === 'filled')).toBe(true);
  });

  it('captions the places and, only when over, how many', () => {
    expect(meterCaption(14, 12)).toEqual({ places: '12 places on Home', over: '2 over' });
    expect(meterCaption(12, 12).over).toBeNull();
  });
});

describe('keywords', () => {
  it('is pending only while fetched is below total', () => {
    expect(keywordsPending({ fetched: 9, total: 10 })).toBe(true);
    expect(keywordsPending({ fetched: 10, total: 10 })).toBe(false);
  });

  it('rounds the percent and guards an empty library', () => {
    expect(keywordPercent({ fetched: 212, total: 1412 })).toBe(15);
    expect(keywordPercent({ fetched: 0, total: 0 })).toBe(0);
    expect(keywordPercent({ fetched: 5, total: 4 })).toBe(100);
  });

  it('says "The other 1 is" for one film left to fetch', () => {
    expect(keywordLine({ fetched: 1411, total: 1412 })).toBe(
      'Keywords fetched for 1,411 of 1,412 films. The other 1 is on its way, so this list may still grow.',
    );
  });

  it('is stalled only when the server says so; absent fields change nothing', () => {
    expect(keywordsStalled({ fetched: 1, total: 2 })).toBe(false);
    expect(keywordsStalled({ stalled: false, enabled: true })).toBe(false);
    expect(keywordsStalled({ stalled: true })).toBe(true);
    expect(keywordsStalled({ enabled: false })).toBe(true);
    expect(emptyPendingText({ fetched: 2, total: 9, stalled: true })).toBe(
      `CineFilm finds them from TMDB keywords. ${KEYWORDS_NOT_FETCHING_TEXT} 2 of 9 films have them so far.`,
    );
  });

  it('writes the strip and the empty copy', () => {
    const kw = { fetched: 1374, total: 1412 };
    expect(keywordLine(kw)).toBe(
      'Keywords fetched for 1,374 of 1,412 films. The other 38 are on their way, so this list may still grow.',
    );
    expect(emptyPendingText({ fetched: 212, total: 1412 })).toBe(
      "CineFilm finds them from TMDB keywords, and it's still fetching those: 212 of 1,412 films so far. Films appear here as their keywords arrive, usually within the hour.",
    );
    expect(EMPTY_NONE_TEXT).toBe(
      'No films in your library are tagged Christmas. Add any you count.',
    );
  });

  it('picks the empty variant only when every list is empty', () => {
    const none = { inWeek: [], added: [], leftOut: [] };
    expect(emptyKind(body({ ...none, keywords: { fetched: 1, total: 2 } }))).toBe('pending');
    expect(emptyKind(body({ ...none, keywords: { fetched: 2, total: 2 } }))).toBe('none');
    expect(emptyKind(body({ ...none, leftOut: films(1) }))).toBeNull();
    expect(emptyKind(body({ ...none, inWeek: films(1) }))).toBeNull();
    expect(emptyKind(body({ ...none, added: films(1) }))).toBeNull();
  });
});

describe('add a film: search result rows', () => {
  it('shows each state as its chip or muted line and one action', () => {
    expect(addResultRow('IN_KEYWORD')).toEqual({
      chip: { label: 'TMDB keyword', variant: 'keyword' },
      line: null,
      action: { label: 'Leave out', fix: false },
    });
    expect(addResultRow('IN_ADDED')).toMatchObject({
      chip: { label: 'Added by me', variant: 'added' },
      action: { label: 'Remove', fix: false },
    });
    expect(addResultRow('LEFT_OUT')).toMatchObject({
      chip: { label: 'Left out by me', variant: 'left-out' },
      action: { label: 'Put back' },
    });
    expect(addResultRow('NOT_IN')).toEqual({
      chip: null,
      line: 'Not a Christmas film',
      action: { label: 'Add', fix: false },
    });
    expect(addResultRow('KEYWORDS_PENDING')).toMatchObject({
      chip: { label: 'Keywords not fetched yet', variant: 'pending' },
      action: { label: 'Add' },
    });
    expect(addResultRow('NO_TMDB_ID')).toEqual({
      chip: null,
      line: 'Needs a match first',
      action: { label: 'Find the film…', fix: true },
    });
  });

  it('has no row for a state not known yet', () => {
    expect(addResultRow(null)).toBeNull();
    expect(addResultRow('SOMETHING_NEW')).toBeNull();
  });

  it('keeps the copy the design asks for', () => {
    expect(RULE_TEXT).toMatch(/You can also add any film yourself\.$/);
    expect(EMPTY_PENDING_ADD_TEXT).toBe("You don't have to wait: add any film yourself.");
  });
});

describe('add a film: a result state', () => {
  const data = {
    inWeek: [
      { filmId: 1, tmdbId: 11, reason: 'KEYWORD' },
      { filmId: 2, tmdbId: 12, reason: 'ADDED' },
    ],
    added: [{ filmId: 2, tmdbId: 12 }],
    leftOut: [{ filmId: 3, tmdbId: 13 }],
  };

  it('reads the screen lists first, whatever an older answer said', () => {
    expect(resultState(1, data, { state: 'NOT_IN', issuedAt: 5 }, 1)).toBe('IN_KEYWORD');
    expect(resultState(2, data, { state: 'NOT_IN', issuedAt: 5 }, 1)).toBe('IN_ADDED');
    expect(resultState(3, data, { state: 'IN_KEYWORD', issuedAt: 5 }, 1)).toBe('LEFT_OUT');
    expect(resultState(1, data, undefined, 1)).toBe('IN_KEYWORD');
  });

  it('uses the answer for a film no list holds', () => {
    expect(resultState(9, data, { state: 'KEYWORDS_PENDING', issuedAt: 1 }, 4)).toBe(
      'KEYWORDS_PENDING',
    );
    expect(resultState(9, data, { state: 'NO_TMDB_ID', issuedAt: 1 }, 4)).toBe('NO_TMDB_ID');
    expect(resultState(9, data, { state: 'NOT_IN', issuedAt: 1 }, 4)).toBe('NOT_IN');
  });

  it('says NOT_IN for a film an older answer put in a list once the data is newer than the question', () => {
    for (const state of ['IN_ADDED', 'LEFT_OUT', 'IN_KEYWORD']) {
      expect(resultState(9, data, { state, issuedAt: 3 }, 4)).toBe('NOT_IN');
    }
  });

  it('believes an answer that is not older than the data: the list has not caught up yet', () => {
    // A keyword poll or the read after Fix match has not landed: the answer knows more than the data does.
    expect(resultState(9, data, { state: 'IN_KEYWORD', issuedAt: 4 }, 4)).toBe('IN_KEYWORD');
    expect(resultState(9, data, { state: 'LEFT_OUT', issuedAt: 4 }, 4)).toBe('LEFT_OUT');
  });

  it('is unknown with no answer and no list', () => {
    expect(resultState(9, data, undefined, 1)).toBeNull();
  });

  it('writes the choice each action sends, with the choice Undo sends back', () => {
    expect(resultWrite('NOT_IN', 5, [])).toEqual({
      choice: 'INCLUDE',
      previous: null,
      toast: 'add',
    });
    expect(resultWrite('KEYWORDS_PENDING', 5, [])).toMatchObject({ choice: 'INCLUDE' });
    expect(resultWrite('IN_ADDED', 5, [])).toMatchObject({ choice: null, previous: 'INCLUDE' });
    expect(resultWrite('LEFT_OUT', 5, [])).toMatchObject({ choice: null, previous: 'EXCLUDE' });
    expect(resultWrite('IN_KEYWORD', 5, [])).toMatchObject({ choice: 'EXCLUDE', previous: null });
    // A TMDB id shared with a film the owner included: Undo must restore that INCLUDE.
    expect(resultWrite('IN_KEYWORD', 5, [{ tmdbId: 5 }])).toMatchObject({ previous: 'INCLUDE' });
    expect(resultWrite('NO_TMDB_ID', null, [])).toBeNull();
  });
});

describe('the Home preview', () => {
  const data = (extra = {}) => ({
    limits: { max: 12, min: 5 },
    fallsBack: false,
    inWeek: Array.from({ length: 14 }, (_, i) => ({
      filmId: i + 1,
      tmdbId: 100 + i,
      title: `Film ${i + 1}`,
      year: 1980 + i,
      posterUrl: `/p${i + 1}.jpg`,
    })),
    ...extra,
  });

  it('says what Home says', () => {
    expect(PREVIEW_TITLE).toBe('This week: Christmas');
    expect(PREVIEW_CAPTION).toBe('A new theme each week');
    expect(PREVIEW_LABEL).toBe('Preview · Home in Christmas week');
    expect(fallbackText(5)).toBe(
      'Christmas week will show the usual weekly theme — under 5 Christmas films.',
    );
  });

  it('holds the first 12 films in the shape of Home’s poster', () => {
    const row = previewRow(data());
    expect(row.fallsBack).toBe(false);
    expect(row.films).toHaveLength(12);
    expect(row.films[0]).toEqual({ id: 1, title: 'Film 1', year: 1980, posterPath: '/p1.jpg' });
    expect(row.films[11].id).toBe(12);
  });

  it('holds no films when the week falls back', () => {
    expect(previewRow(data({ fallsBack: true }))).toEqual({ fallsBack: true, min: 5, films: [] });
  });

  it('writes the phone’s short cut line', () => {
    expect(cutText(12, 2, true)).toBe("Home shows 12 a day. These 2 aren't in today's dozen.");
  });
});
