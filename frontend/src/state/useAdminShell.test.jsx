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

  it('owns the one summary read, and reads it again each time something changed', async () => {
    const fetchMock = installFetch();
    const { result } = renderHook(() => useAdminShell(true));
    await waitFor(() =>
      expect(result.current.value.adminSummary.summary).toEqual(fixtures.adminSummary),
    );
    const reads = () => fetchMock.mock.calls.filter(([url]) => url === '/api/admin/summary').length;
    await waitFor(() => expect(result.current.value.settledAt).not.toBe('never|none'));
    const before = reads();

    act(() => result.current.markChanged());

    await waitFor(() => expect(reads()).toBe(before + 1));
  });

  it('hands sections the change action: it bumps `changed`', () => {
    installFetch();
    const { result } = renderHook(() => useAdminShell(true));
    expect(result.current.value.changed).toBe(0);

    act(() => result.current.value.markChanged());

    expect(result.current.value.changed).toBe(1);
  });

  it('hands sections the toast’s show function, which sets the layout’s toast message', () => {
    installFetch();
    const { result } = renderHook(() => useAdminShell(true));
    expect(result.current.toast.message).toBeNull();
    act(() => result.current.value.showToast('Copied'));
    expect(result.current.toast.message).toBe('Copied');
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

  it('closes the picker too when the path changes', () => {
    installFetch();
    const { result, rerender } = renderHook(({ path }) => useAdminShell(true, path), {
      initialProps: { path: '/admin/subtitles' },
    });
    act(() => result.current.value.openSubtitlePicker({ editionId: 5, kind: 'full' }, null));

    rerender({ path: '/admin/subtitles' });
    expect(result.current.picking).not.toBeNull();
    rerender({ path: '/admin' });

    expect(result.current.picking).toBeNull();
  });

  describe('the OpenSubtitles picker', () => {
    const target = { film: { id: 6, title: 'Rashomon', year: 1950 }, editionId: 102, kind: 'full' };

    it('remembers the target, what opened it and what to run once a file is picked, and closes', () => {
      installFetch();
      const { result } = renderHook(() => useAdminShell(true));
      const opener = {};
      const onPicked = vi.fn();

      act(() => result.current.value.openSubtitlePicker(target, opener, { onPicked }));

      expect(result.current.picking).toEqual({ ...target, opener, onPicked });
      act(() => result.current.closeSubtitlePicker());
      expect(result.current.picking).toBeNull();
    });

    it('reads the lists again, closes the open picker and runs its follow-up when a file was downloaded', () => {
      installFetch();
      const { result } = renderHook(() => useAdminShell(true));
      const onPicked = vi.fn();
      act(() => result.current.value.openSubtitlePicker(target, null, { onPicked }));
      const done = result.current.picking;
      expect(result.current.value.changed).toBe(0);

      act(() => result.current.pickerDone(done));

      expect(result.current.value.changed).toBe(1);
      expect(result.current.picking).toBeNull();
      expect(onPicked).toHaveBeenCalledTimes(1);
    });

    it('still reads the lists again and runs the follow-up for a picker that was closed, without closing another', () => {
      installFetch();
      const { result } = renderHook(() => useAdminShell(true));
      const oldFollowUp = vi.fn();
      act(() => result.current.value.openSubtitlePicker(target, null, { onPicked: oldFollowUp }));
      const old = result.current.picking;
      act(() => result.current.closeSubtitlePicker());
      act(() => result.current.value.openSubtitlePicker({ ...target, editionId: 103 }, null));
      const current = result.current.picking;

      act(() => result.current.pickerDone(old));

      expect(result.current.value.changed).toBe(1);
      expect(result.current.picking).toBe(current);
      expect(oldFollowUp).toHaveBeenCalledTimes(1);
    });

    it('opens with no follow-up when none is given, and runs nothing on done', () => {
      installFetch();
      const { result } = renderHook(() => useAdminShell(true));
      act(() => result.current.value.openSubtitlePicker(target, null));

      expect(() => act(() => result.current.pickerDone(result.current.picking))).not.toThrow();
      expect(result.current.picking).toBeNull();
    });
  });

  it('hands the focus fallback out once after it is armed', () => {
    installFetch();
    const { result } = renderHook(() => useAdminShell(true));
    expect(result.current.value.takeFocusFallback()).toBe(false);

    act(() => result.current.armFocusFallback());

    expect(result.current.value.takeFocusFallback()).toBe(true);
    expect(result.current.value.takeFocusFallback()).toBe(false);
  });

  it('lets a section arm the focus fallback through the context', () => {
    installFetch();
    const { result } = renderHook(() => useAdminShell(true));

    act(() => result.current.value.armFocusFallback());

    expect(result.current.value.takeFocusFallback()).toBe(true);
  });
});
