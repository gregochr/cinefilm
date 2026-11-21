import { useCallback, useEffect, useRef, useState } from 'react';
import { announceLibraryChanged } from '../api/client.js';
import { adminApi } from '../api/resources.js';
import { bestGuess, buildTodoGroups, confirmProblem, leaveProblem } from './adminTodo.js';
import { useAttention } from './useAdmin.js';
import { useFullNeed } from './useFullNeed.js';
import { useResource } from './useResource.js';

/**
 * The three lists behind the To do groups, each read on its own request so one failing leaves the others on screen:
 * the attention list (pick the right film, several files), the full-subtitles need list and the forced need list.
 * `fullNeed` is the full list's {@link useFullNeed} state (`saving`, `problems`, `choose`), which the rows' owner
 * choices use. `onFullSaved(row)` runs just before `onChanged()` when such a choice was saved (not when it failed).
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
 *
 * `leaveUnmatched(item, { onHidden })` is "Leave unmatched": the same busy, hiding, focus and failure handling, but it
 * posts the leave route and tells the global search nothing (the film stays in the library as it was).
 */
export function useAdminTodo({ settledAt, subtitlesVersion, changed, onChanged, onFullSaved }) {
  const attention = useAttention(`${settledAt}|${changed}`);
  // The full list is the Subtitles page's own hook, so a row's owner choice saves, shows and fails the same way here.
  const fullNeed = useFullNeed(`todo:${subtitlesVersion}|${changed}`, {
    onSaved: (row) => {
      onFullSaved?.(row);
      onChanged();
    },
  });
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

  // One row action: a post for the film, its failure words, and what a success does. Shared by both row buttons.
  const act = useCallback(
    async (filmId, { send, problemFor, announce, onHidden }) => {
      if (inFlight.current.has(filmId)) return;
      inFlight.current.add(filmId);
      setConfirming((ids) => [...ids, filmId]);
      setProblem(filmId, null);
      try {
        await send();
      } catch (failure) {
        const { message, gone } = problemFor(failure);
        if (mounted.current) setProblem(filmId, message);
        if (gone) onChanged();
        return;
      } finally {
        inFlight.current.delete(filmId);
        if (mounted.current) setConfirming((ids) => ids.filter((id) => id !== filmId));
      }
      if (announce) announceLibraryChanged();
      if (mounted.current) {
        const list = latestAttention.current;
        setHidden((h) => ({ list, ids: h.list === list ? [...h.ids, filmId] : [filmId] }));
        onHidden?.();
      }
      onChanged();
    },
    [onChanged, setProblem],
  );

  const confirm = useCallback(
    async (item, { onHidden } = {}) => {
      const guess = bestGuess(item);
      if (!guess) return;
      // Global search keeps its pre-match index otherwise, and the layout outlives this page.
      await act(item.filmId, {
        send: () => adminApi.applyMatch(item.filmId, guess.tmdbId),
        problemFor: confirmProblem,
        announce: true,
        onHidden,
      });
    },
    [act],
  );

  const leaveUnmatched = useCallback(
    (item, { onHidden } = {}) =>
      act(item.filmId, {
        send: () => adminApi.leaveUnmatched(item.filmId),
        problemFor: leaveProblem,
        announce: false,
        onHidden,
      }),
    [act],
  );

  const hiddenIds = hidden.list === attention.data ? hidden.ids : [];
  const problems = problemState.list === attention.data ? problemState.byId : {};
  const attentionItems = attention.data
    ? attention.data.items.filter(
        (item) => !(item.kind === 'needs-match' && hiddenIds.includes(item.filmId)),
      )
    : null;
  const fullRows = fullNeed.rows;
  const full = { data: fullRows, error: fullNeed.error, reload: fullNeed.reload };
  const forcedRows = forced.data?.films ?? null;
  const groups = buildTodoGroups({ attention: attentionItems, full: fullRows, forced: forcedRows });
  // "Nothing to do." only once every list has been read: a list that failed or has not answered says nothing.
  const allRead = attentionItems !== null && fullRows !== null && forcedRows !== null;

  return {
    attention,
    full,
    fullNeed,
    forced,
    groups,
    allRead,
    confirm,
    leaveUnmatched,
    confirming,
    problems,
  };
}
