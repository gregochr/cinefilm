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
});
