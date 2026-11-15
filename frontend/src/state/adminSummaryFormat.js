// The words and numbers Admin draws from `GET /api/admin/summary`: the rail counts, the To do heading count and the
// three status cards. Pure, no JSX: the Apple TV and native clients reuse it. The counts come from the server as
// they are; nothing here recomputes a backend rule (it only adds the four To do figures the server sends apart).
import { rungName } from '../player/ladder.js';
import { railNames, statusStrip, todoCount } from './adminCopy.js';
import { timeAgo } from './relativeTime.js';
import { describeDevice } from './userAgent.js';

const num = (value) => (Number.isFinite(value) ? value : 0);

/** The To do total: the four groups' figures added up (the rail and the heading say the same number). */
export function todoTotal(summary) {
  const { pickFilm, severalFiles, fullMissing, forcedQuestion } = summary.todo;
  return num(pickFilm) + num(severalFiles) + num(fullMissing) + num(forcedQuestion);
}

/** The muted line beside "To do": "{n} things need you", "1 thing needs you", null at 0 (or before the summary). */
export function todoHeadingCount(summary) {
  if (!summary) return null;
  const total = todoTotal(summary);
  if (total === 0) return null;
  return total === 1 ? todoCount.singular : todoCount.plural(total);
}

/**
 * What each rail link shows, by section key: `{ badge, label }` (the visible pill and the link's accessible name), or
 * nothing for a section with no count. No summary (loading, or an error) means no counts at all; a count of 0 is not
 * shown, and Playback shows "{n} live" only while something is live.
 */
export function railCounts(summary) {
  if (!summary) return {};
  const counts = {};
  const todo = todoTotal(summary);
  if (todo > 0) counts.todo = { badge: String(todo), label: railNames.todo(todo) };
  const unresolved = num(summary.subtitlesUnresolved);
  if (unresolved > 0) {
    counts.subtitles = { badge: String(unresolved), label: railNames.subtitles(unresolved) };
  }
  const live = num(summary.live);
  if (live > 0) counts.playback = { badge: railNames.live(live), label: railNames.playback(live) };
  return counts;
}

function filmsText(films) {
  const n = num(films);
  return `${n.toLocaleString('en-GB')} ${n === 1 ? 'film' : 'films'}`;
}

/**
 * The Library card: "{n} films · scanned {ago}", "{n} films · not scanned yet" before the first scan, and
 * "{n} films · last scan failed {ago}" (with `failed: true`, so the card is drawn in the error colour) when the last
 * scan finished with an error. `now` is epoch ms.
 */
export function libraryCard(library, now) {
  const films = filmsText(library.films);
  const finished = library.lastScanFinishedAt;
  if (library.lastScanError) {
    const text = finished
      ? statusStrip.scanFailed(films, timeAgo(finished, now))
      : `${films} · last scan failed`;
    return { text, failed: true };
  }
  if (!finished) return { text: statusStrip.neverScanned(films), failed: false };
  return { text: statusStrip.scanned(films, timeAgo(finished, now)), failed: false };
}

/** The Playback card: "Last 30 days: {smooth} of {n} film sessions played smoothly", or the no-films line at 0. */
export function playbackCard(playback) {
  if (!playback.films) return statusStrip.playbackNone;
  return statusStrip.playbackSome(playback.smooth, playback.films);
}

/**
 * The Playing now card: "{film} · {device} · {quality}" for the newest stream, "+{k} more" appended for the others,
 * "Nothing playing" for none. A stream whose report could not be read has no device or quality: the device falls
 * back to "Unknown device" and the quality is left out.
 */
export function playingNowCard(playingNow) {
  if (!playingNow || playingNow.length === 0) return statusStrip.nothingPlaying;
  const [first, ...others] = playingNow;
  const parts = [
    first.filmTitle,
    describeDevice(first.device),
    first.quality ? rungName(first.quality) : null,
  ];
  if (others.length > 0) parts.push(statusStrip.moreStreams(others.length));
  return parts.filter(Boolean).join(' · ');
}
