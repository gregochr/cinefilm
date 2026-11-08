import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';

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
    expect(screen.getByRole('status')).toHaveTextContent('films · sorted by recently added');
    expect(screen.getByRole('link', { name: 'Heat, 1995' })).toBeInTheDocument();
  });

  it('the genre, decade and collection pickers offer the facets from the API', async () => {
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
    const collection = screen.getByLabelText('Collection');
    expect(within(collection).getByRole('option', { name: 'Three Colours' })).toHaveValue('3');
  });

  it('choosing a genre, decade and collection refetches with each and keeps them in the URL', async () => {
    const fetchMock = installFetch();
    renderApp('/library');
    await screen.findByText(/sorted by/);
    await userEvent.selectOptions(screen.getByLabelText('Genre'), 'Sci-fi');
    await waitFor(() => expect(lastFilmsUrl(fetchMock)).toContain('genre=Sci-fi'));
    await userEvent.selectOptions(screen.getByLabelText('Decade'), '1970s');
    await waitFor(() => expect(lastFilmsUrl(fetchMock)).toContain('decade=1970'));
    await userEvent.selectOptions(screen.getByLabelText('Collection'), 'Alien');
    await waitFor(() => expect(lastFilmsUrl(fetchMock)).toContain('collection=1'));
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

  it('is focused when the Search tab opened the library', async () => {
    installFetch();
    renderApp('/library?focus=search');
    const box = await screen.findByRole('searchbox', { name: /Search/ });
    expect(box).toHaveFocus();
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
