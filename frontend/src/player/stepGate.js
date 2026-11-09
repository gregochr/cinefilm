// Decision 3: Auto steps down at once and up one level at a time, after about 60 s without trouble. hls.js
// picks the level; this decides the highest level it may pick (`autoLevelCapping`).

export const HEALTHY_MS = 60000;

/**
 * Levels are hls.js indices, lowest bitrate first. `start(level)` sets the cap at the starting level,
 * `switched(level)` follows a step down, `trouble()` restarts the healthy clock (a stall), and `tick(top)`
 * raises the cap by one level once the clock has run `healthyMs`. Each returns the cap.
 */
export function createStepGate({ now = () => Date.now(), healthyMs = HEALTHY_MS } = {}) {
  let cap = null;
  let healthySince = now();
  return {
    start(level) {
      cap = level;
      healthySince = now();
      return cap;
    },
    switched(level) {
      if (cap === null || level < cap) {
        cap = level;
        healthySince = now();
      }
      return cap;
    },
    trouble() {
      healthySince = now();
    },
    tick(top) {
      if (cap !== null && cap < top && now() - healthySince >= healthyMs) {
        cap += 1;
        healthySince = now();
      }
      return cap;
    },
  };
}
