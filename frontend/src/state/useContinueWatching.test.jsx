import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { installFetch, respond } from '../test/mockApi.jsx';
import { UNDO_MS, useContinueWatching } from './useContinueWatching.js';

const card = (id) => ({
  id,
  title: `Film ${id}`,
  year: 2000 + id,
  progress: { positionMs: 1000 * id, runtimeMs: 100000, editionId: id * 10 },
});
const HERO = card(1);
const A = card(2);
const B = card(3);
const C = card(4);
const DATA = { hero: HERO, continueWatching: [A, B, C] };

const PREVIOUS = {
  positionMs: 20000,
  watched: true,
  watchedAt: '2024-01-01T00:00:00Z',
  hiddenFromContinueWatching: true,
  hiddenAt: '2024-03-01T00:00:00Z',
};

const ids = (list) => list.map((f) => f.id);
const bodyOf = (call) => JSON.parse(call[1].body);
const callsTo = (fetchMock, path) => fetchMock.mock.calls.filter((c) => c[0] === path);

function setup(data = DATA, route) {
  const reload = vi.fn();
  const fetchMock = installFetch(route);
  const hook = renderHook(({ d }) => useContinueWatching(d, reload), {
    initialProps: { d: data },
  });
  return { ...hook, reload, fetchMock };
}

/** A fetch route whose response for `path` is held until `release(response)` is called. */
function held(path) {
  let release;
  const gate = new Promise((resolve) => {
    release = resolve;
  });
  return {
    route: (method, url) => (url === path ? gate : null),
    release,
  };
}

beforeEach(() => vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] }));
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

const flush = () => act(async () => {});

describe('useContinueWatching: Remove', () => {
  it('drops the card at once, calls the API straight away and offers Undo', async () => {
    const { result, fetchMock } = setup();
    expect(ids(result.current.row)).toEqual([2, 3, 4]);

    act(() => result.current.remove(B));
    await flush();

    expect(ids(result.current.row)).toEqual([2, 4]);
    expect(result.current.action).toMatchObject({ kind: 'remove', film: B, index: 1 });
    const [call] = callsTo(fetchMock, '/api/editions/30/continue-watching');
    expect(call[1].method).toBe('PUT');
    expect(bodyOf(call)).toEqual({ hidden: true });
  });

  it('Undo puts the film back at its original index and reverses the call', async () => {
    const { result, fetchMock } = setup();
    act(() => result.current.remove(B));
    await flush();

    await act(async () => {
      await result.current.undo();
    });

    expect(ids(result.current.row)).toEqual([2, 3, 4]);
    expect(result.current.action).toBeNull();
    const calls = callsTo(fetchMock, '/api/editions/30/continue-watching');
    expect(calls.map(bodyOf)).toEqual([{ hidden: true }, { hidden: false }]);
  });

  it('restores the card at the end when the row has shrunk below its old index', async () => {
    const { result } = setup();
    act(() => result.current.remove(C));
    await flush();
    act(() => result.current.remove(B));
    await flush();
    act(() => result.current.remove(A));
    await flush();
    expect(result.current.row).toEqual([]);

    await act(async () => {
      await result.current.undo();
    });

    expect(ids(result.current.row)).toEqual([2]);
  });

  it('puts the card back and drops the toast when the API call fails', async () => {
    const { result } = setup(DATA, (m, url) =>
      url === '/api/editions/30/continue-watching' ? respond({ error: 'x' }, 500) : null,
    );

    act(() => result.current.remove(B));
    await flush();

    expect(ids(result.current.row)).toEqual([2, 3, 4]);
    expect(result.current.action).toBeNull();
  });

  it('a failure of an earlier action restores its card but keeps the later toast', async () => {
    const gate = held('/api/editions/30/continue-watching');
    const { result } = setup(DATA, gate.route);
    act(() => result.current.remove(B));
    act(() => result.current.remove(C));
    expect(result.current.action.film).toBe(C);

    await act(async () => {
      gate.release(respond({ error: 'x' }, 500));
    });

    expect(ids(result.current.row)).toEqual([2, 3]);
    expect(result.current.action.film).toBe(C);
  });

  it('a second removal replaces the toast and only the latest is undone', async () => {
    const { result, fetchMock } = setup();
    act(() => result.current.remove(A));
    await flush();
    act(() => result.current.remove(C));
    await flush();
    expect(result.current.action.film).toBe(C);

    await act(async () => {
      await result.current.undo();
    });

    expect(ids(result.current.row)).toEqual([3, 4]);
    expect(callsTo(fetchMock, '/api/editions/20/continue-watching').map(bodyOf)).toEqual([
      { hidden: true },
    ]);
  });
});

describe('useContinueWatching: Mark as watched', () => {
  it('drops the card, marks the edition watched and Undo restores the exact previous state', async () => {
    const { result, fetchMock } = setup(DATA, (m, url) =>
      url === '/api/editions/30/watched' ? respond({ watched: true, previous: PREVIOUS }) : null,
    );

    act(() => result.current.markWatched(B));
    await flush();

    expect(ids(result.current.row)).toEqual([2, 4]);
    expect(result.current.action).toMatchObject({ kind: 'watched', film: B });
    expect(bodyOf(callsTo(fetchMock, '/api/editions/30/watched')[0])).toEqual({ watched: true });

    await act(async () => {
      await result.current.undo();
    });

    expect(ids(result.current.row)).toEqual([2, 3, 4]);
    const [restore] = callsTo(fetchMock, '/api/editions/30/playback-state');
    expect(restore[1].method).toBe('PUT');
    expect(bodyOf(restore)).toEqual(PREVIOUS);
  });
});

describe('useContinueWatching: the toast timer', () => {
  it('clears the toast after 8 s but the card stays removed', async () => {
    const { result } = setup();
    act(() => result.current.remove(B));
    await flush();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(UNDO_MS - 1);
    });
    expect(result.current.action).not.toBeNull();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1);
    });
    expect(UNDO_MS).toBe(8000);
    expect(result.current.action).toBeNull();
    expect(ids(result.current.row)).toEqual([2, 4]);
  });

  it('a newer action restarts the clock', async () => {
    const { result } = setup();
    act(() => result.current.remove(A));
    await flush();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(6000);
    });
    act(() => result.current.remove(B));
    await flush();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(6000);
    });

    expect(result.current.action.film).toBe(B);
  });

  it('does nothing on Undo once the toast has gone', async () => {
    const { result, fetchMock } = setup();
    act(() => result.current.remove(B));
    await flush();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(UNDO_MS);
    });

    await act(async () => {
      await result.current.undo();
    });

    expect(ids(result.current.row)).toEqual([2, 4]);
    expect(callsTo(fetchMock, '/api/editions/30/continue-watching')).toHaveLength(1);
  });
});

describe('useContinueWatching: Undo while the call is in flight', () => {
  it('waits for the original call before sending the reverse', async () => {
    const gate = held('/api/editions/30/continue-watching');
    const { result, fetchMock } = setup(DATA, gate.route);
    act(() => result.current.remove(B));

    let undone;
    act(() => {
      undone = result.current.undo();
    });
    expect(ids(result.current.row)).toEqual([2, 3, 4]); // the card is back at once
    await flush();
    expect(callsTo(fetchMock, '/api/editions/30/continue-watching')).toHaveLength(1);

    await act(async () => {
      gate.release(respond({ hidden: true }));
      await undone;
    });

    expect(callsTo(fetchMock, '/api/editions/30/continue-watching').map(bodyOf)).toEqual([
      { hidden: true },
      { hidden: false },
    ]);
  });

  it('sends nothing if the original then fails, and the card stays', async () => {
    const gate = held('/api/editions/30/continue-watching');
    const { result, fetchMock } = setup(DATA, gate.route);
    act(() => result.current.remove(B));
    let undone;
    act(() => {
      undone = result.current.undo();
    });

    await act(async () => {
      gate.release(respond({ error: 'x' }, 500));
      await undone;
    });

    expect(callsTo(fetchMock, '/api/editions/30/continue-watching')).toHaveLength(1);
    expect(ids(result.current.row)).toEqual([2, 3, 4]);
  });

  it('reloads the page data when the reverse call cannot be sent', async () => {
    const { result, reload } = setup(DATA, (m, url, init) =>
      url === '/api/editions/30/continue-watching' && init.body === '{"hidden":false}'
        ? respond({ error: 'x' }, 500)
        : null,
    );
    act(() => result.current.remove(B));
    await flush();

    await act(async () => {
      await result.current.undo();
    });

    expect(reload).toHaveBeenCalledTimes(1);
  });
});

describe('useContinueWatching: hero and inputs', () => {
  it('hides the hero while its film is removed and brings it back on Undo', async () => {
    const same = { hero: A, continueWatching: [A, B] };
    const { result } = setup(same);
    expect(result.current.hero).toBe(A);

    act(() => result.current.remove(A));
    await flush();
    expect(result.current.hero).toBeNull();

    await act(async () => {
      await result.current.undo();
    });
    expect(result.current.hero).toBe(A);
  });

  it('puts the hero back when its removal fails', async () => {
    const { result } = setup({ hero: A, continueWatching: [A] }, (m, url) =>
      url === '/api/editions/20/continue-watching' ? respond({ error: 'x' }, 500) : null,
    );

    act(() => result.current.remove(A));
    await flush();

    expect(result.current.hero).toBe(A);
  });

  it('leaves the hero alone when another film is removed', async () => {
    const { result } = setup();
    act(() => result.current.remove(B));
    await flush();
    expect(result.current.hero).toBe(HERO);
  });

  it('ignores a film that is not in the row, or has no edition to act on', async () => {
    const { result, fetchMock } = setup();
    act(() => result.current.remove(card(9)));
    act(() => result.current.markWatched({ ...A, progress: { positionMs: 1, runtimeMs: 2 } }));
    act(() => result.current.markWatched({ ...A, progress: undefined }));
    await flush();

    expect(ids(result.current.row)).toEqual([2, 3, 4]);
    expect(result.current.action).toBeNull();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('does nothing on Undo with nothing to undo', async () => {
    const { result, fetchMock } = setup();
    await act(async () => {
      await result.current.undo();
    });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('follows fresh Home data and copes with no data yet', () => {
    const { result, rerender } = setup(null);
    expect(result.current.row).toEqual([]);
    expect(result.current.hero).toBeNull();

    rerender({ d: DATA });
    expect(ids(result.current.row)).toEqual([2, 3, 4]);
    expect(result.current.hero).toBe(HERO);

    rerender({ d: { hero: null } });
    expect(result.current.row).toEqual([]);
    expect(result.current.hero).toBeNull();
  });
});

describe('useContinueWatching: requests for one edition are serialised', () => {
  /** Every request to `path` waits for `answer(n)`; `sent` lists the bodies in the order they were sent. */
  function sequenced(path) {
    const gates = [];
    const sent = [];
    return {
      sent,
      gates,
      route: (method, url, init) => {
        if (url !== path) return null;
        sent.push(JSON.parse(init.body));
        return new Promise((resolve) => gates.push(resolve));
      },
    };
  }

  it('Remove, Undo, Remove reach the server as true, false, true and the film ends up removed', async () => {
    const seq = sequenced('/api/editions/30/continue-watching');
    const { result } = setup(DATA, seq.route);

    act(() => result.current.remove(B));
    await flush();
    act(() => {
      result.current.undo();
    });
    await flush();
    act(() => result.current.remove(B));
    await flush();

    expect(seq.sent).toEqual([{ hidden: true }]); // the rest wait their turn
    expect(ids(result.current.row)).toEqual([2, 4]);

    await act(async () => seq.gates[0](respond({ hidden: true })));
    expect(seq.sent).toEqual([{ hidden: true }, { hidden: false }]);

    await act(async () => seq.gates[1](respond({ hidden: false })));
    expect(seq.sent).toEqual([{ hidden: true }, { hidden: false }, { hidden: true }]);

    await act(async () => seq.gates[2](respond({ hidden: true })));
    expect(ids(result.current.row)).toEqual([2, 4]);
    expect(result.current.action).toMatchObject({ kind: 'remove', film: B });
  });

  it('a failed request does not stop the ones queued behind it', async () => {
    const seq = sequenced('/api/editions/30/continue-watching');
    const { result } = setup(DATA, seq.route);
    act(() => result.current.remove(B));
    await flush();
    act(() => {
      result.current.undo();
    });
    await flush();

    await act(async () => seq.gates[0](respond({ error: 'x' }, 500)));

    expect(seq.sent).toEqual([{ hidden: true }]); // the original failed: nothing to reverse
    expect(ids(result.current.row)).toEqual([2, 3, 4]);
  });
});

describe('useContinueWatching: watched overrides for the other rows', () => {
  const watchedRoute = (m, url) =>
    url === '/api/editions/30/watched' ? respond({ watched: true, previous: PREVIOUS }) : null;

  it('marks the film watched as soon as it is sent, and drops that on Undo', async () => {
    const { result } = setup(DATA, watchedRoute);
    expect(result.current.watched).toEqual({});

    act(() => result.current.markWatched(B));
    expect(result.current.watched).toEqual({ 3: true });
    await flush();

    await act(async () => {
      await result.current.undo();
    });
    expect(result.current.watched).toEqual({});
  });

  it('drops the override when the request fails', async () => {
    const { result } = setup(DATA, (m, url) =>
      url === '/api/editions/30/watched' ? respond({ error: 'x' }, 500) : null,
    );
    act(() => result.current.markWatched(B));
    await flush();
    expect(result.current.watched).toEqual({});
  });

  it('keeps the overrides of earlier marks, and Remove sets none', async () => {
    const { result } = setup(DATA, watchedRoute);
    act(() => result.current.markWatched(B));
    await flush();
    act(() => result.current.remove(A));
    await flush();
    expect(result.current.watched).toEqual({ 3: true });
  });

  it('forgets them when fresh Home data arrives', async () => {
    const { result, rerender } = setup(DATA, watchedRoute);
    act(() => result.current.markWatched(B));
    await flush();
    rerender({ d: { ...DATA } });
    expect(result.current.watched).toEqual({});
  });
});

describe('useContinueWatching: a stale failure does not roll back a newer action', () => {
  function sequenced(path) {
    const gates = [];
    return {
      gates,
      route: (method, url) => (url === path ? new Promise((resolve) => gates.push(resolve)) : null),
    };
  }

  it('Remove, Undo, Remove, then the first fails: the card stays removed', async () => {
    const seq = sequenced('/api/editions/30/continue-watching');
    const { result } = setup(DATA, seq.route);
    act(() => result.current.remove(B));
    await flush();
    act(() => {
      result.current.undo();
    });
    await flush();
    act(() => result.current.remove(B));
    await flush();

    await act(async () => seq.gates[0](respond({ error: 'x' }, 500)));
    await flush();
    expect(ids(result.current.row)).toEqual([2, 4]);
    expect(result.current.action).toMatchObject({ kind: 'remove', film: B });

    await act(async () => seq.gates[1](respond({ hidden: true })));
    expect(ids(result.current.row)).toEqual([2, 4]);
  });

  it('Mark watched, Undo, Mark watched, then the first fails: the override stays', async () => {
    const seq = sequenced('/api/editions/30/watched');
    const { result } = setup(DATA, seq.route);
    act(() => result.current.markWatched(B));
    await flush();
    act(() => {
      result.current.undo();
    });
    await flush();
    act(() => result.current.markWatched(B));
    await flush();

    await act(async () => seq.gates[0](respond({ error: 'x' }, 500)));
    await flush();

    expect(result.current.watched).toEqual({ 3: true });
    expect(ids(result.current.row)).toEqual([2, 4]);
  });
});

describe('useContinueWatching: the total', () => {
  const MANY = {
    hero: HERO,
    continueWatching: [A, B, C, card(5), card(6)],
    continueWatchingTotal: 91,
  };

  it('takes the server total, and falls back to the list length without one', () => {
    expect(setup(MANY).result.current.total).toBe(91);
    expect(setup(DATA).result.current.total).toBe(3);
    expect(setup(null).result.current.total).toBe(0);
  });

  it('Remove and Mark as watched take one off, and Undo puts it back', async () => {
    const { result } = setup(MANY);
    act(() => result.current.remove(B));
    await flush();
    expect(result.current.total).toBe(90);

    await act(async () => {
      await result.current.undo();
    });
    expect(result.current.total).toBe(91);

    act(() => result.current.markWatched(A));
    await flush();
    expect(result.current.total).toBe(90);
  });

  it('keeps the spares in the list, so the next film moves up and Undo pushes it back out', async () => {
    const { result } = setup(MANY);
    act(() => result.current.remove(B));
    await flush();
    expect(ids(result.current.row)).toEqual([2, 4, 5, 6]);

    await act(async () => {
      await result.current.undo();
    });
    expect(ids(result.current.row)).toEqual([2, 3, 4, 5, 6]);
  });

  it('a failed Remove restores the total as well as the card', async () => {
    const { result } = setup(MANY, (m, url) =>
      url === '/api/editions/30/continue-watching' ? respond({ error: 'x' }, 500) : null,
    );
    act(() => result.current.remove(B));
    await flush();
    expect(result.current.total).toBe(91);
    expect(ids(result.current.row)).toEqual([2, 3, 4, 5, 6]);
  });

  it('counts the film once when a failure meets an Undo that already restored it', async () => {
    const gate = held('/api/editions/30/continue-watching');
    const { result } = setup(MANY, gate.route);
    act(() => result.current.remove(B));
    act(() => {
      result.current.undo();
    });
    await flush();
    expect(result.current.total).toBe(91);

    await act(async () => {
      gate.release(respond({ error: 'x' }, 500));
    });
    expect(result.current.total).toBe(91);
    expect(ids(result.current.row)).toEqual([2, 3, 4, 5, 6]);
  });

  it('follows fresh Home data', () => {
    const { result, rerender } = setup(MANY);
    rerender({ d: { ...MANY, continueWatchingTotal: 7 } });
    expect(result.current.total).toBe(7);
  });
});
