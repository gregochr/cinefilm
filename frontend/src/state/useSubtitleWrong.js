import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from '../api/client.js';
import { adminApi } from '../api/resources.js';
import { Changed, LookupFailed, shownTargets, wrongProblem, wrongTargets } from './subtitleFix.js';

/**
 * The player's "Subtitles are wrong": deletes the OpenSubtitles download that is showing for the edition, which the
 * server also blocks (it never fetches that file for that edition again). The row id is read when the viewer asks
 * (`GET /api/admin/subtitles`), so it is never stale, then `DELETE /api/admin/subtitles/{id}` runs on it. It touches
 * nothing of the playback: no session, no seek, no reload. `report(kind, ids)` (`ids`: the sidecar ids the film showed
 * for that kind) resolves true once the download is gone; a second call while one runs does nothing (a ref, so two clicks in one frame send one request); a failure sets
 * `problem` in words (shown only while that kind of download still shows) and leaves everything as it was. A 404 on
 * the delete counts as removed: the row was already gone.
 */
export function useSubtitleWrong({ filmId, editionId, kind: shownKind = null }) {
  const running = useRef(false);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState(null);
  // What shows now, for a request that ends after the viewer moved to another download.
  const shown = useRef(shownKind);
  shown.current = shownKind;

  const report = useCallback(
    async (kind, ids) => {
      if (running.current) return false;
      running.current = true;
      setBusy(true);
      setFailure(null);
      try {
        const rows = await adminApi.subtitles();
        const playing = wrongTargets(rows, { filmId, editionId, kind });
        // The film did not say which download it showed: nothing can be proven to be the one on screen.
        if (ids == null) throw new Changed();
        if (playing.length === 0) throw new LookupFailed();
        // Only the download the film showed: if another one plays now, the viewer never saw it, so nothing is deleted.
        const targets = shownTargets(playing, ids);
        if (targets.length === 0) throw new Changed();
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
        // A failure for a download that no longer shows is not kept: switching back must not bring it back.
        if (kind === shown.current) setFailure({ kind, text: wrongProblem(error) });
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
