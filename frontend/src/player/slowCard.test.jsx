import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useSlowCard } from './slowCard.js';

beforeEach(() => sessionStorage.clear());
afterEach(() => vi.unstubAllGlobals());

describe('the slow-connection card shows once per session (decision 11)', () => {
  it('stays closed while the connection is not slow', () => {
    const { result } = renderHook(() => useSlowCard(false));
    expect(result.current.open).toBe(false);
  });

  it('opens when the connection turns slow, and closes when asked', () => {
    const { result } = renderHook(() => useSlowCard(true));
    expect(result.current.open).toBe(true);
    act(() => result.current.close());
    expect(result.current.open).toBe(false);
  });

  it('does not open again in the same session, even for a fresh player', () => {
    const first = renderHook(() => useSlowCard(true));
    expect(first.result.current.open).toBe(true);
    first.unmount();
    const second = renderHook(() => useSlowCard(true));
    expect(second.result.current.open).toBe(false);
  });

  it('opens when slowness starts after the hook was mounted', () => {
    const { result, rerender } = renderHook(({ slow }) => useSlowCard(slow), {
      initialProps: { slow: false },
    });
    rerender({ slow: true });
    expect(result.current.open).toBe(true);
  });

  it('still shows, and does not break, when browser storage is blocked', () => {
    const blocked = () => {
      throw new Error('blocked');
    };
    vi.stubGlobal('sessionStorage', { getItem: blocked, setItem: blocked });
    const { result } = renderHook(() => useSlowCard(true));
    expect(result.current.open).toBe(true);
  });
});
