// The Auto start-rung probe: how fast can the original be fetched right now? Pure measurement, with the
// fetch and the clock injected so tests never touch the network or a real timer.

export const PROBE_MS = 1500;
/** How long the first byte may take (hls.js's own first-byte limit for a segment): a library disk may be asleep. */
export const FIRST_BYTE_MS = 10000;
/** With no first byte by now, ask whether the server answers at all. */
export const PROBE_CHECK_MS = 5000;

/**
 * Reads the start of `url` and returns the throughput in kbit/s: it waits up to `firstByteMs` for the first
 * byte, then counts for about `boxMs` (only the bytes after the first one, so latency does not pass for
 * slowness). Returns null when nothing could be measured: no first byte in time, a failed request, an empty
 * body or no byte after the first. With `checkReachable` (resolves whether the server answers), a probe with no
 * first byte after `checkMs` asks; no answer ends it at once and calls `onUnreachable()`. A check that answers
 * after the first byte changes nothing; with no first byte, a check still out when the probe ends is waited for.
 */
export async function measureThroughput(
  url,
  {
    fetchImpl = globalThis.fetch,
    now = () => performance.now(),
    boxMs = PROBE_MS,
    firstByteMs = FIRST_BYTE_MS,
    checkMs = PROBE_CHECK_MS,
    checkReachable,
    onUnreachable = () => {},
  } = {},
) {
  const controller = new AbortController();
  let waitTimer = setTimeout(() => controller.abort(), firstByteMs);
  let checkTimer = null;
  let inFlight = null; // the reachability check, once it has started
  let counting = false; // the first byte has arrived
  if (checkReachable) {
    checkTimer = setTimeout(() => {
      inFlight = (async () => {
        let answers = true;
        try {
          answers = await checkReachable();
        } catch {
          // A check that cannot tell is no reason to end the probe.
        }
        if (counting || answers) return;
        onUnreachable();
        controller.abort();
      })();
    }, checkMs);
  }
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
        counting = true;
        clearTimeout(waitTimer);
        clearTimeout(checkTimer);
        waitTimer = setTimeout(() => controller.abort(), boxMs);
      } else {
        bytes += value.byteLength;
        lastAt = at;
        if (at - firstAt >= boxMs) break;
      }
    }
  } catch {
    // Aborted by a time box, or the link failed: use what arrived.
  } finally {
    clearTimeout(waitTimer);
    clearTimeout(checkTimer);
    reader?.cancel().catch(() => {});
    controller.abort();
    // No first byte and a check still out (it started at 5 s, the first-byte limit is 10 s): let it say whether the
    // server is there, so "unreachable" is not lost. It is bounded by its own limit.
    if (!counting) await inFlight;
  }
  if (bytes === 0 || lastAt === null || lastAt <= firstAt) return null;
  return Math.round((bytes * 8) / (lastAt - firstAt));
}
