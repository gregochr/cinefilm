import { describe, expect, it } from 'vitest';
import { HOLD_ARM_S, HOLD_RELEASE_S, createFloorHold } from './floorHold.js';

describe('createFloorHold', () => {
  it('has thresholds of 420 s to arm and 240 s to release', () => {
    expect(HOLD_ARM_S).toBe(420);
    expect(HOLD_RELEASE_S).toBe(240);
  });

  it('starts released', () => {
    expect(createFloorHold().update(0)).toBe(false);
  });

  it('arms at 420 s and not at 419 s', () => {
    expect(createFloorHold().update(419)).toBe(false);
    expect(createFloorHold().update(420)).toBe(true);
  });

  it('stays armed down to 240 s and releases at 239 s', () => {
    const hold = createFloorHold();
    hold.update(420);
    expect(hold.update(300)).toBe(true);
    expect(hold.update(240)).toBe(true);
    expect(hold.update(239)).toBe(false);
  });

  it('does not re-arm between 240 s and 419 s once released', () => {
    const hold = createFloorHold();
    hold.update(420);
    hold.update(100);
    expect(hold.update(240)).toBe(false);
    expect(hold.update(419)).toBe(false);
    expect(hold.update(420)).toBe(true);
  });

  it.each([[Number.NaN], [undefined], [-5], [Infinity]])('counts %s as 0', (value) => {
    const hold = createFloorHold();
    hold.update(420);
    expect(hold.update(value)).toBe(false);
  });

  it('takes its own thresholds', () => {
    const hold = createFloorHold({ armS: 10, releaseS: 5 });
    expect(hold.update(9)).toBe(false);
    expect(hold.update(10)).toBe(true);
    expect(hold.update(5)).toBe(true);
    expect(hold.update(4)).toBe(false);
  });
});
