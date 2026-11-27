import { describe, expect, it } from 'vitest';
import { createRungMarks } from './rungMarks.js';
import { HEALTHY_MS } from './stepGate.js';

const EXPIRE = 60_000;
const level = (fragmentError = 0) => ({ fragmentError, loadError: 0, loadErrorTime: 0 });

function setup() {
  let t = 1000;
  const marks = createRungMarks({ now: () => t, expireMs: EXPIRE });
  return { marks, at: (ms) => (t = ms) };
}

describe('createRungMarks', () => {
  it('forgets a mark at expireMs and not a millisecond before', () => {
    const { marks, at } = setup();
    const levels = [level(), level(1)];
    expect(marks.sweep(levels, 0)).toEqual([]);
    at(1000 + EXPIRE - 1);
    expect(marks.sweep(levels, 0)).toEqual([]);
    expect(levels[1].fragmentError).toBe(1);
    at(1000 + EXPIRE);
    expect(marks.sweep(levels, 0)).toEqual([1]);
    expect(levels[1].fragmentError).toBe(0);
  });

  it('never clears the fetched level, and its time starts at the first sweep after it is left', () => {
    const { marks, at } = setup();
    const levels = [level(), level(1)];
    marks.sweep(levels, 1);
    at(1000 + EXPIRE * 5);
    expect(marks.sweep(levels, 1)).toEqual([]);
    expect(levels[1].fragmentError).toBe(1);
    marks.sweep(levels, 0); // hls.js left level 1: noted now
    at(1000 + EXPIRE * 6 - 1);
    expect(marks.sweep(levels, 0)).toEqual([]);
    at(1000 + EXPIRE * 6);
    expect(marks.sweep(levels, 0)).toEqual([1]);
    expect(levels[1].fragmentError).toBe(0);
  });

  it('treats null as nothing being fetched', () => {
    const { marks, at } = setup();
    const levels = [level(1)];
    marks.sweep(levels, null);
    at(1000 + EXPIRE);
    expect(marks.sweep(levels, null)).toEqual([0]);
  });

  it('starts the time again when the count grows', () => {
    const { marks, at } = setup();
    const levels = [level(1)];
    marks.sweep(levels, null);
    at(1000 + 30_000);
    levels[0].fragmentError = 2;
    marks.sweep(levels, null);
    at(1000 + 30_000 + EXPIRE - 1);
    expect(marks.sweep(levels, null)).toEqual([]);
    expect(levels[0].fragmentError).toBe(2);
    at(1000 + 30_000 + EXPIRE);
    expect(marks.sweep(levels, null)).toEqual([0]);
  });

  it('starts the time of every noted mark again on trouble', () => {
    const { marks, at } = setup();
    const levels = [level(1), level(2)];
    marks.sweep(levels, null);
    at(1000 + 40_000);
    marks.trouble();
    at(1000 + 40_000 + EXPIRE - 1);
    expect(marks.sweep(levels, null)).toEqual([]);
    at(1000 + 40_000 + EXPIRE);
    expect(marks.sweep(levels, null)).toEqual([0, 1]);
  });

  it('does nothing on trouble when no mark is noted', () => {
    const { marks, at } = setup();
    const levels = [level(0)];
    marks.trouble();
    at(1000 + EXPIRE * 2);
    levels[0].fragmentError = 1;
    expect(marks.sweep(levels, null)).toEqual([]); // noted now, not cleared at once
    expect(levels[0].fragmentError).toBe(1);
    at(1000 + EXPIRE * 3 - 1);
    expect(marks.sweep(levels, null)).toEqual([]);
    at(1000 + EXPIRE * 3);
    expect(marks.sweep(levels, null)).toEqual([0]);
  });

  it('forgets a level whose mark hls.js cleared itself: a later mark gets a whole expireMs', () => {
    const { marks, at } = setup();
    const levels = [level(1)];
    marks.sweep(levels, null);
    at(1000 + EXPIRE - 1000);
    levels[0].fragmentError = 0;
    marks.sweep(levels, null);
    at(1000 + EXPIRE);
    levels[0].fragmentError = 1;
    marks.sweep(levels, null);
    at(1000 + EXPIRE * 2 - 1);
    expect(marks.sweep(levels, null)).toEqual([]);
    at(1000 + EXPIRE * 2);
    expect(marks.sweep(levels, null)).toEqual([0]);
  });

  it('clears each of two marks at its own time', () => {
    const { marks, at } = setup();
    const levels = [level(1), level(0)];
    marks.sweep(levels, null); // level 0 noted at 1000
    at(1000 + 10_000);
    levels[1].fragmentError = 1;
    marks.sweep(levels, null); // level 1 noted at 11,000
    at(1000 + EXPIRE);
    expect(marks.sweep(levels, null)).toEqual([0]);
    expect(levels[1].fragmentError).toBe(1);
    at(1000 + 10_000 + EXPIRE);
    expect(marks.sweep(levels, null)).toEqual([1]);
  });

  it('never writes loadError or loadErrorTime', () => {
    const { marks, at } = setup();
    const levels = [{ fragmentError: 1, loadError: 3, loadErrorTime: 777 }];
    marks.sweep(levels, null);
    expect(levels[0]).toEqual({ fragmentError: 1, loadError: 3, loadErrorTime: 777 });
    at(1000 + EXPIRE);
    marks.sweep(levels, null);
    expect(levels[0]).toEqual({ fragmentError: 0, loadError: 3, loadErrorTime: 777 });
  });

  it('reads the real clock when none is given', () => {
    const marks = createRungMarks();
    const levels = [level(1)];
    marks.sweep(levels, null);
    marks.trouble();
    expect(marks.sweep(levels, null)).toEqual([]); // a minute of real time has not passed
    expect(levels[0].fragmentError).toBe(1);
  });

  it('expires after the step gate’s healthy minute by default', () => {
    let t = 0;
    const marks = createRungMarks({ now: () => t });
    const levels = [level(1)];
    marks.sweep(levels, null);
    t = HEALTHY_MS - 1;
    expect(marks.sweep(levels, null)).toEqual([]);
    t = HEALTHY_MS;
    expect(marks.sweep(levels, null)).toEqual([0]);
    expect(HEALTHY_MS).toBe(60_000);
  });
});
