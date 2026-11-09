// Display helpers for the Admin Plex import panel. No JSX: the Apple TV and native clients reuse them.
import { formatScanTime } from './scanFormat.js';

/** The pill beside the panel title. */
export function plexPillLabel(status) {
  if (status.running) return 'Importing';
  return status.reachable ? 'Reachable' : 'Not reachable';
}

/** "08 Nov 14:09 · 1037 films paired", the error when the last import stopped, "Never" before the first. */
export function describeLastImport(last) {
  if (!last) return 'Never';
  const when = formatScanTime(last.finishedAt);
  if (last.error) return `${when} · ${last.error}`;
  return `${when} · ${last.report?.paired ?? 0} films paired`;
}

/** "12 / 40 films" while films are being handled, a neutral line while Plex is still being read. */
export function plexProgressText(progress) {
  if (!progress.total) return 'Reading Plex…';
  return `${progress.processed} / ${progress.total} films`;
}

/** The count tiles of a report, in panel order. Problems only appear when there are some. */
export function reportTiles(report) {
  const tiles = [
    { key: 'paired', label: 'Paired', value: report.paired },
    { key: 'merged', label: 'Merged', value: report.merged },
    { key: 'editionsMoved', label: 'Editions moved', value: report.editionsMoved },
    { key: 'metadataApplied', label: 'Metadata applied', value: report.metadataApplied },
    { key: 'postersStored', label: 'Posters stored', value: report.postersStored },
    { key: 'backdropsStored', label: 'Backdrops stored', value: report.backdropsStored },
    { key: 'watchedImported', label: 'Watched', value: report.watchedImported },
    { key: 'resumePointsImported', label: 'Resume points', value: report.resumePointsImported },
    { key: 'keptNewer', label: 'Kept (CineFilm newer)', value: report.keptNewer },
    { key: 'onDiskNotInPlex', label: 'On disk, not in Plex', value: report.onDiskNotInPlex },
  ];
  const sometimes = [
    ['failures', 'Failed'],
    ['artworkFailures', 'Artwork failed'],
    ['invalidArtPaths', 'Artwork paths refused'],
    ['tmdbUnavailable', 'TMDB unavailable'],
  ];
  sometimes.forEach(([key, label]) => {
    if (report[key] > 0) tiles.push({ key, label, value: report[key] });
  });
  return tiles;
}

/** The named lists of a report, each with how many there really were; empty lists are left out. */
export function reportLists(report) {
  return [
    {
      key: 'failedFilms',
      title: 'Could not import',
      items: report.failedFilms,
      total: report.failures,
    },
    {
      key: 'notOnDisk',
      title: 'In Plex, not on disk',
      items: report.notOnDisk,
      total: report.notOnDiskTotal,
    },
    {
      key: 'collections',
      title: 'Plex collections not imported',
      items: report.collections,
      total: report.collectionsTotal,
    },
  ].filter((list) => list.items.length > 0);
}

/** "and 48 more" under a capped list, or null when everything is shown. */
export function moreText(list) {
  const more = list.total - list.items.length;
  return more > 0 ? `and ${more} more` : null;
}
