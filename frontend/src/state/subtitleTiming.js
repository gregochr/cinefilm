// Pure rules of the player's subtitle timing (8b-1). No JSX. The step, the limits and the readout are Admin's own
// (`NUDGES`, `nudgedOffset`, `formatOffset` in `subtitleAdmin.js`); the server rules (±10 minutes) are not repeated.
import { ApiError } from '../api/client.js';
import { NUDGES, formatOffset, nudgedOffset, subtitleProblem } from './subtitleAdmin.js';
import { subtitleTiming } from './subtitleFixCopy.js';

/** Thrown when the rows the film showed are not all in the list any more (another tab changed or removed them). */
export class TimingChanged extends Error {
  constructor() {
    super('The shown subtitle changed');
    this.name = 'TimingChanged';
  }
}

/** Thrown when none of the rows the film showed is in the list. */
export class TimingLookupFailed extends Error {
  constructor() {
    super('No subtitle file to change');
    this.name = 'TimingLookupFailed';
  }
}

const stepOf = (key) => NUDGES.find((n) => n.key === key);

/** One "Earlier" / "Later" press: Admin's finer nudge (0.25 s), with Admin's own label and spoken name for it. */
export const EARLIER = stepOf('earlier-025');
export const LATER = stepOf('later-025');

/** The listed rows for the ids the film showed, in the film's order; missing ones are left out. */
export function timingRows(list, ids) {
  return (ids ?? []).map((id) => list.find((row) => row.id === id)).filter(Boolean);
}

/** The new absolute offset for each row after a press: each moves by `delta` (`null`: back to 0), within the limits. */
export function nextOffsets(rows, delta) {
  return rows.map((row) => ({
    id: row.id,
    offsetMs: delta === null ? 0 : nudgedOffset(row.offsetMs, delta),
    was: row.offsetMs,
  }));
}

/** The readout for the offsets of the shown rows: one offset, or that the parts differ. Null while not known. */
export function timingReadout(offsets) {
  if (!offsets || offsets.length === 0) return null;
  const same = offsets.every((o) => o === offsets[0]);
  return same ? formatOffset(offsets[0]) : subtitleTiming.differs;
}

/** Whether every shown row is already at 0 (nothing to reset). Unknown counts as not in sync. */
export function inSync(offsets) {
  return Boolean(offsets) && offsets.length > 0 && offsets.every((o) => o === 0);
}

/**
 * The cache-buster for the subtitle `<track>` URL after a change, from the server's own answers (`SidecarDto.version`
 * is when the row last changed, `offsetMs` its timing): it differs for every change, and the server ignores it.
 */
export function trackToken(answers) {
  return answers.map((a) => `${a.version}_${a.offsetMs}`).join('-');
}

/** The `v` of a subtitle track's address: the film's own version, then the token of any change made while watching. */
export function trackVersion(version, token) {
  return token ? `${version}.${token}` : version;
}

/** What the player says when a change fails: the server's own words for a refusal, else one plain line. */
export function timingProblem(failure) {
  if (failure instanceof ApiError && failure.status === 400 && failure.body?.error) {
    return subtitleProblem(failure);
  }
  if (failure instanceof TimingLookupFailed) return subtitleTiming.lookupFailed;
  if (failure instanceof TimingChanged) return subtitleTiming.changed;
  return subtitleTiming.failed;
}
