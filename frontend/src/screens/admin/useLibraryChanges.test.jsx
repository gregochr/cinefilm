import { act, renderHook } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { announceLibraryChanged } from '../../api/client.js';
import { useLibraryChanges } from './useLibraryChanges.js';

describe('useLibraryChanges', () => {
  it('counts each announcement', () => {
    const { result } = renderHook(() => useLibraryChanges());
    expect(result.current).toBe(0);

    act(() => announceLibraryChanged());
    act(() => announceLibraryChanged());

    expect(result.current).toBe(2);
  });
});
