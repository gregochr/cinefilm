import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';

afterEach(() => vi.unstubAllGlobals());

const gets = (fetchMock, matches) =>
  fetchMock.mock.calls.filter((c) => (c[1]?.method ?? 'GET') === 'GET' && matches(c[0]));
const listGets = (fetchMock) => gets(fetchMock, (u) => u.startsWith('/api/films?'));
const headerLink = (name) =>
  within(screen.getByRole('navigation', { name: 'Primary' })).getByRole('link', { name });

// Serves `path` from `answers` in turn; once they run out, the request waits for `release` (a held answer).
function held(path, firstAnswer) {
  let release;
  let calls = 0;
  const route = (method, url) => {
    if (method !== 'GET' || url.split('?')[0] !== path) return null;
    calls += 1;
    if (calls === 1) return respond(firstAnswer());
    return new Promise((resolve) => (release = (body) => resolve(respond(body))));
  };
  return { route, release: (body) => release(body), calls: () => calls };
}

describe('Back to a screen shows the last answer at once', () => {
  it('Library, a film, back: the grid is there in the first render, with one background request', async () => {
    const fetchMock = installFetch();
    renderApp('/library');
    await userEvent.click(await screen.findByRole('link', { name: 'Heat, 1995' }));
    await screen.findByRole('button', { name: 'Mark as watched' });
    expect(listGets(fetchMock)).toHaveLength(1);

    await userEvent.click(headerLink('Library'));

    expect(screen.getByRole('link', { name: 'Heat, 1995' })).toBeInTheDocument();
    expect(screen.queryAllByTestId('skeleton')).toHaveLength(0);
    await waitFor(() => expect(listGets(fetchMock)).toHaveLength(2));
    await screen.findByRole('link', { name: 'Heat, 1995' });
    expect(listGets(fetchMock)).toHaveLength(2);
  });

  it('shows Home at once from the last answer too', async () => {
    const fetchMock = installFetch();
    renderApp('/');
    await screen.findByRole('region', { name: 'Continue watching' });
    await userEvent.click(headerLink('Library'));
    await screen.findByRole('link', { name: 'Heat, 1995' });
    await userEvent.click(headerLink('Home'));
    expect(screen.getByRole('region', { name: 'Continue watching' })).toBeInTheDocument();
    expect(screen.queryAllByTestId('skeleton')).toHaveLength(0);
    await waitFor(() => expect(gets(fetchMock, (u) => u === '/api/home')).toHaveLength(2));
  });
});

describe('Play reuses the film Film detail loaded', () => {
  it('Film detail, Play: the session starts with exactly one GET of the film', async () => {
    const fetchMock = installFetch();
    renderApp('/films/1');
    await userEvent.click(await screen.findByRole('link', { name: /^Resume from/ }));
    const video = await screen.findByTestId('player-video');
    await waitFor(() => expect(video).toHaveAttribute('src'));
    expect(gets(fetchMock, (u) => u === '/api/films/1')).toHaveLength(1);
    expect(
      fetchMock.mock.calls.some(
        (c) => c[0] === '/api/editions/11/sessions' && c[1].method === 'POST',
      ),
    ).toBe(true);
  });

  it('a preference change makes Play fetch the film again', async () => {
    const fetchMock = installFetch();
    renderApp('/films/1');
    await userEvent.selectOptions(
      await screen.findByLabelText('Audio'),
      'Commentary — Michael Mann · AAC',
    );
    await waitFor(() =>
      expect(fetchMock.mock.calls.some((c) => c[0] === '/api/films/1/preferences')).toBe(true),
    );
    await userEvent.click(screen.getByRole('link', { name: /^Resume from/ }));
    await screen.findByTestId('player-video');
    expect(gets(fetchMock, (u) => u === '/api/films/1')).toHaveLength(2);
  });
});

describe('Never stale after a change made in this tab', () => {
  it('Mark as watched, then Library: the skeleton shows, not the cached grid, and the film is watched', async () => {
    const listing = held('/api/films', () => fixtures.films);
    installFetch(listing.route);
    renderApp('/library');
    await userEvent.click(await screen.findByRole('link', { name: 'Heat, 1995' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Mark as watched' }));
    await screen.findByRole('button', { name: 'Mark as unwatched' });

    await userEvent.click(headerLink('Library'));

    await waitFor(() => expect(listing.calls()).toBe(2));
    expect(screen.getAllByTestId('skeleton').length).toBeGreaterThan(0);
    expect(screen.queryByRole('link', { name: 'Heat, 1995' })).not.toBeInTheDocument();
    listing.release({
      ...fixtures.films,
      films: fixtures.films.films.map((f) => (f.id === 1 ? { ...f, watched: true } : f)),
    });
    expect(await screen.findByRole('link', { name: 'Heat, 1995, watched' })).toBeInTheDocument();
  });
});

describe('A revalidation that crosses a change does not undo it', () => {
  it('Home: Remove from Continue watching, then the held answer lands with the film still in it', async () => {
    const home = held('/api/home', () => fixtures.home);
    installFetch(home.route);
    renderApp('/');
    await screen.findByRole('region', { name: 'Continue watching' });
    await userEvent.click(headerLink('Library'));
    await screen.findByRole('link', { name: 'Heat, 1995' });
    await userEvent.click(headerLink('Home'));
    await waitFor(() => expect(home.calls()).toBe(2)); // the background answer is held
    const cw = screen.getByRole('region', { name: 'Continue watching' });
    expect(within(cw).getByRole('link', { name: /^Arrival/ })).toBeInTheDocument();

    await userEvent.click(within(cw).getByRole('button', { name: 'More options for Arrival' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Remove from Continue watching' }));
    expect(within(cw).queryByRole('link', { name: /^Arrival/ })).not.toBeInTheDocument();

    await act(async () => home.release(fixtures.home)); // the old state, with Arrival still in progress
    expect(within(cw).queryByRole('link', { name: /^Arrival/ })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Undo' }));
    expect(within(cw).getByRole('link', { name: /^Arrival/ })).toBeInTheDocument();
  });

  it('Film detail: Mark as watched, then the held answer lands without it', async () => {
    const film = held('/api/films/1', () => fixtures.heat);
    installFetch(film.route);
    renderApp('/films/1');
    await screen.findByRole('button', { name: 'Mark as watched' });
    await userEvent.click(headerLink('Library'));
    await userEvent.click(await screen.findByRole('link', { name: 'Heat, 1995' }));
    await waitFor(() => expect(film.calls()).toBe(2));
    expect(screen.getByRole('button', { name: 'Mark as watched' })).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Mark as watched' }));
    await screen.findByRole('button', { name: 'Mark as unwatched' });
    await act(async () =>
      film.release({ ...fixtures.heat, overview: 'A stale overview from the held answer.' }),
    );

    expect(screen.getByRole('button', { name: 'Mark as unwatched' })).toBeInTheDocument();
    expect(screen.queryByText('A stale overview from the held answer.')).not.toBeInTheDocument();
  });
});
