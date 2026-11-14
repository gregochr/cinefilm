import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { ActionWithHint } from './ActionWithHint.jsx';

describe('ActionWithHint', () => {
  it('shows the hint as visible text and describes the button with it', () => {
    render(<ActionWithHint hint="Does the thing.">Do it</ActionWithHint>);
    const button = screen.getByRole('button', { name: 'Do it' });
    expect(screen.getByText('Does the thing.')).toBeVisible();
    expect(button).toHaveAccessibleDescription('Does the thing.');
  });

  it('gives each instance its own link, so two rows do not describe each other', () => {
    render(
      <>
        <ActionWithHint hint="First.">One</ActionWithHint>
        <ActionWithHint layout="line" hint="Second.">
          Two
        </ActionWithHint>
      </>,
    );
    expect(screen.getByRole('button', { name: 'One' })).toHaveAccessibleDescription('First.');
    expect(screen.getByRole('button', { name: 'Two' })).toHaveAccessibleDescription('Second.');
  });

  it('is still a working button: clicks, disabled and the variant pass through', async () => {
    const onClick = vi.fn();
    const { rerender } = render(
      <ActionWithHint hint="Hint." variant="primary" className="extra" onClick={onClick}>
        Go
      </ActionWithHint>,
    );
    const button = screen.getByRole('button', { name: 'Go' });
    expect(button).toHaveClass('btn--primary');
    await userEvent.click(button);
    expect(onClick).toHaveBeenCalledTimes(1);
    rerender(
      <ActionWithHint hint="Hint." disabled onClick={onClick}>
        Go
      </ActionWithHint>,
    );
    expect(screen.getByRole('button', { name: 'Go' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Go' })).toHaveClass('btn--secondary');
  });

  // The alignment is a media-query-only CSS rule, so the layout class is the one thing to assert (style-only exception).
  it('takes a layout class for its alignment: row by default, line when asked', () => {
    render(
      <>
        <ActionWithHint hint="Row hint.">Row</ActionWithHint>
        <ActionWithHint layout="line" hint="Line hint.">
          Line
        </ActionWithHint>
      </>,
    );
    const [row, line] = screen.getAllByTestId('action-hint');
    expect(row).toHaveClass('action-hint--row');
    expect(line).toHaveClass('action-hint--line');
  });

  it('keeps an aria-label as the name and the hint as the description', () => {
    render(
      <ActionWithHint hint="Hint." aria-label="Go now">
        Go
      </ActionWithHint>,
    );
    expect(screen.getByRole('button', { name: 'Go now' })).toHaveAccessibleDescription('Hint.');
  });

  it('renders without a hint and without a dangling aria-describedby', () => {
    render(<ActionWithHint>Plain</ActionWithHint>);
    expect(screen.getByRole('button', { name: 'Plain' })).not.toHaveAttribute('aria-describedby');
  });
});
