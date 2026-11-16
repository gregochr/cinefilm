import { useCallback, useRef, useState } from 'react';
import { ApiError, announceSignedOut } from '../api/client.js';
import { sessionApi } from '../api/resources.js';

/** Signs out and announces it so the sign-in card appears; reports a failure for the caller to show. */
export function useSignOut() {
  const [signingOut, setSigningOut] = useState(false);
  const [error, setError] = useState(null);

  const inFlight = useRef(false);

  const signOut = useCallback(async () => {
    if (inFlight.current) return;
    inFlight.current = true;
    setSigningOut(true);
    setError(null);
    try {
      await sessionApi.signOut();
      announceSignedOut();
    } catch (failure) {
      // A guard: a 401 on sign-out, from anything in front of the server, is treated as signed out (the server itself
      // answers 200 for an expired session).
      if (failure instanceof ApiError && failure.status === 401) announceSignedOut();
      else setError("Couldn't sign out. Try again.");
    } finally {
      inFlight.current = false;
      setSigningOut(false);
    }
  }, []);

  return { signingOut, error, signOut };
}
