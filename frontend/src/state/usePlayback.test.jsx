import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { installFetch, respond } from '../test/mockApi.jsx';
import { usePlayback } from './usePlayback.js';

beforeEach(() => vi.useFakeTimers());
afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

const setup = (props = {}, position = { ms: 61000 }) =>
  renderHook(
    (p) =>
      usePlayback({
        filmId: 1,
        editionId: 11,
        playing: false,
        getPositionMs: () => position.ms,
        ...p,
      }),
    { initialProps: props },
  );

const puts = (fetchMock) =>
  fetchMock.mock.calls.filter((c) => c[1].method === 'PUT' && c[0].endsWith('/progress'));

describe('usePlayback reporting', () => {
  it('does not report the same position twice', async () => {
    const fetchMock = installFetch();
    const { result } = setup();
    await act(async () => {
      await result.current.report();
      await result.current.report();
    });
    expect(puts(fetchMock)).toHaveLength(1);
  });

  it('reports again once the position has moved', async () => {
    const fetchMock = installFetch();
    const position = { ms: 61000 };
    const { result } = setup({}, position);
    await act(async () => {
      await result.current.report();
    });
    position.ms = 62000;
    await act(async () => {
      await result.current.report();
    });
    expect(puts(fetchMock).map((c) => JSON.parse(c[1].body).positionMs)).toEqual([61000, 62000]);
  });

  it('retries the same position after a failed report', async () => {
    let fail = true;
    const fetchMock = installFetch(() => (fail ? respond(null, 500) : null));
    const { result } = setup();
    let first;
    await act(async () => {
      first = await result.current.report();
    });
    expect(first).toBeNull();
    fail = false;
    await act(async () => {
      await result.current.report();
    });
    expect(puts(fetchMock)).toHaveLength(2);
  });

  it('rounds the position', async () => {
    const fetchMock = installFetch();
    const { result } = setup({}, { ms: 1000.4 });
    await act(async () => {
      await result.current.report();
    });
    expect(JSON.parse(puts(fetchMock)[0][1].body)).toEqual({ positionMs: 1000 });
  });

  it('never reports without an edition or a finite position', async () => {
    const fetchMock = installFetch();
    const noEdition = setup({ editionId: null });
    const notANumber = setup({}, { ms: NaN });
    await act(async () => {
      expect(await noEdition.result.current.report()).toBeNull();
      expect(await notANumber.result.current.report()).toBeNull();
    });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('does not report on a timer while paused', async () => {
    const fetchMock = installFetch();
    setup({ playing: false });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(30000);
    });
    expect(puts(fetchMock)).toHaveLength(0);
  });

  it('stops the timer when playback stops', async () => {
    const fetchMock = installFetch();
    const position = { ms: 1000 };
    const { rerender } = setup({ playing: true }, position);
    await act(async () => {
      rerender({ playing: false, getPositionMs: () => position.ms });
    });
    fetchMock.mockClear();
    position.ms = 5000;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(30000);
    });
    expect(puts(fetchMock)).toHaveLength(0);
  });
});

describe('usePlayback actions', () => {
  it('marks the edition watched with POST', async () => {
    const fetchMock = installFetch();
    const { result } = setup();
    await act(async () => {
      await result.current.setWatched(true);
    });
    const post = fetchMock.mock.calls.find((c) => c[0] === '/api/editions/11/watched');
    expect(JSON.parse(post[1].body)).toEqual({ watched: true });
  });

  it('marking watched with no edition resolves to null and calls nothing', async () => {
    const fetchMock = installFetch();
    const { result } = setup({ editionId: undefined });
    await act(async () => {
      expect(await result.current.setWatched(true)).toBeNull();
    });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('saves preferences for the film, and a failure resolves to null', async () => {
    const fetchMock = installFetch();
    const { result } = setup();
    await act(async () => {
      await result.current.savePreferences({ quality: 'original' });
    });
    const put = fetchMock.mock.calls.find((c) => c[0] === '/api/films/1/preferences');
    expect(JSON.parse(put[1].body)).toEqual({ quality: 'original' });

    installFetch(() => respond(null, 500));
    await act(async () => {
      expect(await result.current.savePreferences({ quality: 'original' })).toBeNull();
    });
  });
});
