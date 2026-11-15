import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from '../api/client.js';
import { adminApi } from '../api/resources.js';
import { LookupFailed, wrongProblem, wrongTargets } from './subtitleFix.js';

/**
 * The player's "Subtitles are wrong": deletes the OpenSubtitles download that is showing for the edition, which the
 * server also blocks (it never fetches that file for that edition again). The row id is read when the viewer asks
 * (`GET /api/admin/subtitles`), so it is never stale, then `DELETE /api/admin/subtitles/{id}` runs on it. It touches
 * nothing of the playback: no session, no seek, no reload. `report(kind)` resolves true once the download is gone;
 * a second call while one runs does nothing (a ref, so two clicks in one frame send one request); a failure sets
 * `problem` in words (shown only while that kind of download still shows) and leaves everything as it was. A 404 on
 * the delete counts as removed: the row was already gone.
 */
export function useSubtitleWrong({ filmId, editionId, kind: shownKind = null }) {
  const running = useRef(false);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState(null);

  const report = useCallback(
    async (kind) => {
      if (running.current) return false;
      running.current = true;
      setBusy(true);
      setFailure(null);
      try {
        const rows = await adminApi.subtitles();
        const targets = wrongTargets(rows, { filmId, editionId, kind });
        if (targets.length === 0) throw new LookupFailed();
        for (const target of targets) {
          try {
            await adminApi.removeSubtitle(target.id);
          } catch (gone) {
            // Already gone between the list and the delete: that is what the viewer wanted.
            if (!(gone instanceof ApiError && gone.status === 404)) throw gone;
          }
        }
        return true;
      } catch (error) {
        setFailure({ kind, text: wrongProblem(error) });
        return false;
      } finally {
        running.current = false;
        setBusy(false);
      }
    },
    [filmId, editionId],
  );

  // A failure belongs to the download it was for: once another kind (or none) shows it is forgotten, so switching away
  // and back does not bring an old message back.
  useEffect(() => {
    setFailure((f) => (f && f.kind !== shownKind ? null : f));
  }, [shownKind]);
  return { busy, problem: failure?.kind === shownKind ? failure.text : null, report };
}
