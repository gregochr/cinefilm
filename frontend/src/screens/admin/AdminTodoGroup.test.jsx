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
  const handlers = {
    onConfirm: vi.fn(),
    onFindFilm: vi.fn(),
    onLeave: vi.fn(),
    onSortOut: vi.fn(),
    onFindSubtitles: vi.fn(),
    onChooseFull: vi.fn(),
    onAttachedFull: vi.fn(),
    fullSaving: {},
    fullProblems: {},
  };
  const view = render(
    <MemoryRouter>
      <AdminTodoGroup group={group} confirming={[]} problems={{}} {...handlers} {...props} />
    </MemoryRouter>,
  );
  const rerender = (next) =>
    view.rerender(
      <MemoryRouter>
        <AdminTodoGroup group={next} confirming={[]} problems={{}} {...handlers} {...props} />
      </MemoryRouter>,
    );
  return { ...handlers, rerender };
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

    expect(onConfirm).toHaveBeenCalledWith(
      attention.items[5],
      within(suspiria).getByRole('button', { name: 'Yes, it’s Suspiria (1977)' }),
    );
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

  it('tells a screen reader whether the list is expanded, and which list the button controls', async () => {
    renderGroup(find(manyFilm, 'pickFilm'));
    const showAll = screen.getByRole('button', { name: 'Show all 7' });
    expect(showAll).toHaveAttribute('aria-expanded', 'false');
    expect(showAll).toHaveAttribute('aria-controls', screen.getByRole('list').id);

    await userEvent.click(showAll);

    const fewer = screen.getByRole('button', { name: 'Show fewer' });
    expect(fewer).toHaveAttribute('aria-expanded', 'true');
    expect(fewer).toHaveAttribute('aria-controls', screen.getByRole('list').id);
  });
});

describe('AdminTodoGroup: Leave unmatched', () => {
  it('is on every match row, after "Find the film…", with its hint shown', () => {
    renderGroup(find(film, 'pickFilm'));

    for (const row of screen.getAllByRole('listitem')) {
      const buttons = within(row).getAllByRole('button');
      expect(buttons.at(-1)).toHaveAccessibleName('Leave unmatched');
      expect(buttons.at(-2)).toHaveAccessibleName('Find the film…');
      expect(
        within(row).getByText(
          'Stop listing this film here. It stays in your library, and you can still match it later from Fix match.',
        ),
      ).toBeVisible();
    }
  });

  it('passes the item and the button to onLeave', async () => {
    const { onLeave, onFindFilm } = renderGroup(find(film, 'pickFilm'));
    const suspiria = screen.getAllByRole('listitem')[1];
    const button = within(suspiria).getByRole('button', { name: 'Leave unmatched' });

    await userEvent.click(button);

    expect(onLeave).toHaveBeenCalledWith(
      attention.items.find((i) => i.filmId === 34),
      button,
    );
    expect(onFindFilm).not.toHaveBeenCalled();
  });

  it('is described by the hint and the row title, and is quiet like "Find the film…"', () => {
    renderGroup(find(film, 'pickFilm'));
    const suspiria = screen.getAllByRole('listitem')[1];
    const button = within(suspiria).getByRole('button', { name: 'Leave unmatched' });

    expect(button).toHaveAccessibleDescription(/Stop listing this film here.*Suspiria/s);
    expect(button).toHaveClass('admin__todo-quiet');
  });

  it('marks the button aria-disabled while the row is busy and ignores a click then', async () => {
    const { onLeave } = renderGroup(find(film, 'pickFilm'), { confirming: [34] });
    const [wicker, suspiria] = screen.getAllByRole('listitem');
    const button = within(suspiria).getByRole('button', { name: 'Leave unmatched' });

    expect(button).toHaveAttribute('aria-disabled', 'true');
    await userEvent.click(button);

    expect(onLeave).not.toHaveBeenCalled();
    expect(within(wicker).getByRole('button', { name: 'Leave unmatched' })).not.toHaveAttribute(
      'aria-disabled',
    );
  });
});

describe('AdminTodoGroup: buttons name their film', () => {
  it('describes each match and editions button with the row title, keeping the label as the name', () => {
    renderGroup(find(film, 'pickFilm'));
    const [wicker] = screen.getAllByRole('listitem');

    expect(
      within(wicker).getByRole('button', { name: 'Find the film…' }),
    ).toHaveAccessibleDescription(/The Wicker Man/);
  });

  it('names the film on "Yes, it\u2019s …" and its quiet "Find the film…" too', () => {
    renderGroup(find(film, 'pickFilm'));
    const suspiria = screen.getAllByRole('listitem')[1];

    expect(within(suspiria).getByRole('button', { name: /^Yes, it/ })).toHaveAccessibleDescription(
      /Suspiria/,
    );
    expect(
      within(suspiria).getByRole('button', { name: 'Find the film…' }),
    ).toHaveAccessibleDescription(/Suspiria/);
  });

  it('names the film on "Sort out editions…" and on the subtitle search buttons, with the hint kept', () => {
    renderGroup(find(film, 'severalFiles'));
    const button = screen.getAllByRole('button', { name: 'Sort out editions…' })[1];
    expect(button).toHaveAccessibleDescription(/Choose which file is which cut.*The Two Towers/);
  });
});

describe('AdminTodoGroup: titles, counts and busy rows', () => {
  it('mutes the year after the title, and marks a year that is only the best guess with a question mark', () => {
    renderGroup(find(film, 'pickFilm'));

    const [wicker, suspiria] = screen.getAllByRole('listitem');
    // The Wicker Man has no year of its own; its best guess (1973) is shown as a guess.
    expect(within(wicker).getByText('· 1973?')).toBeInTheDocument();
    expect(within(suspiria).getByText('· 1977')).toBeInTheDocument();
    expect(within(suspiria).queryByText(/\?/)).not.toBeInTheDocument();
  });

  it('shows no year when there is none to show', () => {
    const bare = { ...attention.items[0], candidates: [] };
    renderGroup(find(groups({ attention: [bare] }), 'pickFilm'));

    expect(screen.getByText('The Wicker Man')).toBeInTheDocument();
    expect(screen.queryByText(/^·/)).not.toBeInTheDocument();
  });

  it('carries a count for the phone header beside the why-line: files for the match group, films for the rest', () => {
    renderGroup(find(manyFilm, 'pickFilm'));
    expect(screen.getByText('7 files')).toBeInTheDocument();
  });

  it('counts films in the other groups, singular at one', () => {
    renderGroup(find(groups({ attention: [], full: fullNeed.films }), 'fullMissing'));
    expect(screen.getByText('1 film')).toBeInTheDocument();
  });

  it('marks both buttons of a busy row aria-disabled and ignores a click on Find the film…', async () => {
    const { onFindFilm } = renderGroup(find(film, 'pickFilm'), { confirming: [34] });
    const suspiria = screen.getAllByRole('listitem')[1];
    const findButton = within(suspiria).getByRole('button', { name: 'Find the film…' });

    expect(findButton).toHaveAttribute('aria-disabled', 'true');
    await userEvent.click(findButton);

    expect(onFindFilm).not.toHaveBeenCalled();
    // The other row is not busy.
    expect(
      within(screen.getAllByRole('listitem')[0]).getByRole('button', { name: 'Find the film…' }),
    ).not.toHaveAttribute('aria-disabled');
  });

  it('starts folded again after a group shrank to the cap while expanded', async () => {
    const view = renderGroup(find(manyFilm, 'pickFilm'));
    await userEvent.click(screen.getByRole('button', { name: 'Show all 7' }));
    expect(screen.getAllByRole('listitem')).toHaveLength(7);
    const five = {
      ...find(manyFilm, 'pickFilm'),
      rows: find(manyFilm, 'pickFilm').rows.slice(0, 5),
      total: 5,
    };

    view.rerender(five);
    expect(screen.queryByRole('button', { name: /^Show/ })).not.toBeInTheDocument();
    view.rerender(find(manyFilm, 'pickFilm'));

    expect(screen.getAllByRole('listitem')).toHaveLength(5);
    expect(screen.getByRole('button', { name: 'Show all 7' })).toBeInTheDocument();
  });
});

describe('AdminTodoGroup: Sort out several files', () => {
  it('shows the suggestion line and "Sort out editions…" for several-files and parts-mismatch rows', async () => {
    const { onSortOut } = renderGroup(find(film, 'severalFiles'));

    const region = screen.getByRole('region', { name: 'Sort out several files' });
    expect(
      within(region).getByText('2 files · 2 untagged versions of one film, runtimes differ by 22%'),
    ).toBeInTheDocument();
    expect(within(region).getByText('The Two Towers')).toBeInTheDocument();
    const buttons = within(region).getAllByRole('button', { name: 'Sort out editions…' });
    expect(buttons[1]).toHaveAccessibleDescription(
      /^Choose which file is which cut, the part order and the default\. The Two Towers/,
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

  it('lists the English-subtitles rows with their search button, a footer link to Subtitles and no Show all', () => {
    renderGroup(find(subtitles, 'fullMissing'));

    const region = screen.getByRole('region', { name: 'English subtitles missing' });
    expect(
      within(region).getByText('Foreign-language films with nothing to read'),
    ).toBeInTheDocument();
    expect(within(region).getByText('Rashomon')).toBeInTheDocument();
    expect(
      within(region)
        .getAllByRole('button')
        .map((b) => b.textContent),
    ).toEqual(['Search for English subtitles…', 'Add my own file…', 'No English subtitles exist']);
    expect(within(region).queryByRole('button', { name: /Show all/ })).not.toBeInTheDocument();
    expect(within(region).getByText('Showing 1 of 1')).toBeInTheDocument();
    expect(within(region).getByRole('link', { name: 'All in Subtitles →' })).toHaveAttribute(
      'href',
      '/admin/subtitles',
    );
  });

  it('saves a full row’s owner choice through onChooseFull with the row and the choice', async () => {
    const { onChooseFull } = renderGroup(find(subtitles, 'fullMissing'));

    await userEvent.click(screen.getByRole('button', { name: 'No English subtitles exist' }));

    expect(onChooseFull).toHaveBeenCalledWith(fullNeed.films[3], 'NONE_EXIST');
  });

  it('offers "It’s English" only for unlabelled audio, as the Subtitles tab does', () => {
    const unlabelled = { ...structuredClone(fullNeed.films[3]), reason: 'ORIGINAL_AUDIO_ASSUMED' };
    renderGroup(find(groups({ attention: [], full: [unlabelled] }), 'fullMissing'));

    expect(screen.getByRole('button', { name: 'It’s English' })).toBeInTheDocument();
  });

  it('dims a full row that is saving and shows its failure', () => {
    renderGroup(find(subtitles, 'fullMissing'), {
      fullSaving: { 6: true },
      fullProblems: { 6: 'Couldn’t save: try again' },
    });

    expect(screen.getByRole('button', { name: 'No English subtitles exist' })).toHaveAttribute(
      'aria-disabled',
      'true',
    );
    expect(screen.getByRole('alert')).toHaveTextContent('Couldn’t save: try again');
  });

  it('opens the add-a-file form from a full row', async () => {
    renderGroup(find(subtitles, 'fullMissing'));
    await userEvent.click(screen.getByRole('button', { name: 'Add my own file…' }));

    expect(screen.getByRole('button', { name: 'Add my own file…' })).toHaveAttribute(
      'aria-expanded',
      'true',
    );
  });

  it('names the film on a subtitle row\u2019s search button', () => {
    renderGroup(find(subtitles, 'forcedQuestion'));
    expect(
      screen.getByRole('button', { name: 'Find forced subtitles…' }),
    ).toHaveAccessibleDescription(/Taken/);
  });

  it('lists the forced rows with their reason, their search button and a footer link that opens the forced tab', () => {
    renderGroup(find(subtitles, 'forcedQuestion'));

    const region = screen.getByRole('region', { name: 'Forced subtitles not found' });
    expect(within(region).getByText('Taken')).toBeInTheDocument();
    expect(
      within(region).getByText('OpenSubtitles has 2 foreign-parts-only files'),
    ).toBeInTheDocument();
    expect(
      within(region)
        .getAllByRole('button')
        .map((b) => b.textContent),
    ).toEqual(['Find forced subtitles…']);
    expect(within(region).getByRole('link', { name: 'All in Subtitles →' })).toHaveAttribute(
      'href',
      '/admin/subtitles#forced',
    );
  });

  it('opens the picker from a full row with the row, the kind and the button', async () => {
    const { onFindSubtitles } = renderGroup(find(subtitles, 'fullMissing'));
    const button = screen.getByRole('button', { name: 'Search for English subtitles…' });

    await userEvent.click(button);

    expect(onFindSubtitles).toHaveBeenCalledWith(fullNeed.films[3], 'full', button);
  });

  it('opens the picker from a forced row as a forced search', async () => {
    const { onFindSubtitles } = renderGroup(find(subtitles, 'forcedQuestion'));
    const button = screen.getByRole('button', { name: 'Find forced subtitles…' });

    await userEvent.click(button);

    expect(onFindSubtitles).toHaveBeenCalledWith(forcedNeed.films[1], 'forced', button);
  });

  it('gives a row with no edition no button', () => {
    const rows = structuredClone(forcedNeed.films);
    rows[1].editionId = null;
    renderGroup(find(groups({ attention: [], full: [], forced: rows }), 'forcedQuestion'));

    expect(screen.queryByRole('button')).not.toBeInTheDocument();
    expect(screen.getByText('Taken')).toBeInTheDocument();
  });
});
