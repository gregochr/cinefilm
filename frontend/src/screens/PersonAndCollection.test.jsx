import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import App from '../App.jsx';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';

afterEach(() => vi.unstubAllGlobals());

const film = (id, title) => ({
  id,
  title,
  year: 1990 + id,
  posterPath: null,
  backdropPath: null,
  watched: false,
  progress: null,
  director: null,
});

const personWith = (patch) =>
  installFetch((m, url) =>
    url.startsWith('/api/people/') ? respond({ ...fixtures.person, ...patch }) : null,
  );

describe('Person', () => {
  it('names the person, what they did and how many films are in the library', async () => {
    installFetch();
    renderApp('/people/11');
    expect(await screen.findByRole('heading', { level: 1, name: 'Robert De Niro' })).toBeVisible();
    expect(screen.getByText('Actor · 2 films in your library')).toBeInTheDocument();
    const row = screen.getByRole('region', { name: 'In your library' });
    expect(within(row).getByRole('link', { name: 'Heat, 1995' })).toHaveAttribute(
      'href',
      '/films/1',
    );
    expect(within(row).getByText('as Neil McCauley')).toBeInTheDocument();
    expect(within(row).getByText('as Travis Bickle')).toBeInTheDocument();
  });

  it.each([
    ['a director', ['Director'], 'Director · 1 film in your library'],
    [
      'someone who both directs and acts',
      ['Director', 'as Himself'],
      'Actor and director · 1 film in your library',
    ],
    ['an actor', ['as Neil McCauley'], 'Actor · 1 film in your library'],
  ])('describes %s', async (_who, roles, expected) => {
    personWith({ films: [{ film: film(1, 'Heat'), roles }] });
    renderApp('/people/11');
    expect(await screen.findByText(expected)).toBeInTheDocument();
  });

  it('joins several roles in one film', async () => {
    personWith({ films: [{ film: film(1, 'Heat'), roles: ['Director', 'Writer'] }] });
    renderApp('/people/11');
    expect(await screen.findByText('Director, Writer')).toBeInTheDocument();
  });

  it('shows initials instead of a photo when there is none', async () => {
    installFetch();
    renderApp('/people/11');
    await screen.findByRole('heading', { name: 'Robert De Niro' });
    expect(screen.getByText('RD')).toBeInTheDocument();
    expect(screen.queryByAltText('')).not.toBeInTheDocument();
  });

  it('shows the photo, as decoration, when there is one', async () => {
    personWith({ profilePath: '/rdn.jpg' });
    renderApp('/people/11');
    await screen.findByRole('heading', { name: 'Robert De Niro' });
    expect(screen.getByAltText('')).toHaveAttribute(
      'src',
      'https://image.tmdb.org/t/p/w185/rdn.jpg',
    );
    expect(screen.queryByText('RD')).not.toBeInTheDocument();
  });

  it('Back returns to the previous page', async () => {
    installFetch();
    render(
      <MemoryRouter initialEntries={['/films/1', '/people/11']} initialIndex={1}>
        <App />
      </MemoryRouter>,
    );
    await userEvent.click(await screen.findByRole('button', { name: /Back/ }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Heat' })).toBeInTheDocument();
  });

  it('shows a Loading status, then an error with Try again', async () => {
    let fail = true;
    installFetch((m, url) => (url.startsWith('/api/people/') && fail ? respond(null, 500) : null));
    renderApp('/people/11');
    expect(screen.getByRole('status', { name: 'Loading' })).toBeInTheDocument();
    expect(await screen.findByRole('alert')).toHaveTextContent('The projector jammed');
    fail = false;
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('heading', { name: 'Robert De Niro' })).toBeInTheDocument();
  });
});

describe('Collection', () => {
  it('counts owned parts and shows each as a poster or a dashed missing tile', async () => {
    installFetch();
    renderApp('/collections/1');
    // TMDB names it "Alien Collection"; the heading drops the suffix.
    expect(await screen.findByRole('heading', { level: 1, name: 'Alien' })).toBeVisible();
    expect(screen.getByText('2 of 4 films in your library')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Alien, 1979, watched' })).toHaveAttribute(
      'href',
      '/films/7',
    );
    const missing = screen.getByRole('group', { name: 'Aliens, 1986, not in your library' });
    expect(within(missing).getByText('Not in your library')).toBeInTheDocument();
    expect(
      screen.getByRole('group', { name: 'Alien Resurrection, 1997, not in your library' }),
    ).toBeInTheDocument();
  });

  it('shows the artwork of a missing part when it has some', async () => {
    installFetch((m, url) =>
      url.startsWith('/api/collections/')
        ? respond({
            ...fixtures.collection,
            parts: [{ owned: false, title: 'Aliens', year: 1986, posterPath: '/aliens.jpg' }],
          })
        : null,
    );
    renderApp('/collections/1');
    const missing = await screen.findByRole('group', { name: /Aliens, 1986/ });
    expect(within(missing).getByAltText('')).toHaveAttribute(
      'src',
      'https://image.tmdb.org/t/p/w342/aliens.jpg',
    );
    expect(screen.getByText('0 of 1 films in your library')).toBeInTheDocument();
  });

  it('Back returns to the previous page', async () => {
    installFetch();
    render(
      <MemoryRouter initialEntries={['/films/1', '/collections/1']} initialIndex={1}>
        <App />
      </MemoryRouter>,
    );
    await userEvent.click(await screen.findByRole('button', { name: /Back/ }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Heat' })).toBeInTheDocument();
  });

  it('shows a Loading status, then an error with Try again', async () => {
    let fail = true;
    installFetch((m, url) =>
      url.startsWith('/api/collections/') && fail ? respond(null, 500) : null,
    );
    renderApp('/collections/1');
    expect(screen.getByRole('status', { name: 'Loading' })).toBeInTheDocument();
    expect(await screen.findByRole('alert')).toHaveTextContent('The projector jammed');
    fail = false;
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('heading', { name: 'Alien' })).toBeInTheDocument();
  });
});
