import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, respond } from '../../test/mockApi.jsx';
import { forcedRow, fullRow, unlabelledRow } from '../../test/needRoutes.js';
import SubtitleNeedRow from './SubtitleNeedRow.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
});

function renderRow(props) {
  const handlers = { onChoose: vi.fn(), onAttached: vi.fn(), onFind: vi.fn() };
  render(
    <ul>
      <SubtitleNeedRow saving={false} problem={undefined} {...handlers} {...props} />
    </ul>,
  );
  return handlers;
}

describe('SubtitleNeedRow: a foreign-language film', () => {
  it('offers the search and "Add my own file…" for a film with labelled audio, in that order', () => {
    renderRow({ row: fullRow(), kind: 'foreign' });

    expect(screen.getAllByRole('button').map((b) => b.textContent)).toEqual([
      'Search for English subtitles…',
      'Add my own file…',
    ]);
    expect(screen.getByRole('button', { name: 'Add my own file…' })).toHaveAccessibleDescription(
      'Picks one of your subtitle files for this film',
    );
    expect(
      screen.getByRole('button', { name: 'Search for English subtitles…' }),
    ).toHaveAccessibleDescription('Shows OpenSubtitles results for this film so you can pick one');
    expect(screen.getByRole('button', { name: 'Search for English subtitles…' })).toHaveAttribute(
      'aria-haspopup',
      'dialog',
    );
    expect(screen.queryByRole('button', { name: 'It’s English' })).not.toBeInTheDocument();
  });

  it('opens the picker through onFind with the row and the button that was clicked', async () => {
    const row = fullRow();
    const { onFind } = renderRow({ row, kind: 'foreign' });
    const search = screen.getByRole('button', { name: 'Search for English subtitles…' });

    await userEvent.click(search);

    expect(onFind).toHaveBeenCalledTimes(1);
    expect(onFind).toHaveBeenCalledWith(row, search);
  });

  it('ignores a click on the search while the row’s choice is saving', async () => {
    const { onFind } = renderRow({ row: fullRow(), kind: 'foreign', saving: true });
    const search = screen.getByRole('button', { name: 'Search for English subtitles…' });

    await userEvent.click(search);

    expect(search).toHaveAttribute('aria-disabled', 'true');
    expect(onFind).not.toHaveBeenCalled();
  });

  it('offers no search for a film with no edition to search', () => {
    renderRow({ row: fullRow({ editionId: null }), kind: 'foreign' });

    expect(screen.getAllByRole('button').map((b) => b.textContent)).toEqual(['Add my own file…']);
  });

  it('names the language on the search for unlabelled audio and has no plain search', () => {
    renderRow({ row: unlabelledRow(), kind: 'foreign' });

    expect(screen.getAllByRole('button').map((b) => b.textContent)).toEqual([
      'It’s in German — find subtitles',
      'Add my own file…',
      'It’s English',
    ]);
  });

  it('falls back to the plain search for unlabelled audio when TMDB has no language', () => {
    renderRow({ row: unlabelledRow({ originalLanguage: null }), kind: 'foreign' });

    expect(
      screen.getByRole('button', { name: 'Search for English subtitles…' }),
    ).toBeInTheDocument();
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

  it('points "Add my own file…" at the form while it is open, and at nothing while it is closed', async () => {
    installFetch((m, url) => (url === '/api/films/6' ? respond({ id: 6, editions: [] }) : null));
    renderRow({ row: fullRow(), kind: 'foreign' });
    const add = screen.getByRole('button', { name: 'Add my own file…' });
    expect(add).not.toHaveAttribute('aria-controls');

    await userEvent.click(add);
    const form = await screen.findByRole('form', {
      name: 'Add a subtitle file for Rashomon (1950)',
    });

    expect(add).toHaveAttribute('aria-controls', form.id);
    expect(form.id).not.toBe('');
  });

  it('keeps the form open and the toggle disabled while an attach is in flight, so only one request is sent', async () => {
    let release;
    const held = new Promise((resolve) => {
      release = resolve;
    });
    const file = {
      ...fixtures.adminSubtitles[0],
      id: 21,
      kind: 'FULL',
      filmId: null,
      state: 'unmatched',
    };
    const attaches = [];
    installFetch((method, url) => {
      if (url === '/api/admin/subtitles') return respond([file]);
      if (url === '/api/films/6')
        return respond({ id: 6, editions: [{ id: 60, name: 'Original', parts: 1 }] });
      if (method === 'POST' && url === '/api/admin/subtitles/21/attach') {
        attaches.push(url);
        return held.then(() => respond(file));
      }
      return null;
    });
    const { onAttached } = renderRow({ row: fullRow(), kind: 'foreign' });
    const add = screen.getByRole('button', { name: 'Add my own file…' });
    await userEvent.click(add);
    await userEvent.click(await screen.findByRole('radio', { name: /\.srt/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Attach' }));
    await screen.findByRole('button', { name: 'Attaching…' });

    expect(add).toHaveAttribute('aria-disabled', 'true');
    await userEvent.click(add);
    expect(add).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByRole('form')).toBeInTheDocument();
    expect(attaches).toHaveLength(1);

    release();
    await waitFor(() => expect(onAttached).toHaveBeenCalledTimes(1));
    expect(attaches).toHaveLength(1);
  });
});

describe('SubtitleNeedRow: an English film with foreign scenes', () => {
  it('offers "Find forced subtitles…" and "No foreign scenes", and saves the second through onChoose', async () => {
    const row = forcedRow();
    const { onChoose, onFind } = renderRow({ row, kind: 'forced' });

    expect(screen.getByText('OpenSubtitles has 2 foreign-parts-only files')).toBeInTheDocument();
    expect(screen.getAllByRole('button').map((b) => b.textContent)).toEqual([
      'Find forced subtitles…',
      'No foreign scenes',
    ]);
    await userEvent.click(screen.getByRole('button', { name: 'No foreign scenes' }));

    expect(onChoose).toHaveBeenCalledWith(row);
    expect(onFind).not.toHaveBeenCalled();
  });

  it('opens the picker through onFind, without saving a choice first', async () => {
    const row = forcedRow();
    const { onChoose, onFind } = renderRow({ row, kind: 'forced' });
    const find = screen.getByRole('button', { name: 'Find forced subtitles…' });

    await userEvent.click(find);

    expect(onFind).toHaveBeenCalledWith(row, find);
    expect(onChoose).not.toHaveBeenCalled();
  });

  it('offers only "No foreign scenes" when the film has no edition to search', () => {
    renderRow({ row: forcedRow({ editionId: null }), kind: 'forced' });

    expect(screen.getAllByRole('button').map((b) => b.textContent)).toEqual(['No foreign scenes']);
  });

  it('does nothing while saving', async () => {
    const { onChoose, onFind } = renderRow({ row: forcedRow(), kind: 'forced', saving: true });

    await userEvent.click(screen.getByRole('button', { name: 'No foreign scenes' }));
    await userEvent.click(screen.getByRole('button', { name: 'Find forced subtitles…' }));

    expect(onChoose).not.toHaveBeenCalled();
    expect(onFind).not.toHaveBeenCalled();
  });
});
