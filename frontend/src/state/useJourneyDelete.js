import { useCallback, useRef, useState } from 'react';
import { adminApi } from '../api/resources.js';
import { deleteFailure } from './journeysFormat.js';

/**
 * The journey delete write: `run(id)` deletes one stored journey. One at a time: a press while one is in flight does
 * nothing and resolves `{ ignored: true }`. A write is never aborted, because the server may already have made it; it
 * resolves `{ ok: true }` or `{ ok: false, failure }`, where the `failure` (`deleteFailure`) is also kept, with the id
 * of the journey it was for, until `clearFailure` or the next write.
 */
export function useJourneyDelete() {
  const [state, setState] = useState({ busy: false, failure: null });
  const inFlight = useRef(false);

  const run = useCallback(async (id) => {
    if (inFlight.current) return { ignored: true };
    inFlight.current = true;
    setState({ busy: true, failure: null });
    try {
      await adminApi.deleteJourney(id);
      setState({ busy: false, failure: null });
      return { ok: true };
    } catch (error) {
      const failure = { id, ...deleteFailure(error) };
      setState({ busy: false, failure });
      return { ok: false, failure };
    } finally {
      inFlight.current = false;
    }
  }, []);

  const clearFailure = useCallback(
    () => setState((s) => (s.failure ? { ...s, failure: null } : s)),
    [],
  );
  return { busy: state.busy, failure: state.failure, run, clearFailure };
}
