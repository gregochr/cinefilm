import { screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';

afterEach(() => vi.unstubAllGlobals());

function filmServer(mutate = () => {}) {
  const film = structuredClone(fixtures.heat);
  mutate(film);
  return installFetch((method, url) => (url === '/api/films/1' ? respond(film) : null));
}

describe('FilmDetail ratings row', () => {
  it('shows IMDb and Rotten Tomatoes under the meta line, above the plot', async () => {
    filmServer();
    renderApp('/films/1');
    const row = await screen.findByRole('list', { name: 'Ratings' });
    expect(within(row).getByRole('link', { name: /^IMDb rating 8.3 out of 10/ })).toHaveAttribute(
      'href',
      'https://www.imdb.com/title/tt0113277/',
    );
    expect(within(row).getByText('Rotten Tomatoes')).toBeInTheDocument();
    expect(within(row).getByText('84%')).toBeInTheDocument();
    const plot = screen.getByText(fixtures.heat.overview);
    expect(row.compareDocumentPosition(plot) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('hides Rotten Tomatoes when the film has no score from it', async () => {
    filmServer((f) => {
      f.ratings.rottenTomatoes = null;
    });
    renderApp('/films/1');
    await screen.findByRole('list', { name: 'Ratings' });
    expect(screen.queryByText('Rotten Tomatoes')).not.toBeInTheDocument();
  });

  it('hides the whole row when there are no ratings', async () => {
    filmServer((f) => {
      f.ratings = { imdb: null, rottenTomatoes: null };
    });
    renderApp('/films/1');
    await screen.findByRole('heading', { name: 'Heat' });
    expect(screen.queryByRole('list', { name: 'Ratings' })).not.toBeInTheDocument();
  });
});
