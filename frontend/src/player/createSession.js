// Asks the server for a playback session. A film split across several files can answer 503 while the server
// prepares its later parts (it indexes them in the background); that is "ask again", not a failure.

export const PREPARE_TRIES = 15;
export const PREPARE_DELAY_MS = 1000;

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * Calls `create()` and returns the session, or null when it could not be made (or `isCancelled()` turned true
 * while waiting). With `retryWhenPreparing` a 503 is retried up to `tries` times, `delayMs` apart; any other
 * failure, and a 503 for a film in one piece, gives up at once.
 */
export async function createSession(
  create,
  {
    retryWhenPreparing = false,
    tries = PREPARE_TRIES,
    delayMs = PREPARE_DELAY_MS,
    wait = sleep,
    isCancelled = () => false,
  } = {},
) {
  for (let attempt = 1; ; attempt += 1) {
    try {
      return await create();
    } catch (error) {
      if (!retryWhenPreparing || error?.status !== 503 || attempt >= tries) return null;
      await wait(delayMs);
      if (isCancelled()) return null;
    }
  }
}
