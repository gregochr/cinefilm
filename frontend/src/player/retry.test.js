import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, SIGNED_IN_EVENT } from '../api/client.js';
import { backoffMs, createWaiter, reachable } from './retry.js';

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

const online = () => window.dispatchEvent(new Event('online'));
const signedIn = () => window.dispatchEvent(new CustomEvent(SIGNED_IN_EVENT));

// Resolves a promise's state without waiting on it: 'pending' until it settles.
async function settled(promise) {
  let state = 'pending';
  void promise.then(() => {
    state = 'resolved';
  });
  await vi.advanceTimersByTimeAsync(0);
  return state;
}

describe('backoffMs', () => {
  it('doubles from a second to eight seconds and stays there', () => {
    expect([0, 1, 2, 3, 4, 5].map(backoffMs)).toEqual([1000, 2000, 4000, 8000, 8000, 8000]);
  });
});

describe('createWaiter: wait', () => {
  it('resolves at ms and not at ms - 1', async () => {
    const waiter = createWaiter();
    const done = waiter.wait(2000);
    await vi.advanceTimersByTimeAsync(1999);
    expect(await settled(done)).toBe('pending');
    await vi.advanceTimersByTimeAsync(1);
    expect(await settled(done)).toBe('resolved');
    waiter.dispose();
    expect(vi.getTimerCount()).toBe(0);
  });

  it.each([
    ['online', online],
    ['a sign-in', signedIn],
  ])('resolves at once on %s heard during the wait', async (_name, wake) => {
    const waiter = createWaiter();
    const done = waiter.wait(60000);
    wake();
    expect(await settled(done)).toBe('resolved');
    expect(vi.getTimerCount()).toBe(0);
    waiter.dispose();
  });

  it.each([
    ['online', online],
    ['a sign-in', signedIn],
  ])('resolves at once when %s came before it, since the last wait began', async (_name, wake) => {
    const waiter = createWaiter();
    wake();
    expect(await settled(waiter.wait(60000))).toBe('resolved');
    // That wake-up is spent: the next wait is a real one.
    const next = waiter.wait(60000);
    expect(await settled(next)).toBe('pending');
    waiter.dispose();
  });

  it('does not count a wake-up that ended an earlier wait against the next one', async () => {
    const waiter = createWaiter();
    const first = waiter.wait(60000);
    online();
    await first;
    expect(await settled(waiter.wait(60000))).toBe('pending');
    waiter.dispose();
  });

  it('hears a wake-up that comes after a wait ended on its own timer', async () => {
    const waiter = createWaiter();
    const done = waiter.wait(1000);
    await vi.advanceTimersByTimeAsync(1000);
    await done;
    online(); // no wait is pending: remembered for the next one
    expect(await settled(waiter.wait(60000))).toBe('resolved');
    waiter.dispose();
  });

  it('resolves at once when the signal aborts, and when it is already aborted', async () => {
    const waiter = createWaiter();
    const controller = new AbortController();
    const done = waiter.wait(60000, { signal: controller.signal });
    expect(await settled(done)).toBe('pending');
    controller.abort();
    expect(await settled(done)).toBe('resolved');
    expect(vi.getTimerCount()).toBe(0);
    expect(await settled(waiter.wait(60000, { signal: controller.signal }))).toBe('resolved');
    waiter.dispose();
  });

  it('leaves no timer or listener behind once disposed', async () => {
    const waiter = createWaiter();
    const done = waiter.wait(60000);
    waiter.dispose();
    expect(await settled(done)).toBe('resolved'); // a disposed waiter lets a pending wait go
    expect(vi.getTimerCount()).toBe(0);
    online();
    signedIn();
    // A later wake-up does nothing: the next wait on the disposed waiter is not flagged, and returns at once.
    expect(await settled(waiter.wait(60000))).toBe('resolved');
    expect(vi.getTimerCount()).toBe(0);
  });

  it('removes its listeners when disposed', () => {
    const add = vi.spyOn(window, 'addEventListener');
    const remove = vi.spyOn(window, 'removeEventListener');
    const waiter = createWaiter();
    waiter.dispose();
    const names = (spy) => spy.mock.calls.map(([name]) => name).sort();
    expect(names(add)).toEqual(['online', SIGNED_IN_EVENT].sort());
    expect(names(remove)).toEqual(names(add));
    add.mockRestore();
    remove.mockRestore();
  });
});

describe('createWaiter: untilSignedIn', () => {
  it('resolves on a sign-in only, not on online', async () => {
    const waiter = createWaiter();
    const done = waiter.untilSignedIn();
    online();
    expect(await settled(done)).toBe('pending');
    signedIn();
    expect(await settled(done)).toBe('resolved');
    waiter.dispose();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('resolves at once for a sign-in that came before it, once', async () => {
    const waiter = createWaiter();
    signedIn();
    expect(await settled(waiter.untilSignedIn())).toBe('resolved');
    expect(await settled(waiter.untilSignedIn())).toBe('pending');
    waiter.dispose();
  });

  it('does not take an online that came before it for a sign-in', async () => {
    const waiter = createWaiter();
    online();
    expect(await settled(waiter.untilSignedIn())).toBe('pending');
    waiter.dispose();
  });

  it('resolves when the signal aborts', async () => {
    const waiter = createWaiter();
    const controller = new AbortController();
    const done = waiter.untilSignedIn({ signal: controller.signal });
    controller.abort();
    expect(await settled(done)).toBe('resolved');
    waiter.dispose();
  });

  it('resolves when disposed', async () => {
    const waiter = createWaiter();
    const done = waiter.untilSignedIn();
    waiter.dispose();
    expect(await settled(done)).toBe('resolved');
  });
});

describe('reachable', () => {
  const answer = (check, timeoutMs = 5000) => reachable(check, { timeoutMs });

  it('is true for a resolved check, and passes its signal to it', async () => {
    const check = vi.fn().mockResolvedValue(null);
    await expect(answer(check)).resolves.toBe(true);
    expect(check.mock.calls[0][0]).toBeInstanceOf(AbortSignal);
    expect(vi.getTimerCount()).toBe(0);
  });

  it.each([401, 404, 500])('is true for an ApiError %s: the app answered', async (status) => {
    await expect(answer(vi.fn().mockRejectedValue(new ApiError(status)))).resolves.toBe(true);
  });

  it.each([502, 503, 504])('is false for an ApiError %s: a proxy answered', async (status) => {
    await expect(answer(vi.fn().mockRejectedValue(new ApiError(status)))).resolves.toBe(false);
  });

  it('is false for a TypeError (the fetch itself failed)', async () => {
    await expect(answer(vi.fn().mockRejectedValue(new TypeError('offline')))).resolves.toBe(false);
  });

  it('is false for a check that throws at once', async () => {
    const check = vi.fn(() => {
      throw new Error('broken');
    });
    await expect(answer(check)).resolves.toBe(false);
  });

  it('is false when nothing answers within the limit: at the limit, not 1 ms before', async () => {
    let signal;
    const done = answer(
      vi.fn((s) => {
        signal = s;
        return new Promise(() => {});
      }),
    );
    await vi.advanceTimersByTimeAsync(4999);
    expect(await settled(done)).toBe('pending');
    expect(signal.aborted).toBe(false);
    await vi.advanceTimersByTimeAsync(1);
    await expect(done).resolves.toBe(false);
    expect(signal.aborted).toBe(true);
    expect(vi.getTimerCount()).toBe(0);
  });
});
