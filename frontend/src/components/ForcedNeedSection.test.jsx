import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, respond } from '../test/mockApi.jsx';
import ForcedNeedSection from './ForcedNeedSection.jsx';

afterEach(() => vi.unstubAllGlobals());

const FORCED = /^\/api\/admin\/films\/(\d+)\/forced-need$/;
const LIST = fixtures.adminForcedNeed;

const film = (filmId, over = {}) => ({
  ...LIST.films[0],
  filmId,
  title: `Film ${filmId}`,
  need: 'NEEDED',
  reason: 'FOREIGN_LANGUAGE_FILM',
  ...over,
});

/** A stateful fake of the two routes: a choice changes the film the way the server's rule would. */
function serve({ films = LIST.films, failPut = null } = {}) {
  const state = { films: structuredClone(films), puts: [], lists: 0 };
  const fetchMock = installFetch((method, url, init) => {
    if (url === '/api/admin/forced-subtitles/films') {
      state.lists += 1;
      return respond({ films: state.films, counts: {} });
    }
    const match = FORCED.exec(url);
    if (!match) return null;
    const body = JSON.parse(init.body);
    state.puts.push({ filmId: Number(match[1]), body });
    if (failPut) return failPut();
    const target = state.films.find((f) => f.filmId === Number(match[1]));
    Object.assign(target, {
      ownerChoice: body.choice,
      need: body.choice ?? 'NEEDED',
      reason: body.choice ? 'OWNER' : 'OTHER_LANGUAGES',
    });
    return respond(target);
  });
  return { state, fetchMock };
}

async function open(options, props = {}) {
  const served = serve(options);
  const view = render(<ForcedNeedSection refreshKey="a" {...props} />);
  await screen.findByRole('group', { name: 'Show films' });
  return { ...served, ...view };
}

const chips = () => within(screen.getByRole('group', { name: 'Show films' }));
const chip = (name) => chips().getByRole('button', { name });
const titles = () =>
  screen
    .getAllByRole('group', { name: /^Forced subtitles for / })
    .map((g) => g.getAttribute('aria-label').replace('Forced subtitles for ', ''));

describe('Which films need them: the list', () => {
  it('has a heading and shows only the films that need subtitles and have none yet, first', async () => {
    await open();
    expect(screen.getByRole('heading', { level: 3, name: 'Which films need them' })).toBeVisible();
    expect(chip(/^Needed, none yet/)).toHaveAttribute('aria-pressed', 'true');
    expect(titles()).toEqual(['Taken (2008)', 'Das Boot (1981)']);
  });

  it('shows each chip with its count', async () => {
    await open();
    expect(
      within(screen.getByRole('group', { name: 'Show films' }))
        .getAllByRole('button')
        .map((b) => b.textContent),
    ).toEqual(['Needed, none yet (2)', 'Unsure (1)', 'Unknown (1)', 'Not needed (2)', 'All (7)']);
  });

  it('switches filters and marks the chosen chip pressed', async () => {
    await open();
    await userEvent.click(chip(/^Unsure/));
    expect(chip(/^Unsure/)).toHaveAttribute('aria-pressed', 'true');
    expect(chip(/^Needed, none yet/)).toHaveAttribute('aria-pressed', 'false');
    expect(titles()).toEqual(['Alien (1979)']);
    await userEvent.click(chip(/^Not needed/));
    expect(titles()).toEqual(['Heat (1995)', 'Quiet Film (2001)']);
    await userEvent.click(chip(/^All/));
    expect(titles()).toHaveLength(7);
  });

  it('says when a filter has no films', async () => {
    await open({ films: [LIST.films[0]] });
    expect(screen.getByText('No films here.')).toBeVisible();
  });

  it('words the need, the reason and whether a subtitle plays', async () => {
    await open();
    await userEvent.click(chip(/^All/));
    const item = (name) =>
      screen.getByRole('group', { name: `Forced subtitles for ${name}` }).closest('li');
    const taken = within(item('Taken (2008)'));
    expect(taken.getByText('Needed', { selector: 'span' })).toBeVisible();
    expect(taken.getByText('Also spoken: French, Russian (TMDB)')).toBeVisible();
    expect(taken.getByText('None yet')).toBeVisible();
    const basterds = within(item('Inglourious Basterds (2009)'));
    expect(basterds.getByText('Forced subtitle file attached')).toBeVisible();
    expect(basterds.getByText('Plays')).toBeVisible();
    const heat = within(item('Heat (1995)'));
    expect(heat.getByText('Not needed', { selector: 'span' })).toBeVisible();
    expect(heat.getByText('English only (TMDB)')).toBeVisible();
    expect(heat.queryByText('Plays')).not.toBeInTheDocument();
    expect(heat.queryByText('None yet')).not.toBeInTheDocument();
    expect(within(item('Obscure Film')).getByText("TMDB hasn't been asked yet")).toBeVisible();
    expect(within(item('Quiet Film (2001)')).getByText('Your choice')).toBeVisible();
  });

  it('omits the reason line for a reason it does not know', async () => {
    await open({ films: [film(1, { year: null, reason: 'FROM_THE_FUTURE' })] });
    expect(titles()).toEqual(['Film 1']);
    expect(screen.getByRole('listitem')).not.toHaveTextContent('FROM_THE_FUTURE');
  });

  it('shows 100 films at a time, and Show more adds the next 100; changing the filter starts again at 100', async () => {
    const many = Array.from({ length: 250 }, (_, i) => film(i + 1));
    await open({ films: many });
    expect(titles()).toHaveLength(100);
    await userEvent.click(screen.getByRole('button', { name: 'Show more (150 left)' }));
    expect(titles()).toHaveLength(200);
    await userEvent.click(screen.getByRole('button', { name: 'Show more (50 left)' }));
    expect(titles()).toHaveLength(250);
    expect(screen.queryByRole('button', { name: /Show more/ })).not.toBeInTheDocument();

    await userEvent.click(chip(/^All/));
    expect(titles()).toHaveLength(100);
    await userEvent.click(chip(/^Needed, none yet/));
    expect(titles()).toHaveLength(100);
  });
});

describe('Which films need them: the owner’s choice', () => {
  const group = (name) => screen.getByRole('group', { name: `Forced subtitles for ${name}` });

  it('has Needed, Not needed and Automatic with the film in their names, the current one pressed', async () => {
    await open();
    const taken = within(group('Taken (2008)'));
    expect(taken.getAllByRole('button').map((b) => b.getAttribute('aria-label'))).toEqual([
      'Needed: Taken (2008)',
      'Not needed: Taken (2008)',
      'Automatic: Taken (2008)',
    ]);
    expect(taken.getByRole('button', { name: 'Automatic: Taken (2008)' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(taken.getByRole('button', { name: 'Needed: Taken (2008)' })).toHaveAttribute(
      'aria-pressed',
      'false',
    );
    await userEvent.click(chip(/^Not needed/));
    expect(
      within(group('Quiet Film (2001)')).getByRole('button', {
        name: 'Not needed: Quiet Film (2001)',
      }),
    ).toHaveAttribute('aria-pressed', 'true');
  });

  it('sends Not needed, updates the row and the counts, and does not read the list again', async () => {
    const { state } = await open();
    await userEvent.click(screen.getByRole('button', { name: 'Not needed: Taken (2008)' }));

    await waitFor(() => expect(titles()).toEqual(['Das Boot (1981)']));
    expect(state.puts).toEqual([{ filmId: 2, body: { choice: 'NOT_NEEDED' } }]);
    expect(state.lists).toBe(1);
    expect(chip(/^Needed, none yet/)).toHaveTextContent('(1)');
    expect(chip(/^Not needed/)).toHaveTextContent('(3)');
    expect(chip(/^All/)).toHaveTextContent('(7)');

    await userEvent.click(chip(/^Not needed/));
    const taken = within(group('Taken (2008)'));
    expect(
      within(
        taken.getByRole('button', { name: 'Not needed: Taken (2008)' }).closest('li'),
      ).getByText('Your choice'),
    ).toBeVisible();
    expect(taken.getByRole('button', { name: 'Not needed: Taken (2008)' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
  });

  it('sends Needed for a film that was not needed', async () => {
    const { state } = await open();
    await userEvent.click(chip(/^Not needed/));
    await userEvent.click(screen.getByRole('button', { name: 'Needed: Heat (1995)' }));
    await waitFor(() => expect(chip(/^Not needed/)).toHaveTextContent('(1)'));
    expect(state.puts).toEqual([{ filmId: 1, body: { choice: 'NEEDED' } }]);
    expect(chip(/^Needed, none yet/)).toHaveTextContent('(3)');
  });

  it('sends null for Automatic', async () => {
    const { state } = await open();
    await userEvent.click(chip(/^Not needed/));
    await userEvent.click(screen.getByRole('button', { name: 'Automatic: Quiet Film (2001)' }));
    await waitFor(() => expect(state.puts).toHaveLength(1));
    expect(state.puts[0]).toEqual({ filmId: 7, body: { choice: null } });
    await waitFor(() => expect(chip(/^Needed, none yet/)).toHaveTextContent('(3)'));
  });

  it('keeps the row as it was and says a scan is running when the save is refused with a 409', async () => {
    await open({ failPut: () => respond({ error: 'scan' }, 409) });
    await userEvent.click(screen.getByRole('button', { name: 'Not needed: Taken (2008)' }));
    const li = group('Taken (2008)').closest('li');
    expect(await within(li).findByRole('status')).toHaveTextContent(
      'A scan is running: try again when it finishes',
    );
    expect(screen.getByRole('button', { name: 'Automatic: Taken (2008)' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(titles()).toEqual(['Taken (2008)', 'Das Boot (1981)']);
    expect(chip(/^Needed, none yet/)).toHaveTextContent('(2)');
  });

  it('says it could not save for any other failure', async () => {
    await open({ failPut: () => respond(null, 500) });
    await userEvent.click(screen.getByRole('button', { name: 'Needed: Taken (2008)' }));
    const li = group('Taken (2008)').closest('li');
    expect(await within(li).findByRole('status')).toHaveTextContent("Couldn't save: try again");
    expect(within(group('Das Boot (1981)').closest('li')).queryByRole('status')).toBeNull();
  });
});

describe('Which films need them: loading and failing', () => {
  it('shows a static loading status until the list arrives', async () => {
    installFetch((method, url) =>
      url === '/api/admin/forced-subtitles/films' ? new Promise(() => {}) : null,
    );
    render(<ForcedNeedSection refreshKey="a" />);
    expect(screen.getByRole('status', { name: 'Loading which films need them' })).toBeVisible();
    expect(screen.queryByRole('group', { name: 'Show films' })).not.toBeInTheDocument();
  });

  it('shows an error with Try again when the list cannot load, and loads it on Try again', async () => {
    let fail = true;
    installFetch((method, url) =>
      url === '/api/admin/forced-subtitles/films' && fail ? respond(null, 500) : null,
    );
    render(<ForcedNeedSection refreshKey="a" />);
    expect(await screen.findByText(/Couldn.t load which films need them/)).toBeVisible();
    fail = false;
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('group', { name: 'Show films' })).toBeVisible();
    expect(screen.queryByText(/Couldn.t load which films need them/)).not.toBeInTheDocument();
  });

  it('keeps the old list and shows Try again when a refresh fails, and Try again reads it again', async () => {
    let fail = false;
    installFetch((method, url) =>
      url === '/api/admin/forced-subtitles/films' && fail ? respond(null, 500) : null,
    );
    const { rerender } = render(<ForcedNeedSection refreshKey="a" />);
    await screen.findByRole('group', { name: 'Show films' });

    fail = true;
    rerender(<ForcedNeedSection refreshKey="b" />);
    expect(await screen.findByText(/Couldn.t refresh which films need them/)).toBeVisible();
    expect(titles()).toEqual(['Taken (2008)', 'Das Boot (1981)']);

    fail = false;
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    await waitFor(() =>
      expect(screen.queryByText(/Couldn.t refresh which films need them/)).not.toBeInTheDocument(),
    );
    expect(titles()).toEqual(['Taken (2008)', 'Das Boot (1981)']);
  });

  it('reads the list again when the refresh key changes', async () => {
    const { state, rerender } = await open();
    expect(state.lists).toBe(1);
    rerender(<ForcedNeedSection refreshKey="b" />);
    await waitFor(() => expect(state.lists).toBe(2));
  });
});

describe('Which films need them: OpenSubtitles evidence', () => {
  const item = (name) =>
    screen.getByRole('group', { name: `Forced subtitles for ${name}` }).closest('li');

  it('says how many foreign-parts-only files OpenSubtitles has, one or several', async () => {
    await open({
      films: [
        film(1, {
          year: null,
          reason: 'OPENSUBTITLES',
          openSubtitlesForcedCount: 7,
          openSubtitlesSearchedAt: '2026-11-12T06:00:00Z',
        }),
        film(2, {
          year: null,
          reason: 'OPENSUBTITLES',
          openSubtitlesForcedCount: 1,
          openSubtitlesSearchedAt: '2026-11-12T06:00:00Z',
        }),
      ],
    });
    expect(
      within(item('Film 1')).getByText('OpenSubtitles has 7 foreign-parts-only files'),
    ).toBeVisible();
    expect(
      within(item('Film 2')).getByText('OpenSubtitles has 1 foreign-parts-only file'),
    ).toBeVisible();
  });

  it('notes OpenSubtitles has not been searched for an unsure film, and only then', async () => {
    await open();
    await userEvent.click(chip(/^All/));
    expect(within(item('Alien (1979)')).getByText('OpenSubtitles not searched yet')).toBeVisible();
    expect(within(item('Obscure Film')).getByText('OpenSubtitles not searched yet')).toBeVisible();
    expect(
      within(item('Heat (1995)')).queryByText('OpenSubtitles not searched yet'),
    ).not.toBeInTheDocument();
    expect(
      within(item('Taken (2008)')).queryByText('OpenSubtitles not searched yet'),
    ).not.toBeInTheDocument();
  });
});
