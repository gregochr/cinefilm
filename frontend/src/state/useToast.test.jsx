import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { TOAST_MS, useToast } from './useToast.js';

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

describe('useToast', () => {
  it('shows a message and clears it after four seconds, not before', () => {
    const { result } = renderHook(() => useToast());
    expect(result.current.message).toBeNull();

    act(() => result.current.show('Editions saved'));
    expect(result.current.message).toBe('Editions saved');
    act(() => vi.advanceTimersByTime(TOAST_MS - 1));
    expect(result.current.message).toBe('Editions saved');
    act(() => vi.advanceTimersByTime(1));
    expect(result.current.message).toBeNull();
    expect(TOAST_MS).toBe(4000);
  });

  it('a newer message replaces the older one and restarts the clock', () => {
    const { result } = renderHook(() => useToast());
    act(() => result.current.show('First'));
    act(() => vi.advanceTimersByTime(3000));
    act(() => result.current.show('Second'));
    act(() => vi.advanceTimersByTime(3000));

    expect(result.current.message).toBe('Second');
    act(() => vi.advanceTimersByTime(1000));
    expect(result.current.message).toBeNull();
  });

  it('leaves no timer behind when it unmounts', () => {
    const { result, unmount } = renderHook(() => useToast());
    act(() => result.current.show('Gone'));
    unmount();
    expect(vi.getTimerCount()).toBe(0);
  });
});
