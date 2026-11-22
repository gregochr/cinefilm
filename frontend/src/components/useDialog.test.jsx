import { renderHook, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { containTab, FADE_MS, useDialog } from './useDialog.js';

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

describe('containTab', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    document.body.replaceChildren();
  });

  it('pulls focus back in when it sits on a disabled button inside the dialog', () => {
    document.body.innerHTML =
      '<a href="/">Home</a><div role="dialog" aria-label="Fixture"><button>A</button><button disabled>B</button></div>';
    const dialog = screen.getByRole('dialog', { name: 'Fixture' });
    const inside = within(dialog);
    // An engine that does not blur a button the moment it is disabled keeps it as the active element; jsdom does not
    // allow focusing a disabled one, so pin it.
    const spy = vi
      .spyOn(document, 'activeElement', 'get')
      .mockReturnValue(inside.getByRole('button', { name: 'B' }));
    const event = new KeyboardEvent('keydown', { key: 'Tab', cancelable: true });

    containTab(event, dialog);

    spy.mockRestore();
    expect(event.defaultPrevented).toBe(true);
    expect(inside.getByRole('button', { name: 'A' })).toHaveFocus();
  });
});
