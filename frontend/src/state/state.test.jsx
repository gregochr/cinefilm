import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAutoHide } from '../player/autohide.js';
import { fixtures, installFetch, respond } from '../test/mockApi.jsx';
import { useFilm, useFilms } from './useFilms.js';
import { useHome } from './useHome.js';
import { usePlayback } from './usePlayback.js';
import { useSession } from './useSession.js';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe('data hooks', () => {
  it('useHome loads the home payload', async () => {
    installFetch();
    const { result } = renderHook(() => useHome());
    expect(result.current.loading).toBe(true);
    await waitFor(() => expect(result.current.data).toEqual(fixtures.home));
  });

  it('useFilms passes search params and keeps the previous data while reloading', async () => {
    const fetchMock = installFetch();
    const { result, rerender } = renderHook(({ q }) => useFilms({ q, sort: 'title' }), {
      initialProps: { q: '' },
    });
    await waitFor(() => expect(result.current.data?.total).toBe(fixtures.films.total));
    rerender({ q: 'heat' });
    expect(result.current.data).not.toBeNull();
    await waitFor(() => expect(result.current.data.total).toBe(1));
    expect(fetchMock.mock.calls.at(-1)[0]).toBe('/api/films?q=heat&sort=title');
  });

  it('exposes errors', async () => {
    installFetch(() => respond(null, 500));
    const { result } = renderHook(() => useFilm(1));
    await waitFor(() => expect(result.current.error?.status).toBe(500));
  });
});

describe('usePlayback', () => {
  beforeEach(() => vi.useFakeTimers());

  it('reports every 10 s while playing, on pause and on unmount', async () => {
    const fetchMock = installFetch();
    let position = 61000;
    const { rerender, unmount } = renderHook(
      ({ playing }) =>
        usePlayback({ filmId: 1, editionId: 11, playing, getPositionMs: () => position }),
      { initialProps: { playing: true } },
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10000);
    });
    const puts = () => fetchMock.mock.calls.filter((c) => c[1].method === 'PUT');
    expect(puts()).toHaveLength(1);
    expect(puts()[0][0]).toBe('/api/editions/11/progress');
    expect(JSON.parse(puts()[0][1].body)).toEqual({ positionMs: 61000 });
    expect(puts()[0][1].headers['X-CineFilm']).toBe('1');

    position = 70000;
    await act(async () => {
      rerender({ playing: false });
    });
    expect(puts()).toHaveLength(2);

    position = 75000;
    unmount();
    expect(puts()).toHaveLength(3);
  });
});

describe('useAutoHide', () => {
  beforeEach(() => vi.useFakeTimers());

  it('hides after 3 s while playing, but never while paused or a menu is open', () => {
    const { result, rerender } = renderHook((props) => useAutoHide(props), {
      initialProps: { playing: true, menuOpen: false },
    });
    expect(result.current.visible).toBe(true);
    act(() => vi.advanceTimersByTime(2999));
    expect(result.current.visible).toBe(true);
    act(() => vi.advanceTimersByTime(1));
    expect(result.current.visible).toBe(false);

    act(() => result.current.poke());
    expect(result.current.visible).toBe(true);

    rerender({ playing: true, menuOpen: true });
    act(() => vi.advanceTimersByTime(10000));
    expect(result.current.visible).toBe(true);

    rerender({ playing: false, menuOpen: false });
    act(() => vi.advanceTimersByTime(10000));
    expect(result.current.visible).toBe(true);
  });
});

describe('useSession', () => {
  it('flips to expired on a 401 and recovers after sign-in', async () => {
    let signedIn = false;
    const fetchMock = installFetch((method, url) => {
      if (method === 'POST') {
        signedIn = true;
        return respond({ authenticated: true });
      }
      return !signedIn && url === '/api/home' ? respond(null, 401) : null;
    });
    const { result } = renderHook(() => ({ session: useSession(), home: useHome() }));
    await waitFor(() => expect(result.current.session.expired).toBe(true));
    let ok;
    await act(async () => {
      ok = await result.current.session.signIn('changeme');
    });
    expect(ok).toBe(true);
    expect(result.current.session.expired).toBe(false);
    const post = fetchMock.mock.calls.find((c) => c[1].method === 'POST');
    expect(post[0]).toBe('/api/session');
    expect(JSON.parse(post[1].body)).toEqual({ password: 'changeme' });
  });

  it('reports a wrong password', async () => {
    installFetch((method) => (method === 'POST' ? respond(null, 401) : null));
    const { result } = renderHook(() => useSession());
    let ok;
    await act(async () => {
      ok = await result.current.signIn('nope');
    });
    expect(ok).toBe(false);
    expect(result.current.error).toMatch(/password/i);
  });
});
