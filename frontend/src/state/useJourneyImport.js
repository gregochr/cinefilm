import { useCallback, useRef, useState } from 'react';
import { adminApi } from '../api/resources.js';
import { importFailure } from './journeysFormat.js';

/**
 * The journey import write: `run(request)` posts one logger file's request. One at a time: a press while one is in
 * flight does nothing and resolves `{ ignored: true }`. A write is never aborted, because the server may already have
 * made it; it resolves `{ ok: true, response }` or `{ ok: false, failure }` (`importFailure`). `busy` and `failure` hold the
 * state meanwhile, and `clearFailure` forgets a failure.
 */
export function useJourneyImport() {
  const [state, setState] = useState({ busy: false, failure: null });
  const inFlight = useRef(false);

  const run = useCallback(async (request) => {
    if (inFlight.current) return { ignored: true };
    inFlight.current = true;
    setState({ busy: true, failure: null });
    try {
      const response = await adminApi.importJourney(request);
      setState({ busy: false, failure: null });
      return { ok: true, response };
    } catch (error) {
      const failure = importFailure(error);
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
