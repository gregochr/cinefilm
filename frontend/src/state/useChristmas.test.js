import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { SIGNED_IN_EVENT } from '../api/client.js';
import { christmasFixture } from '../test/christmasFixture.js';
import { useChristmas } from './useChristmas.js';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

const reply = (status, body) =>
  new Response(body === undefined ? null : JSON.stringify(body), { status });

/** A promise a test settles when it chooses. */
function deferred() {
  let resolve;
  const promise = new Promise((r) => {
    resolve = r;
  });
  return { promise, resolve };
}

const reads = (fetchMock) => fetchMock.mock.calls.length;

describe('useChristmas: reading', () => {
  it('loads the body and stops loading', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => reply(200, christmasFixture())),
    );
    const { result } = renderHook(() => useChristmas());
    expect(result.current.loading).toBe(true);
    expect(result.current.data).toBeNull();
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.data.inWeek).toHaveLength(14);
    expect(result.current.error).toBeNull();
  });

  it('reports a failure, and reload asks again and clears it', async () => {
    let status = 500;
    const fetchMock = vi.fn(async () =>
      status === 200 ? reply(200, christmasFixture()) : reply(status, null),
    );
    vi.stubGlobal('fetch', fetchMock);
    const { result } = renderHook(() => useChristmas());
    await waitFor(() => expect(result.current.error).not.toBeNull());
    expect(result.current.data).toBeNull();

    status = 200;
    act(() => result.current.reload());
    // The failure stays until the new answer replaces it, with loading shown meanwhile.
    expect(result.current.loading).toBe(true);
    expect(result.current.error).not.toBeNull();
    await waitFor(() => expect(result.current.data).not.toBeNull());
    expect(result.current.error).toBeNull();
    expect(reads(fetchMock)).toBe(2);
  });

  it('shows only the newest read: an older answer that lands later is dropped', async () => {
    const first = deferred();
    const second = deferred();
    const answers = [first.promise, second.promise];
    vi.stubGlobal(
      'fetch',
      vi.fn(() => answers.shift()),
    );
    const { result } = renderHook(() => useChristmas());
    act(() => result.current.reload());

    const newer = christmasFixture();
    newer.inWeek = newer.inWeek.slice(0, 5);
    await act(async () => second.resolve(reply(200, newer)));
    await waitFor(() => expect(result.current.data.inWeek).toHaveLength(5));
    await act(async () => first.resolve(reply(200, christmasFixture())));
    expect(result.current.data.inWeek).toHaveLength(5);
  });
});

describe('useChristmas: signing in', () => {
  it('reads again when a sign-in succeeds, and stops listening on unmount', async () => {
    let status = 401;
    const fetchMock = vi.fn(async () =>
      status === 401 ? reply(401, null) : reply(200, christmasFixture()),
    );
    vi.stubGlobal('fetch', fetchMock);
    const { result, unmount } = renderHook(() => useChristmas());
    await waitFor(() => expect(result.current.error).not.toBeNull());

    status = 200;
    act(() => {
      window.dispatchEvent(new CustomEvent(SIGNED_IN_EVENT));
    });
    await waitFor(() => expect(result.current.data).not.toBeNull());
    expect(result.current.error).toBeNull();
    expect(reads(fetchMock)).toBe(2);

    unmount();
    window.dispatchEvent(new CustomEvent(SIGNED_IN_EVENT));
    expect(reads(fetchMock)).toBe(2);
  });
});

describe('useChristmas: polling', () => {
  const running = () => ({
    ...christmasFixture(),
    keywords: { fetched: 1, total: 1412, running: true },
  });

  it('asks again every 30 seconds while keywords are running, and takes the new answer', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    let body = running();
    const fetchMock = vi.fn(async () => reply(200, body));
    vi.stubGlobal('fetch', fetchMock);
    const { result } = renderHook(() => useChristmas());
    await waitFor(() => expect(result.current.data).not.toBeNull());
    expect(reads(fetchMock)).toBe(1);

    body = { ...body, keywords: { fetched: 900, total: 1412, running: true } };
    await act(async () => vi.advanceTimersByTime(29000));
    expect(reads(fetchMock)).toBe(1);
    await act(async () => vi.advanceTimersByTime(1500));
    await waitFor(() => expect(result.current.data.keywords.fetched).toBe(900));
    expect(reads(fetchMock)).toBe(2);
    expect(result.current.loading).toBe(false);
  });

  it('stops once keywords stop running', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    let body = running();
    const fetchMock = vi.fn(async () => reply(200, body));
    vi.stubGlobal('fetch', fetchMock);
    const { result } = renderHook(() => useChristmas());
    await waitFor(() => expect(result.current.data).not.toBeNull());
    body = { ...body, keywords: { fetched: 1412, total: 1412, running: false } };
    await act(async () => vi.advanceTimersByTime(30500));
    await waitFor(() => expect(result.current.data.keywords.running).toBe(false));
    const after = reads(fetchMock);
    await act(async () => vi.advanceTimersByTime(120000));
    expect(reads(fetchMock)).toBe(after);
  });

  it('does not poll when keywords are not running, and stops on unmount', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const idle = vi.fn(async () => reply(200, christmasFixture()));
    vi.stubGlobal('fetch', idle);
    const quiet = renderHook(() => useChristmas());
    await waitFor(() => expect(quiet.result.current.data).not.toBeNull());
    await act(async () => vi.advanceTimersByTime(120000));
    expect(reads(idle)).toBe(1);

    const live = vi.fn(async () => reply(200, running()));
    vi.stubGlobal('fetch', live);
    const polled = renderHook(() => useChristmas());
    await waitFor(() => expect(polled.result.current.data).not.toBeNull());
    polled.unmount();
    await act(async () => vi.advanceTimersByTime(120000));
    expect(reads(live)).toBe(1);
  });

  it('keeps the list and shows no error when a poll fails', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    let fail = false;
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => (fail ? reply(500, null) : reply(200, running()))),
    );
    const { result } = renderHook(() => useChristmas());
    await waitFor(() => expect(result.current.data).not.toBeNull());
    fail = true;
    await act(async () => vi.advanceTimersByTime(30500));
    expect(result.current.data.inWeek).toHaveLength(14);
    expect(result.current.error).toBeNull();
  });
});

describe('useChristmas: changes', () => {
  const rowOf = (data, tmdbId) => data.inWeek.find((r) => r.tmdbId === tmdbId);

  /** Serves GET with `body` and PUT with `put(tmdbId, choice)`. */
  function serve(put, body = christmasFixture()) {
    const fetchMock = vi.fn(async (url, init) => {
      if (init.method === 'GET') return reply(200, typeof body === 'function' ? body() : body);
      return put(Number(url.split('/').pop()), JSON.parse(init.body).choice);
    });
    vi.stubGlobal('fetch', fetchMock);
    return fetchMock;
  }
  const puts = (fetchMock) => fetchMock.mock.calls.filter(([, init]) => init.method === 'PUT');
  const gets = (fetchMock) => fetchMock.mock.calls.filter(([, init]) => init.method === 'GET');

  async function ready() {
    const hook = renderHook(() => useChristmas());
    await waitFor(() => expect(hook.result.current.data).not.toBeNull());
    return hook;
  }

  it('leaves a film out: sends EXCLUDE, takes the answer, toasts with an Undo, asks to move focus on from that row', async () => {
    const out = christmasFixture();
    out.inWeek = out.inWeek.filter((r) => r.tmdbId !== 106);
    const fetchMock = serve(() => reply(200, out));
    const { result } = await ready();

    const row = rowOf(result.current.data, 106);
    await act(async () => result.current.change(row));

    const [url, init] = puts(fetchMock)[0];
    expect(url).toBe('/api/admin/christmas/106');
    expect(init.headers['X-CineFilm']).toBe('1');
    expect(JSON.parse(init.body)).toEqual({ choice: 'EXCLUDE' });
    expect(result.current.data.inWeek).toHaveLength(13);
    expect(result.current.toast).toMatchObject({
      message: "Left out Die Hard. It won't show at Christmas.",
      undo: { tmdbId: 106, title: 'Die Hard', choice: null, kind: 'leave' },
    });
    expect(result.current.focusRequest).toMatchObject({ filmId: row.filmId, undo: true });
    expect(result.current.busy.size).toBe(0);
  });

  it('removes an added film and puts back a left-out one, each with the right previous choice', async () => {
    const fetchMock = serve(() => reply(200, christmasFixture()));
    const { result } = await ready();

    await act(async () => result.current.change(rowOf(result.current.data, 104)));
    expect(JSON.parse(puts(fetchMock)[0][1].body)).toEqual({ choice: null });
    expect(result.current.toast.message).toBe('Removed Trading Places.');
    expect(result.current.toast.undo.choice).toBe('INCLUDE');

    await act(async () => result.current.change(result.current.data.leftOut[0]));
    expect(result.current.toast.message).toBe("Put back Iron Man 3. It'll show at Christmas.");
    expect(result.current.toast.undo.choice).toBe('EXCLUDE');
  });

  it('undoes a Leave out with INCLUDE when a film sharing the TMDB id was included by the owner', async () => {
    const body = christmasFixture();
    // A tagged film with the same TMDB id as the owner's included Trading Places (104).
    const tagged = {
      filmId: 99,
      tmdbId: 104,
      title: 'Trading Places (TV)',
      year: 1983,
      reason: 'KEYWORD',
    };
    body.inWeek.push(tagged);
    const fetchMock = serve(() => reply(200, body), body);
    const { result } = await ready();

    await act(async () => result.current.change(tagged));
    expect(result.current.toast.undo).toMatchObject({ tmdbId: 104, filmId: 99, choice: 'INCLUDE' });

    await act(async () => result.current.undo(result.current.toast.undo));
    expect(JSON.parse(puts(fetchMock)[1][1].body)).toEqual({ choice: 'INCLUDE' });
    // Focus goes back to the clicked film, not to the sibling that shares its TMDB id.
    expect(result.current.focusRequest).toMatchObject({ filmId: 99, undo: false });
  });

  it('undoes with the previous choice, says the opposite without an Undo, and asks for that row focus', async () => {
    const fetchMock = serve(() => reply(200, christmasFixture()));
    const { result } = await ready();
    await act(async () => result.current.change(rowOf(result.current.data, 106)));

    await act(async () => result.current.undo(result.current.toast.undo));

    expect(JSON.parse(puts(fetchMock)[1][1].body)).toEqual({ choice: null });
    expect(result.current.toast).toMatchObject({
      message: "Put back Die Hard. It'll show at Christmas.",
      undo: null,
    });
    expect(result.current.focusRequest).toMatchObject({ filmId: 6, undo: false });
  });

  it('changes a search result: Add sends INCLUDE, toasts with an Undo, and keeps focus on the result', async () => {
    const fetchMock = serve(() => reply(200, christmasFixture()));
    const { result } = await ready();
    await act(async () =>
      result.current.changeResult({ filmId: 50, tmdbId: 150, title: 'Batman', state: 'NOT_IN' }),
    );
    expect(puts(fetchMock)[0][0]).toBe('/api/admin/christmas/150');
    expect(JSON.parse(puts(fetchMock)[0][1].body)).toEqual({ choice: 'INCLUDE' });
    expect(result.current.toast).toMatchObject({
      message: 'Added Batman.',
      undo: { tmdbId: 150, filmId: 50, choice: null, kind: 'add', panel: true },
    });
    // Not onto the toast's Undo: the panel's button keeps focus.
    expect(result.current.focusRequest).toMatchObject({ filmId: 50, undo: false, panel: true });

    await act(async () => result.current.undo(result.current.toast.undo));
    expect(JSON.parse(puts(fetchMock)[1][1].body)).toEqual({ choice: null });
    expect(result.current.toast.message).toBe('Removed Batman.');
    // An Undo of a change made in the panel asks for focus on that result too.
    expect(result.current.focusRequest).toMatchObject({ filmId: 50, panel: true });
  });

  it('asks for focus on the result when an Undo made from the panel fails', async () => {
    let fail = false;
    serve(() => (fail ? reply(409, null) : reply(200, christmasFixture())));
    const { result } = await ready();
    await act(async () =>
      result.current.changeResult({ filmId: 50, tmdbId: 150, title: 'Batman', state: 'NOT_IN' }),
    );
    fail = true;
    await act(async () => result.current.undo(result.current.toast.undo));
    expect(result.current.focusRequest).toMatchObject({ filmId: 50, undo: false, panel: true });
  });

  it('stamps the data with its request number, a write half a step later, and refresh reads quietly', async () => {
    const fetchMock = serve(() => reply(200, christmasFixture()));
    const { result } = await ready();
    expect(result.current.version).toBe(1);
    expect(result.current.issued()).toBe(1);
    await act(async () => result.current.refresh());
    await waitFor(() => expect(result.current.version).toBe(2));
    expect(result.current.loading).toBe(false);
    expect(gets(fetchMock)).toHaveLength(2);
    await act(async () =>
      result.current.changeResult({ filmId: 50, tmdbId: 150, title: 'B', state: 'NOT_IN' }),
    );
    expect(result.current.version).toBe(3.5);
    // A question asked now is not older than the write's answer; one asked while it was in flight was.
    expect(result.current.issued()).toBe(3.5);
  });

  it('stamps a question asked while a write is in flight with the request, below the write answer', async () => {
    const gate = deferred();
    serve(() => gate.promise);
    const { result } = await ready();
    act(() => {
      result.current.changeResult({ filmId: 50, tmdbId: 150, title: 'B', state: 'NOT_IN' });
    });
    await waitFor(() => expect(result.current.busy.has(150)).toBe(true));
    expect(result.current.issued()).toBe(2);
    await act(async () => gate.resolve(reply(200, christmasFixture())));
    expect(result.current.version).toBe(2.5);
    expect(result.current.issued()).toBe(2.5);
  });

  it('reads again when a read was dropped for a write that then fails', async () => {
    const getGate = deferred();
    const putGate = deferred();
    let reads = 0;
    vi.stubGlobal(
      'fetch',
      vi.fn((url, init) => {
        if (init.method === 'PUT') return putGate.promise;
        reads += 1;
        return reads === 2 ? getGate.promise : Promise.resolve(reply(200, christmasFixture()));
      }),
    );
    const { result } = await ready();
    act(() => result.current.refresh());
    act(() => {
      result.current.changeResult({ filmId: 50, tmdbId: 150, title: 'B', state: 'NOT_IN' });
    });
    await waitFor(() => expect(result.current.busy.has(150)).toBe(true));
    // The read lands after the write began: it is dropped, and the write then fails.
    await act(async () => getGate.resolve(reply(200, christmasFixture())));
    await act(async () => putGate.resolve(reply(500, null)));
    await waitFor(() => expect(reads).toBe(3));
  });

  it('stamps a poll that lands late with when it was asked, not when it landed', async () => {
    const gate = deferred();
    let reads = 0;
    vi.stubGlobal(
      'fetch',
      vi.fn(() => {
        reads += 1;
        return reads === 2 ? gate.promise : Promise.resolve(reply(200, christmasFixture()));
      }),
    );
    const { result } = await ready();
    act(() => result.current.refresh());
    // A question about films asked now is stamped with the newest request, the slow read's.
    expect(result.current.issued()).toBe(2);
    await act(async () => gate.resolve(reply(200, christmasFixture())));
    await waitFor(() => expect(result.current.version).toBe(2));
    expect(result.current.issued()).toBe(2);
  });

  it('does not start the clock on unpinning while the toast is held', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    serve(() => reply(200, christmasFixture()));
    const { result } = await ready();
    act(() => result.current.pinToast(true));
    await act(async () => result.current.change(rowOf(result.current.data, 106)));
    act(() => result.current.holdToast());
    act(() => result.current.pinToast(false));
    await act(async () => vi.advanceTimersByTime(60000));
    expect(result.current.toast).not.toBeNull();
    act(() => result.current.releaseToast());
    await act(async () => vi.advanceTimersByTime(8100));
    expect(result.current.toast).toBeNull();
  });

  it('keeps the toast up while pinned, and gives it its 8 seconds once released', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    serve(() => reply(200, christmasFixture()));
    const { result } = await ready();
    act(() => result.current.pinToast(true));
    await act(async () => result.current.change(rowOf(result.current.data, 106)));
    await act(async () => vi.advanceTimersByTime(60000));
    expect(result.current.toast).not.toBeNull();
    // A pointer leaving the toast does not start the clock while pinned.
    act(() => result.current.releaseToast());
    await act(async () => vi.advanceTimersByTime(60000));
    expect(result.current.toast).not.toBeNull();
    act(() => result.current.pinToast(false));
    await act(async () => vi.advanceTimersByTime(7900));
    expect(result.current.toast).not.toBeNull();
    await act(async () => vi.advanceTimersByTime(200));
    expect(result.current.toast).toBeNull();
  });

  it('removes an added film from a result, and sends nothing for a film with no TMDB id', async () => {
    const fetchMock = serve(() => reply(200, christmasFixture()));
    const { result } = await ready();
    await act(async () =>
      result.current.changeResult({
        filmId: 4,
        tmdbId: 104,
        title: 'Trading Places',
        state: 'IN_ADDED',
      }),
    );
    expect(JSON.parse(puts(fetchMock)[0][1].body)).toEqual({ choice: null });
    expect(result.current.toast).toMatchObject({ undo: { choice: 'INCLUDE' } });

    await act(async () =>
      result.current.changeResult({
        filmId: 60,
        tmdbId: null,
        title: 'Mystery',
        state: 'NO_TMDB_ID',
      }),
    );
    expect(puts(fetchMock)).toHaveLength(1);
  });

  it('holds a result while its request is in flight, like a row', async () => {
    const gate = deferred();
    const fetchMock = serve(() => gate.promise);
    const { result } = await ready();
    const film = { filmId: 50, tmdbId: 150, title: 'Batman', state: 'NOT_IN' };
    act(() => {
      result.current.changeResult(film);
    });
    await waitFor(() => expect(result.current.busy.has(150)).toBe(true));
    await act(async () => result.current.changeResult(film));
    expect(puts(fetchMock)).toHaveLength(1);
    await act(async () => gate.resolve(reply(200, christmasFixture())));
  });

  it('holds a row while its request is in flight: a second change sends nothing', async () => {
    const gate = deferred();
    const fetchMock = serve(() => gate.promise);
    const { result } = await ready();
    const row = rowOf(result.current.data, 106);

    act(() => {
      result.current.change(row);
    });
    await waitFor(() => expect(result.current.busy.has(106)).toBe(true));
    await act(async () => result.current.change(row));
    expect(puts(fetchMock)).toHaveLength(1);

    await act(async () => gate.resolve(reply(200, christmasFixture())));
    expect(result.current.busy.size).toBe(0);
  });

  it('shows the server words for a refusal and changes nothing', async () => {
    serve(() => reply(409, { error: 'A scan is running — try again when it finishes' }));
    const { result } = await ready();
    await act(async () => result.current.change(rowOf(result.current.data, 106)));
    expect(result.current.toast).toMatchObject({
      message: 'A scan is running — try again when it finishes',
      undo: null,
    });
    expect(result.current.data.inWeek).toHaveLength(14);
    expect(result.current.busy.size).toBe(0);
    // A failed Undo replaces the toast its button was in, so the screen is asked to settle focus.
    expect(result.current.focusRequest).toEqual({ filmId: null, undo: false, panel: false, n: 1 });
  });

  it('says the scan is running for a bare 409, and a plain failure for anything else', async () => {
    let status = 409;
    serve(() => reply(status, null));
    const { result } = await ready();
    await act(async () => result.current.change(rowOf(result.current.data, 106)));
    expect(result.current.toast.message).toBe('A scan is running — try again when it finishes');
    status = 500;
    await act(async () => result.current.change(rowOf(result.current.data, 106)));
    expect(result.current.toast.message).toBe("Couldn't save that change. Nothing was changed.");
  });

  it('says a plain failure when the request never reaches the server', async () => {
    serve(() => Promise.reject(new TypeError('Failed to fetch')));
    const { result } = await ready();
    await act(async () => result.current.change(rowOf(result.current.data, 106)));
    expect(result.current.toast.message).toBe("Couldn't save that change. Nothing was changed.");
  });

  it('drops the older of two answers, then reads again so a change the newest answer lacks comes back', async () => {
    const slow = deferred();
    const base = christmasFixture();
    const without = (data, ...ids) => ({
      ...data,
      inWeek: data.inWeek.filter((r) => !ids.includes(r.tmdbId)),
    });
    let truth = base;
    const fetchMock = serve(
      (id) => (id === 106 ? slow.promise : reply(200, without(base, 105))),
      () => truth,
    );
    const { result } = await ready();
    const data = result.current.data;

    act(() => {
      result.current.change(rowOf(data, 106));
    });
    await act(async () => result.current.change(rowOf(data, 105)));
    // The server handled Gremlins first: its answer lacks Die Hard's change.
    expect(rowOf(result.current.data, 106)).toBeDefined();

    truth = without(base, 105, 106);
    await act(async () => slow.resolve(reply(200, without(base, 106))));
    await waitFor(() => expect(result.current.data.inWeek).toHaveLength(12));
    expect(rowOf(result.current.data, 106)).toBeUndefined();
    expect(rowOf(result.current.data, 105)).toBeUndefined();
    expect(result.current.toast.message).toMatch(/^Left out Die Hard/);
    expect(result.current.busy.size).toBe(0);
    expect(gets(fetchMock)).toHaveLength(2);
  });

  it('waits for the last write before reading again: no read while one is still in flight, then exactly one', async () => {
    const first = deferred();
    const second = deferred();
    const base = christmasFixture();
    const without = (data, ...ids) => ({
      ...data,
      inWeek: data.inWeek.filter((r) => !ids.includes(r.tmdbId)),
    });
    let truth = base;
    const fetchMock = serve(
      (id) => (id === 106 ? first.promise : second.promise),
      () => truth,
    );
    const { result } = await ready();
    const data = result.current.data;

    act(() => {
      result.current.change(rowOf(data, 106));
    });
    act(() => {
      result.current.change(rowOf(data, 105));
    });
    await waitFor(() => expect(result.current.busy.size).toBe(2));

    // The older write answers first and is dropped as stale; the newer one is still pending: no read yet.
    await act(async () => first.resolve(reply(200, without(base, 106))));
    await waitFor(() => expect(result.current.busy.has(106)).toBe(false));
    expect(gets(fetchMock)).toHaveLength(1);

    truth = without(base, 105, 106);
    await act(async () => second.resolve(reply(200, without(base, 105, 106))));
    await waitFor(() => expect(gets(fetchMock)).toHaveLength(2));
    expect(rowOf(result.current.data, 105)).toBeUndefined();
    expect(rowOf(result.current.data, 106)).toBeUndefined();
    expect(result.current.busy.size).toBe(0);
  });

  it('does not read again after two changes whose answers landed in order', async () => {
    const fetchMock = serve(() => reply(200, christmasFixture()));
    const { result } = await ready();
    const data = result.current.data;
    await act(async () => result.current.change(rowOf(data, 106)));
    await act(async () => result.current.change(rowOf(data, 105)));
    expect(gets(fetchMock)).toHaveLength(1);
  });

  it('holds the toast while it is held and restarts the 8 seconds on release', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    serve(() => reply(200, christmasFixture()));
    const { result } = await ready();
    await act(async () => result.current.change(rowOf(result.current.data, 106)));
    await act(async () => vi.advanceTimersByTime(5000));
    act(() => result.current.holdToast());
    await act(async () => vi.advanceTimersByTime(60000));
    expect(result.current.toast).not.toBeNull();
    act(() => result.current.releaseToast());
    await act(async () => vi.advanceTimersByTime(7900));
    expect(result.current.toast).not.toBeNull();
    await act(async () => vi.advanceTimersByTime(200));
    expect(result.current.toast).toBeNull();
  });

  it('clears the toast after 8 seconds, and a newer one restarts the wait', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    serve(() => reply(200, christmasFixture()));
    const { result } = await ready();
    await act(async () => result.current.change(rowOf(result.current.data, 106)));
    await act(async () => vi.advanceTimersByTime(5000));
    await act(async () => result.current.change(rowOf(result.current.data, 105)));
    expect(result.current.toast.message).toMatch(/^Left out Gremlins/);
    await act(async () => vi.advanceTimersByTime(7900));
    expect(result.current.toast).not.toBeNull();
    await act(async () => vi.advanceTimersByTime(200));
    expect(result.current.toast).toBeNull();
  });

  it('does not poll while a change is in flight', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const gate = deferred();
    const body = christmasFixture();
    body.keywords = { fetched: 1, total: 1412, running: true };
    const fetchMock = serve(() => gate.promise, body);
    const { result } = await ready();
    act(() => {
      result.current.change(rowOf(result.current.data, 106));
    });
    await act(async () => vi.advanceTimersByTime(65000));
    expect(gets(fetchMock)).toHaveLength(1);
    await act(async () => gate.resolve(reply(200, body)));
  });

  it('arms no toast timer when a change answers, or fails, after unmount', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const gate = deferred();
    serve(() => gate.promise);
    const first = await ready();
    act(() => {
      first.result.current.change(rowOf(first.result.current.data, 106));
    });
    first.unmount();
    await act(async () => gate.resolve(reply(200, christmasFixture())));
    expect(vi.getTimerCount()).toBe(0);

    const failing = deferred();
    serve(() => failing.promise);
    const second = await ready();
    act(() => {
      second.result.current.change(rowOf(second.result.current.data, 106));
    });
    second.unmount();
    await act(async () => failing.resolve(reply(500, null)));
    expect(vi.getTimerCount()).toBe(0);
  });
});
