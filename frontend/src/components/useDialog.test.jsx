import { fireEvent, render, renderHook, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FADE_MS, containTab, useDialog } from './useDialog.js';

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
  function renderDialog() {
    render(
      // The dialog only listens for Tab, bubbled from the controls inside it.
      // eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions
      <div
        role="dialog"
        aria-label="Test"
        tabIndex={-1}
        onKeyDown={(event) => containTab(event, event.currentTarget)}
      >
        <button type="button">A</button>
        <button type="button">B</button>
        <button type="button">C</button>
      </div>,
    );
    return {
      dialog: screen.getByRole('dialog', { name: 'Test' }),
      a: screen.getByRole('button', { name: 'A' }),
      b: screen.getByRole('button', { name: 'B' }),
      c: screen.getByRole('button', { name: 'C' }),
    };
  }

  // Disabling and the key happen in one task: jsdom's focus fixup (its own real timer) cannot run between them.
  function disableWhileFocused(button) {
    button.focus();
    button.disabled = true;
    expect(button).toHaveFocus();
  }

  // fireEvent returns false when the key's default was prevented.
  const tab = (shiftKey = false) =>
    fireEvent.keyDown(document.activeElement, { key: 'Tab', shiftKey });

  it('moves on to the next stop when the focused middle stop is disabled', () => {
    const { b, c } = renderDialog();
    disableWhileFocused(b);

    expect(tab()).toBe(false);
    expect(c).toHaveFocus();
  });

  it('moves back to the previous stop with Shift+Tab from a disabled middle stop', () => {
    const { a, b } = renderDialog();
    disableWhileFocused(b);

    expect(tab(true)).toBe(false);
    expect(a).toHaveFocus();
  });

  it('wraps to the first stop when the focused last stop is disabled', () => {
    const { a, c } = renderDialog();
    disableWhileFocused(c);

    expect(tab()).toBe(false);
    expect(a).toHaveFocus();
  });

  it('wraps to the last stop with Shift+Tab when the focused first stop is disabled', () => {
    const { a, c } = renderDialog();
    disableWhileFocused(a);

    expect(tab(true)).toBe(false);
    expect(c).toHaveFocus();
  });

  it('goes to the first stop with Tab from the dialog element itself', () => {
    const { dialog, a } = renderDialog();
    dialog.focus();

    expect(tab()).toBe(false);
    expect(a).toHaveFocus();
  });

  it('goes to the last stop with Shift+Tab from the dialog element itself', () => {
    const { dialog, c } = renderDialog();
    dialog.focus();

    expect(tab(true)).toBe(false);
    expect(c).toHaveFocus();
  });

  it('leaves Tab from a middle stop to the browser', () => {
    const { b } = renderDialog();
    b.focus();

    expect(tab()).toBe(true);
    expect(b).toHaveFocus();
  });
});
