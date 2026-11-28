// The train look-ahead's map, from the server (decision 2 of the train look-ahead plan): per track, points and
// stretches, and nothing that names a journey. Read defensively: a malformed point or stretch is dropped, a track
// left with fewer than 2 points is dropped, and any failure gives []. The map lives in the caller's memory only
// (decision 3): nothing here stores it or sends it anywhere. No JSX.
import { journeysApi } from './resources.js';

const finite = (value) => typeof value === 'number' && Number.isFinite(value);

function readPoint(raw) {
  if (raw === null || typeof raw !== 'object') return null;
  const { latitude, longitude, courseDeg, t } = raw;
  if (!finite(latitude) || latitude < -90 || latitude > 90) return null;
  if (!finite(longitude) || longitude < -180 || longitude > 180) return null;
  if (!finite(t) || t < 0) return null;
  if (courseDeg !== null && !finite(courseDeg)) return null;
  return { latitude, longitude, courseDeg, t };
}

function readStretch(raw) {
  if (raw === null || typeof raw !== 'object') return null;
  const { from, to, need } = raw;
  if (!finite(from) || !finite(to) || !finite(need)) return null;
  if (from < 0 || to < from || need <= 0) return null;
  return { from, to, need };
}

const readAll = (list, read) =>
  Array.isArray(list) ? list.map((item) => read(item)).filter(Boolean) : [];

function readTrack(raw) {
  if (raw === null || typeof raw !== 'object') return null;
  const points = readAll(raw.points, readPoint).sort((a, b) => a.t - b.t);
  if (points.length < 2) return null;
  return { points, stretches: readAll(raw.stretches, readStretch) };
}

/** The tracks of a link-map answer, or [] when it is not one. */
export function readLinkMap(answer) {
  return readAll(answer?.tracks, readTrack);
}

/** Asks the server for the map: its tracks, or [] on any failure (a 401 included). Never rejects. */
export async function loadLinkMap({ signal } = {}) {
  try {
    return readLinkMap(await journeysApi.linkMap({ signal }));
  } catch {
    return [];
  }
}
