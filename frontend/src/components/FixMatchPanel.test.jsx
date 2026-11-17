import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { installFetch, respond } from '../test/mockApi.jsx';
import FixMatchPanel from './FixMatchPanel.jsx';
import { FADE_MS } from './useDialog.js';

const item = { filmId: 31, title: 'The Wicker Man', files: ['The Wicker Man.m4v'] };
const isApply = (m, url) => m === 'POST' && url === '/api/admin/films/31/match';
const applied = { filmId: 31, joined: false };

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  vi.stubGlobal('matchMedia', () => ({ matches: false }));
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

async function open(route) {
  installFetch(route);
  const onApplied = vi.fn();
  const onClose = vi.fn();
  const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
  const view = render(<FixMatchPanel item={item} onApplied={onApplied} onClose={onClose} />);
  const dialog = screen.getByRole('dialog', { name: 'Fix match' });
  await user.click((await within(dialog).findAllByRole('radio'))[0]);
  return { dialog, user, onApplied, onClose, unmount: view.unmount };
}

const settle = () => act(async () => vi.advanceTimersByTimeAsync(FADE_MS * 3));

describe('FixMatchPanel reports once', () => {
  it('reports an apply at once, and Esc and the close button during the fade change nothing', async () => {
    const { dialog, user, onApplied, onClose } = await open((m, url) =>
      isApply(m, url) ? respond(applied) : null,
    );

    await user.click(within(dialog).getByRole('button', { name: 'Use this match' }));
    expect(onApplied).toHaveBeenCalledTimes(1);
    expect(onApplied).toHaveBeenCalledWith(applied, false);
    expect(onClose).not.toHaveBeenCalled();
    await user.keyboard('{Escape}');
    await user.click(within(dialog).getByRole('button', { name: 'Close' }));
    expect(onClose).not.toHaveBeenCalled(); // the fade is still running
    await settle();

    expect(onApplied).toHaveBeenCalledTimes(1);
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('an unmount during the fade still leaves the apply reported once and never closes', async () => {
    const { dialog, user, onApplied, onClose, unmount } = await open((m, url) =>
      isApply(m, url) ? respond(applied) : null,
    );

    await user.click(within(dialog).getByRole('button', { name: 'Use this match' }));
    unmount();
    await settle();

    expect(onApplied).toHaveBeenCalledTimes(1);
    expect(onClose).not.toHaveBeenCalled();
  });

  it('Cancel, then Esc during the fade, then the apply lands: each runs once', async () => {
    let release;
    const slow = new Promise((resolve) => {
      release = resolve;
    });
    const { dialog, user, onApplied, onClose } = await open((m, url) =>
      isApply(m, url) ? slow : null,
    );
    await user.click(within(dialog).getByRole('button', { name: 'Use this match' }));

    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    await user.keyboard('{Escape}');
    await act(async () => release(respond(applied)));
    // Reported at once; the close waits for the end of the fade.
    expect(onApplied).toHaveBeenCalledTimes(1);
    expect(onApplied).toHaveBeenCalledWith(applied, false);
    expect(onClose).not.toHaveBeenCalled();
    await settle();

    expect(onClose).toHaveBeenCalledTimes(1);
    expect(onApplied).toHaveBeenCalledTimes(1);
  });

  it('an apply that lands after the dialog unmounted is still reported, and nothing is closed', async () => {
    let release;
    const slow = new Promise((resolve) => {
      release = resolve;
    });
    const { dialog, user, onApplied, onClose, unmount } = await open((m, url) =>
      isApply(m, url) ? slow : null,
    );
    await user.click(within(dialog).getByRole('button', { name: 'Use this match' }));

    unmount();
    await act(async () => release(respond(applied)));
    await settle();

    expect(onApplied).toHaveBeenCalledTimes(1);
    expect(onClose).not.toHaveBeenCalled();
  });
});
