import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import UndoToast from './UndoToast.jsx';

const film = { id: 3, title: 'Alien' };

describe('UndoToast', () => {
  it('is an empty status region when there is nothing to undo', () => {
    render(<UndoToast action={null} onUndo={() => {}} />);
    expect(screen.getByRole('status')).toBeEmptyDOMElement();
    expect(screen.queryByRole('button', { name: 'Undo' })).not.toBeInTheDocument();
  });

  it('says a film was removed from Continue watching, with an Undo button', async () => {
    const onUndo = vi.fn();
    render(<UndoToast action={{ kind: 'remove', film }} onUndo={onUndo} />);
    expect(screen.getByRole('status')).toHaveTextContent('Alien removed from Continue watching');
    await userEvent.click(screen.getByRole('button', { name: 'Undo' }));
    expect(onUndo).toHaveBeenCalledTimes(1);
  });

  it('says a film was marked as watched', () => {
    render(<UndoToast action={{ kind: 'watched', film }} onUndo={() => {}} />);
    expect(screen.getByRole('status')).toHaveTextContent('Alien marked as watched');
  });
});
