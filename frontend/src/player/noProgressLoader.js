// A segment loader that gives up on a download that has stopped moving. hls.js's XHR loader waits up to its
// `maxLoadTimeMs` (120 s) for a whole segment; on a poor link a transfer that has gone silent should end sooner
// and escalate through hls.js (its backoff, a lower rung, then "Reconnecting…") rather than sit there.
//
// This relies on hls.js 1.7.4's `fragment-loader.ts` (how it builds `fLoader`, reads and writes `loader.stats`
// before `load`, and destroys the loader inside each callback) and `xhr-loader.ts` / `base-loader.ts` (`stats`
// updated on every progress event, `abort()` reported through `onAbort`). A version bump must re-read them.
// Plan: docs/engineering/poor-link-plan.md, section 2.

export const NO_PROGRESS_MS = 12000;
export const WATCH_MS = 1000;

/**
 * Returns a loader class that wraps one `Base` loader (hls.js builds one per attempt). A check every `watchMs`
 * finds the response begun (`stats.loading.first > 0`, a change), or `stats.loaded` moved (a change), or
 * `noProgressMs` gone since the last change: then it aborts the inner request without reporting an abort and
 * calls `onTimeout(stats, context, null)` once, exactly as the XHR loader's own timeout does. Before the first
 * byte the inner loader's own first-byte limit applies.
 */
export function createNoProgressLoader(
  Base,
  { noProgressMs = NO_PROGRESS_MS, watchMs = WATCH_MS, now = () => Date.now() } = {},
) {
  return class NoProgressLoader {
    constructor(config) {
      // In the constructor: hls.js reads and writes `loader.stats` before it calls `load`.
      this.inner = new Base(config);
      this.timer = null;
      this.timedOut = false;
      if (typeof this.inner.getCacheAge === 'function') {
        this.getCacheAge = () => this.inner.getCacheAge();
      }
      if (typeof this.inner.getResponseHeader === 'function') {
        this.getResponseHeader = (name) => this.inner.getResponseHeader(name);
      }
    }

    get stats() {
      return this.inner.stats;
    }

    set stats(value) {
      this.inner.stats = value;
    }

    get context() {
      return this.inner.context;
    }

    set context(value) {
      this.inner.context = value;
    }

    load(context, config, callbacks) {
      // Each callback passed on is this wrapper's last action: hls.js destroys the loader inside it.
      const wrapped = {
        onSuccess: (response, stats, ctx, details) => {
          this.stop();
          callbacks.onSuccess(response, stats, ctx, details);
        },
        onError: (error, ctx, details, stats) => {
          this.stop();
          callbacks.onError(error, ctx, details, stats);
        },
        onTimeout: (stats, ctx, details) => {
          this.stop();
          callbacks.onTimeout(stats, ctx, details);
        },
        onAbort: (stats, ctx, details) => {
          this.stop();
          if (!this.timedOut) callbacks.onAbort?.(stats, ctx, details);
        },
      };
      // With `progressive` off hls.js gives none, and the XHR loader calls one on success when it is there.
      if (callbacks.onProgress) wrapped.onProgress = callbacks.onProgress;

      let began = false;
      let seenLoaded = 0;
      let changedAt = now();
      this.timer = setInterval(() => {
        const stats = this.inner.stats;
        if (!began) {
          if (!(stats.loading.first > 0)) return; // the response has not begun: the inner limit applies
          began = true;
          seenLoaded = stats.loaded;
          changedAt = now();
        } else if (stats.loaded !== seenLoaded) {
          seenLoaded = stats.loaded;
          changedAt = now();
        } else if (now() - changedAt >= noProgressMs) {
          this.timedOut = true;
          this.stop();
          this.inner.abort(); // reports onAbort, which is swallowed
          callbacks.onTimeout(stats, context, null);
        }
      }, watchMs);
      this.inner.load(context, config, wrapped);
    }

    stop() {
      clearInterval(this.timer);
      this.timer = null;
    }

    abort() {
      this.stop();
      this.inner.abort();
    }

    destroy() {
      this.stop();
      this.inner.destroy();
    }
  };
}
