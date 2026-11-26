import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import RetimeControl from './RetimeControl.jsx';

const download = (over = {}) => ({
  id: 6,
  origin: 'OPENSUBTITLES',
  retimeAction: 'DONT',
  ...over,
});
const notRetimed = (over = {}) => download({ retimeAction: 'RETIME', ...over });

function show(item, { busy = false, retime = vi.fn() } = {}) {
  render(<RetimeControl item={item} name="Blade.Runner" busy={busy} actions={{ retime }} />);
  return retime;
}

describe('RetimeControl', () => {
  it('says "Don’t retime" for a file that is retimed, with its hint', () => {
    show(download());

    const button = screen.getByRole('button', { name: 'Don’t retime: Blade.Runner' });
    expect(button).toHaveAccessibleDescription('Plays it as it was timed; it may drift.');
  });

  it('says "Retime" for a file that is not retimed, with a hint that does not say "again"', () => {
    show(notRetimed());

    const button = screen.getByRole('button', { name: 'Retime: Blade.Runner' });
    expect(button).toHaveAccessibleDescription('Puts it on this film’s timeline.');
  });

  it.each([
    ['an owner’s timing change', { offsetSource: 'OWNER' }],
    ['a "Looks right" check', { verified: true }],
  ])('warns that retiming resets %s', (_, over) => {
    show(notRetimed(over));

    expect(screen.getByRole('button', { name: /^Retime:/ })).toHaveAccessibleDescription(
      'Puts it on this film’s timeline. This resets its timing change and its “Looks right” check.',
    );
  });

  it('gives "Don’t retime" the same hint whatever the owner did', () => {
    show(download({ offsetSource: 'OWNER', verified: true }));

    expect(screen.getByRole('button', { name: /Don’t retime/ })).toHaveAccessibleDescription(
      'Plays it as it was timed; it may drift.',
    );
  });

  it('sends false from "Don’t retime", for the row’s id', async () => {
    const retime = show(download());

    await userEvent.click(screen.getByRole('button', { name: /Don’t retime/ }));

    expect(retime).toHaveBeenCalledExactlyOnceWith(6, false);
  });

  it('sends true from "Retime"', async () => {
    const retime = show(notRetimed({ id: 8 }));

    await userEvent.click(screen.getByRole('button', { name: /Retime:/ }));

    expect(retime).toHaveBeenCalledExactlyOnceWith(8, true);
  });

  it.each([
    ['a file the server offers nothing for', { retimeAction: null }],
    ['an owner’s file', { origin: 'FILE' }],
  ])('shows nothing for %s', (_, over) => {
    show(download(over));

    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('is aria-disabled and sends nothing while an action runs, and stays focusable', async () => {
    const retime = show(download(), { busy: true });
    const button = screen.getByRole('button', { name: /Don’t retime/ });

    expect(button).toHaveAttribute('aria-disabled', 'true');
    await userEvent.tab();
    expect(button).toHaveFocus();
    await userEvent.keyboard('{Enter}');
    expect(retime).not.toHaveBeenCalled();
    expect(button).toHaveFocus();
  });
});
