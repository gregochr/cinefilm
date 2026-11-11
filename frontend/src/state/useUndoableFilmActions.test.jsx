import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { installFetch, respond } from '../test/mockApi.jsx';
import { UNDO_MS, useUndoableFilmActions } from './useUndoableFilmActions.js';

const FILM = { id: 3, title: 'Film 3', progress: { editionId: 30 } };
const OTHER = { id: 4, title: 'Film 4', progress: { editionId: 40 } };
const PREVIOUS = { positionMs: 20000, watched: false };

const callsTo = (fetchMock, path) => fetchMock.mock.calls.filter((c) => c[0] === path);
const bodyOf = (call) => JSON.parse(call[1].body);
const flush = () => act(async () => {});

function setup(route, onRollback) {
  const reload = vi.fn();
  const fetchMock = installFetch(route);
  const hook = renderHook(() => useUndoableFilmActions(reload, onRollback));
  return { ...hook, reload, fetchMock };
}

/** Runs `begin` for a film with spies standing in for the caller's local change. */
function start(result, kind, film = FILM) {
  const change = vi.fn();
  const restore = vi.fn();
  let started;
  act(() => {
    started = result.current.begin(kind, film, change, restore, { index: 7 });
  });
  return { change, restore, started };
}

function held(path) {
  let release;
  const gate = new Promise((resolve) => {
    release = resolve;
  });
  return { route: (method, url) => (url === path ? gate : null), release };
}

beforeEach(() => vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] }));
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe('useUndoableFilmActions', () => {
  it('changes the list, calls the API and offers the action with its meta', async () => {
    const { result, fetchMock } = setup();
    const { change, started } = start(result, 'remove');
    await flush();

    expect(started).toBe(true);
    expect(change).toHaveBeenCalledOnce();
    expect(result.current.action).toMatchObject({ kind: 'remove', film: FILM, index: 7 });
    expect(bodyOf(callsTo(fetchMock, '/api/editions/30/continue-watching')[0])).toEqual({
      hidden: true,
    });
  });

  it('marks watched through the watched endpoint', async () => {
    const { result, fetchMock } = setup();
    start(result, 'watched');
    await flush();
    expect(bodyOf(callsTo(fetchMock, '/api/editions/30/watched')[0])).toEqual({ watched: true });
  });

  it('does nothing for a film with no edition', () => {
    const { result, fetchMock } = setup();
    const { change, started } = start(result, 'remove', { id: 9, title: 'No edition' });
    expect(started).toBe(false);
    expect(change).not.toHaveBeenCalled();
    expect(result.current.action).toBeNull();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('lets the Undo offer lapse after the Undo time, leaving the action committed', async () => {
    const { result, fetchMock } = setup();
    const { restore } = start(result, 'remove');
    await flush();
    act(() => vi.advanceTimersByTime(UNDO_MS));
    expect(result.current.action).toBeNull();
    expect(restore).not.toHaveBeenCalled();
    expect(callsTo(fetchMock, '/api/editions/30/continue-watching')).toHaveLength(1);
  });

  it('keeps only the latest action undoable', async () => {
    const { result } = setup();
    start(result, 'remove');
    start(result, 'watched', OTHER);
    await flush();
    expect(result.current.action).toMatchObject({ kind: 'watched', film: OTHER });
  });

  it('rolls back a failed request and tells the caller', async () => {
    const onRollback = vi.fn();
    const { result } = setup(() => respond({}, 500), onRollback);
    const { restore } = start(result, 'remove');
    await flush();
    expect(restore).toHaveBeenCalledOnce();
    expect(onRollback).toHaveBeenCalledWith(FILM);
    expect(result.current.action).toBeNull();
  });

  it('does not roll back a failure when something newer has happened to the film', async () => {
    const onRollback = vi.fn();
    const slow = held('/api/editions/30/continue-watching');
    const { result, reload } = setup(slow.route, onRollback);
    const { restore } = start(result, 'remove');
    await flush();
    act(() => {
      result.current.undo(); // waits behind the request that is still held
    });
    expect(restore).toHaveBeenCalledOnce(); // by Undo
    slow.release(respond({}, 500));
    await flush();
    expect(restore).toHaveBeenCalledOnce();
    expect(onRollback).not.toHaveBeenCalled();
    expect(reload).toHaveBeenCalledOnce(); // local state no longer matches the server: read it again
  });

  it('Undo restores locally and reverses a Remove after the original settled', async () => {
    const { result, fetchMock } = setup();
    const { restore } = start(result, 'remove');
    await flush();
    await act(async () => {
      await result.current.undo();
    });
    expect(restore).toHaveBeenCalledOnce();
    expect(result.current.action).toBeNull();
    expect(callsTo(fetchMock, '/api/editions/30/continue-watching').map(bodyOf)).toEqual([
      { hidden: true },
      { hidden: false },
    ]);
  });

  it('Undo of Mark as watched sends the previous state the server returned', async () => {
    const { result, fetchMock } = setup((method, url) =>
      url === '/api/editions/30/watched' ? respond({ watched: true, previous: PREVIOUS }) : null,
    );
    start(result, 'watched');
    await flush();
    await act(async () => {
      await result.current.undo();
    });
    const [put] = callsTo(fetchMock, '/api/editions/30/playback-state');
    expect(put[1].method).toBe('PUT');
    expect(bodyOf(put)).toEqual(PREVIOUS);
  });

  it('serialises requests for one edition: Remove, Undo, Remove reach the server in order', async () => {
    const slow = held('/api/editions/30/continue-watching');
    const { result, fetchMock } = setup(slow.route);
    start(result, 'remove');
    act(() => {
      result.current.undo();
    });
    start(result, 'remove');
    await flush();
    expect(callsTo(fetchMock, '/api/editions/30/continue-watching')).toHaveLength(1);
    slow.release(respond({ hidden: true }));
    await flush();
    await flush();
    await flush();
    expect(callsTo(fetchMock, '/api/editions/30/continue-watching').map(bodyOf)).toEqual([
      { hidden: true },
      { hidden: false },
      { hidden: true },
    ]);
  });

  it('waits to reload after a superseded failure until the newer request on the edition has settled', async () => {
    const gates = [];
    const { result, reload } = setup((method, url) =>
      url === '/api/editions/30/continue-watching'
        ? new Promise((resolve) => gates.push(resolve))
        : null,
    );
    start(result, 'remove');
    await flush();
    act(() => {
      result.current.undo();
    });
    start(result, 'remove');
    await flush();

    await act(async () => gates[0](respond({}, 500)));
    await flush();
    await flush();
    expect(gates).toHaveLength(2); // the second Remove has started, behind the failed one
    expect(reload).not.toHaveBeenCalled();

    await act(async () => gates[1](respond({ hidden: true })));
    await flush();
    expect(reload).toHaveBeenCalledOnce();
  });

  it('gives one reload for several superseded failures on one edition', async () => {
    const gates = [];
    const { result, reload } = setup((method, url) =>
      url === '/api/editions/30/continue-watching'
        ? new Promise((resolve) => gates.push(resolve))
        : null,
    );
    start(result, 'remove');
    await flush();
    act(() => {
      result.current.undo();
    });
    start(result, 'remove');
    act(() => {
      result.current.undo();
    });
    start(result, 'remove');
    await flush();

    await act(async () => gates[0](respond({}, 500)));
    await flush();
    await act(async () => gates[1](respond({}, 500)));
    await flush();
    await flush();
    expect(reload).not.toHaveBeenCalled();
    await act(async () => gates[2](respond({ hidden: true })));
    await flush();
    expect(reload).toHaveBeenCalledOnce();
  });

  it('reloads when an Undo cannot be sent', async () => {
    const { result, reload } = setup((method, url, init) =>
      url === '/api/editions/30/continue-watching' && init.body?.includes('false')
        ? respond({}, 500)
        : null,
    );
    start(result, 'remove');
    await flush();
    await act(async () => {
      await result.current.undo();
    });
    expect(reload).toHaveBeenCalledOnce();
  });

  it('does nothing on Undo when there is no action', async () => {
    const { result, fetchMock } = setup();
    await act(async () => {
      await result.current.undo();
    });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('uses the newest onRollback, not the one from the first render', async () => {
    const first = vi.fn();
    const second = vi.fn();
    const reload = vi.fn();
    installFetch(() => respond({}, 500));
    const { result, rerender } = renderHook(({ cb }) => useUndoableFilmActions(reload, cb), {
      initialProps: { cb: first },
    });
    rerender({ cb: second });
    start(result, 'remove');
    await flush();
    expect(second).toHaveBeenCalledWith(FILM);
    expect(first).not.toHaveBeenCalled();
  });

  it('clears the Undo timer on unmount', async () => {
    const { result, unmount } = setup();
    start(result, 'remove');
    await flush();
    unmount();
    expect(vi.getTimerCount()).toBe(0);
  });
});
