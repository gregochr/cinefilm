import { useCallback, useEffect, useRef, useState } from 'react';
import { SESSION_EXPIRED_EVENT, announceSignedIn } from '../api/client.js';
import { sessionApi } from '../api/resources.js';

/**
 * Tracks whether the session has expired (any 401 from the API, or an explicit sign-out) and
 * signs in again. Nothing navigates: the route and playback position stay exactly where they
 * were, and resources reload once sign-in succeeds. `reason` is 'expired' or 'signed-out'; the
 * first one recorded wins until the next successful sign-in.
 */
export function useSession() {
  const [expired, setExpired] = useState(false);
  const expiredRef = useRef(false);
  const [reason, setReason] = useState('expired');
  const [error, setError] = useState(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    const onExpired = (event) => {
      // Polling 401s after a sign-out must not flip the copy back to "expired".
      if (!expiredRef.current) setReason(event.detail?.reason ?? 'expired');
      expiredRef.current = true;
      setExpired(true);
    };
    window.addEventListener(SESSION_EXPIRED_EVENT, onExpired);
    return () => window.removeEventListener(SESSION_EXPIRED_EVENT, onExpired);
  }, []);

  const signIn = useCallback(async (password) => {
    setSubmitting(true);
    setError(null);
    try {
      await sessionApi.signIn(password);
      expiredRef.current = false;
      setExpired(false);
      setReason('expired');
      announceSignedIn();
      return true;
    } catch {
      setError("That password didn't work. Try again.");
      return false;
    } finally {
      setSubmitting(false);
    }
  }, []);

  return { expired, reason, error, submitting, signIn };
}
