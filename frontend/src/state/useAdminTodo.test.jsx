import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, respond } from '../test/mockApi.jsx';
import { useAdminTodo } from './useAdminTodo.js';

afterEach(() => {
  vi.unstubAllGlobals();
});

const ATTENTION = '/api/admin/attention';
const MATCH = '/api/admin/films/34/match';
const SUSPIRIA = fixtures.adminAttention.items.find((i) => i.filmId === 34);

function mount(over = {}) {
  const onChanged = vi.fn();
  const armFocusFallback = vi.fn();
  const base = {
    settledAt: 's',
    subtitlesVersion: 'v',
    changed: 0,
    onChanged,
    armFocusFallback,
  };
  const hook = renderHook((props) => useAdminTodo(props), { initialProps: { ...base, ...over } });
  return { ...hook, onChanged, armFocusFallback, base };
}

const callsTo = (fetchMock, url) => fetchMock.mock.calls.filter(([u]) => u === url).length;
const posts = (fetchMock) => fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST');

describe('useAdminTodo: reading', () => {
  it('reads the three lists on their own requests and builds the groups', async () => {
    const fetchMock = installFetch();
    const { result } = mount();
    expect(result.current.allRead).toBe(false);

    await waitFor(() => expect(result.current.allRead).toBe(true));

    expect(result.current.groups.map((g) => [g.key, g.total])).toEqual([
      ['pickFilm', 2],
      ['severalFiles', 2],
      ['fullMissing', 1],
      ['forcedQuestion', 1],
    ]);
    expect(callsTo(fetchMock, ATTENTION)).toBe(1);
    expect(callsTo(fetchMock, '/api/admin/subtitles/full-need')).toBe(1);
    expect(callsTo(fetchMock, '/api/admin/forced-subtitles/films')).toBe(1);
  });

  it('reads all three again when a fix changed things, and again for a new subtitles stamp', async () => {
    const fetchMock = installFetch();
    const { result, rerender, base } = mount();
    await waitFor(() => expect(result.current.allRead).toBe(true));

    rerender({ ...base, changed: 1 });
    await waitFor(() => expect(callsTo(fetchMock, ATTENTION)).toBe(2));
    await waitFor(() => expect(callsTo(fetchMock, '/api/admin/subtitles/full-need')).toBe(2));
    expect(callsTo(fetchMock, '/api/admin/forced-subtitles/films')).toBe(2);

    // A finished subtitle job moves only the subtitle stamp: the attention list is not read for it.
    rerender({ ...base, changed: 1, subtitlesVersion: 'v2' });
    await waitFor(() => expect(callsTo(fetchMock, '/api/admin/subtitles/full-need')).toBe(3));
    expect(callsTo(fetchMock, ATTENTION)).toBe(2);
  });

  it('leaves the other lists on screen when one fails, and does not say it has read everything', async () => {
    installFetch((m, url) =>
      url === '/api/admin/subtitles/full-need' ? respond(null, 500) : null,
    );
    const { result } = mount();

    await waitFor(() => expect(result.current.full.error).not.toBeNull());
    await waitFor(() => expect(result.current.attention.data).not.toBeNull());

    expect(result.current.groups.map((g) => g.key)).toEqual([
      'pickFilm',
      'severalFiles',
      'forcedQuestion',
    ]);
    expect(result.current.allRead).toBe(false);
  });
});

describe('useAdminTodo: confirm', () => {
  it('posts the best candidate, hides the row, readies the focus fallback and reports the change', async () => {
    const fetchMock = installFetch((m, url) =>
      m === 'POST' && url === MATCH ? respond({ filmId: 34, joined: false }) : null,
    );
    const { result, onChanged, armFocusFallback } = mount();
    await waitFor(() => expect(result.current.allRead).toBe(true));

    await act(() => result.current.confirm(SUSPIRIA));

    const [[, init]] = posts(fetchMock);
    expect(JSON.parse(init.body)).toEqual({ tmdbId: 37557 });
    expect(result.current.groups[0].rows.map((r) => r.filmId)).toEqual([31]);
    expect(armFocusFallback).toHaveBeenCalledTimes(1);
    expect(onChanged).toHaveBeenCalledTimes(1);
    expect(result.current.confirming).toEqual([]);
    expect(result.current.problems[34]).toBeNull();
  });

  it('shows a row again if the re-read still lists it', async () => {
    installFetch((m, url) =>
      m === 'POST' && url === MATCH ? respond({ filmId: 34, joined: false }) : null,
    );
    const { result, rerender, base } = mount();
    await waitFor(() => expect(result.current.allRead).toBe(true));
    await act(() => result.current.confirm(SUSPIRIA));
    expect(result.current.groups[0].rows).toHaveLength(1);

    rerender({ ...base, changed: 1 });

    await waitFor(() => expect(result.current.groups[0].rows).toHaveLength(2));
  });

  it('ignores a second call for the same film while its post is in flight', async () => {
    let release;
    const fetchMock = installFetch((m, url) =>
      m === 'POST' && url === MATCH
        ? new Promise((resolve) => {
            release = () => resolve(respond({ filmId: 34, joined: false }));
          })
        : null,
    );
    const { result, onChanged } = mount();
    await waitFor(() => expect(result.current.allRead).toBe(true));

    let first;
    act(() => {
      first = result.current.confirm(SUSPIRIA);
    });
    await act(() => result.current.confirm(SUSPIRIA));
    expect(result.current.confirming).toEqual([34]);
    expect(posts(fetchMock)).toHaveLength(1);

    await act(async () => {
      release();
      await first;
    });

    expect(posts(fetchMock)).toHaveLength(1);
    expect(onChanged).toHaveBeenCalledTimes(1);
    expect(result.current.confirming).toEqual([]);
  });

  it('lets two different films confirm at the same time', async () => {
    const other = { ...SUSPIRIA, filmId: 35, title: 'Inferno' };
    const fetchMock = installFetch((m, url) =>
      m === 'POST' && /\/match$/.test(url) ? respond({ filmId: 1, joined: false }) : null,
    );
    const { result } = mount();
    await waitFor(() => expect(result.current.allRead).toBe(true));

    await act(async () => {
      await Promise.all([result.current.confirm(SUSPIRIA), result.current.confirm(other)]);
    });

    expect(posts(fetchMock).map(([url]) => url)).toEqual([MATCH, '/api/admin/films/35/match']);
  });

  it('keeps the row, sets the problem and reports no change when the post fails, then clears it on a retry', async () => {
    let fail = true;
    const { result, onChanged, armFocusFallback } = (() => {
      installFetch((m, url) => {
        if (m !== 'POST' || url !== MATCH) return null;
        return fail ? respond({ error: 'x' }, 503) : respond({ filmId: 34, joined: false });
      });
      return mount();
    })();
    await waitFor(() => expect(result.current.allRead).toBe(true));

    await act(() => result.current.confirm(SUSPIRIA));

    expect(result.current.problems[34]).toBe(
      'TMDB isn’t available right now. Try again in a moment.',
    );
    expect(result.current.groups[0].rows).toHaveLength(2);
    expect(onChanged).not.toHaveBeenCalled();
    expect(armFocusFallback).not.toHaveBeenCalled();
    expect(result.current.confirming).toEqual([]);
    fail = false;

    await act(() => result.current.confirm(SUSPIRIA));

    expect(result.current.problems[34]).toBeNull();
    expect(result.current.groups[0].rows).toHaveLength(1);
    expect(onChanged).toHaveBeenCalledTimes(1);
  });

  it('does nothing for an item with no candidate', async () => {
    const fetchMock = installFetch();
    const { result, onChanged } = mount();
    await waitFor(() => expect(result.current.allRead).toBe(true));

    await act(() => result.current.confirm({ ...SUSPIRIA, candidates: [] }));

    expect(posts(fetchMock)).toHaveLength(0);
    expect(onChanged).not.toHaveBeenCalled();
  });
});
