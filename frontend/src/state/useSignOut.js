import { useCallback, useState } from 'react';
import { announceSignedOut } from '../api/client.js';
import { sessionApi } from '../api/resources.js';

/** Signs out and announces it so the sign-in card appears; reports a failure for the caller to show. */
export function useSignOut() {
  const [signingOut, setSigningOut] = useState(false);
  const [error, setError] = useState(null);

  const signOut = useCallback(async () => {
    setSigningOut(true);
    setError(null);
    try {
      await sessionApi.signOut();
      announceSignedOut();
    } catch {
      setError("Couldn't sign out. Try again.");
    } finally {
      setSigningOut(false);
    }
  }, []);

  return { signingOut, error, signOut };
}
