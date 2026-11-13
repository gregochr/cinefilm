import { describe, expect, it } from 'vitest';
import {
  checkSummary,
  fileFilterCounts,
  filterSidecars,
  groupKey,
  isFull,
} from './subtitleAdmin.js';

const check = {
  verdict: 'AGREES',
  reasons: [],
  bestOffsetMs: 0,
  matchedShare: 1,
  servedCount: 12,
  referenceCount: 12,
  referenceRelease: null,
  checkedAt: null,
};

const forced = (over = {}) => ({
  id: 1,
  path: 'Taken.en.forced.srt',
  presence: 'PRESENT',
  state: 'matched',
  kind: 'FORCED',
  origin: 'FILE',
  served: true,
  check: null,
  ...over,
});

const full = (over = {}) =>
  forced({ path: 'Taken.en.srt', kind: 'FULL', check: null, alignment: null, ...over });

describe('full subtitle files in the Admin helpers', () => {
  it('are told apart by the kind the server sends, and a missing kind is forced', () => {
    expect(isFull(full())).toBe(true);
    expect(isFull(forced())).toBe(false);
    expect(isFull(forced({ kind: undefined }))).toBe(false);
  });

  it('are never compared with OpenSubtitles, even when served with no check', () => {
    expect(checkSummary(full())).toBeNull();
    expect(checkSummary(forced())).toMatchObject({ kind: 'unchecked' });
  });

  it('are in All but in none of the forced chips, and are not counted as Unchecked', () => {
    const items = [
      forced({ id: 1 }),
      full({ id: 2 }),
      full({ id: 3, origin: 'OPENSUBTITLES' }),
      full({ id: 4, check: { ...check, verdict: 'CHECK' } }),
    ];

    expect(filterSidecars(items, 'all').map((i) => i.id)).toEqual([1, 2, 3, 4]);
    expect(filterSidecars(items, 'unchecked').map((i) => i.id)).toEqual([1]);
    expect(filterSidecars(items, 'check')).toEqual([]);
    expect(filterSidecars(items, 'downloads')).toEqual([]);
    expect(fileFilterCounts(items)).toEqual({ all: 4, check: 0, unchecked: 1, downloads: 0 });
  });

  it('are grouped like any other file', () => {
    expect(groupKey(full({ state: 'unmatched' }))).toBe('unmatched');
    expect(groupKey(full({ state: 'conflict' }))).toBe('conflicts');
    expect(groupKey(full({ presence: 'MISSING' }))).toBe('missing');
    expect(groupKey(full())).toBe('matched');
  });
});
