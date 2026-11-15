import { useCallback, useEffect, useState } from 'react';
import { adminApi } from '../api/resources.js';

const IDLE = { id: null, record: null, error: null };

/**
 * The whole record (with `report.minutes`) of the playback `playbackId`, for the per-minute view; none is read while
 * the id is null. Only the answer for the id asked for last is ever shown: a request for an earlier id is aborted
 * when the id changes, and an answer that still arrives late is ignored. `loading` is true from the moment the id
 * changes until its own answer (or failure) is in, so an earlier session's record is never shown under a later
 * one. `reload()` asks again after a failure.
 */
export function usePlaybackRecord(playbackId) {
  const [state, setState] = useState(IDLE);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    if (!playbackId) return undefined;
    const controller = new AbortController();
    let current = true;
    adminApi
      .playbackRecord(playbackId, { signal: controller.signal })
      .then((record) => {
        if (current) setState({ id: playbackId, record, error: null });
      })
      .catch((error) => {
        // An aborted request fails too, but by then `current` is false.
        if (current) setState({ id: playbackId, record: null, error });
      });
    return () => {
      current = false;
      controller.abort();
    };
  }, [playbackId, tick]);

  const reload = useCallback(() => {
    setState(IDLE);
    setTick((n) => n + 1);
  }, []);
  const answered = state.id === playbackId;
  return {
    record: answered ? state.record : null,
    error: answered ? state.error : null,
    loading: Boolean(playbackId) && !answered,
    reload,
  };
}
