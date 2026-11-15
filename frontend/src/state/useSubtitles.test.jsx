import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, respond } from '../test/mockApi.jsx';
import { useSubtitles } from './useSubtitles.js';

afterEach(() => vi.unstubAllGlobals());

describe('useSubtitles', () => {
  it('keeps a removed download out of the list even when a refresh already in flight still lists it', async () => {
    installFetch((method, url) => {
      if (method === 'DELETE' && url === '/api/admin/subtitles/6') return respond(null, 204);
      return null;
    });
    const { result } = renderHook(() => useSubtitles('v'));
    await waitFor(() => expect(result.current.items).toEqual(fixtures.adminSubtitles));

    await act(() => result.current.remove(6));

    // The list read again still shows it (the mock's list never changes), but the removed row stays out.
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.items.map((r) => r.id)).not.toContain(6);
    expect(result.current.items).toHaveLength(fixtures.adminSubtitles.length - 1);
  });

  it('draws the row an action answered before the list is read again', async () => {
    let lists = 0;
    let release;
    const gate = new Promise((resolve) => {
      release = resolve;
    });
    installFetch((method, url) => {
      if (url === '/api/admin/subtitles') {
        lists += 1;
        // The first read is the page's; the re-read after the action is held open.
        return lists === 1
          ? respond(fixtures.adminSubtitles)
          : gate.then(() => respond(fixtures.adminSubtitles));
      }
      if (url === '/api/admin/subtitles/1/verify') {
        return respond({ ...fixtures.adminSubtitles[0], verified: true });
      }
      return null;
    });
    const { result } = renderHook(() => useSubtitles('v'));
    await waitFor(() => expect(result.current.items).toEqual(fixtures.adminSubtitles));
    expect(result.current.items.find((r) => r.id === 1).verified).toBe(false);

    await act(() => result.current.verify(1));

    expect(lists).toBe(2);
    expect(result.current.items.find((r) => r.id === 1).verified).toBe(true);
    await act(async () => release());
  });

  it('resolves the row the server answered to an undo, so the caller can read what it recomputed', async () => {
    installFetch((method, url) =>
      method === 'DELETE' && url === '/api/admin/subtitles/1/verify'
        ? respond({ ...fixtures.adminSubtitles[0], replacedByDownload: true })
        : null,
    );
    const { result } = renderHook(() => useSubtitles('v'));
    await waitFor(() => expect(result.current.items).toEqual(fixtures.adminSubtitles));

    let answer;
    await act(async () => {
      answer = await result.current.unverify(1);
    });

    expect(answer).toMatchObject({ id: 1, replacedByDownload: true });
  });

  it('keeps the answers of two actions while the list has not been read again', async () => {
    let lists = 0;
    let release;
    const gate = new Promise((resolve) => {
      release = resolve;
    });
    installFetch((method, url) => {
      if (url === '/api/admin/subtitles') {
        lists += 1;
        return lists === 1
          ? respond(fixtures.adminSubtitles)
          : gate.then(() => respond(fixtures.adminSubtitles));
      }
      const match = url.match(/^\/api\/admin\/subtitles\/(\d+)\/verify$/);
      if (match && method === 'POST') {
        const row = fixtures.adminSubtitles.find((r) => r.id === Number(match[1]));
        return respond({ ...row, verified: true });
      }
      return null;
    });
    const { result } = renderHook(() => useSubtitles('v'));
    await waitFor(() => expect(result.current.items).toEqual(fixtures.adminSubtitles));

    await act(() => result.current.verify(1));
    await act(() => result.current.verify(2));

    const verified = (id) => result.current.items.find((r) => r.id === id).verified;
    expect(verified(1)).toBe(true);
    expect(verified(2)).toBe(true);
    await act(async () => release());
  });

  it('resolves true when the server answered the undo without a row', async () => {
    installFetch((method, url) =>
      method === 'DELETE' && url === '/api/admin/subtitles/1/verify' ? respond(null, 204) : null,
    );
    const { result } = renderHook(() => useSubtitles('v'));
    await waitFor(() => expect(result.current.items).toEqual(fixtures.adminSubtitles));

    let answer;
    await act(async () => {
      answer = await result.current.unverify(1);
    });

    expect(answer).toBe(true);
  });

  it('resolves false when the undo failed', async () => {
    installFetch((method, url) =>
      method === 'DELETE' && url === '/api/admin/subtitles/1/verify'
        ? respond({ error: 'x' }, 500)
        : null,
    );
    const { result } = renderHook(() => useSubtitles('v'));
    await waitFor(() => expect(result.current.items).toEqual(fixtures.adminSubtitles));

    let answer;
    await act(async () => {
      answer = await result.current.unverify(1);
    });

    expect(answer).toBe(false);
  });

  it('drops a second action while one is in flight, so only one request is sent', async () => {
    let release;
    const gate = new Promise((resolve) => {
      release = resolve;
    });
    const fetchMock = installFetch((method, url) =>
      url === '/api/admin/subtitles/1/verify' ? gate.then(() => respond({ id: 1 })) : null,
    );
    const { result } = renderHook(() => useSubtitles('v'));
    await waitFor(() => expect(result.current.items).toEqual(fixtures.adminSubtitles));

    let first;
    let second;
    await act(async () => {
      first = result.current.verify(1);
      second = result.current.unverify(1);
      release();
      await Promise.all([first, second]);
    });

    expect(await second).toBe(false);
    const calls = fetchMock.mock.calls.filter((c) => c[0] === '/api/admin/subtitles/1/verify');
    expect(calls.map((c) => c[1].method)).toEqual(['POST']);
  });

  it('calls onChanged after an action that succeeded, and not after one that failed', async () => {
    const onChanged = vi.fn();
    let fail = true;
    installFetch((method, url) => {
      if (url !== '/api/admin/subtitles/1/verify') return null;
      return fail ? respond({ error: 'x' }, 500) : respond({ id: 1 });
    });
    const { result } = renderHook(() => useSubtitles('v', onChanged));
    await waitFor(() => expect(result.current.items).toEqual(fixtures.adminSubtitles));

    await act(() => result.current.verify(1));
    expect(onChanged).not.toHaveBeenCalled();

    fail = false;
    await act(() => result.current.verify(1));
    expect(onChanged).toHaveBeenCalledTimes(1);
  });
});
