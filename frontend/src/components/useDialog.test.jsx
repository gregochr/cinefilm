import { renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FADE_MS, useDialog } from './useDialog.js';

beforeEach(() => {
  vi.useFakeTimers();
  vi.stubGlobal('matchMedia', () => ({ matches: false }));
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe('useDialog close', () => {
  it('runs only the first after while the fade is pending', () => {
    const { result } = renderHook(() => useDialog({ onEscape: () => {}, opener: null }));
    const first = vi.fn();
    const second = vi.fn();

    result.current.close(first);
    result.current.close(second);
    vi.advanceTimersByTime(FADE_MS * 2);

    expect(first).toHaveBeenCalledTimes(1);
    expect(second).not.toHaveBeenCalled();
  });

  it('runs only the first after under reduced motion too', () => {
    vi.stubGlobal('matchMedia', () => ({ matches: true }));
    const { result } = renderHook(() => useDialog({ onEscape: () => {}, opener: null }));
    const first = vi.fn();
    const second = vi.fn();

    result.current.close(first);
    result.current.close(second);

    expect(first).toHaveBeenCalledTimes(1);
    expect(second).not.toHaveBeenCalled();
  });

  it('never runs after when the dialog unmounts before the fade ends', () => {
    const { result, unmount } = renderHook(() => useDialog({ onEscape: () => {}, opener: null }));
    const first = vi.fn();

    result.current.close(first);
    unmount();
    vi.advanceTimersByTime(FADE_MS * 2);

    expect(first).not.toHaveBeenCalled();
  });
});
