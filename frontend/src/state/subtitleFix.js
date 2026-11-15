// Pure rules of Film detail "Subtitles → Fix…" and the player's "Subtitles are wrong". No JSX. The per-file rules
// themselves (what a file is, where it sits, how it reads) stay in `subtitleAdmin.js`; Admin and Film detail share them.
import { ApiError } from '../api/client.js';
import { FORCED_KEY, FULL_KEY, activeSubtitleKey } from '../player/tracks.js';
import { subtitlesWrong } from './subtitleFixCopy.js';
import { filterSidecars, isDownload, isFull, subtitleProblem } from './subtitleAdmin.js';

/** Thrown when the playing download is no longer the one the film showed (another tab chose a different one). */
export class Changed extends Error {
  constructor() {
    super('The playing subtitle changed');
    this.name = 'Changed';
  }
}

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

/** Of the playing downloads, only those the film showed (`ids`: the edition's `sidecarIds`); never one it did not. */
export function shownTargets(targets, ids) {
  return targets.filter((row) => (ids ?? []).includes(row.id));
}

/** What the player says when "Subtitles are wrong" fails: the server's own words for a refusal, else one plain line. */
export function wrongProblem(failure) {
  if (failure instanceof ApiError && failure.status === 400 && failure.body?.error) {
    return subtitleProblem(failure);
  }
  if (failure instanceof LookupFailed) return subtitlesWrong.lookupFailed;
  if (failure instanceof Changed) return subtitlesWrong.changed;
  return subtitlesWrong.failed;
}
