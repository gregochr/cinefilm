import { useCallback, useEffect, useRef, useState } from 'react';
import { adminApi } from '../api/resources.js';
import { putBackProblem } from './adminTodo.js';
import { useResource } from './useResource.js';

/**
 * The films the owner left unmatched, read on their own request (a failing read leaves To do as it is), and the way
 * back. They are read again when `settledAt` (a scan or import finished) or `changed` (a fix, a leave or a put back)
 * changes; a newer read aborts the older one (`useResource`), so a read that crossed a write never shows its answer.
 *
 * `putBack(film, { onHidden })` posts the put-back route. A second call for the same film while its post is in flight
 * does nothing (a ref, so two clicks in one frame cannot both pass). On success the film is hidden at once, `onHidden()`
 * runs (skipped when the page has gone) and `onChanged()` re-reads this list and To do. The hiding and any problem
 * belong to the list answer on screen when they were set: a read that replaces it forgets them, so a film the server
 * still lists shows again, and a read that fails leaves the answer, and so the hiding, as it was. On failure the film
 * stays with `problems[id]` (a 404 or a stale 409 also reads the lists again).
 */
export function useLeftUnmatched({ settledAt, changed, onChanged }) {
  const list = useResource(`admin-todo-left:${settledAt}|${changed}`, (options) =>
    adminApi.leftUnmatched(options),
  );
  const inFlight = useRef(new Set());
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);
  const [busy, setBusy] = useState([]);
  const [problemState, setProblemState] = useState({ list: null, byId: {} });
  const [hidden, setHidden] = useState({ list: null, ids: [] });
  const latest = useRef(null);
  latest.current = list.data;

  const setProblem = useCallback((id, message) => {
    const current = latest.current;
    setProblemState((p) => ({
      list: current,
      byId: { ...(p.list === current ? p.byId : {}), [id]: message },
    }));
  }, []);

  const putBack = useCallback(
    async (film, { onHidden } = {}) => {
      const id = film.id;
      if (inFlight.current.has(id)) return;
      inFlight.current.add(id);
      setBusy((ids) => [...ids, id]);
      setProblem(id, null);
      try {
        await adminApi.putBack(id);
      } catch (failure) {
        const { message, gone } = putBackProblem(failure);
        if (mounted.current) setProblem(id, message);
        if (gone) onChanged();
        return;
      } finally {
        inFlight.current.delete(id);
        if (mounted.current) setBusy((ids) => ids.filter((x) => x !== id));
      }
      if (mounted.current) {
        const current = latest.current;
        setHidden((h) => ({ list: current, ids: h.list === current ? [...h.ids, id] : [id] }));
        onHidden?.();
      }
      onChanged();
    },
    [onChanged, setProblem],
  );

  const hiddenIds = hidden.list === list.data ? hidden.ids : [];
  const rows = list.data ? list.data.filter((film) => !hiddenIds.includes(film.id)) : [];
  const problems = problemState.list === list.data ? problemState.byId : {};
  return { rows, busy, problems, putBack };
}
