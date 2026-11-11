import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp } from '../test/mockApi.jsx';

afterEach(() => vi.unstubAllGlobals());

const pressed = (group, name) =>
  within(screen.getByRole('group', { name: group })).getByRole('button', { name });
const lastFilmsUrl = (fetchMock) =>
  fetchMock.mock.calls
    .map((c) => c[0])
    .filter((u) => u.startsWith('/api/films?'))
    .at(-1);
const inProgressLabel = `${fixtures.films.total} films in progress · sorted by last watched`;

describe('Library: In progress and Last watched', () => {
  it('offers In progress after All in Show', async () => {
    installFetch();
    renderApp('/library');
    const group = await screen.findByRole('group', { name: 'Show' });
    expect(
      within(group)
        .getAllByRole('button')
        .map((b) => b.textContent),
    ).toEqual(['All', 'In progress', 'Unwatched', 'Watched']);
  });

  it('the deep link shows In progress and Last watched selected, with the right heading', async () => {
    installFetch();
    renderApp('/library?show=in-progress&sort=last-watched');
    expect(await screen.findByText(inProgressLabel)).toBeInTheDocument();
    expect(pressed('Show', 'In progress')).toHaveAttribute('aria-pressed', 'true');
    expect(pressed('Sort', 'Last watched')).toHaveAttribute('aria-pressed', 'true');
    expect(pressed('Show', 'All')).toHaveAttribute('aria-pressed', 'false');
  });

  it('choosing In progress switches the sort to Last watched, and Watched keeps it', async () => {
    const fetchMock = installFetch();
    renderApp('/library');
    await screen.findByText(/sorted by recently added/);
    await userEvent.click(pressed('Show', 'In progress'));
    expect(await screen.findByText(inProgressLabel)).toBeInTheDocument();
    expect(lastFilmsUrl(fetchMock)).toBe('/api/films?sort=last-watched&show=in-progress');

    await userEvent.click(pressed('Show', 'Watched'));
    expect(await screen.findByText(/films · sorted by last watched/)).toBeInTheDocument();
    expect(pressed('Sort', 'Last watched')).toHaveAttribute('aria-pressed', 'true');
    expect(pressed('Show', 'Watched')).toHaveAttribute('aria-pressed', 'true');
  });

  it('choosing All again clears the show filter', async () => {
    installFetch();
    renderApp('/library?show=in-progress&sort=last-watched');
    await screen.findByText(inProgressLabel);
    await userEvent.click(pressed('Show', 'All'));
    expect(
      await screen.findByText(`${fixtures.films.total} films · sorted by last watched`),
    ).toBeInTheDocument();
  });

  it('shows individual films even with Group collections on', async () => {
    installFetch();
    renderApp('/library?show=in-progress&sort=last-watched');
    await screen.findByText(inProgressLabel);
    await userEvent.click(screen.getByRole('switch', { name: 'Group collections' }));
    expect(screen.getByRole('switch', { name: 'Group collections' })).toBeChecked();
    expect(screen.getByText(inProgressLabel)).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /collection of/ })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Alien, 1979, watched' })).toBeInTheDocument();
  });
});
