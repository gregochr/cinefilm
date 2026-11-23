import { serverText } from '../api/client.js';
import { journeyImport as copy } from './journeysCopy.js';

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
