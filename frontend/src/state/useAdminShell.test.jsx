import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch } from '../test/mockApi.jsx';
import { useAdminShell } from './useAdminShell.js';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('useAdminShell', () => {
  it('builds the settled stamps from the scan and Plex finish times', async () => {
    installFetch();
    const { result } = renderHook(() => useAdminShell(true));
    await waitFor(() =>
      expect(result.current.value.settledAt).toBe(`${fixtures.adminScan.lastScan.finishedAt}|none`),
    );
    expect(result.current.value.subtitlesVersion).toBe(
      `${result.current.value.settledAt}|${fixtures.adminScan.openSubtitlesLastSearchedAt ?? 'never'}|${fixtures.adminScan.subtitleChecksLastFinishedAt ?? 'never'}`,
    );
  });

  it('remembers what opened each dialog, and closes them', () => {
    installFetch();
    const { result } = renderHook(() => useAdminShell(true));
    const item = { filmId: 1 };
    const film = { id: 2 };
    const opener = {};

    act(() => result.current.value.openFixMatch(item, opener));
    act(() => result.current.value.openEditEditions(film, opener));
    expect(result.current.fixing).toEqual({ item, opener });
    expect(result.current.editing).toEqual({ film, opener });

    act(() => result.current.closeFixMatch());
    act(() => result.current.closeEditEditions());
    expect(result.current.fixing).toBeNull();
    expect(result.current.editing).toBeNull();
  });

  it('counts a change each time markChanged runs, and reads the scan status again', async () => {
    const fetchMock = installFetch();
    const { result } = renderHook(() => useAdminShell(true));
    const scans = () =>
      fetchMock.mock.calls.filter(
        ([url, init]) => url === '/api/admin/scan' && init?.method !== 'POST',
      ).length;
    await waitFor(() => expect(scans()).toBe(1));
    expect(result.current.value.changed).toBe(0);

    act(() => result.current.markChanged());
    await waitFor(() => expect(scans()).toBe(2));
    act(() => result.current.markChanged());

    expect(result.current.value.changed).toBe(2);
    await waitFor(() => expect(scans()).toBe(3));
  });

  it('closes both dialogs when the path changes, and not for the same path', () => {
    installFetch();
    const { result, rerender } = renderHook(({ path }) => useAdminShell(true, path), {
      initialProps: { path: '/admin' },
    });
    act(() => result.current.value.openFixMatch({ filmId: 1 }, null));
    act(() => result.current.value.openEditEditions({ id: 2 }, null));

    rerender({ path: '/admin' });
    expect(result.current.fixing).not.toBeNull();
    expect(result.current.editing).not.toBeNull();

    rerender({ path: '/admin/library' });
    expect(result.current.fixing).toBeNull();
    expect(result.current.editing).toBeNull();
  });

  it('hands the focus fallback out once after it is armed', () => {
    installFetch();
    const { result } = renderHook(() => useAdminShell(true));
    expect(result.current.value.takeFocusFallback()).toBe(false);

    act(() => result.current.armFocusFallback());

    expect(result.current.value.takeFocusFallback()).toBe(true);
    expect(result.current.value.takeFocusFallback()).toBe(false);
  });
});
