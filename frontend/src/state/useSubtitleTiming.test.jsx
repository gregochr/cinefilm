import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, respond } from '../test/mockApi.jsx';
import { ADMIN_TIMEOUT_MS } from '../api/timeout.js';
import { subtitleTiming } from './subtitleFixCopy.js';
import { useSubtitleTiming } from './useSubtitleTiming.js';

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

const row = (id, offsetMs, over = {}) => ({
  ...fixtures.adminSubtitles[5],
  id,
  offsetMs,
  version: 100,
  served: true,
  ...over,
});

/** Serves the offset route over `rows` (changed as the server would) and records every call. */
function serve({ rows, answer = () => null }) {
  const calls = [];
  installFetch((method, url, init) => {
    calls.push(`${method} ${url}${init?.body ? ` ${init.body}` : ''}`);
    const override = answer(method, url);
    if (override) return override;
    const put = url.match(/^\/api\/admin\/subtitles\/(\d+)\/offset$/);
    if (method === 'PUT' && put) {
      const target = rows.find((r) => r.id === Number(put[1]));
      target.offsetMs = JSON.parse(init.body).offsetMs;
      target.version += 1;
      return respond({ ...target });
    }
    return null;
  });
  return { calls };
}

const open = (over = {}) => ({
  kind: 'FULL',
  ids: [1],
  offsets: [500],
  onApplied: vi.fn(),
  ...over,
});

describe('useSubtitleTiming', () => {
  it('knows the offsets from the film and sends nothing until a press', () => {
    const { calls } = serve({ rows: [row(1, 500)] });
    const { result } = renderHook(() => useSubtitleTiming(open()));

    expect(result.current.available).toBe(true);
    expect(result.current.offsets).toEqual([500]);
    expect(calls).toEqual([]);
  });

  it.each([
    ['no file shows', { kind: null, ids: undefined, offsets: undefined }],
    ['the film gave no offsets', { offsets: undefined }],
    ['the offsets do not match the rows', { ids: [1, 2], offsets: [0] }],
  ])('is not available, and asks for nothing, when %s', async (_name, over) => {
    const { calls } = serve({ rows: [row(1, 500)] });
    const { result } = renderHook(() => useSubtitleTiming(open(over)));

    await act(() => result.current.later());

    expect(result.current.available).toBe(false);
    expect(result.current.offsets).toBeNull();
    expect(calls).toEqual([]);
  });

  it('Later sends only the nudged absolute offset, tells the player with the server’s answer, and shows the new offset', async () => {
    const { calls } = serve({ rows: [row(1, 500)] });
    const props = open();
    const { result } = renderHook(() => useSubtitleTiming(props));

    await act(() => result.current.later());

    expect(calls).toEqual(['PUT /api/admin/subtitles/1/offset {"offsetMs":750}']);
    expect(props.onApplied).toHaveBeenCalledExactlyOnceWith('FULL', '101_750', [750]);
    expect(result.current.offsets).toEqual([750]);
    expect(result.current.busy).toBe(false);
    expect(result.current.problem).toBeNull();
  });

  it('a second press works from the answer of the first, not from the film’s offsets', async () => {
    const { calls } = serve({ rows: [row(1, 500)] });
    const { result } = renderHook(() => useSubtitleTiming(open()));

    await act(() => result.current.later());
    await act(() => result.current.later());
    await act(() => result.current.earlier());
    await act(() => result.current.reset());

    expect(calls).toEqual([
      'PUT /api/admin/subtitles/1/offset {"offsetMs":750}',
      'PUT /api/admin/subtitles/1/offset {"offsetMs":1000}',
      'PUT /api/admin/subtitles/1/offset {"offsetMs":750}',
      'PUT /api/admin/subtitles/1/offset {"offsetMs":0}',
    ]);
  });

  it('moves every part of a split film from its own offset, one request after the other', async () => {
    const { calls } = serve({ rows: [row(1, 1000), row(2, -500)] });
    const props = open({ ids: [1, 2], offsets: [1000, -500] });
    const { result } = renderHook(() => useSubtitleTiming(props));

    await act(() => result.current.later());

    expect(calls).toEqual([
      'PUT /api/admin/subtitles/1/offset {"offsetMs":1250}',
      'PUT /api/admin/subtitles/2/offset {"offsetMs":-250}',
    ]);
    expect(props.onApplied).toHaveBeenCalledWith('FULL', '101_1250-101_-250', [1250, -250]);
    expect(result.current.offsets).toEqual([1250, -250]);
  });

  it('sends nothing when no row would move (a reset at 0)', async () => {
    const { calls } = serve({ rows: [row(1, 0)] });
    const props = open({ offsets: [0] });
    const { result } = renderHook(() => useSubtitleTiming(props));

    await act(() => result.current.reset());

    expect(calls).toEqual([]);
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
    const { result } = renderHook(() => useSubtitleTiming(open({ offsets: [0] })));

    await act(async () => {
      result.current.later();
      result.current.later();
      await Promise.resolve();
    });
    await waitFor(() => expect(result.current.busy).toBe(true));
    release();
    await waitFor(() => expect(result.current.busy).toBe(false));

    expect(calls).toHaveLength(1);
  });

  it('says one plain line when the change fails, leaves the track alone, and clears it on the next try', async () => {
    let refuse = true;
    const { calls } = serve({
      rows: [row(1, 0)],
      answer: (method) => (method === 'PUT' && refuse ? respond({ error: 'x' }, 500) : null),
    });
    const props = open({ offsets: [0] });
    const { result } = renderHook(() => useSubtitleTiming(props));

    await act(() => result.current.later());

    expect(result.current.problem).toBe(subtitleTiming.failed);
    expect(result.current.offsets).toEqual([0]);
    expect(props.onApplied).not.toHaveBeenCalled();
    refuse = false;
    await act(() => result.current.later());
    expect(result.current.problem).toBeNull();
    expect(props.onApplied).toHaveBeenCalledTimes(1);
    expect(calls).toHaveLength(2);
  });

  it('shows the server’s own words for a refusal', async () => {
    serve({
      rows: [row(1, 0)],
      answer: (method) =>
        method === 'PUT' ? respond({ error: 'The offset must be within 10 minutes' }, 400) : null,
    });
    const { result } = renderHook(() => useSubtitleTiming(open({ offsets: [0] })));

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
    const props = open({ ids: [1, 2], offsets: [0, 0] });
    const { result } = renderHook(() => useSubtitleTiming(props));

    await act(() => result.current.later());

    expect(props.onApplied).toHaveBeenCalledExactlyOnceWith('FULL', '101_250', [250, 0]);
    expect(result.current.problem).toBe(subtitleTiming.failed);
    expect(result.current.offsets).toEqual([250, 0]);
  });

  it('does not reload the track when the server no longer serves the changed file, and freezes that file’s timing', async () => {
    const rows = [row(1, 0)];
    serve({
      rows,
      answer: (method) =>
        method === 'PUT' ? respond({ ...rows[0], offsetMs: 250, served: false }) : null,
    });
    const props = open({ offsets: [0] });
    const { result, rerender } = renderHook((p) => useSubtitleTiming(p), { initialProps: props });

    await act(() => result.current.later());

    expect(props.onApplied).not.toHaveBeenCalled();
    expect(result.current.problem).toBe(subtitleTiming.changed);
    expect(result.current.frozen).toBe(true);
    expect(result.current.offsets).toEqual([250]);
    // The freeze and its line belong to that file: another file has neither, and it is back when that file shows again.
    rerender(open({ kind: 'FORCED', ids: [2], offsets: [0], onApplied: props.onApplied }));
    expect(result.current.frozen).toBe(false);
    expect(result.current.problem).toBeNull();
    rerender(props);
    expect(result.current.frozen).toBe(true);
    expect(result.current.problem).toBe(subtitleTiming.changed);
  });

  it('a change the server does not serve cancels what was still loading, and a frozen file takes no press and no retry', async () => {
    const rows = [row(1, 0)];
    const { calls } = serve({
      rows,
      answer: (method) =>
        method === 'PUT' ? respond({ ...rows[0], offsetMs: 250, served: false }) : null,
    });
    const props = open({ offsets: [0] });
    const onReplaced = vi.fn();
    const { result, rerender } = renderHook((p) => useSubtitleTiming(p), {
      initialProps: { ...props, onReplaced },
    });

    await act(() => result.current.later());
    expect(onReplaced).toHaveBeenCalledExactlyOnceWith('FULL');

    // The screen differs from the server, which would be a retry; frozen, nothing is sent or loaded.
    rerender({ ...props, onReplaced, onScreen: [0] });
    await act(() => result.current.later());
    await act(() => result.current.reset());
    expect(calls).toHaveLength(1);
    expect(props.onApplied).not.toHaveBeenCalled();
    expect(onReplaced).toHaveBeenCalledTimes(1);
  });

  it('stops at the first row the server no longer serves: later rows are not sent and nothing reloads', async () => {
    const rows = [row(1, 0), row(2, 0), row(3, 0)];
    const { calls } = serve({
      rows,
      answer: (method, url) =>
        method === 'PUT' && url.endsWith('/2/offset')
          ? respond({ ...rows[1], offsetMs: 250, served: false })
          : null,
    });
    const props = open({ ids: [1, 2, 3], offsets: [0, 0, 0] });
    const { result } = renderHook(() => useSubtitleTiming(props));

    await act(() => result.current.later());

    expect(calls).toEqual([
      'PUT /api/admin/subtitles/1/offset {"offsetMs":250}',
      'PUT /api/admin/subtitles/2/offset {"offsetMs":250}',
    ]);
    expect(props.onApplied).not.toHaveBeenCalled();
    expect(result.current.frozen).toBe(true);
    expect(result.current.offsets).toEqual([250, 250, 0]);
  });

  it('a press with nothing to send retries the last timing when the screen differs from the server, and otherwise leaves the line alone', async () => {
    serve({ rows: [row(1, 500)] });
    const props = open();
    const { result, rerender } = renderHook((p) => useSubtitleTiming(p), { initialProps: props });
    await act(() => result.current.reset());
    expect(props.onApplied).toHaveBeenCalledExactlyOnceWith('FULL', '101_0', [0]);

    // The new track did not load: the screen still shows +0.5 s while the server is at 0. Reset has nothing to send.
    rerender({ ...props, onScreen: [500] });
    act(() => result.current.swapFailed('FULL'));
    expect(result.current.problem).toBe(subtitleTiming.swapFailed);
    await act(() => result.current.reset());

    expect(props.onApplied).toHaveBeenCalledTimes(2);
    expect(props.onApplied).toHaveBeenLastCalledWith('FULL', '101_0', [0]);
    expect(result.current.problem).toBeNull();

    // On screen and on the server the same: nothing to send, nothing to load.
    rerender({ ...props, onScreen: [0] });
    await act(() => result.current.reset());
    expect(props.onApplied).toHaveBeenCalledTimes(2);
  });

  it('forgets a problem when another file shows, and does not bring it back', async () => {
    serve({
      rows: [row(1, 0), row(2, 0)],
      answer: (method) => (method === 'PUT' ? respond({ error: 'x' }, 500) : null),
    });
    const { result, rerender } = renderHook((props) => useSubtitleTiming(props), {
      initialProps: open({ offsets: [0] }),
    });
    await act(() => result.current.later());
    expect(result.current.problem).toBe(subtitleTiming.failed);

    rerender(open({ kind: 'FORCED', ids: [2], offsets: [0] }));
    expect(result.current.problem).toBeNull();
    rerender(open({ offsets: [0] }));
    expect(result.current.problem).toBeNull();
  });

  it('keeps the offsets it learned for a file while another one shows', async () => {
    serve({ rows: [row(1, 0), row(2, 4000)] });
    const { result, rerender } = renderHook((props) => useSubtitleTiming(props), {
      initialProps: open({ offsets: [0] }),
    });
    await act(() => result.current.later());

    rerender(open({ kind: 'FORCED', ids: [2], offsets: [4000] }));
    expect(result.current.offsets).toEqual([4000]);
    rerender(open({ offsets: [0] }));

    expect(result.current.offsets).toEqual([250]);
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
    const props = open({ offsets: [0] });
    const { result, rerender } = renderHook((p) => useSubtitleTiming(p), { initialProps: props });
    await act(async () => {
      result.current.later();
      await Promise.resolve();
    });

    rerender(open({ kind: 'FORCED', ids: [2], offsets: [4000], onApplied: props.onApplied }));
    release();
    await waitFor(() => expect(props.onApplied).toHaveBeenCalledWith('FULL', '101_250', [250]));

    expect(result.current.offsets).toEqual([4000]);
    expect(result.current.busy).toBe(false);
    expect(result.current.problem).toBeNull();
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
    const props = open({ offsets: [0] });
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

  it('swapFailed tells the viewer the new timing is saved but not loaded, only while that kind shows', () => {
    const { result, rerender } = renderHook((props) => useSubtitleTiming(props), {
      initialProps: open(),
    });

    act(() => result.current.swapFailed('FORCED'));
    expect(result.current.problem).toBeNull();
    act(() => result.current.swapFailed('FULL'));
    expect(result.current.problem).toBe(subtitleTiming.swapFailed);

    rerender(open({ kind: 'FORCED', ids: [2], offsets: [0] }));
    expect(result.current.problem).toBeNull();
  });

  describe('a new track that failed to load', () => {
    // Later was saved (+0.5 s -> +0.75 s) and its track failed: the server holds +0.75 s, the screen still +0.5 s.
    async function failedLater() {
      const served = serve({ rows: [row(1, 500)] });
      const props = open();
      const hook = renderHook((p) => useSubtitleTiming(p), { initialProps: props });
      await act(() => hook.result.current.later());
      hook.rerender({ ...props, onScreen: [500] });
      act(() => hook.result.current.swapFailed('FULL'));
      return { ...served, ...hook, props };
    }

    it.each([
      ['Earlier', 'earlier'],
      ['Later', 'later'],
      ['Reset', 'reset'],
    ])(
      'the first %s press loads the saved timing again and sends nothing, then the next press moves',
      async (_name, press) => {
        const { calls, result, props } = await failedLater();
        expect(calls).toEqual(['PUT /api/admin/subtitles/1/offset {"offsetMs":750}']);
        expect(result.current.problem).toBe(subtitleTiming.swapFailed);

        await act(() => result.current[press]());

        expect(calls).toHaveLength(1);
        expect(props.onApplied).toHaveBeenCalledTimes(2);
        expect(props.onApplied).toHaveBeenLastCalledWith('FULL', '101_750', [750]);
        expect(result.current.problem).toBeNull();

        // The viewer has now seen the saved timing (or the retry works): the next press is a real step.
        await act(() => result.current.earlier());
        expect(calls).toHaveLength(2);
        expect(calls[1]).toBe('PUT /api/admin/subtitles/1/offset {"offsetMs":500}');
      },
    );

    it('the online event loads it again by itself, once, and sends nothing', async () => {
      const { calls, result, props } = await failedLater();

      await act(async () => {
        window.dispatchEvent(new Event('online'));
      });

      expect(props.onApplied).toHaveBeenCalledTimes(2);
      expect(props.onApplied).toHaveBeenLastCalledWith('FULL', '101_750', [750]);
      expect(result.current.problem).toBeNull();
      await act(async () => {
        window.dispatchEvent(new Event('online'));
      });
      expect(props.onApplied).toHaveBeenCalledTimes(2);
      expect(calls).toHaveLength(1);
    });

    it('a second failure keeps the retry going on the next online event', async () => {
      const { result, props } = await failedLater();
      await act(async () => {
        window.dispatchEvent(new Event('online'));
      });
      act(() => result.current.swapFailed('FULL'));
      expect(result.current.problem).toBe(subtitleTiming.swapFailed);

      await act(async () => {
        window.dispatchEvent(new Event('online'));
      });

      expect(props.onApplied).toHaveBeenCalledTimes(3);
    });

    it('does nothing on online when no load failed, or after the player is gone', async () => {
      const served = serve({ rows: [row(1, 500)] });
      const props = open();
      const { result, unmount } = renderHook(() => useSubtitleTiming(props));
      await act(() => result.current.later());

      await act(async () => {
        window.dispatchEvent(new Event('online'));
      });
      expect(props.onApplied).toHaveBeenCalledTimes(1);

      act(() => result.current.swapFailed('FULL'));
      unmount();
      window.dispatchEvent(new Event('online'));
      expect(props.onApplied).toHaveBeenCalledTimes(1);
      expect(served.calls).toHaveLength(1);
    });
  });

  describe('a request with no answer', () => {
    it('fails after the timeout with the plain line, unblocks the buttons, and the next press can send', async () => {
      vi.useFakeTimers();
      let hang = true;
      const { calls } = serve({
        rows: [row(1, 500)],
        answer: (method) => (method === 'PUT' && hang ? new Promise(() => {}) : null),
      });
      const props = open();
      const { result } = renderHook(() => useSubtitleTiming(props));

      await act(async () => {
        void result.current.later();
        await vi.advanceTimersByTimeAsync(ADMIN_TIMEOUT_MS - 1);
      });
      expect(result.current.busy).toBe(true);
      await act(async () => {
        await vi.advanceTimersByTimeAsync(1);
      });

      expect(result.current.busy).toBe(false);
      expect(result.current.problem).toBe(subtitleTiming.failed);
      expect(props.onApplied).not.toHaveBeenCalled();
      // Offsets are absolute: asking again sends the same value, never a second step.
      hang = false;
      await act(() => result.current.later());
      expect(calls).toEqual([
        'PUT /api/admin/subtitles/1/offset {"offsetMs":750}',
        'PUT /api/admin/subtitles/1/offset {"offsetMs":750}',
      ]);
      expect(props.onApplied).toHaveBeenCalledExactlyOnceWith('FULL', '101_750', [750]);
    });

    it('a split film whose second part got no answer re-sends both targets on the next press, never "differs by part"', async () => {
      vi.useFakeTimers();
      const rows = [row(1, 0), row(2, 0)];
      let hang = true;
      const { calls } = serve({
        rows,
        answer: (method, url) => {
          if (method === 'PUT' && url.endsWith('/2/offset') && hang) {
            rows[1].offsetMs = 250; // the server saved it; the answer never arrived
            return new Promise(() => {});
          }
          return null;
        },
      });
      const props = open({ ids: [1, 2], offsets: [0, 0] });
      const { result } = renderHook(() => useSubtitleTiming(props));

      await act(async () => {
        void result.current.later();
        await vi.advanceTimersByTimeAsync(ADMIN_TIMEOUT_MS);
      });

      expect(result.current.problem).toBe(subtitleTiming.failed);
      expect(result.current.offsets).toEqual([0, 0]);
      expect(props.onApplied).not.toHaveBeenCalled();
      hang = false;
      await act(() => result.current.later());

      expect(calls.slice(2)).toEqual([
        'PUT /api/admin/subtitles/1/offset {"offsetMs":250}',
        'PUT /api/admin/subtitles/2/offset {"offsetMs":250}',
      ]);
      expect(result.current.offsets).toEqual([250, 250]);
      expect(result.current.problem).toBeNull();
      expect(props.onApplied).toHaveBeenCalledTimes(1);
    });

    it('a failed retry keeps the targets: the next press finishes them before anything moves', async () => {
      vi.useFakeTimers();
      const rows = [row(1, 0), row(2, 0)];
      let mode = 'hang';
      const { calls } = serve({
        rows,
        answer: (method, url) => {
          if (method !== 'PUT') return null;
          if (mode === 'hang' && url.endsWith('/1/offset')) {
            rows[0].offsetMs = 250;
            rows[1].offsetMs = 250; // the server saved both; the answer never arrived
            return new Promise(() => {});
          }
          return mode === 'refuse' ? respond({ error: 'boom' }, 500) : null;
        },
      });
      const props = open({ ids: [1, 2], offsets: [0, 0] });
      const { result } = renderHook(() => useSubtitleTiming(props));
      await act(async () => {
        void result.current.later();
        await vi.advanceTimersByTimeAsync(ADMIN_TIMEOUT_MS);
      });
      mode = 'refuse';
      await act(() => result.current.later());
      expect(result.current.problem).toBe(subtitleTiming.failed);

      mode = 'ok';
      await act(() => result.current.earlier());

      // Earlier did not move from the stale [0, 0]: the saved [250, 250] was finished first.
      expect(calls.slice(2)).toEqual([
        'PUT /api/admin/subtitles/1/offset {"offsetMs":250}',
        'PUT /api/admin/subtitles/2/offset {"offsetMs":250}',
      ]);
      expect(result.current.offsets).toEqual([250, 250]);
      await act(() => result.current.earlier());
      expect(calls.slice(4)).toEqual([
        'PUT /api/admin/subtitles/1/offset {"offsetMs":0}',
        'PUT /api/admin/subtitles/2/offset {"offsetMs":0}',
      ]);
    });
  });
});
