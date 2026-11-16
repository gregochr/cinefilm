// Pure rules of Film detail "Subtitles → Fix…" and the player's "Subtitles are wrong". No JSX. The per-file rules
// themselves (what a file is, where it sits, how it reads) stay in `subtitleAdmin.js`; Admin and Film detail share them.
import { ApiError } from '../api/client.js';
import { FORCED_KEY, FULL_KEY, activeSubtitleKey } from '../player/tracks.js';
import { subtitleFix, subtitlesWrong } from './subtitleFixCopy.js';
import { filterSidecars, isDownload, isFull, subtitleProblem } from './subtitleAdmin.js';

/** Thrown when the film did not say which download it showed: nothing can be proven to be the one on screen. */
export class Changed extends Error {
  constructor() {
    super('The playing subtitle changed');
    this.name = 'Changed';
  }
}

/** The files of one film, as the server lists them (path order): the rows that sit under it, none of the unattached. */
export function filmFiles(items, filmId) {
  return filterSidecars(items, 'all', filmId);
}

/** The word for a file's kind: "Full" or "Forced". */
export function kindTag(item) {
  return isFull(item) ? subtitleFix.full : subtitleFix.forced;
}

/**
 * Which per-file tools apply to a file of the film. "Use my file instead" only for an owner's file a download has
 * replaced and the owner has not yet chosen; "Let CineFilm decide" only for a file the owner attached by hand;
 * "Delete this download" only for an OpenSubtitles download (never the owner's own file); "Preview lines" for any.
 * "Looks right" is the rule Admin's file list had (moved here in 8b-2): a forced file (never a full one) that sits
 * under a film and is not yet checked, except an owner's file a download replaced, which has "Use my file instead"
 * (the same `verify` route) in its place; once checked, `undoCheck` takes the check away again.
 */
export function fileTools(item) {
  const checkable = !isFull(item) && item.filmId != null;
  const replaced = item.replacedByDownload === true;
  return {
    useMine: replaced && !item.verified,
    letDecide: item.ownerAttached === true,
    preview: true,
    deleteDownload: isDownload(item),
    looksRight: checkable && !item.verified && !replaced,
    undoCheck: checkable && item.verified === true,
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

/**
 * The kind (`'FULL'` or `'FORCED'`) of the subtitle *file* on screen right now (the owner's own or a download), or null
 * when what shows is an embedded stream or nothing. `activeSubtitleKey` decides what shows; the edition's forced and
 * full subtitle are always files, so only the key's kind matters. Timing is offered for exactly these.
 */
export function shownFileKind({ choice, streams, forcedSubtitle, fullSubtitle }) {
  const key = activeSubtitleKey(streams, choice, forcedSubtitle, fullSubtitle);
  if (key === FULL_KEY && fullSubtitle) return 'FULL';
  if (key === FORCED_KEY && forcedSubtitle) return 'FORCED';
  return null;
}

/** What the player says when "Subtitles are wrong" fails: the server's own words for a refusal, else one plain line. */
export function wrongProblem(failure) {
  if (failure instanceof ApiError && failure.status === 400 && failure.body?.error) {
    return subtitleProblem(failure);
  }
  if (failure instanceof Changed) return subtitlesWrong.changed;
  return subtitlesWrong.failed;
}
