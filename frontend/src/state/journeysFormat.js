import { serverText } from '../api/client.js';
import { journeyDelete, journeyImport as copy, journeyList } from './journeysCopy.js';

const DAY_AND_TIME = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Europe/London',
  day: 'numeric',
  month: 'numeric',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
});
// Written out, so that September is "Sep" whatever the browser's locale data says ("Sept" in some).
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** The parts of an instant by Britain's clock: `{ date: '4 Mar 2030', time: '22:00' }`. */
function londonParts(ms) {
  const p = Object.fromEntries(
    DAY_AND_TIME.formatToParts(new Date(ms)).map((x) => [x.type, x.value]),
  );
  return {
    date: `${Number(p.day)} ${MONTHS[Number(p.month) - 1]} ${p.year}`,
    time: `${p.hour}:${p.minute}`,
  };
}

/** "4 Mar 2030, 22:00 to 23:15", or with the second day written out when the two ends are on different days. */
export function loggedRange(fromMs, toMs) {
  const from = londonParts(fromMs);
  const to = londonParts(toMs);
  const end = to.date === from.date ? to.time : `${to.date}, ${to.time}`;
  return `${from.date}, ${from.time} to ${end}`;
}

/** A calendar date ("2030-03-04") as "4 Mar 2030". It is a day, not a moment: no time zone is applied. */
function calendarDate(isoDate) {
  const [year, month, day] = isoDate.split('-').map(Number);
  return `${day} ${MONTHS[month - 1]} ${year}`;
}

/** The row's title: when the journey was logged, or that it has no samples. */
function journeyTitle(journey) {
  const { from, to } = journey;
  return from === null ? journeyList.noSamples : loggedRange(Date.parse(from), Date.parse(to));
}

/**
 * The lines of a stored journey's row; `all` is every journey in the list (a run's other segments are told by their
 * `runStartedMs`). `second` is the parts that have something to say, joined with " · "; `fourth` is null when neither
 * the file's name nor the laptop's model is known.
 */
export function journeyLines(journey, all) {
  const { device } = journey;
  const hasParts = all.some((j) => j.id !== journey.id && j.runStartedMs === journey.runStartedMs);
  const second = [
    journey.trainUnit ?? journeyList.unitNotNamed,
    hasParts ? journeyList.part(journey.segment + 1) : null,
    journeyList.classes[journey.travelClass],
    journeyList.rounds(journey.samples),
    journeyList.viewings(journey.linked),
  ]
    .filter(Boolean)
    .join(' · ');
  const fourth = [journey.sourceFile, device?.model].filter(Boolean).join(' · ');
  return {
    title: journeyTitle(journey),
    second,
    third: journey.train
      ? journeyList.train(journey.train.headcode, calendarDate(journey.train.date))
      : journeyList.noTrain,
    fourth: fourth || null,
  };
}

/** "Ready to import: 363 rounds". */
export function readyLine(summary) {
  return copy.ready(summary.rounds);
}

/** What will be sent, as terms and values in the order shown; `request` is what `readJourneyLog` made. */
export function sentRows(summary, request) {
  const rows = [
    { term: copy.terms.fileName, value: request.sourceFile ?? copy.fileNotSent },
    { term: copy.terms.rounds, value: summary.rounds.toLocaleString('en-GB') },
    { term: copy.terms.logged, value: loggedRange(summary.from, summary.to) },
    {
      term: copy.terms.units,
      value: summary.units.length > 0 ? summary.units.join(', ') : copy.noUnits,
    },
    { term: copy.terms.modems, value: String(summary.modems.length) },
    { term: copy.terms.version, value: String(summary.loggerVersion) },
    { term: copy.terms.laptop, value: summary.hasDevice ? copy.laptopKnown : copy.laptopUnknown },
    { term: copy.terms.travelClass, value: copy.classes[summary.travelClass] ?? copy.classUnknown },
    {
      term: copy.terms.leftOut,
      value:
        summary.leftOut.length > 0
          ? summary.leftOut.map((l) => `${l.field} (${l.count})`).join(', ')
          : copy.nothingLeftOut,
    },
  ];
  if (summary.unreadableLines > 0) {
    rows.push({ term: copy.terms.unreadable, value: String(summary.unreadableLines) });
  }
  return rows;
}

/** The sentence for a `readJourneyLog` problem. */
export function problemText(problem) {
  return copy.problems[problem];
}

/**
 * How a failed import reads: `kind` is `signin` (a 401: the sign-in card handles it, no text) or `alert`, with the
 * `text` to show. A 409 is told apart by the server's own text.
 */
export function importFailure(error) {
  if (error?.status === 401) return { kind: 'signin', text: null };
  if (error?.status === 400) return { kind: 'alert', text: copy.refused };
  if (error?.status === 409) {
    return {
      kind: 'alert',
      text:
        serverText(error) === 'Already imported' ? copy.alreadyImported : copy.otherImportRunning,
    };
  }
  return { kind: 'alert', text: copy.failed };
}

/** The toast for the 201: "Imported 1 journey: 363 rounds, 2 viewings linked", the counts summed over the journeys. */
export function importToast(response) {
  const { journeys } = response;
  const rounds = journeys.reduce((sum, j) => sum + j.samples, 0);
  const linked = journeys.reduce((sum, j) => sum + j.linked, 0);
  return copy.toast(journeys.length, rounds, linked);
}

/** What a screen reader says for a row's "Delete…": which journey it is, by the row's title. */
export function deleteSpoken(journey) {
  return journey.from === null
    ? journeyDelete.spokenNoSamples
    : journeyDelete.spoken(journeyTitle(journey));
}

/** The confirmation's sentence: it counts the rounds that go with the journey, unless it has none. */
export function deleteSentence(journey) {
  return journey.from === null
    ? journeyDelete.sentenceNoSamples
    : journeyDelete.sentence(journey.samples);
}

/**
 * How a failed delete reads: `kind` is `signin` (a 401: the sign-in card handles it, no text), `gone` (a 404: the
 * journey was already deleted, told in a toast, no text) or `alert`, with the `text` for the confirmation.
 */
export function deleteFailure(error) {
  if (error?.status === 401) return { kind: 'signin', text: null };
  if (error?.status === 404) return { kind: 'gone', text: null };
  if (error?.status === 409) return { kind: 'alert', text: journeyDelete.conflict };
  return { kind: 'alert', text: journeyDelete.failed };
}

/**
 * The journey to focus once `id` is gone: the one that followed it in the list as it was (`before`), else the one
 * before it, each only if it is still in the list as it is now (`now`). Null when neither is, or `id` was not in `before`.
 */
export function rowAfterDelete(before, id, now) {
  const at = before.findIndex((journey) => journey.id === id);
  if (at < 0) return null;
  const present = new Set(now.map((journey) => journey.id));
  const candidates = [before[at + 1]?.id, before[at - 1]?.id];
  return candidates.find((candidate) => candidate !== undefined && present.has(candidate)) ?? null;
}
