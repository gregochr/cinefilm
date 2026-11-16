import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SESSION_EXPIRED_EVENT } from '../api/client.js';
import { installFetch, respond } from '../test/mockApi.jsx';
import { useSignOut } from './useSignOut.js';

const announced = vi.fn();
const listen = (event) => announced(event.detail);
const isSignOut = (m, url) => m === 'DELETE' && url === '/api/session';
const deletes = (fetchMock) => fetchMock.mock.calls.filter(([, init]) => init?.method === 'DELETE');

beforeEach(() => window.addEventListener(SESSION_EXPIRED_EVENT, listen));
afterEach(() => {
  window.removeEventListener(SESSION_EXPIRED_EVENT, listen);
  announced.mockReset();
  vi.unstubAllGlobals();
});

describe('useSignOut', () => {
  it('sends one DELETE /api/session and announces it, busy only while the request is open', async () => {
    let finish;
    const fetchMock = installFetch((m, url) =>
      isSignOut(m, url)
        ? new Promise((resolve) => {
            finish = resolve;
          })
        : null,
    );
    const { result } = renderHook(() => useSignOut());
    expect(result.current.signingOut).toBe(false);

    let pending;
    act(() => {
      pending = result.current.signOut();
    });
    expect(result.current.signingOut).toBe(true);
    expect(announced).not.toHaveBeenCalled();

    // The client may await before it calls fetch, so the request is not open the moment signOut is called.
    await waitFor(() => expect(finish).toBeTypeOf('function'));
    await act(async () => {
      finish(respond(null, 204));
      await pending;
    });
    const calls = deletes(fetchMock);
    expect(calls).toHaveLength(1);
    expect(calls[0][0]).toBe('/api/session');
    expect(calls[0][1].headers['X-CineFilm']).toBe('1');
    expect(announced).toHaveBeenCalledTimes(1);
    expect(announced).toHaveBeenCalledWith({ reason: 'signed-out' });
    expect(result.current.signingOut).toBe(false);
    expect(result.current.error).toBeNull();
  });

  it('reports a failure, does not announce, and a retry clears the message', async () => {
    let fail = true;
    const fetchMock = installFetch((m, url) =>
      isSignOut(m, url) ? respond(null, fail ? 500 : 204) : null,
    );
    const { result } = renderHook(() => useSignOut());

    await act(() => result.current.signOut());
    expect(result.current.error).toBe("Couldn't sign out. Try again.");
    expect(result.current.signingOut).toBe(false);
    expect(announced).not.toHaveBeenCalled();

    fail = false;
    await act(() => result.current.signOut());
    expect(result.current.error).toBeNull();
    expect(deletes(fetchMock)).toHaveLength(2);
    expect(announced).toHaveBeenCalledTimes(1);
  });

  it('a 401 means the session already ended: it announces signed out and shows no failure', async () => {
    installFetch((m, url) => (isSignOut(m, url) ? respond(null, 401) : null));
    const { result } = renderHook(() => useSignOut());

    await act(() => result.current.signOut());

    expect(announced).toHaveBeenCalledTimes(1);
    expect(announced).toHaveBeenCalledWith({ reason: 'signed-out' });
    expect(result.current.error).toBeNull();
    expect(result.current.signingOut).toBe(false);
  });

  it('a network failure still reports the failure and does not announce', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));
    const { result } = renderHook(() => useSignOut());

    await act(() => result.current.signOut());

    expect(result.current.error).toBe("Couldn't sign out. Try again.");
    expect(announced).not.toHaveBeenCalled();
  });

  it('a second press while the request is open sends nothing', async () => {
    let finish;
    const fetchMock = installFetch((m, url) =>
      isSignOut(m, url)
        ? new Promise((resolve) => {
            finish = resolve;
          })
        : null,
    );
    const { result } = renderHook(() => useSignOut());

    let both;
    act(() => {
      both = Promise.all([result.current.signOut(), result.current.signOut()]);
    });
    await waitFor(() => expect(finish).toBeTypeOf('function'));
    await act(async () => {
      finish(respond(null, 204));
      await both;
    });

    expect(deletes(fetchMock)).toHaveLength(1);
    expect(announced).toHaveBeenCalledTimes(1);
  });
});
