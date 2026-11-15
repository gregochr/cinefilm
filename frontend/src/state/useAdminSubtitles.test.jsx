import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { installFetch, respond } from '../test/mockApi.jsx';
import { forcedRow, fullRow, serveNeeds, unlabelledRow } from '../test/needRoutes.js';
import { useAdminSubtitles } from './useAdminSubtitles.js';

afterEach(() => vi.unstubAllGlobals());

const settled = (over = {}) => fullRow({ filmId: 3, unresolved: false, ...over });

describe('useAdminSubtitles', () => {
  it('lists only the rows the server flagged unresolved, null before a list is read', async () => {
    const server = serveNeeds({
      full: [fullRow(), settled(), unlabelledRow()],
      forced: [forcedRow(), forcedRow({ filmId: 5, unresolved: false })],
    });
    installFetch(server.route);
    const { result } = renderHook(() =>
      useAdminSubtitles({ subtitlesVersion: 'v', changed: 0, onChanged: vi.fn() }),
    );
    expect(result.current.foreign).toBeNull();
    expect(result.current.forcedFilms).toBeNull();

    await waitFor(() => expect(result.current.foreign).not.toBeNull());
    await waitFor(() => expect(result.current.forcedFilms).not.toBeNull());

    expect(result.current.foreign.map((r) => r.filmId)).toEqual([6, 8]);
    expect(result.current.forcedFilms.map((r) => r.filmId)).toEqual([2]);
    expect(result.current.full.counts).toMatchObject({ unresolved: 2, beingChecked: 0 });
  });

  it('keeps one list on screen when the other fails', async () => {
    const server = serveNeeds({ full: [fullRow()], forced: [forcedRow()] });
    installFetch((method, url, init) =>
      url === '/api/admin/subtitles/full-need'
        ? respond(null, 500)
        : server.route(method, url, init),
    );
    const { result } = renderHook(() =>
      useAdminSubtitles({ subtitlesVersion: 'v', changed: 0, onChanged: vi.fn() }),
    );

    await waitFor(() => expect(result.current.forcedFilms).toHaveLength(1));
    await waitFor(() => expect(result.current.full.error).not.toBeNull());

    expect(result.current.foreign).toBeNull();
  });

  it('calls onChanged after a saved choice, and reads both lists again when `changed` moves', async () => {
    const server = serveNeeds({ full: [unlabelledRow()], forced: [forcedRow()] });
    installFetch(server.route);
    const onChanged = vi.fn();
    const { result, rerender } = renderHook(
      ({ changed }) => useAdminSubtitles({ subtitlesVersion: 'v', changed, onChanged }),
      { initialProps: { changed: 0 } },
    );
    await waitFor(() => expect(result.current.foreign).toHaveLength(1));

    await act(() => result.current.full.choose(8, 'NOT_NEEDED'));
    expect(onChanged).toHaveBeenCalledTimes(1);
    expect(result.current.foreign).toEqual([]);

    const reads = { ...server.reads };
    rerender({ changed: 1 });
    await waitFor(() => expect(server.reads.full).toBe(reads.full + 1));
    expect(server.reads.forced).toBe(reads.forced + 1);
    expect(result.current.foreign).toEqual([]);
  });

  it('keeps the lists’ identity between renders, so a screen can react to a change of rows', async () => {
    const server = serveNeeds({ full: [fullRow()], forced: [forcedRow()] });
    installFetch(server.route);
    const { result, rerender } = renderHook(() =>
      useAdminSubtitles({ subtitlesVersion: 'v', changed: 0, onChanged: vi.fn() }),
    );
    await waitFor(() => expect(result.current.foreign).toHaveLength(1));
    const before = result.current.foreign;

    rerender();

    expect(result.current.foreign).toBe(before);
  });
});
