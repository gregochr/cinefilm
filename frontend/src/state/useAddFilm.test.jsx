import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { prepareIndex } from './searchIndex.js';
import { STATES_DEBOUNCE_MS, useAddFilm } from './useAddFilm.js';

const fetchMock = vi.fn();
const ok = (body) => new Response(JSON.stringify(body), { status: 200 });
/** The film ids each `/states` request asked about. */
const asked = () =>
  fetchMock.mock.calls.map(([url]) => new URL(url, 'http://x').searchParams.get('filmIds'));

const INDEX = prepareIndex({
  films: [
    { id: 1, t: 'Batman', y: 1989 },
    { id: 2, t: 'Batman Returns', y: 1992 },
    { id: 3, t: 'Gremlins', y: 1984 },
  ],
  collections: [],
  people: [],
});

const emptyData = () => ({ inWeek: [], added: [], leftOut: [] });

/** A promise a test settles when it chooses. */
function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

/** The newest request number the screen has issued, which a `/states` question is stamped with. */
let issuedNow = 1;
const issued = () => issuedNow;

function setup(data = emptyData(), extra = {}) {
  return renderHook(
    (props) =>
      useAddFilm({
        index: INDEX,
        status: 'ready',
        idPrefix: 'opt',
        version: 1,
        issued,
        ...props,
      }),
    { initialProps: { data, ...extra } },
  );
}

const settle = () => act(() => vi.advanceTimersByTimeAsync(STATES_DEBOUNCE_MS));

beforeEach(() => {
  issuedNow = 1;
  vi.useFakeTimers();
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe('useAddFilm', () => {
  it('finds film titles from the index, with no state until the answer arrives', () => {
    fetchMock.mockResolvedValue(ok([]));
    const { result } = setup();
    act(() => result.current.setQuery('batman'));
    expect(result.current.rows.map((r) => [r.filmId, r.title, r.year, r.state])).toEqual([
      [1, 'Batman', 1989, null],
      [2, 'Batman Returns', 1992, null],
    ]);
    expect(result.current.total).toBe(2);
    expect(result.current.rows[0].id).toBe('opt-1');
    expect(result.current.state.open).toBe(true);
  });

  it('asks for the matched ids once typing pauses, and shows the states', async () => {
    fetchMock.mockResolvedValue(
      ok([
        { filmId: 1, tmdbId: 101, state: 'NOT_IN' },
        { filmId: 2, tmdbId: null, state: 'NO_TMDB_ID' },
      ]),
    );
    const { result } = setup();
    act(() => result.current.setQuery('b'));
    act(() => result.current.setQuery('batman'));
    expect(fetchMock).not.toHaveBeenCalled();
    await settle();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(asked()).toEqual(['1,2']);
    expect(result.current.rows.map((r) => [r.tmdbId, r.state])).toEqual([
      [101, 'NOT_IN'],
      [null, 'NO_TMDB_ID'],
    ]);
  });

  it('ignores the answer for an older query', async () => {
    const first = deferred();
    fetchMock.mockReturnValueOnce(first.promise);
    fetchMock.mockResolvedValueOnce(ok([{ filmId: 3, tmdbId: 103, state: 'KEYWORDS_PENDING' }]));
    const { result } = setup();
    act(() => result.current.setQuery('batman'));
    await settle();
    act(() => result.current.setQuery('gremlins'));
    await settle();
    // The older answer arrives last, and says something else about film 1.
    await act(async () => first.resolve(ok([{ filmId: 1, tmdbId: 101, state: 'IN_KEYWORD' }])));
    expect(result.current.rows.map((r) => [r.filmId, r.state])).toEqual([[3, 'KEYWORDS_PENDING']]);
    act(() => result.current.setQuery('batman'));
    expect(result.current.rows[0].state).toBeNull();
  });

  it('shows a film in the screen lists as that, and a later older answer cannot undo it', async () => {
    fetchMock.mockResolvedValue(
      ok([
        { filmId: 1, tmdbId: 101, state: 'NOT_IN' },
        { filmId: 2, tmdbId: 102, state: 'IN_ADDED' },
      ]),
    );
    const { result, rerender } = setup({
      inWeek: [{ filmId: 2, tmdbId: 102, reason: 'ADDED' }],
      added: [],
      leftOut: [],
    });
    act(() => result.current.setQuery('batman'));
    await settle();
    expect(result.current.rows.map((r) => r.state)).toEqual(['NOT_IN', 'IN_ADDED']);

    // Film 1 was added and film 2 removed: the lists say so, the answer still says what it said.
    rerender({
      data: { inWeek: [{ filmId: 1, tmdbId: 101, reason: 'ADDED' }], added: [], leftOut: [] },
      version: 2,
    });
    expect(result.current.rows.map((r) => r.state)).toEqual(['IN_ADDED', 'NOT_IN']);
    expect(result.current.rows[0].tmdbId).toBe(101);
  });

  it('believes an answer asked after the data was, until data asked for later lacks the film', async () => {
    fetchMock.mockResolvedValue(ok([{ filmId: 1, tmdbId: 101, state: 'IN_KEYWORD' }]));
    const { result, rerender } = setup();
    act(() => result.current.setQuery('batman'));
    await settle();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(result.current.rows[0].state).toBe('IN_KEYWORD');

    // A poll that was asked before the question lands now: the question is not older than it, so the answer stands.
    rerender({ data: emptyData(), version: 1 });
    expect(result.current.rows[0].state).toBe('IN_KEYWORD');
    // A read asked for after the question, without the film: the answer no longer holds.
    rerender({ data: emptyData(), version: 2 });
    expect(result.current.rows[0].state).toBe('NOT_IN');
  });

  it('stamps an answer with the request number when it was asked, not when it landed', async () => {
    const gate = deferred();
    fetchMock.mockReturnValueOnce(gate.promise);
    const { result, rerender } = setup();
    act(() => result.current.setQuery('batman'));
    await settle();
    // Asked at 1; a write (answered as 2.5) lands, then the answer does.
    issuedNow = 3;
    rerender({ data: emptyData(), version: 2.5 });
    await act(async () => gate.resolve(ok([{ filmId: 1, tmdbId: 101, state: 'IN_ADDED' }])));
    expect(result.current.rows[0].state).toBe('NOT_IN');
  });

  it('trusts an answer asked after a write landed, and not one asked while it was in flight', async () => {
    fetchMock.mockImplementation(async () => ok([{ filmId: 1, tmdbId: 101, state: 'IN_ADDED' }]));
    // Asked while the write (request 2, answered as 2.5) was in flight: older than its answer.
    issuedNow = 2;
    const first = setup(emptyData(), { version: 2.5 });
    act(() => first.result.current.setQuery('batman'));
    await settle();
    expect(first.result.current.rows[0].state).toBe('NOT_IN');
    first.unmount();

    // Asked after the answer landed (the stamp is then 2.5): the film really is in.
    issuedNow = 2.5;
    const second = setup(emptyData(), { version: 2.5 });
    act(() => second.result.current.setQuery('batman'));
    await settle();
    expect(second.result.current.rows[0].state).toBe('IN_ADDED');
  });

  it('takes the TMDB id of a listed film before any answer', () => {
    const { result } = setup({
      inWeek: [],
      added: [],
      leftOut: [{ filmId: 1, tmdbId: 101, title: 'Batman' }],
    });
    act(() => result.current.setQuery('batman'));
    expect(result.current.rows[0]).toMatchObject({ tmdbId: 101, state: 'LEFT_OUT' });
  });

  it('asks again when the refresh key changes', async () => {
    fetchMock.mockResolvedValue(ok([{ filmId: 2, tmdbId: null, state: 'NO_TMDB_ID' }]));
    const { result, rerender } = setup(emptyData(), { refreshKey: 0 });
    act(() => result.current.setQuery('returns'));
    await settle();
    expect(fetchMock).toHaveBeenCalledTimes(1);

    fetchMock.mockResolvedValue(ok([{ filmId: 2, tmdbId: 102, state: 'NOT_IN' }]));
    rerender({ data: emptyData(), refreshKey: 1 });
    await settle();
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(result.current.rows[0]).toMatchObject({ tmdbId: 102, state: 'NOT_IN' });
  });

  it('says so when the states could not be read, and clears it on the next answer', async () => {
    fetchMock.mockRejectedValueOnce(new Error('down'));
    const { result } = setup();
    act(() => result.current.setQuery('gremlins'));
    await settle();
    expect(result.current.statesFailed).toBe(true);
    expect(result.current.rows[0].state).toBeNull();

    fetchMock.mockResolvedValueOnce(ok([{ filmId: 1, tmdbId: 101, state: 'NOT_IN' }]));
    act(() => result.current.setQuery('batman'));
    expect(result.current.statesFailed).toBe(false);
    await settle();
    expect(result.current.statesFailed).toBe(false);
  });

  it('forgets what it knew when a read fails, so the rows do not contradict the failure', async () => {
    fetchMock.mockResolvedValueOnce(ok([{ filmId: 1, tmdbId: 101, state: 'NOT_IN' }]));
    const { result, rerender } = setup(emptyData(), { refreshKey: 0 });
    act(() => result.current.setQuery('batman'));
    await settle();
    expect(result.current.rows[0].state).toBe('NOT_IN');

    fetchMock.mockRejectedValueOnce(new Error('down'));
    rerender({ data: emptyData(), refreshKey: 1 });
    await settle();
    expect(result.current.statesFailed).toBe(true);
    expect(result.current.rows[0].state).toBeNull();
  });

  it('is not ready while the index loads', () => {
    const { result } = renderHook(() =>
      useAddFilm({
        index: null,
        status: 'loading',
        data: emptyData(),
        idPrefix: 'opt',
        issued: () => 1,
      }),
    );
    expect(result.current.ready).toBe(false);
    expect(result.current.status).toBe('loading');
    expect(result.current.rows).toEqual([]);
  });
});
