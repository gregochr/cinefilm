import { useEffect, useState } from 'react';
import { SIGNED_IN_EVENT } from '../../api/client.js';

/**
 * A count that goes up each time a sign-in succeeds (the sign-in card shows over a screen that stays mounted after a
 * 401). A screen passes it to a state hook, which asks again when it changes; the listener lives here, not in `state/`.
 */
export function useSignInRecovery() {
  const [count, setCount] = useState(0);
  useEffect(() => {
    const onSignedIn = () => setCount((n) => n + 1);
    window.addEventListener(SIGNED_IN_EVENT, onSignedIn);
    return () => window.removeEventListener(SIGNED_IN_EVENT, onSignedIn);
  }, []);
  return count;
}
