import { useCallback, useEffect, useRef, useState } from 'react';
import { SIGNED_IN_EVENT } from '../api/client.js';

/**
 * Loads a resource. `key` identifies the request (change it to refetch); `fetcher` receives
 * `{ signal }`. Previous data stays visible while a new key loads, so search does not flash.
 * Refetches automatically after a sign-in that follows a session expiry.
 */
export function useResource(key, fetcher) {
  const [state, setState] = useState({ data: null, error: null, loading: true });
  const [tick, setTick] = useState(0);
  const fetcherRef = useRef(fetcher);
  useEffect(() => {
    fetcherRef.current = fetcher;
  });

  useEffect(() => {
    const onSignedIn = () => setTick((n) => n + 1);
    window.addEventListener(SIGNED_IN_EVENT, onSignedIn);
    return () => window.removeEventListener(SIGNED_IN_EVENT, onSignedIn);
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    setState((s) => ({ ...s, loading: true, error: null }));
    Promise.resolve()
      .then(() => fetcherRef.current({ signal: controller.signal }))
      .then((data) => {
        if (!controller.signal.aborted) setState({ data, error: null, loading: false });
      })
      .catch((error) => {
        if (controller.signal.aborted || error?.name === 'AbortError') return;
        setState((s) => ({ ...s, error, loading: false }));
      });
    return () => controller.abort();
  }, [key, tick]);

  const reload = useCallback(() => setTick((n) => n + 1), []);
  return { ...state, reload };
}
