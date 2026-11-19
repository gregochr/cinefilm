import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
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
