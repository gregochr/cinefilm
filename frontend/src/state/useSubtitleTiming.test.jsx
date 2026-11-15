import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, respond } from '../test/mockApi.jsx';
import { subtitleTiming } from './subtitleFixCopy.js';
import { useSubtitleTiming } from './useSubtitleTiming.js';

afterEach(() => vi.unstubAllGlobals());

const row = (id, offsetMs, over = {}) => ({
  ...fixtures.adminSubtitles[5],
  id,
  offsetMs,
  version: 100,
  ...over,
});

/** Serves the list `rows` (mutated by an offset PUT, as the server would) and records every call. */
function serve({ rows, answer = () => null }) {
  const calls = [];
  const fetchMock = installFetch((method, url, init) => {
    calls.push(`${method} ${url}${init?.body ? ` ${init.body}` : ''}`);
    const override = answer(method, url);
    if (override) return override;
    if (url === '/api/admin/subtitles') return respond(rows.map((r) => ({ ...r })));
    const put = url.match(/^\/api\/admin\/subtitles\/(\d+)\/offset$/);
    if (method === 'PUT' && put) {
      const target = rows.find((r) => r.id === Number(put[1]));
      target.offsetMs = JSON.parse(init.body).offsetMs;
      target.version += 1;
      return respond({ ...target });
    }
    return null;
  });
  return { fetchMock, calls };
}

const open = (over = {}) => ({
  kind: 'FULL',
  ids: [1],
  enabled: true,
  onApplied: vi.fn(),
  ...over,
});

describe('useSubtitleTiming', () => {
  it('reads the offsets while enabled, and not before the panel is open', async () => {
    const { calls } = serve({ rows: [row(1, 500)] });
    const { result, rerender } = renderHook((props) => useSubtitleTiming(props), {
      initialProps: open({ enabled: false }),
    });

    expect(result.current.available).toBe(true);
    expect(calls).toEqual([]);
    rerender(open({ enabled: true }));

    await waitFor(() => expect(result.current.offsets).toEqual([500]));
    expect(calls).toEqual(['GET /api/admin/subtitles']);
  });

  it('is not available, and asks for nothing, when no file shows', async () => {
    const { calls } = serve({ rows: [row(1, 500)] });
    const { result } = renderHook(() => useSubtitleTiming(open({ kind: null, ids: undefined })));

    await act(() => result.current.later());

    expect(result.current.available).toBe(false);
    expect(calls).toEqual([]);
  });

  it('Later sends the nudged absolute offset for the row, tells the player with the server’s answer, and reads the new offset', async () => {
    const { calls } = serve({ rows: [row(1, 500)] });
    const props = open();
    const { result } = renderHook(() => useSubtitleTiming(props));
    await waitFor(() => expect(result.current.offsets).toEqual([500]));

    await act(() => result.current.later());

    expect(calls.slice(1)).toEqual([
      'GET /api/admin/subtitles',
      'PUT /api/admin/subtitles/1/offset {"offsetMs":750}',
    ]);
    expect(props.onApplied).toHaveBeenCalledExactlyOnceWith('FULL', '101_750');
    expect(result.current.offsets).toEqual([750]);
    expect(result.current.busy).toBe(false);
    expect(result.current.problem).toBeNull();
  });

  it('Earlier sends a quarter second less, and Reset sends 0', async () => {
    const { calls } = serve({ rows: [row(1, 500)] });
    const { result } = renderHook(() => useSubtitleTiming(open()));

    await act(() => result.current.earlier());
    await act(() => result.current.reset());

    expect(calls.filter((c) => c.startsWith('PUT'))).toEqual([
      'PUT /api/admin/subtitles/1/offset {"offsetMs":250}',
      'PUT /api/admin/subtitles/1/offset {"offsetMs":0}',
    ]);
  });

  it('moves every part of a split film from its own offset, one request after the other', async () => {
    const { calls } = serve({ rows: [row(1, 1000), row(2, -500)] });
    const props = open({ ids: [1, 2] });
    const { result } = renderHook(() => useSubtitleTiming(props));

    await act(() => result.current.later());

    expect(calls.filter((c) => c.startsWith('PUT'))).toEqual([
      'PUT /api/admin/subtitles/1/offset {"offsetMs":1250}',
      'PUT /api/admin/subtitles/2/offset {"offsetMs":-250}',
    ]);
    expect(props.onApplied).toHaveBeenCalledWith('FULL', '101_1250-101_-250');
    expect(result.current.offsets).toEqual([1250, -250]);
  });

  it('sends nothing when no row would move (a reset at 0)', async () => {
    const { calls } = serve({ rows: [row(1, 0)] });
    const props = open();
    const { result } = renderHook(() => useSubtitleTiming(props));

    await act(() => result.current.reset());

    expect(calls.some((c) => c.startsWith('PUT'))).toBe(false);
    expect(props.onApplied).not.toHaveBeenCalled();
  });

  it('two presses in one frame send one change; it is busy until the answer', async () => {
    let release;
    const gate = new Promise((resolve) => {
      release = resolve;
    });
    const rows = [row(1, 0)];
    const { calls } = serve({
      rows,
      answer: (method) => (method === 'PUT' ? gate.then(() => respond({ ...rows[0] })) : null),
    });
    const { result } = renderHook(() => useSubtitleTiming(open({ enabled: false })));

    await act(async () => {
      result.current.later();
      result.current.later();
      await Promise.resolve();
    });
    await waitFor(() => expect(result.current.busy).toBe(true));
    release();
    await waitFor(() => expect(result.current.busy).toBe(false));

    expect(calls.filter((c) => c.startsWith('PUT'))).toHaveLength(1);
    expect(calls.filter((c) => c === 'GET /api/admin/subtitles')).toHaveLength(1);
  });

  it('says one plain line when the change fails, leaves the track alone, and clears it on the next try', async () => {
    let refuse = true;
    const { calls } = serve({
      rows: [row(1, 0)],
      answer: (method) => (method === 'PUT' && refuse ? respond({ error: 'x' }, 500) : null),
    });
    const props = open();
    const { result } = renderHook(() => useSubtitleTiming(props));

    await act(() => result.current.later());

    expect(result.current.problem).toBe(subtitleTiming.failed);
    expect(props.onApplied).not.toHaveBeenCalled();
    refuse = false;
    await act(() => result.current.later());
    expect(result.current.problem).toBeNull();
    expect(props.onApplied).toHaveBeenCalledTimes(1);
    expect(calls.filter((c) => c.startsWith('PUT'))).toHaveLength(2);
  });

  it('shows the server’s own words for a refusal', async () => {
    serve({
      rows: [row(1, 0)],
      answer: (method) =>
        method === 'PUT' ? respond({ error: 'The offset must be within 10 minutes' }, 400) : null,
    });
    const { result } = renderHook(() => useSubtitleTiming(open()));

    await act(() => result.current.later());

    expect(result.current.problem).toBe('The offset must be within 10 minutes');
  });

  it('after a partial failure the track is still told, and the rows that did change show', async () => {
    const rows = [row(1, 0), row(2, 0)];
    serve({
      rows,
      answer: (method, url) =>
        method === 'PUT' && url.endsWith('/2/offset') ? respond({ error: 'x' }, 500) : null,
    });
    const props = open({ ids: [1, 2] });
    const { result } = renderHook(() => useSubtitleTiming(props));

    await act(() => result.current.later());

    expect(props.onApplied).toHaveBeenCalledExactlyOnceWith('FULL', '101_250');
    expect(result.current.problem).toBe(subtitleTiming.failed);
    expect(result.current.offsets).toEqual([250, 0]);
  });

  it('says so, and changes nothing, when the list no longer has the shown file or only some of its parts', async () => {
    const { calls } = serve({ rows: [row(1, 0)] });
    const { result, rerender } = renderHook((props) => useSubtitleTiming(props), {
      initialProps: open({ ids: [9], enabled: false }),
    });

    await act(() => result.current.later());
    expect(result.current.problem).toBe(subtitleTiming.lookupFailed);

    rerender(open({ ids: [1, 9], enabled: false }));
    await act(() => result.current.later());
    expect(result.current.problem).toBe(subtitleTiming.changed);
    expect(calls.some((c) => c.startsWith('PUT'))).toBe(false);
  });

  it('says one plain line when the list cannot be read', async () => {
    serve({
      rows: [],
      answer: (m, url) => (url === '/api/admin/subtitles' ? respond({}, 500) : null),
    });
    const { result } = renderHook(() => useSubtitleTiming(open({ enabled: false })));

    await act(() => result.current.later());

    expect(result.current.problem).toBe(subtitleTiming.failed);
  });

  it('forgets a problem when another file shows, and does not bring it back', async () => {
    serve({
      rows: [row(1, 0), row(2, 0)],
      answer: (method) => (method === 'PUT' ? respond({ error: 'x' }, 500) : null),
    });
    const { result, rerender } = renderHook((props) => useSubtitleTiming(props), {
      initialProps: open({ enabled: false }),
    });
    await act(() => result.current.later());
    expect(result.current.problem).toBe(subtitleTiming.failed);

    rerender(open({ kind: 'FORCED', ids: [2], enabled: false }));
    expect(result.current.problem).toBeNull();
    rerender(open({ enabled: false }));
    expect(result.current.problem).toBeNull();
  });

  it('a late answer after another file shows is not applied to it, but the changed file’s track is still told', async () => {
    let release;
    const gate = new Promise((resolve) => {
      release = resolve;
    });
    const rows = [row(1, 0), row(2, 4000)];
    serve({
      rows,
      answer: (method, url) =>
        method === 'PUT' && url.endsWith('/1/offset')
          ? gate.then(() => respond({ ...rows[0], offsetMs: 250, version: 101 }))
          : null,
    });
    const props = open({ enabled: false });
    const { result, rerender } = renderHook((p) => useSubtitleTiming(p), { initialProps: props });
    await act(async () => {
      result.current.later();
      await Promise.resolve();
    });

    rerender(open({ kind: 'FORCED', ids: [2], enabled: false, onApplied: props.onApplied }));
    release();
    await waitFor(() => expect(props.onApplied).toHaveBeenCalledWith('FULL', '101_250'));

    expect(result.current.offsets).toBeNull();
    expect(result.current.busy).toBe(false);
    expect(result.current.problem).toBeNull();
  });

  it('a read that started before a change does not overwrite the new offsets', async () => {
    let release;
    const gate = new Promise((resolve) => {
      release = resolve;
    });
    const rows = [row(1, 0)];
    let reads = 0;
    serve({
      rows,
      answer: (method, url) => {
        if (url !== '/api/admin/subtitles') return null;
        reads += 1;
        // The first read (the panel opening) is slow and carries the old offset.
        return reads === 1 ? gate.then(() => respond([{ ...rows[0], offsetMs: 0 }])) : null;
      },
    });
    const { result } = renderHook(() => useSubtitleTiming(open()));

    await act(() => result.current.later());
    release();
    await act(async () => {
      await gate;
    });

    expect(result.current.offsets).toEqual([250]);
  });

  it('says nothing and does not tell the player after it is gone', async () => {
    let release;
    const gate = new Promise((resolve) => {
      release = resolve;
    });
    const rows = [row(1, 0)];
    serve({
      rows,
      answer: (method) => (method === 'PUT' ? gate.then(() => respond({ ...rows[0] })) : null),
    });
    const props = open({ enabled: false });
    const { result, unmount } = renderHook(() => useSubtitleTiming(props));
    await act(async () => {
      result.current.later();
      await Promise.resolve();
    });
    await waitFor(() => expect(result.current.busy).toBe(true));

    unmount();
    release();
    await act(async () => {
      await gate;
    });

    expect(props.onApplied).not.toHaveBeenCalled();
  });
});
