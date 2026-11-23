import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { installFetch, respond } from '../test/mockApi.jsx';
import { useJourneyImport } from './useJourneyImport.js';

afterEach(() => {
  vi.unstubAllGlobals();
});

const REQUEST = { runStartedMs: 1790000000000, loggerVersion: 3, samples: [] };
const CREATED = { journeys: [{ samples: 4, linked: 1 }] };
const held = () => {
  let release;
  const gate = new Promise((resolve) => {
    release = resolve;
  });
  return { gate, release };
};
const imports = (fetchMock) =>
  fetchMock.mock.calls.filter(([url]) => url === '/api/admin/journeys');

describe('useJourneyImport', () => {
  it('posts the request once and resolves the response', async () => {
    const fetchMock = installFetch((method) => (method === 'POST' ? respond(CREATED, 201) : null));
    const { result } = renderHook(() => useJourneyImport());

    let outcome;
    await act(async () => {
      outcome = await result.current.run(REQUEST);
    });

    expect(outcome).toEqual({ ok: true, response: CREATED });
    expect(imports(fetchMock)).toHaveLength(1);
    expect(JSON.parse(imports(fetchMock)[0][1].body)).toEqual(REQUEST);
    expect(result.current.busy).toBe(false);
    expect(result.current.failure).toBeNull();
  });

  it('sends one request for two presses', async () => {
    const { gate, release } = held();
    const fetchMock = installFetch((method) => (method === 'POST' ? gate : null));
    const { result } = renderHook(() => useJourneyImport());

    let first;
    let second;
    await act(async () => {
      first = result.current.run(REQUEST);
      second = await result.current.run(REQUEST);
    });
    expect(second).toEqual({ ignored: true });
    expect(result.current.busy).toBe(true);

    await act(async () => {
      release(respond(CREATED, 201));
      await first;
    });
    expect(imports(fetchMock)).toHaveLength(1);
    expect(result.current.busy).toBe(false);
  });

  it.each([
    [400, { error: 'Invalid request' }, 'The server refused this log. Nothing was stored.'],
    [409, { error: 'Already imported' }, 'This log is already imported.'],
    [409, { error: 'A scan is running' }, 'Another import was running. Try again.'],
    [500, null, 'Couldn’t import the log. Try again.'],
  ])('keeps a %s as a failure with its sentence', async (status, body, text) => {
    installFetch((method) => (method === 'POST' ? respond(body, status) : null));
    const { result } = renderHook(() => useJourneyImport());

    let outcome;
    await act(async () => {
      outcome = await result.current.run(REQUEST);
    });

    expect(outcome).toEqual({ ok: false, failure: { kind: 'alert', text } });
    expect(result.current.failure).toEqual({ kind: 'alert', text });
    expect(result.current.busy).toBe(false);
  });

  it('keeps a lost connection as a failure', async () => {
    installFetch((method) => (method === 'POST' ? Promise.reject(new TypeError('offline')) : null));
    const { result } = renderHook(() => useJourneyImport());

    await act(async () => {
      await result.current.run(REQUEST);
    });

    expect(result.current.failure).toEqual({
      kind: 'alert',
      text: 'Couldn’t import the log. Try again.',
    });
  });

  it('keeps a 401 as a sign-in failure with no text', async () => {
    installFetch((method) => (method === 'POST' ? respond({ error: 'Sign in' }, 401) : null));
    const { result } = renderHook(() => useJourneyImport());

    await act(async () => {
      await result.current.run(REQUEST);
    });

    expect(result.current.failure).toEqual({ kind: 'signin', text: null });
  });

  it('forgets a failure on clearFailure, and a new write starts clean', async () => {
    let answer = respond(null, 500);
    installFetch((method) => (method === 'POST' ? answer : null));
    const { result } = renderHook(() => useJourneyImport());
    await act(async () => {
      await result.current.run(REQUEST);
    });
    expect(result.current.failure).not.toBeNull();

    act(() => result.current.clearFailure());
    expect(result.current.failure).toBeNull();
    // With nothing to clear it changes nothing.
    act(() => result.current.clearFailure());
    expect(result.current.failure).toBeNull();

    await act(async () => {
      await result.current.run(REQUEST);
    });
    expect(result.current.failure).not.toBeNull();
    answer = respond(CREATED, 201);
    await act(async () => {
      await result.current.run(REQUEST);
    });
    expect(result.current.failure).toBeNull();
  });

  it('resolves the outcome when the page has gone', async () => {
    const { gate, release } = held();
    installFetch((method) => (method === 'POST' ? gate : null));
    const { result, unmount } = renderHook(() => useJourneyImport());

    let pending;
    act(() => {
      pending = result.current.run(REQUEST);
    });
    unmount();
    release(respond(CREATED, 201));

    expect(await pending).toEqual({ ok: true, response: CREATED });
  });

  it('resolves a failure when the page has gone', async () => {
    const { gate, release } = held();
    installFetch((method) => (method === 'POST' ? gate : null));
    const { result, unmount } = renderHook(() => useJourneyImport());

    let pending;
    act(() => {
      pending = result.current.run(REQUEST);
    });
    unmount();
    release(respond(null, 500));

    const outcome = await pending;
    expect(outcome.ok).toBe(false);
    expect(outcome.failure.kind).toBe('alert');
  });
});
