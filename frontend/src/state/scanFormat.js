// Display helpers for the Admin scan and attention panels. No JSX: the Apple TV and native clients reuse them.

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

/** "08 Nov 12:41 · 2 new, 0 missing", "Never" before the first scan. */
export function describeLastScan(lastScan) {
  if (!lastScan) return 'Never';
  return `${formatScanTime(lastScan.finishedAt)} · ${lastScan.newFiles} new, ${lastScan.missingFiles} missing`;
}

/** The count tiles, in panel order. "Unreadable" only appears when something is. */
export function countTiles(counts) {
  const tiles = [
    { key: 'films', label: 'Films', value: counts.films },
    { key: 'newFiles', label: 'New', value: counts.newFiles },
    { key: 'missing', label: 'Missing', value: counts.missing },
    { key: 'protectedFiles', label: 'Protected', value: counts.protectedFiles },
    { key: 'needsConversion', label: 'Needs conversion', value: counts.needsConversion },
    { key: 'needsMatch', label: 'Need a match', value: counts.needsMatch },
  ];
  if (counts.unreadable > 0) {
    tiles.push({ key: 'unreadable', label: 'Unreadable', value: counts.unreadable });
  }
  return tiles;
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
