// The network round trip to the server: small requests to a cheap endpoint, timed by the browser itself
// (Resource Timing: responseStart minus requestStart). Pure measurement, with the fetch and the observer
// injected so tests never touch the network. Detection can never break playback: nothing here rejects.

export const LATENCY_PATH = '/api/session';
export const LATENCY_SAMPLES = 3;
export const LATENCY_TIMEOUT_MS = 4000;
/** How long to wait for a browser that hands over a Resource Timing entry late. */
export const ENTRY_WAIT_MS = 200;
/** The pauses before another try while none has been timed yet (a tunnel, a cutting). */
export const RETRY_PAUSES_MS = [2000, 5000, 10000, 20000, 30000, 30000];
/** What the dev fixture server reports: away from home, with no request. */
export const FIXTURE_LATENCY_MS = 48;

/** The round trip in an entry, or null when the browser gave no usable figure (cached, cross-origin, absent). */
export function roundTrip(entry) {
  const { requestStart, responseStart } = entry ?? {};
  return requestStart > 0 && responseStart >= requestStart ? responseStart - requestStart : null;
}

/** Waits `ms`; resolves false when `signal` aborts first. Leaves no timer and no listener behind. */
function pause(ms, signal) {
  return new Promise((resolve) => {
    const onAbort = () => {
      clearTimeout(timer);
      resolve(false);
    };
    const timer = setTimeout(() => {
      signal?.removeEventListener('abort', onAbort);
      resolve(true);
    }, ms);
    signal?.addEventListener('abort', onAbort);
  });
}

const ANSWERED = 'answered';
const FAILED = 'failed';

// One try. It resolves the round trip in ms when the try was timed, ANSWERED when a response came (any status)
// but no usable entry did, or when this browser cannot observe resources, and FAILED when the request rejected or
// timed out.
async function attempt({ signal, fetchImpl, Observer, url }) {
  const controller = new AbortController();
  let found = null; // the first usable entry for `url`
  let observer = null;
  let timeout = null;
  let waitTimer = null;
  let release = null; // ends the wait for a late entry
  const take = (entries) => {
    found ??= entries.find((entry) => entry.name === url && roundTrip(entry) !== null) ?? null;
  };
  const onAbort = () => {
    controller.abort();
    release?.();
  };
  try {
    try {
      observer = new Observer((list) => {
        take(list.getEntries());
        if (found !== null) release?.();
      });
      observer.observe({ entryTypes: ['resource'] });
    } catch {
      return ANSWERED;
    }
    signal?.addEventListener('abort', onAbort);
    timeout = setTimeout(() => controller.abort(), LATENCY_TIMEOUT_MS);
    let response;
    try {
      response = await fetchImpl(LATENCY_PATH, {
        credentials: 'same-origin',
        cache: 'no-store',
        headers: { Accept: 'application/json' },
        signal: controller.signal,
      });
    } catch {
      return FAILED;
    }
    try {
      await response.text();
    } catch {
      // The body is only read so the request is complete; its failure changes nothing.
    }
    take(observer.takeRecords());
    if (found === null && !signal?.aborted) {
      await new Promise((resolve) => {
        release = resolve;
        waitTimer = setTimeout(resolve, ENTRY_WAIT_MS);
      });
    }
    return found === null ? ANSWERED : roundTrip(found);
  } finally {
    clearTimeout(timeout);
    clearTimeout(waitTimer);
    signal?.removeEventListener('abort', onAbort);
    try {
      observer.disconnect(); // a missing observer throws here, and there is nothing to release
    } catch {
      // Nothing to release.
    }
  }
}

/**
 * The smallest round trip of up to three tries, in whole milliseconds, or null when there is no figure. Never
 * rejects. Until one try has been timed a failed try is followed by another after a growing pause; once one has
 * been timed, two more follow at once and any that is not timed is left out. A try that is answered but has no
 * usable entry ends the measurement: the browser gives no timing and asking again will not change that. No
 * usable observer means no request at all: the observer is made before the request.
 */
export async function measureLatency({
  signal,
  fetchImpl = globalThis.fetch,
  Observer = globalThis.PerformanceObserver,
  baseUrl = globalThis.location?.href,
} = {}) {
  try {
    if (import.meta.env.VITE_FIXTURES === '1') return FIXTURE_LATENCY_MS;
    if (signal?.aborted || typeof fetchImpl !== 'function') return null;
    const url = new URL(LATENCY_PATH, baseUrl).href;
    const timed = [];
    let remaining = null; // tries still to make once one has been timed
    let pauses = 0;
    for (;;) {
      const outcome = await attempt({ signal, fetchImpl, Observer, url });
      if (signal?.aborted) return null;
      const isTimed = typeof outcome === 'number';
      if (isTimed) timed.push(outcome);
      if (remaining !== null) {
        remaining -= 1;
      } else if (isTimed) {
        remaining = LATENCY_SAMPLES - 1;
      } else if (outcome === ANSWERED || pauses >= RETRY_PAUSES_MS.length) {
        return null;
      } else if (!(await pause(RETRY_PAUSES_MS[pauses], signal))) {
        return null;
      } else {
        pauses += 1;
      }
      if (remaining === 0) return Math.round(Math.min(...timed));
    }
  } catch {
    return null;
  }
}
