import { describe, expect, it } from 'vitest';
import { ApiError } from '../api/client.js';
import {
  checkTime,
  filterOf,
  focusTargetAfter,
  hasNextPage,
  isPlausible,
  neighboursOf,
  pageAfterRead,
  pagingText,
  panelTimeMs,
  parseTime,
  plausibleRange,
  rowLines,
  stillTimes,
  writeFailure,
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
  ])(
    'with a marker from %s, line two names the source and the time and there is no line three',
    (source, two) => {
      expect(rowLines(row({ markerMs: 6730000, source }))).toEqual({ two, three: null });
    },
  );

  it('shows an automatic marker with no time and no line three', () => {
    expect(rowLines(row({ markerMs: 6730000, source: 'AUTO' }))).toEqual({
      two: 'Set automatically',
      three: null,
    });
  });

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

describe('panelTimeMs for an automatic marker', () => {
  it('ignores the automatic time and falls to Plex’s refused time', () => {
    expect(
      panelTimeMs(
        row({ markerMs: 6730000, source: 'AUTO', plex: { state: 'REFUSED', markerMs: 2467000 } }),
      ),
    ).toBe(2467000);
  });

  it('ignores the automatic time and falls to 97% of the runtime', () => {
    expect(panelTimeMs(row({ markerMs: 6730000, source: 'AUTO' }))).toBe(6984000);
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

describe('parseTime', () => {
  it.each([
    ['1:52:10', 6730000],
    ['52:10', 3130000],
    ['0:59', 59000],
    ['75:00', 4500000],
    ['  1:52:10 ', 6730000],
    ['0:00:00', 0],
  ])('reads %s as %i ms', (text, ms) => {
    expect(parseTime(text)).toBe(ms);
  });

  it.each([
    '1:60:00',
    '1:2:3:4',
    '',
    '-1:00',
    '-0:30',
    '1:52:1',
    '1:5:10',
    '52:60',
    'abc',
    '1:xx:10',
    '1:52:10.5',
  ])('refuses %j', (text) => {
    expect(parseTime(text)).toBeNull();
  });
});

describe('the guard range', () => {
  // 7,200,500 ms is not a whole second: 60% is 4,320,300 ms and 60 s before the end is 7,140,500 ms.
  const total = 7200500;

  it('matches the server: at least 60% in and at least 60 s before the end', () => {
    expect(isPlausible(4320300, total)).toBe(true);
    expect(isPlausible(4320299, total)).toBe(false);
    expect(isPlausible(7140500, total)).toBe(true);
    expect(isPlausible(7140501, total)).toBe(false);
  });

  it('gives the whole-second range, and a whole second either side of each end is refused or accepted', () => {
    expect(plausibleRange(total)).toEqual({ fromMs: 4321000, toMs: 7140000 });
    expect(isPlausible(4320000, total)).toBe(false);
    expect(isPlausible(4321000, total)).toBe(true);
    expect(isPlausible(7140000, total)).toBe(true);
    expect(isPlausible(7141000, total)).toBe(false);
  });

  it('gives no range for a film too short to have one', () => {
    expect(plausibleRange(90000)).toBeNull();
  });

  it('checks a typed time against it, in words', () => {
    expect(checkTime('1:12:01', total)).toEqual({ ms: 4321000 });
    expect(checkTime('1:12:00', total).error).toBe(
      'That time is outside where end credits can start (1:12:01 to 1:59:00). Pick a time inside it',
    );
    expect(checkTime('1:59:00', total)).toEqual({ ms: 7140000 });
    expect(checkTime('1:59:01', total).error).toMatch(/^That time is outside/);
    expect(checkTime('soon', total).error).toBe('Use h:mm:ss or m:ss, for example 1:52:10');
    expect(checkTime('1:30', 90000).error).toBe(
      'This film is too short to set an end credits time',
    );
  });
});

describe('writeFailure', () => {
  it('maps each status to where it shows', () => {
    expect(writeFailure(new ApiError(401, 'Unauthorised'), 'set')).toEqual({
      kind: 'signin',
      text: null,
    });
    expect(writeFailure(new ApiError(404, undefined, { error: 'No such edition' }), 'set')).toEqual(
      { kind: 'gone', text: 'This film isn’t in the library any more' },
    );
    expect(
      writeFailure(
        new ApiError(400, undefined, { error: 'That time is outside where end credits can start' }),
        'set',
      ),
    ).toEqual({ kind: 'field', text: 'That time is outside where end credits can start' });
    expect(writeFailure(new ApiError(400, undefined, null), 'set')).toEqual({
      kind: 'field',
      text: 'That time can’t be used',
    });
  });

  it('uses the lock holder the server names on a 409, ending in a full stop, else its own words', () => {
    expect(
      writeFailure(new ApiError(409, undefined, { error: 'A Plex import is running' }), 'set'),
    ).toEqual({
      kind: 'alert',
      text: 'A Plex import is running.',
    });
    expect(
      writeFailure(new ApiError(409, undefined, { error: 'A scan is running' }), 'clear'),
    ).toEqual({
      kind: 'alert',
      text: 'A scan or import is running. Try again when it finishes.',
    });
  });
});

describe('where focus goes after a write', () => {
  const items = (...ids) => ids.map((editionId) => ({ editionId }));

  it('finds the rows either side of a row', () => {
    expect(neighboursOf(items(1, 2, 3), 2)).toEqual({ after: 3, before: 1 });
    expect(neighboursOf(items(1, 2, 3), 1)).toEqual({ after: 2, before: null });
    expect(neighboursOf(items(1, 2, 3), 3)).toEqual({ after: null, before: 2 });
    expect(neighboursOf(items(1, 2, 3), 9)).toEqual({ after: null, before: null });
  });

  it('prefers the row itself, then the next row, then the previous row', () => {
    const near = { after: 3, before: 1 };
    expect(focusTargetAfter(2, near, items(1, 2, 3), false)).toBe(2);
    expect(focusTargetAfter(2, near, items(1, 3), false)).toBe(3);
    expect(focusTargetAfter(2, near, items(1, 4), false)).toBe(1);
    expect(focusTargetAfter(2, { after: null, before: 1 }, items(1), false)).toBe(1);
  });

  it('takes the last row of the new page after a step back, and none from an empty page', () => {
    expect(focusTargetAfter(2, { after: 3, before: 1 }, items(7, 8, 9), true)).toBe(9);
    expect(focusTargetAfter(2, { after: 3, before: 1 }, [], false)).toBeNull();
    expect(focusTargetAfter(2, { after: null, before: null }, items(5), false)).toBeNull();
  });
});
