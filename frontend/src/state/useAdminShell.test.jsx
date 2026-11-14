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

  it('counts a change each time markChanged runs', () => {
    installFetch();
    const { result } = renderHook(() => useAdminShell(true));
    expect(result.current.value.changed).toBe(0);

    act(() => result.current.markChanged());
    act(() => result.current.markChanged());

    expect(result.current.value.changed).toBe(2);
  });
});
