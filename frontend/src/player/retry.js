// The waiting and asking behind "keep trying while the link is poor": backoff, a waiter that a returning
// connection or a sign-in wakes early, and a check that the server answers at all. No React; the only side
// effects are timers and window listeners, all removed by `dispose()`.
import { ApiError, SIGNED_IN_EVENT } from '../api/client.js';

const BASE_MS = 1000;
const MAX_MS = 8000;
/** The statuses a proxy answers when the app behind it is down or restarting. */
const PROXY_STATUSES = new Set([502, 503, 504]);

/** The pause before attempt `attempt` (0 for the first): 1, 2, 4, 8 s, then 8 s every time (hls.js's own). */
export const backoffMs = (attempt) => Math.min(BASE_MS * 2 ** attempt, MAX_MS);

/**
 * One waiter per call. It hears the window's `online` event and `SIGNED_IN_EVENT` from creation until
 * `dispose()`, so a wake-up that arrives while a request is in flight still ends the next wait at once.
 * `wait(ms, { signal })` resolves after `ms`, at once when a wake-up came since the last wait began, or when
 * `signal` is or becomes aborted. `untilSignedIn({ signal })` resolves on a sign-in (one that came since the
 * last wait began counts) or when `signal` aborts. Both resolve (never reject) when the waiter is disposed.
 */
export function createWaiter() {
  let woken = false; // an `online` or a sign-in arrived with no wait to end
  let signedIn = false; // a sign-in arrived with no wait to end
  let pending = null; // { signedInOnly, finish } of the wait in progress
  let disposed = false;

  const onOnline = () => {
    if (pending) {
      if (!pending.signedInOnly) pending.finish();
    } else {
      woken = true;
    }
  };
  const onSignedIn = () => {
    if (pending) {
      pending.finish();
    } else {
      woken = true;
      signedIn = true;
    }
  };
  window.addEventListener('online', onOnline);
  window.addEventListener(SIGNED_IN_EVENT, onSignedIn);

  const begin = (signedInOnly, ms, signal) =>
    new Promise((resolve) => {
      const early = signedInOnly ? signedIn : woken;
      woken = false;
      signedIn = false;
      if (disposed || early || signal?.aborted) {
        resolve();
        return;
      }
      let timer = null;
      const entry = {
        signedInOnly,
        finish() {
          clearTimeout(timer);
          signal?.removeEventListener('abort', entry.finish);
          if (pending === entry) pending = null;
          resolve();
        },
      };
      pending = entry;
      signal?.addEventListener('abort', entry.finish);
      if (!signedInOnly) timer = setTimeout(entry.finish, ms);
    });

  return {
    wait: (ms, { signal } = {}) => begin(false, ms, signal),
    untilSignedIn: ({ signal } = {}) => begin(true, 0, signal),
    dispose() {
      disposed = true;
      window.removeEventListener('online', onOnline);
      window.removeEventListener(SIGNED_IN_EVENT, onSignedIn);
      pending?.finish();
    },
  };
}

/**
 * Whether the server answers at all: `check(signal)` resolves, or rejects with an `ApiError` whose status is
 * not a proxy's (502, 503, 504), within `timeoutMs`. A network failure, a proxy's answer and silence are all
 * "no". Never rejects.
 */
export async function reachable(check, { timeoutMs }) {
  const controller = new AbortController();
  let timer = null;
  const silence = new Promise((resolve) => {
    timer = setTimeout(() => {
      controller.abort();
      resolve(false);
    }, timeoutMs);
  });
  const answer = new Promise((resolve) => resolve(check(controller.signal))).then(
    () => true,
    (error) => error instanceof ApiError && !PROXY_STATUSES.has(error.status),
  );
  try {
    return await Promise.race([answer, silence]);
  } finally {
    clearTimeout(timer);
  }
}
