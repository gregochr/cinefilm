import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useHoverNone } from './useHoverNone.js';

afterEach(() => vi.unstubAllGlobals());

function stubMatchMedia(matches) {
  const listeners = new Set();
  const list = {
    matches,
    addEventListener: vi.fn((type, fn) => listeners.add(fn)),
    removeEventListener: vi.fn((type, fn) => listeners.delete(fn)),
  };
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => list),
  );
  return {
    list,
    change(next) {
      list.matches = next;
      listeners.forEach((fn) => fn());
    },
  };
}

describe('useHoverNone', () => {
  it('is false where matchMedia does not exist', () => {
    vi.stubGlobal('matchMedia', undefined);
    const { result } = renderHook(() => useHoverNone());
    expect(result.current).toBe(false);
  });

  it('asks for (hover: none) and reports whether it matches', () => {
    const media = stubMatchMedia(true);
    const { result } = renderHook(() => useHoverNone());
    expect(result.current).toBe(true);
    expect(window.matchMedia).toHaveBeenCalledWith('(hover: none)');
    expect(media.list.addEventListener).toHaveBeenCalledWith('change', expect.any(Function));
  });

  it('follows changes and stops listening on unmount', () => {
    const media = stubMatchMedia(false);
    const { result, unmount } = renderHook(() => useHoverNone());
    expect(result.current).toBe(false);

    act(() => media.change(true));
    expect(result.current).toBe(true);

    unmount();
    expect(media.list.removeEventListener).toHaveBeenCalledWith('change', expect.any(Function));
  });

  it('does not throw when unmounted without matchMedia', () => {
    vi.stubGlobal('matchMedia', undefined);
    const { unmount } = renderHook(() => useHoverNone());
    expect(() => unmount()).not.toThrow();
  });
});
