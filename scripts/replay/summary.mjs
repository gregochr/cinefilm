// The train viewer's summary (docs/engineering/train-viewer-plan.md, decision 12): the lines of `summary.txt` and what
// is printed when a run ends, as a pure function of the results the run keeps (`samples`, `health` and `held`, as
// `viewing.mjs` writes them). It takes only fields that are there and of the right type, and every line passes
// `printable`. There is no table of minutes: `health.json` holds the record and Admin shows the minutes.
import { printable } from './text.mjs';

const LINE_LIMIT = 300;
const MAX_HELD_LINES = 20;
const MAX_ERROR_LINES = 10;
const MAX_RUNGS = 12;

const number = (value) => (typeof value === 'number' && Number.isFinite(value) ? value : null);
const text = (value, limit) => (typeof value === 'string' && value !== '' ? printable(value, limit) : null);
const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
const objects = (value) => (Array.isArray(value) ? value.filter(isObject) : []);
const seconds = (ms) => (number(ms) === null ? null : `${Math.round(ms / 1000)} s`);
const two = (n) => String(n).padStart(2, '0');

// `label value` when the value is there, else nothing (so that a missing field is left out, never written as null).
const part = (label, value, unit = '') => (value === null || value === undefined ? null : `${label} ${value}${unit}`);
const joined = (parts, separator = '; ') => parts.filter(Boolean).join(separator);

/** A time in milliseconds since 1970 as local time and as UTC, `2026-11-17 14:05:09 (2026-11-17 14:05:09 UTC)`. */
export function stamp(at) {
  if (number(at) === null || Number.isNaN(new Date(at).getTime())) return 'unknown';
  const d = new Date(at);
  const local = `${d.getFullYear()}-${two(d.getMonth() + 1)}-${two(d.getDate())} `
    + `${two(d.getHours())}:${two(d.getMinutes())}:${two(d.getSeconds())}`;
  return `${local} (${d.toISOString().slice(0, 19).replace('T', ' ')} UTC)`;
}

function headerLines(run) {
  const film = number(run.film);
  return [
    joined([part('Film', film), text(run.title, 120)], ' ') || 'Film unknown',
    `Site: ${text(run.site, 120) ?? 'unknown'}`,
    joined([
      `Began ${stamp(run.beganAt)}`, part('played', seconds(run.runMs), ' from the film\'s opening'),
      `ended: ${text(run.ended, 40) ?? 'unknown'}`,
    ], ', '),
  ];
}

// The time on each rung, from `rungMs` ({ id: ms }): the entries whose value is a number, at most a dozen.
function rungsText(rungMs) {
  if (!isObject(rungMs)) return null;
  const entries = Object.entries(rungMs).filter(([, ms]) => number(ms) !== null).slice(0, MAX_RUNGS);
  return entries.length === 0 ? null : entries.map(([id, ms]) => `${printable(id, 20)} ${seconds(ms)}`).join(', ');
}

// What a reader wants of the last viewing's latest record: time watched, stalls, start-up, steps, errors, where.
function figureLines(record) {
  const fellBack = typeof record.fellBack === 'boolean' ? (record.fellBack ? 'yes' : 'no') : null;
  const lines = [
    joined([
      part('time watched', seconds(record.watchedMs)),
      joined([part('stalls', number(record.stallCount)), part('time stalled', seconds(record.stallMs)),
        part('longest', seconds(record.longestStallMs))], ', '),
      part('start-up', number(record.startupMs), ' ms'),
    ]),
    joined([
      joined([part('steps down', number(record.stepsDown)), part('steps up', number(record.stepsUp))], ', '),
      part('reconnects', number(record.reconnectCount)),
      joined([part('network errors', number(record.netErrors)), part('timeouts', number(record.timeouts))], ', '),
      part('fell back to direct play:', fellBack),
    ]),
    joined([
      part('engine', text(record.engine, 30)), part('environment', text(record.environment, 30)),
      part('operator', text(record.operator, 60)),
    ]),
    part('time on each rung:', rungsText(record.rungMs)),
  ];
  return lines.filter(Boolean).map((line) => `  ${line}`);
}

function recordLines(health) {
  const viewings = objects(health.viewings);
  const sent = number(health.sent);
  const notRead = number(health.notRead);
  const counts = joined([
    sent === null ? null : `${sent} sent`, notRead === null ? null : `${notRead} could not be read`,
    `${viewings.length} ${viewings.length === 1 ? 'viewing' : 'viewings'}`,
  ], ', ');
  const lines = [`Playback records: ${counts}`];
  if (viewings.length === 0) return [...lines, 'No playback record could be read: there are no figures of one.'];
  const last = viewings.at(-1);
  const record = isObject(last.latest) ? last.latest : {};
  return [...lines, `Latest record of the last viewing (${number(last.count) ?? '?'} sent):`, ...figureLines(record)];
}

function sampleLines(samples) {
  const all = objects(samples);
  if (all.length === 0) return ['Samples: none'];
  const times = all.map((sample) => number(sample.at)).filter((at) => at !== null);
  let longest = null;
  for (let i = 1; i < times.length; i += 1) longest = Math.max(longest ?? 0, times[i] - times[i - 1]);
  const direct = all.filter((sample) => sample.direct === true).length;
  const lines = [joined([
    `Samples: ${all.length}`, part('longest gap', seconds(longest)), `${direct} showed direct play`,
  ], ', ')];
  const seen = all.filter((sample) => number(sample.t) !== null);
  if (seen.length > 0) {
    const at = (sample) => `${sample.t} s at +${seconds(sample.sinceMs) ?? '?'}`;
    lines.push(`Playhead: ${at(seen[0])}, then ${at(seen.at(-1))}`);
  }
  return lines;
}

function heldLines(held) {
  const entries = objects(held.held);
  const lines = [entries.length === 0 ? 'Held back: nothing' : 'Held back:'];
  for (const entry of entries.slice(0, MAX_HELD_LINES)) {
    lines.push(`  ${text(entry.method, 16) ?? '?'} ${text(entry.shape, 200) ?? '?'}: ${number(entry.count) ?? '?'}`);
  }
  if (entries.length > MAX_HELD_LINES) lines.push(`  and ${entries.length - MAX_HELD_LINES} more kinds`);
  lines.push(joined([
    part('Guard: failed', number(held.failed) ?? 0), part('rejected sends', number(held.rejected) ?? 0),
    part('entries dropped', number(held.heldDropped) ?? 0),
  ], ', '));
  return lines;
}

function pressLines(presses) {
  const source = isObject(presses) ? presses : {};
  const place = text(source.place, 30) ?? 'not pressed';
  const retries = number(source.retries) ?? 0;
  return [`Presses: place card ${place}, "Try again" ${retries} ${retries === 1 ? 'time' : 'times'}`];
}

function troubleLines({ pageErrors, warnings }) {
  const errors = Array.isArray(pageErrors) ? pageErrors.filter((line) => typeof line === 'string') : [];
  const lines = [`Page errors: ${errors.length}`];
  for (const line of errors.slice(0, MAX_ERROR_LINES)) lines.push(`  ${line}`);
  for (const warning of Array.isArray(warnings) ? warnings : []) {
    if (typeof warning === 'string') lines.push(`WARNING: ${warning}`);
  }
  return lines;
}

/**
 * The summary as printable lines (none longer than 300 characters), from `{ samples, health, held }` as the run keeps
 * them: the film, the site, when it began and how long it ran, how it ended; the latest record of the last viewing;
 * how many records were sent and could not be read; the samples; the presses; what was held back; the first ten page
 * errors; the warnings. Anything missing or of the wrong type is left out, never guessed.
 */
export function summarise(results) {
  const source = isObject(results) ? results : {};
  const held = isObject(source.held) ? source.held : {};
  const lines = [
    ...headerLines(isObject(held.run) ? held.run : {}),
    ...recordLines(isObject(source.health) ? source.health : {}),
    ...sampleLines(source.samples),
    ...pressLines(held.presses),
    ...heldLines(held),
    ...troubleLines(held),
  ];
  return lines.map((line) => printable(line, LINE_LIMIT));
}
