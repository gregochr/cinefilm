import { useCallback, useEffect, useRef, useState } from 'react';
import { adminApi } from '../api/resources.js';
import { bestGuess, buildTodoGroups, confirmProblem } from './adminTodo.js';
import { useAttention } from './useAdmin.js';
import { useResource } from './useResource.js';

/**
 * The three lists behind the To do groups, each read on its own request so one failing leaves the others on screen:
 * the attention list (pick the right film, several files), the full-subtitles need list and the forced need list.
 * They are read again when `settledAt` (a scan or import finished), `subtitlesVersion` (a subtitle job finished) or
 * `changed` (a fix was applied) changes; a newer read aborts the older one (`useResource`).
 *
 * `confirm(item, { onHidden })` is "Yes, it's {Title} ({year})": it posts the item's best candidate through the
 * existing match route. A second call for the same film while its post is in flight does nothing (a ref, so two clicks
 * in one frame cannot both pass). On success the row is hidden at once, `onHidden()` runs (the screen moves focus
 * there and then, not through a fallback a later list change could consume; it is skipped when the page has gone), and
 * `onChanged()` re-reads the summary and the lists. The hidden row and any problem belong to the attention answer
 * on screen when they were set: a read that replaces it forgets them, so a row the server still lists shows again,
 * and a read that fails leaves the answer, and so the hiding, as it was. On failure the row stays with `problems[filmId]`
 * (a 404, the film gone, also reads the lists again).
 */
export function useAdminTodo({ settledAt, subtitlesVersion, changed, onChanged }) {
  const attention = useAttention(`${settledAt}|${changed}`);
  const full = useResource(`admin-todo-full:${subtitlesVersion}|${changed}`, (options) =>
    adminApi.fullNeed(options),
  );
  const forced = useResource(`admin-todo-forced:${subtitlesVersion}|${changed}`, (options) =>
    adminApi.forcedNeed(options),
  );

  const inFlight = useRef(new Set());
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);
  const [confirming, setConfirming] = useState([]);
  // Both are tied to one particular attention answer (see above).
  const [problemState, setProblemState] = useState({ list: null, byId: {} });
  const [hidden, setHidden] = useState({ list: null, ids: [] });
  const latestAttention = useRef(null);
  latestAttention.current = attention.data;

  const setProblem = useCallback((filmId, message) => {
    const list = latestAttention.current;
    setProblemState((p) => ({
      list,
      byId: { ...(p.list === list ? p.byId : {}), [filmId]: message },
    }));
  }, []);

  const confirm = useCallback(
    async (item, { onHidden } = {}) => {
      const guess = bestGuess(item);
      const { filmId } = item;
      if (!guess || inFlight.current.has(filmId)) return;
      inFlight.current.add(filmId);
      setConfirming((ids) => [...ids, filmId]);
      setProblem(filmId, null);
      try {
        await adminApi.applyMatch(filmId, guess.tmdbId);
      } catch (failure) {
        const { message, gone } = confirmProblem(failure);
        if (mounted.current) setProblem(filmId, message);
        if (gone) onChanged();
        return;
      } finally {
        inFlight.current.delete(filmId);
        if (mounted.current) setConfirming((ids) => ids.filter((id) => id !== filmId));
      }
      if (mounted.current) {
        const list = latestAttention.current;
        setHidden((h) => ({ list, ids: h.list === list ? [...h.ids, filmId] : [filmId] }));
        onHidden?.();
      }
      onChanged();
    },
    [onChanged, setProblem],
  );

  const hiddenIds = hidden.list === attention.data ? hidden.ids : [];
  const problems = problemState.list === attention.data ? problemState.byId : {};
  const attentionItems = attention.data
    ? attention.data.items.filter(
        (item) => !(item.kind === 'needs-match' && hiddenIds.includes(item.filmId)),
      )
    : null;
  const fullRows = full.data?.films ?? null;
  const forcedRows = forced.data?.films ?? null;
  const groups = buildTodoGroups({ attention: attentionItems, full: fullRows, forced: forcedRows });
  // "Nothing to do." only once every list has been read: a list that failed or has not answered says nothing.
  const allRead = attentionItems !== null && fullRows !== null && forcedRows !== null;

  return { attention, full, forced, groups, allRead, confirm, confirming, problems };
}
