import { useCallback, useRef, useState } from 'react';
import { adminApi } from '../api/resources.js';
import { trainFailure } from './journeysFormat.js';

/**
 * The write that gives a stored journey its train's file: `give(id, request)` sends one file. One at a time: a press
 * while one is in flight does nothing and resolves `{ ignored: true }`. A write is never aborted, because the server may
 * already have made it; it resolves `{ ok: true, response }` or `{ ok: false, failure }`, where the `failure`
 * (`trainFailure`) is also kept, with the id of the journey it was for, until `clearFailure` or the next write.
 */
export function useJourneyTrain() {
  const [state, setState] = useState({ busy: false, failure: null });
  const inFlight = useRef(false);

  const give = useCallback(async (id, request) => {
    if (inFlight.current) return { ignored: true };
    inFlight.current = true;
    setState({ busy: true, failure: null });
    try {
      const response = await adminApi.giveJourneyTrain(id, request);
      setState({ busy: false, failure: null });
      return { ok: true, response };
    } catch (error) {
      const failure = { id, ...trainFailure(error) };
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
  return { busy: state.busy, failure: state.failure, give, clearFailure };
}
