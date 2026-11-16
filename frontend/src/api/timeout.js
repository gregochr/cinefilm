// A request that gives up. No JSX, no DOM: reusable by other clients. A connected but dead link (a train in a tunnel)
// leaves a fetch pending for minutes; the player's small admin requests (a timing step, a download removal) must not.

/** How long one of the player's admin requests may take before it counts as failed. */
export const ADMIN_TIMEOUT_MS = 15000;

/** What `withTimeout` rejects with when the request got no answer in time. */
export class RequestTimeout extends Error {
  constructor() {
    super('The request got no answer');
    this.name = 'RequestTimeout';
  }
}

/**
 * Runs `call(signal)` and rejects with `RequestTimeout` when it has not settled within `timeoutMs`, aborting the
 * request through `signal` (the race also ends it for a caller that ignores the signal). The timer is always cleared.
 */
export async function withTimeout(call, timeoutMs = ADMIN_TIMEOUT_MS) {
  const controller = new AbortController();
  let timer = null;
  const silence = new Promise((_, reject) => {
    timer = setTimeout(() => {
      controller.abort();
      reject(new RequestTimeout());
    }, timeoutMs);
  });
  try {
    return await Promise.race([call(controller.signal), silence]);
  } finally {
    clearTimeout(timer);
  }
}
