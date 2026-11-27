import { describe, expect, it, vi } from 'vitest';
import { createBankLoaders } from './bankLoader.js';

function newStats() {
  return {
    aborted: false,
    loaded: 0,
    retry: 0,
    total: 0,
    chunkCount: 0,
    bwEstimate: 0,
    loading: { start: 0, first: 0, end: 0 },
    parsing: { start: 0, end: 0 },
    buffering: { start: 0, first: 0, end: 0 },
  };
}

// Stands in for hls.js's default loader: stats exist at construction; `load` is recorded; `abort` reports
// `onAbort` as hls.js's base loader does.
class FakeInner {
  constructor(config) {
    this.config = config;
    this.stats = newStats();
    this.context = null;
    this.loads = [];
    this.destroyed = false;
    this.aborted = false;
  }
  // As hls.js 1.7.4's base-loader.ts `load` (:29-44): one use only, and it stamps `loading.start` itself.
  load(context, config, callbacks) {
    if (this.stats.loading.start) throw new Error('Loader can only be used once.');
    this.stats.loading.start = 12_345;
    this.context = context;
    this.callbacks = callbacks;
    this.loads.push(context);
  }
  abort() {
    this.aborted = true;
    this.callbacks?.onAbort?.(this.stats, this.context, null);
  }
  destroy() {
    this.destroyed = true;
  }
  getCacheAge() {
    return 7;
  }
  getResponseHeader(name) {
    return `h-${name}`;
  }
}

function fragContext(sn, { level = 2, type = 'media-fragment' } = {}) {
  return {
    url: `https://film.test/seg-${sn}.ts`,
    responseType: 'arraybuffer',
    type,
    frag: { sn, level },
    part: null,
    resetIV: false,
  };
}

const bytesOf = (...values) => new Uint8Array(values).buffer;

function setup({ decision = { action: 'pass' }, armed = false, playlist = null } = {}) {
  const bank = {
    armed: vi.fn(() => armed),
    fragmentRequest: vi.fn(() => decision),
    fragmentLoaded: vi.fn(),
    playlistText: vi.fn(() => playlist),
  };
  const { fLoader: F, pLoader: P } = createBankLoaders({
    InnerF: FakeInner,
    InnerP: FakeInner,
    bank,
    now: () => 10_000,
  });
  const callbacks = () => ({
    onSuccess: vi.fn(),
    onError: vi.fn(),
    onTimeout: vi.fn(),
    onAbort: vi.fn(),
  });
  return { bank, F, P, callbacks };
}

const flush = () => Promise.resolve();

const ANSWER = (bytes = bytesOf(1)) => ({
  action: 'answer',
  bytes,
  timings: { start: 0, first: 1, end: 2 },
});

describe('bank fLoader answers', () => {
  it('replays the held timings into the stats and answers from a microtask', async () => {
    const held = bytesOf(1, 2, 3, 4);
    const { F, callbacks, bank } = setup({
      decision: { action: 'answer', bytes: held, timings: { start: 500, first: 540, end: 800 } },
    });
    const loader = new F({});
    const cb = callbacks();
    const ctx = fragContext(5);
    loader.load(ctx, {}, cb);
    expect(cb.onSuccess).not.toHaveBeenCalled();
    expect(bank.fragmentRequest).toHaveBeenCalledWith(
      { sn: 5, level: 2, url: ctx.url, frag: ctx.frag, context: ctx },
      expect.any(Object),
    );
    await flush();
    expect(cb.onSuccess).toHaveBeenCalledTimes(1);
    const [response, stats, context, details] = cb.onSuccess.mock.calls[0];
    expect(cb.onSuccess).toHaveBeenCalledWith(
      { url: ctx.url, data: response.data },
      loader.stats,
      ctx,
      null,
    );
    expect(response.url).toBe(ctx.url);
    expect(new Uint8Array(response.data)).toEqual(new Uint8Array([1, 2, 3, 4]));
    expect(stats.loading).toEqual({ start: 9700, first: 9740, end: 10_000 });
    expect(stats.loaded).toBe(4);
    expect(stats.total).toBe(4);
    expect(stats.bankAnswered).toBe(true);
    expect(stats).toBe(loader.stats);
    expect(context).toBe(ctx);
    expect(context.resetIV).toBe(false);
    expect(details).toBeNull();
    expect(loader.inner.loads).toHaveLength(0);
  });

  it('hands out a fresh copy for each answer, so a transfer does not touch what is held', async () => {
    const held = bytesOf(9, 8, 7);
    const { F, callbacks } = setup({ decision: ANSWER(held) });
    const got = [];
    for (let i = 0; i < 2; i++) {
      const cb = callbacks();
      new F({}).load(fragContext(1), {}, cb);
      await flush();
      got.push(cb.onSuccess.mock.calls[0][0].data);
    }
    expect(got[0]).not.toBe(got[1]);
    expect(got[0]).not.toBe(held);
    structuredClone(got[0], { transfer: [got[0]] });
    expect(got[0].byteLength).toBe(0);
    expect(new Uint8Array(got[1])).toEqual(new Uint8Array([9, 8, 7]));
    expect(new Uint8Array(held)).toEqual(new Uint8Array([9, 8, 7]));
  });

  it('does not answer after abort, and reports the abort as the default loader does', async () => {
    const { F, callbacks } = setup({ decision: ANSWER() });
    const loader = new F({});
    const cb = callbacks();
    const ctx = fragContext(1);
    loader.load(ctx, {}, cb);
    expect(loader.context).toBe(ctx);
    loader.abort();
    await flush();
    expect(cb.onSuccess).not.toHaveBeenCalled();
    expect(cb.onAbort).toHaveBeenCalledWith(loader.stats, ctx, null);
    expect(loader.stats.aborted).toBe(true);
    loader.abort();
    expect(cb.onAbort).toHaveBeenCalledTimes(1);
  });

  it('does not answer after destroy, and destroys the inner loader', async () => {
    const { F, callbacks } = setup({ decision: ANSWER() });
    const loader = new F({});
    const cb = callbacks();
    loader.load(fragContext(1), {}, cb);
    loader.destroy();
    await flush();
    expect(cb.onSuccess).not.toHaveBeenCalled();
    expect(loader.inner.destroyed).toBe(true);
  });

  it('survives destroy called from inside onSuccess', async () => {
    const { F, callbacks } = setup({ decision: ANSWER() });
    const loader = new F({});
    const cb = callbacks();
    cb.onSuccess.mockImplementation(() => loader.destroy());
    const ctx = fragContext(1);
    loader.load(ctx, {}, cb);
    expect(loader.context).toBe(ctx);
    await flush();
    expect(cb.onSuccess).toHaveBeenCalledWith(
      { url: ctx.url, data: expect.any(ArrayBuffer) },
      expect.objectContaining({ bankAnswered: true }),
      ctx,
      null,
    );
    expect(loader.inner.destroyed).toBe(true);
  });

  it('survives destroy from inside load, when the decider answers at once', () => {
    const cancel = vi.fn();
    const { F, callbacks, bank } = setup();
    const loader = new F({});
    const cb = callbacks();
    bank.fragmentRequest.mockImplementation((request, reply) => {
      reply.success(bytesOf(1), { start: 0, first: 1, end: 2 });
      return { action: 'wait', cancel };
    });
    cb.onSuccess.mockImplementation(() => loader.destroy());
    loader.load(fragContext(1), {}, cb);
    expect(cb.onSuccess).toHaveBeenCalledTimes(1);
    expect(loader.inner.destroyed).toBe(true);
    expect(cancel).not.toHaveBeenCalled();
  });

  it('cancels the wait when the loader is destroyed inside the decider', () => {
    const cancel = vi.fn();
    const { F, callbacks, bank } = setup();
    const loader = new F({});
    bank.fragmentRequest.mockImplementation(() => {
      loader.destroy();
      return { action: 'wait', cancel };
    });
    loader.load(fragContext(1), {}, callbacks());
    expect(cancel).toHaveBeenCalledTimes(1);
  });

  it('exposes the inner loader for hls.js: stats, context, cache age and headers', () => {
    const { F } = setup();
    const loader = new F({});
    const fresh = newStats();
    loader.stats = fresh;
    expect(loader.inner.stats).toBe(fresh);
    const ctx = fragContext(1);
    loader.context = ctx;
    expect(loader.inner.context).toBe(ctx);
    expect(loader.getCacheAge()).toBe(7);
    expect(loader.getResponseHeader('age')).toBe('h-age');
  });

  it('falls back to null when the inner loader has no cache age or headers', () => {
    const Bare = class {
      constructor() {
        this.stats = newStats();
      }
    };
    const { fLoader: F } = createBankLoaders({
      InnerF: Bare,
      InnerP: Bare,
      bank: {},
      now: () => 0,
    });
    const loader = new F({});
    expect(loader.getCacheAge()).toBeNull();
    expect(loader.getResponseHeader('x')).toBeNull();
  });
});

describe('bank fLoader waits', () => {
  function waiting() {
    const cancel = vi.fn();
    const s = setup({ decision: { action: 'wait', cancel } });
    const loader = new s.F({});
    const cb = s.callbacks();
    const ctx = fragContext(7);
    loader.load(ctx, {}, cb);
    const reply = s.bank.fragmentRequest.mock.calls[0][1];
    return { ...s, loader, cb, reply, cancel, ctx };
  }

  it('answers with the bytes and timings the decider later gives', () => {
    const { loader, cb, reply, ctx } = waiting();
    expect(loader.context).toBe(ctx);
    expect(loader.stats.loading.start).toBe(10_000);
    reply.success(bytesOf(5, 6), { start: 0, first: 10, end: 50 });
    expect(cb.onSuccess).toHaveBeenCalledTimes(1);
    const [response, stats] = cb.onSuccess.mock.calls[0];
    expect(cb.onSuccess).toHaveBeenCalledWith(
      { url: ctx.url, data: response.data },
      stats,
      ctx,
      null,
    );
    expect(new Uint8Array(response.data)).toEqual(new Uint8Array([5, 6]));
    expect(stats.loading).toEqual({ start: 9950, first: 9960, end: 10_000 });
    expect(stats.bankAnswered).toBe(true);
    expect(loader.inner.loads).toHaveLength(0);
  });

  it('hands a server answer to hls.js as onError with the status', () => {
    const { loader, cb, reply, ctx } = waiting();
    reply.error({ code: 503, text: 'Service Unavailable' });
    expect(cb.onError).toHaveBeenCalledWith(
      { code: 503, text: 'Service Unavailable' },
      ctx,
      null,
      loader.stats,
    );
    reply.success(bytesOf(1), { start: 0, first: 0, end: 1 });
    expect(cb.onSuccess).not.toHaveBeenCalled();
  });

  it('calls onTimeout when the decider times the wait out', () => {
    const { loader, cb, reply, ctx } = waiting();
    reply.timeout();
    expect(cb.onTimeout).toHaveBeenCalledWith(loader.stats, ctx, null);
    expect(cb.onSuccess).not.toHaveBeenCalled();
  });

  it('abort during the wait cancels it, calls onAbort and ignores a later reply', () => {
    const { loader, cb, reply, cancel, ctx } = waiting();
    loader.abort();
    expect(cancel).toHaveBeenCalledTimes(1);
    expect(cb.onAbort).toHaveBeenCalledWith(loader.stats, ctx, null);
    expect(cb.onAbort).toHaveBeenCalledTimes(1);
    reply.success(bytesOf(1), { start: 0, first: 0, end: 1 });
    reply.error({ code: 503, text: 'x' });
    reply.timeout();
    expect(cb.onSuccess).not.toHaveBeenCalled();
    expect(cb.onError).not.toHaveBeenCalled();
    expect(cb.onTimeout).not.toHaveBeenCalled();
  });

  it('destroy during the wait cancels it without reporting an abort', () => {
    const { loader, cb, cancel } = waiting();
    loader.destroy();
    expect(cancel).toHaveBeenCalledTimes(1);
    expect(cb.onAbort).not.toHaveBeenCalled();
  });

  it('abort during a wait with no cancel hook and no onAbort callback is safe', () => {
    const s = setup({ decision: { action: 'wait' } });
    const loader = new s.F({});
    const cb = s.callbacks();
    delete cb.onAbort;
    loader.load(fragContext(7), {}, cb);
    expect(() => loader.abort()).not.toThrow();
  });
});

describe('bank fLoader passes through', () => {
  it('passes a fragment the decider passes to the inner loader, with the same callbacks', () => {
    const s = setup({ decision: { action: 'pass' } });
    const loader = new s.F({});
    const cb = s.callbacks();
    const ctx = fragContext(3);
    expect(() => loader.load(ctx, { timeout: 1 }, cb)).not.toThrow();
    expect(loader.inner.loads).toEqual([ctx]);
    expect(loader.stats.loading.start).toBe(12_345);
    expect(loader.inner.callbacks).toBe(cb);
    loader.abort();
    expect(loader.inner.aborted).toBe(true);
    expect(cb.onAbort).toHaveBeenCalledWith(loader.stats, ctx, null);
  });

  it('never asks the decider about the init segment, and passes it through even when armed', () => {
    const s = setup({ armed: true });
    const loader = new s.F({});
    const cb = s.callbacks();
    loader.load(fragContext('initSegment'), {}, cb);
    expect(s.bank.fragmentRequest).not.toHaveBeenCalled();
    expect(loader.inner.callbacks).toBe(cb);
  });

  it('never asks the decider about a master or non-fragment load', () => {
    const s = setup({ armed: true });
    const loader = new s.F({});
    const cb = s.callbacks();
    loader.load({ url: 'u', type: 'manifest', responseType: 'text' }, {}, cb);
    expect(s.bank.fragmentRequest).not.toHaveBeenCalled();
    expect(loader.inner.callbacks).toBe(cb);
  });

  it('copies an armed inner load into the bank before onSuccess, intact after the transfer', () => {
    const s = setup({ armed: true });
    const loader = new s.F({});
    const cb = s.callbacks();
    const ctx = fragContext(4);
    loader.load(ctx, {}, cb);
    const payload = bytesOf(1, 2, 3);
    const stats = loader.stats;
    stats.loading = { start: 100, first: 130, end: 400 };
    let copiedBefore = false;
    cb.onSuccess.mockImplementation((response) => {
      copiedBefore = s.bank.fragmentLoaded.mock.calls.length === 1;
      structuredClone(response.data, { transfer: [response.data] });
    });
    loader.inner.callbacks.onSuccess({ url: ctx.url, data: payload }, stats, ctx, null);
    expect(copiedBefore).toBe(true);
    expect(payload.byteLength).toBe(0);
    const arg = s.bank.fragmentLoaded.mock.calls[0][0];
    expect(arg).toMatchObject({
      sn: 4,
      level: 2,
      url: ctx.url,
      timings: { start: 100, first: 130, end: 400 },
    });
    expect(new Uint8Array(arg.bytes)).toEqual(new Uint8Array([1, 2, 3]));
    expect(cb.onSuccess).toHaveBeenCalledWith(
      { url: ctx.url, data: expect.any(ArrayBuffer) },
      stats,
      ctx,
      null,
    );
    expect(ctx.resetIV).toBe(false);
  });

  it('forwards an inner error while armed and copies nothing', () => {
    const s = setup({ armed: true });
    const loader = new s.F({});
    const cb = s.callbacks();
    const ctx = fragContext(4);
    loader.load(ctx, {}, cb);
    loader.inner.callbacks.onError({ code: 500, text: 'e' }, ctx, null, loader.stats);
    expect(cb.onError).toHaveBeenCalledWith({ code: 500, text: 'e' }, ctx, null, loader.stats);
    expect(s.bank.fragmentLoaded).not.toHaveBeenCalled();
  });

  it('copies nothing from a text payload, and wraps nothing while not armed', () => {
    const armedBank = setup({ armed: true });
    const a = new armedBank.F({});
    const cbA = armedBank.callbacks();
    const ctx = fragContext(4);
    a.load(ctx, {}, cbA);
    a.inner.callbacks.onSuccess({ url: ctx.url, data: 'text' }, a.stats, ctx, null);
    expect(armedBank.bank.fragmentLoaded).not.toHaveBeenCalled();
    expect(cbA.onSuccess).toHaveBeenCalledWith({ url: ctx.url, data: 'text' }, a.stats, ctx, null);

    const off = setup({ armed: false });
    const b = new off.F({});
    const cbB = off.callbacks();
    b.load(ctx, {}, cbB);
    expect(b.inner.callbacks).toBe(cbB);
  });
});

describe('bank pLoader', () => {
  const levelContext = { url: 'https://film.test/r480/index.m3u8', type: 'level', level: 2 };

  it('answers a held media playlist from a microtask', async () => {
    const { P, callbacks, bank } = setup({ playlist: '#EXTM3U\n' });
    const loader = new P({});
    const cb = callbacks();
    const ctx = { ...levelContext };
    loader.load(ctx, {}, cb);
    expect(loader.context).toBe(ctx);
    expect(bank.playlistText).toHaveBeenCalledTimes(1);
    expect(cb.onSuccess).not.toHaveBeenCalled();
    await flush();
    const [response, stats, context, details] = cb.onSuccess.mock.calls[0];
    expect(context).toBe(ctx);
    expect(response).toEqual({ url: levelContext.url, data: '#EXTM3U\n' });
    expect(stats.loading).toEqual({ start: 10_000, first: 10_000, end: 10_000 });
    expect(stats.loaded).toBe(8);
    expect(stats.total).toBe(8);
    expect(stats.bankAnswered).toBe(true);
    expect(details).toBeNull();
    expect(loader.inner.loads).toHaveLength(0);
  });

  it('does not answer a playlist after abort or destroy', async () => {
    const { P, callbacks } = setup({ playlist: 'x' });
    const a = new P({});
    const cbA = callbacks();
    const ctxA = { ...levelContext };
    a.load(ctxA, {}, cbA);
    a.abort();
    const b = new P({});
    const cbB = callbacks();
    b.load({ ...levelContext }, {}, cbB);
    b.destroy();
    await flush();
    expect(cbA.onSuccess).not.toHaveBeenCalled();
    expect(cbA.onAbort).toHaveBeenCalledWith(a.stats, ctxA, null);
    expect(cbB.onSuccess).not.toHaveBeenCalled();
  });

  it('passes through a media playlist that is not held', () => {
    const { P, callbacks } = setup({ playlist: null });
    const loader = new P({});
    const cb = callbacks();
    loader.load({ ...levelContext }, {}, cb);
    expect(loader.inner.callbacks).toBe(cb);
  });

  it('passes through the master playlist without asking the bank', () => {
    const { P, callbacks, bank } = setup({ playlist: 'x' });
    const loader = new P({});
    const cb = callbacks();
    expect(() =>
      loader.load({ url: 'https://film.test/master.m3u8', type: 'manifest' }, {}, cb),
    ).not.toThrow();
    expect(loader.stats.loading.start).toBe(12_345);
    expect(bank.playlistText).not.toHaveBeenCalled();
    expect(loader.inner.callbacks).toBe(cb);
  });
});

describe('abort or destroy from inside the decider', () => {
  const decisions = {
    answer: () => ANSWER(),
    wait: () => ({ action: 'wait', cancel: vi.fn() }),
    pass: () => ({ action: 'pass' }),
  };

  for (const [name, make] of Object.entries(decisions)) {
    it(`reports one onAbort when it aborts the load and returns ${name}`, async () => {
      const { F, callbacks, bank } = setup();
      const loader = new F({});
      const cb = callbacks();
      const ctx = fragContext(1);
      const decision = make();
      bank.fragmentRequest.mockImplementation(() => {
        loader.abort();
        return decision;
      });
      loader.load(ctx, {}, cb);
      await flush();
      expect(cb.onAbort).toHaveBeenCalledTimes(1);
      expect(cb.onAbort).toHaveBeenCalledWith(loader.stats, ctx, null);
      expect(cb.onSuccess).not.toHaveBeenCalled();
      expect(loader.inner.loads).toEqual([]);
      if (decision.cancel) expect(decision.cancel).toHaveBeenCalledTimes(1);
    });

    it(`starts nothing when it destroys the load and returns ${name}`, async () => {
      const { F, callbacks, bank } = setup();
      const loader = new F({});
      const cb = callbacks();
      const inner = loader.inner;
      bank.fragmentRequest.mockImplementation(() => {
        loader.destroy();
        return make();
      });
      expect(() => loader.load(fragContext(1), {}, cb)).not.toThrow();
      await flush();
      expect(cb.onSuccess).not.toHaveBeenCalled();
      expect(cb.onAbort).not.toHaveBeenCalled();
      expect(inner.loads).toEqual([]);
      expect(inner.destroyed).toBe(true);
    });
  }

  it('reports the abort of a playlist load aborted inside playlistText', async () => {
    const { P, callbacks, bank } = setup();
    const loader = new P({});
    const cb = callbacks();
    const ctx = { url: 'https://film.test/r480/index.m3u8', type: 'level', level: 2 };
    bank.playlistText.mockImplementation(() => {
      loader.abort();
      return '#EXTM3U\n';
    });
    loader.load(ctx, {}, cb);
    await flush();
    expect(cb.onAbort).toHaveBeenCalledWith(loader.stats, ctx, null);
    expect(cb.onSuccess).not.toHaveBeenCalled();
    expect(loader.inner.loads).toEqual([]);
  });
});
