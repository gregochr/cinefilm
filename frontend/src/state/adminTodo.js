// What the To do page lists, and how: which attention kinds go in which group, the cap of rows, the footers and the
// small lines under each row. Pure, no JSX: the Apple TV and native clients reuse it. The server decides every rule
// (kind, `confident`, `unresolved`); nothing here re-derives one.
import { todoRow } from './adminCopy.js';
import { audioNote, fullSubtitleLine } from './fullNeed.js';
import { reasonText } from './forcedNeed.js';
import { titleWithYear } from './scanFormat.js';

/** The most rows a group shows until "Show all {n}". */
export const TODO_ROW_CAP = 5;

/** The groups in the order they are drawn. */
export const TODO_GROUP_KEYS = ['pickFilm', 'severalFiles', 'fullMissing', 'forcedQuestion'];

// Attention kinds that are To do items; every other kind (protected, needs-conversion, unreadable, …) is not.
const KIND_GROUP = {
  'needs-match': 'pickFilm',
  'multiple-files': 'severalFiles',
  'parts-mismatch': 'severalFiles',
};

/** The group an attention kind belongs to, or null when it is not a To do item. */
export function groupOfKind(kind) {
  return KIND_GROUP[kind] ?? null;
}

/** The candidate with the highest score (the first of equals), or null when there is none. */
export function bestGuess(item) {
  let best = null;
  for (const candidate of item.candidates ?? []) {
    if (best === null || candidate.score > best.score) best = candidate;
  }
  return best;
}

/**
 * The non-empty groups, in order: `{ key, rows, total }`. `attention` is the attention list's items; `full` and
 * `forced` are the rows of the two need lists (a source that has not loaded is null and adds nothing). Subtitle rows
 * are those the server flagged `unresolved`, in the server's order.
 */
export function buildTodoGroups({ attention, full, forced }) {
  const rows = { pickFilm: [], severalFiles: [], fullMissing: [], forcedQuestion: [] };
  for (const item of attention ?? []) {
    const key = groupOfKind(item.kind);
    if (key) rows[key].push(item);
  }
  rows.fullMissing = (full ?? []).filter((row) => row.unresolved);
  rows.forcedQuestion = (forced ?? []).filter((row) => row.unresolved);
  return TODO_GROUP_KEYS.filter((key) => rows[key].length > 0).map((key) => ({
    key,
    rows: rows[key],
    total: rows[key].length,
  }));
}

// The groups whose full list "Show all" opens; the other two link to Subtitles instead.
const EXPANDS = new Set(['pickFilm', 'severalFiles']);

/** The rows of a group on screen: all of them when `expanded`, else the first {@link TODO_ROW_CAP}. */
export function visibleRows(group, expanded) {
  return expanded ? group.rows : group.rows.slice(0, TODO_ROW_CAP);
}

/**
 * The footer under a group: `{ shown, total, control }` where `control` is "show-all" (a group of 1 or 2 with more
 * than the cap), "show-fewer" (the same, expanded), "subtitles" (a group of 3 or 4, always), or null for no footer.
 */
export function footerFor(group, expanded) {
  const shown = visibleRows(group, expanded).length;
  const { total } = group;
  if (!EXPANDS.has(group.key)) return { shown, total, control: 'subtitles' };
  if (total <= TODO_ROW_CAP) return null;
  return { shown, total, control: expanded ? 'show-fewer' : 'show-all' };
}

/** The best guess of a needs-match row as words: "Heat (1995)", or null when there is no candidate. */
export function guessTitle(item) {
  const guess = bestGuess(item);
  return guess ? titleWithYear(guess.title, guess.year) : null;
}

/** The small line under a needs-match row: "{file} · best guess: Heat (1995)", or "… · best guess: none". */
export function matchLine(item) {
  const guess = guessTitle(item);
  return `${item.files.join(' · ')} · ${guess ? todoRow.bestGuess(guess) : todoRow.noGuess}`;
}

/** The small line under a several-files row: "3 files · {the scanner's suggestion}". */
export function filesLine(item) {
  return `${todoRow.files(item.files.length)} · ${item.detail}`;
}

/** The small line under a full-subtitles row: "Audio: German · None yet". */
export function fullLine(row) {
  return [audioNote(row), fullSubtitleLine(row)].filter(Boolean).join(' · ');
}

/** The small line under a forced-subtitles row: the server's reason in words. */
export function forcedLine(row) {
  return reasonText(row);
}
