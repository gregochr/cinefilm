// Spend the buffer before the quality: while the bank could carry the film at 360p, Auto may not go below 360p.
// The rule decides by how long the bank would last if the film were fetched at 360p on the link as measured,
// as well as by the seconds in hand (docs/engineering/floor-hold-by-time-plan.md).

export const ARM_LASTS_S = 900; // the bank would last this long at 360p: hold the floor
export const RELEASE_LASTS_S = 600; // it would last less than this at 360p: let Auto go lower
export const ARM_MIN_AHEAD_S = 240; // never arm with less than this in hand
// Release below this much in hand: 120 s for one stuck fragment (hls.js's `maxLoadTimeMs`) plus a minute.
export const RELEASE_MIN_AHEAD_S = 180;

/**
 * How long `aheadS` seconds of film last when the film is fetched at `rateKbps` (the 360p level's peak bitrate)
 * over a link of `kbps`: the bank drains at the rate the film is consumed minus the rate it is filled.
 * Unbounded when the link is at least as fast as the film.
 */
export function lastsS(aheadS, kbps, rateKbps) {
  if (kbps >= rateKbps) return Infinity;
  return aheadS / (1 - kbps / rateKbps);
}

const known = (kbps) => (Number.isFinite(kbps) && kbps >= 0 ? kbps : null);

/**
 * `update({ aheadS, armKbps, releaseKbps, rateKbps })` returns whether the hold is armed. `aheadS` is the
 * seconds buffered ahead, `armKbps` the link speed the arming reads (null when there are no samples),
 * `releaseKbps` the lowest recent speed the release reads (null when there is none), `rateKbps` the 360p
 * level's bitrate. It arms when `aheadS` is at least ARM_MIN_AHEAD_S and the bank would last ARM_LASTS_S at
 * `armKbps`; it releases when `aheadS` is under RELEASE_MIN_AHEAD_S or the bank would last under
 * RELEASE_LASTS_S at `releaseKbps` (the release wins when both hold); otherwise it is as it was. It starts
 * released. An `aheadS` that is not a finite number counts as 0; a speed that is not a finite number counts as
 * unknown; without a usable `rateKbps` the hold is released.
 */
export function createFloorHold() {
  let armed = false;
  return {
    /** Releases the hold at once, as fragment trouble or a new network does. */
    reset() {
      armed = false;
    },
    update({ aheadS, armKbps = null, releaseKbps = null, rateKbps }) {
      if (!Number.isFinite(rateKbps) || rateKbps <= 0) {
        armed = false;
        return armed;
      }
      const ahead = Number.isFinite(aheadS) ? aheadS : 0;
      const arm = known(armKbps);
      const release = known(releaseKbps);
      const releasing =
        ahead < RELEASE_MIN_AHEAD_S ||
        (release !== null && lastsS(ahead, release, rateKbps) < RELEASE_LASTS_S);
      if (releasing) {
        armed = false;
      } else if (
        ahead >= ARM_MIN_AHEAD_S &&
        arm !== null &&
        lastsS(ahead, arm, rateKbps) >= ARM_LASTS_S
      ) {
        armed = true;
      }
      return armed;
    },
  };
}
