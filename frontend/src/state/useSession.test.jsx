import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { SESSION_EXPIRED_EVENT, announceSignedOut } from '../api/client.js';
import { useSession } from './useSession.js';

afterEach(() => vi.unstubAllGlobals());

describe('useSession', () => {
  it('flips to expired on the session-expired event and stops listening on unmount', () => {
    const remove = vi.spyOn(window, 'removeEventListener');
    const { result, unmount } = renderHook(() => useSession());
    expect(result.current.expired).toBe(false);
    act(() => window.dispatchEvent(new CustomEvent(SESSION_EXPIRED_EVENT)));
    expect(result.current.expired).toBe(true);
    unmount();
    expect(remove).toHaveBeenCalledWith(SESSION_EXPIRED_EVENT, expect.any(Function));
    remove.mockRestore();
  });

  it('is submitting while the request is in flight and clears the old error as it starts', async () => {
    let reply;
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      .mockImplementationOnce(() => new Promise((resolve) => (reply = resolve)));
    vi.stubGlobal('fetch', fetchMock);
    const { result } = renderHook(() => useSession());

    await act(async () => {
      expect(await result.current.signIn('wrong')).toBe(false);
    });
    expect(result.current.error).toMatch(/didn't work/);
    expect(result.current.submitting).toBe(false);

    let second;
    act(() => {
      second = result.current.signIn('right');
    });
    await waitFor(() => expect(result.current.submitting).toBe(true));
    expect(result.current.error).toBeNull();

    await act(async () => {
      reply(new Response(null, { status: 204 }));
      expect(await second).toBe(true);
    });
    expect(result.current.submitting).toBe(false);
    expect(result.current.expired).toBe(false);
  });

  it('records the signed-out reason and keeps it when a plain expiry follows', () => {
    const { result } = renderHook(() => useSession());
    expect(result.current.reason).toBe('expired');
    act(() => announceSignedOut());
    expect(result.current.expired).toBe(true);
    expect(result.current.reason).toBe('signed-out');
    act(() => window.dispatchEvent(new CustomEvent(SESSION_EXPIRED_EVENT)));
    expect(result.current.expired).toBe(true);
    expect(result.current.reason).toBe('signed-out');
  });

  it('a plain expiry records the expired reason', () => {
    const { result } = renderHook(() => useSession());
    act(() => window.dispatchEvent(new CustomEvent(SESSION_EXPIRED_EVENT)));
    expect(result.current.reason).toBe('expired');
  });

  it('a successful sign-in clears expired and resets the reason', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 204 })));
    const { result } = renderHook(() => useSession());
    act(() => announceSignedOut());
    await act(async () => {
      expect(await result.current.signIn('right')).toBe(true);
    });
    expect(result.current.expired).toBe(false);
    expect(result.current.reason).toBe('expired');
    act(() => window.dispatchEvent(new CustomEvent(SESSION_EXPIRED_EVENT)));
    expect(result.current.reason).toBe('expired');
  });
});
