// What the Admin "How films played" chart draws, from the chart projection (`GET /api/admin/playback-health/chart`)
// and from one full record's `minutes`. Pure, no JSX, no DOM: the Apple TV and native clients reuse it.
//
// The server already keeps only film sessions (`FILM_SESSION_MIN_MS`, three minutes), so nothing here filters on
// watched time again; every count is a count of sessions (a film watched twice counts twice).
import { playbackChart as copy } from './adminCopy.js';
import { environmentLabel, stallDuration } from './healthFormat.js';
import { formatRuntime } from '../player/time.js';
import { formatScanTime } from './scanFormat.js';
import { describeDevice } from './userAgent.js';

const num = (value) => (Number.isFinite(value) ? value : 0);

/** The four stacked tones, bottom to top, neutral on purpose: amber is for actions. Stall dots are `STALL_COLOUR`. */
export const RUNG_BUCKETS = [
  { key: 'original', label: 'Original', colour: '#EDE6DA' },
  { key: 'p1080', label: '1080p', colour: '#BFB5A5' },
  { key: 'p720', label: '720p', colour: '#8E8576' },
  { key: 'low', label: '480p or lower', colour: '#5E574C' },
];
export const STALL_COLOUR = '#F0876A';

const BUCKET_OF_RUNG = {
  original: 'original',
  '1080p': 'p1080',
  '720p': 'p720',
  '480p': 'low',
  '360p': 'low',
  '240p': 'low',
};
/** The player's rung codes in `minutes` rows (`player/health.js`); 0 means none was known. */
const RUNG_OF_CODE = { 1: 'original', 2: '1080p', 3: '720p', 4: '480p', 5: '360p', 6: '240p' };

export const ENVIRONMENT_FILTERS = ['all', 'home', 'away', 'train'];
const LETTERS = { home: 'H', away: 'A', train: 'T' };

/** `home`, `away`, `train`, else `unknown`. */
export const environmentKey = (environment) =>
  Object.hasOwn(LETTERS, environment) ? environment : 'unknown';

/** The letter under a column: H, A, T, or ? for an unknown environment. */
export const environmentLetter = (environment) => LETTERS[environmentKey(environment)] ?? '?';

/** The sessions of one environment; "all" keeps every one, and only "all" keeps an unknown environment. */
export function filterByEnvironment(sessions, filter) {
  if (filter === 'all') return sessions;
  return sessions.filter((session) => environmentKey(session.environment) === filter);
}

/** The time on rungs the ladder knows, by bucket: `{ ms: { original, p1080, p720, low }, total }`. */
function bucketTimes(rungMs) {
  const ms = Object.fromEntries(RUNG_BUCKETS.map(({ key }) => [key, 0]));
  let total = 0;
  for (const [id, value] of Object.entries(rungMs ?? {})) {
    const bucket = BUCKET_OF_RUNG[id];
    if (bucket && num(value) > 0) {
      ms[bucket] += value;
      total += value;
    }
  }
  return { ms, total };
}

/**
 * The share of a session's time on each bucket: `{ original, p1080, p720, low }`, fractions of the time on known
 * rungs, adding up to 1; all 0 when no time is recorded (a report that cannot be read has no `rungMs`). A rung id the
 * ladder does not know is left out of the total.
 */
export function bucketShares(rungMs) {
  const { ms, total } = bucketTimes(rungMs);
  return Object.fromEntries(
    Object.entries(ms).map(([key, value]) => [key, total === 0 ? 0 : value / total]),
  );
}

/** Where the stall dot grows: under `SMALL_BELOW_MS` small, up to `MEDIUM_BELOW_MS` medium, then large. */
export const STALL_SMALL_BELOW_MS = 5_000;
export const STALL_MEDIUM_BELOW_MS = 20_000;

/** The diameter in px of a stall dot for `stallMs` of stalling; 0 (no dot) when there was none. */
export function stallDotSize(stallMs) {
  const ms = num(stallMs);
  if (ms <= 0) return 0;
  if (ms < STALL_SMALL_BELOW_MS) return 8;
  if (ms < STALL_MEDIUM_BELOW_MS) return 12;
  return 16;
}

/**
 * The dot under a chart column: shown whenever the session stalled (`stallCount` > 0, the same test as the "stalled"
 * tile), sized by `stallMs`; a stall that left no measured time still gets the smallest dot.
 */
export function sessionDotSize(session) {
  if (!(num(session.stallCount) > 0)) return 0;
  return Math.max(stallDotSize(session.stallMs), stallDotSize(1));
}

/** The middle value; the mean of the two middle ones for an even count; null for none. */
export function median(values) {
  if (values.length === 0) return null;
  const sorted = values.toSorted((a, b) => a - b);
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
}

/**
 * The tiles over `sessions`: how many were watched, how many ran without a stall and how many stalled; the
 * environment every stalled one shares (`stalledEnvironment`, null when they differ, none stalled or the shared one
 * is unknown); the median start-up time (null when none ever started); and the percentage of time at Original or
 * 1080p across all sessions (null with no time recorded).
 */
export function chartTiles(sessions) {
  const stalled = sessions.filter((s) => num(s.stallCount) > 0);
  const environments = new Set(stalled.map((s) => environmentKey(s.environment)));
  const only = environments.size === 1 ? [...environments][0] : null;
  const startups = sessions.map((s) => s.startupMs).filter((ms) => Number.isFinite(ms));
  let high = 0;
  let total = 0;
  for (const session of sessions) {
    const times = bucketTimes(session.rungMs);
    high += times.ms.original + times.ms.p1080;
    total += times.total;
  }
  return {
    watched: sessions.length,
    withoutStall: sessions.length - stalled.length,
    stalled: stalled.length,
    stalledEnvironment: only && only !== 'unknown' ? only : null,
    medianStartupMs: median(startups),
    topRungPercent: total > 0 ? Math.round((high * 100) / total) : null,
  };
}

/** "1.6 s", or an en dash when there is none. */
export const startupText = (ms) => (ms == null ? '–' : `${(ms / 1000).toFixed(1)} s`);

/** "86%", or an en dash when there is none. */
export const percentText = (percent) => (percent == null ? '–' : `${percent}%`);

/** "(all on the train)" and its like, or null when the stalled sessions do not share one place. */
export const stalledNote = (environment) => copy.allOn[environment] ?? null;

const percentOf = (share) => Math.round(share * 100);

/** "Original 70%, 480p or lower 30%": the buckets that hold time, or "no quality recorded". */
function qualityWords(rungMs) {
  const shares = bucketShares(rungMs);
  const parts = RUNG_BUCKETS.filter(({ key }) => shares[key] > 0).map(
    ({ key, label }) => `${label} ${percentOf(shares[key])}%`,
  );
  return parts.length > 0 ? parts.join(', ') : copy.noQuality;
}

/** What a screen reader hears for one column: "Heat, 10 Nov 19:43, Train. 2 stalls, 9 s. Original 70%, …". */
export function columnLabel(session) {
  const count = num(session.stallCount);
  let stalls = copy.noStalls;
  if (count > 0)
    stalls = `${count} ${count === 1 ? 'stall' : 'stalls'}, ${stallDuration(session.stallMs)}`;
  return `${session.filmTitle}, ${formatScanTime(session.startedAt)}, ${environmentLabel(session.environment)}. ${stalls}. ${qualityWords(session.rungMs)}.`;
}

/** The text equivalent of the whole chart. */
export function chartSummary(sessions, { days, environment }) {
  const where = environment === 'all' ? '' : ` on ${environmentLabel(environment)}`;
  if (sessions.length === 0) return copy.summaryEmpty(days, where);
  const tiles = chartTiles(sessions);
  const parts = [
    `${tiles.withoutStall} played without a stall`,
    `${tiles.stalled} stalled`,
    `typical start ${startupText(tiles.medianStartupMs)}`,
    `${percentText(tiles.topRungPercent)} of time at Original or 1080p`,
  ];
  return copy.summary(sessions.length, days, where, parts.join(', '));
}

/**
 * The parts after the film's title in the selected session's heading, as an array: ["10 Nov 19:43", "iPad",
 * "Train (EE)", "1h 12m watched"]. `operator` is the record's, once it is read; without it the place stands alone.
 */
export function sessionHeading(session, operator) {
  const place = environmentLabel(session.environment);
  return [
    formatScanTime(session.startedAt),
    describeDevice(session.userAgent, session.touch),
    operator ? `${place} (${operator})` : place,
    `${formatRuntime(num(session.watchedMs))} watched`,
  ];
}

// The per-minute strip, drawn in a 1000 x 124 box: stall dots on top, the speed line, then the quality band.
export const STRIP = {
  width: 1000,
  height: 124,
  dotY: 8,
  lineTop: 24,
  lineBottom: 88,
  bandY: 98,
  bandHeight: 10,
};

/**
 * One `minutes` row as `{ minute, kbps, rung, stalledMs }`, or null for a row that is not one. A row is seven
 * figures `[minute, median kbit/s, rung played, ms moved, ms stalled, seconds buffered ahead, rung being fetched]`,
 * or five (an older record, without the last two); the figures read here are in both.
 */
function minuteRow(row) {
  if (!Array.isArray(row) || (row.length !== 5 && row.length !== 7)) return null;
  if (!row.every((figure) => Number.isFinite(figure))) return null;
  return { minute: row[0], kbps: row[1], rung: RUNG_OF_CODE[row[2]] ?? null, stalledMs: row[4] };
}

const round1 = (n) => Math.round(n * 10) / 10;

/** "1:49" for 109 minutes. */
const clock = (minutes) => `${Math.floor(minutes / 60)}:${String(minutes % 60).padStart(2, '0')}`;

/**
 * The strip for a record's `minutes`: `{ count, durationLabel, line, bands, stalls }`, or null when there is no
 * readable row. `line` is an SVG path of the median speed (a minute with no speed sample breaks it), `bands` the
 * rung played in each minute (`{ x, width, bucket }`; a minute whose rung is unknown has none) and `stalls` a dot in
 * each minute that stalled (`{ x, size, minute }`, sized like the chart's).
 */
export function minuteStrip(rows) {
  const points = (Array.isArray(rows) ? rows : [])
    .map(minuteRow)
    .filter((point) => point !== null && point.minute >= 0)
    .toSorted((a, b) => a.minute - b.minute);
  if (points.length === 0) return null;
  const count = points.at(-1).minute + 1;
  const slot = STRIP.width / count;
  const fastest = Math.max(...points.map((p) => p.kbps), 1);
  const span = STRIP.lineBottom - STRIP.lineTop;
  const centre = (point) => round1((point.minute + 0.5) * slot);
  const level = (point) => round1(STRIP.lineBottom - (Math.max(point.kbps, 0) / fastest) * span);
  let line = '';
  let previous = null;
  for (const point of points) {
    if (point.kbps <= 0) {
      previous = null;
      continue;
    }
    const joined = previous !== null && point.minute === previous.minute + 1;
    line += `${joined ? 'L' : 'M'}${centre(point)} ${level(point)}`;
    // A lone minute is a point: a zero-length line segment, drawn round.
    if (!joined) line += `L${centre(point)} ${level(point)}`;
    previous = point;
  }
  return {
    count,
    durationLabel: clock(count),
    line,
    bands: points
      .filter((p) => p.rung !== null)
      .map((p) => ({
        x: round1(p.minute * slot),
        width: round1(Math.max(slot - 1, 0.5)),
        bucket: BUCKET_OF_RUNG[p.rung],
      })),
    stalls: points
      .filter((p) => stallDotSize(p.stalledMs) > 0)
      .map((p) => ({ x: centre(p), size: stallDotSize(p.stalledMs), minute: p.minute })),
  };
}

/** The colour of a rung bucket. */
export const bucketColour = (key) => RUNG_BUCKETS.find((b) => b.key === key)?.colour;
