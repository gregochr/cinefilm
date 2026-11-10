import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client.js';
import { PREPARE_DELAY_MS, PREPARE_TRIES, createSession } from './createSession.js';

const created = { sessionId: 's1' };

describe('createSession', () => {
  it('returns the session when the first request works', async () => {
    const create = vi.fn().mockResolvedValue(created);
    await expect(createSession(create)).resolves.toBe(created);
    expect(create).toHaveBeenCalledTimes(1);
  });

  it('gives up at once on any failure for a film in one piece, 503 included', async () => {
    const wait = vi.fn();
    for (const status of [409, 404, 400, 503]) {
      const create = vi.fn().mockRejectedValue(new ApiError(status));
      await expect(createSession(create, { wait })).resolves.toBeNull();
      expect(create).toHaveBeenCalledTimes(1);
    }
    expect(wait).not.toHaveBeenCalled();
  });

  it('asks again after a 503 for a split film, a second apart, until it works', async () => {
    const create = vi
      .fn()
      .mockRejectedValueOnce(new ApiError(503))
      .mockRejectedValueOnce(new ApiError(503))
      .mockResolvedValue(created);
    const wait = vi.fn().mockResolvedValue(undefined);

    await expect(createSession(create, { retryWhenPreparing: true, wait })).resolves.toBe(created);

    expect(create).toHaveBeenCalledTimes(3);
    expect(wait).toHaveBeenCalledTimes(2);
    expect(wait).toHaveBeenCalledWith(PREPARE_DELAY_MS);
    expect(PREPARE_DELAY_MS).toBe(1000);
  });

  it('does not retry any other failure of a split film', async () => {
    const create = vi.fn().mockRejectedValue(new ApiError(409));
    const wait = vi.fn();

    await expect(createSession(create, { retryWhenPreparing: true, wait })).resolves.toBeNull();

    expect(create).toHaveBeenCalledTimes(1);
    expect(wait).not.toHaveBeenCalled();
  });

  it('does not retry a failure that is not an API error', async () => {
    const create = vi.fn().mockRejectedValue(new TypeError('offline'));
    await expect(createSession(create, { retryWhenPreparing: true })).resolves.toBeNull();
    expect(create).toHaveBeenCalledTimes(1);
  });

  it('stops after the given number of tries', async () => {
    const create = vi.fn().mockRejectedValue(new ApiError(503));
    const wait = vi.fn().mockResolvedValue(undefined);

    await expect(
      createSession(create, { retryWhenPreparing: true, tries: 4, wait }),
    ).resolves.toBeNull();

    expect(create).toHaveBeenCalledTimes(4);
    expect(wait).toHaveBeenCalledTimes(3);
    expect(PREPARE_TRIES).toBe(15);
  });

  it('stops waiting once the viewer has moved on', async () => {
    const create = vi.fn().mockRejectedValue(new ApiError(503));
    let cancelled = false;
    const wait = vi.fn().mockImplementation(async () => {
      cancelled = true;
    });

    await expect(
      createSession(create, { retryWhenPreparing: true, wait, isCancelled: () => cancelled }),
    ).resolves.toBeNull();

    expect(create).toHaveBeenCalledTimes(1);
  });

  it('really waits between tries with its own timer by default', async () => {
    vi.useFakeTimers();
    try {
      const create = vi.fn().mockRejectedValueOnce(new ApiError(503)).mockResolvedValue(created);
      const pending = createSession(create, { retryWhenPreparing: true, delayMs: 250 });
      await vi.advanceTimersByTimeAsync(249);
      expect(create).toHaveBeenCalledTimes(1);
      await vi.advanceTimersByTimeAsync(1);
      await expect(pending).resolves.toBe(created);
      expect(create).toHaveBeenCalledTimes(2);
    } finally {
      vi.useRealTimers();
    }
  });
});
