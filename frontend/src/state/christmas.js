// What the Christmas films screen says and counts, from the API body. No JSX.

/** How long the undo toast stays. */
export const TOAST_MS = 8000;
/** How often the screen asks again while keywords are still being fetched. */
export const POLL_MS = 30000;

export const SCAN_RUNNING_TEXT = 'A scan is running — try again when it finishes';
export const FAILED_CHANGE_TEXT = "Couldn't save that change. Nothing was changed.";

export const TABS = [
  { key: 'inWeek', label: 'In Christmas week', short: 'In week' },
  { key: 'added', label: 'Added by me', short: 'Added' },
  { key: 'leftOut', label: 'Left out by me', short: 'Left out' },
];

const DAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** A count with thousands separators: 1374 reads "1,374". */
export const formatCount = (n) => n.toLocaleString('en-GB');

/** "1 film" or "n films". */
export const filmsText = (n) => `${formatCount(n)} ${n === 1 ? 'film' : 'films'}`;

/** "Mon 14 Dec" from an ISO date, read as a calendar date (no time zone shift). */
export function shortDate(iso) {
  const [year, month, day] = iso.split('-').map(Number);
  const date = new Date(Date.UTC(year, month - 1, day));
  return `${DAYS[date.getUTCDay()]} ${day} ${MONTHS[month - 1]}`;
}

/** "Mon 14 Dec to Sun 20 Dec". */
export const weekText = (week) => `${shortDate(week.start)} to ${shortDate(week.end)}`;

/** The three tab counts. */
export const tabCounts = (data) => ({
  inWeek: data.inWeek.length,
  added: data.added.length,
  leftOut: data.leftOut.length,
});

/** The rows a tab lists. */
export const tabRows = (data, tab) => data[tab];

/** How many rows are above the cut line: Home shows the first `max` of the In list. */
export const cutIndex = (rows, max) => Math.min(rows.length, max);

/** What a row is: a film TMDB tags, one the owner added, or one the owner left out (the only rows with no reason). */
export const rowKind = (row) => {
  if (row.reason === 'KEYWORD') return 'keyword';
  if (row.reason === 'ADDED') return 'added';
  return 'leftOut';
};

const ACTIONS = {
  keyword: { label: 'Leave out', choice: 'EXCLUDE', previous: null, toast: 'leave' },
  added: { label: 'Remove', choice: null, previous: 'INCLUDE', toast: 'remove' },
  leftOut: { label: 'Put back', choice: null, previous: 'EXCLUDE', toast: 'putback' },
};

/**
 * The one action a row offers: its label, the choice to send, the choice Undo sends back, and the toast kind.
 * `added` is the screen's Added list. Two library films can share a TMDB id (a keyword-tagged film and an untagged one
 * the owner included); the server keeps the one INCLUDE row for the untagged one, so leaving the tagged one out
 * replaces that INCLUDE and an Undo must restore it, not delete the choice. The previous choice therefore comes from
 * the data for that TMDB id, not from the clicked row's reason alone.
 */
export function rowAction(row, added = []) {
  const action = ACTIONS[rowKind(row)];
  if (action.toast !== 'leave') return action;
  const included = added.some((film) => film.tmdbId === row.tmdbId);
  return included ? { ...action, previous: 'INCLUDE' } : action;
}

/** The toast kind that says the opposite (after an Undo). */
export const INVERSE_TOAST = { leave: 'putback', putback: 'leave', add: 'remove', remove: 'add' };

/** The toast wording for a kind of change. */
export function toastText(kind, title) {
  switch (kind) {
    case 'leave':
      return `Left out ${title}. It won't show at Christmas.`;
    case 'putback':
      return `Put back ${title}. It'll show at Christmas.`;
    case 'add':
      return `Added ${title}.`;
    default:
      return `Removed ${title}.`;
  }
}

/** The words for a refused or failed write: the server's own, else the scan-lock text for a 409, else a plain one. */
export function failureText(status, serverWords) {
  if (serverWords) return serverWords;
  return status === 409 ? SCAN_RUNNING_TEXT : FAILED_CHANGE_TEXT;
}

/** The reason chip of a row: its label and variant. */
export function chipFor(row) {
  const kind = rowKind(row);
  if (kind === 'keyword') return { label: 'TMDB keyword', variant: 'keyword' };
  if (kind === 'added') return { label: 'Added by me', variant: 'added' };
  return { label: 'Left out by me', variant: 'left-out' };
}

/** The extra line under a row's title in the Added and Left out tabs, or null. */
export function rowNote(row, tab) {
  if (tab === 'added') return "TMDB doesn't tag it, so it's only in because you added it.";
  if (tab === 'leftOut') {
    return row.taggedByTmdb
      ? 'TMDB tags it “christmas”. Put back and it shows again.'
      : "TMDB doesn't tag it, so Put back takes it off this screen.";
  }
  return null;
}

/** "Title · year" (the year is left off when unknown). */
export const titleLine = (row) => (row.year ? `${row.title} · ${row.year}` : row.title);

/** The cut line's words. */
export const cutText = (max, count) =>
  `Home shows ${max}. These ${count} are in, but won't fit. Leave out an older film to make room.`;

/** The status card's sentences: the bold count, the rest of the headline, and the line under it. */
export function statusText(data) {
  const n = data.inWeek.length;
  const { max, min } = data.limits;
  if (data.fallsBack) {
    return {
      bold: filmsText(n),
      rest: ` ${n === 1 ? 'is' : 'are'} in, which is under ${min}, so Christmas week shows the usual weekly theme instead.`,
      hint: `Home needs at least ${min} Christmas films to show them.`,
    };
  }
  return {
    bold: filmsText(n),
    rest: ` will show in Christmas week (${weekText(data.week)}).`,
    hint: `Home shows up to ${max}, oldest first. Under ${min} and it falls back to the usual weekly theme.`,
  };
}

/** The meter: `max` slots, filled per film that gets a place, then one hollow segment per film over `max`. */
export function meterSegments(total, max) {
  const filled = Math.min(total, max);
  const over = Math.max(0, total - max);
  return [
    ...Array.from({ length: max }, (_, i) => (i < filled ? 'filled' : 'empty')),
    ...Array.from({ length: over }, () => 'over'),
  ];
}

/** The meter's captions: the places on Home, and how many films are over (null when none). */
export const meterCaption = (total, max) => ({
  places: `${max} places on Home`,
  over: total > max ? `${total - max} over` : null,
});

/** True while keywords are still being fetched, so the strip and the empty copy show. */
export const keywordsPending = (keywords) => keywords.fetched < keywords.total;

/** How full the keyword progress is, as a whole percent (0 when there are no films). */
export const keywordPercent = ({ fetched, total }) =>
  total > 0 ? Math.min(100, Math.round((fetched / total) * 100)) : 0;

/** The keyword strip's sentence. */
export const keywordLine = ({ fetched, total }) =>
  `Keywords fetched for ${formatCount(fetched)} of ${formatCount(total)} films. The other ${formatCount(total - fetched)} are on their way, so this list may still grow.`;

/** Which empty copy applies: null when there is something to list. */
export function emptyKind(data) {
  if (data.inWeek.length > 0 || data.added.length > 0 || data.leftOut.length > 0) return null;
  return keywordsPending(data.keywords) ? 'pending' : 'none';
}

/** The empty panel's body for the keywords-still-arriving case. */
export const emptyPendingText = ({ fetched, total }) =>
  `CineFilm finds them from TMDB keywords, and it's still fetching those: ${formatCount(fetched)} of ${formatCount(total)} films so far. Films appear here as their keywords arrive, usually within the hour.`;

export const EMPTY_NONE_TEXT = 'No films in your library are tagged Christmas.';

/** The line a tab with nothing in it shows (when the other lists are not empty either). */
export const EMPTY_TAB_TEXT = {
  inWeek: 'No films are in Christmas week yet.',
  added: "You haven't added any films.",
  leftOut: "You haven't left any films out.",
};
