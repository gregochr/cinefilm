// What the Admin Subtitles section lists and how it words it: the two tabs, the rows the server flagged, the
// "Everything else" figures and the choice of a file for one film. Pure, no JSX: the Apple TV and native clients reuse
// it. The server decides every rule (`unresolved`, `beingChecked`, `need`); nothing here re-derives one.
import { subtitlesPage, todoRow } from './adminCopy.js';
import { fullNeedReason, fullSubtitleLine } from './fullNeed.js';
import { languageName, reasonText } from './forcedNeed.js';
import { isFull, partOptions } from './subtitleAdmin.js';
import { titleWithYear } from './scanFormat.js';

/** The tabs in the order they are drawn. */
export const SUBTITLE_TABS = ['foreign', 'forced'];

/** The tab a key moves to: arrows step (wrapping), Home and End jump; null for any other key. */
export function tabAfterKey(current, key) {
  const at = SUBTITLE_TABS.indexOf(current);
  const last = SUBTITLE_TABS.length - 1;
  if (key === 'ArrowRight') return SUBTITLE_TABS[at === last ? 0 : at + 1];
  if (key === 'ArrowLeft') return SUBTITLE_TABS[at === 0 ? last : at - 1];
  if (key === 'Home') return SUBTITLE_TABS[0];
  if (key === 'End') return SUBTITLE_TABS[last];
  return null;
}

/** The rows the server flagged `unresolved`, in its order; null while the list has not been read. */
export function unresolvedRows(rows) {
  return rows ? rows.filter((row) => row.unresolved) : null;
}

/** Whether the audio is unlabelled and the film's original language was assumed (so the owner can say otherwise). */
export function isUnlabelled(row) {
  return row.reason === 'ORIGINAL_AUDIO_ASSUMED';
}

/**
 * The reason line under a foreign-language row (AR2): "Spoken: French · none found automatically on OpenSubtitles"
 * (or what plays instead), or the unlabelled-audio line. A film the owner called needed says so.
 */
export function foreignLine(row) {
  const language = row.originalLanguage ? languageName(row.originalLanguage) : null;
  if (isUnlabelled(row)) return subtitlesPage.unlabelled(language);
  const spoken = language ? todoRow.spoken(language) : todoRow.languageUnknown;
  const found = row.fullSubtitle ? fullSubtitleLine(row) : todoRow.noneFound;
  const parts = [spoken, row.reason === 'OWNER' ? fullNeedReason(row) : null, found];
  return parts.filter(Boolean).join(' · ');
}

/** The reason line under a forced-subtitles row: the server's reason in words. */
export function forcedRowLine(row) {
  return reasonText(row);
}

/** The actions a foreign-language row offers, in order. The picker's ones (4c) are not here yet. */
export function foreignActions(row) {
  return isUnlabelled(row) ? ['addFile', 'itsEnglish'] : ['addFile'];
}

/**
 * The figures of "Everything else": `films` and `haveSubtitles` (the foreign films with English subtitles playing),
 * `inEnglish` (the library's films that are not in the full-need list) and `beingChecked` (the two lists' counts).
 * Each is null until what it needs has been read.
 */
export function everythingElse({ fullRows, fullCounts, forcedCounts, libraryFilms }) {
  const films = fullRows
    ? fullRows.filter((row) => row.need === 'NEEDED' && row.fullSubtitle)
    : null;
  const checked = [fullCounts, forcedCounts].filter(Boolean);
  return {
    films,
    haveSubtitles: films ? films.length : null,
    inEnglish:
      fullRows && libraryFilms != null ? Math.max(0, libraryFilms - fullRows.length) : null,
    beingChecked:
      checked.length > 0 ? checked.reduce((sum, c) => sum + (c.beingChecked ?? 0), 0) : null,
  };
}

/** "Das Boot (1981)". */
export function filmTitle(row) {
  return titleWithYear(row.title, row.year);
}

/**
 * The files a film's "Add my own file…" offers: the owner's own full subtitle files that are present and not already
 * on this film, unattached ones first. Downloads are not offered (they are the server's, not the owner's).
 */
export function fileChoices(files, filmId) {
  const own = (files ?? []).filter(
    (file) =>
      isFull(file) &&
      file.origin !== 'OPENSUBTITLES' &&
      file.presence !== 'MISSING' &&
      file.filmId !== filmId,
  );
  return [...own.filter((f) => f.filmId == null), ...own.filter((f) => f.filmId != null)];
}

/** What a file choice says beside its path: where it sits now. */
export function fileChoiceNote(file) {
  return file.filmId == null
    ? subtitlesPage.addFile.unattached
    : subtitlesPage.addFile.attachedTo(file.filmTitle ?? 'another film');
}

/** The body of the attach request once an edition and (for a split edition) a part are chosen, else null. */
export function attachTarget(edition, part) {
  if (!edition) return null;
  if (edition.parts > 1 && !partOptions(edition).some((option) => option.value === part)) {
    return null;
  }
  return { editionId: edition.id, part: edition.parts > 1 ? part : null };
}
