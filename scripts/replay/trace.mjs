// The link of the 12 November journey, one figure a minute, rebuilt from the playback-health record.
//
// Method. The record's minute rows are [minute, median kbit/s of the speed samples, rung played, ms the picture
// moved, ms stalled]. It was made under the old rules: a two-minute buffer, a 360p floor.
//
//  - A minute with 5 s or more stalled (and at least 50 s spent moving or stalled, so not a paused one): the
//    buffer was empty, so what played is what arrived. The link carried (seconds moved / 60) x the wire rate of
//    the rung played. Nothing moved means nothing arrived: 0.
//  - A clean minute: the median of the speed samples taken in it. That overstates what a film gets, because a
//    sample times a segment from its first byte and leaves out the wait before it, so two traces are made:
//    "sampled" takes clean minutes at the sampled figure, "cautious" at 0.6 of it.
//  - A clean minute with no sample (nothing was fetched: the buffer was full) takes the lower of its neighbours.
//  - Minutes the record has no row for (the film was paused or over) take the lower of their neighbours too.
//
// Wire rates of the old ladder: video plus audio, plus 8% (WIRE) for the MPEG-TS wrapping (360p: 600 + 96 kbit/s
// gives about 750 on the wire, the figure train-link-plan.md uses).
//
// Run: node scripts/replay/trace.mjs    prints both traces and the check on the six bad spells; writes no file
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
export const RECORD_PATH = join(HERE, 'record', 'train-2026-11-12.json');
/** What the MPEG-TS wrapping adds to a segment's bitrate: the weight of a segment, and of a stalled minute's film. */
export const WIRE = 1.08;
// Rung codes in the record -> kbit/s of video plus audio under the old ladder.
const RUNG_KBPS = { 1: 10128, 2: 5128, 3: 2628, 4: 1128, 5: 696, 6: 496 };
export const CAUTIOUS_FACTOR = 0.6;
// The six bad spells, as record minutes (first, last).
export const SPELLS = [[5, 7], [12, 23], [27, 37], [52, 61], [81, 85], [100, 110]];

const STALLED_MS = 5000;
const AWAKE_MS = 50000;

export function loadRecord(path = RECORD_PATH) {
  return JSON.parse(readFileSync(path, 'utf8'));
}

/** The record's rows by minute number. */
export function rowsByMinute(record) {
  return new Map(record.report.minutes.map((row) => [row[0], row]));
}

// One row's figure in kbit/s, or null when it has none ("the lower of the neighbours" then fills it).
function figureOf(row, cleanFactor) {
  if (!row) return null;
  const [, kbps, rung, moved, stalled] = row;
  // "What played is what arrived" only holds for a minute the film spent trying to play: one that was mostly
  // paused (minute 111, the stop for the AirPods) is read from its samples like a clean one.
  if (stalled >= STALLED_MS && moved + stalled >= AWAKE_MS) {
    const wire = (RUNG_KBPS[rung] ?? RUNG_KBPS[5]) * WIRE;
    return Math.round((moved / 60000) * wire);
  }
  return kbps > 0 ? Math.round(kbps * cleanFactor) : null;
}

// The nearest figure from minute `from` in steps of `step`, or null when there is none that way.
function nearest(figures, from, step) {
  for (let i = from; i >= 0 && i < figures.length; i += step) {
    if (figures[i] !== null) return figures[i];
  }
  return null;
}

// Fills the gaps from the nearest minutes that have a figure, taking the lower.
function fillGaps(figures) {
  return figures.map((value, n) => {
    if (value !== null) return value;
    const known = [nearest(figures, n - 1, -1), nearest(figures, n + 1, 1)].filter((v) => v !== null);
    return known.length ? Math.min(...known) : 0;
  });
}

/** kbit/s for every minute from 0 to the record's last, by the method above. */
export function buildTrace(record, { cleanFactor = 1 } = {}) {
  const rows = rowsByMinute(record);
  const last = Math.max(...rows.keys());
  const figures = [];
  for (let n = 0; n <= last; n += 1) figures.push(figureOf(rows.get(n), cleanFactor));
  return fillGaps(figures);
}

export function traces(record = loadRecord()) {
  return {
    sampled: buildTrace(record, { cleanFactor: 1 }),
    cautious: buildTrace(record, { cleanFactor: CAUTIOUS_FACTOR }),
  };
}

export function spellAverage(trace) {
  let sum = 0;
  let count = 0;
  for (const [first, last] of SPELLS) {
    for (let n = first; n <= last; n += 1) {
      sum += trace[n];
      count += 1;
    }
  }
  return sum / count;
}

export function clockOf(record, minute) {
  const start = new Date(record.startedAt).getTime();
  return new Date(start + minute * 60000).toISOString().slice(11, 19);
}

function printTraces() {
  const record = loadRecord();
  const both = traces(record);
  const rows = rowsByMinute(record);
  console.log('minute clock     moved stalled sampled  ->  sampled-trace cautious-trace (kbit/s)');
  both.sampled.forEach((value, n) => {
    const row = rows.get(n);
    const moved = row ? Math.round(row[3] / 1000) : '-';
    const stalled = row ? Math.round(row[4] / 1000) : '-';
    const sampled = row ? row[1] : '-';
    const left = `${String(n).padStart(4)}   ${clockOf(record, n)} ${String(moved).padStart(5)}`;
    console.log(`${left} ${String(stalled).padStart(7)} ${String(sampled).padStart(7)}      ${String(value).padStart(8)} ${String(both.cautious[n]).padStart(8)}`);
  });
  for (const [name, trace] of Object.entries(both)) {
    const mean = trace.reduce((a, b) => a + b, 0) / trace.length;
    console.log(`${name}: mean ${Math.round(mean)} kbit/s over ${trace.length} minutes; inside the six spells ${Math.round(spellAverage(trace))} kbit/s (expected about 300)`);
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) printTraces();
