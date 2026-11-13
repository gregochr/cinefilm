// Spend the buffer before the quality: while a deep buffer is held, Auto may not go below 360p. A pure
// hysteresis on the seconds buffered ahead: it arms when the buffer is deep and releases once it has drained.

export const HOLD_ARM_S = 420; // the buffer is deep enough to hold the floor
export const HOLD_RELEASE_S = 240; // the buffer has drained enough to let Auto go lower

/**
 * `update(aheadS)` takes the seconds buffered ahead and returns whether the hold is armed: armed at
 * `armS` or more, released below `releaseS`, otherwise as it was. It starts released; a value that is not
 * a finite number counts as 0.
 */
export function createFloorHold({ armS = HOLD_ARM_S, releaseS = HOLD_RELEASE_S } = {}) {
  let armed = false;
  return {
    update(aheadS) {
      const ahead = Number.isFinite(aheadS) ? aheadS : 0;
      if (ahead >= armS) armed = true;
      else if (ahead < releaseS) armed = false;
      return armed;
    },
  };
}
