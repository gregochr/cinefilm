import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { AdminContext } from '../../state/adminContext.js';
import SectionTitle from './SectionTitle.jsx';
import { useFallbackFocus } from './useFallbackFocus.js';

function Section({ signal }) {
  useFallbackFocus(signal);
  return (
    <>
      <SectionTitle>To do</SectionTitle>
      <button type="button">Row</button>
    </>
  );
}

// The hook is armed by the layout after a dialog's save, so it is armed only once the section is on screen.
function setup(armedLater) {
  let armed = false;
  const take = vi.fn(() => armed);
  const view = (signal) => (
    <AdminContext.Provider value={{ takeFocusFallback: take }}>
      <Section signal={signal} />
    </AdminContext.Provider>
  );
  const { rerender } = render(view(1));
  return {
    take,
    change: () => {
      armed = armedLater;
      rerender(view(2));
    },
  };
}

describe('useFallbackFocus', () => {
  it('focuses the heading when armed and focus has fallen to the page body', () => {
    const { take, change } = setup(true);
    take.mockClear();
    expect(document.body).toHaveFocus();

    change();

    expect(screen.getByRole('heading', { level: 1, name: 'To do' })).toHaveFocus();
    expect(take).toHaveBeenCalledTimes(1);
  });

  it('leaves focus where it is when it is on a real element', () => {
    const { change } = setup(true);
    const row = screen.getByRole('button', { name: 'Row' });
    row.focus();

    change();

    expect(row).toHaveFocus();
  });

  it('does nothing when not armed (a stale apply, or no apply at all)', () => {
    const { change } = setup(false);
    expect(document.body).toHaveFocus();

    change();

    expect(screen.getByRole('heading', { level: 1, name: 'To do' })).not.toHaveFocus();
    expect(document.body).toHaveFocus();
  });
});
