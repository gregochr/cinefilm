import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FIRST_BYTE_MS, PROBE_CHECK_MS, PROBE_MS, measureThroughput } from './probe.js';

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

// A response whose body hands out `chunks` (byte counts) one read at a time.
function bodyOf(chunks, { hang = false } = {}) {
  let i = 0;
  const cancel = vi.fn(async () => {});
  const response = { ok: true, cancel, signal: null };
  const reader = {
    read: async () => {
      if (i < chunks.length) return { done: false, value: new Uint8Array(chunks[i++]) };
      // A stalled link: the read only ends when the request is aborted, as a real body's does.
      if (hang) {
        return new Promise((_, reject) =>
          response.signal.addEventListener('abort', () => reject(new Error('aborted'))),
        );
      }
      return { done: true };
    },
    cancel,
  };
  response.body = { getReader: () => reader };
  return response;
}

// A clock that returns the next value each time it is read.
const clockOf = (...times) => {
  let i = 0;
  return () => times[Math.min(i++, times.length - 1)];
};

describe('measureThroughput', () => {
  it('counts only the bytes after the first one, over the time between first and last', async () => {
    const response = bodyOf([1000, 125_000, 125_000]);
    const fetchImpl = vi.fn(async () => response);
    // first chunk at t=100 (ignored); then 125 kB at t=200; 125 kB at t=300
    const kbps = await measureThroughput('/api/editions/11/stream', {
      fetchImpl,
      now: clockOf(100, 200, 300),
    });
    // 250,000 bytes = 2,000 kbit over 200 ms = 10,000 kbit/s
    expect(kbps).toBe(10000);
  });

  it('asks for a range of the direct-play URL with the session cookie', async () => {
    const fetchImpl = vi.fn(async () => bodyOf([10, 10]));
    await measureThroughput('/api/editions/11/stream', { fetchImpl, now: clockOf(0, 1) });
    expect(fetchImpl).toHaveBeenCalledWith('/api/editions/11/stream', {
      headers: { Range: 'bytes=0-' },
      credentials: 'same-origin',
      signal: expect.any(AbortSignal),
    });
  });

  it('stops reading once the time box (counted from the first byte) has passed and cancels the body', async () => {
    const response = bodyOf([10, 1000, 1000, 1000]);
    // first at 0; the next chunk at 1500 reaches the 1.5 s box, so the third is never read
    const kbps = await measureThroughput('/s', {
      fetchImpl: async () => response,
      now: clockOf(0, 1500, 9999),
      boxMs: 1500,
    });
    expect(kbps).toBe(5); // 1000 bytes = 8 kbit over 1500 ms
    expect(response.cancel).toHaveBeenCalledTimes(1);
  });

  it('aborts a stalled body 1.5 s after its first byte and uses what arrived', async () => {
    const response = bodyOf([10, 3000], { hang: true });
    const fetchImpl = vi.fn(async (url, { signal }) => {
      response.signal = signal;
      return response;
    });
    const pending = measureThroughput('/s', { fetchImpl, now: clockOf(0, 100), boxMs: 1500 });
    await vi.advanceTimersByTimeAsync(1500);
    expect(await pending).toBe(240); // 3000 bytes = 24 kbit over 100 ms
  });

  it('is null when only one chunk arrives (nothing after the first byte)', async () => {
    const kbps = await measureThroughput('/s', {
      fetchImpl: async () => bodyOf([5000]),
      now: clockOf(0, 10),
    });
    expect(kbps).toBeNull();
  });

  it('is null when the chunks arrive at the same instant (no elapsed time to divide by)', async () => {
    const kbps = await measureThroughput('/s', {
      fetchImpl: async () => bodyOf([10, 10]),
      now: clockOf(5, 5),
    });
    expect(kbps).toBeNull();
  });

  it('is null for an error status or a missing body', async () => {
    expect(await measureThroughput('/s', { fetchImpl: async () => ({ ok: false }) })).toBeNull();
    expect(
      await measureThroughput('/s', { fetchImpl: async () => ({ ok: true, body: null }) }),
    ).toBeNull();
  });

  it('is null when the request fails', async () => {
    const fetchImpl = async () => {
      throw new TypeError('offline');
    };
    expect(await measureThroughput('/s', { fetchImpl })).toBeNull();
  });

  it('does not leave its timer running', async () => {
    await measureThroughput('/s', { fetchImpl: async () => bodyOf([1, 1]), now: clockOf(0, 1) });
    expect(vi.getTimerCount()).toBe(0);
  });
});

// A body whose chunks arrive at the given times ([[ms after the request, bytes], ...], on fake time); after the
// last it hangs until aborted, as a stalled link does. Aborting rejects the read and clears its timer.
function timedBody(schedule, t) {
  return async (url, { signal }) => {
    t.signal = signal;
    const start = Date.now();
    let i = 0;
    const reader = {
      read: () =>
        new Promise((resolve, reject) => {
          let timer = null;
          const abort = () => {
            clearTimeout(timer);
            reject(new Error('aborted'));
          };
          signal.addEventListener('abort', abort);
          if (signal.aborted) return abort();
          if (i >= schedule.length) return undefined;
          const [at, bytes] = schedule[i++];
          timer = setTimeout(
            () => {
              signal.removeEventListener('abort', abort);
              resolve({ done: false, value: new Uint8Array(bytes) });
            },
            Math.max(0, start + at - Date.now()),
          );
          return undefined;
        }),
      cancel: async () => {},
    };
    return { ok: true, body: { getReader: () => reader } };
  };
}
const clock = () => Date.now();

describe('the first byte may take 10 s, and the 1.5 s box starts there', () => {
  it('has the constants the plan gives', () => {
    expect([FIRST_BYTE_MS, PROBE_MS, PROBE_CHECK_MS]).toEqual([10000, 1500, 5000]);
  });

  it('measures a first byte at 9,999 ms for the 1,500 ms after it', async () => {
    const t = {};
    // first byte at 9,999; 125 kB at 11,000 (1,001 ms after it); another 125 kB at 11,498 (1,499 ms after it)
    const pending = measureThroughput('/s', {
      fetchImpl: timedBody(
        [
          [9999, 10],
          [11000, 125000],
          [11498, 125000],
        ],
        t,
      ),
      now: clock,
    });
    await vi.advanceTimersByTimeAsync(11498);
    expect(t.signal.aborted).toBe(false);
    await vi.advanceTimersByTimeAsync(1); // 11,499: the box (1,500 ms after the first byte) ends it
    await vi.advanceTimersByTimeAsync(1);
    expect(t.signal.aborted).toBe(true);
    // 250,000 bytes = 2,000 kbit over 1,499 ms
    expect(await pending).toBe(Math.round(2000000 / 1499));
    expect(vi.getTimerCount()).toBe(0);
  });

  it('is null when no first byte comes by 10,000 ms', async () => {
    const t = {};
    const pending = measureThroughput('/s', { fetchImpl: timedBody([[10000, 10]], t), now: clock });
    await vi.advanceTimersByTimeAsync(9999);
    expect(t.signal.aborted).toBe(false);
    await vi.advanceTimersByTimeAsync(1);
    expect(t.signal.aborted).toBe(true);
    expect(await pending).toBeNull();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('does not start the 1,500 ms box with the request', async () => {
    const t = {};
    const pending = measureThroughput('/s', { fetchImpl: timedBody([[2000, 10]], t), now: clock });
    await vi.advanceTimersByTimeAsync(1999);
    expect(t.signal.aborted).toBe(false); // the old box (from the request) would have fired at 1,500
    await vi.advanceTimersByTimeAsync(1); // the first byte arrives: the box starts now
    await vi.advanceTimersByTimeAsync(1499);
    expect(t.signal.aborted).toBe(false);
    await vi.advanceTimersByTimeAsync(1);
    expect(t.signal.aborted).toBe(true);
    expect(await pending).toBeNull(); // only the first byte arrived
    expect(vi.getTimerCount()).toBe(0);
  });
});

describe('a probe with no first byte asks whether the server answers', () => {
  const stuck = (t) => timedBody([[60000, 10]], t);

  it('makes no check when the first byte came at 4,999 ms', async () => {
    const t = {};
    const checkReachable = vi.fn().mockResolvedValue(true);
    const pending = measureThroughput('/s', {
      fetchImpl: timedBody([[4999, 10]], t),
      now: clock,
      checkReachable,
    });
    await vi.advanceTimersByTimeAsync(5001);
    expect(checkReachable).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1500);
    expect(await pending).toBeNull();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('checks at 5,000 ms and not before, when there is still no first byte', async () => {
    const t = {};
    const checkReachable = vi.fn().mockResolvedValue(true);
    const pending = measureThroughput('/s', { fetchImpl: stuck(t), now: clock, checkReachable });
    await vi.advanceTimersByTimeAsync(4999);
    expect(checkReachable).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1);
    expect(checkReachable).toHaveBeenCalledTimes(1);
    // Reachable: the probe waits on to 10,000 ms.
    expect(t.signal.aborted).toBe(false);
    await vi.advanceTimersByTimeAsync(4999);
    expect(t.signal.aborted).toBe(false);
    await vi.advanceTimersByTimeAsync(1);
    expect(t.signal.aborted).toBe(true);
    expect(await pending).toBeNull();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('ends at once with nothing measured, and says so once, when the server cannot be reached', async () => {
    const t = {};
    const onUnreachable = vi.fn();
    const pending = measureThroughput('/s', {
      fetchImpl: stuck(t),
      now: clock,
      checkReachable: vi.fn().mockResolvedValue(false),
      onUnreachable,
    });
    await vi.advanceTimersByTimeAsync(5000);
    expect(onUnreachable).toHaveBeenCalledTimes(1);
    expect(t.signal.aborted).toBe(true);
    expect(await pending).toBeNull();
    await vi.advanceTimersByTimeAsync(20000);
    expect(onUnreachable).toHaveBeenCalledTimes(1);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('changes nothing when the check answers "unreachable" after the first byte arrived', async () => {
    const t = {};
    let answer;
    const onUnreachable = vi.fn();
    const pending = measureThroughput('/s', {
      fetchImpl: timedBody(
        [
          [5500, 10],
          [5600, 125000],
          [5700, 125000],
        ],
        t,
      ),
      now: clock,
      checkReachable: vi.fn(() => new Promise((resolve) => (answer = resolve))),
      onUnreachable,
    });
    await vi.advanceTimersByTimeAsync(5000); // the check starts, no answer yet
    await vi.advanceTimersByTimeAsync(700); // the first byte, then the rest, arrive
    answer(false);
    await vi.advanceTimersByTimeAsync(0);
    expect(onUnreachable).not.toHaveBeenCalled();
    expect(t.signal.aborted).toBe(false);
    await vi.advanceTimersByTimeAsync(1500); // the box ends
    expect(await pending).toBe(10000); // 250,000 bytes over 200 ms
    expect(vi.getTimerCount()).toBe(0);
  });

  it('with no first byte, waits for a check still out at 10,000 ms and calls onUnreachable if it says no', async () => {
    const t = {};
    let answer;
    const onUnreachable = vi.fn();
    const pending = measureThroughput('/s', {
      fetchImpl: stuck(t),
      now: clock,
      checkReachable: vi.fn(() => new Promise((resolve) => (answer = resolve))),
      onUnreachable,
    });
    await vi.advanceTimersByTimeAsync(10000); // the first-byte limit ends the probe; the check is still out
    expect(onUnreachable).not.toHaveBeenCalled();
    answer(false);
    expect(await pending).toBeNull();
    expect(onUnreachable).toHaveBeenCalledTimes(1);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('with no first byte, a check still out at 10,000 ms that says yes calls nothing', async () => {
    const t = {};
    let answer;
    const onUnreachable = vi.fn();
    const pending = measureThroughput('/s', {
      fetchImpl: stuck(t),
      now: clock,
      checkReachable: vi.fn(() => new Promise((resolve) => (answer = resolve))),
      onUnreachable,
    });
    await vi.advanceTimersByTimeAsync(10000);
    answer(true);
    expect(await pending).toBeNull();
    expect(onUnreachable).not.toHaveBeenCalled();
  });

  it('treats a check that throws as an answer: the probe waits on', async () => {
    const t = {};
    const onUnreachable = vi.fn();
    const pending = measureThroughput('/s', {
      fetchImpl: stuck(t),
      now: clock,
      checkReachable: vi.fn().mockRejectedValue(new Error('broken')),
      onUnreachable,
    });
    await vi.advanceTimersByTimeAsync(5000);
    expect(t.signal.aborted).toBe(false);
    expect(onUnreachable).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(5000);
    expect(await pending).toBeNull();
  });

  it('just waits the 10 s with no checkReachable', async () => {
    const t = {};
    const pending = measureThroughput('/s', { fetchImpl: stuck(t), now: clock });
    await vi.advanceTimersByTimeAsync(9999);
    expect(t.signal.aborted).toBe(false);
    await vi.advanceTimersByTimeAsync(1);
    expect(await pending).toBeNull();
  });
});

describe('what the probe leaves behind', () => {
  it('reads performance.now by default', async () => {
    const times = [100, 200, 300];
    vi.spyOn(performance, 'now').mockImplementation(() => times.shift());
    const kbps = await measureThroughput('/s', {
      fetchImpl: async () => bodyOf([1000, 125_000, 125_000]),
    });
    expect(kbps).toBe(10000);
    vi.restoreAllMocks();
  });

  it('arms no reachability check timer without a checkReachable: only the first-byte limit', async () => {
    const t = {};
    const pending = measureThroughput('/s', { fetchImpl: timedBody([[60000, 10]], t), now: clock });
    expect(vi.getTimerCount()).toBe(1);
    await vi.advanceTimersByTimeAsync(10000);
    expect(await pending).toBeNull();
  });

  it('clears a pending check timer when the request fails at once', async () => {
    const fetchImpl = async () => {
      throw new TypeError('offline');
    };
    const checkReachable = vi.fn();
    expect(await measureThroughput('/s', { fetchImpl, checkReachable })).toBeNull();
    expect(vi.getTimerCount()).toBe(0);
    await vi.advanceTimersByTimeAsync(20000);
    expect(checkReachable).not.toHaveBeenCalled();
  });

  it('aborts its own request when it is done, even after reading the body to the end', async () => {
    let signal;
    const response = bodyOf([10, 10]);
    await measureThroughput('/s', {
      fetchImpl: async (_url, init) => {
        signal = init.signal;
        return response;
      },
      now: clockOf(0, 1),
    });
    expect(signal.aborted).toBe(true);
  });
});
