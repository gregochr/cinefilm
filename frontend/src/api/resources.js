import { get, getWithEtag, put, post, del } from './client.js';

export const homeApi = {
  get: (options) => get('/api/home', options),
};

export function buildFilmsQuery(params = {}) {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') search.set(key, String(value));
  });
  const text = search.toString();
  return text ? `?${text}` : '';
}

export const filmsApi = {
  list: (params, options) => get(`/api/films${buildFilmsQuery(params)}`, options),
  get: (id, options) => get(`/api/films/${id}`, options),
  savePreferences: (id, preferences) => put(`/api/films/${id}/preferences`, preferences),
};

/** The compact search index. Resolves `{ notModified: true }` or `{ data, etag }` (see `getWithEtag`). */
export const searchApi = {
  index: ({ etag, signal } = {}) => getWithEtag('/api/search-index', etag, { signal }),
};

export const peopleApi = {
  get: (id, options) => get(`/api/people/${id}`, options),
};

export const collectionsApi = {
  list: (options) => get('/api/collections', options),
  get: (id, options) => get(`/api/collections/${id}`, options),
};

export const editionsApi = {
  streamUrl: (id) => `/api/editions/${id}/stream`,
  /**
   * The WebVTT of an embedded subtitle stream of the edition on the edition's own timeline (`index` is the stream
   * index in part 1, the logical stream). A film split across several files has one such track, with every part's
   * cues in place.
   */
  subtitleUrl: (editionId, index) => `/api/editions/${editionId}/subtitles/${index}.vtt`,
  /**
   * The JPEG of the frame at `atMs` (edition time). Floored to whole seconds, as the server does, so the browser
   * cache key matches the server's cache.
   */
  stillUrl: (id, atMs) =>
    `/api/editions/${id}/still?atMs=${Math.floor(Math.max(0, atMs) / 1000) * 1000}`,
  reportProgress: (id, positionMs, options) =>
    put(`/api/editions/${id}/progress`, { positionMs }, options),
  setWatched: (id, watched) => post(`/api/editions/${id}/watched`, { watched }),
  /** Remove from (or put back into) Continue watching; keeps the resume position. */
  setHiddenFromContinueWatching: (id, hidden) =>
    put(`/api/editions/${id}/continue-watching`, { hidden }),
  /** Undo: restore a `previous` snapshot returned by setWatched. */
  restoreState: (id, snapshot) => put(`/api/editions/${id}/playback-state`, snapshot),
};

/** HLS playback sessions (adaptive streaming). `remove` is sent with keepalive when the page is closing. */
export const sessionsApi = {
  create: (editionId, body, options) => post(`/api/editions/${editionId}/sessions`, body, options),
  remove: (sessionId, options) => del(`/api/sessions/${sessionId}`, options),
};

export const sessionApi = {
  status: () => get('/api/session'),
  signIn: (password) => post('/api/session', { password }),
  signOut: () => del('/api/session'),
};

export const adminApi = {
  scan: (options) => get('/api/admin/scan', options),
  startScan: () => post('/api/admin/scan'),
  startRatingsRefresh: () => post('/api/admin/ratings/refresh'),
  attention: (options) => get('/api/admin/attention', options),
  groupingReport: (options) => get('/api/admin/grouping-report', options),
  /** TMDB results (at most 8, with details) for the Fix match panel. `year` is optional. */
  matchSearch: (filmId, { query, year }, options) => {
    const params = new URLSearchParams({ query });
    if (year) params.set('year', String(year));
    return get(`/api/admin/films/${filmId}/match?${params}`, options);
  },
  applyMatch: (filmId, tmdbId) => post(`/api/admin/films/${filmId}/match`, { tmdbId }),
  /** A film's files, editions and the suggested arrangement, for the Edit editions panel. */
  editions: (filmId, options) => get(`/api/admin/films/${filmId}/editions`, options),
  /** Saves the arranged editions. A 400 carries `{ error, errors: [{ field, message }] }`; a 409 means a scan. */
  saveEditions: (filmId, request) => put(`/api/admin/films/${filmId}/editions`, request),
  plex: (options) => get('/api/admin/plex', options),
  startPlexImport: () => post('/api/admin/plex/import'),
  transcoding: (options) => get('/api/admin/transcoding', options),
  stopTranscodingSession: (id) => del(`/api/admin/transcoding/sessions/${id}`),
  playbackHealth: (options) => get('/api/admin/playback-health', options),
  /** The whole report of one playback as JSON: a link, not a fetch. */
  playbackHealthUrl: (playbackId) => `/api/admin/playback-health/${playbackId}`,
};

export const mediaFilesApi = {
  /** The WebVTT of one file's embedded subtitle stream (`index` is the stream index in the file). */
  subtitleUrl: (fileId, index) => `/api/media-files/${fileId}/subtitles/${index}.vtt`,
};
