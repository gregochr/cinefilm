// A small in-memory cache for browse data (Home, Library, Film detail, ...). No JSX, no React.
// Kept only for a short while, and emptied by every change made in this tab (see `classify`).
import { LIBRARY_CHANGED_EVENT, SESSION_EXPIRED_EVENT, onMutation } from '../api/client.js';

export const MAX_ENTRIES = 12;
/** An answer this old or older is never shown. */
export const MAX_AGE_MS = 30 * 60 * 1000;

const entries = new Map(); // key -> { data, json, at }; the order is least to most recently used
let generation = 0;
let inFlight = 0;
let waiters = [];
let now = () => Date.now();

/** Tests inject a clock; `clearResourceCache` puts the real one back. */
export function setResourceCacheClock(clock) {
  now = clock;
}

/** Empties the cache and forgets every mutation in flight (tests, and any full reset). */
export function clearResourceCache() {
  entries.clear();
  generation += 1;
  inFlight = 0;
  now = () => Date.now();
  flushWaiters();
}

function flushWaiters() {
  const ready = waiters;
  waiters = [];
  ready.forEach((resolve) => resolve());
}

/** The longest `whenSettled` waits: a change that hangs (a dead network) must not hold a screen on its skeleton. */
export const SETTLE_WAIT_MS = 1000;

/**
 * Resolves at once when no invalidating change is in flight, otherwise when the last one has ended (or, if one
 * hangs, after `SETTLE_WAIT_MS`). The value is true when everything ended, false when it gave up.
 */
export function whenSettled() {
  if (inFlight === 0) return Promise.resolve(true);
  return new Promise((resolve) => {
    const timer = setTimeout(() => {
      waiters = waiters.filter((w) => w !== done);
      resolve(false);
    }, SETTLE_WAIT_MS);
    const done = () => {
      clearTimeout(timer);
      resolve(true);
    };
    waiters.push(done);
  });
}

/** The entry for `key` if the server gave or confirmed it less than `maxAgeMs` ago, else null. */
export function lookup(key, maxAgeMs = MAX_AGE_MS) {
  const entry = entries.get(key);
  if (!entry) return null;
  const age = now() - entry.at;
  if (age >= MAX_AGE_MS || age < 0) {
    entries.delete(key);
    return null;
  }
  if (age >= maxAgeMs) return null;
  entries.delete(key);
  entries.set(key, entry);
  return entry;
}

/** Called when a request starts; pass the result to `commit` when its answer lands. */
export function beginRequest() {
  return { generation, clean: inFlight === 0 };
}

/**
 * Offers an answer. It is stored only if no invalidating mutation was in flight when its request began and none
 * has started since. Returns `{ stored, json, data }`; `data` is the entry's own object when the answer is
 * JSON-equal to what was stored (that confirmation refreshes the entry's time).
 */
export function commit(key, token, data) {
  const json = JSON.stringify(data);
  if (!token.clean || token.generation !== generation) return { stored: false, json, data };
  const existing = entries.get(key);
  const entry = existing?.json === json ? { ...existing, at: now() } : { data, json, at: now() };
  entries.delete(key);
  entries.set(key, entry);
  while (entries.size > MAX_ENTRIES) entries.delete(entries.keys().next().value);
  return { stored: true, json, data: entry.data };
}

const PREFERENCES = /^\/api\/films\/([^/]+)\/preferences$/;
const SESSIONS = /^\/api\/editions\/[^/]+\/sessions$/;
const SESSION = /^\/api\/sessions\/[^/]+$/;
const HEALTH = /^\/api\/playback-health\/[^/]+$/;

/** What a mutating request invalidates: nothing, one film, or everything (the safe answer for the unknown). */
function classify(method, path) {
  const preferences = method === 'PUT' ? PREFERENCES.exec(path) : null;
  if (preferences) return { film: preferences[1] };
  if (method === 'POST' && SESSIONS.test(path)) return null;
  if (method === 'DELETE' && SESSION.test(path)) return null;
  if (method === 'PUT' && HEALTH.test(path)) return null;
  return { all: true };
}

function invalidate({ method, path, phase }) {
  const what = classify(method, path.split('?')[0]);
  if (!what) return;
  inFlight = phase === 'start' ? inFlight + 1 : Math.max(0, inFlight - 1);
  generation += 1;
  if (inFlight === 0) flushWaiters();
  if (what.all) entries.clear();
  else entries.delete(`film:${what.film}`);
}

function clearAll() {
  entries.clear();
  generation += 1;
}

onMutation(invalidate);
if (typeof window !== 'undefined') {
  window.addEventListener(LIBRARY_CHANGED_EVENT, clearAll);
  window.addEventListener(SESSION_EXPIRED_EVENT, clearAll);
}
