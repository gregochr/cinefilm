import { renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { installFetch, respond } from '../test/mockApi.jsx';
import { usePageExitReport } from './exitReport.js';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('usePageExitReport', () => {
  it('sends a keepalive progress request on pagehide', () => {
    const fetchMock = installFetch();
    renderHook(() => usePageExitReport({ editionId: 11, getPositionMs: () => 61000 }));
    window.dispatchEvent(new Event('pagehide'));
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/editions/11/progress');
    expect(init.keepalive).toBe(true);
    expect(init.method).toBe('PUT');
    expect(init.headers['X-CineFilm']).toBe('1');
    expect(JSON.parse(init.body)).toEqual({ positionMs: 61000 });
  });

  it('sends when the page becomes hidden, not when visible', () => {
    const fetchMock = installFetch();
    renderHook(() => usePageExitReport({ editionId: 11, getPositionMs: () => 5000 }));
    const state = vi.spyOn(document, 'visibilityState', 'get');
    state.mockReturnValue('visible');
    document.dispatchEvent(new Event('visibilitychange'));
    expect(fetchMock).not.toHaveBeenCalled();
    state.mockReturnValue('hidden');
    document.dispatchEvent(new Event('visibilitychange'));
    expect(fetchMock).toHaveBeenCalledTimes(1);
    state.mockRestore();
  });

  it.each([
    ['at the very start', { editionId: 11, getPositionMs: () => 0 }],
    ['with an unknown position', { editionId: 11, getPositionMs: () => NaN }],
    ['with no edition', { editionId: undefined, getPositionMs: () => 5000 }],
  ])('sends nothing %s', (_when, props) => {
    const fetchMock = installFetch();
    renderHook(() => usePageExitReport(props));
    window.dispatchEvent(new Event('pagehide'));
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('reads the position at the moment the page goes, not when the hook was set up', () => {
    const fetchMock = installFetch();
    let position = 1000;
    renderHook(() => usePageExitReport({ editionId: 11, getPositionMs: () => position }));
    position = 9000;
    window.dispatchEvent(new Event('pagehide'));
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ positionMs: 9000 });
  });

  it('rounds the position to whole milliseconds', () => {
    const fetchMock = installFetch();
    renderHook(() => usePageExitReport({ editionId: 11, getPositionMs: () => 1234.6 }));
    window.dispatchEvent(new Event('pagehide'));
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ positionMs: 1235 });
  });

  it('stops listening once unmounted', () => {
    const fetchMock = installFetch();
    const { unmount } = renderHook(() =>
      usePageExitReport({ editionId: 11, getPositionMs: () => 5000 }),
    );
    unmount();
    window.dispatchEvent(new Event('pagehide'));
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('a failed report is swallowed (the page is closing anyway)', async () => {
    installFetch(() => respond(null, 500));
    renderHook(() => usePageExitReport({ editionId: 11, getPositionMs: () => 5000 }));
    expect(() => window.dispatchEvent(new Event('pagehide'))).not.toThrow();
    await Promise.resolve();
  });
});
