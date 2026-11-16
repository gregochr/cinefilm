// Checks a finished replay and measures what its summary does not show.
//
//   node scripts/replay/analyse.mjs <run name>
//
// 1. Was the run sound? The sampling cadence, the samples that were not playing, the playhead against the wall
//    clock, and what the stand-in delivered against the trace. A stall with film in hand, or a playhead that
//    falls behind the clock outside a stall, means the machine was too busy and the run proves nothing.
// 2. Gaps of over 30 s in which no segment was being fetched (the player had as much as it wanted).
// 3. The six bad spells: the buffer going in, its lowest point, seconds stalled beside 12 November.
// 4. What the screen showed against the link, the rungs beside 12 November, the bytes fetched.
//
// The sums are exported functions over a run's data (what the driver wrote to scripts/replay/out/<name>/); the
// lines they give are made by `describe` and printed only when this runs as a program. From the request log it reads
// only `kind`, `rung`, `ms`, `sent`, `firstByteMs`, `doneMs` and `aborted`.
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { isRunName, printable } from './text.mjs';
import { SPELLS, loadRecord, rowsByMinute } from './trace.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const RUNG = { 1: 'orig', 2: '1080p', 3: '720p', 4: '480p', 5: '360p', 6: '240p' };
const LINE_LIMIT = 2000; // the list of thin minutes of a long run is one long line
const LONG_GAP_MS = 6500; // a sample taken more than this after the one before means a busy or sleeping machine
const IDLE_GAP_MS = 30000;
const WARM_UP_MS = 20000;
const GOOD_LINK_KBPS = 2000;
const THIN_KBPS = 700;
const SAMPLE_SECONDS = 5;

const secs = (ms) => (ms / 1000).toFixed(0);
const pad = (value, width) => String(value).padStart(width);
const sumOf = (list, pick) => list.reduce((sum, item) => sum + pick(item), 0);
const listRungs = (rungMs) => Object.entries(rungMs ?? {}).map(([id, ms]) => `${id} ${secs(ms)}`).join(', ');

/** The record minute a run began at, from the first line of its summary ("Replay of record minutes 24 to ..."). */
export function startOf(summaryText) {
  const found = /^Replay of record minutes (\d+) to /m.exec(summaryText);
  return found ? Number(found[1]) : 0;
}

/** The trace's kbit/s in force in each replay minute, as the samples saw it. */
export function linkByMinute(samples) {
  const link = new Map();
  for (const s of samples) link.set(Math.floor(s.ms / 60000), s.kbps);
  return link;
}

/** The film buffered ahead, in whole seconds, at the sample nearest to `ms` (the earlier one when two are as near). */
export function aheadAt(samples, ms) {
  let best = samples[0];
  for (const s of samples) {
    if (Math.abs(s.ms - ms) < Math.abs(best.ms - ms)) best = s;
  }
  return Math.round(best.ahead);
}

// ---- 1. was the run sound ----------------------------------------------------------------------------------------

/** The gaps between samples: how many, the longest in ms, and how many were over 6,500 ms. */
export function samplingGaps(samples) {
  let longest = 0;
  let long = 0;
  for (let i = 1; i < samples.length; i += 1) {
    const gap = samples[i].ms - samples[i - 1].ms;
    longest = Math.max(longest, gap);
    if (gap > LONG_GAP_MS) long += 1;
  }
  return { samples: samples.length, longest, long };
}

/** The samples after the first 20 s that were buffering, not ready to play, or paused. */
export function notPlaying(samples) {
  return samples.filter((s) => s.ms > WARM_UP_MS && (s.buffering || s.readyState < 3 || s.paused));
}

/** How many of those had more than 5 s of film in hand: a stall for the wrong reason. */
export function withFilmInHand(paused) {
  return paused.filter((s) => s.ahead > 5).length;
}

/**
 * How far the playhead fell behind the clock between samples, from the fourth sample on: the seconds by which a
 * step fell more than half a second short, in all and where both ends of the step had over 10 s in hand. `moved` and
 * `wall` are the film's seconds and the clock's seconds from the third sample to the last.
 */
export function playheadLag(samples) {
  if (samples.length < 3) return { behind: 0, behindWithFilm: 0, moved: 0, wall: 0 };
  let behind = 0;
  let behindWithFilm = 0;
  for (let i = 3; i < samples.length; i += 1) {
    const short = (samples[i].ms - samples[i - 1].ms) / 1000 - (samples[i].t - samples[i - 1].t);
    if (short > 0.5) {
      behind += short;
      if (samples[i - 1].ahead > 10 && samples[i].ahead > 10) behindWithFilm += short;
    }
  }
  const last = samples[samples.length - 1];
  return { behind, behindWithFilm, moved: last.t - samples[2].t, wall: (last.ms - samples[2].ms) / 1000 };
}

// Adds `sent` bytes to the minutes from `from` to `to` (ms), in proportion to the time spent in each.
function spread(perMinute, from, to, sent) {
  const last = perMinute.length - 1;
  let t = from;
  while (t < to) {
    const m = Math.floor(t / 60000);
    const end = Math.min(to, (m + 1) * 60000);
    perMinute[Math.min(last, m)] += (sent * (end - t)) / (to - from);
    t = end;
  }
}

/**
 * Bytes delivered in each replay minute (`minutesRun` + 1 of them; the last holds anything later). A request counts
 * from its first byte to its end, in proportion to the time in each minute; one with no end (the player cut it
 * short) or no time between its first byte and its end counts in the minute of its first byte.
 */
export function deliveredPerMinute(requests, minutesRun) {
  const perMinute = new Array(minutesRun + 1).fill(0);
  for (const r of requests) {
    if (!r.sent || !Number.isFinite(r.ms)) continue;
    const from = r.ms + (r.firstByteMs || 0);
    const to = Number.isFinite(r.doneMs) ? r.ms + r.doneMs : null;
    if (to === null || to <= from) {
      perMinute[Math.min(minutesRun, Math.floor(from / 60000))] += r.sent;
    } else {
      spread(perMinute, from, to, r.sent);
    }
  }
  return perMinute;
}

/**
 * What the stand-in delivered against what the trace allowed: bytes both ways, the minutes delivered more than 5%
 * and 60 kbit/s above the trace (`over`), and the thin ones (trace under 700 kbit/s) as `{ minute, rate, got }`.
 */
export function deliveryAgainstTrace(perMinute, link, start, minutesRun) {
  let over = 0;
  let delivered = 0;
  let allowed = 0;
  const thin = [];
  for (let m = 0; m < minutesRun; m += 1) {
    const got = (perMinute[m] * 8) / 60 / 1000;
    const rate = link.get(m) ?? 0;
    delivered += perMinute[m];
    allowed += (rate * 60 * 1000) / 8;
    if (got > rate * 1.05 + 60) over += 1;
    if (rate < THIN_KBPS) thin.push({ minute: m + start, rate, got: Math.round(got) });
  }
  return { delivered, allowed, over, thin };
}

/** The whole of section 1. */
export function soundness({ samples, requests, start }) {
  const minutesRun = Math.ceil(samples[samples.length - 1].ms / 60000);
  const paused = notPlaying(samples);
  const perMinute = deliveredPerMinute(requests, minutesRun);
  return {
    gaps: samplingGaps(samples),
    notPlaying: paused,
    withFilm: withFilmInHand(paused),
    lag: playheadLag(samples),
    delivery: deliveryAgainstTrace(perMinute, linkByMinute(samples), start, minutesRun),
  };
}

// ---- 2. gaps with nothing fetched --------------------------------------------------------------------------------

/**
 * The stretches of over 30 s in which no segment was being fetched, taking each segment from its request to its
 * end. Each is `{ gap, first, last, before, after, aheadBefore, aheadAfter, rates }`: its length in ms, the replay
 * minutes it spans, the rungs fetched either side, the film buffered then and the link in each minute.
 */
export function idleGaps(requests, samples, link) {
  const segments = requests.filter((r) => r.kind === 'segment' && Number.isFinite(r.ms)).sort((a, b) => a.ms - b.ms);
  if (segments.length === 0) return [];
  const gaps = [];
  let lastEnd = segments[0].ms;
  let previous = segments[0];
  for (const r of segments) {
    if (r.ms - lastEnd > IDLE_GAP_MS) gaps.push(describeGap(r, previous, lastEnd, samples, link));
    const end = Number.isFinite(r.doneMs) ? r.ms + r.doneMs : r.ms;
    if (end > lastEnd) lastEnd = end;
    previous = r;
  }
  return gaps;
}

function describeGap(next, previous, lastEnd, samples, link) {
  const first = Math.floor(lastEnd / 60000);
  const last = Math.floor(next.ms / 60000);
  const rates = [];
  for (let m = first; m <= last; m += 1) rates.push(link.get(m));
  return {
    gap: next.ms - lastEnd, first, last, before: previous.rung, after: next.rung,
    aheadBefore: aheadAt(samples, lastEnd), aheadAfter: aheadAt(samples, next.ms), rates,
  };
}

// ---- 3. the six bad spells ---------------------------------------------------------------------------------------

/**
 * For each of the six spells the run covered completely (not the first replay minute, nor one past the last): the mean
 * link, the seconds banked going in, the lowest figure, the ms stalled now and on 12 November, and the rungs fetched.
 */
export function spellFigures({ report, samples, record, start }) {
  const minutesRun = Math.ceil(samples[samples.length - 1].ms / 60000);
  const rows = new Map(report.minutes.map((row) => [row[0], row]));
  const old = rowsByMinute(record);
  const link = linkByMinute(samples);
  const figures = [];
  for (const [first, last] of SPELLS) {
    if (first - start < 1 || last - start >= minutesRun) continue;
    figures.push(oneSpell({ first, last, rows, old, link, start }));
  }
  return figures;
}

function oneSpell({ first, last, rows, old, link, start }) {
  let stalled = 0;
  let stalledThen = 0;
  let lowest = Infinity;
  let sum = 0;
  const fetched = new Set();
  for (let m = first; m <= last; m += 1) {
    const row = rows.get(m - start);
    if (!row) continue;
    sum += link.get(m - start) ?? 0;
    stalledThen += (old.get(m) ?? [0, 0, 0, 0, 0])[4];
    stalled += row[4];
    lowest = Math.min(lowest, row[5]);
    fetched.add(RUNG[row[6]]);
  }
  return {
    first, last, meanLink: Math.round(sum / (last - first + 1)), goingIn: rows.get(first - start - 1)?.[5], lowest,
    stalled, stalledThen, fetched: [...fetched],
  };
}

// ---- 4. screen, rungs and bytes ----------------------------------------------------------------------------------

/**
 * The seconds the pill said 240p (and how many of them with the link at 2 Mbit/s or more), the seconds it said 360p
 * with the link at 2 Mbit/s or more, and the toasts the samples caught, as `[text, count]`. Each sample counts for
 * five seconds.
 */
export function screenAgainstLink(samples) {
  let floorShown = 0;
  let floorOnGoodLink = 0;
  let holdOnGoodLink = 0;
  for (const s of samples) {
    const floor = /240p/.test(s.pill ?? '');
    const good = s.kbps >= GOOD_LINK_KBPS;
    if (floor) floorShown += SAMPLE_SECONDS;
    if (floor && good) floorOnGoodLink += SAMPLE_SECONDS;
    if (/360p/.test(s.pill ?? '') && good) holdOnGoodLink += SAMPLE_SECONDS;
  }
  return { floorShown, floorOnGoodLink, holdOnGoodLink, toasts: toastCounts(samples) };
}

// The toasts the samples caught, as [text, count], in the order first seen.
function toastCounts(samples) {
  const toasts = new Map();
  for (const s of samples) {
    if (s.toast) toasts.set(s.toast, (toasts.get(s.toast) ?? 0) + 1);
  }
  return [...toasts];
}

/** The segments fetched: bytes by rung, the count, and those the player cut short with their bytes. */
export function rungsAndBytes(requests) {
  const segments = requests.filter((r) => r.kind === 'segment');
  const bytes = new Map();
  for (const r of segments) bytes.set(r.rung, (bytes.get(r.rung) ?? 0) + r.sent);
  const cut = segments.filter((r) => r.aborted);
  return { bytes: [...bytes], count: segments.length, cut: cut.length, cutBytes: sumOf(cut, (r) => r.sent) };
}

// ---- the words ---------------------------------------------------------------------------------------------------

function soundLines(s) {
  const { gaps, lag, delivery } = s;
  const lines = ['', '== 1. Was the run sound? =='];
  lines.push(`  samples: ${gaps.samples}, longest gap ${gaps.longest} ms, gaps over 6.5 s: ${gaps.long} `
    + '(a long gap is a busy or sleeping machine)');
  lines.push(`  samples not playing after the first 20 s: ${s.notPlaying.length}`);
  for (const p of s.notPlaying) {
    lines.push(`    minute ${Math.floor(p.ms / 60000)}: playhead ${p.t}, ahead ${p.ahead} s, ${p.buffering ?? 'no label'}, `
      + `readyState ${p.readyState}`);
  }
  lines.push(`  of those, with more than 5 s of film in hand: ${s.withFilm} (anything but 0 is a stall for the wrong reason)`);
  lines.push(`  playhead: ${lag.moved.toFixed(1)} s of film in ${lag.wall.toFixed(0)} s of clock; fell behind by `
    + `${lag.behind.toFixed(1)} s in all, ${lag.behindWithFilm.toFixed(1)} s of it with over 10 s in hand`);
  lines.push(`  delivered ${(delivery.delivered / 1e6).toFixed(0)} MB of the ${(delivery.allowed / 1e6).toFixed(0)} MB the `
    + `trace allowed; minutes delivered well above the trace: ${delivery.over}`);
  const thin = delivery.thin.map((t) => `${t.minute}:${t.rate}/${t.got}`).join('  ');
  lines.push(`  thin minutes (trace under 700 kbit/s), minute:trace/delivered: ${thin}`);
  return lines;
}

function idleLines(gaps, start) {
  const lines = ['', '== 2. Gaps of over 30 s with no segment being fetched =='];
  for (const g of gaps) {
    lines.push(`  ${pad(secs(g.gap), 4)} s, minutes ${g.first + start}-${g.last + start}: fetched ${g.before} before and `
      + `${g.after} after, ahead ${g.aheadBefore} s -> ${g.aheadAfter} s, link ${g.rates.join(', ')}`);
  }
  lines.push(`  in all: ${secs(sumOf(gaps, (g) => g.gap))} s`);
  return lines;
}

function spellLines(spells) {
  const lines = ['', '== 3. The six bad spells =='];
  for (const f of spells) {
    lines.push(`  minutes ${f.first}-${f.last}: mean link ${f.meanLink} kbit/s, banked going in ${f.goingIn} s, `
      + `lowest ${f.lowest} s, stalled ${secs(f.stalled)} s (12 Nov ${secs(f.stalledThen)} s), `
      + `fetched ${f.fetched.join(', ')}`);
  }
  return lines;
}

function totalsLine(label, r) {
  return `${label}: ${r.stallCount} stalls, ${secs(r.stallMs)} s stalled, longest ${(r.longestStallMs / 1000).toFixed(1)} s, `
    + `${secs(r.watchedMs)} s watched, steps down ${r.stepsDown}, up ${r.stepsUp}, timeouts ${r.timeouts}`;
}

function screenLines({ screen, fetched, report, then }) {
  const toasts = screen.toasts.map(([text, n]) => `${n} x "${text}"`).join('; ') || 'none';
  const bytes = fetched.bytes.map(([rung, b]) => `${rung} ${(b / 1e6).toFixed(0)} MB`).join(', ');
  return [
    '', '== 4. Screen, rungs and bytes ==',
    `  240p on screen ${screen.floorShown} s, ${screen.floorOnGoodLink} s of it with the link at 2 Mbit/s or more; `
      + `360p on screen with the link at 2 Mbit/s or more: ${screen.holdOnGoodLink} s`,
    `  toasts caught by the five-second samples: ${toasts}`,
    `  ${totalsLine('replay', report)}`,
    `          seconds on each rung: ${listRungs(report.rungMs)}`,
    `  ${totalsLine('12 Nov', then)}`,
    `          seconds on each rung: ${listRungs(then.rungMs)}`,
    `  fetched: ${bytes}; ${fetched.count} segments, ${fetched.cut} cut short by the player `
      + `(${(fetched.cutBytes / 1e6).toFixed(1)} MB thrown away)`,
  ];
}

/**
 * The lines of the whole analysis of one run: `{ name, heading, samples, requests, report, record, start }`, where
 * `heading` is the first line of its summary.
 */
export function describe({ name, heading, samples, requests, report, record, start }) {
  const link = linkByMinute(samples);
  return [
    `${name}: ${heading}`,
    ...soundLines(soundness({ samples, requests, start })),
    ...idleLines(idleGaps(requests, samples, link), start),
    ...spellLines(spellFigures({ report, samples, record, start })),
    ...screenLines({
      screen: screenAgainstLink(samples), fetched: rungsAndBytes(requests), report, then: record.report,
    }),
  ];
}

// ---- as a program ------------------------------------------------------------------------------------------------

function readJson(dir, file) {
  try {
    return JSON.parse(readFileSync(join(dir, file), 'utf8'));
  } catch (error) {
    if (error?.code === 'ENOENT') throw new Error(`${file} is not in this run's folder`);
    throw new Error(`${file} cannot be read as JSON`);
  }
}

/** Reads the run called `name` from scripts/replay/out and returns the lines of its analysis. */
export function analyseRun(name) {
  if (!isRunName(name)) {
    throw new Error('usage: node scripts/replay/analyse.mjs <run name> (letters, digits, dot, dash and underscore)');
  }
  const dir = join(HERE, 'out', name);
  const requests = readJson(dir, 'requests.json');
  const samples = readJson(dir, 'samples.json');
  const report = readJson(dir, 'health.json').final?.report;
  if (!report) throw new Error('no health report in this run: nothing to analyse');
  if (samples.length === 0) throw new Error('no samples in this run: nothing to analyse');
  let summary = '';
  try {
    summary = readFileSync(join(dir, 'summary.txt'), 'utf8');
  } catch {
    // Without its summary the run is still analysed, from record minute 0.
  }
  const heading = summary.split('\n').find((line) => line.startsWith('Replay')) ?? '(no summary line)';
  return describe({ name, heading, samples, requests, report, record: loadRecord(), start: startOf(summary) });
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  try {
    for (const line of analyseRun(process.argv[2])) console.log(printable(line, LINE_LIMIT));
  } catch (error) {
    console.error(printable(error?.message ?? error, LINE_LIMIT));
    process.exitCode = 1;
  }
}
