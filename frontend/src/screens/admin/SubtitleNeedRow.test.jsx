import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { installFetch, respond } from '../../test/mockApi.jsx';
import { forcedRow, fullRow, unlabelledRow } from '../../test/needRoutes.js';
import SubtitleNeedRow from './SubtitleNeedRow.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
});

function renderRow(props) {
  const handlers = { onChoose: vi.fn(), onAttached: vi.fn() };
  render(
    <ul>
      <SubtitleNeedRow saving={false} problem={undefined} {...handlers} {...props} />
    </ul>,
  );
  return handlers;
}

describe('SubtitleNeedRow: a foreign-language film', () => {
  it('offers "Add my own file…" for a film with labelled audio, and nothing for the picker', () => {
    renderRow({ row: fullRow(), kind: 'foreign' });

    expect(screen.getByRole('button', { name: 'Add my own file…' })).toHaveAccessibleDescription(
      'Picks which film (and part) a subtitle file belongs to.',
    );
    expect(screen.queryByRole('button', { name: 'It’s English' })).not.toBeInTheDocument();
    expect(screen.getAllByRole('button')).toHaveLength(1);
  });

  it('offers "It’s English" for unlabelled audio and saves it through onChoose', async () => {
    const row = unlabelledRow();
    const { onChoose } = renderRow({ row, kind: 'foreign' });

    await userEvent.click(screen.getByRole('button', { name: 'It’s English' }));

    expect(onChoose).toHaveBeenCalledWith(row);
  });

  it('ignores a click on "It’s English" while the choice is saving, and keeps the button focusable', async () => {
    const { onChoose } = renderRow({ row: unlabelledRow(), kind: 'foreign', saving: true });
    const button = screen.getByRole('button', { name: 'It’s English' });

    expect(button).toHaveAttribute('aria-disabled', 'true');
    expect(button).not.toBeDisabled();
    await userEvent.click(button);

    expect(onChoose).not.toHaveBeenCalled();
  });

  it('shows what a failed save says, as an alert in the row', () => {
    renderRow({ row: unlabelledRow(), kind: 'foreign', problem: 'Couldn’t save: try again' });

    expect(screen.getByRole('alert')).toHaveTextContent('Couldn’t save: try again');
  });

  it('opens and closes the add-a-file form, and Cancel returns focus to "Add my own file…"', async () => {
    installFetch((m, url) => (url === '/api/films/6' ? respond({ id: 6, editions: [] }) : null));
    renderRow({ row: fullRow(), kind: 'foreign' });
    const add = screen.getByRole('button', { name: 'Add my own file…' });
    expect(add).toHaveAttribute('aria-expanded', 'false');

    await userEvent.click(add);
    const form = await screen.findByRole('form', {
      name: 'Add a subtitle file for Rashomon (1950)',
    });
    expect(add).toHaveAttribute('aria-expanded', 'true');

    await userEvent.click(within(form).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('form')).not.toBeInTheDocument();
    expect(add).toHaveFocus();
  });
});

describe('SubtitleNeedRow: an English film with foreign scenes', () => {
  it('offers only "No foreign scenes" and saves it through onChoose', async () => {
    const row = forcedRow();
    const { onChoose } = renderRow({ row, kind: 'forced' });

    expect(screen.getByText('OpenSubtitles has 2 foreign-parts-only files')).toBeInTheDocument();
    expect(screen.getAllByRole('button')).toHaveLength(1);
    await userEvent.click(screen.getByRole('button', { name: 'No foreign scenes' }));

    expect(onChoose).toHaveBeenCalledWith(row);
  });

  it('does nothing while saving', async () => {
    const { onChoose } = renderRow({ row: forcedRow(), kind: 'forced', saving: true });

    await userEvent.click(screen.getByRole('button', { name: 'No foreign scenes' }));

    expect(onChoose).not.toHaveBeenCalled();
  });
});
