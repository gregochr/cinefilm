import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, respond } from '../test/mockApi.jsx';
import { useForcedNeed } from './useForcedNeed.js';

afterEach(() => vi.unstubAllGlobals());

const LIST = fixtures.adminForcedNeed;
const FORCED = /^\/api\/admin\/films\/(\d+)\/forced-need$/;
const answered = (filmId, choice) => ({
  ...LIST.films.find((f) => f.filmId === filmId),
  ownerChoice: choice,
  need: choice ?? 'NEEDED',
  reason: choice ? 'OWNER' : 'OTHER_LANGUAGES',
});

/** A PUT per film that waits for the test to answer it; the answer follows the abort like a real fetch. */
function deferredPuts() {
  const puts = [];
  const fetchMock = installFetch((method, url, init) => {
    if (!FORCED.test(url)) return null;
    return new Promise((resolve, reject) => {
      const put = { body: JSON.parse(init.body), signal: init.signal, resolve, reject };
      init.signal?.addEventListener('abort', () =>
        reject(new DOMException('aborted', 'AbortError')),
      );
      puts.push(put);
    }).then((row) => respond(row));
  });
  return { puts, fetchMock };
}

async function loaded() {
  const hook = renderHook(({ key }) => useForcedNeed(key), { initialProps: { key: 'a' } });
  await waitFor(() => expect(hook.result.current.rows).toEqual(LIST.films));
  return hook;
}

const rowOf = (result, filmId) => result.current.rows.find((r) => r.filmId === filmId);

describe('useForcedNeed', () => {
  it('applies the returned row without reading the list again', async () => {
    const { puts, fetchMock } = deferredPuts();
    const { result } = await loaded();
    const listReads = fetchMock.mock.calls.length;

    let done;
    act(() => {
      done = result.current.choose(2, 'NOT_NEEDED');
    });
    expect(result.current.saving).toEqual({ 2: true });
    expect(puts[0].body).toEqual({ choice: 'NOT_NEEDED' });
    await act(async () => {
      puts[0].resolve(answered(2, 'NOT_NEEDED'));
      await done;
    });

    expect(rowOf(result, 2)).toMatchObject({ ownerChoice: 'NOT_NEEDED', need: 'NOT_NEEDED' });
    expect(result.current.saving).toEqual({});
    expect(fetchMock.mock.calls.length).toBe(listReads + 1);
  });

  it('sends null for Automatic', async () => {
    const { puts } = deferredPuts();
    const { result } = await loaded();
    act(() => {
      result.current.choose(7, null);
    });
    expect(puts[0].body).toEqual({ choice: null });
  });

  it('lets a newer click on the same film win even when the older answer arrives last', async () => {
    const { puts } = deferredPuts();
    const { result } = await loaded();

    let first;
    let second;
    act(() => {
      first = result.current.choose(2, 'NEEDED');
      second = result.current.choose(2, null);
    });
    expect(puts[0].signal.aborted).toBe(true);
    expect(puts[1].signal.aborted).toBe(false);
    await act(async () => {
      puts[1].resolve(answered(2, null));
      await second;
      puts[0].resolve(answered(2, 'NEEDED')); // too late: its request was aborted
      await first;
    });

    expect(rowOf(result, 2).ownerChoice).toBeNull();
    expect(result.current.saving).toEqual({});
    expect(result.current.problems).toEqual({});
  });

  it('ignores the older answer even if the transport delivered it after the newer click', async () => {
    const puts = [];
    installFetch((method, url, init) => {
      if (!FORCED.test(url)) return null;
      // A fetch that ignores the abort signal, as a response already on the wire would.
      return new Promise((resolve) => {
        puts.push({ body: JSON.parse(init.body), resolve });
      });
    });
    const { result } = await loaded();

    let first;
    let second;
    act(() => {
      first = result.current.choose(2, 'NEEDED');
      second = result.current.choose(2, 'NOT_NEEDED');
    });
    await act(async () => {
      puts[1].resolve(respond(answered(2, 'NOT_NEEDED')));
      await second;
      puts[0].resolve(respond(answered(2, 'NEEDED')));
      await first;
    });

    expect(rowOf(result, 2).ownerChoice).toBe('NOT_NEEDED');
  });

  it('does not show the failure of an older request that a newer click replaced', async () => {
    const { puts } = deferredPuts();
    const { result } = await loaded();
    let first;
    let second;
    act(() => {
      first = result.current.choose(2, 'NEEDED');
      second = result.current.choose(2, 'NOT_NEEDED');
    });
    await act(async () => {
      puts[1].resolve(answered(2, 'NOT_NEEDED'));
      await Promise.all([first, second]);
    });
    expect(result.current.problems).toEqual({});
  });

  it('keeps other films free: two films save at once', async () => {
    const { puts } = deferredPuts();
    const { result } = await loaded();
    let a;
    let b;
    act(() => {
      a = result.current.choose(2, 'NOT_NEEDED');
      b = result.current.choose(3, 'NOT_NEEDED');
    });
    expect(result.current.saving).toEqual({ 2: true, 3: true });
    expect(puts.map((p) => p.signal.aborted)).toEqual([false, false]);
    await act(async () => {
      puts[0].resolve(answered(2, 'NOT_NEEDED'));
      puts[1].resolve(answered(3, 'NOT_NEEDED'));
      await Promise.all([a, b]);
    });
    expect(rowOf(result, 2).ownerChoice).toBe('NOT_NEEDED');
    expect(rowOf(result, 3).ownerChoice).toBe('NOT_NEEDED');
  });

  it('keeps the row and says a scan is running for a 409, then clears the message on the next click', async () => {
    let status = 409;
    installFetch((method, url) =>
      FORCED.test(url)
        ? status === 409
          ? respond({ error: 'scan' }, 409)
          : respond(answered(2, 'NOT_NEEDED'))
        : null,
    );
    const { result } = await loaded();

    await act(() => result.current.choose(2, 'NOT_NEEDED'));
    expect(result.current.problems).toEqual({
      2: 'A scan is running: try again when it finishes',
    });
    expect(rowOf(result, 2)).toEqual(LIST.films.find((f) => f.filmId === 2));
    expect(result.current.saving).toEqual({});

    status = 200;
    await act(() => result.current.choose(2, 'NOT_NEEDED'));
    expect(result.current.problems).toEqual({});
    expect(rowOf(result, 2).ownerChoice).toBe('NOT_NEEDED');
  });

  it('says it could not save for any other failure', async () => {
    installFetch((method, url) => (FORCED.test(url) ? respond(null, 500) : null));
    const { result } = await loaded();
    await act(() => result.current.choose(2, 'NEEDED'));
    expect(result.current.problems).toEqual({ 2: "Couldn't save: try again" });
  });

  it('reads the list again when the refresh key changes, and the server’s rows replace the answers', async () => {
    const { puts } = deferredPuts();
    const { result, rerender } = await loaded();
    let done;
    act(() => {
      done = result.current.choose(2, 'NOT_NEEDED');
    });
    await act(async () => {
      puts[0].resolve(answered(2, 'NOT_NEEDED'));
      await done;
    });
    expect(rowOf(result, 2).ownerChoice).toBe('NOT_NEEDED');

    rerender({ key: 'b' });
    await waitFor(() => expect(rowOf(result, 2).ownerChoice).toBeNull());
  });

  it('answers applied after a refresh apply to the refreshed list', async () => {
    const { puts } = deferredPuts();
    const { result, rerender } = await loaded();
    let done;
    act(() => {
      done = result.current.choose(2, 'NOT_NEEDED');
    });
    rerender({ key: 'b' });
    await waitFor(() => expect(result.current.loading).toBe(false));
    await act(async () => {
      puts[0].resolve(answered(2, 'NOT_NEEDED'));
      await done;
    });
    expect(rowOf(result, 2).ownerChoice).toBe('NOT_NEEDED');
  });

  it('aborts a request that is still in flight when it unmounts', async () => {
    const { puts } = deferredPuts();
    const { result, unmount } = await loaded();
    let done;
    act(() => {
      done = result.current.choose(2, 'NEEDED');
    });
    unmount();
    expect(puts[0].signal.aborted).toBe(true);
    await done;
  });
});
