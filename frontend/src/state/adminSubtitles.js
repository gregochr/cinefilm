// What the Admin Subtitles section lists and how it words it: the two tabs, the rows the server flagged, the
// "Everything else" figures and the choice of a file for one film. Pure, no JSX: the Apple TV and native clients reuse
// it. The server decides every rule (`unresolved`, `beingChecked`, `need`, and the `subtitled` and `inEnglish` counts);
// nothing here re-derives one: this only picks the rows the server flagged and words what it sent.
import { buttons, subtitlesPage, todoRow } from './adminCopy.js';
import { fullNeedReason, fullSubtitleLine } from './fullNeed.js';
import { languageName, reasonText } from './forcedNeed.js';
import { isDownload, isFull, partOptions, partWhere } from './subtitleAdmin.js';
import { titleWithYear } from './scanFormat.js';

/** The tabs in the order they are drawn. */
export const SUBTITLE_TABS = ['foreign', 'forced'];

/** The key of the tab (of `keys`) a key moves to: arrows step (wrapping), Home and End jump; null for any other key or a `current` that is not in `keys`. */
export function tabAfterKey(keys, current, key) {
  const at = keys.indexOf(current);
  if (at === -1) return null;
  const last = keys.length - 1;
  if (key === 'ArrowRight') return keys[at === last ? 0 : at + 1];
  if (key === 'ArrowLeft') return keys[at === 0 ? last : at - 1];
  if (key === 'Home') return keys[0];
  if (key === 'End') return keys[last];
  return null;
}

/** The rows the server flagged `unresolved`, in its order; null while the list has not been read. */
export function unresolvedRows(rows) {
  return rows ? rows.filter((row) => row.unresolved) : null;
}

/**
 * Whether the audio is unlabelled and the film's original language was assumed (so the owner can say otherwise). The
 * server's `ORIGINAL_AUDIO_ASSUMED` also covers a film with no edition and one whose editions disagree, so "unlabelled"
 * is the copy's word for all three.
 */
export function isUnlabelled(row) {
  return row.reason === 'ORIGINAL_AUDIO_ASSUMED';
}

/**
 * The reason line under a foreign-language row (AR2): "Spoken: French · nothing found automatically on OpenSubtitles"
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

/**
 * The action of a row that opens the OpenSubtitles picker, or null when it cannot: the row has no `editionId` (the
 * film has no present edition, so there is nothing to search for). `kind` is `full` or `forced`. A full row whose audio
 * is unlabelled and whose original language is known offers "It's in {language} — find subtitles"; any other full row
 * "Search for English subtitles…"; a forced row "Find forced subtitles…".
 */
export function pickerAction(row, kind) {
  if (row.editionId == null) return null;
  if (kind === 'forced') return 'findForced';
  return isUnlabelled(row) && row.originalLanguage ? 'itsInLanguage' : 'search';
}

/** The label (and hint, when the copy has one) of a picker action for a row. */
export function pickerButton(action, row) {
  if (action === 'itsInLanguage') {
    return { label: buttons.itsInLanguage.label(languageName(row.originalLanguage)) };
  }
  const entry = action === 'findForced' ? buttons.findForced : buttons.searchEnglishSubtitles;
  return { label: entry.label, hint: entry.hint };
}

/**
 * What the layout opens the picker with: the film (for the title), the edition to search, its name when the film has
 * several (the server sends it only then) and the kind.
 */
export function pickerTarget(row, kind) {
  return {
    film: { id: row.filmId, title: row.title, year: row.year },
    editionId: row.editionId,
    editionName: row.editionName ?? null,
    kind,
  };
}

/**
 * The actions a foreign-language row offers, in order: the picker's button first (when the row has an edition),
 * "Add my own file…", and "It's English" for unlabelled audio.
 */
export function foreignActions(row) {
  const find = pickerAction(row, 'full');
  const own = isUnlabelled(row) ? ['addFile', 'itsEnglish'] : ['addFile'];
  return find ? [find, ...own] : own;
}

/** The actions a forced-subtitles row offers, in order: "Find forced subtitles…" (when it has an edition), then "No foreign scenes". */
export function forcedActions(row) {
  const find = pickerAction(row, 'forced');
  return find ? [find, 'noForeignScenes'] : ['noForeignScenes'];
}

/**
 * The figures of "Everything else", as the server sent them: `haveSubtitles` and `inEnglish` are the full-need list's
 * `counts.subtitled` and `counts.inEnglish` (browsable films, over the whole library) and `beingChecked` adds the two
 * lists' `counts.beingChecked`. `films` are the rows the server flagged `subtitled` (what "Show the {n} films" lists, so its
 * count is the number of rows it opens). Each is null until what it needs has been read; `beingChecked` waits for both lists.
 */
export function everythingElse({ fullRows, fullCounts, forcedCounts }) {
  return {
    films: fullRows ? fullRows.filter((row) => row.subtitled) : null,
    haveSubtitles: fullCounts ? fullCounts.subtitled : null,
    inEnglish: fullCounts ? fullCounts.inEnglish : null,
    beingChecked:
      fullCounts && forcedCounts ? fullCounts.beingChecked + forcedCounts.beingChecked : null,
  };
}

/**
 * The films the owner has made a choice for, from the two lists' rows (the server's `ownerChoice`): `{ kind, row }`,
 * full-subtitle choices first, each list in the server's order. A list that has not been read adds none.
 */
export function ownerChoices({ fullRows, forcedRows }) {
  const chosen = (rows) => (rows ?? []).filter((row) => row.ownerChoice != null);
  return [
    ...chosen(fullRows).map((row) => ({ kind: 'full', row })),
    ...chosen(forcedRows).map((row) => ({ kind: 'forced', row })),
  ];
}

/** "Das Boot (1981)". */
export function filmTitle(row) {
  return titleWithYear(row.title, row.year);
}

/**
 * The files a film's "Add my own file…" offers: the owner's own full subtitle files that are present and not already
 * on this film, unattached ones first. Downloads are not offered (they are the server's, not the owner's). With
 * `allKinds` (Film detail's Fix… panel, where Admin's old "Attach to… / Move to…" now lives) forced files are offered
 * too (the attach route takes any file and keeps its kind), and so are this film's own files, so one can be moved to
 * another edition or part. Order: unattached, then other films', then this film's.
 */
export function fileChoices(files, filmId, allKinds = false) {
  const own = (files ?? []).filter(
    (file) =>
      (allKinds || isFull(file)) &&
      !isDownload(file) &&
      file.presence !== 'MISSING' &&
      (allKinds || file.filmId !== filmId),
  );
  return [
    ...own.filter((f) => f.filmId == null),
    ...own.filter((f) => f.filmId != null && f.filmId !== filmId),
    ...own.filter((f) => f.filmId === filmId),
  ];
}

/** What a file choice says beside its path: where it sits now (for this film's own file, its edition and part). */
export function fileChoiceNote(file, filmId) {
  if (file.filmId == null) return subtitlesPage.addFile.unattached;
  if (file.filmId === filmId) {
    return subtitlesPage.addFile.onThisFilm(
      [file.editionName, partWhere(file)].filter(Boolean).join(', '),
    );
  }
  return subtitlesPage.addFile.attachedTo(file.filmTitle ?? 'another film');
}

/**
 * True when attaching `file` to `target` (`attachTarget`'s answer) would put it where it already is: the same film and
 * edition, and the same place in it. In a split edition a `null` part is the whole film; in a one-part edition it is
 * part 1 (the server stores such a file on part 1, not as the whole film, so a row may read either way).
 */
export function isCurrentTarget(file, filmId, target) {
  if (!file || file.filmId !== filmId || file.editionId !== target.editionId) return false;
  if (file.parts > 1) return file.whole ? target.part === null : target.part === file.part;
  return (target.part ?? 1) === 1 && (file.whole === true || file.part === 1);
}

/** The body of the attach request once an edition and (for a split edition) a part are chosen, else null. */
export function attachTarget(edition, part) {
  if (!edition) return null;
  if (edition.parts > 1 && !partOptions(edition).some((option) => option.value === part)) {
    return null;
  }
  return { editionId: edition.id, part: edition.parts > 1 ? part : null };
}
