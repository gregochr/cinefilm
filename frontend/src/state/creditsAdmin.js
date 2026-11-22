// The End credits list's rules, pure (no JSX): the words of a row, the time a panel shows, and paging.
import { END_OVERLAY_FRACTION } from '../player/resume.js';
import { formatResume } from '../player/time.js';
import { creditsRow } from './creditsAdminCopy.js';

/** How far before the panel's time the second still is taken. */
export const EARLIER_MS = 30000;

/** The filters, in tab order; each is also the `filter` the server takes and the `?show=` value. */
export const FILTERS = ['none', 'plex', 'owner', 'all'];

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
 * The time a row's panel starts from: the marker, else Plex's refused time, else the default end screen point. Null
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
