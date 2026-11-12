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

  it('sends one write at a time: a second click waits for the first, and the last answer shows', async () => {
    const { puts } = deferredPuts();
    const { result } = await loaded();
    let first;
    let second;
    act(() => {
      first = result.current.choose(2, 'NEEDED');
      second = result.current.choose(2, 'NOT_NEEDED');
    });
    expect(puts).toHaveLength(1);
    await act(async () => {
      puts[0].resolve(answered(2, 'NEEDED'));
      await Promise.resolve();
    });
    await waitFor(() => expect(puts).toHaveLength(2));
    expect(puts.map((p) => p.body)).toEqual([{ choice: 'NEEDED' }, { choice: 'NOT_NEEDED' }]);
    expect(result.current.saving).toEqual({ 2: true });
    expect(rowOf(result, 2).ownerChoice).toBeNull(); // the server's row, until the chain ends
    await act(async () => {
      puts[1].resolve(answered(2, 'NOT_NEEDED'));
      await Promise.all([first, second]);
    });
    expect(rowOf(result, 2).ownerChoice).toBe('NOT_NEEDED');
    expect(result.current.saving).toEqual({});
    expect(result.current.problems).toEqual({});
  });

  it('sends only the first and the last of three quick clicks', async () => {
    const { puts } = deferredPuts();
    const { result } = await loaded();
    let first;
    act(() => {
      first = result.current.choose(2, 'NEEDED');
      result.current.choose(2, 'NOT_NEEDED');
      result.current.choose(2, null);
    });
    await act(async () => {
      puts[0].resolve(answered(2, 'NEEDED'));
      await Promise.resolve();
    });
    await waitFor(() => expect(puts).toHaveLength(2));
    await act(async () => {
      puts[1].resolve(answered(2, null));
      await first;
    });
    expect(puts.map((p) => p.body)).toEqual([{ choice: 'NEEDED' }, { choice: null }]);
    expect(rowOf(result, 2).ownerChoice).toBeNull();
  });

  it('still sends the waiting choice when the first write fails, and reports no error when that one succeeds', async () => {
    const { puts } = deferredPuts();
    const { result } = await loaded();
    let first;
    act(() => {
      first = result.current.choose(2, 'NEEDED');
      result.current.choose(2, 'NOT_NEEDED');
    });
    await act(async () => {
      puts[0].reject(new Error('offline'));
      await Promise.resolve();
    });
    await waitFor(() => expect(puts).toHaveLength(2));
    await act(async () => {
      puts[1].resolve(answered(2, 'NOT_NEEDED'));
      await first;
    });
    expect(result.current.problems).toEqual({});
    expect(rowOf(result, 2).ownerChoice).toBe('NOT_NEEDED');
  });

  it('reports the last write’s failure and keeps the last answer the server gave', async () => {
    const { puts } = deferredPuts();
    const { result } = await loaded();
    let first;
    act(() => {
      first = result.current.choose(2, 'NEEDED');
      result.current.choose(2, 'NOT_NEEDED');
    });
    await act(async () => {
      puts[0].resolve(answered(2, 'NEEDED'));
      await Promise.resolve();
    });
    await waitFor(() => expect(puts).toHaveLength(2));
    await act(async () => {
      puts[1].reject(new Error('offline'));
      await first;
    });
    expect(result.current.problems).toEqual({ 2: "Couldn't save: try again" });
    expect(rowOf(result, 2)).toMatchObject({ ownerChoice: 'NEEDED' });
    expect(result.current.saving).toEqual({});
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
    expect(puts).toHaveLength(2); // another film's click goes straight through
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

  it('does not abort a write in flight when it unmounts, and ignores its result without a warning', async () => {
    const errors = vi.spyOn(console, 'error').mockImplementation(() => {});
    const { puts } = deferredPuts();
    const { result, unmount } = await loaded();
    let done;
    act(() => {
      done = result.current.choose(2, 'NEEDED');
      result.current.choose(2, 'NOT_NEEDED');
    });
    unmount();
    expect(puts[0].signal).toBeUndefined();
    puts[0].resolve(answered(2, 'NEEDED'));
    await done;
    expect(puts).toHaveLength(1); // the waiting choice is not sent
    expect(errors).not.toHaveBeenCalled();
    errors.mockRestore();
  });
});

describe('useForcedNeed: a refresh that overlaps a save', () => {
  const LIST_URL = '/api/admin/forced-subtitles/films';

  /** The first list read answers at once; later ones and every PUT wait for the test. */
  function overlapping() {
    const gets = [];
    const puts = [];
    let reads = 0;
    installFetch((method, url, init) => {
      if (url === LIST_URL) {
        reads += 1;
        if (reads === 1) return respond(LIST);
        return new Promise((resolve) => gets.push(resolve)).then((list) => respond(list));
      }
      if (!FORCED.test(url)) return null;
      return new Promise((resolve, reject) =>
        puts.push({ body: JSON.parse(init.body), resolve, reject }),
      ).then((row) => respond(row));
    });
    return { gets, puts };
  }

  const loadedFirst = async () => {
    const hook = renderHook(({ key }) => useForcedNeed(key), { initialProps: { key: 'a' } });
    await waitFor(() => expect(hook.result.current.rows).toEqual(LIST.films));
    return hook;
  };

  const save = async (result, puts, index, filmId, choice) => {
    let done;
    act(() => {
      done = result.current.choose(filmId, choice);
    });
    await act(async () => {
      puts[index].resolve(answered(filmId, choice));
      await done;
    });
  };

  it('keeps a saved choice when a list read that started before the save answers with the old row', async () => {
    const { gets, puts } = overlapping();
    const { result, rerender } = await loadedFirst();

    rerender({ key: 'b' }); // the refresh starts and waits
    await waitFor(() => expect(gets).toHaveLength(1));
    await save(result, puts, 0, 2, 'NOT_NEEDED');
    await act(async () => {
      gets[0](LIST); // the old snapshot, read before the save
    });

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(rowOf(result, 2).ownerChoice).toBe('NOT_NEEDED');
  });

  it('lets a list read that started after the save replace it', async () => {
    const { gets, puts } = overlapping();
    const { result, rerender } = await loadedFirst();
    await save(result, puts, 0, 2, 'NOT_NEEDED');

    rerender({ key: 'b' });
    await waitFor(() => expect(gets).toHaveLength(1));
    await act(async () => {
      gets[0]({
        ...LIST,
        films: LIST.films.map((f) => (f.filmId === 2 ? answered(2, 'NEEDED') : f)),
      });
    });
    await waitFor(() => expect(rowOf(result, 2).ownerChoice).toBe('NEEDED'));
  });

  it('shows the last click when two saves of one film surround a refresh', async () => {
    const { gets, puts } = overlapping();
    const { result, rerender } = await loadedFirst();
    await save(result, puts, 0, 2, 'NEEDED');

    rerender({ key: 'b' });
    await waitFor(() => expect(gets).toHaveLength(1));
    await save(result, puts, 1, 2, 'NOT_NEEDED');
    await act(async () => {
      gets[0](LIST);
    });

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(rowOf(result, 2).ownerChoice).toBe('NOT_NEEDED');
  });

  const refreshed = {
    ...LIST,
    films: LIST.films.map((f) => (f.filmId === 2 ? { ...f, hasForcedSubtitle: true } : f)),
  };

  it('lets a refresh that landed between two writes win over the first write’s older row, and shows the failure', async () => {
    const { gets, puts } = overlapping();
    const { result, rerender } = await loadedFirst();
    let first;
    act(() => {
      first = result.current.choose(2, 'NEEDED');
      result.current.choose(2, 'NOT_NEEDED');
    });
    await act(async () => {
      puts[0].resolve(answered(2, 'NEEDED'));
      await Promise.resolve();
    });
    await waitFor(() => expect(puts).toHaveLength(2));

    rerender({ key: 'b' });
    await waitFor(() => expect(gets).toHaveLength(1));
    await act(async () => {
      gets[0](refreshed);
    });
    await waitFor(() => expect(rowOf(result, 2).hasForcedSubtitle).toBe(true));
    await act(async () => {
      puts[1].reject(new Error('offline'));
      await first;
    });

    expect(rowOf(result, 2)).toMatchObject({ hasForcedSubtitle: true, ownerChoice: null });
    expect(result.current.problems).toEqual({ 2: "Couldn't save: try again" });
  });

  it('lets the first write’s answer win when the refresh resolved before it arrived', async () => {
    const { gets, puts } = overlapping();
    const { result, rerender } = await loadedFirst();
    let first;
    act(() => {
      first = result.current.choose(2, 'NEEDED');
      result.current.choose(2, 'NOT_NEEDED');
    });
    rerender({ key: 'b' });
    await waitFor(() => expect(gets).toHaveLength(1));
    await act(async () => {
      gets[0](refreshed);
    });
    await act(async () => {
      puts[0].resolve(answered(2, 'NEEDED'));
      await Promise.resolve();
    });
    await waitFor(() => expect(puts).toHaveLength(2));
    await act(async () => {
      puts[1].reject(new Error('offline'));
      await first;
    });

    expect(rowOf(result, 2).ownerChoice).toBe('NEEDED');
    expect(result.current.problems).toEqual({ 2: "Couldn't save: try again" });
  });
});
