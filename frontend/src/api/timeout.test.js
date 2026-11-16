import { afterEach, describe, expect, it, vi } from 'vitest';
import { ADMIN_TIMEOUT_MS, RequestTimeout, withTimeout } from './timeout.js';

afterEach(() => vi.useRealTimers());

describe('withTimeout', () => {
  it('resolves with the answer and clears its timer', async () => {
    vi.useFakeTimers();

    await expect(withTimeout(async () => 'ok', 1000)).resolves.toBe('ok');

    expect(vi.getTimerCount()).toBe(0);
  });

  it('passes on the failure of the call itself', async () => {
    await expect(
      withTimeout(async () => {
        throw new Error('boom');
      }, 1000),
    ).rejects.toThrow('boom');
  });

  it('rejects with RequestTimeout and aborts the request at the limit, not before', async () => {
    vi.useFakeTimers();
    let signal;
    const outcome = withTimeout((s) => {
      signal = s;
      return new Promise(() => {});
    }, 1000).catch((error) => error);

    await vi.advanceTimersByTimeAsync(999);
    expect(signal.aborted).toBe(false);
    await vi.advanceTimersByTimeAsync(1);

    expect(await outcome).toBeInstanceOf(RequestTimeout);
    expect(signal.aborted).toBe(true);
  });

  it('defaults to the admin limit', async () => {
    vi.useFakeTimers();
    const outcome = withTimeout(() => new Promise(() => {})).catch((error) => error);

    await vi.advanceTimersByTimeAsync(ADMIN_TIMEOUT_MS);

    expect(await outcome).toBeInstanceOf(RequestTimeout);
  });
});
