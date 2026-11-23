// What the Admin "Playback log" shows for one stored playback. No JSX, no DOM: other clients reuse it. A report from
// an older release can lack any field, so a missing number counts as 0 and a missing string is left out.
import { rungName } from '../player/ladder.js';
import { formatRuntime } from '../player/time.js';
import { playbackLog as copy } from './adminCopy.js';
import {
  deviceLabel,
  environmentLabel,
  FILM_SESSION_MIN_MS,
  isHeadless,
  operatorName,
  rungParts,
  speedText,
  stallDuration,
} from './healthFormat.js';
import { formatScanTime } from './scanFormat.js';

/** Rows per page of the log. */
export const LOG_PAGE_SIZE = 50;

const num = (value) => (Number.isFinite(value) ? value : 0);
const plural = (n, one, many) => `${n} ${n === 1 ? one : many}`;

/**
 * The list route's parameters for a filter and a zero-based page. The server does the filtering: "films" is
 * `minWatchedMs` at the film-session minimum, "problems" is `problems=true`, "robots" is `source=robot` (the headless
 * viewer's runs), "all" sends only the paging. The other filters send no `source`, so they list both kinds.
 */
export function logQuery(filter, page) {
  const paging = { page, size: LOG_PAGE_SIZE };
  if (filter === 'films') return { minWatchedMs: FILM_SESSION_MIN_MS, ...paging };
  if (filter === 'problems') return { problems: true, ...paging };
  if (filter === 'robots') return { source: 'robot', ...paging };
  return paging;
}

/** True for a play shorter than a film session (under 3 minutes watched): a test, a skip or a misclick. */
export const isBrowsing = (row) => num(row.report?.watchedMs) < FILM_SESSION_MIN_MS;

/** "8 s" under a minute, "1m 10s" under an hour, then "2h 41m". */
export function watchedText(ms) {
  const seconds = Math.floor(num(ms) / 1000);
  if (seconds < 60) return `${seconds} s`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
  return formatRuntime(ms);
}

/**
 * The one rung a play spent most of its watched time on, as its name ("720p", "Original"), by the time on each
 * rung (not the rounded shares); an exact tie goes to the rung earlier in the ladder (the better picture); "—"
 * when no rung holds 1% (nothing watched). The shares of all the rungs are in the row's Details.
 */
export function mainRung(report) {
  const parts = rungParts(report);
  if (parts.length === 0) return '—';
  return parts.reduce((best, part) => (part.ms > best.ms ? part : best), parts[0]).name;
}

/** The cells of one table row, as text: `stalled` is true when the stall count is above zero. */
export function logRow(row) {
  const report = row.report ?? {};
  const stalls = num(report.stallCount);
  return {
    id: row.playbackId,
    film: row.filmTitle,
    started: formatScanTime(row.startedAt),
    browsing: isBrowsing(row),
    watched: watchedText(report.watchedMs),
    where: `${environmentLabel(report.environment)} · ${isHeadless(row) ? copy.headlessRun : deviceLabel(report.client?.userAgent, report.client?.touch)}`,
    start: report.startupMs == null ? '—' : `${(num(report.startupMs) / 1000).toFixed(1)} s`,
    stalls,
    stalled: stalls > 0,
    quality: mainRung(report),
  };
}

const ENGINES = { hlsjs: 'hls.js', native: 'native player' };

function playerValue(report) {
  const parts = [];
  if (typeof report.engine === 'string') parts.push(ENGINES[report.engine] ?? report.engine);
  if (typeof report.quality === 'string') parts.push(`quality ${rungName(report.quality)}`);
  parts.push(deviceLabel(report.client?.userAgent, report.client?.touch));
  if (typeof report.client?.screen === 'string') parts.push(report.client.screen.replace('x', '×'));
  return parts.join(' · ');
}

function networkValue(report) {
  const parts = [environmentLabel(report.environment)];
  const operator = operatorName(report.operator);
  if (operator) parts.push(operator);
  if (Number.isFinite(report.latencyMs)) parts.push(`${report.latencyMs} ms`);
  parts.push(speedText(report));
  return parts.join(' · ');
}

function qualityValue(report) {
  const shares = rungParts(report).map(({ name, percent }) => `${name} ${percent}%`);
  return [
    ...(shares.length > 0 ? shares : ['no quality recorded']),
    `${plural(num(report.stepsDown), 'step', 'steps')} down`,
    `${num(report.stepsUp)} up`,
  ].join(' · ');
}

function interruptionsValue(report) {
  const stalls = num(report.stallCount);
  const parts = [
    stalls > 0
      ? `${plural(stalls, 'stall', 'stalls')}, ${stallDuration(report.stallMs)}`
      : plural(0, 'stall', 'stalls'),
    plural(num(report.seekCount), 'seek wait', 'seek waits'),
    plural(num(report.reloadCount), 'reload', 'reloads'),
    plural(num(report.reconnectCount), 'reconnect', 'reconnects'),
    plural(num(report.netErrors) + num(report.timeouts), 'error', 'errors'),
  ];
  if (report.fellBack === true) parts.push('fell back to direct play');
  return parts.join(' · ');
}

/**
 * The four lines of a row's Details, `[{ key, label, value }]`: Player, Network, Quality and Interruptions, each
 * one line of words (never the report's JSON). Written from the report whatever it lacks.
 */
export function reportDetails(row) {
  const report = row.report ?? {};
  const labels = copy.detailLabels;
  return [
    { key: 'player', label: labels.player, value: playerValue(report) },
    { key: 'network', label: labels.network, value: networkValue(report) },
    { key: 'quality', label: labels.quality, value: qualityValue(report) },
    { key: 'interruptions', label: labels.interruptions, value: interruptionsValue(report) },
  ];
}

/** The report as it is stored, indented JSON, for the clipboard. The list route leaves out the per-minute figures. */
export const rawReport = (row) => JSON.stringify(row.report ?? {}, null, 2);

/** Puts the row's raw report on the clipboard; false when the browser has no clipboard or refuses it. */
export async function copyRawReport(row) {
  try {
    await globalThis.navigator.clipboard.writeText(rawReport(row));
    return true;
  } catch {
    return false;
  }
}
