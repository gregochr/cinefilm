import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { adminApi } from '../api/resources.js';
import { applyRow, needProblem } from './forcedNeed.js';
import { useResource } from './useResource.js';

const without = (record, key) => {
  const { [key]: _dropped, ...rest } = record;
  return rest;
};

/**
 * The Admin list of which films need forced subtitles, and the owner's choice per film. `refreshKey` changes
 * whenever the subtitle files may have changed (a scan, a file attached, moved or detached), and the list is read
 * again. A choice is one request per film: a newer click on the same film aborts the older request and wins, other
 * films stay free, and a failure leaves the row as it was with `problems[filmId]` saying why. The answer of a
 * request is applied to its row; nothing is refetched.
 */
export function useForcedNeed(refreshKey) {
  const { data, error, loading, reload } = useResource(
    `admin-forced-need:${refreshKey}`,
    (options) => adminApi.forcedNeed(options),
  );
  // The rows answered since `base` was read: a list read again replaces them, so the server's list wins.
  const [applied, setApplied] = useState({ base: null, rows: [] });
  const [problems, setProblems] = useState({});
  const [saving, setSaving] = useState({});
  const latest = useRef(data);
  latest.current = data;
  // The request each film is waiting on: only the answer of the one in this map is used.
  const inflight = useRef(new Map());

  useEffect(() => {
    const requests = inflight.current;
    return () => {
      requests.forEach((request) => request.controller.abort());
      requests.clear();
    };
  }, []);

  const choose = useCallback(async (filmId, choice) => {
    inflight.current.get(filmId)?.controller.abort();
    const request = { controller: new AbortController() };
    inflight.current.set(filmId, request);
    setProblems((prev) => without(prev, filmId));
    setSaving((prev) => ({ ...prev, [filmId]: true }));
    const current = () => inflight.current.get(filmId) === request;
    try {
      const row = await adminApi.setForcedNeed(filmId, choice, {
        signal: request.controller.signal,
      });
      if (!current()) return;
      setApplied((prev) => ({
        base: latest.current,
        rows: [...(prev.base === latest.current ? prev.rows : []), row],
      }));
    } catch (failure) {
      if (!current()) return;
      setProblems((prev) => ({ ...prev, [filmId]: needProblem(failure) }));
    } finally {
      if (current()) {
        inflight.current.delete(filmId);
        setSaving((prev) => without(prev, filmId));
      }
    }
  }, []);

  const rows = useMemo(() => {
    if (!data) return null;
    return applied.base === data
      ? applied.rows.reduce((list, row) => applyRow(list, row), data.films)
      : data.films;
  }, [data, applied]);

  return { rows, error, loading, reload, problems, saving, choose };
}
