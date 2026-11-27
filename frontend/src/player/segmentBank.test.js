import { describe, expect, it } from 'vitest';
import { BANK_LIMIT_BYTES, KEEP_BEHIND_S, createSegmentBank } from './segmentBank.js';

const TIMINGS = { start: 100, first: 140, end: 300 };

// Segment `seq` is 4 s long: seq 0 is film 0-4 s, seq 1 is 4-8 s.
function seg(seq, { rung = 'r480', size = 10, start = seq * 4, end = start + 4 } = {}) {
  return {
    seq,
    rung,
    start,
    end,
    bytes: new Uint8Array(size).fill(seq + 1).buffer,
    timings: TIMINGS,
  };
}

function bankWith(seqs, options) {
  const bank = createSegmentBank(options);
  for (const s of seqs) bank.putSegment(seg(s));
  return bank;
}

const range = (from, to) => Array.from({ length: to - from }, (_, i) => from + i);

describe('the bank store (decisions 1 and 9)', () => {
  it('limits the bank to 600 MB and keeps 30 s behind', () => {
    expect(BANK_LIMIT_BYTES).toBe(629_145_600);
    expect(KEEP_BEHIND_S).toBe(30);
  });

  it('holds one copy per sequence number, with its rung, film times, bytes and timings', () => {
    const bank = bankWith([3]);
    const held = bank.getSegment(3);
    expect(held.seq).toBe(3);
    expect(held.rung).toBe('r480');
    expect(held.start).toBe(12);
    expect(held.end).toBe(16);
    expect(new Uint8Array(held.bytes)).toEqual(new Uint8Array(10).fill(4));
    expect(held.timings).toEqual({ start: 100, first: 140, end: 300 });
    expect(bank.hasSegment(3)).toBe(true);
    expect(bank.hasSegment(4)).toBe(false);
    expect(bank.getSegment(4)).toBeNull();
    expect(bank.heldBytes()).toBe(10);
  });

  it('replaces a copy on a later put of the same number and recounts the bytes', () => {
    const bank = bankWith([5]);
    bank.putSegment(seg(5, { rung: 'r240', size: 25 }));
    expect(bank.getSegment(5).rung).toBe('r240');
    expect(bank.getSegment(5).bytes.byteLength).toBe(25);
    expect(bank.heldBytes()).toBe(25);
    bank.putSegment(seg(5, { rung: 'r360', size: 4 }));
    expect(bank.heldBytes()).toBe(4);
  });

  it('hands out a fresh copy each time, never the stored buffer, unaffected by a transfer', () => {
    const bank = bankWith([0]);
    const first = bank.getSegment(0);
    structuredClone(first.bytes, { transfer: [first.bytes] });
    expect(first.bytes.byteLength).toBe(0);
    const second = bank.getSegment(0);
    expect(second.bytes.byteLength).toBe(10);
    expect(new Uint8Array(second.bytes)).toEqual(new Uint8Array(10).fill(1));
    expect(bank.getSegment(0).bytes).not.toBe(second.bytes);
    second.timings.end = 0;
    expect(bank.getSegment(0).timings.end).toBe(300);
    expect(bank.heldBytes()).toBe(10);
  });

  it('owns its bytes: detaching the original buffer changes nothing held, and dropping releases the limit', () => {
    const bank = createSegmentBank({ limitBytes: 10 });
    const original = new Uint8Array(10).fill(7).buffer;
    bank.putSegment({ seq: 0, rung: 'r480', start: 0, end: 4, bytes: original, timings: TIMINGS });
    structuredClone(original, { transfer: [original] });
    expect(original.byteLength).toBe(0);
    expect(bank.heldBytes()).toBe(10);
    expect(bank.atLimit()).toBe(true);
    expect(new Uint8Array(bank.getSegment(0).bytes)).toEqual(new Uint8Array(10).fill(7));
    bank.dropBehind(50);
    expect(bank.heldBytes()).toBe(0);
    expect(bank.atLimit()).toBe(false);
  });

  it('holds playlists: held, missing, and all of a list of rungs', () => {
    const bank = createSegmentBank();
    expect(bank.getPlaylist('r240')).toBeNull();
    expect(bank.allPlaylistsHeld(['r240', 'r360'])).toBe(false);
    bank.putPlaylist('r240', '#EXTM3U a');
    expect(bank.allPlaylistsHeld(['r240', 'r360'])).toBe(false);
    bank.putPlaylist('r360', '#EXTM3U b');
    expect(bank.getPlaylist('r240')).toBe('#EXTM3U a');
    expect(bank.allPlaylistsHeld(['r240', 'r360'])).toBe(true);
    expect(bank.allPlaylistsHeld([])).toBe(true);
  });

  describe('the limit', () => {
    it('stops the front at the limit, accepts a put over it, and releases it when film is dropped', () => {
      const bank = createSegmentBank({ limitBytes: 25 });
      bank.putSegment(seg(0));
      bank.putSegment(seg(1));
      expect(bank.atLimit()).toBe(false);
      bank.putSegment(seg(2, { size: 15 }));
      expect(bank.heldBytes()).toBe(35);
      expect(bank.atLimit()).toBe(true);
      expect(bank.hasSegment(2)).toBe(true);
      bank.dropBehind(41);
      expect(bank.heldBytes()).toBe(15);
      bank.dropBehind(43);
      expect(bank.heldBytes()).toBe(0);
      expect(bank.atLimit()).toBe(false);
    });

    it('is at the limit exactly at limitBytes and not one byte under', () => {
      const bank = createSegmentBank({ limitBytes: 20 });
      bank.putSegment(seg(0, { size: 19 }));
      expect(bank.atLimit()).toBe(false);
      bank.putSegment(seg(1, { size: 1 }));
      expect(bank.atLimit()).toBe(true);
    });

    it('is released when a smaller copy replaces a larger one', () => {
      const bank = createSegmentBank({ limitBytes: 20 });
      bank.putSegment(seg(0, { size: 30 }));
      expect(bank.atLimit()).toBe(true);
      bank.putSegment(seg(0, { size: 5 }));
      expect(bank.atLimit()).toBe(false);
    });
  });

  describe('dropping behind the playhead (decision 9)', () => {
    it('keeps a segment ending exactly 30 s before the playhead and drops one just over', () => {
      const bank = bankWith(range(0, 12));
      bank.dropBehind(34);
      expect(bank.hasSegment(0)).toBe(true);
      bank.dropBehind(34.01);
      expect(bank.hasSegment(0)).toBe(false);
      expect(bank.hasSegment(1)).toBe(true);
      expect(bank.heldBytes()).toBe(110);
    });
  });

  describe('seeking', () => {
    it('forward inside what is held drops what ends more than 30 s before the target and keeps the rest', () => {
      const bank = bankWith(range(0, 20));
      expect(bank.seek(50)).toBe('inside');
      // segment 4 ends at 20 s, exactly 30 s before 50: kept; segment 3 ends at 16 s: dropped.
      expect(range(0, 20).filter((n) => bank.hasSegment(n))).toEqual(range(4, 20));
    });

    it('back keeps everything held ahead and drops nothing', () => {
      const bank = bankWith(range(10, 20));
      expect(bank.seek(5)).toBe('inside');
      expect(bank.heldBytes()).toBe(100);
      expect(range(10, 20).every((n) => bank.hasSegment(n))).toBe(true);
    });

    it('beyond what is held says so and leaves it to the caller to clear', () => {
      const bank = bankWith(range(0, 5));
      expect(bank.seek(19.99)).toBe('inside');
      expect(bank.seek(20)).toBe('beyond');
      expect(bank.seek(500)).toBe('beyond');
      expect(bank.heldBytes()).toBe(50);
      bank.clear();
      expect(bank.heldBytes()).toBe(0);
      expect(bank.hasSegment(0)).toBe(false);
    });

    it('is beyond when nothing is held', () => {
      expect(createSegmentBank().seek(0)).toBe('beyond');
    });
  });

  describe('covers (decision 2)', () => {
    it('is true when held segments run without a gap from 30 s behind the playhead to the buffered end', () => {
      const bank = bankWith(range(5, 12));
      // playhead 50 -> from 20 s (start of seq 5); buffered end 48 = end of seq 11.
      expect(bank.covers(50, 48)).toBe(true);
      expect(bank.covers(50, 40)).toBe(true);
    });

    it('is false with a gap in the middle', () => {
      const bank = bankWith([5, 6, 8, 9, 10, 11]);
      expect(bank.covers(50, 48)).toBe(false);
      expect(bank.covers(50, 28)).toBe(true);
    });

    it('is false when the start is not held or the end is past what is held', () => {
      const bank = bankWith(range(6, 12));
      expect(bank.covers(50, 48)).toBe(false);
      expect(bankWith(range(5, 12)).covers(50, 48.5)).toBe(false);
    });

    it('clamps the start at 0 for a playhead under 30 s', () => {
      const bank = bankWith(range(0, 4));
      expect(bank.covers(10, 16)).toBe(true);
      expect(bankWith(range(1, 4)).covers(10, 16)).toBe(false);
    });

    it('is true with the buffered end exactly at a segment boundary', () => {
      const bank = bankWith(range(0, 4));
      expect(bank.covers(0, 16)).toBe(true);
      expect(bank.covers(0, 16.01)).toBe(false);
    });

    it('is false when nothing is held', () => {
      expect(createSegmentBank().covers(0, 0)).toBe(false);
    });
  });

  describe('the front and film ahead', () => {
    it('gives the first number after the contiguous held run holding a position', () => {
      const bank = bankWith([2, 3, 4, 7, 8]);
      expect(bank.nextAfterRun(8)).toBe(5);
      expect(bank.nextAfterRun(17)).toBe(5);
      expect(bank.nextAfterRun(30)).toBe(9);
    });

    it('gives null for a position not held, and a segment end belongs to the next segment', () => {
      const bank = bankWith([2, 3]);
      expect(bank.nextAfterRun(7.99)).toBeNull();
      expect(bank.nextAfterRun(8)).toBe(4);
      expect(bank.nextAfterRun(16)).toBeNull();
    });

    it('counts film ahead held up to the end of the run, and 0 when not held', () => {
      const bank = bankWith([2, 3, 4, 7]);
      expect(bank.filmAheadHeld(10)).toBe(10);
      expect(bank.filmAheadHeld(8)).toBe(12);
      expect(bank.filmAheadHeld(22)).toBe(0);
      expect(bank.filmAheadHeld(30)).toBe(2);
    });

    it('follows sequence numbers across segments of unequal length', () => {
      const bank = createSegmentBank();
      bank.putSegment(seg(0, { start: 0, end: 4.004 }));
      bank.putSegment(seg(1, { start: 4.004, end: 16.01 }));
      expect(bank.nextAfterRun(1)).toBe(2);
      expect(bank.filmAheadHeld(1)).toBeCloseTo(15.01, 6);
      expect(bank.covers(0, 16.01)).toBe(true);
    });
  });
});
