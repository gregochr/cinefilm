// Display and rule helpers for the Admin "Subtitles" panel. No JSX: the Apple TV and native clients reuse them.
import { ApiError } from '../api/client.js';
import { formatClock } from '../player/time.js';
import { formatScanTime, titleWithYear } from './scanFormat.js';

/** The server allows a timing offset of this many milliseconds either way. */
export const MAX_OFFSET_MS = 600000;

/** "Play from here" opens the player this long before a cue, so the viewer hears the line arrive. */
export const PLAY_LEAD_MS = 3000;

/** The nudge buttons, in the order they are shown. */
export const NUDGES = [
  { key: 'earlier-1', label: '−1 s', delta: -1000, spoken: 'Show lines 1 second earlier' },
  { key: 'earlier-025', label: '−0.25 s', delta: -250, spoken: 'Show lines 0.25 seconds earlier' },
  { key: 'later-025', label: '+0.25 s', delta: 250, spoken: 'Show lines 0.25 seconds later' },
  { key: 'later-1', label: '+1 s', delta: 1000, spoken: 'Show lines 1 second later' },
];

/** The offset after a nudge, kept within what the server allows. */
export function nudgedOffset(current, delta) {
  return Math.max(-MAX_OFFSET_MS, Math.min(MAX_OFFSET_MS, current + delta));
}

/** "+0.25 s", "−1.25 s", "+1 s"; "No timing change" for 0. */
export function formatOffset(ms) {
  if (ms === 0) return 'No timing change';
  const seconds = String(Math.abs(ms) / 1000);
  return `${ms > 0 ? '+' : '−'}${seconds} s`;
}

/** Where "Play from here" starts for a cue: its start minus the lead, never before 0. */
export function playFromHereMs(cue) {
  return Math.max(0, cue.startMs - PLAY_LEAD_MS);
}

/** The player address for a file's film and edition at an edition time. */
export function playerPath(item, atMs) {
  return `/films/${item.filmId}/play?edition=${item.editionId}&at=${atMs}`;
}

/** "00:36:36", the cue's start on the edition's timeline. */
export function cueTime(cue) {
  return formatClock(cue.startMs);
}

/** The file name without its folders. */
export function fileName(path) {
  return path.slice(path.lastIndexOf('/') + 1);
}

const GROUPS = [
  { key: 'unmatched', title: 'Unmatched' },
  { key: 'conflicts', title: 'Conflicts' },
  { key: 'missing', title: 'Missing' },
  { key: 'matched', title: 'Matched' },
];

/** Which group a file is listed under: Missing first (the file is not there), then its state. */
export function groupKey(item) {
  if (item.presence === 'MISSING') return 'missing';
  if (item.state === 'unmatched') return 'unmatched';
  if (item.state === 'conflict') return 'conflicts';
  return 'matched';
}

/** The files grouped as Unmatched, Conflicts, Missing, Matched; a group with no file is left out. */
export function sidecarGroups(items) {
  return GROUPS.map((group) => ({
    ...group,
    items: items.filter((item) => groupKey(item) === group.key),
  })).filter((group) => group.items.length > 0);
}

/** Where a file belongs: "The Two Towers · Extended Edition · whole film", "… · part 2", or that it is unattached. */
export function placement(item) {
  if (item.filmId == null) return 'Not attached to a film';
  const where = item.whole ? 'whole film' : item.parts > 1 ? `part ${item.part}` : null;
  return [item.filmTitle, item.editionName, where].filter(Boolean).join(' · ');
}

/** "12 cues · No problems found", "1 cue · 3 blocks skipped". */
export function reportLine(item) {
  return `${item.cueCount} ${item.cueCount === 1 ? 'cue' : 'cues'} · ${item.parseReport}`;
}

/** The warnings shown for a file, in plain words. */
export function warningsFor(item) {
  const warnings = [];
  if (item.presence === 'MISSING') warnings.push('The last scan did not find this file.');
  if (item.state === 'conflict') {
    warnings.push(
      'Another file competes for the same place, so neither plays. Attach one of them elsewhere.',
    );
  }
  if (item.endsAfterRuntime) warnings.push('Ends after the film: wrong film or cut?');
  return warnings;
}

/** The radio choices for where in an edition a file goes: the whole film, and each part of a split one. */
export function partOptions(edition) {
  const whole = { value: null, label: 'Whole film' };
  if (!(edition.parts > 1)) return [whole];
  const parts = Array.from({ length: edition.parts }, (_, i) => ({
    value: i + 1,
    label: `Part ${i + 1}`,
  }));
  return [whole, ...parts];
}

/** "Extended Edition", "Extended Edition · 2 files". */
export function editionLabel(edition) {
  return edition.parts > 1 ? `${edition.name} · ${edition.parts} files` : edition.name;
}

/** "Heat (1995)". */
export function filmLabel(film) {
  return titleWithYear(film.title, film.year);
}

/** What the panel says when an action fails. The server's own message is shown for a rejected request. */
export function subtitleProblem(failure) {
  const status = failure instanceof ApiError ? failure.status : 0;
  if (status === 400)
    return failure.body?.error ?? 'That was not accepted. Check it and try again.';
  if (status === 404) return 'That is no longer there. Reload the list and look again.';
  return 'Something went wrong. Try again.';
}

/** Whether playback serves this file (the server says): only those are compared with OpenSubtitles. */
export function isServed(item) {
  return item.served === true;
}

/**
 * What a file's check says, in words: `{ kind, label, lines, reference }`, or null for a file that is not served
 * (nothing to compare). `kind` is `agrees`, `check` or `unchecked`; `lines` are the server's reasons (a timing hint
 * for AGREES) or the quiet explanation (unchecked); `reference` is the muted "Compared with ... · checked ..." line.
 */
export function checkSummary(item) {
  const check = item.check;
  if (!check) {
    if (!isServed(item)) return null;
    return {
      kind: 'unchecked',
      label: 'Unchecked',
      lines: ['No OpenSubtitles file to compare with yet'],
      reference: null,
    };
  }
  const reasons = check.reasons ?? [];
  const when = check.checkedAt ? `checked ${formatScanTime(check.checkedAt)}` : null;
  const reference =
    [check.referenceRelease ? `Compared with ${check.referenceRelease}` : null, when]
      .filter(Boolean)
      .join(' · ') || null;
  if (check.verdict === 'CHECK') {
    return { kind: 'check', label: 'Needs checking', lines: reasons, reference };
  }
  // The server words the reasons, a timing hint included ("OpenSubtitles' file is 1.2 s later"); nothing is derived here.
  return { kind: 'agrees', label: 'Agrees with OpenSubtitles', lines: reasons, reference };
}

/** The filter chips on the file list, in order; `matches` says which files a chip lists. */
export const FILE_FILTERS = [
  { key: 'all', label: 'All', matches: () => true },
  { key: 'check', label: 'Needs checking', matches: (item) => item.check?.verdict === 'CHECK' },
  { key: 'unchecked', label: 'Unchecked', matches: (item) => !item.check && isServed(item) },
];

/** The files a chip lists (all of them for an unknown key). */
export function filterSidecars(items, key) {
  const filter = FILE_FILTERS.find((f) => f.key === key) ?? FILE_FILTERS[0];
  return items.filter(filter.matches);
}

/** How many files each chip lists, by chip key. */
export function fileFilterCounts(items) {
  return Object.fromEntries(FILE_FILTERS.map((f) => [f.key, items.filter(f.matches).length]));
}
