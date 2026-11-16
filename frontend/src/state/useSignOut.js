import { useCallback, useRef, useState } from 'react';
import { announceSignedOut } from '../api/client.js';
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
    } catch {
      setError("Couldn't sign out. Try again.");
    } finally {
      inFlight.current = false;
      setSigningOut(false);
    }
  }, []);

  return { signingOut, error, signOut };
}
