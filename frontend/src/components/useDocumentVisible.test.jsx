import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { useDocumentVisible } from './useDocumentVisible.js';

afterEach(() => {
  delete document.visibilityState;
});

const setVisibility = (value) => {
  Object.defineProperty(document, 'visibilityState', { value, configurable: true });
  document.dispatchEvent(new Event('visibilitychange'));
};

describe('useDocumentVisible', () => {
  it('is true while the tab is visible, false when hidden, and true again when shown', () => {
    const { result } = renderHook(() => useDocumentVisible());
    expect(result.current).toBe(true);

    act(() => setVisibility('hidden'));
    expect(result.current).toBe(false);

    act(() => setVisibility('visible'));
    expect(result.current).toBe(true);
  });

  it('stops listening when it unmounts', () => {
    const { result, unmount } = renderHook(() => useDocumentVisible());
    unmount();

    act(() => setVisibility('hidden'));

    expect(result.current).toBe(true);
  });
});
