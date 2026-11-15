import { useCallback, useRef, useState } from 'react';
import { adminApi } from '../api/resources.js';
import { LookupFailed, wrongProblem, wrongTargets } from './subtitleFix.js';

/**
 * The player's "Subtitles are wrong": deletes the OpenSubtitles download that is showing for the edition, which the
 * server also blocks (it never fetches that file for that edition again). The row id is read when the viewer asks
 * (`GET /api/admin/subtitles`), so it is never stale, then `DELETE /api/admin/subtitles/{id}` runs on it. It touches
 * nothing of the playback: no session, no seek, no reload. `report(kind)` resolves true once the download is gone;
 * a second call while one runs does nothing (a ref, so two clicks in one frame send one request); a failure sets
 * `problem` in words and leaves everything as it was.
 */
export function useSubtitleWrong({ filmId, editionId }) {
  const running = useRef(false);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState(null);

  const report = useCallback(
    async (kind) => {
      if (running.current) return false;
      running.current = true;
      setBusy(true);
      setProblem(null);
      try {
        const rows = await adminApi.subtitles();
        const targets = wrongTargets(rows, { filmId, editionId, kind });
        if (targets.length === 0) throw new LookupFailed();
        for (const target of targets) await adminApi.removeSubtitle(target.id);
        return true;
      } catch (failure) {
        setProblem(wrongProblem(failure));
        return false;
      } finally {
        running.current = false;
        setBusy(false);
      }
    },
    [filmId, editionId],
  );

  return { busy, problem, report };
}
