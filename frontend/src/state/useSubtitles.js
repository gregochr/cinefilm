import { useCallback, useState } from 'react';
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
export function useSubtitles() {
  const { data, error, loading, reload } = useResource('admin-subtitles', (options) =>
    adminApi.subtitles(options),
  );
  const [busy, setBusy] = useState(null);
  const [problem, setProblem] = useState(null);

  const act = useCallback(
    async (id, call) => {
      setBusy(id);
      setProblem(null);
      try {
        await call();
        announceLibraryChanged();
        reload();
        return true;
      } catch (failure) {
        setProblem({ id, text: subtitleProblem(failure) });
        return false;
      } finally {
        setBusy(null);
      }
    },
    [reload],
  );

  return {
    items: data ?? null,
    error,
    loading,
    reload,
    busy,
    problem,
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
 * One file's cues on the edition's timeline. Asked again when the offset changes and when the file is attached
 * somewhere else (another edition, part, or whole film versus one part): the times depend on where it sits.
 */
export function useSubtitleCues(item) {
  const place = `${item.editionId}:${item.whole}:${item.part}`;
  return useResource(`admin-subtitle-cues:${item.id}:${place}:${item.offsetMs}`, (options) =>
    adminApi.subtitleCues(item.id, options),
  );
}
