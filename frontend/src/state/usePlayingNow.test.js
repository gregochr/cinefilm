import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { usePlayingNow } from './usePlayingNow.js';

afterEach(() => {
  vi.unstubAllGlobals();
});

const MAC = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)';
const entry = (n, over = {}) => ({
  playbackId: `p${n}`,
  editionId: 10 + n,
  filmTitle: `Film ${n}`,
  device: MAC,
  touch: false,
  environment: 'home',
  quality: '720p',
  ...over,
});
const session = (n, over = {}) => ({
  id: `s${n}`,
  filmTitle: `Film ${n}`,
  editionId: 10 + n,
  rung: '720p',
  speed: 3,
  segmentsAhead: 5,
  startedAt: '2026-11-09T19:02:11Z',
  ...over,
});

const STOP_FAILED = 'Couldn’t stop that stream. Try again.';

/** A fetch stub for the DELETE; `answer` returns the status, or a promise of a Response. */
function stubFetch(answer = () => 204) {
  const mock = vi.fn(async () => {
    const result = await answer();
    return result instanceof Response ? result : new Response(null, { status: result });
  });
  vi.stubGlobal('fetch', mock);
  return mock;
}

function mount(props = {}) {
  const calls = {
    onLeft: vi.fn(),
    reloadSessions: vi.fn(),
    reloadSummary: vi.fn(),
  };
  const initial = { entries: [entry(1)], sessions: [session(1)], ...calls, ...props };
  const hook = renderHook((p) => usePlayingNow(p), { initialProps: initial });
  return { ...hook, calls, initial };
}

describe('usePlayingNow rows', () => {
  it('builds a row per entry and matches its session by edition', () => {
    const { result } = mount({
      entries: [entry(1), entry(2)],
      sessions: [session(2)],
    });
    expect(result.current.rows.map((r) => [r.playbackId, r.sessionId])).toEqual([
      ['p1', null],
      ['p2', 's2'],
    ]);
  });

  it.each([
    ['entries', { entries: undefined }],
    ['entries and sessions', { entries: undefined, sessions: undefined }],
  ])('has no rows when %s are not read yet', (_name, props) => {
    const { result } = mount(props);
    expect(result.current.rows).toEqual([]);
  });

  it('has rows without a session when the live sessions are not read yet', () => {
    const { result } = mount({ sessions: undefined });
    expect(result.current.rows).toHaveLength(1);
    expect(result.current.rows[0].sessionId).toBeNull();
  });
});

describe('usePlayingNow stop', () => {
  it('sends nothing for a row with no session', async () => {
    const fetchMock = stubFetch();
    const { result, calls } = mount({ sessions: [] });
    await act(async () => {
      await result.current.stop(result.current.rows[0]);
    });
    expect(fetchMock).not.toHaveBeenCalled();
    expect(calls.reloadSessions).not.toHaveBeenCalled();
    expect(result.current.rows).toHaveLength(1);
  });

  it('sends one DELETE for two stops in flight, flags it while it runs and clears the flag after', async () => {
    let release;
    const fetchMock = stubFetch(
      () =>
        new Promise((resolve) => {
          release = () => resolve(new Response(null, { status: 204 }));
        }),
    );
    const { result } = mount();
    const row = result.current.rows[0];
    let first;
    let second;
    act(() => {
      first = result.current.stop(row);
      second = result.current.stop(row);
    });
    expect(result.current.stopping).toEqual({ p1: true });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0][0]).toBe('/api/admin/transcoding/sessions/s1');
    expect(fetchMock.mock.calls[0][1].method).toBe('DELETE');
    await act(async () => {
      release();
      await Promise.all([first, second]);
    });
    expect(result.current.stopping).toEqual({});
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('on a failure sets the exact message, keeps the row and calls nothing else', async () => {
    stubFetch(() => 500);
    const { result, calls } = mount();
    await act(async () => {
      await result.current.stop(result.current.rows[0]);
    });
    expect(result.current.failures).toEqual({ p1: STOP_FAILED });
    expect(result.current.stopping).toEqual({});
    expect(result.current.rows).toHaveLength(1);
    expect(calls.onLeft).not.toHaveBeenCalled();
    expect(calls.reloadSessions).not.toHaveBeenCalled();
    expect(calls.reloadSummary).not.toHaveBeenCalled();
  });

  it('clears the old failure when it tries again, and a success then leaves none', async () => {
    let status = 500;
    stubFetch(() => status);
    const { result } = mount();
    await act(async () => {
      await result.current.stop(result.current.rows[0]);
    });
    expect(result.current.failures.p1).toBe(STOP_FAILED);

    let release;
    status = new Promise((resolve) => {
      release = () => resolve(new Response(null, { status: 204 }));
    });
    let retry;
    act(() => {
      retry = result.current.stop(result.current.rows[0]);
    });
    expect(result.current.failures).toEqual({});
    await act(async () => {
      release();
      await retry;
    });
    expect(result.current.failures).toEqual({});
  });

  it('on success hides the row and calls onLeft, reloadSessions and reloadSummary once each', async () => {
    stubFetch();
    const { result, calls } = mount();
    const row = result.current.rows[0];
    await act(async () => {
      await result.current.stop(row);
    });
    expect(result.current.rows).toEqual([]);
    expect(calls.onLeft).toHaveBeenCalledTimes(1);
    expect(calls.onLeft).toHaveBeenCalledWith(row);
    expect(calls.reloadSessions).toHaveBeenCalledTimes(1);
    expect(calls.reloadSummary).toHaveBeenCalledTimes(1);
    expect(result.current.failures).toEqual({});
  });

  it('works with no onLeft', async () => {
    stubFetch();
    const { result } = mount({ onLeft: undefined });
    await act(async () => {
      await result.current.stop(result.current.rows[0]);
    });
    expect(result.current.rows).toEqual([]);
  });
});

describe('usePlayingNow after a stop', () => {
  async function stopped(extra = {}) {
    stubFetch();
    const view = mount(extra);
    await act(async () => {
      await view.result.current.stop(view.result.current.rows[0]);
    });
    return view;
  }

  it('keeps the row hidden while its session is gone, though the summary still lists it', async () => {
    const { result, rerender, initial } = await stopped();
    rerender({ ...initial, sessions: [] });
    expect(result.current.rows).toEqual([]);
    rerender({ ...initial, sessions: undefined });
    expect(result.current.rows).toEqual([]);
  });

  it('keeps the row hidden while the stopped session is still listed', async () => {
    const { result, rerender, initial } = await stopped();
    rerender({ ...initial, sessions: [session(1)] });
    expect(result.current.rows).toEqual([]);
  });

  it('shows the row again, with Stop, on a session with another id', async () => {
    const { result, rerender, initial } = await stopped();
    rerender({ ...initial, sessions: [session(1, { id: 's1-new' })] });
    await waitFor(() => expect(result.current.rows).toHaveLength(1));
    expect(result.current.rows[0].sessionId).toBe('s1-new');
  });

  it('does not hide another playback id of the same edition', async () => {
    const { result, rerender, initial } = await stopped();
    rerender({
      ...initial,
      entries: [entry(1), entry(1, { playbackId: 'p1-reload' })],
      sessions: [],
    });
    expect(result.current.rows.map((r) => r.playbackId)).toEqual(['p1-reload']);
  });
});
