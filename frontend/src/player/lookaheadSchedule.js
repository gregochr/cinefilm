// The train look-ahead's scheduler (train look-ahead plan, decision 1 and PR 3c). It asks the train's feed, matches
// each answer on the map, keeps `lookaheadInput`'s state and hands the engine its input about once a second. Pure
// of the page: the ask, the map's loader, the clock and the timers are all passed in. It runs only between `begin`
// and `stop`, which the caller ties to "away from home"; at home it is never begun, so nothing is asked. It also
// works only while an engine that can take the input (`bankInput`) is attached: with none (Safari's native
// player, an Admin stop, direct play, a rebuild in between) nothing is asked, loaded or sent.
//
// When it asks (with an engine attached): at once on `begin`, on each `trigger` (a network change) and when a
// capable engine is attached; and every 30 s once a fix has parsed in this playback, whatever each answer is (a
// null, unparsable or refused answer changes nothing: the input has its own lapse rule). Before any fix has parsed
// there is one ask per trigger and no repeat: on a train `online` and `offline` may never fire, so one timeout
// must not end it for good, but nothing may loop either. One ask at a time: a trigger while one is in flight
// aborts it (its answer is from the old network) and asks again only once it has settled. The map is loaded
// once, lazily, when a fix first parses; a failed load is tried again only on a trigger or an attach.
//
// Nothing about the position is logged, stored or sent: it lives in the closures below, in memory.
import { createLookaheadInput } from './lookaheadInput.js';
import { matchFix } from './trackMatch.js';
import { parseFix } from './trainFeed.js';

/** After an answer, the next ask follows this many ms later. */
export const ASK_EVERY_MS = 30_000;
/** The engine is handed its input this often, in ms, while the schedule runs. */
export const FEED_ENGINE_EVERY_MS = 1000;

const globalTimers = {
  setTimeout: (...args) => globalThis.setTimeout(...args),
  clearTimeout: (...args) => globalThis.clearTimeout(...args),
  setInterval: (...args) => globalThis.setInterval(...args),
  clearInterval: (...args) => globalThis.clearInterval(...args),
};

/**
 * @param {Object} deps
 * @param {(signal: AbortSignal) => Promise<string|null>} deps.ask the feed's answer as text, or null; never rejects
 * @param {(signal: AbortSignal) => Promise<object[]>} deps.loadMap the map's tracks, [] on any failure
 * @param {() => number} [deps.now] the clock, in ms
 * @param {typeof globalTimers} [deps.timers]
 * @param {typeof createLookaheadInput} [deps.createInput]
 * @returns {{
 *   begin: () => void,
 *   trigger: () => void,
 *   stop: () => void,
 *   attach: (engine: { bankInput: (input: object|null) => void }) => void,
 *   detach: (engine: object) => void,
 * }}
 */
export function createLookaheadSchedule({
  ask,
  loadMap,
  now = () => Date.now(),
  timers = globalTimers,
  createInput = createLookaheadInput,
}) {
  let running = false;
  let epoch = 0; // each `begin` and `stop` is a new one: a late answer of an older one changes nothing
  let input = null;
  let engine = null;
  let tracks = null; // the map, once loaded and not empty
  let last = null; // the last fresh match
  let lastFix = null; // { fix, atMs }: the newest parsed fix, kept for a map that arrives after it
  let controller = null; // the ask in flight, null when none
  let askAgain = false; // a trigger came while an ask was in flight: ask again once it has settled
  let hasFix = false; // a fix has parsed in this playback: the 30 s cycle then goes on whatever an answer is
  let cycleTimer = null;
  let tickTimer = null;
  let mapController = null; // the map's load in flight, null when none
  let mapTried = false; // loaded, or tried since the last trigger

  const pushInput = () => {
    if (engine === null) return;
    try {
      engine.bankInput(input.input(now()));
    } catch {
      // The engine's business: nothing here may break playback.
    }
  };

  const startTick = () => {
    if (tickTimer === null) tickTimer = timers.setInterval(pushInput, FEED_ENGINE_EVERY_MS);
  };

  const place = (fix, atMs) => {
    const nowMs = now();
    const aged = { ...fix, ageS: fix.ageS + (nowMs - atMs) / 1000 };
    const result = matchFix(tracks, aged, last);
    last = result.match;
    input.noteMatch(result, aged, tracks, nowMs);
    pushInput();
  };

  const loadMapOnce = () => {
    if (mapController !== null || mapTried) return;
    mapTried = true;
    const mine = epoch;
    const own = new AbortController();
    mapController = own;
    void loadMap(own.signal)
      .catch(() => [])
      .then((loaded) => {
        if (mine !== epoch) return;
        mapController = null;
        if (!Array.isArray(loaded) || loaded.length === 0) return; // failed: a trigger tries again
        tracks = loaded;
        place(lastFix.fix, lastFix.atMs); // a map is asked for only after a fix
      })
      .catch(() => {}); // the state was cleaned up first; a throw below it must not become an unhandled rejection
  };

  const onAnswer = (text) => {
    const fix = typeof text === 'string' ? parseFix(text) : null;
    if (fix === null) {
      // Before any fix the cycle ends here until the next trigger; after one it goes on, the input as it was.
      if (hasFix) cycleTimer = timers.setTimeout(askNow, ASK_EVERY_MS);
      return;
    }
    const atMs = now();
    hasFix = true;
    lastFix = { fix, atMs };
    startTick();
    cycleTimer = timers.setTimeout(askNow, ASK_EVERY_MS);
    if (tracks === null) loadMapOnce();
    else place(fix, atMs);
  };

  // Nothing is asked, loaded or sent while no capable engine is attached: ends what is in flight and every timer.
  const pause = () => {
    epoch += 1;
    askAgain = false;
    controller?.abort();
    controller = null;
    mapController?.abort();
    mapController = null;
    timers.clearTimeout(cycleTimer);
    timers.clearInterval(tickTimer);
    cycleTimer = null;
    tickTimer = null;
  };

  function askNow() {
    timers.clearTimeout(cycleTimer);
    cycleTimer = null;
    // Only ever called with no ask in flight: `begin` and `trigger` check, and the 30 s timer is set after one settles.
    const mine = epoch;
    const own = new AbortController();
    controller = own;
    void ask(own.signal)
      .catch(() => null)
      .then((text) => {
        if (mine !== epoch) return; // stopped meanwhile: `stop` has cleaned up
        controller = null;
        if (askAgain) {
          askAgain = false;
          askNow();
          return;
        }
        onAnswer(text);
      })
      .catch(() => {}); // the state was cleaned up first; a throw below it must not become an unhandled rejection
  }

  return {
    /** Playback starts away from home: asks once. A second `begin` while running does nothing. */
    begin() {
      if (running) return;
      running = true;
      epoch += 1;
      input = createInput();
      last = null;
      lastFix = null;
      hasFix = false;
      mapTried = false;
      if (engine !== null) askNow();
    },

    /** The network changed: asks again, now (an ask in flight is from the old network: it is dropped). */
    trigger() {
      if (!running || engine === null) return;
      mapTried = false;
      if (controller === null) {
        askNow();
        return;
      }
      askAgain = true;
      controller.abort();
    },

    /** Away no more, or the playback is over: cancels the ask and the map's load, clears the timers, ends the input. */
    stop() {
      if (!running) return;
      running = false;
      pause();
      if (engine !== null) {
        try {
          engine.bankInput(null);
        } catch {
          // The engine may be on its way out.
        }
      }
    },

    /**
     * A new engine (`useStream` builds one per session). One without `bankInput` (the native player) is ignored.
     * While running, a capable one is a trigger: it asks at once (no ask is in flight, or the one in flight stands)
     * and gets the current input at once, then about once a second.
     */
    attach(next) {
      if (typeof next?.bankInput !== 'function') return;
      engine = next;
      if (!running) return;
      if (hasFix) {
        startTick();
        pushInput();
      }
      if (controller === null) {
        mapTried = false;
        askNow();
      }
    },

    /** The engine is going: forgotten if it is the one attached, and the asking and the ticking pause until another comes. */
    detach(old) {
      if (engine !== old || old === null) return;
      engine = null;
      if (running) pause();
    },
  };
}
