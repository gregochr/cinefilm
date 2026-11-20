import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import ChristmasPreviewSheet from './ChristmasPreviewSheet.jsx';

function setup() {
  const onClose = vi.fn();
  render(
    <>
      <button type="button">behind</button>
      <ChristmasPreviewSheet onClose={onClose} toast={<button type="button">Undo</button>}>
        <p>The row</p>
      </ChristmasPreviewSheet>
    </>,
  );
  return { onClose };
}

describe('ChristmasPreviewSheet', () => {
  it('is a modal dialog with the label, the row, the toast and Close focused', () => {
    setup();
    const dialog = screen.getByRole('dialog', { name: 'Home in Christmas week' });
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    expect(dialog).toHaveTextContent('Preview · Home in Christmas week');
    expect(dialog).toHaveTextContent('The row');
    expect(screen.getByRole('button', { name: 'Undo' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Close' })).toHaveFocus();
  });

  it('closes on Close and on Esc', async () => {
    const user = userEvent.setup();
    const { onClose } = setup();
    await user.click(screen.getByRole('button', { name: 'Close' }));
    expect(onClose).toHaveBeenCalledTimes(1);
    await user.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalledTimes(2);
  });

  it('keeps Tab inside, wrapping at both ends', async () => {
    const user = userEvent.setup();
    setup();
    await user.tab();
    expect(screen.getByRole('button', { name: 'Undo' })).toHaveFocus();
    await user.tab();
    expect(screen.getByRole('button', { name: 'Close' })).toHaveFocus();
    await user.tab({ shift: true });
    expect(screen.getByRole('button', { name: 'Undo' })).toHaveFocus();
  });

  it('stops the page behind scrolling, and lets it again on close', () => {
    document.body.style.overflow = 'auto';
    const view = render(<ChristmasPreviewSheet onClose={() => {}} toast={null} />);
    expect(document.body.style.overflow).toBe('hidden');
    view.unmount();
    expect(document.body.style.overflow).toBe('auto');
  });
});
