import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { measureThroughput } from './probe.js';

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

  it('stops reading once the time box has passed and cancels the body', async () => {
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

  it('aborts a stalled body at the time box and uses what arrived', async () => {
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
