// The compact search index, shared app-wide. Plain functions: no JSX, no DOM events (the browser-facing wiring is
// `components/useSearchIndexLifecycle.js`). Usable with React's useSyncExternalStore.
//
// It loads once after sign-in, is kept in localStorage so a reload has it at once, and is revalidated with its ETag
// when asked to (the tab became visible, a scan or import finished, the player closed). A 304 keeps what is held.
// Whatever comes from the server or from storage is rebuilt field by field (`sanitizeRaw`) before it is used.
import { useSyncExternalStore } from 'react';
import { FIXTURE_ETAG } from '../api/client.js';
import { searchApi } from '../api/resources.js';
import { displayName } from './collections.js';

/** The localStorage key of the cached `{ etag, raw }`. */
export const SEARCH_INDEX_STORAGE_KEY = 'cinefilm.searchIndex.v1';

const WEAK_ETAG = /^W\/"[0-9a-f]{16}"$/;

/** Only the server's own weak ETags (or the fixture server's) are kept or sent back. */
export function isValidEtag(value) {
  return typeof value === 'string' && (WEAK_ETAG.test(value) || value === FIXTURE_ETAG);
}

const MAX_TEXT = 300;
const MAX_DIRECTORS = 20;
const MAX_POSTERS = 3;

const isNumber = (v) => typeof v === 'number' && Number.isFinite(v);
const isText = (v) => typeof v === 'string' && v.length <= MAX_TEXT;
const texts = (v, max) => (Array.isArray(v) ? v.filter(isText).slice(0, max) : []);
const ifNumber = (v) => (isNumber(v) ? v : undefined);
const ifText = (v) => (isText(v) ? v : undefined);
const without = (entry) =>
  Object.fromEntries(Object.entries(entry).filter(([, value]) => value !== undefined));

/**
 * Rebuilds the compact index from scratch with only the known fields, each type-checked: ids and counts finite
 * numbers, names strings, `d` and `p` arrays of strings with capped lengths. An entry missing its id or name is
 * dropped; an optional field of the wrong type is left out. Nothing else (extra keys, other types) survives.
 */
export function sanitizeRaw(raw) {
  const list = (value) => (Array.isArray(value) ? value : []);
  const section = raw && typeof raw === 'object' ? raw : {};
  return {
    films: list(section.films)
      .filter((f) => f && isNumber(f.id) && isText(f.t))
      .map((f) =>
        without({
          id: f.id,
          t: f.t,
          y: ifNumber(f.y),
          d: texts(f.d, MAX_DIRECTORS),
          p: ifText(f.p),
          left: ifNumber(f.left),
          at: ifText(f.at),
          s: ifText(f.s),
        }),
      ),
    collections: list(section.collections)
      .filter((c) => c && isNumber(c.id) && isText(c.n) && isNumber(c.o) && isNumber(c.of))
      .map((c) => ({ id: c.id, n: c.n, o: c.o, of: c.of, p: texts(c.p, MAX_POSTERS) })),
    people: list(section.people)
      .filter((p) => p && isNumber(p.id) && isText(p.n))
      .map((p) => ({ id: p.id, n: p.n, a: ifNumber(p.a) ?? 0, d: ifNumber(p.d) ?? 0 })),
  };
}

/**
 * Turns the server's compact index into entries with display strings ready. Normalisation for matching is added
 * on top of this later; here the keys are only spelled out and the display strings built once.
 */
export function prepareIndex(raw) {
  return {
    films: (raw?.films ?? []).map((f) => {
      const directors = f.d ?? [];
      return {
        id: f.id,
        title: f.t,
        year: f.y ?? null,
        directors,
        subtitle: [f.y, directors.join(', ')].filter(Boolean).join(' · '),
        posterPath: f.p ?? null,
        leftMs: f.left ?? null,
        playedAt: f.at ?? null,
        sortTitle: f.s ?? f.t.toLowerCase(),
      };
    }),
    collections: (raw?.collections ?? []).map((c) => ({
      id: c.id,
      name: displayName(c.n),
      ownedCount: c.o,
      totalCount: c.of,
      posterPaths: c.p ?? [],
    })),
    people: (raw?.people ?? []).map((p) => ({
      id: p.id,
      name: p.n,
      actorCount: p.a ?? 0,
      directorCount: p.d ?? 0,
    })),
  };
}

/** The stored copy as `{ etag, raw }`, rebuilt through the same checks as a response; null if absent or invalid. */
function readStored() {
  try {
    const stored = JSON.parse(localStorage.getItem(SEARCH_INDEX_STORAGE_KEY));
    return stored && isValidEtag(stored.etag)
      ? { etag: stored.etag, raw: sanitizeRaw(stored.raw) }
      : null;
  } catch {
    return null;
  }
}

/** Stores a copy of an already sanitised index with its valid ETag. */
function writeStored(tag, raw) {
  try {
    localStorage.setItem(
      SEARCH_INDEX_STORAGE_KEY,
      JSON.stringify({ etag: tag, raw: sanitizeRaw(raw) }),
    );
  } catch {
    // Storage full, blocked or missing: the index still works from memory.
  }
}

function clearStored() {
  try {
    localStorage.removeItem(SEARCH_INDEX_STORAGE_KEY);
  } catch {
    // Nothing to clear if storage cannot be reached.
  }
}

const INITIAL = { index: null, status: 'loading' };

let snapshot = INITIAL;
let etag = null;
let started = false;
let inFlight = null;
let pending = false;
let controller = null;
const listeners = new Set();

function publish(next) {
  snapshot = next;
  listeners.forEach((listener) => listener());
}

/** `{ index, status }`: status is `loading` (nothing held yet), `ready`, or `error` (nothing held, load failed). */
export function getSnapshot() {
  return snapshot;
}

export function subscribe(listener) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/**
 * Asks the server whether the index changed, sending the held ETag. A 304 keeps the data; a 200 replaces it and
 * stores it. A failure keeps whatever is held (and is `error` only when nothing is). A call made while a request
 * is in flight shares it, and marks it `pending`: that request may have been answered before the change that
 * prompted the call, so one more request runs when it settles. A burst of events costs at most one extra request.
 */
export function revalidate() {
  if (inFlight) {
    pending = true;
    return inFlight;
  }
  const mine = new AbortController();
  controller = mine;
  const request = searchApi
    .index({ etag, signal: mine.signal })
    .then((result) => {
      if (mine.signal.aborted || result.notModified) return;
      const raw = sanitizeRaw(result.data);
      etag = isValidEtag(result.etag) ? result.etag : null;
      if (etag) writeStored(etag, raw);
      publish({ index: prepareIndex(raw), status: 'ready' });
    })
    .catch(() => {
      if (mine.signal.aborted || snapshot.index) return;
      publish({ index: null, status: 'error' });
    })
    .finally(() => {
      if (inFlight !== request) return;
      inFlight = null;
      if (pending) {
        pending = false;
        void revalidate();
      }
    });
  inFlight = request;
  return request;
}

/** The session ended: forget the stored copy and any request in flight. What is on screen stays until reload. */
export function forgetSearchIndex() {
  clearStored();
  controller?.abort();
  inFlight = null;
  pending = false;
}

/**
 * Loads the index (the stored copy at once, then the server). Only the first call does anything; it returns true
 * when it started the load, false when the store was already started.
 */
export function startSearchIndex() {
  if (started) return false;
  started = true;
  const stored = readStored();
  if (stored) {
    etag = stored.etag;
    publish({ index: prepareIndex(stored.raw), status: 'ready' });
  }
  void revalidate();
  return true;
}

/** Forgets everything. For tests; the app never stops the store. */
export function resetSearchIndexForTests() {
  controller?.abort();
  controller = null;
  inFlight = null;
  pending = false;
  started = false;
  etag = null;
  snapshot = INITIAL;
  listeners.clear();
}

/** The shared index: `{ index, status }`. Starting and refreshing it is `useSearchIndexLifecycle`'s job. */
export function useSearchIndex() {
  return useSyncExternalStore(subscribe, getSnapshot);
}
