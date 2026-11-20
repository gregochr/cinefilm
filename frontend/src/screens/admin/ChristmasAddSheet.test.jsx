import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import ChristmasAddSheet from './ChristmasAddSheet.jsx';

const rows = [
  {
    id: 'o-1',
    filmId: 1,
    tmdbId: 101,
    title: 'Batman',
    year: 1989,
    posterPath: null,
    state: 'NOT_IN',
  },
  {
    id: 'o-2',
    filmId: 2,
    tmdbId: 102,
    title: 'Batman Returns',
    year: 1992,
    posterPath: null,
    state: 'IN_ADDED',
  },
];

function setup({ query = 'batman', busy = new Set(), ready = true } = {}) {
  const onClose = vi.fn();
  const onAct = vi.fn();
  const setQuery = vi.fn();
  const add = {
    state: { query, open: query !== '', active: 0 },
    rows,
    ready,
    statesFailed: false,
    setQuery,
  };
  const view = render(
    <>
      <button type="button">behind</button>
      <ChristmasAddSheet
        add={add}
        head="2 films in your library"
        busy={busy}
        onAct={onAct}
        registerButton={() => () => {}}
        onClose={onClose}
        toast={<button type="button">Undo</button>}
      />
    </>,
  );
  return { onClose, onAct, setQuery, ...view };
}

const field = () => screen.getByRole('textbox', { name: 'Add a film' });

describe('ChristmasAddSheet', () => {
  it('is a dialog with the field focused, Cancel, the results and the toast', () => {
    setup();
    const dialog = screen.getByRole('dialog', { name: 'Add a film' });
    expect(field()).toHaveFocus();
    expect(field()).toHaveValue('batman');
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Add Batman' })).toBeInTheDocument();
    expect(screen.getByRole('list', { name: 'Films in your library' })).toBeInTheDocument();
    expect(dialog).toContainElement(screen.getByRole('status'));
    expect(dialog).toContainElement(screen.getByRole('button', { name: 'Undo' }));
    expect(document.body.style.overflow).toBe('hidden');
  });

  it('shows no results before anything is typed, or while the index is not ready', () => {
    const { unmount } = setup({ query: '' });
    expect(screen.queryByRole('list')).not.toBeInTheDocument();
    unmount();
    setup({ ready: false });
    expect(screen.queryByRole('listitem')).not.toBeInTheDocument();
  });

  it('types into the query', async () => {
    const user = userEvent.setup();
    const { setQuery } = setup({ query: '' });
    await user.keyboard('b');
    expect(setQuery).toHaveBeenCalledWith('b');
  });

  it('acts on a press and stays open', async () => {
    const user = userEvent.setup();
    const { onAct, onClose } = setup();
    await user.click(screen.getByRole('button', { name: 'Add Batman' }));
    expect(onAct).toHaveBeenCalledWith(rows[0], screen.getByRole('button', { name: 'Add Batman' }));
    expect(onClose).not.toHaveBeenCalled();
  });

  it('closes on Cancel and on Esc from the field and from a button', async () => {
    const user = userEvent.setup();
    const { onClose } = setup();
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onClose).toHaveBeenCalledTimes(1);
    await user.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalledTimes(2);
    screen.getByRole('button', { name: 'Remove Batman Returns' }).focus();
    await user.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalledTimes(3);
  });

  it('keeps Tab inside the sheet, toast included', async () => {
    const user = userEvent.setup();
    setup();
    const undo = screen.getByRole('button', { name: 'Undo' });
    undo.focus();
    await user.tab();
    // The toast is the last thing in the dialog: Tab wraps to the first, not to the page behind.
    expect(field()).toHaveFocus();
    expect(screen.getByRole('button', { name: 'behind' })).not.toHaveFocus();
    await user.tab({ shift: true });
    expect(undo).toHaveFocus();
  });

  it('puts the page scroll back when it goes', () => {
    const { unmount } = setup();
    expect(document.body.style.overflow).toBe('hidden');
    unmount();
    expect(document.body.style.overflow).toBe('');
  });
});
