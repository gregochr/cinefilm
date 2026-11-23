import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { installFetch, respond } from '../test/mockApi.jsx';
import { useJourneyDelete } from './useJourneyDelete.js';

afterEach(() => {
  vi.unstubAllGlobals();
});

const held = () => {
  let release;
  const gate = new Promise((resolve) => {
    release = resolve;
  });
  return { gate, release };
};
const deletes = (fetchMock) => fetchMock.mock.calls.filter(([, init]) => init?.method === 'DELETE');
const answering = (answer) => installFetch((method) => (method === 'DELETE' ? answer() : null));

describe('useJourneyDelete', () => {
  it('deletes the journey by its id and resolves ok', async () => {
    const fetchMock = answering(() => respond(null, 204));
    const { result } = renderHook(() => useJourneyDelete());

    let outcome;
    await act(async () => {
      outcome = await result.current.run(31);
    });

    expect(outcome).toEqual({ ok: true });
    expect(deletes(fetchMock)).toHaveLength(1);
    expect(deletes(fetchMock)[0][0]).toBe('/api/admin/journeys/31');
    expect(result.current.busy).toBe(false);
    expect(result.current.failure).toBeNull();
  });

  it('sends one request for two presses', async () => {
    const { gate, release } = held();
    const fetchMock = answering(() => gate);
    const { result } = renderHook(() => useJourneyDelete());

    let first;
    let second;
    await act(async () => {
      first = result.current.run(31);
      second = await result.current.run(32);
    });
    expect(second).toEqual({ ignored: true });
    expect(result.current.busy).toBe(true);

    await act(async () => {
      release(respond(null, 204));
      await first;
    });
    expect(deletes(fetchMock)).toHaveLength(1);
    expect(result.current.busy).toBe(false);
  });

  it.each([
    [
      409,
      { error: 'Try again' },
      { kind: 'alert', text: 'Another request was running. Try again.' },
    ],
    [500, null, { kind: 'alert', text: 'Couldn’t delete the journey. Try again.' }],
    [404, { error: 'No such journey' }, { kind: 'gone', text: null }],
    [401, { error: 'Sign in' }, { kind: 'signin', text: null }],
  ])('keeps a %s as a failure with the id it was for', async (status, body, failure) => {
    answering(() => respond(body, status));
    const { result } = renderHook(() => useJourneyDelete());

    let outcome;
    await act(async () => {
      outcome = await result.current.run(31);
    });

    expect(outcome).toEqual({ ok: false, failure: { id: 31, ...failure } });
    expect(result.current.failure).toEqual({ id: 31, ...failure });
    expect(result.current.busy).toBe(false);
  });

  it('keeps a lost connection as a failure', async () => {
    answering(() => Promise.reject(new TypeError('offline')));
    const { result } = renderHook(() => useJourneyDelete());

    await act(async () => {
      await result.current.run(31);
    });

    expect(result.current.failure).toEqual({
      id: 31,
      kind: 'alert',
      text: 'Couldn’t delete the journey. Try again.',
    });
  });

  it('forgets a failure on clearFailure, and a new write starts clean', async () => {
    let answer = respond(null, 500);
    answering(() => answer);
    const { result } = renderHook(() => useJourneyDelete());
    await act(async () => {
      await result.current.run(31);
    });
    expect(result.current.failure).not.toBeNull();

    act(() => result.current.clearFailure());
    expect(result.current.failure).toBeNull();
    // With nothing to clear it changes nothing.
    act(() => result.current.clearFailure());
    expect(result.current.failure).toBeNull();

    await act(async () => {
      await result.current.run(32);
    });
    expect(result.current.failure.id).toBe(32);
    answer = respond(null, 204);
    await act(async () => {
      await result.current.run(32);
    });
    expect(result.current.failure).toBeNull();
  });

  it('resolves the outcome when the page has gone', async () => {
    const { gate, release } = held();
    answering(() => gate);
    const { result, unmount } = renderHook(() => useJourneyDelete());

    let pending;
    act(() => {
      pending = result.current.run(31);
    });
    unmount();
    release(respond(null, 204));

    expect(await pending).toEqual({ ok: true });
  });
});
