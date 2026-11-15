import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SESSION_EXPIRED_EVENT } from '../api/client.js';
import { sessionApi } from '../api/resources.js';
import { useSignOut } from './useSignOut.js';

const announced = vi.fn();
const listen = (event) => announced(event.detail);

beforeEach(() => window.addEventListener(SESSION_EXPIRED_EVENT, listen));
afterEach(() => {
  window.removeEventListener(SESSION_EXPIRED_EVENT, listen);
  announced.mockReset();
  vi.restoreAllMocks();
});

describe('useSignOut', () => {
  it('signs out once and announces it, busy only while the request is open', async () => {
    let finish;
    const signOutSpy = vi.spyOn(sessionApi, 'signOut').mockReturnValue(
      new Promise((resolve) => {
        finish = resolve;
      }),
    );
    const { result } = renderHook(() => useSignOut());
    expect(result.current.signingOut).toBe(false);

    let pending;
    act(() => {
      pending = result.current.signOut();
    });
    expect(result.current.signingOut).toBe(true);
    expect(announced).not.toHaveBeenCalled();

    await act(async () => {
      finish();
      await pending;
    });
    expect(signOutSpy).toHaveBeenCalledTimes(1);
    expect(announced).toHaveBeenCalledTimes(1);
    expect(result.current.signingOut).toBe(false);
    expect(result.current.error).toBeNull();
  });

  it('reports a failure, does not announce, and a retry clears the message', async () => {
    const signOutSpy = vi.spyOn(sessionApi, 'signOut').mockRejectedValueOnce(new Error('down'));
    const { result } = renderHook(() => useSignOut());

    await act(() => result.current.signOut());
    expect(result.current.error).toBe("Couldn't sign out. Try again.");
    expect(result.current.signingOut).toBe(false);
    expect(announced).not.toHaveBeenCalled();

    signOutSpy.mockResolvedValueOnce();
    await act(() => result.current.signOut());
    expect(result.current.error).toBeNull();
    expect(announced).toHaveBeenCalledTimes(1);
    expect(announced).toHaveBeenCalledWith({ reason: 'signed-out' });
  });
});
