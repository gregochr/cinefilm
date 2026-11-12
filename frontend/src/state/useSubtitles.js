import { useCallback, useRef, useState } from 'react';
import { announceLibraryChanged } from '../api/client.js';
import { adminApi } from '../api/resources.js';
import { nudgedOffset, subtitleProblem } from './subtitleAdmin.js';
import { useResource } from './useResource.js';

/**
 * The Admin "Subtitles" panel: every forced subtitle file and the owner's actions on them. Each action is one
 * request on a row id; afterwards the list is read again and the browse cache is emptied, so the player and the
 * film page see the new timing. `busy` is the id of the file an action is running on, `problem` the last failure
 * as `{ id, text }`.
 */
export function useSubtitles(version) {
  const { data, error, loading, reload } = useResource(`admin-subtitles:${version}`, (options) =>
    adminApi.subtitles(options),
  );
  // What an action answered for its file, shown until the list read again arrives: a second nudge in the
  // meantime must start from the new offset, not the one the old list still holds.
  const [answers, setAnswers] = useState({ base: null, rows: {} });
  const running = useRef(false);
  const latest = useRef(data);
  latest.current = data;
  const [busy, setBusy] = useState(null);
  const [problem, setProblem] = useState(null);
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
      ? data.map((row) => (answers.base === data ? answers.rows[row.id] : undefined) ?? row)
      : null,
    error,
    loading,
    reload,
    busy,
    problem,
    changes,
    attach: (id, target) => act(id, () => adminApi.attachSubtitle(id, target)),
    detach: (id) => act(id, () => adminApi.detachSubtitle(id)),
    nudge: (item, delta) =>
      act(item.id, () => adminApi.setSubtitleOffset(item.id, nudgedOffset(item.offsetMs, delta))),
    resetTiming: (id) => act(id, () => adminApi.setSubtitleOffset(id, 0)),
    verify: (id) => act(id, () => adminApi.verifySubtitle(id)),
    unverify: (id) => act(id, () => adminApi.unverifySubtitle(id)),
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
