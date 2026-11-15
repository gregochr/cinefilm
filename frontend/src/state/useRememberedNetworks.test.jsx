import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { installStorage } from '../test/storage.js';
import { NETWORKS_KEY } from './networks.js';
import { useRememberedNetworks } from './useRememberedNetworks.js';

const VIRGIN = 'AS5089 Virgin Media Limited';
const RAIL = 'AS64500 Example Rail';

let storage;
beforeEach(() => {
  storage = installStorage({
    [NETWORKS_KEY]: JSON.stringify([
      { operator: VIRGIN, environment: 'away' },
      { operator: RAIL, environment: 'train' },
    ]),
  });
});
afterEach(() => vi.restoreAllMocks());

describe('useRememberedNetworks', () => {
  it('lists the networks newest answer first', () => {
    const { result } = renderHook(() => useRememberedNetworks());
    expect(result.current.networks.map((n) => n.operator)).toEqual([RAIL, VIRGIN]);
    expect(result.current.failed).toBeNull();
  });

  it('is empty when nothing is remembered', () => {
    storage.clear();
    const { result } = renderHook(() => useRememberedNetworks());
    expect(result.current.networks).toEqual([]);
  });

  it('forgets one network, keeps the other and says it is gone', () => {
    const { result } = renderHook(() => useRememberedNetworks());
    let gone;
    act(() => {
      gone = result.current.forget(RAIL);
    });
    expect(gone).toBe(true);
    expect(result.current.networks.map((n) => n.operator)).toEqual([VIRGIN]);
    expect(JSON.parse(storage.getItem(NETWORKS_KEY))).toEqual([
      { operator: VIRGIN, environment: 'away' },
    ]);
    expect(result.current.failed).toBeNull();
  });

  it('keeps the network and names it when the browser refuses the write', () => {
    const { result } = renderHook(() => useRememberedNetworks());
    vi.spyOn(storage, 'setItem').mockImplementation(() => {
      throw new Error('full');
    });
    let gone;
    act(() => {
      gone = result.current.forget(RAIL);
    });
    expect(gone).toBe(false);
    expect(result.current.networks.map((n) => n.operator)).toEqual([RAIL, VIRGIN]);
    expect(result.current.failed).toBe(RAIL);
  });

  it('clears an earlier failure once a later forget works', () => {
    const { result } = renderHook(() => useRememberedNetworks());
    const spy = vi.spyOn(storage, 'setItem').mockImplementation(() => {
      throw new Error('full');
    });
    act(() => {
      result.current.forget(RAIL);
    });
    expect(result.current.failed).toBe(RAIL);
    spy.mockRestore();
    act(() => {
      result.current.forget(RAIL);
    });
    expect(result.current.failed).toBeNull();
  });
});
