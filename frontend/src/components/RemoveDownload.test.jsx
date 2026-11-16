import { useState } from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import RemoveDownload from './RemoveDownload.jsx';

const item = { id: 8 };
const openButton = () => screen.getByRole('button', { name: 'Delete this download: Alien.1979' });

function setup(props = {}) {
  const actions = { remove: vi.fn().mockResolvedValue(true) };
  const onRemoved = vi.fn();
  render(
    <RemoveDownload
      item={item}
      name="Alien.1979"
      busy={false}
      failed={false}
      actions={actions}
      onRemoved={onRemoved}
      {...props}
    />,
  );
  return { actions, onRemoved };
}

/** Owns what the Fix… panel owns for it: `busy` while the delete runs, `failed` after a refusal. */
function Harness({ remove }) {
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);
  const actions = {
    remove: async (id) => {
      setBusy(true);
      setFailed(false);
      const ok = await remove(id);
      setBusy(false);
      setFailed(!ok);
      return ok;
    },
  };
  return (
    <RemoveDownload
      item={item}
      name="Alien.1979"
      busy={busy}
      failed={failed}
      actions={actions}
      onRemoved={() => {}}
    />
  );
}

describe('RemoveDownload', () => {
  it('has one wording: "Delete this download" with its hint, then "Delete download" and "Keep it"', async () => {
    setup();

    expect(openButton()).toHaveTextContent('Delete this download');
    expect(openButton()).toHaveAccessibleDescription(
      'Deletes the downloaded file; CineFilm won’t fetch it again.',
    );
    await userEvent.click(openButton());

    expect(screen.getByRole('group', { name: 'Delete this download' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Delete download' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Keep it' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Remove/ })).not.toBeInTheDocument();
  });

  it('removes through the action and tells its owner once the download is gone', async () => {
    const { actions, onRemoved } = setup();

    await userEvent.click(openButton());
    await userEvent.click(screen.getByRole('button', { name: 'Delete download' }));

    await waitFor(() => expect(onRemoved).toHaveBeenCalledTimes(1));
    expect(actions.remove).toHaveBeenCalledExactlyOnceWith(8);
  });

  it('does not tell its owner when the removal failed', async () => {
    const { actions, onRemoved } = setup();
    actions.remove.mockResolvedValue(false);

    await userEvent.click(openButton());
    await userEvent.click(screen.getByRole('button', { name: 'Delete download' }));

    await waitFor(() => expect(actions.remove).toHaveBeenCalledTimes(1));
    expect(onRemoved).not.toHaveBeenCalled();
  });

  it('says "Try again" after a failure', async () => {
    setup({ failed: true });

    await userEvent.click(openButton());

    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Delete download' })).not.toBeInTheDocument();
  });

  it('focuses the safe choice, and "Keep it" gives focus back to the opening button without removing', async () => {
    const { actions } = setup();

    await userEvent.click(openButton());
    const keep = screen.getByRole('button', { name: 'Keep it' });
    await waitFor(() => expect(keep).toHaveFocus());
    await userEvent.click(keep);

    await waitFor(() => expect(openButton()).toHaveFocus());
    expect(actions.remove).not.toHaveBeenCalled();
  });

  it('while another action runs the opening button is aria-disabled, still focusable, and a press does nothing', async () => {
    setup({ busy: true });

    expect(openButton()).toHaveAttribute('aria-disabled', 'true');
    expect(openButton()).toBeEnabled();
    openButton().focus();
    await userEvent.click(openButton());

    expect(openButton()).toHaveFocus();
    expect(screen.queryByRole('group', { name: 'Delete this download' })).not.toBeInTheDocument();
  });

  it('while the delete runs the buttons are aria-disabled, the pressed one keeps focus, and after a failure it is still there as "Try again"', async () => {
    let release;
    const gate = new Promise((resolve) => {
      release = resolve;
    });
    const remove = vi.fn().mockReturnValueOnce(gate).mockResolvedValue(true);
    render(<Harness remove={remove} />);
    await userEvent.click(openButton());
    await userEvent.click(screen.getByRole('button', { name: 'Delete download' }));

    const confirm = screen.getByRole('button', { name: 'Delete download' });
    const keep = screen.getByRole('button', { name: 'Keep it' });
    expect(confirm).toHaveAttribute('aria-disabled', 'true');
    expect(keep).toHaveAttribute('aria-disabled', 'true');
    expect(confirm).toHaveFocus();
    await userEvent.click(keep);
    await userEvent.click(confirm);
    expect(remove).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('group', { name: 'Delete this download' })).toBeInTheDocument();

    release(false);

    const retry = await screen.findByRole('button', { name: 'Try again' });
    expect(retry).toBe(confirm);
    expect(retry).toHaveFocus();
    expect(retry).not.toHaveAttribute('aria-disabled');
    await userEvent.click(retry);
    await waitFor(() => expect(remove).toHaveBeenCalledTimes(2));
  });
});
