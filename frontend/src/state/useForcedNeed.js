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
 * again. A choice is one write per film at a time, never aborted (the server may still process it): a newer
 * click on a film that is saving waits and replaces any earlier waiting choice, and is sent when the write settles,
 * even if that write failed. Only the last write's answer is applied to the row (`saving[filmId]` is set until
 * then) and only its failure is reported in `problems[filmId]`, with the row left at the last answer the server
 * gave. Other films stay free. Nothing is refetched, and a list read that started before the save arrived cannot
 * undo it. After unmount the write in flight is never aborted, its result is ignored (no state is set) and no waiting
 * choice is sent.
 */
export function useForcedNeed(refreshKey) {
  // One counter orders everything: a list read is numbered when it starts, a saved choice when its answer arrives.
  const sequence = useRef(0);
  const { data, error, loading, reload } = useResource(
    `admin-forced-need:${refreshKey}`,
    async (options) => {
      const startedAt = ++sequence.current;
      return { ...(await adminApi.forcedNeed(options)), startedAt };
    },
  );
  // The latest saved row of each film and when its answer arrived. A list that started reading before that may
  // predate the save, so the row is laid over it; a list that started after it already holds the save.
  const [saved, setSaved] = useState({});
  const [problems, setProblems] = useState({});
  const [saving, setSaving] = useState({});
  // The write each film is waiting on, with the choice a newer click wants sent next: `{ pending: null | { choice } }`.
  const chains = useRef(new Map());
  const mounted = useRef(true);

  useEffect(() => {
    mounted.current = true;
    return () => {
      // Nothing is aborted: a write in flight settles on the server, and its result is ignored here.
      mounted.current = false;
    };
  }, []);

  const choose = useCallback(async (filmId, choice) => {
    const running = chains.current.get(filmId);
    if (running) {
      // One write per film at a time, never aborted: the newest wish waits its turn and replaces an older one.
      running.pending = { choice };
      return;
    }
    const chain = { pending: null };
    chains.current.set(filmId, chain);
    setProblems((prev) => without(prev, filmId));
    setSaving((prev) => ({ ...prev, [filmId]: true }));
    let next = { choice };
    let answer = null; // the last answer the server gave in this chain, stamped when it arrived
    let failure = null; // the failure of the last write, if it failed
    while (next) {
      chain.pending = null;
      try {
        const row = await adminApi.setForcedNeed(filmId, next.choice);
        answer = { row, answeredAt: ++sequence.current };
        failure = null;
      } catch (error) {
        if (!mounted.current) return;
        failure = error; // only the last write's failure is reported; a queued choice is still sent
      }
      if (!mounted.current) return;
      next = chain.pending;
    }
    chains.current.delete(filmId);
    if (answer) setSaved((prev) => ({ ...prev, [filmId]: answer }));
    if (failure) setProblems((prev) => ({ ...prev, [filmId]: needProblem(failure) }));
    setSaving((prev) => without(prev, filmId));
  }, []);

  const rows = useMemo(() => {
    if (!data) return null;
    return Object.values(saved)
      .filter((entry) => entry.answeredAt > data.startedAt)
      .reduce((list, entry) => applyRow(list, entry.row), data.films);
  }, [data, saved]);

  return { rows, error, loading, reload, problems, saving, choose };
}
