import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { installFetch, respond } from '../test/mockApi.jsx';
import { usePlaybackRecord } from './usePlaybackRecord.js';

afterEach(() => vi.unstubAllGlobals());

const A = '00000000-0000-4000-8000-00000000000a';
const B = '00000000-0000-4000-8000-00000000000b';
const record = (id) => ({ playbackId: id, report: { minutes: [] } });

/** A server that holds each record's answer until the test releases it. */
function held() {
  const gates = {};
  const signals = {};
  installFetch((method, url, init) => {
    const id = url.split('/').pop();
    signals[id] = init.signal;
    return new Promise((resolve) => {
      gates[id] = (response) => resolve(response);
    });
  });
  return { gates, signals };
}

describe('usePlaybackRecord', () => {
  it('reads nothing without an id', () => {
    const fetchMock = installFetch();
    const { result } = renderHook(() => usePlaybackRecord(null));
    expect(result.current).toMatchObject({ record: null, error: null, loading: false });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('loads the record of the id, then returns it', async () => {
    installFetch((m, url) => (url.endsWith(A) ? respond(record(A)) : null));
    const { result } = renderHook(() => usePlaybackRecord(A));
    expect(result.current).toMatchObject({ record: null, loading: true });
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.record).toEqual(record(A));
  });

  it("ignores an earlier id's answer that arrives after the later one", async () => {
    const { gates, signals } = held();
    const { result, rerender } = renderHook(({ id }) => usePlaybackRecord(id), {
      initialProps: { id: A },
    });
    await waitFor(() => expect(gates[A]).toBeDefined());
    rerender({ id: B });
    await waitFor(() => expect(gates[B]).toBeDefined());
    expect(signals[A].aborted).toBe(true);
    expect(result.current).toMatchObject({ record: null, loading: true });

    await act(async () => gates[B](respond(record(B))));
    await waitFor(() => expect(result.current.record).toEqual(record(B)));
    await act(async () => gates[A](respond(record(A))));
    expect(result.current.record).toEqual(record(B));
    expect(result.current.loading).toBe(false);
  });

  it("ignores an earlier id's failure that arrives late", async () => {
    const { gates } = held();
    const { result, rerender } = renderHook(({ id }) => usePlaybackRecord(id), {
      initialProps: { id: A },
    });
    await waitFor(() => expect(gates[A]).toBeDefined());
    rerender({ id: B });
    await waitFor(() => expect(gates[B]).toBeDefined());
    await act(async () => gates[B](respond(record(B))));
    await act(async () => gates[A](respond({ error: 'x' }, 500)));
    expect(result.current.error).toBeNull();
    expect(result.current.record).toEqual(record(B));
  });

  it("never shows one id's record under another while the second loads", async () => {
    const { gates } = held();
    const { result, rerender } = renderHook(({ id }) => usePlaybackRecord(id), {
      initialProps: { id: A },
    });
    await waitFor(() => expect(gates[A]).toBeDefined());
    await act(async () => gates[A](respond(record(A))));
    await waitFor(() => expect(result.current.record).toEqual(record(A)));
    rerender({ id: B });
    expect(result.current).toMatchObject({ record: null, error: null, loading: true });
  });

  it('reports a failure, and reads again on reload', async () => {
    let ok = false;
    const fetchMock = installFetch((m, url) =>
      url.endsWith(A) ? (ok ? respond(record(A)) : respond({ error: 'x' }, 500)) : null,
    );
    const { result } = renderHook(() => usePlaybackRecord(A));
    await waitFor(() => expect(result.current.error).not.toBeNull());
    expect(result.current).toMatchObject({ record: null, loading: false });
    ok = true;
    act(() => result.current.reload());
    expect(result.current).toMatchObject({ error: null, loading: true });
    await waitFor(() => expect(result.current.record).toEqual(record(A)));
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('drops the answer that arrives after unmount', async () => {
    const { gates } = held();
    const { result, unmount } = renderHook(() => usePlaybackRecord(A));
    await waitFor(() => expect(gates[A]).toBeDefined());
    unmount();
    await act(async () => gates[A](respond(record(A))));
    expect(result.current.record).toBeNull();
  });
});
