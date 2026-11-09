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

/** kbit/s of one loaded fragment from hls.js stats (bytes, and loading.first/end in ms), or null. */
export function fragmentKbps(stats) {
  const loading = stats?.loading;
  if (!loading || !stats.loaded) return null;
  const ms = loading.end - (loading.first || loading.start);
  return ms > 0 ? Math.round((stats.loaded * 8) / ms) : null;
}
