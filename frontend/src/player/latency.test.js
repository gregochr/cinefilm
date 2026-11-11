import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  ENTRY_WAIT_MS,
  FIXTURE_LATENCY_MS,
  LATENCY_PATH,
  LATENCY_SAMPLES,
  LATENCY_TIMEOUT_MS,
  RETRY_PAUSES_MS,
  measureLatency,
  roundTrip,
} from './latency.js';

const BASE = 'https://cine.test/watch/1';
const URL = 'https://cine.test/api/session';

// A PerformanceObserver as far as the measurement uses it: it records what it is told to observe and what it
// is asked for, and a test hands entries to it by `takeRecords` or by its callback.
class FakeObserver {
  static instances = [];
  static observeFails = false;
  static constructFails = false;
  constructor(callback) {
    if (FakeObserver.constructFails) throw new Error('no observer');
    this.callback = callback;
    this.queued = [];
    this.disconnected = false;
    FakeObserver.instances.push(this);
  }
  observe(options) {
    if (FakeObserver.observeFails) throw new Error('no resource entries');
    this.options = options;
  }
  takeRecords() {
    const taken = this.queued;
    this.queued = [];
    return taken;
  }
  disconnect() {
    this.disconnected = true;
  }
  deliver(entries) {
    this.callback({ getEntries: () => entries });
  }
}

const entry = (ms, { name = URL, requestStart = 100 } = {}) => ({
  name,
  requestStart,
  responseStart: requestStart + ms,
});

// One step per request: { rt } is found by takeRecords as soon as the body is read, { late, at } is delivered
// to the callback `at` ms later, { none } answers with no entry, { fail } rejects, { hang } never settles (it
// rejects when its signal aborts, as fetch does), { status } sets the HTTP status.
function scenario(steps) {
  const calls = [];
  const texts = { read: 0 };
  const fetchImpl = vi.fn((url, init) => {
    const step = steps[calls.length] ?? { fail: true };
    calls.push({ url, init });
    const observer = FakeObserver.instances.at(-1);
    if (step.fail) return Promise.reject(new TypeError('Failed to fetch'));
    if (step.hang) {
      return new Promise((resolve, reject) => {
        init.signal.addEventListener('abort', () =>
          reject(new DOMException('aborted', 'AbortError')),
        );
      });
    }
    if (step.rt !== undefined) observer.queued.push(entry(step.rt, step.entry));
    if (step.now !== undefined) observer.deliver([entry(step.now, step.entry)]);
    if (step.late !== undefined) {
      setTimeout(() => observer.deliver([entry(step.late, step.entry)]), step.at);
    }
    return Promise.resolve({
      ok: (step.status ?? 200) < 400,
      status: step.status ?? 200,
      text: () => {
        texts.read += 1;
        return step.textFails ? Promise.reject(new Error('body failed')) : Promise.resolve('{}');
      },
    });
  });
  return { fetchImpl, calls, texts };
}

const run = (fetchImpl, extra = {}) =>
  measureLatency({ fetchImpl, Observer: FakeObserver, baseUrl: BASE, ...extra });
const settle = () => vi.advanceTimersByTimeAsync(0);
const allDisconnected = () => FakeObserver.instances.every((o) => o.disconnected);

beforeEach(() => {
  vi.useFakeTimers();
  FakeObserver.instances = [];
  FakeObserver.observeFails = false;
  FakeObserver.constructFails = false;
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllEnvs();
});

describe('roundTrip', () => {
  it('is responseStart minus requestStart', () => {
    expect(roundTrip({ requestStart: 100, responseStart: 148 })).toBe(48);
  });

  it('accepts a response that started at the very moment of the request', () => {
    expect(roundTrip({ requestStart: 5, responseStart: 5 })).toBe(0);
  });

  it('is null when the request never started (cached, or timing hidden)', () => {
    expect(roundTrip({ requestStart: 0, responseStart: 40 })).toBeNull();
  });

  it('is null when the response started before the request', () => {
    expect(roundTrip({ requestStart: 100, responseStart: 99 })).toBeNull();
  });

  it('is null for a missing entry or missing fields', () => {
    expect(roundTrip(undefined)).toBeNull();
    expect(roundTrip(null)).toBeNull();
    expect(roundTrip({})).toBeNull();
  });
});

describe('measureLatency: the figure', () => {
  it('is the smallest of three timed tries, rounded once', async () => {
    const { fetchImpl } = scenario([{ rt: 47.6 }, { rt: 52.2 }, { rt: 49.9 }]);
    const promise = run(fetchImpl);
    await settle();
    expect(await promise).toBe(48);
    expect(fetchImpl).toHaveBeenCalledTimes(LATENCY_SAMPLES);
    expect(allDisconnected()).toBe(true);
  });

  it('rounds 47.4 down', async () => {
    const { fetchImpl } = scenario([{ rt: 52.2 }, { rt: 47.4 }, { rt: 49.9 }]);
    const promise = run(fetchImpl);
    await settle();
    expect(await promise).toBe(47);
  });

  it('sends the exact request, one after another, and observes resource entries', async () => {
    const { fetchImpl, calls } = scenario([{ rt: 30 }, { rt: 30 }, { rt: 30 }]);
    const promise = run(fetchImpl);
    await settle();
    await promise;
    expect(LATENCY_PATH).toBe('/api/session');
    for (const call of calls) {
      expect(call.url).toBe('/api/session');
      expect(call.init).toEqual({
        credentials: 'same-origin',
        cache: 'no-store',
        headers: { Accept: 'application/json' },
        signal: expect.any(AbortSignal),
      });
    }
    expect(FakeObserver.instances).toHaveLength(3);
    expect(FakeObserver.instances[0].options).toEqual({ entryTypes: ['resource'] });
  });

  it('counts an entry for a 401, whatever the status', async () => {
    const { fetchImpl } = scenario([
      { rt: 60, status: 401 },
      { rt: 70, status: 401 },
      { rt: 80, status: 401 },
    ]);
    const promise = run(fetchImpl);
    await settle();
    expect(await promise).toBe(60);
  });

  it('ignores a body that fails to read', async () => {
    const { fetchImpl, texts } = scenario([{ rt: 25, textFails: true }, { rt: 26 }, { rt: 27 }]);
    const promise = run(fetchImpl);
    await settle();
    expect(await promise).toBe(25);
    expect(texts.read).toBe(3);
  });

  it('ignores an entry for another address', async () => {
    const other = { name: 'https://cine.test/api/home' };
    const { fetchImpl } = scenario([{ rt: 5, entry: other }]);
    const promise = run(fetchImpl);
    await vi.advanceTimersByTimeAsync(ENTRY_WAIT_MS);
    expect(await promise).toBeNull();
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });

  it('finds the entry among several the observer holds', async () => {
    const { fetchImpl } = scenario([{ rt: 40 }, { rt: 41 }, { rt: 42 }]);
    const promise = run(fetchImpl);
    await vi.advanceTimersByTimeAsync(0);
    expect(await promise).toBe(40);
  });

  it('does not count an entry whose request never started', async () => {
    const { fetchImpl } = scenario([{ rt: 40, entry: { requestStart: 0 } }]);
    const promise = run(fetchImpl);
    await vi.advanceTimersByTimeAsync(ENTRY_WAIT_MS);
    expect(await promise).toBeNull();
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });

  it('does not count an entry whose response started before its request', async () => {
    const { fetchImpl } = scenario([{ rt: -1 }]);
    const promise = run(fetchImpl);
    await vi.advanceTimersByTimeAsync(ENTRY_WAIT_MS);
    expect(await promise).toBeNull();
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });
});

describe('measureLatency: the entry that arrives late', () => {
  it('is found when the callback delivers it at 199 ms', async () => {
    const { fetchImpl } = scenario([{ late: 41, at: 199 }, { rt: 50 }, { rt: 50 }]);
    const promise = run(fetchImpl);
    await vi.advanceTimersByTimeAsync(199);
    await settle();
    expect(await promise).toBe(41);
    expect(allDisconnected()).toBe(true);
  });

  it('finds an entry the callback delivered before the response, with no wait', async () => {
    const { fetchImpl } = scenario([{ now: 39 }, { now: 40 }, { now: 41 }]);
    const promise = run(fetchImpl);
    await settle();
    expect(await promise).toBe(39);
    expect(fetchImpl).toHaveBeenCalledTimes(LATENCY_SAMPLES);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('stops waiting as soon as the callback delivers it', async () => {
    const { fetchImpl } = scenario([{ late: 41, at: 10 }, { rt: 50 }, { rt: 50 }]);
    const promise = run(fetchImpl);
    await vi.advanceTimersByTimeAsync(9);
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    // Delivered at 10 ms: the two further tries follow at once, long before the 200 ms wait would end.
    await vi.advanceTimersByTimeAsync(1);
    expect(fetchImpl).toHaveBeenCalledTimes(LATENCY_SAMPLES);
    await settle();
    expect(await promise).toBe(41);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('is not timed when nothing has arrived by 200 ms', async () => {
    const { fetchImpl } = scenario([{ none: true }]);
    let settled = false;
    const promise = run(fetchImpl).then((value) => {
      settled = true;
      return value;
    });
    await vi.advanceTimersByTimeAsync(ENTRY_WAIT_MS - 1);
    expect(settled).toBe(false);
    await vi.advanceTimersByTimeAsync(1);
    expect(await promise).toBeNull();
    expect(settled).toBe(true);
    expect(vi.getTimerCount()).toBe(0);
    expect(allDisconnected()).toBe(true);
  });

  it('keeps waiting when the callback delivers an entry it cannot use', async () => {
    const { fetchImpl } = scenario([{ none: true }]);
    const promise = run(fetchImpl);
    let settled = false;
    promise.then(() => {
      settled = true;
    });
    await vi.advanceTimersByTimeAsync(50);
    FakeObserver.instances[0].deliver([entry(5, { requestStart: 0 })]);
    await vi.advanceTimersByTimeAsync(ENTRY_WAIT_MS - 51);
    expect(settled).toBe(false);
    await vi.advanceTimersByTimeAsync(1);
    expect(await promise).toBeNull();
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });
});

describe('measureLatency: a browser that gives no timing', () => {
  it('ends after one request with no figure when a try is answered with no entry', async () => {
    const { fetchImpl } = scenario([{ none: true }, { rt: 30 }]);
    const promise = run(fetchImpl);
    await vi.advanceTimersByTimeAsync(ENTRY_WAIT_MS);
    expect(await promise).toBeNull();
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('sends no request when there is no PerformanceObserver', async () => {
    const { fetchImpl } = scenario([{ rt: 30 }]);
    expect(await run(fetchImpl, { Observer: null })).toBeNull();
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it('sends no request when PerformanceObserver is not a function', async () => {
    const { fetchImpl } = scenario([{ rt: 30 }]);
    expect(await run(fetchImpl, { Observer: {} })).toBeNull();
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it('sends no request when the observer will not observe resource entries', async () => {
    FakeObserver.observeFails = true;
    const { fetchImpl } = scenario([{ rt: 30 }]);
    expect(await run(fetchImpl)).toBeNull();
    expect(fetchImpl).not.toHaveBeenCalled();
    expect(allDisconnected()).toBe(true);
  });

  it('sends no request when the observer cannot be made', async () => {
    FakeObserver.constructFails = true;
    const { fetchImpl } = scenario([{ rt: 30 }]);
    expect(await run(fetchImpl)).toBeNull();
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it('has no figure and sends nothing when there is no fetch', async () => {
    expect(
      await measureLatency({ fetchImpl: null, Observer: FakeObserver, baseUrl: BASE }),
    ).toBeNull();
  });
});

describe('measureLatency: tries that fail before any is timed', () => {
  it('tries again after 2 s (not 1.999 s), then 5 s', async () => {
    const { fetchImpl } = scenario([
      { fail: true },
      { fail: true },
      { rt: 33 },
      { rt: 34 },
      { rt: 35 },
    ]);
    const promise = run(fetchImpl);
    await vi.advanceTimersByTimeAsync(1999);
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(fetchImpl).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(4999);
    expect(fetchImpl).toHaveBeenCalledTimes(2);
    // The third request is timed, so the two more follow at once.
    await vi.advanceTimersByTimeAsync(1);
    await settle();
    expect(await promise).toBe(33);
    expect(fetchImpl).toHaveBeenCalledTimes(5);
  });

  it('uses every pause and then gives up with no figure', async () => {
    const { fetchImpl } = scenario([]);
    const promise = run(fetchImpl);
    let sent = 1;
    for (const pause of RETRY_PAUSES_MS) {
      await vi.advanceTimersByTimeAsync(pause - 1);
      expect(fetchImpl).toHaveBeenCalledTimes(sent);
      await vi.advanceTimersByTimeAsync(1);
      sent += 1;
      expect(fetchImpl).toHaveBeenCalledTimes(sent);
    }
    expect(sent).toBe(RETRY_PAUSES_MS.length + 1);
    expect(await promise).toBeNull();
    expect(vi.getTimerCount()).toBe(0);
    expect(allDisconnected()).toBe(true);
  });

  it('gives a request that never settles up at 4 s (not 3.999 s)', async () => {
    const { fetchImpl, calls } = scenario([{ hang: true }]);
    const controller = new AbortController();
    const promise = run(fetchImpl, { signal: controller.signal });
    await vi.advanceTimersByTimeAsync(LATENCY_TIMEOUT_MS - 1);
    expect(calls[0].init.signal.aborted).toBe(false);
    await vi.advanceTimersByTimeAsync(1);
    expect(calls[0].init.signal.aborted).toBe(true);
    // It counts as a failure: another try follows after the first pause.
    await vi.advanceTimersByTimeAsync(RETRY_PAUSES_MS[0]);
    expect(fetchImpl).toHaveBeenCalledTimes(2);
    controller.abort();
    expect(await promise).toBeNull();
  });

  it('survives a fetch that throws at once', async () => {
    const fetchImpl = vi.fn(() => {
      throw new Error('boom');
    });
    const controller = new AbortController();
    const promise = run(fetchImpl, { signal: controller.signal });
    await vi.advanceTimersByTimeAsync(RETRY_PAUSES_MS[0]);
    expect(fetchImpl).toHaveBeenCalledTimes(2);
    controller.abort();
    expect(await promise).toBeNull();
  });
});

describe('measureLatency: tries after the first has been timed', () => {
  it('leaves a failed try out, with no pause: timed, failed, timed', async () => {
    const { fetchImpl } = scenario([{ rt: 60 }, { fail: true }, { rt: 44 }]);
    const promise = run(fetchImpl);
    await settle();
    expect(await promise).toBe(44);
    expect(fetchImpl).toHaveBeenCalledTimes(3);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('leaves an answered-without-timing try out, with no extra try', async () => {
    const { fetchImpl } = scenario([{ rt: 60 }, { none: true }, { rt: 44 }, { rt: 1 }]);
    const promise = run(fetchImpl);
    await vi.advanceTimersByTimeAsync(ENTRY_WAIT_MS);
    expect(await promise).toBe(44);
    expect(fetchImpl).toHaveBeenCalledTimes(3);
  });

  it('makes no more than three tries even when the later ones fail', async () => {
    const { fetchImpl } = scenario([{ rt: 60 }, { fail: true }, { fail: true }]);
    const promise = run(fetchImpl);
    await settle();
    expect(await promise).toBe(60);
    expect(fetchImpl).toHaveBeenCalledTimes(3);
  });

  it('resolves the figure when the first try took a pause to be timed', async () => {
    const { fetchImpl } = scenario([{ fail: true }, { rt: 70 }, { rt: 52 }, { rt: 90 }]);
    const promise = run(fetchImpl);
    await vi.advanceTimersByTimeAsync(RETRY_PAUSES_MS[0]);
    await settle();
    expect(await promise).toBe(52);
    expect(fetchImpl).toHaveBeenCalledTimes(4);
  });
});

describe('measureLatency: the caller gives up', () => {
  it('sends nothing when already aborted', async () => {
    const { fetchImpl } = scenario([{ rt: 30 }]);
    const controller = new AbortController();
    controller.abort();
    expect(await run(fetchImpl, { signal: controller.signal })).toBeNull();
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it('stops during a request', async () => {
    const { fetchImpl } = scenario([{ hang: true }]);
    const controller = new AbortController();
    const promise = run(fetchImpl, { signal: controller.signal });
    await vi.advanceTimersByTimeAsync(100);
    controller.abort();
    expect(await promise).toBeNull();
    expect(vi.getTimerCount()).toBe(0);
    await vi.advanceTimersByTimeAsync(60000);
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    expect(allDisconnected()).toBe(true);
  });

  it('stops during the wait for an entry', async () => {
    const { fetchImpl } = scenario([{ none: true }]);
    const controller = new AbortController();
    const promise = run(fetchImpl, { signal: controller.signal });
    await vi.advanceTimersByTimeAsync(100);
    controller.abort();
    expect(await promise).toBeNull();
    expect(vi.getTimerCount()).toBe(0);
    await vi.advanceTimersByTimeAsync(60000);
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    expect(allDisconnected()).toBe(true);
  });

  it('stops during a pause', async () => {
    const { fetchImpl } = scenario([{ fail: true }]);
    const controller = new AbortController();
    const promise = run(fetchImpl, { signal: controller.signal });
    await vi.advanceTimersByTimeAsync(1000);
    controller.abort();
    expect(await promise).toBeNull();
    expect(vi.getTimerCount()).toBe(0);
    await vi.advanceTimersByTimeAsync(60000);
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });

  it('gives no figure when aborted after a timed try, and sends nothing more', async () => {
    const { fetchImpl } = scenario([{ rt: 30 }, { hang: true }]);
    const controller = new AbortController();
    const promise = run(fetchImpl, { signal: controller.signal });
    await settle();
    controller.abort();
    expect(await promise).toBeNull();
    expect(fetchImpl).toHaveBeenCalledTimes(2);
    expect(vi.getTimerCount()).toBe(0);
  });
});

describe('measureLatency: where it never gets going', () => {
  it('has no figure when the address cannot be made', async () => {
    const { fetchImpl } = scenario([{ rt: 30 }]);
    expect(await run(fetchImpl, { baseUrl: 'not a url' })).toBeNull();
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it('answers the fixture figure with no request in fixture mode', async () => {
    vi.stubEnv('VITE_FIXTURES', '1');
    const { fetchImpl } = scenario([{ rt: 30 }]);
    expect(await run(fetchImpl)).toBe(FIXTURE_LATENCY_MS);
    expect(FIXTURE_LATENCY_MS).toBe(48);
    expect(fetchImpl).not.toHaveBeenCalled();
    expect(FakeObserver.instances).toHaveLength(0);
  });
});
