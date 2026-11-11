import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, SIGNED_IN_EVENT } from '../api/client.js';
import { PREPARE_DELAY_MS, PREPARE_TRIES, createSession } from './createSession.js';
import { createWaiter } from './retry.js';

const created = { sessionId: 's1' };

describe('createSession', () => {
  it('returns the session when the first request works', async () => {
    const create = vi.fn().mockResolvedValue(created);
    await expect(createSession(create)).resolves.toBe(created);
    expect(create).toHaveBeenCalledTimes(1);
  });

  it('gives up at once on an answer that asking again cannot change, for a film in one piece', async () => {
    const wait = vi.fn();
    for (const status of [409, 404, 400]) {
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

  it('asks again after a failure that is not an API error, until it works', async () => {
    const create = vi
      .fn()
      .mockRejectedValueOnce(new TypeError('offline'))
      .mockResolvedValue(created);
    const wait = vi.fn().mockResolvedValue(undefined);
    await expect(createSession(create, { retryWhenPreparing: true, wait })).resolves.toBe(created);
    expect(create).toHaveBeenCalledTimes(2);
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

// What the server (or the link) can answer, and what asking again does about it. Plan: poor-link-plan.md, section 1.
describe('createSession: transient failures retry, refusals give up', () => {
  const quick = (over = {}) => ({
    wait: vi.fn().mockResolvedValue(undefined),
    untilSignedIn: vi.fn().mockResolvedValue(undefined),
    onRetrying: vi.fn(),
    onNoAnswer: vi.fn(),
    ...over,
  });
  const rejectsThenSession = (...errors) => {
    const create = vi.fn();
    errors.forEach((error) => create.mockRejectedValueOnce(error));
    return create.mockResolvedValue(created);
  };

  it('gives each attempt its own AbortSignal', async () => {
    const create = rejectsThenSession(new TypeError('offline'));
    await createSession(create, quick());
    const [first] = create.mock.calls[0];
    const [second] = create.mock.calls[1];
    expect(first).toBeInstanceOf(AbortSignal);
    expect(second).toBeInstanceOf(AbortSignal);
    expect(first).not.toBe(second);
  });

  it.each([400, 403, 404, 409, 410, 418])(
    'gives up at once on a %s: nothing asked again can change',
    async (status) => {
      const options = quick();
      const create = vi.fn().mockRejectedValue(new ApiError(status));
      await expect(createSession(create, options)).resolves.toBeNull();
      expect(create).toHaveBeenCalledTimes(1);
      expect(options.wait).not.toHaveBeenCalled();
      expect(options.onRetrying.mock.calls).toEqual([[false]]);
    },
  );

  it('asks once more after a 500, a second later, without the notice', async () => {
    const options = quick();
    const create = rejectsThenSession(new ApiError(500));
    await expect(createSession(create, options)).resolves.toBe(created);
    expect(create).toHaveBeenCalledTimes(2);
    expect(options.wait.mock.calls).toEqual([[1000]]);
    expect(options.onRetrying).not.toHaveBeenCalledWith(true);
    expect(options.onNoAnswer).not.toHaveBeenCalled();
  });

  it('gives up on a second 500', async () => {
    const options = quick();
    const create = vi.fn().mockRejectedValue(new ApiError(500));
    await expect(createSession(create, options)).resolves.toBeNull();
    expect(create).toHaveBeenCalledTimes(2);
    expect(options.wait).toHaveBeenCalledTimes(1);
  });

  it('turns the notice off when a 500 shows the server is there', async () => {
    const options = quick();
    const create = rejectsThenSession(new TypeError('offline'), new ApiError(500));
    await createSession(create, options);
    expect(options.onRetrying.mock.calls).toEqual([[true], [false]]);
  });

  it('asks again without limit when there is no answer: backoff 1, 2, 4, 8 s, then every 8 s', async () => {
    const options = quick();
    const create = vi.fn();
    for (let i = 0; i < 20; i += 1) create.mockRejectedValueOnce(new TypeError('offline'));
    create.mockResolvedValue(created);
    await expect(createSession(create, options)).resolves.toBe(created);
    expect(create).toHaveBeenCalledTimes(21);
    const waits = options.wait.mock.calls.map(([ms]) => ms);
    expect(waits.slice(0, 6)).toEqual([1000, 2000, 4000, 8000, 8000, 8000]);
    expect(new Set(waits.slice(3))).toEqual(new Set([8000]));
    expect(options.onNoAnswer).toHaveBeenCalledTimes(20);
    expect(options.onNoAnswer).toHaveBeenCalledWith({ timeout: false });
    expect(options.onRetrying.mock.calls).toEqual([[true], [false]]);
  });

  it.each([502, 503, 504, 408, 429])(
    'asks a %s again without limit (a proxy whose app is down), under the notice',
    async (status) => {
      const options = quick();
      const create = vi.fn();
      for (let i = 0; i < 20; i += 1) create.mockRejectedValueOnce(new ApiError(status));
      create.mockResolvedValue(created);
      await expect(createSession(create, options)).resolves.toBe(created);
      expect(create).toHaveBeenCalledTimes(21);
      expect(options.onRetrying.mock.calls).toEqual([[true], [false]]);
      expect(options.wait.mock.calls[0]).toEqual([1000]);
      // An answer with a status, even a proxy's, is not a request that got no answer: nothing is reported.
      expect(options.onNoAnswer).not.toHaveBeenCalled();
    },
  );

  it('says the notice is off at the end even when it never said on', async () => {
    const options = quick();
    await createSession(vi.fn().mockResolvedValue(created), options);
    expect(options.onRetrying.mock.calls).toEqual([[false]]);
  });

  it('a split film: a network failure then 503 turns the notice off and starts the preparing loop', async () => {
    const options = quick();
    const create = rejectsThenSession(new TypeError('offline'), new ApiError(503));
    await expect(createSession(create, { ...options, retryWhenPreparing: true })).resolves.toBe(
      created,
    );
    expect(options.onRetrying.mock.calls).toEqual([[true], [false]]);
    expect(options.wait.mock.calls).toEqual([[1000], [PREPARE_DELAY_MS]]);
  });

  it('a split film: the notice is already off while it waits to ask again about the preparing 503', async () => {
    const options = quick();
    const seen = [];
    options.wait = vi.fn(async (ms) => {
      seen.push([ms, options.onRetrying.mock.calls.at(-1)?.[0] ?? null]);
    });
    const create = rejectsThenSession(new TypeError('offline'), new ApiError(503));
    await createSession(create, { ...options, retryWhenPreparing: true });
    expect(seen).toEqual([
      [1000, true],
      [PREPARE_DELAY_MS, false],
    ]);
  });

  it('treats a rejection with nothing in it as a request that got no answer', async () => {
    const options = quick();
    const create = vi.fn().mockRejectedValueOnce(undefined).mockResolvedValue(created);
    await expect(createSession(create, options)).resolves.toBe(created);
    expect(options.onNoAnswer).toHaveBeenCalledTimes(1);
  });

  it('a 401 waits for the sign-in with no notice and nothing counted, then asks at once', async () => {
    const options = quick();
    const create = rejectsThenSession(new ApiError(401));
    await expect(createSession(create, options)).resolves.toBe(created);
    expect(options.untilSignedIn).toHaveBeenCalledTimes(1);
    expect(options.wait).not.toHaveBeenCalled();
    expect(options.onRetrying).not.toHaveBeenCalledWith(true);
    expect(options.onNoAnswer).not.toHaveBeenCalled();
    expect(create).toHaveBeenCalledTimes(2);
  });

  it('a 401 after a failure turns the notice off while it waits for the sign-in', async () => {
    const options = quick();
    const create = rejectsThenSession(new TypeError('offline'), new ApiError(401));
    await createSession(create, options);
    expect(options.onRetrying.mock.calls).toEqual([[true], [false]]);
  });

  it('a sign-in that came while the request was in flight ends the wait for it at once', async () => {
    const waiter = createWaiter();
    try {
      let reject;
      const create = vi
        .fn()
        .mockImplementationOnce(() => new Promise((_, no) => (reject = no)))
        .mockResolvedValue(created);
      const pending = createSession(create, {
        wait: waiter.wait,
        untilSignedIn: waiter.untilSignedIn,
      });
      window.dispatchEvent(new CustomEvent(SIGNED_IN_EVENT));
      reject(new ApiError(401));
      await expect(pending).resolves.toBe(created);
    } finally {
      waiter.dispose();
    }
  });

  it('returns null without a callback when cancelled during a wait, and asks no more', async () => {
    let cancelled = false;
    const options = quick({ isCancelled: () => cancelled });
    options.wait = vi.fn().mockImplementation(async () => {
      cancelled = true;
    });
    const create = vi.fn().mockRejectedValue(new TypeError('offline'));
    await expect(createSession(create, options)).resolves.toBeNull();
    expect(create).toHaveBeenCalledTimes(1);
    // Only what came before the cancellation: the failure, and the notice on.
    expect(options.onNoAnswer).toHaveBeenCalledTimes(1);
    expect(options.onRetrying.mock.calls).toEqual([[true]]);
  });

  it('returns null without a callback when cancelled during an attempt that then fails', async () => {
    let cancelled = false;
    const options = quick({ isCancelled: () => cancelled });
    let reject;
    const create = vi.fn(() => new Promise((_, no) => (reject = no)));
    const pending = createSession(create, options);
    cancelled = true;
    reject(new TypeError('offline'));
    await expect(pending).resolves.toBeNull();
    expect(options.onNoAnswer).not.toHaveBeenCalled();
    expect(options.onRetrying).not.toHaveBeenCalled();
    expect(options.wait).not.toHaveBeenCalled();
  });

  it('still returns a session that arrives after cancellation, so the caller can delete it', async () => {
    const options = quick({ isCancelled: () => true });
    await expect(createSession(vi.fn().mockResolvedValue(created), options)).resolves.toBe(created);
    expect(options.onRetrying).not.toHaveBeenCalled();
  });

  it('returns null when cancelled during the sign-in wait', async () => {
    let cancelled = false;
    const options = quick({ isCancelled: () => cancelled });
    options.untilSignedIn = vi.fn().mockImplementation(async () => {
      cancelled = true;
    });
    const create = vi.fn().mockRejectedValue(new ApiError(401));
    await expect(createSession(create, options)).resolves.toBeNull();
    expect(create).toHaveBeenCalledTimes(1);
  });
});

describe('createSession: a request that seems lost is checked, not timed out', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  // A request that stays pending until aborted (rejects as fetch does) or resolved by the test.
  const pendingRequest = () => {
    const state = { signal: null, resolve: null };
    const request = (signal) =>
      new Promise((resolve, reject) => {
        state.signal = signal;
        state.resolve = resolve;
        signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
      });
    return { state, request };
  };
  const options = (over = {}) => ({
    wait: vi.fn().mockResolvedValue(undefined),
    onRetrying: vi.fn(),
    onNoAnswer: vi.fn(),
    reachable: vi.fn().mockResolvedValue(true),
    ...over,
  });

  it('checks a pending attempt at 5,000 ms and not at 4,999, then at 20,000 and not at 19,999', async () => {
    const opts = options();
    const { state, request } = pendingRequest();
    const pending = createSession(request, opts);
    await vi.advanceTimersByTimeAsync(4999);
    expect(opts.reachable).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1);
    expect(opts.reachable).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(14999);
    expect(opts.reachable).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(opts.reachable).toHaveBeenCalledTimes(2);
    // A server that answers leaves the attempt alone: no abort, no notice.
    expect(state.signal.aborted).toBe(false);
    expect(opts.onRetrying).not.toHaveBeenCalled();
    state.resolve(created);
    await expect(pending).resolves.toBe(created);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('aborts the attempt when no answer comes, reports one timeout, and asks again', async () => {
    const opts = options({ reachable: vi.fn().mockResolvedValue(false) });
    const first = pendingRequest();
    const create = vi
      .fn()
      .mockImplementationOnce(first.request)
      .mockImplementationOnce(() => Promise.resolve(created));
    const pending = createSession(create, opts);
    await vi.advanceTimersByTimeAsync(5000);
    await expect(pending).resolves.toBe(created);
    expect(first.state.signal.aborted).toBe(true);
    expect(opts.onNoAnswer.mock.calls).toEqual([[{ timeout: true }]]);
    expect(opts.onRetrying.mock.calls).toEqual([[true], [false]]);
    expect(opts.wait.mock.calls).toEqual([[1000]]);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('ignores a check still pending when its attempt succeeds, whatever it then says', async () => {
    let answer;
    const opts = options({ reachable: vi.fn(() => new Promise((resolve) => (answer = resolve))) });
    const { state, request } = pendingRequest();
    const create = vi.fn(request);
    const pending = createSession(create, opts);
    await vi.advanceTimersByTimeAsync(5000);
    expect(opts.reachable).toHaveBeenCalledTimes(1);
    state.resolve(created);
    await expect(pending).resolves.toBe(created);
    answer(false);
    await vi.advanceTimersByTimeAsync(0);
    expect(state.signal.aborted).toBe(false);
    expect(opts.onNoAnswer).not.toHaveBeenCalled();
    expect(create).toHaveBeenCalledTimes(1);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('after cancellation the checks go on: an unreachable server aborts the attempt, silently, with no further attempt', async () => {
    let cancelled = false;
    const opts = options({
      isCancelled: () => cancelled,
      reachable: vi.fn().mockResolvedValue(false),
    });
    const { state, request } = pendingRequest();
    const create = vi.fn(request);
    const pending = createSession(create, opts);
    cancelled = true;
    await vi.advanceTimersByTimeAsync(5000);
    expect(state.signal.aborted).toBe(true);
    await expect(pending).resolves.toBeNull();
    expect(create).toHaveBeenCalledTimes(1);
    expect(opts.onNoAnswer).not.toHaveBeenCalled();
    expect(opts.onRetrying).not.toHaveBeenCalled();
    expect(opts.wait).not.toHaveBeenCalled();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('after cancellation a server that answers leaves the attempt alone, and its session is returned', async () => {
    let cancelled = false;
    const opts = options({ isCancelled: () => cancelled });
    const { state, request } = pendingRequest();
    const pending = createSession(request, opts);
    cancelled = true;
    await vi.advanceTimersByTimeAsync(20000);
    expect(opts.reachable).toHaveBeenCalledTimes(2); // 5,000 and 20,000 ms
    expect(state.signal.aborted).toBe(false);
    state.resolve(created);
    await expect(pending).resolves.toBe(created);
    expect(opts.onNoAnswer).not.toHaveBeenCalled();
    expect(opts.onRetrying).not.toHaveBeenCalled();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('makes no check without a reachable()', async () => {
    const { state, request } = pendingRequest();
    const pending = createSession(request, { wait: vi.fn() });
    await vi.advanceTimersByTimeAsync(60000);
    state.resolve(created);
    await expect(pending).resolves.toBe(created);
  });
});
