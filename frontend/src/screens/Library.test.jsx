import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';
import { installStorage } from '../test/storage.js';

let storage;
beforeEach(() => {
  storage = installStorage();
});
afterEach(() => vi.unstubAllGlobals());

const filmsUrls = (fetchMock) =>
  fetchMock.mock.calls.map((c) => c[0]).filter((u) => u.startsWith('/api/films?'));
const lastFilmsUrl = (fetchMock) => filmsUrls(fetchMock).at(-1);

describe('Library filters', () => {
  it('lists every film with a count and the default sort', async () => {
    installFetch();
    renderApp('/library');
    expect(
      await screen.findByText(`${fixtures.films.total} films · sorted by recently added`),
    ).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Heat, 1995' })).toBeInTheDocument();
  });

  it('the genre and decade pickers offer the facets from the API', async () => {
    installFetch();
    renderApp('/library');
    await screen.findByText(/sorted by/);
    const genre = screen.getByLabelText('Genre');
    expect(
      within(genre)
        .getAllByRole('option')
        .map((o) => o.textContent),
    ).toEqual(['All', 'Crime', 'Drama', 'Sci-fi', 'Thriller']);
    const decade = screen.getByLabelText('Decade');
    expect(within(decade).getByRole('option', { name: '1970s' })).toHaveValue('1970');
    expect(screen.queryByLabelText('Collection')).not.toBeInTheDocument();
  });

  it('choosing a genre and a decade refetches with each and keeps them in the URL', async () => {
    const fetchMock = installFetch();
    renderApp('/library');
    await screen.findByText(/sorted by/);
    await userEvent.selectOptions(screen.getByLabelText('Genre'), 'Sci-fi');
    await waitFor(() => expect(lastFilmsUrl(fetchMock)).toContain('genre=Sci-fi'));
    await userEvent.selectOptions(screen.getByLabelText('Decade'), '1970s');
    await waitFor(() => expect(lastFilmsUrl(fetchMock)).toContain('decade=1970'));
    expect(lastFilmsUrl(fetchMock)).toContain('genre=Sci-fi');
    expect(lastFilmsUrl(fetchMock)).toContain('decade=1970');
  });

  it('choosing All removes a filter again', async () => {
    const fetchMock = installFetch();
    renderApp('/library?genre=Crime');
    await screen.findByText(/sorted by/);
    expect(screen.getByLabelText('Genre')).toHaveDisplayValue('Crime');
    await userEvent.selectOptions(screen.getByLabelText('Genre'), 'All');
    await waitFor(() => expect(lastFilmsUrl(fetchMock)).not.toContain('genre='));
  });

  it('Show: Watched and Unwatched use aria-pressed and refetch; All clears it', async () => {
    const fetchMock = installFetch();
    renderApp('/library');
    await screen.findByText(/sorted by/);
    const group = screen.getByRole('group', { name: 'Show' });
    expect(within(group).getByRole('button', { name: 'All' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    await userEvent.click(within(group).getByRole('button', { name: 'Unwatched' }));
    expect(within(group).getByRole('button', { name: 'Unwatched' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    await waitFor(() => expect(lastFilmsUrl(fetchMock)).toContain('show=unwatched'));
    await userEvent.click(within(group).getByRole('button', { name: 'Watched' }));
    await waitFor(() => expect(lastFilmsUrl(fetchMock)).toContain('show=watched'));
    await userEvent.click(within(group).getByRole('button', { name: 'All' }));
    await waitFor(() => expect(lastFilmsUrl(fetchMock)).not.toContain('show='));
  });

  it('Sort: choosing Recently added returns to the default sort', async () => {
    const fetchMock = installFetch();
    renderApp('/library?sort=year');
    await screen.findByText(/sorted by year/);
    await userEvent.click(screen.getByRole('button', { name: 'Recently added' }));
    await screen.findByText(/sorted by recently added/);
    expect(lastFilmsUrl(fetchMock)).toBe('/api/films?sort=added');
    expect(screen.getByRole('button', { name: 'Recently added' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
  });

  it('an unknown sort in the URL is described as recently added', async () => {
    installFetch();
    renderApp('/library?sort=bogus');
    expect(await screen.findByText(/sorted by recently added/)).toBeInTheDocument();
  });
});

describe('Library search', () => {
  it('starts from the q in the URL', async () => {
    installFetch();
    renderApp('/library?q=heat');
    expect(await screen.findByText('1 film · sorted by recently added')).toBeInTheDocument();
    expect(screen.getByRole('searchbox', { name: /Search/ })).toHaveValue('heat');
  });

  it('Escape empties a filled search box', async () => {
    installFetch();
    renderApp('/library?q=heat');
    const box = await screen.findByRole('searchbox', { name: /Search/ });
    box.focus();
    await userEvent.keyboard('{Escape}');
    expect(box).toHaveValue('');
    await screen.findByText(`${fixtures.films.total} films · sorted by recently added`);
  });

  it('Escape in an already empty box does nothing', async () => {
    installFetch();
    renderApp('/library');
    const box = await screen.findByRole('searchbox', { name: /Search/ });
    box.focus();
    await userEvent.keyboard('{Escape}');
    expect(box).toHaveValue('');
    expect(box).toHaveFocus();
  });

  it('trims the typed text before it is sent', async () => {
    const fetchMock = installFetch();
    renderApp('/library');
    await screen.findByText(/sorted by/);
    await userEvent.type(screen.getByRole('searchbox', { name: /Search/ }), ' heat ');
    await waitFor(() => expect(lastFilmsUrl(fetchMock)).toBe('/api/films?q=heat&sort=added'));
  });

  it('typing only spaces sends no search', async () => {
    const fetchMock = installFetch();
    renderApp('/library?q=heat');
    const box = await screen.findByRole('searchbox', { name: /Search/ });
    await userEvent.clear(box);
    await userEvent.type(box, '   ');
    await waitFor(() => expect(lastFilmsUrl(fetchMock)).not.toContain('q='));
  });

  it('is not focused otherwise', async () => {
    installFetch();
    renderApp('/library');
    expect(await screen.findByRole('searchbox', { name: /Search/ })).not.toHaveFocus();
  });
});

describe('Library states', () => {
  it('says "1 film" in the singular', async () => {
    installFetch();
    renderApp('/library?q=heat');
    expect(await screen.findByText('1 film · sorted by recently added')).toBeInTheDocument();
  });

  it('shows the empty-library state when there are no films and no filters', async () => {
    installFetch((m, url) => (url.startsWith('/api/films?') ? respond(fixtures.filmsEmpty) : null));
    renderApp('/library');
    expect(await screen.findByText('The house lights are up')).toBeInTheDocument();
    expect(screen.queryByText('Nothing on the shelf')).not.toBeInTheDocument();
  });

  it('shows no-results, naming the query, when filters leave nothing', async () => {
    installFetch((m, url) => (url.startsWith('/api/films?') ? respond(fixtures.filmsEmpty) : null));
    renderApp('/library?genre=Horror');
    expect(await screen.findByText('Nothing on the shelf')).toBeInTheDocument();
    expect(screen.getByText(/^No films match with these filters/)).toBeInTheDocument();
  });

  it('shows a Loading status on the first load', () => {
    installFetch(() => new Promise(() => {}));
    renderApp('/library');
    expect(screen.getByRole('status', { name: 'Loading' })).toBeInTheDocument();
  });

  it('shows the error state and retries', async () => {
    let fail = true;
    installFetch((m, url) => (url.startsWith('/api/films?') && fail ? respond(null, 500) : null));
    renderApp('/library');
    expect(await screen.findByRole('alert')).toHaveTextContent('The projector jammed');
    fail = false;
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('link', { name: 'Heat, 1995' })).toBeInTheDocument();
  });

  it('with no facets in the response the pickers offer only All', async () => {
    installFetch((m, url) =>
      url.startsWith('/api/films?')
        ? respond({ total: 1, films: [fixtures.films.films[0]] })
        : null,
    );
    renderApp('/library');
    await screen.findByText(/sorted by/);
    expect(within(screen.getByLabelText('Genre')).getAllByRole('option')).toHaveLength(1);
  });

  it('keeps the previous films on screen, marked busy, while a new search loads', async () => {
    let release;
    installFetch((m, url) => {
      if (url.includes('q=heat')) return new Promise((resolve) => (release = resolve));
      return null;
    });
    renderApp('/library');
    await screen.findByRole('link', { name: 'Alien, 1979, watched' });
    await userEvent.type(screen.getByRole('searchbox', { name: /Search/ }), 'heat');
    await waitFor(() => expect(release).toBeDefined());
    expect(screen.getByRole('link', { name: 'Alien, 1979, watched' })).toBeInTheDocument();
    release(respond({ ...fixtures.films, total: 1, films: [fixtures.films.films[0]] }));
    await screen.findByText('1 film · sorted by recently added');
    expect(screen.queryByRole('link', { name: 'Alien, 1979, watched' })).not.toBeInTheDocument();
  });
});

const tileNames = () =>
  screen
    .getAllByRole('link')
    .filter((a) => /^\/(films|collections)\//.test(a.getAttribute('href')))
    .map((a) => a.getAttribute('aria-label') || '');

describe('Group collections', () => {
  it('has a switch, off by default, and no tiles', async () => {
    installFetch();
    renderApp('/library');
    await screen.findByText(/sorted by/);
    expect(screen.getByRole('switch', { name: 'Group collections' })).not.toBeChecked();
    expect(screen.queryByRole('link', { name: /collection of/ })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Alien, 1979, watched' })).toBeInTheDocument();
  });

  it('turning it on folds each collection into one tile and says so in the count', async () => {
    installFetch();
    renderApp('/library');
    await screen.findByText(`${fixtures.films.total} films · sorted by recently added`);
    await userEvent.click(screen.getByRole('switch', { name: 'Group collections' }));
    // Alien + Alien 3 become one tile, the three Three Colours films another: 27 films, 24 - 2 ... tiles.
    const tiles = fixtures.films.total - 1 - 2;
    expect(
      await screen.findByText(
        `${fixtures.films.total} films in ${tiles} tiles · sorted by recently added`,
      ),
    ).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Alien, collection of 2 films' })).toHaveAttribute(
      'href',
      '/collections/1',
    );
    expect(
      screen.getByRole('link', { name: 'The Three Colours, collection of 3 films' }),
    ).toHaveAttribute('href', '/collections/3');
    expect(screen.getByText('Collection · 3 films')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Alien, 1979, watched' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Heat, 1995' })).toBeInTheDocument();
    expect(storage.getItem('cinefilm.library.groupCollections')).toBe('1');
  });

  it('turning it off again unfolds them', async () => {
    installFetch();
    renderApp('/library');
    await screen.findByText(/sorted by/);
    const toggle = screen.getByRole('switch', { name: 'Group collections' });
    await userEvent.click(toggle);
    await screen.findByRole('link', { name: 'Alien, collection of 2 films' });
    await userEvent.click(toggle);
    expect(await screen.findByRole('link', { name: 'Alien, 1979, watched' })).toBeInTheDocument();
    expect(storage.getItem('cinefilm.library.groupCollections')).toBeNull();
  });

  it('starts on when it was left on', async () => {
    storage.setItem('cinefilm.library.groupCollections', '1');
    installFetch();
    renderApp('/library');
    expect(await screen.findByRole('switch', { name: 'Group collections' })).toBeChecked();
    expect(
      await screen.findByRole('link', { name: 'Alien, collection of 2 films' }),
    ).toBeInTheDocument();
  });

  it('a search shows individual films even with the switch on', async () => {
    storage.setItem('cinefilm.library.groupCollections', '1');
    installFetch();
    renderApp('/library?q=alien');
    expect(await screen.findByText(/^2 films · sorted by/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Alien, 1979, watched' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Alien 3, 1992' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /collection of/ })).not.toBeInTheDocument();
    expect(screen.getByRole('switch', { name: 'Group collections' })).toBeChecked();
  });

  it('a collection tile counts only the films that pass the filters', async () => {
    storage.setItem('cinefilm.library.groupCollections', '1');
    // As the server answers show=unwatched: only one Alien film passes, none of the Three Colours do.
    installFetch((m, url) =>
      url.includes('show=unwatched')
        ? respond({
            total: 2,
            films: [
              {
                ...fixtures.films.films[0],
                id: 117,
                title: 'Alien 3',
                sortTitle: 'alien 3',
                year: 1992,
                collectionId: 1,
              },
              {
                ...fixtures.films.films[0],
                id: 2,
                title: 'Zodiac',
                sortTitle: 'zodiac',
                collectionId: null,
              },
            ],
            facets: { genres: [], decades: [] },
          })
        : null,
    );
    renderApp('/library?show=unwatched');
    expect(
      await screen.findByRole('link', { name: 'Alien, collection of 1 film' }),
    ).toBeInTheDocument();
    expect(screen.getByText('Collection · 1 film')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /Three Colours/ })).not.toBeInTheDocument();
    expect(
      await screen.findByText('2 films in 2 tiles · sorted by recently added'),
    ).toBeInTheDocument();
  });

  it('puts a collection under the title sort at its display name among the films', async () => {
    storage.setItem('cinefilm.library.groupCollections', '1');
    installFetch((m, url) =>
      url.startsWith('/api/films?')
        ? respond({
            total: 4,
            films: [
              {
                ...fixtures.films.films[0],
                id: 1,
                title: 'Aardvark',
                sortTitle: 'aardvark',
                collectionId: null,
              },
              {
                ...fixtures.films.films[0],
                id: 7,
                title: 'Alien',
                sortTitle: 'alien',
                year: 1979,
                collectionId: 1,
              },
              {
                ...fixtures.films.films[0],
                id: 117,
                title: 'Alien 3',
                year: 1992,
                collectionId: 1,
              },
              { ...fixtures.films.films[0], id: 2, title: 'Zodiac', collectionId: null },
            ],
            facets: { genres: [], decades: [] },
          })
        : null,
    );
    renderApp('/library?sort=title');
    await screen.findByRole('link', { name: 'Alien, collection of 2 films' });
    expect(tileNames().map((n) => n.split(',')[0])).toEqual(['Aardvark', 'Alien', 'Zodiac']);
    expect(await screen.findByText('4 films in 3 tiles · sorted by title')).toBeInTheDocument();
  });

  it('says "1 film in 1 tile" in the singular', async () => {
    storage.setItem('cinefilm.library.groupCollections', '1');
    installFetch((m, url) =>
      url.startsWith('/api/films?')
        ? respond({
            total: 1,
            films: [{ ...fixtures.films.films[0], collectionId: null }],
            facets: { genres: [], decades: [] },
          })
        : null,
    );
    renderApp('/library');
    expect(
      await screen.findByText('1 film in 1 tile · sorted by recently added'),
    ).toBeInTheDocument();
  });

  it('shows plain films while the collections are unavailable', async () => {
    storage.setItem('cinefilm.library.groupCollections', '1');
    installFetch((m, url) => (url === '/api/collections' ? respond(null, 500) : null));
    renderApp('/library');
    expect(await screen.findByRole('link', { name: 'Alien, 1979, watched' })).toBeInTheDocument();
  });
});
