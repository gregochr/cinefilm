import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { SIGNED_IN_EVENT } from '../api/client.js';
import { installFetch, respond } from '../test/mockApi.jsx';
import { useJourneys } from './useJourneys.js';

afterEach(() => {
  vi.unstubAllGlobals();
});

const ONE = { journeys: [{ id: 1 }] };
const TWO = { journeys: [{ id: 2 }, { id: 1 }] };
const reads = (fetchMock) => fetchMock.mock.calls.filter(([url]) => url === '/api/admin/journeys');

/** A server that answers each read with the next of `answers` (a Response, or a promise of one). */
const serving = (...answers) => {
  let at = 0;
  return installFetch((method, url) =>
    method === 'GET' && url === '/api/admin/journeys' ? answers[at++] : null,
  );
};

describe('useJourneys', () => {
  it('has no list until the first answer arrives, then the list', async () => {
    serving(respond(ONE));
    const { result } = renderHook(() => useJourneys());

    expect(result.current.journeys).toBeNull();
    expect(result.current.loading).toBe(true);
    await waitFor(() => expect(result.current.journeys).toEqual(ONE.journeys));
    expect(result.current.loading).toBe(false);
    expect(result.current.error).toBeNull();
  });

  it('reports a failure and no list', async () => {
    serving(respond({ error: 'x' }, 500));
    const { result } = renderHook(() => useJourneys());

    await waitFor(() => expect(result.current.error).not.toBeNull());
    expect(result.current.loading).toBe(false);
    expect(result.current.journeys).toBeNull();
  });

  it('reads again on reload, keeping the rows until the new answer arrives', async () => {
    let release;
    const held = new Promise((resolve) => {
      release = resolve;
    });
    const fetchMock = serving(respond(ONE), held);
    const { result } = renderHook(() => useJourneys());
    await waitFor(() => expect(result.current.journeys).toEqual(ONE.journeys));

    act(() => result.current.reload());
    await waitFor(() => expect(reads(fetchMock)).toHaveLength(2));
    expect(result.current.journeys).toEqual(ONE.journeys);
    expect(result.current.loading).toBe(true);

    await act(async () => release(respond(TWO)));
    await waitFor(() => expect(result.current.journeys).toEqual(TWO.journeys));
    expect(result.current.loading).toBe(false);
  });

  it('reads again after a sign-in', async () => {
    const fetchMock = serving(respond({ error: 'x' }, 401), respond(ONE));
    const { result } = renderHook(() => useJourneys());
    await waitFor(() => expect(result.current.error).not.toBeNull());

    act(() => {
      window.dispatchEvent(new Event(SIGNED_IN_EVENT));
    });

    await waitFor(() => expect(result.current.journeys).toEqual(ONE.journeys));
    expect(reads(fetchMock)).toHaveLength(2);
    expect(result.current.error).toBeNull();
  });
});
