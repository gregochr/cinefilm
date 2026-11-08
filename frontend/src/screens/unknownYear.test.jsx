import { fireEvent, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';

afterEach(() => vi.unstubAllGlobals());

// A film whose year is unknown comes from the API with `year: null`: no year, no separator, no "null".
const noYear = (film) => ({ ...film, year: null });

describe('a film with an unknown year', () => {
  it('is a poster captioned with the title alone, named for screen readers without a year', async () => {
    installFetch((m, url) =>
      url.startsWith('/api/films?') || url === '/api/films'
        ? respond({ ...fixtures.films, films: [noYear(fixtures.films.films[0])], total: 1 })
        : null,
    );
    renderApp('/library');
    const link = await screen.findByRole('link', { name: 'Heat' });
    expect(link.querySelector('.poster__caption').textContent).toBe('Heat');
    expect(link.textContent).not.toMatch(/·|null|undefined/);
  });

  it('shows no year or stray dot in the Home hero meta', async () => {
    installFetch((m, url) =>
      url === '/api/home' ? respond({ ...fixtures.home, hero: noYear(fixtures.home.hero) }) : null,
    );
    const { container } = renderApp('/');
    await screen.findByRole('heading', { name: 'Heat' });
    const meta = container.querySelector('.hero__meta').textContent;
    expect(meta).toBe('Michael Mann · 1h 45m left');
    expect(meta).not.toMatch(/^·|null/);
  });

  it('shows the detail meta and each edition line without a year or dot', async () => {
    const film = structuredClone(fixtures.bladeRunner);
    film.year = null;
    film.editions.forEach((e) => {
      e.year = null;
    });
    installFetch((m, url) => (url === '/api/films/6' ? respond(film) : null));
    const { container } = renderApp('/films/6');
    await screen.findByRole('heading', { name: 'Blade Runner' });
    expect(screen.queryByText('1982')).not.toBeInTheDocument();
    const meta = [...container.querySelectorAll('.edition__meta')].map((n) => n.textContent);
    expect(meta).toEqual(['1h 57m', '1h 56m', '1h 58m']);
    expect(container.textContent).not.toMatch(/null|undefined/);
  });

  it('still shows the year and the dot when there is one', async () => {
    installFetch();
    const { container } = renderApp('/films/6');
    await screen.findByRole('heading', { name: 'Blade Runner' });
    expect(screen.getByText('1982')).toBeInTheDocument();
    expect(container.querySelector('.edition__meta').textContent).toBe('2007 · 1h 57m');
  });

  it('has no year in the player header', async () => {
    const film = structuredClone(fixtures.heat);
    film.year = null;
    film.editions.forEach((e) => {
      e.year = null;
    });
    installFetch((m, url) => (url === '/api/films/1' ? respond(film) : null));
    const { container } = renderApp('/films/1/play?edition=11');
    const video = await screen.findByTestId('player-video');
    fireEvent.loadedMetadata(video);
    expect(container.querySelector('.player__name')).toHaveTextContent('Heat');
    expect(container.querySelector('.player__year')).toBeNull();
  });

  it('keeps the year in the player header when there is one', async () => {
    installFetch();
    const { container } = renderApp('/films/1/play?edition=11');
    const video = await screen.findByTestId('player-video');
    fireEvent.loadedMetadata(video);
    expect(container.querySelector('.player__year')).toHaveTextContent('1995');
  });

  it('a missing collection part with no year reads as the title alone', async () => {
    const collection = structuredClone(fixtures.collection);
    collection.parts.find((p) => p.title === 'Aliens').year = null;
    installFetch((m, url) => (/^\/api\/collections\//.test(url) ? respond(collection) : null));
    renderApp('/collections/1');
    const tile = await screen.findByRole('group', { name: 'Aliens, not in your library' });
    expect(within(tile).getByText('Aliens')).toBeInTheDocument();
    expect(tile.textContent).not.toMatch(/·|null/);
  });

  it('a missing collection part with a year keeps it', async () => {
    installFetch();
    renderApp('/collections/1');
    const tile = await screen.findByRole('group', { name: 'Aliens, 1986, not in your library' });
    expect(within(tile).getByText('Aliens · 1986')).toBeInTheDocument();
  });
});
