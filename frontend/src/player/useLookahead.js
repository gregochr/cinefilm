import { useEffect, useState } from 'react';
import { loadLinkMap } from '../api/linkMap.js';
import { createLookaheadSchedule } from './lookaheadSchedule.js';
import { askTrainFeed } from './trainFeedFrame.js';
import { TRAIN_FEED_URL } from './trainFeed.js';

// The fixtures build has no train: nothing is asked there (as `operator.js` and `latency.js` answer fixed values).
const askFeed = (signal) =>
  import.meta.env.VITE_FIXTURES === '1'
    ? Promise.resolve(null)
    : askTrainFeed(TRAIN_FEED_URL, { signal });

// The scheduler calls `ask(signal)` and `loadMap(signal)` with a bare signal; `loadLinkMap` takes `{ signal }`, so
// it is adapted here, or an abort would never reach the request.
const loadMapFor = (signal) => loadLinkMap({ signal });

/**
 * The train look-ahead of one playback: the scheduler (`lookaheadSchedule.js`), running while `active` (away from
 * home, `isAway`) and stopped when `active` goes or the player unmounts. It asks again on a network change (the
 * window's `online` and `offline`, as `throughput.js` counts them). Returns the schedule, whose `attach` and
 * `detach` `useStream` calls for each engine it builds. `ask` and `loadMap` are for tests: pass stable functions.
 */
export function useLookahead({ active, ask = askFeed, loadMap = loadMapFor }) {
  const [schedule] = useState(() => createLookaheadSchedule({ ask, loadMap }));

  useEffect(() => {
    if (!active) return undefined;
    schedule.begin();
    return () => schedule.stop();
  }, [active, schedule]);

  useEffect(() => {
    const onNetworkChange = () => schedule.trigger();
    window.addEventListener('online', onNetworkChange);
    window.addEventListener('offline', onNetworkChange);
    return () => {
      window.removeEventListener('online', onNetworkChange);
      window.removeEventListener('offline', onNetworkChange);
    };
  }, [schedule]);

  return schedule;
}
