import { useCallback, useRef, useState } from 'react';
import { adminApi } from '../api/resources.js';
import { trainFailure, trainRemoveFailure } from './journeysFormat.js';

/**
 * The writes on a stored journey's train: `give(id, request)` sends one file, `takeAway(id)` removes the train. One at
 * a time between the two: a press while either is in flight does nothing and resolves `{ ignored: true }`. A write is
 * never aborted, because the server may already have made it; it resolves `{ ok: true, response }` (`give`) or
 * `{ ok: true }` (`takeAway`), or `{ ok: false, failure }`, where the `failure` (`trainFailure`, `trainRemoveFailure`)
 * is also kept, with the id of the journey it was for, until `clearFailure` or the next write.
 */
export function useJourneyTrain() {
  const [state, setState] = useState({ busy: false, failure: null });
  const inFlight = useRef(false);

  const run = useCallback(async (id, send, describe, succeeded) => {
    if (inFlight.current) return { ignored: true };
    inFlight.current = true;
    setState({ busy: true, failure: null });
    try {
      const response = await send();
      setState({ busy: false, failure: null });
      return succeeded(response);
    } catch (error) {
      const failure = { id, ...describe(error) };
      setState({ busy: false, failure });
      return { ok: false, failure };
    } finally {
      inFlight.current = false;
    }
  }, []);

  const give = useCallback(
    (id, request) =>
      run(
        id,
        () => adminApi.giveJourneyTrain(id, request),
        trainFailure,
        (response) => ({ ok: true, response }),
      ),
    [run],
  );
  const takeAway = useCallback(
    (id) =>
      run(
        id,
        () => adminApi.removeJourneyTrain(id),
        trainRemoveFailure,
        () => ({ ok: true }),
      ),
    [run],
  );

  const clearFailure = useCallback(
    () => setState((s) => (s.failure ? { ...s, failure: null } : s)),
    [],
  );
  return { busy: state.busy, failure: state.failure, give, takeAway, clearFailure };
}
