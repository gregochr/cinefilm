import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { fixRemoveLabels } from '../state/subtitleFixCopy.js';
import RemoveDownload from './RemoveDownload.jsx';

const item = { id: 8 };
const openButton = () => screen.getByRole('button', { name: /Remove the OpenSubtitles/ });

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

describe('RemoveDownload', () => {
  it('uses Admin’s old words by default', async () => {
    setup();

    await userEvent.click(openButton());

    expect(screen.getByRole('group', { name: 'Remove this download' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Remove download' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Keep it' })).toBeInTheDocument();
  });

  it('removes through the action and tells its owner once the download is gone', async () => {
    const { actions, onRemoved } = setup();

    await userEvent.click(openButton());
    await userEvent.click(screen.getByRole('button', { name: 'Remove download' }));

    await waitFor(() => expect(onRemoved).toHaveBeenCalledTimes(1));
    expect(actions.remove).toHaveBeenCalledExactlyOnceWith(8);
  });

  it('does not tell its owner when the removal failed', async () => {
    const { actions, onRemoved } = setup();
    actions.remove.mockResolvedValue(false);

    await userEvent.click(openButton());
    await userEvent.click(screen.getByRole('button', { name: 'Remove download' }));

    await waitFor(() => expect(actions.remove).toHaveBeenCalledTimes(1));
    expect(onRemoved).not.toHaveBeenCalled();
  });

  it('says "Try again" after a failure', async () => {
    setup({ failed: true });

    await userEvent.click(openButton());

    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Remove download' })).not.toBeInTheDocument();
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

  it('disables the opening button while another action runs', () => {
    setup({ busy: true });

    expect(openButton()).toBeDisabled();
  });

  it('takes other words and a hint line under the opening button', () => {
    setup({ labels: fixRemoveLabels, hint: 'Deletes the downloaded file.' });

    const open = screen.getByRole('button', { name: 'Delete this download: Alien.1979' });
    expect(open).toHaveTextContent('Delete this download');
    expect(open).toHaveAccessibleDescription('Deletes the downloaded file.');
  });
});
