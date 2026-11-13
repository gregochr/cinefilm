import { describe, expect, it } from 'vitest';
import { HOLE_S, bankedAhead } from './buffered.js';

function videoWith(ranges, currentTime) {
  return {
    currentTime,
    buffered: {
      length: ranges.length,
      start: (i) => ranges[i][0],
      end: (i) => ranges[i][1],
    },
  };
}

describe('bankedAhead', () => {
  it('bridges holes of up to 0.5 s', () => {
    expect(HOLE_S).toBe(0.5);
  });

  it('is the end of the one range minus the playhead', () => {
    expect(bankedAhead(videoWith([[0, 100]], 40))).toBe(60);
  });

  it('runs to the end of the second range across a 0.3 s hole', () => {
    expect(
      bankedAhead(
        videoWith(
          [
            [0, 10],
            [10.3, 100],
          ],
          4,
        ),
      ),
    ).toBeCloseTo(96, 5);
  });

  it('bridges a hole of exactly 0.5 s and not one of 0.51 s', () => {
    const at = (gap) =>
      bankedAhead(
        videoWith(
          [
            [0, 10],
            [10 + gap, 100],
          ],
          0,
        ),
      );
    expect(at(0.5)).toBe(100);
    expect(at(0.51)).toBe(10);
  });

  it('chains three ranges', () => {
    expect(
      bankedAhead(
        videoWith(
          [
            [0, 10],
            [10.2, 20],
            [20.4, 30],
          ],
          0,
        ),
      ),
    ).toBe(30);
  });

  it('stops at the first hole that is too wide', () => {
    expect(
      bankedAhead(
        videoWith(
          [
            [0, 10],
            [10.2, 20],
            [25, 30],
          ],
          0,
        ),
      ),
    ).toBe(20);
  });

  it('finds the range when the playhead is up to 0.5 s before its start', () => {
    expect(bankedAhead(videoWith([[10.5, 20]], 10))).toBe(10);
    expect(bankedAhead(videoWith([[10.51, 20]], 10))).toBe(0);
  });

  it('is 0 when the playhead is outside every range', () => {
    expect(
      bankedAhead(
        videoWith(
          [
            [0, 10],
            [50, 60],
          ],
          30,
        ),
      ),
    ).toBe(0);
    expect(bankedAhead(videoWith([[0, 10]], 11))).toBe(0);
  });

  it('is 0 with no ranges', () => {
    expect(bankedAhead(videoWith([], 0))).toBe(0);
  });
});
