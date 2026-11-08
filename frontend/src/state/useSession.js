import { useCallback, useEffect, useState } from 'react';
import { SESSION_EXPIRED_EVENT, announceSignedIn } from '../api/client.js';
import { sessionApi } from '../api/resources.js';

/**
 * Tracks whether the session has expired (any 401 from the API) and signs in again.
 * Nothing navigates: the route and playback position stay exactly where they were, and
 * resources reload once sign-in succeeds.
 */
export function useSession() {
  const [expired, setExpired] = useState(false);
  const [error, setError] = useState(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    const onExpired = () => setExpired(true);
    window.addEventListener(SESSION_EXPIRED_EVENT, onExpired);
    return () => window.removeEventListener(SESSION_EXPIRED_EVENT, onExpired);
  }, []);

  const signIn = useCallback(async (password) => {
    setSubmitting(true);
    setError(null);
    try {
      await sessionApi.signIn(password);
      setExpired(false);
      announceSignedIn();
      return true;
    } catch {
      setError("That password didn't work. Try again.");
      return false;
    } finally {
      setSubmitting(false);
    }
  }, []);

  return { expired, error, submitting, signIn };
}
