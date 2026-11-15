import { useMemo } from 'react';
import { useFullNeed } from './useFullNeed.js';
import { useForcedNeed } from './useForcedNeed.js';
import { unresolvedRows } from './adminSubtitles.js';

/**
 * The two lists behind the Subtitles tabs, each on its own request so one failing leaves the other on screen: the
 * full-subtitles need list and the forced need list, with the owner's choice per film. Both are read again when
 * `subtitlesVersion` (a scan or subtitle job finished) or `changed` (an owner choice or a fix was saved) changes, and
 * a saved choice calls `onChanged` so the summary, the rail counts and the To do groups read again too. `full.rows`
 * and `forced.rows` are the server's rows; `foreign` and `forcedFilms` are those it flagged `unresolved` (null until
 * the list has been read).
 */
export function useAdminSubtitles({ subtitlesVersion, changed, onChanged }) {
  const key = `${subtitlesVersion}|${changed}`;
  const full = useFullNeed(key, { onSaved: onChanged });
  const forced = useForcedNeed(key, { onSaved: onChanged });
  // Memoised: the lists keep their identity between renders, so a screen can react to a change of the rows.
  const foreign = useMemo(() => unresolvedRows(full.rows), [full.rows]);
  const forcedFilms = useMemo(() => unresolvedRows(forced.rows), [forced.rows]);
  return { full, forced, foreign, forcedFilms };
}
