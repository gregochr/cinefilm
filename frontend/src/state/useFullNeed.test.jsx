import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, respond } from '../test/mockApi.jsx';
import { useFullNeed } from './useFullNeed.js';

afterEach(() => vi.unstubAllGlobals());

const LIST = fixtures.adminFullNeed;
const FULL = /^\/api\/admin\/films\/(\d+)\/full-need$/;

describe('useFullNeed', () => {
  it('reads the full-subtitles list and applies the row a choice answers, without reading the list again', async () => {
    const puts = [];
    const fetchMock = installFetch((method, url, init) => {
      if (!FULL.test(url)) return null;
      puts.push({ url, body: JSON.parse(init.body) });
      return respond({
        ...LIST.films[1],
        ownerChoice: 'NEEDED',
        need: 'NEEDED',
        reason: 'OWNER',
      });
    });
    const { result } = renderHook(() => useFullNeed('a'));
    await waitFor(() => expect(result.current.rows).toEqual(LIST.films));
    const reads = fetchMock.mock.calls.length;

    await act(() => result.current.choose(4, 'NEEDED'));

    expect(puts).toEqual([{ url: '/api/admin/films/4/full-need', body: { choice: 'NEEDED' } }]);
    expect(result.current.rows.find((r) => r.filmId === 4)).toMatchObject({
      need: 'NEEDED',
      ownerChoice: 'NEEDED',
    });
    expect(fetchMock.mock.calls.length).toBe(reads + 1);
    expect(result.current.saving).toEqual({});
  });

  it('says a scan is running for a 409 and keeps the row as it was', async () => {
    installFetch((method, url) => (FULL.test(url) ? respond({ error: 'scan' }, 409) : null));
    const { result } = renderHook(() => useFullNeed('a'));
    await waitFor(() => expect(result.current.rows).toEqual(LIST.films));

    await act(() => result.current.choose(4, 'NEEDED'));

    expect(result.current.problems).toEqual({ 4: 'A scan is running: try again when it finishes' });
    expect(result.current.rows).toEqual(LIST.films);
  });

  it('exposes the server counts of the list it read', async () => {
    installFetch();
    const { result } = renderHook(() => useFullNeed('a'));

    await waitFor(() => expect(result.current.counts).toEqual(LIST.counts));
  });

  it('calls onSaved with the saved row once a choice is saved, and not for a failed one', async () => {
    const onSaved = vi.fn();
    let fail = true;
    installFetch((method, url) => {
      if (!FULL.test(url)) return null;
      return fail ? respond({ error: 'x' }, 500) : respond({ ...LIST.films[1], need: 'NEEDED' });
    });
    const { result } = renderHook(() => useFullNeed('a', { onSaved }));
    await waitFor(() => expect(result.current.rows).toEqual(LIST.films));

    await act(() => result.current.choose(4, 'NEEDED'));
    expect(onSaved).not.toHaveBeenCalled();

    fail = false;
    await act(() => result.current.choose(4, 'NEEDED'));
    expect(onSaved).toHaveBeenCalledTimes(1);
    expect(onSaved).toHaveBeenCalledWith({ ...LIST.films[1], need: 'NEEDED' });
  });

  it('still calls onSaved, once, when the owner left before the save came back, and sets no state', async () => {
    let release;
    const held = new Promise((resolve) => {
      release = resolve;
    });
    const onSaved = vi.fn();
    installFetch((method, url) =>
      FULL.test(url) ? held.then(() => respond({ ...LIST.films[1], need: 'NEEDED' })) : null,
    );
    const { result, unmount } = renderHook(() => useFullNeed('a', { onSaved }));
    await waitFor(() => expect(result.current.rows).toEqual(LIST.films));
    let choosing;
    act(() => {
      choosing = result.current.choose(4, 'NEEDED');
    });

    unmount();
    release();
    await choosing;

    expect(onSaved).toHaveBeenCalledTimes(1);
    expect(onSaved).toHaveBeenCalledWith({ ...LIST.films[1], need: 'NEEDED' });
  });

  it('sends no waiting choice and does not call onSaved after unmount when the save failed', async () => {
    let release;
    const held = new Promise((resolve) => {
      release = resolve;
    });
    const onSaved = vi.fn();
    const puts = [];
    installFetch((method, url, init) => {
      if (!FULL.test(url)) return null;
      puts.push(JSON.parse(init.body));
      return held.then(() => respond({ error: 'x' }, 500));
    });
    const { result, unmount } = renderHook(() => useFullNeed('a', { onSaved }));
    await waitFor(() => expect(result.current.rows).toEqual(LIST.films));
    let choosing;
    act(() => {
      choosing = result.current.choose(4, 'NEEDED');
      result.current.choose(4, 'NOT_NEEDED');
    });

    unmount();
    release();
    await choosing;

    expect(onSaved).not.toHaveBeenCalled();
    expect(puts).toEqual([{ choice: 'NEEDED' }]);
  });

  it('still calls onSaved once when an earlier write was saved and a queued one fails after the owner left', async () => {
    let releaseFirst;
    let releaseSecond;
    const first = new Promise((resolve) => {
      releaseFirst = resolve;
    });
    const second = new Promise((resolve) => {
      releaseSecond = resolve;
    });
    const onSaved = vi.fn();
    let writes = 0;
    installFetch((method, url) => {
      if (!FULL.test(url)) return null;
      writes += 1;
      return writes === 1
        ? first.then(() => respond({ ...LIST.films[1], need: 'NEEDED' }))
        : second.then(() => respond({ error: 'x' }, 500));
    });
    const { result, unmount } = renderHook(() => useFullNeed('a', { onSaved }));
    await waitFor(() => expect(result.current.rows).toEqual(LIST.films));
    let choosing;
    act(() => {
      choosing = result.current.choose(4, 'NEEDED');
      // Queued behind the first write, sent when it settles.
      result.current.choose(4, 'NOT_NEEDED');
    });

    releaseFirst();
    await waitFor(() => expect(writes).toBe(2));
    unmount();
    releaseSecond();
    await choosing;

    expect(onSaved).toHaveBeenCalledTimes(1);
    expect(onSaved).toHaveBeenCalledWith({ ...LIST.films[1], need: 'NEEDED' });
  });
});
