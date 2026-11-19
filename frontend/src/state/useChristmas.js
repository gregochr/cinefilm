import { useCallback, useEffect, useRef, useState } from 'react';
import { getChristmas } from '../api/christmas.js';
import { SIGNED_IN_EVENT } from '../api/client.js';
import { POLL_MS } from './christmas.js';

/**
 * The Christmas films screen's data. Every read takes a number and its answer is shown only if no newer read has
 * started since. While keywords are still being fetched (`keywords.running`) the screen asks again every 30 seconds,
 * quietly: the list stays and a failed poll says nothing.
 *
 * Returns `{ data, loading, error, reload }`.
 */
export function useChristmas() {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const latest = useRef(0);

  // `quiet` is a poll.
  const read = useCallback((quiet) => {
    const mine = ++latest.current;
    // The failure stays on screen (with its Try again, busy) until the new answer replaces it.
    if (!quiet) setLoading(true);
    getChristmas().then(
      (body) => {
        if (mine !== latest.current) return;
        setData(body);
        setError(null);
        setLoading(false);
      },
      (failure) => {
        if (mine !== latest.current || quiet) return;
        setError(failure);
        setLoading(false);
      },
    );
  }, []);

  useEffect(() => {
    read(false);
  }, [read]);

  // A 401 shows the sign-in card over this page, which stays mounted: read again once signed in.
  useEffect(() => {
    const onSignedIn = () => read(false);
    window.addEventListener(SIGNED_IN_EVENT, onSignedIn);
    return () => window.removeEventListener(SIGNED_IN_EVENT, onSignedIn);
  }, [read]);

  const running = data?.keywords.running === true;
  useEffect(() => {
    if (!running) return undefined;
    const timer = setInterval(() => read(true), POLL_MS);
    return () => clearInterval(timer);
  }, [running, read]);

  return { data, loading, error, reload: () => read(false) };
}
