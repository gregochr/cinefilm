import { describe, expect, it } from 'vitest';
import {
  filterOf,
  hasNextPage,
  pageAfterRead,
  pagingText,
  panelTimeMs,
  rowLines,
  stillTimes,
} from './creditsAdmin.js';

const row = (over = {}) => ({
  runtimeMs: 7200000,
  markerMs: null,
  source: null,
  plex: { state: 'NONE', markerMs: null },
  ...over,
});

describe('rowLines', () => {
  it.each([
    ['PLEX', 'From Plex · 1:52:10'],
    ['OWNER', 'Yours · 1:52:10'],
    [null, 'Not settled yet · 1:52:10'],
    ['AUTO', 'Detected · 1:52:10'],
  ])(
    'with a marker from %s, line two names the source and the time and there is no line three',
    (source, two) => {
      expect(rowLines(row({ markerMs: 6730000, source }))).toEqual({ two, three: null });
    },
  );

  it.each([
    [{ state: 'NONE', markerMs: null }, 'Plex has no marker for this film'],
    [
      { state: 'NO_FINGERPRINT', markerMs: null },
      'This file hasn’t been fully scanned, so Plex’s marker can’t be matched to it',
    ],
    [{ state: 'STALE', markerMs: null }, 'Plex’s marker is for an older copy of this file'],
    [
      { state: 'USABLE', markerMs: 6730000 },
      'Plex’s marker at 1:52:10 is ready but not applied yet',
    ],
    [{ state: 'OUTSIDE', markerMs: null }, 'Plex’s marker is past the end of this file'],
    [
      { state: 'REFUSED', markerMs: 2467000 },
      'Plex’s marker at 41:07 is too early or too late to trust',
    ],
    [{ state: 'NO_TIMELINE', markerMs: null }, 'This film’s length isn’t known'],
  ])(
    'with no marker and Plex %j, line two is "No marker" and line three the reason',
    (plex, three) => {
      expect(rowLines(row({ plex }))).toEqual({
        two: 'No marker · the end screen shows at 97%',
        three,
      });
    },
  );
});

describe('panelTimeMs', () => {
  it('is the marker when there is one, over Plex’s refused time', () => {
    expect(
      panelTimeMs(row({ markerMs: 6720000, plex: { state: 'REFUSED', markerMs: 2467000 } })),
    ).toBe(6720000);
  });

  it('is Plex’s refused time when there is no marker', () => {
    expect(panelTimeMs(row({ plex: { state: 'REFUSED', markerMs: 2467000 } }))).toBe(2467000);
  });

  it('is 97% of the runtime when there is neither, ignoring a usable Plex time', () => {
    expect(panelTimeMs(row({ plex: { state: 'USABLE', markerMs: 6700000 } }))).toBe(6984000);
  });

  it('is null with no marker and no runtime', () => {
    expect(panelTimeMs(row({ runtimeMs: null }))).toBeNull();
  });
});

describe('stillTimes', () => {
  it('is 30 s earlier and the time', () => {
    expect(stillTimes(6720000)).toEqual([6690000, 6720000]);
  });

  it('floors the earlier one at 0', () => {
    expect(stillTimes(12000)).toEqual([0, 12000]);
  });
});

describe('paging', () => {
  it('words the first, a middle and the last page', () => {
    expect(pagingText(0, 25, 212)).toBe('Showing 1–25 of 212');
    expect(pagingText(1, 25, 212)).toBe('Showing 26–50 of 212');
    expect(pagingText(8, 25, 212)).toBe('Showing 201–212 of 212');
  });

  it('says nothing for an empty list', () => {
    expect(pagingText(0, 25, 0)).toBe('');
  });

  it('has a next page until the last row is shown', () => {
    expect(hasNextPage(0, 25, 26)).toBe(true);
    expect(hasNextPage(0, 25, 25)).toBe(false);
    expect(hasNextPage(8, 25, 212)).toBe(false);
  });

  it('steps back one when a page is empty but the filter still has films', () => {
    expect(pageAfterRead(2, 0, 50)).toBe(1);
  });

  it.each([
    [2, 3, 53],
    [0, 0, 0],
    [2, 0, 0],
    [0, 0, 5],
  ])('stays on page %i with %i rows of %i', (page, count, total) => {
    expect(pageAfterRead(page, count, total)).toBe(page);
  });
});

describe('filterOf', () => {
  it('takes the four filters and falls back to Needs a look', () => {
    expect(['none', 'plex', 'owner', 'all'].map(filterOf)).toEqual([
      'none',
      'plex',
      'owner',
      'all',
    ]);
    expect(filterOf('bogus')).toBe('none');
    expect(filterOf(null)).toBe('none');
  });
});
