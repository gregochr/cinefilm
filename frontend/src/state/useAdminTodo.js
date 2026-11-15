import { useCallback, useRef, useState } from 'react';
import { adminApi } from '../api/resources.js';
import { bestGuess, buildTodoGroups } from './adminTodo.js';
import { matchProblem } from './fixMatch.js';
import { useAttention } from './useAdmin.js';
import { useResource } from './useResource.js';

/**
 * The three lists behind the To do groups, each read on its own request so one failing leaves the others on screen:
 * the attention list (pick the right film, several files), the full-subtitles need list and the forced need list.
 * They are read again when `settledAt` (a scan or import finished), `subtitlesVersion` (a subtitle job finished) or
 * `changed` (a fix was applied) changes; a newer read aborts the older one (`useResource`).
 *
 * `confirm(item)` is "Yes, it's {Title} ({year})": it posts the item's best candidate through the existing match
 * route. A second call for the same film while its post is in flight does nothing (a ref, so two clicks in one frame
 * cannot both pass). On success the row leaves at once, `armFocusFallback()` readies the focus fallback for the row
 * that was clicked, and `onChanged()` re-reads the summary and the lists; the hidden row is forgotten when the
 * attention list is replaced by a read, so a row the server still lists shows again. On failure the row stays and
 * `problems[filmId]` holds the message until the next try.
 */
export function useAdminTodo({
  settledAt,
  subtitlesVersion,
  changed,
  onChanged,
  armFocusFallback,
}) {
  const attention = useAttention(`${settledAt}|${changed}`);
  const full = useResource(`admin-todo-full:${subtitlesVersion}|${changed}`, (options) =>
    adminApi.fullNeed(options),
  );
  const forced = useResource(`admin-todo-forced:${subtitlesVersion}|${changed}`, (options) =>
    adminApi.forcedNeed(options),
  );

  const inFlight = useRef(new Set());
  const [confirming, setConfirming] = useState([]);
  const [problems, setProblems] = useState({});
  // Film ids confirmed against one particular attention answer: they stay hidden only while that answer is shown.
  const [hidden, setHidden] = useState({ list: null, ids: [] });
  const latestAttention = useRef(null);
  latestAttention.current = attention.data;

  const confirm = useCallback(
    async (item) => {
      const guess = bestGuess(item);
      const { filmId } = item;
      if (!guess || inFlight.current.has(filmId)) return;
      inFlight.current.add(filmId);
      setConfirming((ids) => [...ids, filmId]);
      setProblems((p) => ({ ...p, [filmId]: null }));
      try {
        await adminApi.applyMatch(filmId, guess.tmdbId);
      } catch (failure) {
        setProblems((p) => ({ ...p, [filmId]: matchProblem(failure) }));
        return;
      } finally {
        inFlight.current.delete(filmId);
        setConfirming((ids) => ids.filter((id) => id !== filmId));
      }
      const list = latestAttention.current;
      setHidden((h) => ({ list, ids: h.list === list ? [...h.ids, filmId] : [filmId] }));
      armFocusFallback();
      onChanged();
    },
    [armFocusFallback, onChanged],
  );

  const hiddenIds = hidden.list === attention.data ? hidden.ids : [];
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
