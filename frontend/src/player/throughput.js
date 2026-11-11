// Measured throughput samples (kbit/s). The indicator reads "Home" from their median.

export const MAX_SAMPLES = 9;
export const HOME_KBPS = 20000;

/** Appends a valid sample and keeps the newest `max`. Invalid samples (zero, negative, NaN) are dropped. */
export function addSample(samples, kbps, max = MAX_SAMPLES) {
  if (!Number.isFinite(kbps) || kbps <= 0) return samples;
  return [...samples, kbps].slice(-max);
}

/** The median of the samples, or null when there are none. An even count averages the middle two. */
export function median(samples) {
  if (samples.length === 0) return null;
  const sorted = [...samples].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
}

/** The lower middle of the samples (the lower of the middle two for an even count), or null when there are none. */
export function lowerMiddle(samples) {
  if (samples.length === 0) return null;
  const sorted = [...samples].sort((a, b) => a - b);
  return sorted[Math.floor((sorted.length - 1) / 2)];
}

// The speed this browser session last measured, kept so the next film (or a recovery) can skip the probe.
export const SPEED_KEY = 'cinefilm.speed';
/** A remembered speed is reused for this long. */
export const REMEMBER_MS = 600000;

/** Remembers `kbps` (a whole number) measured at `at` (ms since the epoch). A storage that throws is ignored. */
export function rememberSpeed(kbps, at = Date.now()) {
  try {
    sessionStorage.setItem(SPEED_KEY, JSON.stringify({ kbps: Math.round(kbps), at }));
  } catch {
    // Storage blocked or full: the speed is simply not remembered.
  }
}

/** The remembered speed if it is from the last `REMEMBER_MS` (age 0 up to 599,999 ms), else null. */
export function recallSpeed(at = Date.now()) {
  let entry = null;
  try {
    entry = JSON.parse(sessionStorage.getItem(SPEED_KEY));
  } catch {
    return null; // blocked storage, or an entry that is not JSON
  }
  const age = at - entry?.at;
  const usable = Number.isFinite(entry?.kbps) && entry.kbps > 0 && age >= 0 && age < REMEMBER_MS;
  return usable ? entry.kbps : null;
}

export function forgetSpeed() {
  try {
    sessionStorage.removeItem(SPEED_KEY);
  } catch {
    // Storage blocked: nothing to forget.
  }
}

// How many times the network has changed (the window went offline or online). A speed measured before a change
// says nothing about the link after it, so callers that keep samples compare this counter with the one they saw.
let networkChanges = 0;

export const networkGeneration = () => networkChanges;

const onNetworkChange = () => {
  networkChanges += 1;
  forgetSpeed();
};
if (typeof window !== 'undefined') {
  window.addEventListener('offline', onNetworkChange);
  window.addEventListener('online', onNetworkChange);
}

/** Tests: the counter back to 0 and no remembered speed. */
export function resetThroughputState() {
  networkChanges = 0;
  forgetSpeed();
}

/** kbit/s of one loaded fragment from hls.js stats (bytes, and loading.first/end in ms), or null. */
export function fragmentKbps(stats) {
  const loading = stats?.loading;
  if (!loading || !stats.loaded) return null;
  const ms = loading.end - (loading.first || loading.start);
  return ms > 0 ? Math.round((stats.loaded * 8) / ms) : null;
}
