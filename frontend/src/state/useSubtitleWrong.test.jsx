import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, respond } from '../test/mockApi.jsx';
import { subtitlesWrong } from './subtitleFixCopy.js';
import { useSubtitleWrong } from './useSubtitleWrong.js';

afterEach(() => vi.unstubAllGlobals());

// Film 6 (Blade Runner) edition 61 has a played forced download, row 6 in the fixtures.
const target = { filmId: 6, editionId: 61 };
const calls = (fetchMock) => fetchMock.mock.calls.map((c) => `${c[1]?.method ?? 'GET'} ${c[0]}`);

describe('useSubtitleWrong', () => {
  it('reads the list, then deletes the download that plays, and nothing else', async () => {
    const fetchMock = installFetch();
    const { result } = renderHook(() => useSubtitleWrong(target));

    let done;
    await act(async () => {
      done = await result.current.report('FORCED');
    });

    expect(done).toBe(true);
    expect(calls(fetchMock)).toEqual(['GET /api/admin/subtitles', 'DELETE /api/admin/subtitles/6']);
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
      first = result.current.report('FORCED');
      second = result.current.report('FORCED');
      await Promise.resolve();
    });
    expect(await second).toBe(false);
    release();
    await act(async () => {
      expect(await first).toBe(true);
    });

    expect(calls(fetchMock).filter((c) => c.startsWith('DELETE'))).toEqual([
      'DELETE /api/admin/subtitles/6',
    ]);
    expect(result.current.busy).toBe(false);
  });

  it('says so and deletes nothing when no download plays for that edition and kind', async () => {
    const fetchMock = installFetch();
    const { result } = renderHook(() => useSubtitleWrong(target));

    let done;
    await act(async () => {
      done = await result.current.report('FULL');
    });

    expect(done).toBe(false);
    expect(result.current.problem).toBe(subtitlesWrong.lookupFailed);
    expect(calls(fetchMock)).toEqual(['GET /api/admin/subtitles']);
  });

  it('shows the server’s own words when it refuses the delete, then clears them on the next try', async () => {
    let refuse = true;
    installFetch((method) =>
      method === 'DELETE' && refuse
        ? respond({ error: 'Only a subtitle downloaded from OpenSubtitles can be removed' }, 400)
        : null,
    );
    const { result } = renderHook(() => useSubtitleWrong(target));

    await act(() => result.current.report('FORCED'));
    expect(result.current.problem).toBe(
      'Only a subtitle downloaded from OpenSubtitles can be removed',
    );

    refuse = false;
    await act(() => result.current.report('FORCED'));
    expect(result.current.problem).toBeNull();
  });

  it('says one plain line when the list cannot be read', async () => {
    const fetchMock = installFetch((method, url) =>
      url === '/api/admin/subtitles' ? respond({ error: 'x' }, 500) : null,
    );
    const { result } = renderHook(() => useSubtitleWrong(target));

    let done;
    await act(async () => {
      done = await result.current.report('FORCED');
    });

    expect(done).toBe(false);
    expect(result.current.problem).toBe(subtitlesWrong.failed);
    expect(calls(fetchMock).some((c) => c.startsWith('DELETE'))).toBe(false);
  });

  it('removes every playing download of the kind (a split film), stopping at the first failure', async () => {
    const rows = [
      { ...fixtures.adminSubtitles[5], id: 61 },
      { ...fixtures.adminSubtitles[5], id: 62 },
    ];
    const fetchMock = installFetch((method, url) => {
      if (url === '/api/admin/subtitles') return respond(rows);
      if (method !== 'DELETE') return null;
      return url === '/api/admin/subtitles/62'
        ? respond({ error: 'No such subtitle file' }, 404)
        : respond(null, 204);
    });
    const { result } = renderHook(() => useSubtitleWrong(target));

    let done;
    await act(async () => {
      done = await result.current.report('FORCED');
    });

    expect(done).toBe(false);
    expect(calls(fetchMock).filter((c) => c.startsWith('DELETE'))).toEqual([
      'DELETE /api/admin/subtitles/61',
      'DELETE /api/admin/subtitles/62',
    ]);
    expect(result.current.problem).toBe(subtitlesWrong.failed);
  });
});
