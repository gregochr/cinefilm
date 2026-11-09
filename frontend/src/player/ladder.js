// The quality ladder as the client sees it: rung ids, how a level or a video height maps to a rung, which
// remembered quality is usable, and the toast words for an automatic change.

export const ORIGINAL = 'original';
export const AUTO = 'auto';
/** Rungs the handover calls slow: 480p and everything below it. */
const SLOW_RUNGS = new Set(['480p', '360p', '240p']);

export const rungName = (id) => (id === AUTO ? 'Auto' : id === ORIGINAL ? 'Original' : id);

export const isSlowRung = (id) => SLOW_RUNGS.has(id);

const availableIn = (qualities, id) => qualities.some((q) => q.id === id && q.available);

/**
 * The quality to start with. A remembered quality the edition does not list, or lists as unavailable, falls
 * back to Auto; with Auto itself unavailable (worker offline) only the original can play.
 */
export function resolveQuality(saved, qualities = []) {
  if (saved && saved !== AUTO && availableIn(qualities, saved)) return saved;
  return workerOffline(qualities) ? ORIGINAL : AUTO;
}

/** True when nothing but the original can play: Auto is listed but not available (the worker is offline). */
export function workerOffline(qualities = []) {
  return qualities.some((q) => q.id === AUTO) && !availableIn(qualities, AUTO);
}

/** The rung whose height is `height`. Heights are unique per film, so at most one matches. */
export function rungForHeight(renditions, height) {
  return renditions.find((r) => r.height === height)?.id ?? null;
}

/** The rung an hls.js level is: by height, else by exact bitrate. Null when neither matches. */
export function rungForLevel(renditions, level) {
  const byHeight = level.height ? rungForHeight(renditions, level.height) : null;
  if (byHeight) return byHeight;
  return renditions.find((r) => r.bitrate === level.bitrate)?.id ?? null;
}

/** The highest rung of a session (the tallest one), or the original when it has no renditions. */
export function topRung(renditions) {
  if (!renditions?.length) return ORIGINAL;
  return renditions.reduce((top, r) => (r.height > top.height ? r : top)).id;
}

/**
 * The toast for an automatic change from rung `from` to rung `to`, or null when nothing changed or either
 * rung is unknown. Down: "Switching to 720p for smoother playback". Up: "Back to Original" (decision 3).
 */
export function levelToast(renditions, from, to) {
  if (!from || !to || from === to) return null;
  const before = renditions.find((r) => r.id === from);
  const after = renditions.find((r) => r.id === to);
  if (!before || !after) return null;
  return after.height < before.height
    ? `Switching to ${rungName(to)} for smoother playback`
    : `Back to ${rungName(to)}`;
}
