import { useEffect, useState } from 'react';
import { LIBRARY_CHANGED_EVENT } from '../../api/client.js';

/**
 * A count that goes up each time something announces `cinefilm:library-changed`: a scan, the Plex import or the
 * credits import finishing (each announces from its own status). A screen passes it to a state hook as part of a
 * request's key; the listener lives here, not in `state/`.
 */
export function useLibraryChanges() {
  const [count, setCount] = useState(0);
  useEffect(() => {
    const onChanged = () => setCount((n) => n + 1);
    window.addEventListener(LIBRARY_CHANGED_EVENT, onChanged);
    return () => window.removeEventListener(LIBRARY_CHANGED_EVENT, onChanged);
  }, []);
  return count;
}
