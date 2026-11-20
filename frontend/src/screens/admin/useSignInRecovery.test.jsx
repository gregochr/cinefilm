import { act, renderHook } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { announceSignedIn } from '../../api/client.js';
import { useSignInRecovery } from './useSignInRecovery.js';

describe('useSignInRecovery', () => {
  it('counts each sign-in, and stops listening on unmount', () => {
    const remove = vi.spyOn(window, 'removeEventListener');
    const { result, unmount } = renderHook(() => useSignInRecovery());
    expect(result.current).toBe(0);
    act(() => announceSignedIn());
    act(() => announceSignedIn());
    expect(result.current).toBe(2);

    unmount();
    expect(remove).toHaveBeenCalledWith('cinefilm:signed-in', expect.any(Function));
    remove.mockRestore();
  });
});
