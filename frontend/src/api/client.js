// Fetch client. No JSX, no DOM beyond window events: reusable by other clients.

export const SESSION_EXPIRED_EVENT = 'cinefilm:session-expired';
export const SIGNED_IN_EVENT = 'cinefilm:signed-in';
/** Something the search index shows changed in this tab: a scan or import finished, or the player closed. */
export const LIBRARY_CHANGED_EVENT = 'cinefilm:library-changed';

const MUTATING = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

export class ApiError extends Error {
  constructor(status, message) {
    super(message || `Request failed (${status})`);
    this.name = 'ApiError';
    this.status = status;
  }
}

function emit(name, detail) {
  if (typeof window !== 'undefined') window.dispatchEvent(new CustomEvent(name, { detail }));
}

export function announceSignedIn() {
  emit(SIGNED_IN_EVENT);
}

/** The user signed out on purpose: the same event as an expiry, with the reason attached. */
export function announceSignedOut() {
  emit(SESSION_EXPIRED_EVENT, { reason: 'signed-out' });
}

export function announceLibraryChanged() {
  emit(LIBRARY_CHANGED_EVENT);
}

async function fixtureRequest(method, path, body) {
  // VITE_FIXTURES is a build-time constant, so this branch is dropped from production builds.
  const mod = await import('./fixtureServer.js');
  return mod.handle(method, path, body);
}

/**
 * Sends a request to the API. Returns parsed JSON (or null for 204 / empty bodies).
 * A 401 from anything except the session endpoint emits the session-expired event.
 */
export async function request(method, path, { body, signal, keepalive } = {}) {
  if (import.meta.env.VITE_FIXTURES === '1') {
    return fixtureRequest(method, path, body);
  }
  const headers = { Accept: 'application/json' };
  if (MUTATING.has(method)) headers['X-CineFilm'] = '1';
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const response = await send(method, path, {
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    signal,
    keepalive,
  });
  if (!response.ok) throw new ApiError(response.status);
  if (response.status === 204) return null;
  return parse(response);
}

async function send(method, path, { headers, body, signal, keepalive }) {
  const response = await fetch(path, {
    method,
    headers,
    credentials: 'same-origin',
    body,
    signal,
    keepalive: keepalive || undefined,
  });
  if (response.status === 401) {
    if (!path.startsWith('/api/session')) emit(SESSION_EXPIRED_EVENT);
    throw new ApiError(401, 'Unauthorised');
  }
  return response;
}

async function parse(response) {
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

/** The ETag the fixture server stands in with: the same tag always means the same fixture. */
export const FIXTURE_ETAG = '"fixtures"';

/**
 * A conditional GET. Sends `If-None-Match` when `etag` is set. Resolves `{ notModified: true }` on a 304 and
 * `{ data, etag }` on a 200 (`etag` is null when the server sent none). 401 and other errors behave as in `request`.
 */
export async function getWithEtag(path, etag, { signal } = {}) {
  if (import.meta.env.VITE_FIXTURES === '1') {
    const data = await fixtureRequest('GET', path);
    return etag === FIXTURE_ETAG ? { notModified: true } : { data, etag: FIXTURE_ETAG };
  }
  const headers = { Accept: 'application/json' };
  if (etag) headers['If-None-Match'] = etag;
  const response = await send('GET', path, { headers, signal });
  if (response.status === 304) return { notModified: true };
  if (!response.ok) throw new ApiError(response.status);
  return { data: await parse(response), etag: response.headers.get('ETag') };
}

export const get = (path, options) => request('GET', path, options);
export const put = (path, body, options) => request('PUT', path, { ...options, body });
export const post = (path, body, options) => request('POST', path, { ...options, body });
export const del = (path, options) => request('DELETE', path, options);
