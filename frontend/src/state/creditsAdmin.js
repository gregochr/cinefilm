// The End credits list's rules, pure (no JSX): the words of a row, the time a panel shows, and paging.
import { END_OVERLAY_FRACTION } from '../player/resume.js';
import { formatResume } from '../player/time.js';
import { serverText } from '../api/client.js';
import { creditsPanel, creditsRow } from './creditsAdminCopy.js';
import { lockText } from './lockText.js';

/** How far before the panel's time the second still is taken. */
export const EARLIER_MS = 30000;

/** The filters, in tab order; each is also the `filter` the server takes and the `?show=` value. */
export const FILTERS = ['none', 'plex', 'auto', 'owner', 'all'];

/** The filter a `?show=` value names; anything else is the default, "Needs a look". */
export function filterOf(value) {
  return FILTERS.includes(value) ? value : 'none';
}

function sourceLabel(row) {
  if (row.source === 'OWNER') return creditsRow.yours;
  if (row.source === 'PLEX') return creditsRow.fromPlex;
  if (row.source === 'AUTO') return creditsRow.detected;
  return creditsRow.notSettled;
}

/** Why a row has no marker, from what Plex says. */
function reasonText(row) {
  const plex = row.plex;
  switch (plex.state) {
    case 'NONE':
      return creditsRow.plexNone;
    case 'NO_FINGERPRINT':
      return creditsRow.plexNoFingerprint;
    case 'STALE':
      return creditsRow.plexStale;
    case 'USABLE':
      return creditsRow.plexUsable(formatResume(plex.markerMs));
    case 'OUTSIDE':
      return creditsRow.plexOutside;
    case 'REFUSED':
      return creditsRow.plexRefused(formatResume(plex.markerMs));
    default:
      return creditsRow.noTimeline;
  }
}

/**
 * Lines two and three of a row. Line two is the source and time (or "No marker"); line three, only when there is no
 * marker, says why. Line one (title, year, edition) is the component's.
 */
export function rowLines(row) {
  if (row.markerMs != null) {
    return { two: `${sourceLabel(row)} · ${formatResume(row.markerMs)}`, three: null };
  }
  return {
    two: creditsRow.noMarker(Math.round(END_OVERLAY_FRACTION * 100)),
    three: reasonText(row),
  };
}

/**
 * The time a row's panel starts from: the marker (Plex's, the detected or the owner's), else Plex's refused time, else the default end screen point. Null
 * when the film's length is unknown and there is neither.
 */
export function panelTimeMs(row) {
  if (row.markerMs != null) return row.markerMs;
  if (row.plex.state === 'REFUSED' && row.plex.markerMs != null) return row.plex.markerMs;
  return row.runtimeMs ? Math.floor(row.runtimeMs * END_OVERLAY_FRACTION) : null;
}

/** The two stills' times, earlier one first. */
export function stillTimes(atMs) {
  return [Math.max(0, atMs - EARLIER_MS), atMs];
}

/** "Showing 26–50 of 212"; empty when there is nothing to show. */
export function pagingText(page, size, total) {
  if (total <= 0) return '';
  const from = page * size + 1;
  const to = Math.min(total, (page + 1) * size);
  return `Showing ${from}–${to} of ${total}`;
}

/** Whether a page comes after this one. */
export function hasNextPage(page, size, total) {
  return (page + 1) * size < total;
}

/** A page that came back empty while the filter still has films (the last row went) steps back one. */
export function pageAfterRead(page, itemCount, total) {
  return itemCount === 0 && total > 0 && page > 0 ? page - 1 : page;
}

/**
 * Reads `h:mm:ss` or `m:ss` (whole seconds) as milliseconds, or null for anything else. With hours the minutes are
 * two digits and under 60; seconds are two digits and under 60. Minutes alone may pass 59 ("75:00").
 */
export function parseTime(text) {
  const match = /^(?:(\d{1,2}):(\d{2})|(\d{1,3})):(\d{2})$/.exec(String(text).trim());
  if (!match) return null;
  const [, hours, minutesWithHours, minutesAlone, seconds] = match;
  const minutes = Number(minutesWithHours ?? minutesAlone);
  if ((hours !== undefined && minutes > 59) || Number(seconds) > 59) return null;
  return ((Number(hours ?? 0) * 60 + minutes) * 60 + Number(seconds)) * 1000;
}

/** The same check as the server's `EditionGuard.plausible`: at least 60% in and at least 60 s before the end. */
export function isPlausible(markerMs, totalMs) {
  return markerMs * 100 >= totalMs * 60 && totalMs - markerMs >= 60000;
}

/** The earliest and latest whole-second time `isPlausible` accepts for a film of `totalMs`; null when none is. */
export function plausibleRange(totalMs) {
  const fromMs = Math.ceil(Math.ceil((totalMs * 60) / 100) / 1000) * 1000;
  const toMs = Math.floor((totalMs - 60000) / 1000) * 1000;
  return fromMs <= toMs ? { fromMs, toMs } : null;
}

/** What a typed time means for a film of `totalMs`: `{ ms }` when it can be sent, else `{ error }` (the words). */
export function checkTime(text, totalMs) {
  const ms = parseTime(text);
  if (ms == null) return { error: creditsPanel.timeFormat };
  if (!isPlausible(ms, totalMs)) {
    const range = plausibleRange(totalMs);
    return {
      error: range
        ? creditsPanel.outside(formatResume(range.fromMs), formatResume(range.toMs))
        : creditsPanel.noRange,
    };
  }
  return { ms };
}

/**
 * How a refused or failed write shows. `field`: the typed time was refused (400), `alert`: another reason to show
 * in the panel (the lock, a failure), `gone`: the edition is no longer there (404), `signin`: a 401, which the
 * sign-in card handles. `text` is what the panel says. `verb` is "set" or "clear".
 */
export function writeFailure(error, verb) {
  if (error?.status === 401) return { kind: 'signin', text: null };
  if (error?.status === 404) return { kind: 'gone', text: creditsPanel.gone };
  if (error?.status === 400)
    return { kind: 'field', text: serverText(error) ?? creditsPanel.refused };
  if (error?.status === 409)
    return { kind: 'alert', text: lockText(error, creditsPanel.scanRunning) };
  return {
    kind: 'alert',
    text: verb === 'set' ? creditsPanel.setFailed : creditsPanel.clearFailed,
  };
}

/**
 * Where focus goes once the list has been read again after a write on `row` (of `items`, the page before): the
 * row's own id if it is still there, else the next row's, else the previous row's. Null when none is on the page.
 * After a step back (`steppedBack`) the page is another one, and its last row is the target.
 */
export function focusTargetAfter(rowId, neighbours, newItems, steppedBack) {
  if (newItems.length === 0) return null;
  if (steppedBack) return newItems.at(-1).editionId;
  const ids = new Set(newItems.map((item) => item.editionId));
  return (
    [rowId, neighbours.after, neighbours.before].find((id) => id != null && ids.has(id)) ?? null
  );
}

/** The ids of the rows after and before `rowId` in `items` (null at an end or when the row is not in the list). */
export function neighboursOf(items, rowId) {
  const at = items.findIndex((item) => item.editionId === rowId);
  if (at < 0) return { after: null, before: null };
  return { after: items[at + 1]?.editionId ?? null, before: items[at - 1]?.editionId ?? null };
}
