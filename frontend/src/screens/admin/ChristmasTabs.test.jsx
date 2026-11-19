import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import ChristmasTabs from './ChristmasTabs.jsx';

const counts = { inWeek: 14, added: 1, leftOut: 3 };

function setup(selected = 'inWeek') {
  const onSelect = vi.fn();
  render(<ChristmasTabs counts={counts} selected={selected} onSelect={onSelect} />);
  return {
    onSelect,
    inWeek: screen.getByRole('tab', { name: 'In Christmas week, 14' }),
    added: screen.getByRole('tab', { name: 'Added by me, 1' }),
    leftOut: screen.getByRole('tab', { name: 'Left out by me, 3' }),
  };
}

describe('ChristmasTabs', () => {
  it('is a tablist of three tabs named with their counts, one selected', () => {
    const { inWeek, added, leftOut } = setup('added');
    expect(screen.getByRole('tablist', { name: 'Christmas lists' })).toBeInTheDocument();
    expect(added).toHaveAttribute('aria-selected', 'true');
    expect(inWeek).toHaveAttribute('aria-selected', 'false');
    expect(leftOut).toHaveAttribute('aria-selected', 'false');
  });

  it('keeps only the selected tab in the tab order', () => {
    const { inWeek, added, leftOut } = setup('inWeek');
    expect(inWeek).toHaveAttribute('tabindex', '0');
    expect(added).toHaveAttribute('tabindex', '-1');
    expect(leftOut).toHaveAttribute('tabindex', '-1');
  });

  it('selects a tab on click', async () => {
    const { onSelect, leftOut } = setup();
    await userEvent.click(leftOut);
    expect(onSelect).toHaveBeenCalledWith('leftOut');
  });

  it('moves with the arrows, wrapping at both ends, and focuses the tab it selects', async () => {
    const { onSelect, inWeek, added, leftOut } = setup('inWeek');
    inWeek.focus();
    await userEvent.keyboard('{ArrowRight}');
    expect(onSelect).toHaveBeenLastCalledWith('added');
    expect(added).toHaveFocus();
    await userEvent.keyboard('{ArrowLeft}');
    expect(onSelect).toHaveBeenLastCalledWith('leftOut');
    expect(leftOut).toHaveFocus();
  });

  it('jumps with Home and End and ignores other keys', async () => {
    const { onSelect, inWeek, leftOut } = setup('added');
    screen.getByRole('tab', { name: 'Added by me, 1' }).focus();
    await userEvent.keyboard('{End}');
    expect(onSelect).toHaveBeenLastCalledWith('leftOut');
    expect(leftOut).toHaveFocus();
    await userEvent.keyboard('{Home}');
    expect(onSelect).toHaveBeenLastCalledWith('inWeek');
    expect(inWeek).toHaveFocus();
    onSelect.mockClear();
    await userEvent.keyboard('x');
    expect(onSelect).not.toHaveBeenCalled();
  });

  it('carries both a long and a short label for the stylesheet, and reads the long name aloud', () => {
    const { inWeek } = setup();
    expect(inWeek).toHaveTextContent('In Christmas weekIn week14');
  });
});
