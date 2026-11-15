// Pure rules of Film detail "Subtitles → Fix…" and the player's "Subtitles are wrong". No JSX. The per-file rules
// themselves (what a file is, where it sits, how it reads) stay in `subtitleAdmin.js`; Admin and Film detail share them.
import { ApiError } from '../api/client.js';
import { FORCED_KEY, FULL_KEY, activeSubtitleKey } from '../player/tracks.js';
import { subtitlesWrong } from './subtitleFixCopy.js';
import { filterSidecars, isDownload, subtitleProblem } from './subtitleAdmin.js';

/** Thrown when no download row matches what is showing (it was already removed, or the list moved on). */
export class LookupFailed extends Error {
  constructor() {
    super('No download to remove');
    this.name = 'LookupFailed';
  }
}

/** The files of one film, as the server lists them (path order): the rows that sit under it, none of the unattached. */
export function filmFiles(items, filmId) {
  return filterSidecars(items, 'all', filmId);
}

/**
 * Which per-file tools apply to a file of the film. "Use my file instead" only for an owner's file a download has
 * replaced and the owner has not yet chosen; "Let CineFilm decide" only for a file the owner attached by hand;
 * "Delete this download" only for an OpenSubtitles download (never the owner's own file); "Preview lines" for any.
 * "Looks right" is not here: it goes to the player's panel only (8b).
 */
export function fileTools(item) {
  return {
    useMine: item.replacedByDownload === true && !item.verified,
    letDecide: item.ownerAttached === true,
    preview: true,
    deleteDownload: isDownload(item),
  };
}

/**
 * The kind (`'FULL'` or `'FORCED'`) of the OpenSubtitles download that is on screen right now, or null when what shows
 * is the owner's file, an embedded stream or nothing. The player's own rule decides what shows (`activeSubtitleKey`);
 * the edition says where the forced and full subtitle came from (`source` is `DOWNLOAD` for a download).
 */
export function shownDownloadKind({ choice, streams, forcedSubtitle, fullSubtitle }) {
  const key = activeSubtitleKey(streams, choice, forcedSubtitle, fullSubtitle);
  if (key === FULL_KEY && fullSubtitle?.source === 'DOWNLOAD') return 'FULL';
  if (key === FORCED_KEY && forcedSubtitle?.source === 'DOWNLOAD') return 'FORCED';
  return null;
}

/** The rows of the download that plays now for one edition and kind: what "Subtitles are wrong" deletes. */
export function wrongTargets(rows, { filmId, editionId, kind }) {
  return rows.filter(
    (row) =>
      row.filmId === filmId &&
      row.editionId === editionId &&
      row.kind === kind &&
      row.served === true &&
      isDownload(row),
  );
}

/** What the player says when "Subtitles are wrong" fails: the server's own words for a refusal, else one plain line. */
export function wrongProblem(failure) {
  if (failure instanceof ApiError && failure.status === 400 && failure.body?.error) {
    return subtitleProblem(failure);
  }
  if (failure instanceof LookupFailed) return subtitlesWrong.lookupFailed;
  return subtitlesWrong.failed;
}
