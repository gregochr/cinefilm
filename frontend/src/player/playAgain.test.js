import { describe, expect, it } from 'vitest';
import { withLearnedOffsets } from './playAgain.js';

const film = {
  id: 1,
  editions: [
    {
      id: 11,
      forcedSubtitle: { sidecarIds: [5], offsetsMs: [0] },
      fullSubtitle: { sidecarIds: [6, 7], offsetsMs: [0, 0] },
    },
    { id: 12, forcedSubtitle: null, fullSubtitle: null },
  ],
};

describe('withLearnedOffsets', () => {
  it('gives the saved offsets to the subtitle whose rows they were saved for', () => {
    const next = withLearnedOffsets(film, {
      FORCED: { ids: [5], offsets: [500] },
      FULL: { ids: [6, 7], offsets: [250, -250] },
    });
    expect(next.editions[0].forcedSubtitle.offsetsMs).toEqual([500]);
    expect(next.editions[0].fullSubtitle.offsetsMs).toEqual([250, -250]);
    expect(next.editions[1].forcedSubtitle).toBeNull();
    expect(film.editions[0].forcedSubtitle.offsetsMs).toEqual([0]);
  });

  it('leaves a subtitle alone when its rows are other rows, or nothing was saved', () => {
    const next = withLearnedOffsets(film, { FORCED: { ids: [9], offsets: [500] } });
    expect(next.editions[0].forcedSubtitle.offsetsMs).toEqual([0]);
    expect(next.editions[0].fullSubtitle.offsetsMs).toEqual([0, 0]);
    expect(withLearnedOffsets(film, {}).editions[0].forcedSubtitle.offsetsMs).toEqual([0]);
  });
});
