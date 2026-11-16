import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ADMIN_TIMEOUT_MS } from '../api/timeout.js';
import { installFetch, respond } from '../test/mockApi.jsx';
import { subtitlesWrong } from './subtitleFixCopy.js';
import { useSubtitleWrong } from './useSubtitleWrong.js';

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

const target = { kind: 'FORCED' };
const calls = (fetchMock) => fetchMock.mock.calls.map((c) => `${c[1]?.method ?? 'GET'} ${c[0]}`);

describe('useSubtitleWrong', () => {
  it('deletes the download the film showed by its id, and reads nothing', async () => {
    const fetchMock = installFetch();
    const { result } = renderHook(() => useSubtitleWrong(target));

    let done;
    await act(async () => {
      done = await result.current.report('FORCED', [6]);
    });

    expect(done).toBe(true);
    expect(calls(fetchMock)).toEqual(['DELETE /api/admin/subtitles/6']);
    expect(result.current.busy).toBe(false);
    expect(result.current.problem).toBeNull();
  });

  it('is busy while the request is out, and a second click sends no second request', async () => {
    let release;
    const gate = new Promise((resolve) => {
      release = resolve;
    });
    const fetchMock = installFetch((method, url) =>
      method === 'DELETE' && url === '/api/admin/subtitles/6'
        ? gate.then(() => respond(null, 204))
        : null,
    );
    const { result } = renderHook(() => useSubtitleWrong(target));

    let first;
    let second;
    await act(async () => {
      first = result.current.report('FORCED', [6]);
      second = result.current.report('FORCED', [6]);
      await Promise.resolve();
    });
    expect(await second).toBe(false);
    release();
    await act(async () => {
      expect(await first).toBe(true);
    });

    expect(calls(fetchMock)).toEqual(['DELETE /api/admin/subtitles/6']);
    expect(result.current.busy).toBe(false);
  });

  it.each([
    ['none', undefined],
    ['an empty list', []],
  ])('says the subtitle changed and sends nothing when the film gave %s', async (_name, ids) => {
    const fetchMock = installFetch();
    const { result } = renderHook(() => useSubtitleWrong({ kind: 'FULL' }));

    let done;
    await act(async () => {
      done = await result.current.report('FULL', ids);
    });

    expect(done).toBe(false);
    expect(result.current.problem).toBe(subtitlesWrong.changed);
    expect(calls(fetchMock)).toEqual([]);
  });

  it('shows the server’s own words when it refuses the delete, then clears them on the next try', async () => {
    let refuse = true;
    installFetch((method) =>
      method === 'DELETE' && refuse
        ? respond({ error: 'Only a subtitle downloaded from OpenSubtitles can be removed' }, 400)
        : null,
    );
    const { result } = renderHook(() => useSubtitleWrong(target));

    await act(() => result.current.report('FORCED', [6]));
    expect(result.current.problem).toBe(
      'Only a subtitle downloaded from OpenSubtitles can be removed',
    );

    refuse = false;
    await act(() => result.current.report('FORCED', [6]));
    expect(result.current.problem).toBeNull();
  });

  it('removes every row of the download (a split film), stopping at the first failure', async () => {
    const fetchMock = installFetch((method, url) => {
      if (method !== 'DELETE') return null;
      return url === '/api/admin/subtitles/62'
        ? respond({ error: 'boom' }, 500)
        : respond(null, 204);
    });
    const { result } = renderHook(() => useSubtitleWrong(target));

    let done;
    await act(async () => {
      done = await result.current.report('FORCED', [61, 62, 63]);
    });

    expect(done).toBe(false);
    expect(calls(fetchMock)).toEqual([
      'DELETE /api/admin/subtitles/61',
      'DELETE /api/admin/subtitles/62',
    ]);
    expect(result.current.problem).toBe(subtitlesWrong.failed);
  });

  it('counts a 404 on a delete as removed: the row was already gone', async () => {
    const fetchMock = installFetch((method, url) => {
      if (method !== 'DELETE') return null;
      return url === '/api/admin/subtitles/61'
        ? respond({ error: 'No such subtitle file' }, 404)
        : respond(null, 204);
    });
    const { result } = renderHook(() => useSubtitleWrong(target));

    let done;
    await act(async () => {
      done = await result.current.report('FORCED', [61, 62]);
    });

    expect(done).toBe(true);
    expect(result.current.problem).toBeNull();
    expect(calls(fetchMock)).toEqual([
      'DELETE /api/admin/subtitles/61',
      'DELETE /api/admin/subtitles/62',
    ]);
  });

  it('a delete with no answer fails after the timeout, unblocks the button, and the next try can go out', async () => {
    vi.useFakeTimers();
    let hang = true;
    const fetchMock = installFetch((method) =>
      method === 'DELETE' && hang ? new Promise(() => {}) : null,
    );
    const { result } = renderHook(() => useSubtitleWrong(target));

    let first;
    await act(async () => {
      first = result.current.report('FORCED', [6]);
      await vi.advanceTimersByTimeAsync(ADMIN_TIMEOUT_MS - 1);
    });
    expect(result.current.busy).toBe(true);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1);
    });

    expect(await first).toBe(false);
    expect(result.current.busy).toBe(false);
    expect(result.current.problem).toBe(subtitlesWrong.failed);

    hang = false;
    let again;
    await act(async () => {
      again = await result.current.report('FORCED', [6]);
    });
    expect(again).toBe(true);
    expect(result.current.problem).toBeNull();
    expect(calls(fetchMock)).toEqual([
      'DELETE /api/admin/subtitles/6',
      'DELETE /api/admin/subtitles/6',
    ]);
  });

  it('forgets a failure once another download (or none) shows', async () => {
    installFetch((method) => (method === 'DELETE' ? respond({ error: 'boom' }, 500) : null));
    const { result, rerender } = renderHook((props) => useSubtitleWrong(props), {
      initialProps: target,
    });
    await act(() => result.current.report('FORCED', [6]));
    expect(result.current.problem).toBe(subtitlesWrong.failed);

    rerender({ kind: 'FULL' });
    expect(result.current.problem).toBeNull();

    // Switching back does not bring the old message back.
    rerender(target);
    expect(result.current.problem).toBeNull();
  });

  it('does not keep a failure that arrives after the viewer moved to another download', async () => {
    let release;
    const gate = new Promise((resolve) => {
      release = resolve;
    });
    installFetch((method) =>
      method === 'DELETE' ? gate.then(() => respond({ error: 'boom' }, 500)) : null,
    );
    const { result, rerender } = renderHook((props) => useSubtitleWrong(props), {
      initialProps: target,
    });
    let pending;
    await act(async () => {
      pending = result.current.report('FORCED', [6]);
      await Promise.resolve();
    });
    rerender({ kind: 'FULL' });

    await act(async () => {
      release();
      await pending;
    });
    rerender(target);

    expect(result.current.problem).toBeNull();
  });
});
