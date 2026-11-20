import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { usePhoneLayout } from './usePhoneLayout.js';

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

describe('usePhoneLayout', () => {
  it('is false where matchMedia does not exist', () => {
    vi.stubGlobal('matchMedia', undefined);
    const { result, unmount } = renderHook(() => usePhoneLayout());
    expect(result.current).toBe(false);
    expect(() => unmount()).not.toThrow();
  });

  it('asks for 600px and under and follows changes', () => {
    const media = stubMatchMedia(true);
    const { result, unmount } = renderHook(() => usePhoneLayout());
    expect(result.current).toBe(true);
    expect(window.matchMedia).toHaveBeenCalledWith('(max-width: 600px)');

    act(() => media.change(false));
    expect(result.current).toBe(false);

    unmount();
    expect(media.list.removeEventListener).toHaveBeenCalledWith('change', expect.any(Function));
  });
});
