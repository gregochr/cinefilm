import { useCallback, useRef, useState } from 'react';
import { announceLibraryChanged } from '../api/client.js';
import { adminApi } from '../api/resources.js';
import { subtitleProblem } from './subtitleAdmin.js';
import { useResource } from './useResource.js';

/**
 * The subtitle files and the owner's per-file actions on them (Film detail's Fix… panel). Each action is one
 * request on a row id; afterwards the list is read again and the browse cache is emptied, so the player and the
 * film page see the new timing. `busy` is the id of the file an action is running on, `problem` the last failure
 * as `{ id, text }`. `onChanged` runs after each action that succeeded, so views that depend on the files read again.
 */
export function useSubtitles(version, onChanged) {
  const { data, error, loading, reload } = useResource(`admin-subtitles:${version}`, (options) =>
    adminApi.subtitles(options),
  );
  // What an action answered for its file, shown until the list read again arrives, so a file that was
  // just checked is not drawn as unchecked in the meantime.
  const [answers, setAnswers] = useState({ base: null, rows: {} });
  const running = useRef(false);
  const changedHandler = useRef(onChanged);
  changedHandler.current = onChanged;
  const latest = useRef(data);
  latest.current = data;
  const [busy, setBusy] = useState(null);
  const [problem, setProblem] = useState(null);
  // Removed downloads stay out of the list at once: row ids are never reused, and a refresh that was already in
  // flight when the removal finished must not bring the row back.
  const [removedIds, setRemovedIds] = useState(() => new Set());
  // How many actions succeeded: views that depend on the files (which films have a forced subtitle) read again.
  const [changes, setChanges] = useState(0);

  const act = useCallback(
    async (id, call) => {
      // One request at a time, whatever the buttons say: a second call while one is in flight is dropped.
      if (running.current) return false;
      running.current = true;
      setBusy(id);
      setProblem(null);
      try {
        const answer = await call();
        if (answer?.id === id) {
          setAnswers((prev) => ({
            base: latest.current,
            rows: { ...(prev.base === latest.current ? prev.rows : {}), [id]: answer },
          }));
        }
        announceLibraryChanged();
        setChanges((n) => n + 1);
        changedHandler.current?.();
        reload();
        return true;
      } catch (failure) {
        setProblem({ id, text: subtitleProblem(failure) });
        return false;
      } finally {
        running.current = false;
        setBusy(null);
      }
    },
    [reload],
  );

  return {
    items: data
      ? data
          .filter((row) => !removedIds.has(row.id))
          .map((row) => (answers.base === data ? answers.rows[row.id] : undefined) ?? row)
      : null,
    error,
    loading,
    reload,
    busy,
    problem,
    changes,
    detach: (id) => act(id, () => adminApi.detachSubtitle(id)),
    verify: (id) => act(id, () => adminApi.verifySubtitle(id)),
    unverify: (id) => act(id, () => adminApi.unverifySubtitle(id)),
    /** Removes an OpenSubtitles download; resolves true once it is gone. */
    remove: async (id) => {
      const removed = await act(id, () => adminApi.removeSubtitle(id));
      if (removed) setRemovedIds((prev) => new Set(prev).add(id));
      return removed;
    },
  };
}

/**
 * What the cues of a file depend on: where it sits (edition, whole film or part), its offset and its version (when
 * the row last changed, so a scan that replaced the cues counts). Used as the resource key, and by the panel to
 * remount the list so cues for another key are never shown.
 */
export function cueResourceKey(item) {
  return `admin-subtitle-cues:${item.id}:${item.editionId}:${item.whole}:${item.part}:${item.offsetMs}:${item.version}`;
}

/** One file's cues on the edition's timeline; see {@link cueResourceKey} for when they are asked again. */
export function useSubtitleCues(item) {
  return useResource(cueResourceKey(item), (options) => adminApi.subtitleCues(item.id, options));
}
