import { describe, expect, it } from 'vitest';
import {
  EMPTY_NONE_TEXT,
  FAILED_CHANGE_TEXT,
  INVERSE_TOAST,
  SCAN_RUNNING_TEXT,
  chipFor,
  cutIndex,
  cutText,
  emptyKind,
  emptyPendingText,
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
  titleLine,
  toastText,
  weekText,
} from './christmas.js';

const week = { start: '2026-12-14', end: '2026-12-20' };
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
  it('writes the week as Mon 14 Dec to Sun 20 Dec', () => {
    expect(weekText(week)).toBe('Mon 14 Dec to Sun 20 Dec');
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

  it('writes the cut line', () => {
    expect(cutText(12, 2)).toBe(
      "Home shows 12. These 2 are in, but won't fit. Leave out an older film to make room.",
    );
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

  it('writes Title · year, leaving off a missing year', () => {
    expect(titleLine(keyword)).toBe('A · 1990');
    expect(titleLine(added)).toBe('B');
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
      rest: ' will show in Christmas week (Mon 14 Dec to Sun 20 Dec).',
      hint: 'Home shows up to 12, oldest first. Under 5 and it falls back to the usual weekly theme.',
    });
  });

  it('says 1 film', () => {
    expect(statusText(body({ inWeek: films(1) })).bold).toBe('1 film');
  });

  it('explains the fall back under 5 and how many more are needed', () => {
    expect(statusText(body({ inWeek: films(3), fallsBack: true }))).toEqual({
      bold: '3 films',
      rest: ' are in, which is under 5, so Christmas week shows the usual weekly theme instead.',
      hint: 'Home needs at least 5 Christmas films to show them.',
    });
    const one = statusText(body({ inWeek: films(1), fallsBack: true }));
    expect(one.rest).toMatch(/^ is in, /);
    expect(one.hint).toBe('Home needs at least 5 Christmas films to show them.');
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

  it('writes the strip and the empty copy', () => {
    const kw = { fetched: 1374, total: 1412 };
    expect(keywordLine(kw)).toBe(
      'Keywords fetched for 1,374 of 1,412 films. The other 38 are on their way, so this list may still grow.',
    );
    expect(emptyPendingText({ fetched: 212, total: 1412 })).toBe(
      "CineFilm finds them from TMDB keywords, and it's still fetching those: 212 of 1,412 films so far. Films appear here as their keywords arrive, usually within the hour.",
    );
    expect(EMPTY_NONE_TEXT).toBe('No films in your library are tagged Christmas.');
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
