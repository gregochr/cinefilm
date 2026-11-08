import { renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { installFetch } from '../test/mockApi.jsx';
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
});
