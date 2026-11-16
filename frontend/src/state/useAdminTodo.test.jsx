import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LIBRARY_CHANGED_EVENT } from '../api/client.js';
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
  const base = {
    settledAt: 's',
    subtitlesVersion: 'v',
    changed: 0,
    onChanged,
  };
  const hook = renderHook((props) => useAdminTodo(props), { initialProps: { ...base, ...over } });
  return { ...hook, onChanged, base };
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
  it('posts the best candidate, hides the row, tells the screen and reports the change', async () => {
    const fetchMock = installFetch((m, url) =>
      m === 'POST' && url === MATCH ? respond({ filmId: 34, joined: false }) : null,
    );
    const { result, onChanged } = mount();
    const onHidden = vi.fn();
    await waitFor(() => expect(result.current.allRead).toBe(true));

    await act(() => result.current.confirm(SUSPIRIA, { onHidden }));

    const [[, init]] = posts(fetchMock);
    expect(JSON.parse(init.body)).toEqual({ tmdbId: 37557 });
    expect(result.current.groups[0].rows.map((r) => r.filmId)).toEqual([31]);
    expect(onHidden).toHaveBeenCalledTimes(1);
    expect(onChanged).toHaveBeenCalledTimes(1);
    expect(result.current.confirming).toEqual([]);
    expect(result.current.problems[34]).toBeNull();
  });

  it('announces a library change once when a match was confirmed, and not when the post fails', async () => {
    let fail = true;
    installFetch((m, url) => {
      if (m !== 'POST' || url !== MATCH) return null;
      return fail ? respond({}, 500) : respond({ filmId: 34, joined: false });
    });
    const changed = vi.fn();
    window.addEventListener(LIBRARY_CHANGED_EVENT, changed);
    try {
      const { result } = mount();
      await waitFor(() => expect(result.current.allRead).toBe(true));

      await act(() => result.current.confirm(SUSPIRIA));
      expect(changed).not.toHaveBeenCalled();
      fail = false;
      await act(() => result.current.confirm(SUSPIRIA));

      expect(changed).toHaveBeenCalledTimes(1);
    } finally {
      window.removeEventListener(LIBRARY_CHANGED_EVENT, changed);
    }
  });

  it('announces the library change even when the page has gone before the post finished', async () => {
    let release;
    installFetch((m, url) =>
      m === 'POST' && url === MATCH
        ? new Promise((resolve) => {
            release = () => resolve(respond({ filmId: 34, joined: false }));
          })
        : null,
    );
    const changed = vi.fn();
    window.addEventListener(LIBRARY_CHANGED_EVENT, changed);
    try {
      const { result, unmount } = mount();
      await waitFor(() => expect(result.current.allRead).toBe(true));
      let pending;
      act(() => {
        pending = result.current.confirm(SUSPIRIA);
      });

      unmount();
      release();
      await pending;

      expect(changed).toHaveBeenCalledTimes(1);
    } finally {
      window.removeEventListener(LIBRARY_CHANGED_EVENT, changed);
    }
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
    const onHidden = vi.fn();
    const { result, onChanged } = (() => {
      installFetch((m, url) => {
        if (m !== 'POST' || url !== MATCH) return null;
        return fail ? respond({ error: 'x' }, 503) : respond({ filmId: 34, joined: false });
      });
      return mount();
    })();
    await waitFor(() => expect(result.current.allRead).toBe(true));

    await act(() => result.current.confirm(SUSPIRIA, { onHidden }));

    expect(result.current.problems[34]).toBe(
      'TMDB isn’t available right now. Try again in a moment.',
    );
    expect(result.current.groups[0].rows).toHaveLength(2);
    expect(onChanged).not.toHaveBeenCalled();
    expect(onHidden).not.toHaveBeenCalled();
    expect(result.current.confirming).toEqual([]);
    fail = false;

    await act(() => result.current.confirm(SUSPIRIA));

    expect(result.current.problems[34]).toBeNull();
    expect(result.current.groups[0].rows).toHaveLength(1);
    expect(onChanged).toHaveBeenCalledTimes(1);
  });

  it('shows the server’s own words for a 409 on the row', async () => {
    installFetch((m, url) =>
      m === 'POST' && url === MATCH
        ? respond({ error: 'The library is busy; try again in a moment' }, 409)
        : null,
    );
    const { result, onChanged } = mount();
    await waitFor(() => expect(result.current.allRead).toBe(true));

    await act(() => result.current.confirm(SUSPIRIA));

    expect(result.current.problems[34]).toBe('The library is busy; try again in a moment');
    expect(onChanged).not.toHaveBeenCalled();
  });

  it.each([
    [404, 'That film is no longer in the library. The list is being updated.', true],
    [400, 'Something went wrong. Try again.', false],
    [500, 'Something went wrong. Try again.', false],
    [409, 'A scan is running. Try again when it finishes.', false],
  ])(
    'words a %i for the row, and reads the lists again only when the film is gone',
    async (status, message, gone) => {
      installFetch((m, url) =>
        m === 'POST' && url === MATCH
          ? respond(status === 409 ? null : { error: 'x' }, status)
          : null,
      );
      const { result, onChanged } = mount();
      await waitFor(() => expect(result.current.allRead).toBe(true));

      await act(() => result.current.confirm(SUSPIRIA));

      expect(result.current.problems[34]).toBe(message);
      expect(result.current.groups[0].rows).toHaveLength(2);
      expect(onChanged).toHaveBeenCalledTimes(gone ? 1 : 0);
    },
  );

  it('forgets a problem when a read replaces the attention list', async () => {
    installFetch((m, url) => (m === 'POST' && url === MATCH ? respond({}, 400) : null));
    const { result, rerender, base } = mount();
    await waitFor(() => expect(result.current.allRead).toBe(true));
    await act(() => result.current.confirm(SUSPIRIA));
    expect(result.current.problems[34]).toBe('Something went wrong. Try again.');

    rerender({ ...base, changed: 1 });

    await waitFor(() => expect(result.current.problems[34]).toBeUndefined());
  });

  it('keeps the row hidden when a read lands while the post is in flight, and reports one change', async () => {
    let release;
    installFetch((m, url) =>
      m === 'POST' && url === MATCH
        ? new Promise((resolve) => {
            release = () => resolve(respond({ filmId: 34, joined: false }));
          })
        : null,
    );
    const { result, rerender, base, onChanged } = mount();
    await waitFor(() => expect(result.current.allRead).toBe(true));
    let pending;
    act(() => {
      pending = result.current.confirm(SUSPIRIA);
    });
    const before = result.current.attention.data;

    rerender({ ...base, settledAt: 's2' });
    await waitFor(() => expect(result.current.attention.data).not.toBe(before));
    await act(async () => {
      release();
      await pending;
    });

    expect(result.current.groups[0].rows.map((r) => r.filmId)).toEqual([31]);
    expect(onChanged).toHaveBeenCalledTimes(1);
  });

  it('keeps the row hidden when the re-read after the confirm fails', async () => {
    let failReads = false;
    installFetch((m, url) => {
      if (m === 'POST' && url === MATCH) {
        failReads = true;
        return respond({ filmId: 34, joined: false });
      }
      return url === ATTENTION && failReads ? respond(null, 500) : null;
    });
    const { result, rerender, base } = mount();
    await waitFor(() => expect(result.current.allRead).toBe(true));
    await act(() => result.current.confirm(SUSPIRIA));

    rerender({ ...base, changed: 1 });
    await waitFor(() => expect(result.current.attention.error).not.toBeNull());

    // The last answer stays on screen, and so does the hiding that belongs to it.
    expect(result.current.groups[0].rows.map((r) => r.filmId)).toEqual([31]);
  });

  it('does not touch the screen when the page has gone mid-post, but still reports the change', async () => {
    let release;
    installFetch((m, url) =>
      m === 'POST' && url === MATCH
        ? new Promise((resolve) => {
            release = () => resolve(respond({ filmId: 34, joined: false }));
          })
        : null,
    );
    const { result, unmount, onChanged } = mount();
    await waitFor(() => expect(result.current.allRead).toBe(true));
    const onHidden = vi.fn();
    let pending;
    act(() => {
      pending = result.current.confirm(SUSPIRIA, { onHidden });
    });

    unmount();
    release();
    await pending;

    expect(onHidden).not.toHaveBeenCalled();
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
