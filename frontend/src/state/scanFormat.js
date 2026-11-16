// Display helpers for the Admin scan and attention panels. No JSX: the Apple TV and native clients reuse them.
import { libraryPage } from './adminCopy.js';
import { FILE_LISTS } from './adminRoutes.js';
import { timeAgo } from './relativeTime.js';

const WHEN = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Europe/London',
  day: '2-digit',
  month: 'short',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
});

/** "08 Nov 12:41" in the application time zone (Europe/London, like the server). */
export function formatScanTime(iso) {
  const parts = Object.fromEntries(WHEN.formatToParts(new Date(iso)).map((p) => [p.type, p.value]));
  return `${parts.day} ${parts.month} ${parts.hour}:${parts.minute}`;
}

const DAY = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Europe/London',
  day: 'numeric',
  month: 'short',
});

/** "9 Nov": the day in the application time zone, for "updated 9 Nov". */
export function formatScanDay(iso) {
  const parts = Object.fromEntries(DAY.formatToParts(new Date(iso)).map((p) => [p.type, p.value]));
  return `${parts.day} ${parts.month}`;
}

const CLOCK = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Europe/London',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
});

/** "17:56": the time of day in the application time zone (Europe/London, like the server). */
export function formatScanClock(iso) {
  return CLOCK.format(new Date(iso));
}

/**
 * How long a scan took, in whole seconds: "48s", "1m 48s" (the seconds stay when 0: "2m 0s"), "1h 5m" from an hour.
 * A finish before the start (clock skew) reads "0s".
 */
export function formatDuration(startedAt, finishedAt) {
  const seconds = Math.max(0, Math.round((new Date(finishedAt) - new Date(startedAt)) / 1000));
  if (seconds < 60) return `${seconds}s`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
  return `${Math.floor(seconds / 3600)}h ${Math.floor((seconds % 3600) / 60)}m`;
}

/** "Last scan 12 min ago · took 1m 48s" (`now` is epoch ms), "Not scanned yet" before the first scan. */
export function describeScanHeader(lastScan, now) {
  if (!lastScan) return libraryPage.scan.neverScanned;
  return libraryPage.scan.lastScan(
    timeAgo(lastScan.finishedAt, now),
    formatDuration(lastScan.startedAt, lastScan.finishedAt),
  );
}

/**
 * The count tiles, in panel order. "Unreadable" only appears when something is. The protected, needs-conversion and
 * unreadable tiles carry `to`, the address of the plain list of those files (`FILE_LISTS`); the others have none.
 */
export function countTiles(counts) {
  const tiles = [
    { key: 'films', label: 'Films', value: counts.films },
    { key: 'newFiles', label: 'New', value: counts.newFiles },
    { key: 'missing', label: 'Missing', value: counts.missing },
    { key: 'protectedFiles', label: 'Can’t play (protected)', value: counts.protectedFiles },
    { key: 'needsConversion', label: 'Needs conversion', value: counts.needsConversion },
    { key: 'needsMatch', label: 'Need a match', value: counts.needsMatch },
  ];
  if (counts.unreadable > 0) {
    tiles.push({ key: 'unreadable', label: 'Unreadable', value: counts.unreadable });
  }
  return tiles.map((tile) => {
    const list = FILE_LISTS.find((l) => l.tile === tile.key);
    return list ? { ...tile, to: list.path } : tile;
  });
}

/** "12 / 40 files" while files are being handled, a neutral line while the folder is still being walked. */
export function progressText(progress) {
  if (!progress.total) return 'Looking through the folder…';
  return `${progress.processed} / ${progress.total} files`;
}

/** Progress as a 0..100 width for the bar. */
export function progressPercent(progress) {
  if (!progress.total) return 0;
  return Math.min(100, Math.round((progress.processed / progress.total) * 100));
}

const KIND_LABELS = {
  'needs-match': 'Unmatched',
  protected: 'Protected',
  'needs-conversion': 'Needs conversion',
  unreadable: 'Unreadable',
  'multiple-files': 'Several files',
  'parts-mismatch': 'Parts don\u2019t match \u2014 will transcode',
};

/** The small tag on an attention entry; an unknown kind shows as is. */
export function kindLabel(kind) {
  return KIND_LABELS[kind] ?? kind;
}

/** "Dune (1984)", or just the title when the year is unknown. */
export function titleWithYear(title, year) {
  return year ? `${title} (${year})` : title;
}

const CHANGE_LABELS = { split: 'Split', join: 'Joined', flag: 'Check' };

/** The small label on a grouping report line; an unknown kind shows as is. */
export function changeLabel(kind) {
  return CHANGE_LABELS[kind] ?? kind;
}

/** Grouping changes shown at first, and added by each "Show … more". */
export const GROUPING_PAGE_SIZE = 25;

/** How many changes of each kind a report holds: `{ split, join, flag }`; a kind it does not know is not counted. */
export function groupingCounts(items) {
  const counts = { split: 0, join: 0, flag: 0 };
  items.forEach((item) => {
    if (item.kind in counts) counts[item.kind] += 1;
  });
  return counts;
}

/** "2 split, 1 joined, 1 flagged": the figures of the summary. */
export function describeGroupingCounts(items) {
  const { split, join, flag } = groupingCounts(items);
  return libraryPage.changes.counts(split, join, flag);
}

/**
 * The first `shown` changes and the label of the button for the next page: `{ visible, moreLabel }`, with `moreLabel`
 * null once everything is shown. The label says how many the next press adds (at most `GROUPING_PAGE_SIZE`).
 */
export function groupingPage(items, shown) {
  const left = items.length - shown;
  return {
    visible: items.slice(0, shown),
    moreLabel: left > 0 ? libraryPage.changes.showMore(Math.min(left, GROUPING_PAGE_SIZE)) : null,
  };
}

/** "530 of 538 films have credits" from the scan's counts. */
export function describeCreditsCount(counts) {
  return libraryPage.cast.count(counts.filmsWithCredits, counts.films);
}

/**
 * The Subtitles section's OpenSubtitles line (it left the Library scan card in 4a): "Subtitles searched 12 Nov 06:00",
 * "Searching for subtitles…" while a search runs, "Subtitles not searched yet" before the first; null when the search
 * is off. `format` formats the time.
 */
export function describeSubtitleSearch({ enabled, running, lastSearchedAt }, format) {
  if (!enabled) return null;
  if (running) return 'Searching for subtitles…';
  return lastSearchedAt
    ? `Subtitles searched ${format(lastSearchedAt)}`
    : 'Subtitles not searched yet';
}
