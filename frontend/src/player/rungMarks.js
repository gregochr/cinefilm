// hls.js marks a level when one of its fragments fails (`fragmentError`) and does not choose it again until a
// fragment of that level is buffered; its own expiry (`errorPenaltyExpireMs`) only covers `loadError`
// (docs/engineering/stuck-rung-plan.md). This forgets a mark that has stood for a minute without trouble on a
// level hls.js is not fetching, so hls.js may choose that level again on its own estimate.
import { HEALTHY_MS } from './stepGate.js';

/**
 * `sweep(levels, fetched)` reads `fragmentError` on every hls.js level except `fetched` (the index hls.js is
 * fetching, null when none: its own rules apply there, and the retry count it shares across levels is not to
 * be disturbed while that level is failing) and sets it back to 0 once the same count has stood for
 * `expireMs`. It returns the indices it cleared. `loadError` is never written: hls.js's own expiry covers it.
 * `trouble()` starts the time of every mark again, so the time is time without trouble.
 */
export function createRungMarks({ now = () => Date.now(), expireMs = HEALTHY_MS } = {}) {
  const seen = new Map(); // level index -> { count, at }
  return {
    sweep(levels, fetched) {
      const cleared = [];
      levels.forEach((level, index) => {
        const count = level.fragmentError;
        if (index === fetched || !(count > 0)) {
          seen.delete(index);
          return;
        }
        const noted = seen.get(index);
        if (!noted || noted.count !== count) {
          seen.set(index, { count, at: now() });
        } else if (now() - noted.at >= expireMs) {
          level.fragmentError = 0;
          seen.delete(index);
          cleared.push(index);
        }
      });
      return cleared;
    },
    trouble() {
      const at = now();
      for (const noted of seen.values()) noted.at = at;
    },
  };
}
