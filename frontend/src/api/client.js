// Fetch client. No JSX, no DOM beyond window events: reusable by other clients.

export const SESSION_EXPIRED_EVENT = 'cinefilm:session-expired';
export const SIGNED_IN_EVENT = 'cinefilm:signed-in';

const MUTATING = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

export class ApiError extends Error {
  constructor(status, message) {
    super(message || `Request failed (${status})`);
    this.name = 'ApiError';
    this.status = status;
  }
}

function emit(name) {
  if (typeof window !== 'undefined') window.dispatchEvent(new CustomEvent(name));
}

export function announceSignedIn() {
  emit(SIGNED_IN_EVENT);
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
  const response = await fetch(path, {
    method,
    headers,
    credentials: 'same-origin',
    body: body === undefined ? undefined : JSON.stringify(body),
    signal,
    keepalive: keepalive || undefined,
  });
  if (response.status === 401) {
    if (!path.startsWith('/api/session')) emit(SESSION_EXPIRED_EVENT);
    throw new ApiError(401, 'Unauthorised');
  }
  if (!response.ok) throw new ApiError(response.status);
  if (response.status === 204) return null;
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

export const get = (path, options) => request('GET', path, options);
export const put = (path, body, options) => request('PUT', path, { ...options, body });
export const post = (path, body, options) => request('POST', path, { ...options, body });
export const del = (path, options) => request('DELETE', path, options);
