// Display helpers for the Admin "Playback health" card. No JSX, no DOM: other clients reuse them. A report from
// an older release can lack any field, so a missing number counts as 0, a missing flag as false and a missing
// string as unknown.
import { rungName } from '../player/ladder.js';
import { formatRuntime } from '../player/time.js';
import { formatScanTime } from './scanFormat.js';

const MINUTE_MS = 60_000;
const RUNG_ORDER = ['original', '1080p', '720p', '480p', '360p', '240p'];
const DEVICE_WORDS = ['iPhone', 'iPad', 'Android', 'Windows', 'Mac', 'Linux'];
/**
 * The shortest watched time that counts as a film session (three minutes): shorter ones are tests, skips and
 * misclicks. The same figure as `PlaybackHealthService.FILM_SESSION_MIN_MS` on the server.
 */
export const FILM_SESSION_MIN_MS = 180_000;

const ENVIRONMENTS = { home: 'Home', train: 'Train', away: 'Away' };

const num = (value) => (Number.isFinite(value) ? value : 0);
const mbit = (kbps) => (num(kbps) / 1000).toFixed(1);

/** "Home", "Train" or "Away"; anything else, a missing value included, is "Unknown". */
export function environmentLabel(environment) {
  return Object.hasOwn(ENVIRONMENTS, environment) ? ENVIRONMENTS[environment] : 'Unknown';
}

/** The lookup's `org` without its leading "AS<digits> "; null when there is nothing to show. */
export function operatorName(operator) {
  if (typeof operator !== 'string') return null;
  const name = operator.replace(/^AS\d+\s+/, '').trim();
  return name === '' ? null : name;
}

/** The first platform word found in a user agent (an iPhone's also says "Mac OS X"), else "Unknown device". */
export function deviceLabel(userAgent) {
  const text = typeof userAgent === 'string' ? userAgent : '';
  return DEVICE_WORDS.find((word) => text.includes(word)) ?? 'Unknown device';
}

/** "9 s" under a minute, "2m 5s" from there, in whole rounded seconds. */
export function stallDuration(ms) {
  const seconds = Math.round(num(ms) / 1000);
  if (seconds < 60) return `${seconds} s`;
  return `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
}

/** "10 Nov 19:43 · 1h 12m watched · Mac". */
export function startedLine(row) {
  const report = row.report ?? {};
  const watchedMs = num(report.watchedMs);
  const watched = watchedMs < MINUTE_MS ? 'under 1m' : formatRuntime(watchedMs);
  return `${formatScanTime(row.startedAt)} · ${watched} watched · ${deviceLabel(report.client?.userAgent)}`;
}

function startText(report) {
  if (report.startupMs == null) return report.autoplayRefused ? 'Not played yet' : 'Did not start';
  return `Start ${(num(report.startupMs) / 1000).toFixed(1)} s`;
}

function stallText(report) {
  const count = num(report.stallCount);
  if (count === 0) return 'No stalls';
  return `${count} ${count === 1 ? 'stall' : 'stalls'}, ${stallDuration(report.stallMs)}`;
}

/** "Start 2.4 s · 2 stalls, 9 s · 1 reconnect · fell back to direct play". */
export function playLine(row) {
  const report = row.report ?? {};
  const reconnects = num(report.reconnectCount);
  let line = `${startText(report)} · ${stallText(report)}`;
  if (reconnects > 0) line += ` · ${reconnects} ${reconnects === 1 ? 'reconnect' : 'reconnects'}`;
  if (report.fellBack === true) line += ' · fell back to direct play';
  return line;
}

/** ["480p 34%", "360p 66%"]: rungs in ladder order holding at least 1% of the watched time. */
export function rungShares(report) {
  const watchedMs = num(report.watchedMs);
  if (watchedMs <= 0) return [];
  const rungMs = report.rungMs ?? {};
  return RUNG_ORDER.filter((id) => num(rungMs[id]) * 100 >= watchedMs).map(
    (id) => `${rungName(id)} ${Math.round((num(rungMs[id]) * 100) / watchedMs)}%`,
  );
}

/** "0.4 to 2.4 Mbit/s, typically 0.9", or "no speed samples". */
export function speedText(report) {
  if (num(report.kbpsSamples) <= 0) return 'no speed samples';
  return `${mbit(report.kbpsLow)} to ${mbit(report.kbpsHigh)} Mbit/s, typically ${mbit(report.kbpsMedian)}`;
}

/** The rung and speed line; null when nothing was watched. */
export function qualityLine(row) {
  const report = row.report ?? {};
  if (num(report.watchedMs) <= 0) return null;
  return [...rungShares(report), speedText(report)].join(' · ');
}

/** "Virgin Media Limited · 48 ms", either part alone when only one is known, null when neither is. */
export function networkLine(row) {
  const report = row.report ?? {};
  const parts = [];
  const name = operatorName(report.operator);
  if (name) parts.push(name);
  if (Number.isFinite(report.latencyMs)) parts.push(`${report.latencyMs} ms`);
  return parts.length > 0 ? parts.join(' · ') : null;
}

/** The lines of one list item, in order, the ones with nothing to say left out. */
export function describePlayback(row) {
  return [startedLine(row), playLine(row), qualityLine(row), networkLine(row)].filter(
    (line) => line !== null,
  );
}
