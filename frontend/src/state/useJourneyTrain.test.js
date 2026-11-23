import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { installFetch, respond } from '../test/mockApi.jsx';
import { useJourneyTrain } from './useJourneyTrain.js';

afterEach(() => {
  vi.unstubAllGlobals();
});

const FILE = {
  uid: 'X12345',
  date: '2030-03-04',
  headcode: '9Z99',
  calls: [{ location: 'Alpha Junction' }, { location: 'Theta Central' }],
};
const STORED = { uid: 'X12345', date: '2030-03-04', headcode: '9Z99', calls: 2 };
const held = () => {
  let release;
  const gate = new Promise((resolve) => {
    release = resolve;
  });
  return { gate, release };
};
const puts = (fetchMock) => fetchMock.mock.calls.filter(([, init]) => init?.method === 'PUT');
const answering = (answer) => installFetch((method) => (method === 'PUT' ? answer() : null));

describe('useJourneyTrain', () => {
  it('puts the file to the journey’s train by its id and resolves ok with the response', async () => {
    const fetchMock = answering(() => respond(STORED));
    const { result } = renderHook(() => useJourneyTrain());

    let outcome;
    await act(async () => {
      outcome = await result.current.give(31, FILE);
    });

    expect(outcome).toEqual({ ok: true, response: STORED });
    expect(puts(fetchMock)).toHaveLength(1);
    expect(puts(fetchMock)[0][0]).toBe('/api/admin/journeys/31/train');
    expect(JSON.parse(puts(fetchMock)[0][1].body)).toEqual(FILE);
    expect(result.current.busy).toBe(false);
    expect(result.current.failure).toBeNull();
  });

  it('sends one request for two presses', async () => {
    const { gate, release } = held();
    const fetchMock = answering(() => gate);
    const { result } = renderHook(() => useJourneyTrain());

    let first;
    let second;
    await act(async () => {
      first = result.current.give(31, FILE);
      second = await result.current.give(32, FILE);
    });
    expect(second).toEqual({ ignored: true });
    expect(result.current.busy).toBe(true);

    await act(async () => {
      release(respond(STORED));
      await first;
    });
    expect(puts(fetchMock)).toHaveLength(1);
    expect(result.current.busy).toBe(false);
  });

  it.each([
    [
      400,
      { error: 'The train did not run during this journey' },
      { kind: 'alert', text: 'That train did not run during this journey. Nothing was stored.' },
    ],
    [
      400,
      { error: 'Invalid train file' },
      { kind: 'alert', text: 'The server refused this train file. Nothing was stored.' },
    ],
    [
      409,
      { error: 'This train is stored with other end points' },
      {
        kind: 'alert',
        text: 'This train is already stored with other end points. Nothing was changed.',
      },
    ],
    [
      409,
      { error: 'Try again' },
      { kind: 'alert', text: 'Another request was running. Try again.' },
    ],
    [500, null, { kind: 'alert', text: 'Couldn’t store the train. Try again.' }],
    [404, { error: 'No such journey' }, { kind: 'gone', text: null }],
    [401, { error: 'Sign in' }, { kind: 'signin', text: null }],
  ])('keeps a %s (%j) as a failure with the id it was for', async (status, body, failure) => {
    answering(() => respond(body, status));
    const { result } = renderHook(() => useJourneyTrain());

    let outcome;
    await act(async () => {
      outcome = await result.current.give(31, FILE);
    });

    expect(outcome).toEqual({ ok: false, failure: { id: 31, ...failure } });
    expect(result.current.failure).toEqual({ id: 31, ...failure });
    expect(result.current.busy).toBe(false);
  });

  it('keeps a lost connection as a failure', async () => {
    answering(() => Promise.reject(new TypeError('offline')));
    const { result } = renderHook(() => useJourneyTrain());

    await act(async () => {
      await result.current.give(31, FILE);
    });

    expect(result.current.failure).toEqual({
      id: 31,
      kind: 'alert',
      text: 'Couldn’t store the train. Try again.',
    });
  });

  it('forgets a failure on clearFailure, and a new write starts clean', async () => {
    let answer = respond(null, 500);
    answering(() => answer);
    const { result } = renderHook(() => useJourneyTrain());
    await act(async () => {
      await result.current.give(31, FILE);
    });
    expect(result.current.failure).not.toBeNull();

    act(() => result.current.clearFailure());
    expect(result.current.failure).toBeNull();
    // With nothing to clear it changes nothing.
    act(() => result.current.clearFailure());
    expect(result.current.failure).toBeNull();

    await act(async () => {
      await result.current.give(32, FILE);
    });
    expect(result.current.failure.id).toBe(32);
    answer = respond(STORED);
    await act(async () => {
      await result.current.give(32, FILE);
    });
    expect(result.current.failure).toBeNull();
  });

  describe('takeAway', () => {
    const deletes = (fetchMock) =>
      fetchMock.mock.calls.filter(([, init]) => init?.method === 'DELETE');
    const removing = (answer) => installFetch((method) => (method === 'DELETE' ? answer() : null));
    const noContent = () => new Response(null, { status: 204 });

    it('deletes the journey’s train by its id and resolves ok with nothing else', async () => {
      const fetchMock = removing(noContent);
      const { result } = renderHook(() => useJourneyTrain());

      let outcome;
      await act(async () => {
        outcome = await result.current.takeAway(31);
      });

      expect(outcome).toEqual({ ok: true });
      expect(deletes(fetchMock)).toHaveLength(1);
      expect(deletes(fetchMock)[0][0]).toBe('/api/admin/journeys/31/train');
      expect(result.current.busy).toBe(false);
      expect(result.current.failure).toBeNull();
    });

    it('sends one request for two presses', async () => {
      const { gate, release } = held();
      const fetchMock = removing(() => gate);
      const { result } = renderHook(() => useJourneyTrain());

      let first;
      let second;
      await act(async () => {
        first = result.current.takeAway(31);
        second = await result.current.takeAway(32);
      });
      expect(second).toEqual({ ignored: true });
      expect(result.current.busy).toBe(true);

      await act(async () => {
        release(noContent());
        await first;
      });
      expect(deletes(fetchMock)).toHaveLength(1);
      expect(result.current.busy).toBe(false);
    });

    it('ignores a give while a takeAway is in flight', async () => {
      const { gate, release } = held();
      const fetchMock = installFetch((method) => (method === 'DELETE' ? gate : respond(STORED)));
      const { result } = renderHook(() => useJourneyTrain());

      let first;
      let second;
      await act(async () => {
        first = result.current.takeAway(31);
        second = await result.current.give(31, FILE);
      });
      expect(second).toEqual({ ignored: true });

      await act(async () => {
        release(noContent());
        await first;
      });
      expect(puts(fetchMock)).toHaveLength(0);
      expect(deletes(fetchMock)).toHaveLength(1);
    });

    it('ignores a takeAway while a give is in flight', async () => {
      const { gate, release } = held();
      const fetchMock = installFetch((method) => (method === 'PUT' ? gate : noContent()));
      const { result } = renderHook(() => useJourneyTrain());

      let first;
      let second;
      await act(async () => {
        first = result.current.give(31, FILE);
        second = await result.current.takeAway(31);
      });
      expect(second).toEqual({ ignored: true });

      await act(async () => {
        release(respond(STORED));
        await first;
      });
      expect(deletes(fetchMock)).toHaveLength(0);
      expect(puts(fetchMock)).toHaveLength(1);
    });

    it.each([
      [
        409,
        { error: 'Try again' },
        { kind: 'alert', text: 'Another request was running. Try again.' },
      ],
      [500, null, { kind: 'alert', text: 'Couldn’t remove the train. Try again.' }],
      [404, { error: 'No such journey' }, { kind: 'gone', text: null }],
      [401, { error: 'Sign in' }, { kind: 'signin', text: null }],
    ])('keeps a %s (%j) as a failure with its id', async (status, body, failure) => {
      removing(() => respond(body, status));
      const { result } = renderHook(() => useJourneyTrain());

      let outcome;
      await act(async () => {
        outcome = await result.current.takeAway(31);
      });

      const kept = { id: 31, ...failure };
      expect(outcome).toEqual({ ok: false, failure: kept });
      expect(result.current.failure).toEqual(kept);
      expect(result.current.busy).toBe(false);
    });

    it('keeps a lost connection as a failure', async () => {
      removing(() => Promise.reject(new TypeError('offline')));
      const { result } = renderHook(() => useJourneyTrain());

      await act(async () => {
        await result.current.takeAway(31);
      });

      expect(result.current.failure).toEqual({
        id: 31,
        kind: 'alert',
        text: 'Couldn’t remove the train. Try again.',
      });
    });

    it('forgets a failure on clearFailure, and a success starts clean', async () => {
      let answer = respond(null, 500);
      removing(() => answer);
      const { result } = renderHook(() => useJourneyTrain());
      await act(async () => {
        await result.current.takeAway(31);
      });
      expect(result.current.failure).not.toBeNull();

      act(() => result.current.clearFailure());
      expect(result.current.failure).toBeNull();

      await act(async () => {
        await result.current.takeAway(31);
      });
      expect(result.current.failure).not.toBeNull();
      answer = noContent();
      await act(async () => {
        await result.current.takeAway(31);
      });
      expect(result.current.failure).toBeNull();
    });
  });
});
