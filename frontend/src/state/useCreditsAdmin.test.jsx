import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, respond } from '../test/mockApi.jsx';
import { useCreditsAdmin } from './useCreditsAdmin.js';

afterEach(() => {
  vi.unstubAllGlobals();
});

const COUNTS = { none: 3, plex: 1, owner: 1, all: 5 };
const credits = (fetchMock) =>
  fetchMock.mock.calls
    .map(([url]) => String(url))
    .filter((url) => url.startsWith('/api/admin/credits'));

/** A page of a filter: `n` rows out of `total`. */
function pageOf(filter, page, n, total) {
  const items = Array.from({ length: n }, (_, i) => ({
    ...structuredClone(fixtures.adminCredits.items[1]),
    editionId: 100 + page * 25 + i,
    title: `${filter} ${page} ${i}`,
  }));
  return { counts: COUNTS, page, size: 25, total, items };
}

const serve = (answer) =>
  installFetch((method, url) => {
    if (!String(url).startsWith('/api/admin/credits')) return null;
    const params = new URL(url, 'http://test.local').searchParams;
    return answer(params.get('filter'), Number(params.get('page')));
  });

describe('useCreditsAdmin', () => {
  it('reads the first page of the filter by its exact address', async () => {
    const fetchMock = serve((filter, page) => respond(pageOf(filter, page, 2, 52)));
    const { result } = renderHook(() => useCreditsAdmin('plex', 0));

    await waitFor(() => expect(result.current.data).not.toBeNull());

    expect(credits(fetchMock)).toEqual(['/api/admin/credits?filter=plex&page=0']);
    expect(result.current.page).toBe(0);
    expect(result.current.data.items.map((r) => r.title)).toEqual(['plex 0 0', 'plex 0 1']);
  });

  it('goes to the next and the previous page', async () => {
    const fetchMock = serve((filter, page) => respond(pageOf(filter, page, 2, 52)));
    const { result } = renderHook(() => useCreditsAdmin('none', 0));
    await waitFor(() => expect(result.current.data).not.toBeNull());

    act(() => result.current.setPage(1));
    await waitFor(() => expect(result.current.data?.page).toBe(1));
    expect(result.current.page).toBe(1);
    expect(credits(fetchMock)).toContain('/api/admin/credits?filter=none&page=1');

    act(() => result.current.setPage(0));
    await waitFor(() => expect(result.current.data?.page).toBe(0));
    expect(result.current.page).toBe(0);
  });

  it('steps back exactly one page from page 2, reading page 1 once', async () => {
    const fetchMock = serve((filter, page) =>
      respond(page === 2 ? pageOf(filter, page, 0, 30) : pageOf(filter, page, 2, 30)),
    );
    const { result } = renderHook(() => useCreditsAdmin('none', 0));
    await waitFor(() => expect(result.current.data).not.toBeNull());
    act(() => result.current.setPage(1));
    await waitFor(() => expect(result.current.data?.page).toBe(1));

    act(() => result.current.setPage(2));

    await waitFor(() => expect(result.current.data?.page).toBe(1));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.page).toBe(1);
    expect(credits(fetchMock)).toEqual([
      '/api/admin/credits?filter=none&page=0',
      '/api/admin/credits?filter=none&page=1',
      '/api/admin/credits?filter=none&page=2',
      '/api/admin/credits?filter=none&page=1',
    ]);
  });

  it('stays on page 0 when the filter has no films at all', async () => {
    serve((filter, page) => respond(pageOf(filter, page, 0, 0)));
    const { result } = renderHook(() => useCreditsAdmin('none', 0));

    await waitFor(() => expect(result.current.data).not.toBeNull());

    expect(result.current.page).toBe(0);
    expect(result.current.data.items).toEqual([]);
  });

  it('reports a failed read and reads again on reload', async () => {
    let fail = true;
    const fetchMock = serve((filter, page) =>
      fail ? respond(null, 500) : respond(pageOf(filter, page, 2, 2)),
    );
    const { result } = renderHook(() => useCreditsAdmin('none', 0));
    await waitFor(() => expect(result.current.error).not.toBeNull());
    expect(result.current.data).toBeNull();

    fail = false;
    act(() => result.current.reload());

    await waitFor(() => expect(result.current.data?.items).toHaveLength(2));
    expect(result.current.error).toBeNull();
    expect(credits(fetchMock)).toHaveLength(2);
  });

  it('reads again when the sign-in recovery count changes', async () => {
    const fetchMock = serve((filter, page) => respond(pageOf(filter, page, 2, 2)));
    const { result, rerender } = renderHook(({ count }) => useCreditsAdmin('none', count), {
      initialProps: { count: 0 },
    });
    await waitFor(() => expect(result.current.data).not.toBeNull());
    expect(credits(fetchMock)).toHaveLength(1);

    rerender({ count: 1 });

    await waitFor(() => expect(credits(fetchMock)).toHaveLength(2));
    expect(credits(fetchMock)[1]).toBe('/api/admin/credits?filter=none&page=0');
  });

  it('reads page 0 when a tab is left and came back to, not the page it was left on', async () => {
    const fetchMock = serve((filter, page) => respond(pageOf(filter, page, 2, 52)));
    const { result, rerender } = renderHook(({ filter }) => useCreditsAdmin(filter, 0), {
      initialProps: { filter: 'none' },
    });
    await waitFor(() => expect(result.current.data).not.toBeNull());
    act(() => result.current.setPage(1));
    await waitFor(() => expect(result.current.data?.page).toBe(1));

    rerender({ filter: 'plex' });
    await waitFor(() => expect(result.current.data?.items[0].title).toBe('plex 0 0'));
    rerender({ filter: 'none' });

    await waitFor(() => expect(result.current.data?.items[0].title).toBe('none 0 0'));
    expect(result.current.page).toBe(0);
    expect(credits(fetchMock).at(-1)).toBe('/api/admin/credits?filter=none&page=0');
  });

  it('reports the error, not loading, when the next page fails after a page was shown', async () => {
    serve((filter, page) =>
      page === 1 ? respond(null, 500) : respond(pageOf(filter, page, 2, 52)),
    );
    const { result } = renderHook(() => useCreditsAdmin('none', 0));
    await waitFor(() => expect(result.current.data).not.toBeNull());

    act(() => result.current.setPage(1));

    await waitFor(() => expect(result.current.error).not.toBeNull());
    expect(result.current.loading).toBe(false);
    act(() => result.current.reload());
    await waitFor(() => expect(result.current.error).not.toBeNull());
    expect(result.current.loading).toBe(false);
  });
});
