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
  const onCancel = vi.fn();
  const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
  render(<FixMatchPanel item={item} onApplied={onApplied} onCancel={onCancel} />);
  const dialog = screen.getByRole('dialog', { name: 'Fix match' });
  await user.click((await within(dialog).findAllByRole('radio'))[0]);
  return { dialog, user, onApplied, onCancel };
}

const settle = () => act(async () => vi.advanceTimersByTimeAsync(FADE_MS * 3));

describe('FixMatchPanel reports once', () => {
  it('an apply, then Esc and the close button during the fade: onApplied once, onCancel never', async () => {
    const { dialog, user, onApplied, onCancel } = await open((m, url) =>
      isApply(m, url) ? respond(applied) : null,
    );

    await user.click(within(dialog).getByRole('button', { name: 'Use this match' }));
    await user.keyboard('{Escape}');
    await user.click(within(dialog).getByRole('button', { name: 'Close' }));
    await settle();

    expect(onApplied).toHaveBeenCalledTimes(1);
    expect(onApplied).toHaveBeenCalledWith(applied, false);
    expect(onCancel).not.toHaveBeenCalled();
  });

  it('Cancel, then Esc during the fade, then the apply lands: each runs once', async () => {
    let release;
    const slow = new Promise((resolve) => {
      release = resolve;
    });
    const { dialog, user, onApplied, onCancel } = await open((m, url) =>
      isApply(m, url) ? slow : null,
    );
    await user.click(within(dialog).getByRole('button', { name: 'Use this match' }));

    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    await user.keyboard('{Escape}');
    await act(async () => release(respond(applied)));
    // Reported at once, still mounted so not stale; the Cancel waits for the end of the fade.
    expect(onApplied).toHaveBeenCalledTimes(1);
    expect(onApplied).toHaveBeenCalledWith(applied, false);
    expect(onCancel).not.toHaveBeenCalled();
    await settle();

    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(onApplied).toHaveBeenCalledTimes(1);
  });
});
