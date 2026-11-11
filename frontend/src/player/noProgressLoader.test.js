import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NO_PROGRESS_MS, WATCH_MS, createNoProgressLoader } from './noProgressLoader.js';

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

// What hls.js 1.7.4's XHR loader does that the wrapper relies on: `stats` exists from construction, `loading.first`
// is set when the headers arrive, `loaded` moves with the bytes, `abort()` reports `onAbort`, and `destroy()` empties
// `stats`. The test moves `stats` itself.
class StubLoader {
  constructor(config) {
    this.config = config;
    this.stats = { loading: { start: 0, first: 0, end: 0 }, loaded: 0 };
    this.context = null;
    this.callbacks = null;
    this.load = vi.fn((context, config2, callbacks) => {
      this.context = context;
      this.callbacks = callbacks;
    });
    this.abort = vi.fn(() => {
      this.callbacks?.onAbort?.(this.stats, this.context, null);
    });
    this.destroy = vi.fn(() => {
      this.stats = null;
    });
    StubLoader.last = this;
  }
}
class CachingStub extends StubLoader {
  getCacheAge() {
    return 7;
  }
  getResponseHeader(name) {
    return `header:${name}`;
  }
}

const context = { url: '/seg.ts' };
const config = { loadPolicy: {} };
const callbacksOf = () => ({
  onSuccess: vi.fn(),
  onError: vi.fn(),
  onTimeout: vi.fn(),
  onAbort: vi.fn(),
});

// A loader started on `Base` with the callbacks, plus the inner stub.
function started(Base = StubLoader, callbacks = callbacksOf(), options = {}) {
  const Loader = createNoProgressLoader(Base, options);
  const loader = new Loader({ any: 'config' });
  loader.load(context, config, callbacks);
  return { loader, inner: Base.last, callbacks };
}

const beginResponse = (inner) => {
  inner.stats.loading.first = 1;
};

describe('the no-progress loader: when it fires', () => {
  it('has the plan’s limits', () => {
    expect([NO_PROGRESS_MS, WATCH_MS]).toEqual([12000, 1000]);
  });

  it('never fires while a byte arrives every 11,999 ms, for ten minutes', async () => {
    const { inner, callbacks } = started();
    beginResponse(inner);
    for (let elapsed = 0; elapsed < 600000; elapsed += 11999) {
      await vi.advanceTimersByTimeAsync(11999);
      inner.stats.loaded += 100;
    }
    expect(callbacks.onTimeout).not.toHaveBeenCalled();
    expect(inner.abort).not.toHaveBeenCalled();
  });

  it('fires at 12,000 ms of silence since the last byte seen and not at 11,999', async () => {
    const { inner, callbacks } = started();
    beginResponse(inner);
    await vi.advanceTimersByTimeAsync(1999);
    inner.stats.loaded = 500;
    await vi.advanceTimersByTimeAsync(1); // the check at 2,000 ms sees the bytes: a change
    await vi.advanceTimersByTimeAsync(11999); // 13,999 ms: 11,999 ms of silence
    expect(callbacks.onTimeout).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1); // 14,000 ms: 12,000 ms
    expect(callbacks.onTimeout).toHaveBeenCalledTimes(1);
  });

  it('fires once, aborts the inner loader without passing its abort on, with the inner stats and the context', async () => {
    const { inner, callbacks } = started();
    beginResponse(inner);
    const stats = inner.stats;
    await vi.advanceTimersByTimeAsync(1000); // the response is seen at 1,000 ms
    await vi.advanceTimersByTimeAsync(11999);
    expect(callbacks.onTimeout).not.toHaveBeenCalled(); // 12,999 ms: 11,999 ms of silence
    await vi.advanceTimersByTimeAsync(1);
    expect(callbacks.onTimeout).toHaveBeenCalledTimes(1);
    expect(callbacks.onTimeout).toHaveBeenCalledWith(stats, context, null);
    expect(inner.abort).toHaveBeenCalledTimes(1);
    expect(callbacks.onAbort).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(60000);
    expect(callbacks.onTimeout).toHaveBeenCalledTimes(1);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('does not fire before the response began, however long (the inner limit applies)', async () => {
    const { callbacks } = started();
    await vi.advanceTimersByTimeAsync(600000);
    expect(callbacks.onTimeout).not.toHaveBeenCalled();
  });

  it('headers at 8,500 ms and nothing after: the check at 9,000 is a change, so a timeout at 21,000 not 20,000', async () => {
    const { inner, callbacks } = started();
    await vi.advanceTimersByTimeAsync(8500);
    beginResponse(inner);
    await vi.advanceTimersByTimeAsync(500); // the check at 9,000 finds the response begun
    await vi.advanceTimersByTimeAsync(11000); // 20,000 ms
    expect(callbacks.onTimeout).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(999);
    expect(callbacks.onTimeout).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1); // 21,000 ms
    expect(callbacks.onTimeout).toHaveBeenCalledTimes(1);
  });

  it('uses the clock it is given', async () => {
    let clock = 0;
    const { inner, callbacks } = started(StubLoader, callbacksOf(), { now: () => clock });
    beginResponse(inner);
    await vi.advanceTimersByTimeAsync(1000);
    clock = 11999;
    await vi.advanceTimersByTimeAsync(1000);
    expect(callbacks.onTimeout).not.toHaveBeenCalled();
    clock = 12000;
    await vi.advanceTimersByTimeAsync(1000);
    expect(callbacks.onTimeout).toHaveBeenCalledTimes(1);
  });

  it('takes limits from its options', async () => {
    const { inner, callbacks } = started(StubLoader, callbacksOf(), {
      noProgressMs: 3000,
      watchMs: 500,
    });
    beginResponse(inner);
    await vi.advanceTimersByTimeAsync(500); // the response is seen at 500 ms
    await vi.advanceTimersByTimeAsync(2999);
    expect(callbacks.onTimeout).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1); // 3,500 ms: 3,000 ms of silence
    expect(callbacks.onTimeout).toHaveBeenCalledTimes(1);
  });
});

describe('the no-progress loader: how hls.js 1.7.4 drives it', () => {
  it('builds the inner loader in the constructor, with hls.js’s config, so stats can be read and written before load', () => {
    const Loader = createNoProgressLoader(StubLoader);
    const loader = new Loader({ any: 'config' });
    expect(StubLoader.last.config).toEqual({ any: 'config' });
    expect(loader.stats).toBe(StubLoader.last.stats);
    const replacement = { loading: { start: 0, first: 0, end: 0 }, loaded: 0 };
    loader.stats = replacement;
    expect(StubLoader.last.stats).toBe(replacement);
    expect(StubLoader.last.load).not.toHaveBeenCalled();
  });

  it('exposes the inner loader’s very stats object and reads the context through', () => {
    const { loader, inner } = started();
    expect(loader.stats).toBe(inner.stats);
    expect(loader.context).toBe(context);
    const next = { url: '/other.ts' };
    loader.context = next;
    expect(inner.context).toBe(next);
  });

  it('passes load through with its own callbacks', () => {
    const { inner } = started();
    expect(inner.load).toHaveBeenCalledWith(context, config, expect.any(Object));
  });

  it('passes onProgress to the inner loader only when hls.js gave one', () => {
    const without = started();
    expect('onProgress' in without.inner.load.mock.calls[0][2]).toBe(false);
    const onProgress = vi.fn();
    const withOne = started(StubLoader, { ...callbacksOf(), onProgress });
    expect(withOne.inner.load.mock.calls[0][2].onProgress).toBe(onProgress);
  });

  it('passes getCacheAge and getResponseHeader through when the inner loader has them, and has neither otherwise', () => {
    const plain = new (createNoProgressLoader(StubLoader))({});
    expect(plain.getCacheAge).toBeUndefined();
    expect(plain.getResponseHeader).toBeUndefined();
    const cached = new (createNoProgressLoader(CachingStub))({});
    expect(cached.getCacheAge()).toBe(7);
    expect(cached.getResponseHeader('age')).toBe('header:age');
  });

  it.each([
    ['onSuccess', ['response', 'stats', 'ctx', 'xhr']],
    ['onError', [{ code: 0 }, 'ctx', 'xhr', 'stats']],
    ['onTimeout', ['stats', 'ctx', 'xhr']],
  ])('passes the inner loader’s %s through once and stops the timer', async (name, args) => {
    const { inner, callbacks } = started();
    beginResponse(inner);
    inner.callbacks[name](...args);
    expect(callbacks[name]).toHaveBeenCalledTimes(1);
    expect(callbacks[name]).toHaveBeenCalledWith(...args);
    expect(vi.getTimerCount()).toBe(0);
    await vi.advanceTimersByTimeAsync(60000);
    expect(callbacks.onTimeout).toHaveBeenCalledTimes(name === 'onTimeout' ? 1 : 0);
  });

  it('stops the timer when the inner loader reports an abort of its own', () => {
    const { inner, callbacks } = started();
    inner.callbacks.onAbort('stats', context, null);
    expect(callbacks.onAbort).toHaveBeenCalledTimes(1);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('stops its own timer when it fires and when abort() is called, even if the inner abort reports nothing', async () => {
    class SilentAbort extends StubLoader {
      constructor(config) {
        super(config);
        this.abort = vi.fn();
      }
    }
    const fired = started(SilentAbort);
    beginResponse(fired.inner);
    await vi.advanceTimersByTimeAsync(13000);
    expect(fired.callbacks.onTimeout).toHaveBeenCalledTimes(1);
    expect(vi.getTimerCount()).toBe(0);
    const aborted = started(SilentAbort);
    aborted.loader.abort();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('passes a plain abort through and stops the timer', () => {
    const { loader, inner, callbacks } = started();
    loader.abort();
    expect(inner.abort).toHaveBeenCalledTimes(1);
    expect(callbacks.onAbort).toHaveBeenCalledTimes(1);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('tolerates hls.js giving no onAbort when the inner loader aborts', () => {
    const { loader } = started(StubLoader, {
      onSuccess: vi.fn(),
      onError: vi.fn(),
      onTimeout: vi.fn(),
    });
    expect(() => loader.abort()).not.toThrow();
  });

  it('a callback that destroys the loader (as hls.js does) gets the stats object it had, and nothing of the wrapper runs after it', async () => {
    const seen = [];
    let loader;
    const callbacks = {
      ...callbacksOf(),
      onSuccess: vi.fn((response, stats) => {
        loader.destroy(); // hls.js: resetLoader -> destroy, inside the callback
        seen.push(stats);
      }),
    };
    const started1 = started(StubLoader, callbacks);
    loader = started1.loader;
    const { inner } = started1;
    const stats = inner.stats;
    inner.callbacks.onSuccess('response', stats, context, null);
    expect(seen).toEqual([stats]);
    expect(inner.destroy).toHaveBeenCalledTimes(1);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('the timeout callback gets stats read before hls.js destroys the loader inside it', async () => {
    let loader;
    const seen = [];
    const callbacks = {
      ...callbacksOf(),
      onTimeout: vi.fn((stats) => {
        loader.destroy(); // the inner stats are emptied here
        seen.push(stats);
      }),
    };
    const s = started(StubLoader, callbacks);
    loader = s.loader;
    beginResponse(s.inner);
    const stats = s.inner.stats;
    await vi.advanceTimersByTimeAsync(13000);
    expect(seen).toEqual([stats]);
    expect(seen[0]).not.toBeNull();
  });

  it('destroy() twice, and inside a callback, is safe, and stops the timer', () => {
    const { loader, inner } = started();
    loader.destroy();
    expect(() => loader.destroy()).not.toThrow();
    expect(inner.destroy).toHaveBeenCalledTimes(2);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('abort() and destroy() stop the timer and reach the inner loader', async () => {
    const first = started();
    first.loader.abort();
    expect(first.inner.abort).toHaveBeenCalledTimes(1);
    expect(vi.getTimerCount()).toBe(0);
    const second = started();
    second.loader.destroy();
    expect(second.inner.destroy).toHaveBeenCalledTimes(1);
    expect(vi.getTimerCount()).toBe(0);
  });
});
