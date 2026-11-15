import { describe, expect, it } from 'vitest';
import { ApiError } from '../api/client.js';
import { MAX_OFFSET_MS, NUDGES } from './subtitleAdmin.js';
import { subtitleTiming } from './subtitleFixCopy.js';
import {
  EARLIER,
  LATER,
  TimingChanged,
  TimingLookupFailed,
  inSync,
  nextOffsets,
  timingProblem,
  timingReadout,
  timingRows,
  trackToken,
  trackVersion,
} from './subtitleTiming.js';

const row = (id, offsetMs) => ({ id, offsetMs });

describe('the step', () => {
  it('is Admin’s own 0.25 s nudge, with its own spoken name', () => {
    expect(EARLIER).toBe(NUDGES.find((n) => n.delta === -250));
    expect(LATER).toBe(NUDGES.find((n) => n.delta === 250));
    expect(EARLIER.spoken).toBe('Show lines 0.25 seconds earlier');
  });
});

describe('timingRows', () => {
  it('returns the listed rows for the ids, in the film’s order, and leaves missing ones out', () => {
    const list = [row(3, 0), row(1, 100), row(2, 200)];

    expect(timingRows(list, [2, 9, 1]).map((r) => r.id)).toEqual([2, 1]);
    expect(timingRows(list, undefined)).toEqual([]);
  });
});

describe('nextOffsets', () => {
  it('moves each row from its own offset, so parts that differ keep their difference', () => {
    expect(nextOffsets([row(1, 1000), row(2, -500)], 250)).toEqual([
      { id: 1, offsetMs: 1250, was: 1000 },
      { id: 2, offsetMs: -250, was: -500 },
    ]);
  });

  it('keeps every row within what the server allows', () => {
    const moved = nextOffsets([row(1, MAX_OFFSET_MS), row(2, -MAX_OFFSET_MS)], 250);

    expect(moved.map((m) => m.offsetMs)).toEqual([MAX_OFFSET_MS, -MAX_OFFSET_MS + 250]);
  });

  it('sets every row to 0 for a reset', () => {
    expect(nextOffsets([row(1, 1000), row(2, -500)], null).map((m) => m.offsetMs)).toEqual([0, 0]);
  });
});

describe('timingReadout', () => {
  it.each([
    [[0], 'No timing change'],
    [[250], '+0.25 s'],
    [[-1000, -1000], '−1 s'],
    [[250, 500], subtitleTiming.differs],
  ])('says %j as %s', (offsets, text) => {
    expect(timingReadout(offsets)).toBe(text);
  });

  it('says nothing while the offsets are not known', () => {
    expect(timingReadout(null)).toBeNull();
    expect(timingReadout([])).toBeNull();
  });
});

describe('inSync', () => {
  it('is true only when every row is at 0', () => {
    expect(inSync([0, 0])).toBe(true);
    expect(inSync([0, 250])).toBe(false);
    expect(inSync(null)).toBe(false);
    expect(inSync([])).toBe(false);
  });
});

describe('trackToken and trackVersion', () => {
  it('differ for every change the server answers with, and the film’s own version is kept in front', () => {
    const a = trackToken([{ version: 5, offsetMs: 250 }]);
    const b = trackToken([{ version: 5, offsetMs: 500 }]);

    expect(a).not.toBe(b);
    expect(
      trackToken([
        { version: 5, offsetMs: 250 },
        { version: 6, offsetMs: 0 },
      ]),
    ).toBe('5_250-6_0');
    expect(trackVersion(77, a)).toBe(`77.${a}`);
    expect(trackVersion(77, null)).toBe(77);
  });
});

describe('timingProblem', () => {
  it('shows the server’s own words for a refusal', () => {
    expect(timingProblem(new ApiError(400, 'Bad', { error: 'The offset must be tidy' }))).toBe(
      'The offset must be tidy',
    );
  });

  it.each([
    [new TimingLookupFailed(), subtitleTiming.lookupFailed],
    [new TimingChanged(), subtitleTiming.changed],
    [new ApiError(500, 'Boom'), subtitleTiming.failed],
    [new Error('offline'), subtitleTiming.failed],
  ])('says one plain line for %s', (failure, text) => {
    expect(timingProblem(failure)).toBe(text);
  });
});
