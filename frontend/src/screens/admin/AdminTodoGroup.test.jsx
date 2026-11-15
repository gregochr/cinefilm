import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { buildTodoGroups } from '../../state/adminTodo.js';
import attention from '../../test/fixtures/admin-attention.json';
import attentionMany from '../../test/fixtures/admin-attention-many.json';
import forcedNeed from '../../test/fixtures/admin-forced-need.json';
import fullNeed from '../../test/fixtures/admin-full-need.json';
import AdminTodoGroup from './AdminTodoGroup.jsx';

const groups = (source) => buildTodoGroups(source);
const find = (list, key) => list.find((g) => g.key === key);

function renderGroup(group, props = {}) {
  const handlers = { onConfirm: vi.fn(), onFindFilm: vi.fn(), onSortOut: vi.fn() };
  render(
    <MemoryRouter>
      <AdminTodoGroup group={group} confirming={[]} problems={{}} {...handlers} {...props} />
    </MemoryRouter>,
  );
  return handlers;
}

const film = groups({ attention: attention.items });
const manyFilm = groups({ attention: attentionMany.items });

describe('AdminTodoGroup: Pick the right film', () => {
  it('is a region named by its heading, with the why-line and one row per film', () => {
    renderGroup(find(film, 'pickFilm'));

    const region = screen.getByRole('region', { name: 'Pick the right film' });
    expect(
      within(region).getByText('CineFilm couldn’t tell which film these files are'),
    ).toBeInTheDocument();
    expect(within(region).getAllByRole('listitem')).toHaveLength(2);
  });

  it('offers only "Find the film…" without confidence, and passes the item and the button', async () => {
    const { onFindFilm, onConfirm } = renderGroup(find(film, 'pickFilm'));
    const wicker = screen.getAllByRole('listitem')[0];

    expect(within(wicker).queryByRole('button', { name: /^Yes/ })).not.toBeInTheDocument();
    const button = within(wicker).getByRole('button', { name: 'Find the film…' });
    await userEvent.click(button);

    expect(onFindFilm).toHaveBeenCalledWith(attention.items[0], button);
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it('adds "Yes, it’s {Title} ({year})" when confident, which confirms the item', async () => {
    const { onConfirm, onFindFilm } = renderGroup(find(film, 'pickFilm'));
    const suspiria = screen.getAllByRole('listitem')[1];

    await userEvent.click(
      within(suspiria).getByRole('button', { name: 'Yes, it’s Suspiria (1977)' }),
    );

    expect(onConfirm).toHaveBeenCalledWith(attention.items[5]);
    expect(onFindFilm).not.toHaveBeenCalled();
    expect(within(suspiria).getByRole('button', { name: 'Find the film…' })).toBeInTheDocument();
  });

  it('keeps "Yes" an ordinary button and marks it aria-disabled, not disabled, while busy', () => {
    renderGroup(find(film, 'pickFilm'), { confirming: [34] });

    const yes = screen.getByRole('button', { name: 'Yes, it’s Suspiria (1977)' });
    expect(yes).toHaveAttribute('aria-disabled', 'true');
    expect(yes).toBeEnabled();
    expect(yes).not.toHaveClass('btn--primary');
  });

  it('shows a confident item with no candidate as "Find the film…" alone', () => {
    const odd = { ...attention.items[5], candidates: [] };
    renderGroup(find(groups({ attention: [odd] }), 'pickFilm'));

    expect(screen.queryByRole('button', { name: /^Yes/ })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Find the film…' })).toBeInTheDocument();
  });

  it('shows the problem for a film inside its own row', () => {
    renderGroup(find(film, 'pickFilm'), { problems: { 34: 'Something went wrong. Try again.' } });

    const [wicker, suspiria] = screen.getAllByRole('listitem');
    expect(within(suspiria).getByRole('alert')).toHaveTextContent(
      'Something went wrong. Try again.',
    );
    expect(within(wicker).queryByRole('alert')).not.toBeInTheDocument();
  });

  it('has no footer at two rows', () => {
    renderGroup(find(film, 'pickFilm'));

    expect(screen.queryByText(/^Showing/)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Show/ })).not.toBeInTheDocument();
  });

  it('cuts at five and expands in place with Show all, then folds back with Show fewer', async () => {
    renderGroup(find(manyFilm, 'pickFilm'));
    expect(screen.getAllByRole('listitem')).toHaveLength(5);
    // The phone footer says "5 of 7"; CSS shows one of the two.
    expect(screen.getByText('Showing 5 of 7')).toBeInTheDocument();
    expect(screen.getByText('5 of 7')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Show all 7' }));

    expect(screen.getAllByRole('listitem')).toHaveLength(7);
    expect(screen.getByText('Showing 7 of 7')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Show fewer' })).toHaveFocus();

    await userEvent.click(screen.getByRole('button', { name: 'Show fewer' }));

    expect(screen.getAllByRole('listitem')).toHaveLength(5);
    expect(screen.getByRole('button', { name: 'Show all 7' })).toHaveFocus();
  });
});

describe('AdminTodoGroup: Sort out several files', () => {
  it('shows the suggestion line and "Sort out editions…" for several-files and parts-mismatch rows', async () => {
    const { onSortOut } = renderGroup(find(film, 'severalFiles'));

    const region = screen.getByRole('region', { name: 'Sort out several files' });
    expect(
      within(region).getByText('2 files · 2 untagged versions of one film, runtimes differ by 22%'),
    ).toBeInTheDocument();
    expect(within(region).getByText('The Two Towers (2002)')).toBeInTheDocument();
    const buttons = within(region).getAllByRole('button', { name: 'Sort out editions…' });
    expect(buttons[1]).toHaveAccessibleDescription(
      'Choose which file is which cut, the part order and the default.',
    );
    await userEvent.click(buttons[1]);

    expect(onSortOut).toHaveBeenCalledWith(attention.items[4], buttons[1]);
  });

  it('expands at six rows, like the match group', () => {
    renderGroup(find(manyFilm, 'severalFiles'));

    expect(screen.getAllByRole('listitem')).toHaveLength(5);
    expect(screen.getByRole('button', { name: 'Show all 6' })).toBeInTheDocument();
  });
});

describe('AdminTodoGroup: the subtitle groups', () => {
  const subtitles = groups({ attention: [], full: fullNeed.films, forced: forcedNeed.films });

  it('lists the English-subtitles rows read-only, with a footer link to Subtitles and no Show all', () => {
    renderGroup(find(subtitles, 'fullMissing'));

    const region = screen.getByRole('region', { name: 'English subtitles missing' });
    expect(
      within(region).getByText('Foreign-language films with nothing to read'),
    ).toBeInTheDocument();
    expect(within(region).getByText('Rashomon (1950)')).toBeInTheDocument();
    expect(within(region).queryByRole('button')).not.toBeInTheDocument();
    expect(within(region).getByText('Showing 1 of 1')).toBeInTheDocument();
    expect(within(region).getByRole('link', { name: 'All in Subtitles →' })).toHaveAttribute(
      'href',
      '/admin/subtitles',
    );
  });

  it('lists the forced rows with their reason and the same footer link', () => {
    renderGroup(find(subtitles, 'forcedQuestion'));

    const region = screen.getByRole('region', { name: 'Forced subtitles not found' });
    expect(within(region).getByText('Taken (2008)')).toBeInTheDocument();
    expect(
      within(region).getByText('OpenSubtitles has 2 foreign-parts-only files'),
    ).toBeInTheDocument();
    expect(within(region).getByRole('link', { name: 'All in Subtitles →' })).toBeInTheDocument();
  });
});
