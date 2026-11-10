import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LIBRARY_CHANGED_EVENT } from '../api/client.js';
import { installFetch, respond } from '../test/mockApi.jsx';
import { useEditEditions } from './useEditEditions.js';

afterEach(() => {
  vi.unstubAllGlobals();
});

const isGet = (m, url) => m === 'GET' && url === '/api/admin/films/40/editions';
const isPut = (m, url) => m === 'PUT' && url === '/api/admin/films/40/editions';
const puts = (fetchMock) => fetchMock.mock.calls.filter(([, init]) => init?.method === 'PUT');

describe('useEditEditions', () => {
  it('has nothing to show and nothing to save while the files load', async () => {
    const fetchMock = installFetch((m, url) => (isGet(m, url) ? new Promise(() => {}) : null));
    const { result } = renderHook(() => useEditEditions(40, vi.fn()));

    expect(result.current).toMatchObject({ status: 'loading', view: null, reason: '', fixed: [] });
    await act(async () => result.current.save());

    expect(puts(fetchMock)).toHaveLength(0);
  });

  it('loads the suggestion and offers its editions', async () => {
    installFetch();
    const { result } = renderHook(() => useEditEditions(40, vi.fn()));

    await waitFor(() => expect(result.current.status).toBe('ready'));
    expect(result.current.reason).toBe('From the edition tags and part numbers');
    expect(result.current.fixed.map((f) => f.name)).toEqual(['Extended Edition', 'Theatrical Cut']);
    expect(result.current.view.errorCount).toBe(0);
  });

  it('does not save an arrangement that has errors', async () => {
    const fetchMock = installFetch();
    const { result } = renderHook(() => useEditEditions(40, vi.fn()));
    await waitFor(() => expect(result.current.status).toBe('ready'));

    act(() => result.current.setPart(12, '3'));
    await act(async () => result.current.save());

    expect(result.current.view.errorCount).toBe(1);
    expect(puts(fetchMock)).toHaveLength(0);
  });

  it('ignores a second save while one is on its way', async () => {
    let release;
    const fetchMock = installFetch((m, url) =>
      isPut(m, url)
        ? new Promise((resolve) => {
            release = () => resolve(respond({ filmId: 40, newFilmIds: [] }));
          })
        : null,
    );
    const onSaved = vi.fn();
    const { result } = renderHook(() => useEditEditions(40, onSaved));
    await waitFor(() => expect(result.current.status).toBe('ready'));

    act(() => {
      result.current.save();
    });
    await waitFor(() => expect(result.current.saving).toBe(true));
    await act(async () => result.current.save());
    await act(async () => release());

    await waitFor(() => expect(onSaved).toHaveBeenCalledTimes(1));
    expect(puts(fetchMock)).toHaveLength(1);
  });

  it('a request cancelled by the panel closing is not a load failure', async () => {
    installFetch((m, url) => {
      if (!isGet(m, url)) return null;
      return Promise.reject(new DOMException('aborted', 'AbortError'));
    });
    const { result } = renderHook(() => useEditEditions(40, vi.fn()));

    await act(async () => {});

    expect(result.current.status).toBe('loading');
  });

  it('says it failed to load, and tries again on request', async () => {
    let fail = true;
    installFetch((m, url) => (isGet(m, url) && fail ? respond({ error: 'x' }, 500) : null));
    const { result } = renderHook(() => useEditEditions(40, vi.fn()));
    await waitFor(() => expect(result.current.status).toBe('error'));

    fail = false;
    act(() => result.current.retry());

    await waitFor(() => expect(result.current.status).toBe('ready'));
  });

  it('forgets a load that was overtaken by unmounting', async () => {
    let finish;
    installFetch((m, url) =>
      isGet(m, url)
        ? new Promise((resolve) => {
            finish = () => resolve(respond({ files: [], editions: [], suggestion: {} }));
          })
        : null,
    );
    const { result, unmount } = renderHook(() => useEditEditions(40, vi.fn()));

    unmount();
    await act(async () => finish());

    expect(result.current.status).toBe('loading');
  });

  it('ignores edits while a save is on its way, so what is saved is what is shown', async () => {
    let release;
    const fetchMock = installFetch((m, url) =>
      isPut(m, url)
        ? new Promise((resolve) => {
            release = () => resolve(respond({ filmId: 40, newFilmIds: [] }));
          })
        : null,
    );
    const { result } = renderHook(() => useEditEditions(40, vi.fn()));
    await waitFor(() => expect(result.current.status).toBe('ready'));

    act(() => {
      result.current.save();
      result.current.setDefault('Extended Edition');
    });
    await waitFor(() => expect(result.current.saving).toBe(true));
    act(() => result.current.setPart(12, '3'));
    await act(async () => release());

    await waitFor(() => expect(result.current.saving).toBe(false));
    expect(result.current.view.defaults.map((d) => d.on)).toEqual([false, true]);
    expect(result.current.view.errorCount).toBe(0);
    expect(JSON.parse(puts(fetchMock)[0][1].body).editions[1].default).toBe(true);
  });

  it('announces a library change after a save went through, and not after one that failed', async () => {
    let answer = respond({ error: 'A scan is running' }, 409);
    installFetch((m, url) => (isPut(m, url) ? answer : null));
    const changed = vi.fn();
    window.addEventListener(LIBRARY_CHANGED_EVENT, changed);
    const { result } = renderHook(() => useEditEditions(40, vi.fn()));
    await waitFor(() => expect(result.current.status).toBe('ready'));

    await act(async () => result.current.save());
    expect(changed).not.toHaveBeenCalled();
    answer = respond({ filmId: 40, newFilmIds: [] });
    await act(async () => result.current.save());

    expect(changed).toHaveBeenCalledTimes(1);
    window.removeEventListener(LIBRARY_CHANGED_EVENT, changed);
  });
});
