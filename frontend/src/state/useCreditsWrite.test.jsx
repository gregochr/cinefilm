import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, respond } from '../test/mockApi.jsx';
import { useCreditsWrite } from './useCreditsWrite.js';

afterEach(() => {
  vi.unstubAllGlobals();
});

const ROW = fixtures.adminCredits.items[0];
const writes = (fetchMock) =>
  fetchMock.mock.calls
    .filter(([url]) => String(url).includes('/credits') && String(url).includes('/editions/'))
    .map(([url, init]) => `${init.method} ${url} ${init.body ?? ''}`.trim());

/** Holds the answer to an edition's credits write until the test releases it. */
function held() {
  let release;
  const gate = new Promise((resolve) => {
    release = resolve;
  });
  return { gate, release: (response) => release(response) };
}

describe('useCreditsWrite', () => {
  it('sends the PUT with the time and resolves ok', async () => {
    const fetchMock = installFetch((method, url) =>
      url === '/api/admin/editions/33/credits' ? respond(ROW) : null,
    );
    const { result } = renderHook(() => useCreditsWrite());

    let outcome;
    await act(async () => {
      outcome = await result.current.run(33, 'set', 6730000);
    });

    expect(outcome).toEqual({ ok: true });
    expect(writes(fetchMock)).toEqual(['PUT /api/admin/editions/33/credits {"markerMs":6730000}']);
    expect(result.current.busy).toBe(false);
    expect(result.current.failure).toBeNull();
  });

  it('sends the DELETE for clear', async () => {
    const fetchMock = installFetch((method, url) =>
      url === '/api/admin/editions/33/credits' ? respond(ROW) : null,
    );
    const { result } = renderHook(() => useCreditsWrite());

    await act(async () => {
      await result.current.run(33, 'clear');
    });

    expect(writes(fetchMock)).toEqual(['DELETE /api/admin/editions/33/credits']);
  });

  it('is busy while the request is out, and ignores a second press, a Clear after a Set included', async () => {
    const answer = held();
    const fetchMock = installFetch((method, url) =>
      url === '/api/admin/editions/33/credits' ? answer.gate : null,
    );
    const { result } = renderHook(() => useCreditsWrite());

    let first;
    let second;
    let third;
    await act(async () => {
      first = result.current.run(33, 'set', 6730000);
      second = result.current.run(33, 'set', 6730000);
      third = result.current.run(33, 'clear');
    });

    expect(result.current.busy).toBe(true);
    expect(await second).toEqual({ ignored: true });
    expect(await third).toEqual({ ignored: true });
    await act(async () => answer.release(respond(ROW)));
    expect(await first).toEqual({ ok: true });
    expect(writes(fetchMock)).toHaveLength(1);
    expect(result.current.busy).toBe(false);
  });

  it('keeps a refusal with its edition until cleared, and lets the next write go', async () => {
    let refuse = true;
    const fetchMock = installFetch((method, url) => {
      if (url !== '/api/admin/editions/33/credits') return null;
      return refuse
        ? respond({ error: 'That time is outside where end credits can start' }, 400)
        : respond(ROW);
    });
    const { result } = renderHook(() => useCreditsWrite());

    let outcome;
    await act(async () => {
      outcome = await result.current.run(33, 'set', 1000);
    });

    const failure = {
      editionId: 33,
      kind: 'field',
      text: 'That time is outside where end credits can start',
    };
    expect(outcome).toEqual({ ok: false, failure });
    expect(result.current.failure).toEqual(failure);
    expect(result.current.busy).toBe(false);

    act(() => result.current.clearFailure());
    expect(result.current.failure).toBeNull();

    refuse = false;
    await act(async () => {
      outcome = await result.current.run(33, 'set', 6730000);
    });
    expect(outcome).toEqual({ ok: true });
    expect(writes(fetchMock)).toHaveLength(2);
  });

  it('reports a 401 as sign-in, with no words', async () => {
    installFetch((method, url) =>
      url === '/api/admin/editions/33/credits' ? respond(null, 401) : null,
    );
    const { result } = renderHook(() => useCreditsWrite());

    await act(async () => {
      await result.current.run(33, 'set', 6730000);
    });

    expect(result.current.failure).toEqual({ editionId: 33, kind: 'signin', text: null });
  });
});
