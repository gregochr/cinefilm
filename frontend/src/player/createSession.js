// Asks the server for a playback session and keeps asking while the link, not the server, is the problem. A
// definite refusal gives up (the caller direct-plays); no answer, or a proxy's, retries under "Reconnecting…".
// A film split across several files can answer 503 while the server prepares its later parts (it indexes them
// in the background); that is "ask again" too, with its own limit. Pure: the waits, the reachability check and
// the notice are injected. Plan: docs/engineering/poor-link-plan.md, section 1.
import { backoffMs } from './retry.js';

export const PREPARE_TRIES = 15;
export const PREPARE_DELAY_MS = 1000;
/** A pending request is checked this long after it began, and every `SESSION_CHECK_MS` after that. */
export const FIRST_CHECK_MS = 5000;
export const SESSION_CHECK_MS = 15000;
/** How long the check itself may take to hear the server. */
export const REACH_TIMEOUT_MS = 5000;

/** A proxy's answer when the app is down, or one that a retry can change: asked again like no answer. */
const RETRY_STATUSES = new Set([502, 503, 504, 408, 429]);
const SERVER_ERROR = 500;
const SIGNED_OUT = 401;
const PREPARING = 503;

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const never = () => new Promise(() => {});

/**
 * Calls `create(signal)` until it has a session, and returns it; returns null when the server refused (a 4xx
 * asking again cannot change, a second 500, a split film's 503 after `tries` tries) or `isCancelled()` turned
 * true (then nothing is called back, and a session that arrives anyway is still returned so the caller can
 * delete it). `create` builds its request on each call. `wait(ms)` and `untilSignedIn()` end early on a
 * wake-up or cancellation; `reachable()` resolves whether the server answers; `onRetrying(on)` says "Reconnecting…"
 * on or off; `onNoAnswer({ timeout })` reports a request that got no answer.
 */
export async function createSession(
  create,
  {
    retryWhenPreparing = false,
    tries = PREPARE_TRIES,
    delayMs = PREPARE_DELAY_MS,
    wait = sleep,
    untilSignedIn = never,
    isCancelled = () => false,
    reachable = null,
    onRetrying = () => {},
    onNoAnswer = () => {},
    firstCheckMs = FIRST_CHECK_MS,
    checkMs = SESSION_CHECK_MS,
  } = {},
) {
  let lastSent = null; // what onRetrying was last told
  const say = (on) => {
    if (on === lastSent || isCancelled()) return;
    lastSent = on;
    onRetrying(on);
  };

  // One request. Resolves { session } or { error, timedOut }; while it is pending, asks `reachable` now and then
  // and aborts the request's own controller when the server cannot be reached.
  const attempt = () => {
    const controller = new AbortController();
    let settled = false;
    let timedOut = false;
    let timer = null;
    const schedule = (delay) => {
      timer = setTimeout(async () => {
        if (settled) return;
        schedule(checkMs);
        const answers = await reachable();
        if (settled || answers) return;
        timedOut = true;
        controller.abort();
      }, delay);
    };
    if (reachable) schedule(firstCheckMs);
    return (async () => create(controller.signal))().then(
      (session) => {
        settled = true;
        clearTimeout(timer);
        return { session };
      },
      (error) => {
        settled = true;
        clearTimeout(timer);
        return { error, timedOut };
      },
    );
  };

  let failures = 0; // requests with no answer, which index the backoff
  let serverErrors = 0;
  let preparing = 0;
  for (;;) {
    const outcome = await attempt();
    if (outcome.session) {
      say(false);
      return outcome.session;
    }
    if (isCancelled()) return null;
    const status = outcome.error?.status;

    if (status === SIGNED_OUT) {
      say(false);
      await untilSignedIn();
      if (isCancelled()) return null;
      continue;
    }
    if (status === PREPARING && retryWhenPreparing) {
      say(false);
      preparing += 1;
      if (preparing >= tries) return null;
      await wait(delayMs);
    } else if (status === SERVER_ERROR) {
      serverErrors += 1;
      say(false); // the app answered
      if (serverErrors >= 2) return null;
      await wait(backoffMs(0));
    } else if (status === undefined || RETRY_STATUSES.has(status)) {
      if (status === undefined) onNoAnswer({ timeout: outcome.timedOut });
      say(true);
      await wait(backoffMs(failures));
      failures += 1;
    } else {
      say(false);
      return null;
    }
    if (isCancelled()) return null;
  }
}
