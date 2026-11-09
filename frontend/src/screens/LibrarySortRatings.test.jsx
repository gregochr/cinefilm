import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { installFetch, renderApp } from '../test/mockApi.jsx';

afterEach(() => vi.unstubAllGlobals());

const lastFilmsUrl = (fetchMock) =>
  fetchMock.mock.calls
    .map((c) => c[0])
    .filter((u) => u.startsWith('/api/films?'))
    .at(-1);

describe('Library sort by rating', () => {
  it('offers IMDb rating and Rotten Tomatoes after the existing sorts', async () => {
    installFetch();
    renderApp('/library');
    const group = await screen.findByRole('group', { name: 'Sort' });
    expect(
      within(group)
        .getAllByRole('button')
        .map((b) => b.textContent),
    ).toEqual(['Title', 'Year', 'Recently added', 'IMDb rating', 'Rotten Tomatoes']);
  });

  it('choosing IMDb rating refetches sorted by imdb and says so', async () => {
    const fetchMock = installFetch();
    renderApp('/library');
    await screen.findByText(/sorted by recently added/);
    await userEvent.click(screen.getByRole('button', { name: 'IMDb rating' }));
    expect(await screen.findByText(/sorted by IMDb rating/)).toBeInTheDocument();
    expect(lastFilmsUrl(fetchMock)).toBe('/api/films?sort=imdb');
  });

  it('choosing Rotten Tomatoes refetches sorted by rt and says so', async () => {
    const fetchMock = installFetch();
    renderApp('/library?sort=imdb');
    await screen.findByText(/sorted by IMDb rating/);
    await userEvent.click(screen.getByRole('button', { name: 'Rotten Tomatoes' }));
    expect(await screen.findByText(/sorted by Rotten Tomatoes/)).toBeInTheDocument();
    expect(lastFilmsUrl(fetchMock)).toBe('/api/films?sort=rt');
  });
});
