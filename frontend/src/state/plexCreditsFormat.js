// Display helpers for the Admin "Import credits from Plex" block. No JSX: the Apple TV and native clients reuse them.
import { formatScanTime } from './scanFormat.js';

/** "12 / 40 films" while films are being asked about, a neutral line while Plex's list is still being read. */
export function creditsProgressText(progress) {
  if (!progress.total) return 'Reading Plex…';
  return `${progress.processed} / ${progress.total} films`;
}

/** "08 Nov 14:09 · 860 markers stored", the error when the last import stopped, "Not run yet" before the first. */
export function describeLastCreditsImport(last) {
  if (!last) return 'Not run yet';
  const when = formatScanTime(last.finishedAt);
  if (last.error) return `${when} · ${last.error}`;
  return `${when} · ${last.report?.imported ?? 0} markers stored`;
}

/** The count tiles of a report, in block order. */
export function creditsTiles(report) {
  return [
    { key: 'plexFilms', label: 'Plex films', value: report.plexFilms },
    { key: 'matchedFilms', label: 'Matched', value: report.matchedFilms },
    { key: 'imported', label: 'Stored', value: report.imported },
    { key: 'unmatched', label: 'Unmatched files', value: report.unmatched },
  ];
}

/** One line per skip reason, "Films split into parts: 3". Only the reasons that happened are in the report. */
export function creditsSkipLines(report) {
  return report.skipped.map((skip) => ({
    key: skip.reason,
    text: `${skip.description}: ${skip.count}`,
  }));
}
