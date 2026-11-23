// The train viewer's segment record: every media segment the page downloads, one entry each, so that the speed of each
// download can be laid beside the train's position (`segments.json`, docs/engineering/train-viewer.md). It listens to
// Chrome's `Network` domain and keeps no header, no cookie, no body, no session id and no full address: only the
// rung, the file name and the figures. A playlist (`.m3u8`) is counted and not listed.
import { printable } from './text.mjs';

/** At most this many segments are listed (a 2 h 16 min film at 6 s segments is about 1,400, plus retries). */
export const MAX_SEGMENTS = 20_000;
const MAX_ERROR_CHARS = 100;

const UUID = '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}';
const MEDIA_PATH = new RegExp(`^/api/sessions/${UUID}/([A-Za-z0-9_-]{1,32})/([A-Za-z0-9._-]{1,100})$`, 'i');

/**
 * What a request is to the record: `{ rung, file }` for a segment, `{ playlist: true }` for a playlist, null for
 * anything else (another method than `GET`, another origin, another path). The address's query is ignored. A `GET` is
 * the only method the guard (`guard.mjs`) never answers or fails itself: it answers writes only, so a request it
 * fulfilled on purpose is never in the record and a failure here is the network's.
 */
export function classify(method, url, origin) {
  if (method !== 'GET') return null;
  let parsed;
  try {
    parsed = new URL(url);
  } catch {
    return null;
  }
  if (parsed.origin !== origin) return null;
  const match = MEDIA_PATH.exec(parsed.pathname);
  if (match === null) return null;
  if (match[2].toLowerCase().endsWith('.m3u8')) return { playlist: true };
  return { rung: match[1], file: match[2] };
}

const finite = (value) => (typeof value === 'number' && Number.isFinite(value) ? value : null);

// Milliseconds from the request's start to the first byte of its answer, from the `timing` object (its `requestTime` is
// on the same clock as the event timestamps, its other fields are milliseconds after it). null when Chrome gave none
// (a cached answer has no timing).
function firstByteMs(timing, startedAt) {
  if (timing === null || typeof timing !== 'object') return null;
  const requestTime = finite(timing.requestTime);
  const headers = finite(timing.receiveHeadersStart) ?? finite(timing.receiveHeadersEnd);
  if (requestTime === null || headers === null || headers < 0 || startedAt === null) return null;
  return Math.max(0, Math.round(requestTime * 1000 + headers - startedAt * 1000));
}

const elapsedMs = (from, to) => (from === null || to === null ? null : Math.max(0, Math.round((to - from) * 1000)));

/**
 * The speed of a finished download in kilobits a second (bytes on the wire over the total time), or null when there is
 * none to speak of: a failed or cancelled download, an unfinished one, no bytes, no time, or a status that is not 2xx.
 */
export function speedKbit(entry) {
  if (entry.failed || entry.canceled || entry.unfinished) return null;
  if (!(entry.status >= 200 && entry.status < 300)) return null;
  if (!(entry.bytes > 0) || !(entry.totalMs > 0)) return null;
  return (entry.bytes * 8) / entry.totalMs;
}

/**
 * Puts the recorder on a page (the `send` and `on` of `cdp.mjs`): registers the listeners, then asks Chrome for the
 * `Network` domain and resolves once it has said yes; the caller opens no address before that. Events for a request
 * whose start was never seen are counted (`unseen`) and dropped. Resolves `{ report }`: `report()` is a deep copy of
 * `{ cap, capped, dropped, playlists, unseen, segments }`, the segments in the order they started, a request still in
 * flight listed with `unfinished: true`. `cap` is for a test.
 */
export async function attachSegments(page, { site, cap = MAX_SEGMENTS }) {
  const origin = new URL(site).origin;
  const listed = [];
  const open = new Map();
  const counts = { dropped: 0, playlists: 0, unseen: 0 };

  page.on('Network.requestWillBeSent', (params) => {
    const requestId = params?.requestId;
    if (typeof requestId !== 'string' || open.has(requestId)) return;
    const kind = classify(params.request?.method, params.request?.url, origin);
    if (kind === null) return;
    if (kind.playlist) {
      counts.playlists += 1;
      return;
    }
    if (listed.length >= cap) {
      counts.dropped += 1;
      return;
    }
    const wall = finite(params.wallTime);
    const entry = {
      rung: kind.rung, file: kind.file, status: null, bytes: null,
      startMs: wall === null ? null : Math.round(wall * 1000),
      ttfbMs: null, totalMs: null, failed: false, canceled: false, errorText: null, unfinished: true,
    };
    listed.push(entry);
    open.set(requestId, { entry, startedAt: finite(params.timestamp) });
  });

  page.on('Network.responseReceived', (params) => {
    const seen = open.get(params?.requestId);
    if (seen === undefined) {
      counts.unseen += 1;
      return;
    }
    const response = params.response ?? {};
    seen.entry.status = finite(response.status);
    seen.entry.ttfbMs = firstByteMs(response.timing, seen.startedAt);
  });

  const end = (params, change) => {
    const seen = open.get(params?.requestId);
    if (seen === undefined) {
      counts.unseen += 1;
      return;
    }
    open.delete(params.requestId);
    seen.entry.unfinished = false;
    seen.entry.totalMs = elapsedMs(seen.startedAt, finite(params.timestamp));
    change(seen.entry);
  };

  page.on('Network.loadingFinished', (params) => end(params, (entry) => {
    entry.bytes = finite(params.encodedDataLength);
  }));

  page.on('Network.loadingFailed', (params) => end(params, (entry) => {
    entry.canceled = params.canceled === true;
    entry.failed = !entry.canceled;
    entry.errorText = typeof params.errorText === 'string' ? printable(params.errorText, MAX_ERROR_CHARS) : null;
  }));

  await page.send('Network.enable');
  return {
    report: () => ({
      cap, capped: counts.dropped > 0, dropped: counts.dropped, playlists: counts.playlists, unseen: counts.unseen,
      segments: listed.map((entry) => ({ ...entry })),
    }),
  };
}
