import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import RetimeControl from './RetimeControl.jsx';

const download = (over = {}) => ({
  id: 6,
  origin: 'OPENSUBTITLES',
  retime: 'AUTO',
  retimedBy: 0.95904,
  timedFps: 23.976,
  filmFps: 25,
  ...over,
});

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

  it('says "Retime" for a file switched off', () => {
    show(download({ retime: 'OFF', retimedBy: null }));

    expect(screen.getByRole('button', { name: 'Retime: Blade.Runner' })).toBeInTheDocument();
  });

  it('sends false from "Don’t retime", for the row’s id', async () => {
    const retime = show(download());

    await userEvent.click(screen.getByRole('button', { name: /Don’t retime/ }));

    expect(retime).toHaveBeenCalledExactlyOnceWith(6, false);
  });

  it('sends true from "Retime"', async () => {
    const retime = show(download({ id: 8, retime: 'OFF', retimedBy: null }));

    await userEvent.click(screen.getByRole('button', { name: /Retime:/ }));

    expect(retime).toHaveBeenCalledExactlyOnceWith(8, true);
  });

  it.each([
    ['a file that fits as it is', { retime: 'OFF_FITS' }],
    ['an owner’s file', { origin: 'FILE' }],
    ['a file with no factor to apply', { retime: null, retimedBy: null, filmFps: null }],
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
