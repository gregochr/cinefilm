import { get, getWithEtag, put, post, del } from './client.js';
import { pathSegment } from './pathSegment.js';

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
  get: (id, options) => get(`/api/films/${pathSegment(id)}`, options),
  savePreferences: (id, preferences) =>
    put(`/api/films/${pathSegment(id)}/preferences`, preferences),
};

/** The compact search index. Resolves `{ notModified: true }` or `{ data, etag }` (see `getWithEtag`). */
export const searchApi = {
  index: ({ etag, signal } = {}) => getWithEtag('/api/search-index', etag, { signal }),
};

export const peopleApi = {
  get: (id, options) => get(`/api/people/${pathSegment(id)}`, options),
};

export const collectionsApi = {
  list: (options) => get('/api/collections', options),
  get: (id, options) => get(`/api/collections/${pathSegment(id)}`, options),
};

export const editionsApi = {
  streamUrl: (id) => `/api/editions/${pathSegment(id)}/stream`,
  /**
   * The WebVTT of an embedded subtitle stream of the edition on the edition's own timeline (`index` is the stream
   * index in part 1, the logical stream). A film split across several files has one such track, with every part's
   * cues in place.
   */
  subtitleUrl: (editionId, index) =>
    `/api/editions/${pathSegment(editionId)}/subtitles/${pathSegment(index)}.vtt`,
  /**
   * The WebVTT of the edition's forced English subtitle on the edition's timeline. `version` (from the film detail's
   * `forcedSubtitle`) only busts the browser's cache after a nudge (Admin, or the player's Earlier / Later): the server ignores it.
   */
  forcedSubtitleUrl: (editionId, version) =>
    `/api/editions/${pathSegment(editionId)}/forced-subtitles.vtt${version ? `?v=${encodeURIComponent(version)}` : ''}`,
  /**
   * The WebVTT of the edition's full English subtitle on the edition's timeline. `version` (from the film detail's
   * `fullSubtitle`) busts the browser's cache when the file changes: the server ignores it.
   */
  fullSubtitleUrl: (editionId, version) =>
    `/api/editions/${pathSegment(editionId)}/full-subtitles.vtt${version ? `?v=${encodeURIComponent(version)}` : ''}`,
  /**
   * The JPEG of the frame at `atMs` (edition time). Floored to whole seconds, as the server does, so the browser
   * cache key matches the server's cache.
   */
  stillUrl: (id, atMs) =>
    `/api/editions/${pathSegment(id)}/still?atMs=${Math.floor(Math.max(0, atMs) / 1000) * 1000}`,
  reportProgress: (id, positionMs, options) =>
    put(`/api/editions/${pathSegment(id)}/progress`, { positionMs }, options),
  setWatched: (id, watched) => post(`/api/editions/${pathSegment(id)}/watched`, { watched }),
  /** Remove from (or put back into) Continue watching; keeps the resume position. */
  setHiddenFromContinueWatching: (id, hidden) =>
    put(`/api/editions/${pathSegment(id)}/continue-watching`, { hidden }),
  /** Undo: restore a `previous` snapshot returned by setWatched. */
  restoreState: (id, snapshot) => put(`/api/editions/${pathSegment(id)}/playback-state`, snapshot),
};

/** HLS playback sessions (adaptive streaming). `remove` is sent with keepalive when the page is closing. */
export const sessionsApi = {
  create: (editionId, body, options) =>
    post(`/api/editions/${pathSegment(editionId)}/sessions`, body, options),
  remove: (sessionId, options) => del(`/api/sessions/${pathSegment(sessionId)}`, options),
};

/** One viewing's health report: each send replaces the one before (the server keeps the highest `seq`). */
export const healthApi = {
  report: (playbackId, report, options) =>
    put(`/api/playback-health/${pathSegment(playbackId)}`, report, options),
};

export const sessionApi = {
  status: (options) => get('/api/session', options),
  signIn: (password) => post('/api/session', { password }),
  signOut: () => del('/api/session'),
};

export const adminApi = {
  scan: (options) => get('/api/admin/scan', options),
  startScan: () => post('/api/admin/scan'),
  startRatingsRefresh: () => post('/api/admin/ratings/refresh'),
  attention: (options) => get('/api/admin/attention', options),
  /** One page (0-based, the server's 25 rows) of the End credits list; `filter` is none, plex, owner or all. */
  credits: (filter, page, options) =>
    get(`/api/admin/credits?${new URLSearchParams({ filter, page: String(page) })}`, options),
  /** The rail and To do strip's figures, read fresh each time (the server does not cache). */
  summary: (options) => get('/api/admin/summary', options),
  groupingReport: (options) => get('/api/admin/grouping-report', options),
  /** TMDB results (at most 8, with details), the file length and the suggested pick, for Fix match. `year` is optional. */
  matchSearch: (filmId, { query, year }, options) => {
    const params = new URLSearchParams({ query });
    if (year) params.set('year', String(year));
    return get(`/api/admin/films/${pathSegment(filmId)}/match?${params}`, options);
  },
  applyMatch: (filmId, tmdbId) => post(`/api/admin/films/${pathSegment(filmId)}/match`, { tmdbId }),
  /** "Leave unmatched": the film stops being listed as needing a match (204; a 409 says a scan holds the lock). */
  leaveUnmatched: (filmId) => post(`/api/admin/films/${pathSegment(filmId)}/leave-unmatched`),
  /** The films the owner left unmatched, by title: `[{ id, title, year }]`. */
  leftUnmatched: (options) => get('/api/admin/films/left-unmatched', options),
  /** "Put back": a left-unmatched film is listed as needing a match again (204; a 409 says a scan holds the lock, or that the film is not left unmatched). */
  putBack: (filmId) => post(`/api/admin/films/${pathSegment(filmId)}/put-back`),
  /** A film's files, editions and the suggested arrangement, for the Edit editions panel. */
  editions: (filmId, options) => get(`/api/admin/films/${pathSegment(filmId)}/editions`, options),
  /**
   * Saves the arranged editions. A 400 carries `{ error, errors: [{ field, message }] }`; a 409: the library lock is
   * held (its `error` says by what).
   */
  saveEditions: (filmId, request) =>
    put(`/api/admin/films/${pathSegment(filmId)}/editions`, request),
  plex: (options) => get('/api/admin/plex', options),
  startPlexImport: () => post('/api/admin/plex/import'),
  /** Sets the owner's end-credits time on an edition (whole seconds, in ms); answers the edition's new row. */
  setCredits: (id, markerMs) => put(`/api/admin/editions/${pathSegment(id)}/credits`, { markerMs }),
  /** Clears the owner's time on an edition; answers its new row. */
  clearCredits: (id) => del(`/api/admin/editions/${pathSegment(id)}/credits`),
  /** The one-off import of Plex's credits markers: status with progress and the last report. */
  plexCredits: (options) => get('/api/admin/plex/credits', options),
  startPlexCreditsImport: () => post('/api/admin/plex/credits/import'),
  /** Every stored journey, the newest run first: `{ journeys: [...] }`. */
  journeys: (options) => get('/api/admin/journeys', options),
  /** Stores one train feed logger file, as `readJourneyLog` trimmed it; answers `{ journeys: [{ samples, linked, ... }] }`. */
  importJourney: (request) => post('/api/admin/journeys', request),
  transcoding: (options) => get('/api/admin/transcoding', options),
  // `playbackId` is the row's viewing: the server also ends that viewing's newer session (one made after the list was read).
  stopTranscodingSession: (id, playbackId) =>
    del(
      `/api/admin/transcoding/sessions/${pathSegment(id)}${playbackId ? `?playbackId=${encodeURIComponent(playbackId)}` : ''}`,
    ),
  /**
   * One page of the playback log, newest start first, filtered by the server: `{ playbacks, total }`. `params` are
   * the list route's own (`since`, `minWatchedMs`, `problems`, `page`, `size`); each one present switches the
   * filtered, paged answer on, so `page` and `size` should always be sent.
   */
  playbackLog: (params, options) =>
    get(`/api/admin/playback-health${buildFilmsQuery(params)}`, options),
  /** Every film session since `since` (an ISO instant; all when null), oldest first, with only the chart's fields. */
  playbackChart: (since, options) =>
    get(
      `/api/admin/playback-health/chart${since ? `?since=${encodeURIComponent(since)}` : ''}`,
      options,
    ),
  /** One playback's whole record, `report.minutes` included (rows of seven figures, or five in an older record). */
  playbackRecord: (playbackId, options) =>
    get(`/api/admin/playback-health/${pathSegment(playbackId)}`, options),
  /** Every forced subtitle file beside the films, with where it belongs and how it reads. */
  subtitles: (options) => get('/api/admin/subtitles', options),
  /** One file's cues on the edition's timeline, its offset applied. */
  subtitleCues: (id, options) => get(`/api/admin/subtitles/${pathSegment(id)}/cues`, options),
  /** Attaches a file to an edition: `part` is the 1-based part, or null for the whole edition. */
  attachSubtitle: (id, { editionId, part }) =>
    post(`/api/admin/subtitles/${pathSegment(id)}/attach`, { editionId, part }),
  /** Gives a hand-attached file back to matching by name. */
  detachSubtitle: (id) => post(`/api/admin/subtitles/${pathSegment(id)}/detach`),
  /** Sets the file's timing offset in milliseconds (the server allows ten minutes either way). */
  setSubtitleOffset: (id, offsetMs, options) =>
    put(`/api/admin/subtitles/${pathSegment(id)}/offset`, { offsetMs }, options),
  /** Says the file's lines are right and in sync ("Looks right"); `unverifySubtitle` undoes it. */
  verifySubtitle: (id) => post(`/api/admin/subtitles/${pathSegment(id)}/verify`),
  unverifySubtitle: (id) => del(`/api/admin/subtitles/${pathSegment(id)}/verify`),
  /** Removes an OpenSubtitles download (never the owner's own file); CineFilm will not use it for that edition again. */
  removeSubtitle: (id, options) => del(`/api/admin/subtitles/${pathSegment(id)}`, options),
  /** Every film with whether it needs forced subtitles and why: `{ films: [...], counts: {...} }`. */
  forcedNeed: (options) => get('/api/admin/forced-subtitles/films', options),
  /**
   * Records the owner's choice for a film (`'NEEDED'`, `'NOT_NEEDED'`, or null for the automatic rule) and returns
   * that film's row. A 409: the library lock is held (its `error` says by what); a 404 an unknown film.
   */
  setForcedNeed: (filmId, choice, options) =>
    put(`/api/admin/films/${pathSegment(filmId)}/forced-need`, { choice }, options),
  /**
   * The films that may need full English subtitles (not made in English, or chosen by the owner), each with its need,
   * the reason and its editions' audio: `{ films: [...], counts: {...} }`.
   */
  fullNeed: (options) => get('/api/admin/subtitles/full-need', options),
  /**
   * Records the owner's choice on full subtitles for a film (`'NEEDED'`, `'NOT_NEEDED'`, or null for the automatic
   * rule) and returns that film's row. A 409: the library lock is held (its `error` says by what); a 404 an unknown
   * film.
   */
  setFullNeed: (filmId, choice, options) =>
    put(`/api/admin/films/${pathSegment(filmId)}/full-need`, { choice }, options),
  /**
   * Searches OpenSubtitles for an edition's film (`kind` is `'full'` or `'forced'`): `{ editionId, kind, imdbId,
   * downloadsLeft, results: [{ fileId, release, fileName, fps, downloadCount, fromTrusted, hearingImpaired,
   * uploadDate }] }`, ranked for the edition. A 400 means no IMDb id (or a bad kind); a 503 an outage or no API key.
   */
  searchOpenSubtitles: (editionId, kind, options) =>
    get(
      `/api/admin/editions/${pathSegment(editionId)}/opensubtitles?${new URLSearchParams({ kind })}`,
      options,
    ),
  /**
   * Downloads one file a search of that edition and kind returned in the last 15 minutes and makes it the
   * edition's download: `{ editionId, kind, fileId, release, downloadsLeft }`. A 400 means the file was not in such
   * a search, a 409 a scan is running or the edition cannot take the file, a 429 the day's limit is used up, a 502 a
   * charged download that could not be delivered, a 503 an outage or no credentials.
   */
  downloadOpenSubtitles: (editionId, { fileId, kind }) =>
    post(`/api/admin/editions/${pathSegment(editionId)}/opensubtitles/download`, { fileId, kind }),
};

/** Server settings (Admin). `update` takes the minimum watch time in whole minutes, 0 to 30. */
export const settingsApi = {
  get: (options) => get('/api/admin/settings', options),
  update: (continueWatchingMinMinutes) =>
    put('/api/admin/settings', { continueWatchingMinMinutes }),
};

export const mediaFilesApi = {
  /** The WebVTT of one file's embedded subtitle stream (`index` is the stream index in the file). */
  subtitleUrl: (fileId, index) =>
    `/api/media-files/${pathSegment(fileId)}/subtitles/${pathSegment(index)}.vtt`,
};
