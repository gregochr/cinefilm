import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { installStorage } from '../test/storage.js';
import { GROUP_KEY, useGroupCollections } from './useGroupCollections.js';

let storage;
beforeEach(() => {
  storage = installStorage();
});
afterEach(() => vi.unstubAllGlobals());

describe('useGroupCollections', () => {
  it('is off by default', () => {
    const { result } = renderHook(() => useGroupCollections());
    expect(result.current[0]).toBe(false);
  });

  it('remembers "on" as 1 and removes the key when turned off', () => {
    const { result } = renderHook(() => useGroupCollections());
    act(() => result.current[1](true));
    expect(result.current[0]).toBe(true);
    expect(storage.getItem(GROUP_KEY)).toBe('1');
    expect(renderHook(() => useGroupCollections()).result.current[0]).toBe(true);
    act(() => result.current[1](false));
    expect(storage.getItem(GROUP_KEY)).toBeNull();
  });

  it('still works for the visit when storage is blocked', () => {
    const blocked = () => {
      throw new Error('blocked');
    };
    storage.getItem = blocked;
    storage.setItem = blocked;
    const { result } = renderHook(() => useGroupCollections());
    expect(result.current[0]).toBe(false);
    act(() => result.current[1](true));
    expect(result.current[0]).toBe(true);
  });
});
