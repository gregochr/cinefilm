// The Auto start-rung probe: how fast can the original be fetched right now? Pure measurement, with the
// fetch and the clock injected so tests never touch the network or a real timer.

export const PROBE_MS = 1500;

/**
 * Reads the start of `url` for about `boxMs` and returns the throughput in kbit/s, counting only the bytes
 * that arrive after the first one (so latency does not pass for slowness). Returns null when nothing could
 * be measured: a failed request, an empty body or no byte after the first.
 */
export async function measureThroughput(
  url,
  { fetchImpl = globalThis.fetch, now = () => performance.now(), boxMs = PROBE_MS } = {},
) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), boxMs);
  let reader = null;
  let bytes = 0;
  let firstAt = null;
  let lastAt = null;
  try {
    const response = await fetchImpl(url, {
      headers: { Range: 'bytes=0-' },
      credentials: 'same-origin',
      signal: controller.signal,
    });
    if (!response.ok || !response.body) return null;
    reader = response.body.getReader();
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      const at = now();
      if (firstAt === null) {
        firstAt = at;
      } else {
        bytes += value.byteLength;
        lastAt = at;
        if (at - firstAt >= boxMs) break;
      }
    }
  } catch {
    // Aborted by the time box, or the link failed: use what arrived.
  } finally {
    clearTimeout(timer);
    reader?.cancel().catch(() => {});
    controller.abort();
  }
  if (bytes === 0 || lastAt === null || lastAt <= firstAt) return null;
  return Math.round((bytes * 8) / (lastAt - firstAt));
}
