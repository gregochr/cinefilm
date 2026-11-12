import { useCallback, useEffect, useRef, useState } from 'react';
import { SIGNED_IN_EVENT } from '../api/client.js';
import { beginRequest, commit, lookup, MAX_AGE_MS, whenSettled } from './resourceCache.js';

/** The cached answer this hook may show for `key`, or null (no cache option, a miss, or too old). */
function cached(key, cache, maxAgeMs) {
  if (cache === 'revalidate') return lookup(key, MAX_AGE_MS);
  if (cache === 'fresh') return lookup(key, maxAgeMs);
  return null;
}

/** How often a fetch that crossed a change made in this tab is repeated before its answer is shown anyway. */
const MAX_RETRIES = 2;

const shown = (entry, key) => ({
  data: entry.data,
  json: entry.json,
  dataKey: key,
  error: null,
  loading: false,
});

const settled = (s) => (s.loading || s.error ? { ...s, error: null, loading: false } : s);

/**
 * Loads a resource. `key` identifies the request (change it to refetch); `fetcher` receives
 * `{ signal }`. Previous data stays visible while a new key loads, so search does not flash.
 * Refetches automatically after a sign-in that follows a session expiry.
 *
 * `options.cache` (default: none) uses the in-memory cache of `resourceCache.js`:
 * - `'revalidate'`: an answer the server gave under 30 minutes ago is shown at once and fetched again in the
 *   background (`loading` stays false); a JSON-equal answer keeps the shown object.
 * - `'fresh'` with `maxAgeMs`: an answer under `maxAgeMs` old is used with no fetch at all.
 * An answer is stored only if no change was made in this tab while its request was in flight.
 */
export function useResource(key, fetcher, options = {}) {
  const { cache, maxAgeMs } = options;
  const [state, setState] = useState(() => {
    const hit = cached(key, cache, maxAgeMs);
    return hit
      ? shown(hit, key)
      : { data: null, json: null, dataKey: undefined, error: null, loading: true };
  });
  const [tick, setTick] = useState(0);
  const seenTick = useRef(0);
  const quietTick = useRef(false);
  const fetcherRef = useRef(fetcher);
  useEffect(() => {
    fetcherRef.current = fetcher;
  });

  if (state.dataKey !== key) {
    // A new key whose answer is cached shows it in this render, not a frame later.
    const hit = cached(key, cache, maxAgeMs);
    if (hit) setState(shown(hit, key));
  }

  useEffect(() => {
    const onSignedIn = () => setTick((n) => n + 1);
    window.addEventListener(SIGNED_IN_EVENT, onSignedIn);
    return () => window.removeEventListener(SIGNED_IN_EVENT, onSignedIn);
  }, []);

  useEffect(() => {
    const forced = seenTick.current !== tick; // reload() and a sign-in always fetch, and show loading
    seenTick.current = tick;
    // A refresh() is a background look: the answer replaces the data, but `loading` and `error` are left alone.
    const quiet = forced && quietTick.current;
    quietTick.current = false;
    const hit = forced ? null : cached(key, cache, maxAgeMs);
    if (hit) setState((s) => (s.dataKey === key ? s : shown(hit, key)));
    if (hit && cache === 'fresh') return undefined;

    const controller = new AbortController();
    if (!hit && !quiet) setState((s) => ({ ...s, loading: true, error: null }));
    const run = (retries) => {
      const token = cache ? beginRequest() : null;
      Promise.resolve()
        .then(() => fetcherRef.current({ signal: controller.signal }))
        .then((data) => {
          if (controller.signal.aborted) return;
          if (!token) {
            setState({ data, json: null, dataKey: key, error: null, loading: false });
            return;
          }
          const result = commit(key, token, data);
          if (!result.stored && !hit && retries > 0) {
            // A change made in this tab crossed the request: the answer may predate it, so ask again.
            // ...once nothing is still in flight, or the retry would cross the same change.
            // A change that hangs is not waited for twice: the next answer is shown whatever it crossed.
            void whenSettled().then((settled) => {
              if (!controller.signal.aborted) run(settled ? retries - 1 : 0);
            });
            return;
          }
          setState((s) => {
            const has = s.dataKey === key;
            // Revalidating over what is shown: the shown answer may be newer than this one, so it stands.
            // A reload() always shows what the server answered: callers drop local changes when the data is replaced.
            if (has && ((!result.stored && hit) || (!forced && s.json === result.json)))
              return settled(s);
            return {
              data: forced ? data : result.data, // the cache hands back its own object for an equal answer
              json: result.json,
              dataKey: key,
              error: null,
              loading: false,
            };
          });
        })
        .catch((error) => {
          if (controller.signal.aborted || error?.name === 'AbortError') return;
          setState((s) => ({ ...s, error, loading: false }));
        });
    };
    run(MAX_RETRIES);
    return () => controller.abort();
  }, [key, tick, cache, maxAgeMs]);

  const reload = useCallback(() => {
    quietTick.current = false;
    setTick((n) => n + 1);
  }, []);
  // Like reload, for polling: the data on screen stays and `loading` does not flip while the new answer is fetched.
  const refresh = useCallback(() => {
    quietTick.current = true;
    setTick((n) => n + 1);
  }, []);
  const { data, error, loading } = state;
  return { data, error, loading, reload, refresh };
}
