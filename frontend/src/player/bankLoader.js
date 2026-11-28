// The loaders that let the film bank answer hls.js (outside-bank-plan.md, decision 5 and the armed copies of
// decision 2). Nothing installs them yet (B5b wires them).
//
// This relies on hls.js 1.7.4: `fragment-loader.ts` builds one fLoader per load and assigns
// `frag.stats = loader.stats` before `load`; each callback is followed by `destroy()`, also from inside `load`;
// the payload handed to `onSuccess` is transferred to the demux worker; `onSuccess` gets `(response, stats,
// context, networkDetails)` and hls.js reads `context.resetIV` afterwards; `base-loader.ts` reports `abort()` through
// `onAbort(stats, context, details)`. The bandwidth estimate is taken from `stats.loading` (start, first, end), so
// replaying a real fetch's own durations repeats its sample. A version bump must re-read them.

/**
 * The bank decider B2b implements (the loaders only ask it things):
 *
 * - `armed(): boolean`: while true, a fragment loaded through the inner loader is copied to the decider.
 * - `fragmentRequest(request, reply)`: asked once per media fragment load, `request` being `{ sn, level, url,
 *   frag, context }` (`frag` and `context` are hls.js's own). Returns one of
 *   - `{ action: 'answer', bytes, timings }`: `bytes` an ArrayBuffer, `timings` `{ start, first, end }` (the real
 *     fetch's, in any one clock: only the differences are used). The loader hands out a fresh copy.
 *   - `{ action: 'wait', cancel? }`: the decider answers later by calling exactly one of `reply.success(bytes,
 *     timings)`, `reply.error({ code, text })` or `reply.timeout()`; `cancel()` is called if hls.js aborts or
 *     destroys the loader first, after which the replies do nothing.
 *   - `{ action: 'pass' }`: the inner loader loads it, unchanged.
 * - `fragmentLoaded({ sn, level, url, bytes, timings })`: while armed, after an inner load succeeded and before
 *   hls.js's `onSuccess`: `bytes` is a copy that stays intact when hls.js transfers its own payload; `timings` are
 *   the real stats' `loading` start, first and end.
 * - `playlistText(context): string | null`: the held text of the media playlist for `context.level`, or null.
 *
 * An answer is marked on the stats as `stats.bankAnswered = true`: the engine takes no throughput sample from it.
 */

const LEVEL_PLAYLIST = 'level';
const MEDIA_FRAGMENT = 'media-fragment';

/** The loader's own (not the inner loader's) in-flight state for a load it is serving itself. */
class OwnLoad {
  constructor(context) {
    this.context = context;
    this.cancel = null;
    this.done = false;
    this.cancelled = false; // aborted or destroyed before it finished
  }
}

function createBankLoader(Inner, bank, now, beginLoad, onPass) {
  return class BankLoader {
    constructor(config) {
      // In the constructor: hls.js reads and writes `loader.stats` before it calls `load`.
      this.inner = new Inner(config);
      this.own = null;
      this.callbacks = null;
    }

    get stats() {
      return this.inner.stats;
    }

    set stats(value) {
      this.inner.stats = value;
    }

    // While the bank serves a load itself the inner loader never runs, so the load's context is kept here:
    // hls.js's playlist loader reads `loader.context` to ignore a duplicate level load.
    get context() {
      return this.own === null ? this.inner.context : this.own.context;
    }

    set context(value) {
      this.inner.context = value;
    }

    getCacheAge() {
      return this.inner.getCacheAge?.() ?? null;
    }

    getResponseHeader(name) {
      return this.inner.getResponseHeader?.(name) ?? null;
    }

    load(context, config, callbacks) {
      const own = new OwnLoad(context);
      const done = (deliver) => {
        if (own.done) return;
        own.done = true;
        own.cancel = null;
        deliver();
      };
      // Before asking the decider: it may abort or destroy this loader from inside its own call, and hls.js's
      // loaders stamp `loading.start` at load time (a wait counts from here; an inner load stamps it itself).
      this.own = own;
      this.callbacks = callbacks;
      this.inner.stats.loading.start = now();
      const handed = beginLoad({ loader: this, context, callbacks, own, done });
      if (own.cancelled) return; // aborted or destroyed inside the decider: nothing more to start
      if (handed === 'pass') {
        this.own = null;
        this.inner.stats.loading.start = 0;
        onPass?.(context);
        this.inner.load(context, config, this.armedCallbacks(context, callbacks));
      }
    }

    // While armed, the inner load's payload is copied to the bank before hls.js's `onSuccess` (which hands it to
    // the worker, detaching it).
    armedCallbacks(context, callbacks) {
      const frag = context.frag;
      if (context.type !== MEDIA_FRAGMENT || frag?.sn === 'initSegment' || !bank.armed()) {
        return callbacks;
      }
      return {
        ...callbacks,
        onSuccess: (response, stats, ctx, details) => {
          if (response.data instanceof ArrayBuffer) {
            bank.fragmentLoaded({
              sn: frag.sn,
              level: frag.level,
              url: context.url,
              bytes: response.data.slice(0),
              timings: {
                start: stats.loading.start,
                first: stats.loading.first,
                end: stats.loading.end,
              },
            });
          }
          callbacks.onSuccess(response, stats, ctx, details);
        },
      };
    }

    abort() {
      const own = this.own;
      if (own === null) {
        this.inner.abort();
        return;
      }
      if (own.done) return;
      own.done = own.cancelled = true;
      own.cancel?.();
      own.cancel = null;
      const stats = this.inner.stats;
      stats.aborted = true;
      this.callbacks?.onAbort?.(stats, own.context, null);
    }

    destroy() {
      const own = this.own;
      if (own !== null && !own.done) {
        own.done = own.cancelled = true;
        own.cancel?.();
        own.cancel = null;
      }
      this.inner.destroy();
    }
  };
}

// Fills `stats` as hls.js's own loaders leave them after a success, from a held fetch's timings.
function fillStats(stats, timings, size, now) {
  const end = now();
  const start = end - (timings.end - timings.start);
  stats.loading.start = start;
  stats.loading.first = start + (timings.first - timings.start);
  stats.loading.end = end;
  stats.loaded = stats.total = size;
  stats.aborted = false;
  stats.bankAnswered = true;
}

/**
 * Returns `{ fLoader, pLoader }`: loader classes for hls.js's config. `InnerF` and `InnerP` are the loader
 * classes that load what the bank does not answer (today's fLoader, hls.js's default loader). `bank` is the
 * decider described above. `now` is the clock `stats` are written in (hls.js's own is `performance.now()`).
 * `onPass(context)`, optional, is called for each media request the fLoader hands to `InnerF` (the request goes
 * to the server through hls.js's own loader, not answered by the bank); never for a playlist.
 */
export function createBankLoaders({ InnerF, InnerP, bank, now = () => performance.now(), onPass }) {
  const deliverAnswer = (loader, context, callbacks, bytes, timings) => {
    const stats = loader.inner.stats;
    fillStats(stats, timings, bytes.byteLength, now);
    callbacks.onSuccess({ url: context.url, data: bytes.slice(0) }, stats, context, null);
  };

  const fragmentStart = ({ loader, context, callbacks, own, done }) => {
    const frag = context.frag;
    if (context.type !== MEDIA_FRAGMENT || typeof frag?.sn !== 'number') return 'pass';
    const reply = {
      success: (bytes, timings) =>
        done(() => deliverAnswer(loader, context, callbacks, bytes, timings)),
      error: (error) => done(() => callbacks.onError(error, context, null, loader.inner.stats)),
      timeout: () => done(() => callbacks.onTimeout(loader.inner.stats, context, null)),
    };
    const decision = bank.fragmentRequest(
      { sn: frag.sn, level: frag.level, url: context.url, frag, context },
      reply,
    );
    if (decision.action === 'pass') return 'pass';
    if (decision.action === 'answer') {
      queueMicrotask(() =>
        done(() => deliverAnswer(loader, context, callbacks, decision.bytes, decision.timings)),
      );
    } else if (own.cancelled) {
      decision.cancel?.(); // aborted or destroyed from inside the decider's own reply
    } else if (!own.done) {
      own.cancel = decision.cancel ?? null;
    }
    return 'own';
  };

  const playlistStart = ({ loader, context, callbacks, done }) => {
    if (context.type !== LEVEL_PLAYLIST) return 'pass';
    const text = bank.playlistText(context);
    if (text === null) return 'pass';
    queueMicrotask(() =>
      done(() => {
        const stats = loader.inner.stats;
        const t = now();
        stats.loading.start = stats.loading.first = stats.loading.end = t;
        stats.loaded = stats.total = text.length;
        stats.aborted = false;
        stats.bankAnswered = true;
        callbacks.onSuccess({ url: context.url, data: text }, stats, context, null);
      }),
    );
    return 'own';
  };

  return {
    fLoader: createBankLoader(InnerF, bank, now, fragmentStart, onPass),
    pLoader: createBankLoader(InnerP, bank, now, playlistStart),
  };
}
