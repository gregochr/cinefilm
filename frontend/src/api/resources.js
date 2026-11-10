import { get, put, post, del } from './client.js';

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
  plex: (options) => get('/api/admin/plex', options),
  startPlexImport: () => post('/api/admin/plex/import'),
  transcoding: (options) => get('/api/admin/transcoding', options),
  stopTranscodingSession: (id) => del(`/api/admin/transcoding/sessions/${id}`),
};

export const mediaFilesApi = {
  /** The WebVTT of one file's embedded subtitle stream (`index` is the stream index in the file). */
  subtitleUrl: (fileId, index) => `/api/media-files/${fileId}/subtitles/${index}.vtt`,
};
