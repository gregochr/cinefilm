import { readFileSync } from 'node:fs';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';

afterEach(() => vi.unstubAllGlobals());

const EMPTY = {
  hero: null,
  continueWatching: [],
  recentlyAdded: [],
  notWatchedInYears: [],
  weeklyTheme: null,
};
const film = (id, title, extra = {}) => ({
  id,
  title,
  year: 2000 + id,
  posterPath: null,
  backdropPath: null,
  watched: false,
  progress: null,
  director: null,
  ...extra,
});

const homeWith = (patch) =>
  installFetch((m, url) => (url === '/api/home' ? respond({ ...EMPTY, ...patch }) : null));

describe('Home hero', () => {
  it('offers Resume from the saved time to the saved edition, and Details', async () => {
    installFetch();
    renderApp('/');
    const hero = await screen.findByRole('region', { name: 'Resume Heat' });
    expect(within(hero).getByText('Pick up where you left off')).toBeInTheDocument();
    expect(within(hero).getByRole('heading', { level: 1, name: 'Heat' })).toBeInTheDocument();
    expect(within(hero).getByText('1995 · Michael Mann · 1h 45m left')).toBeInTheDocument();
    expect(within(hero).getByRole('link', { name: 'Resume from 1:04:31' })).toHaveAttribute(
      'href',
      '/films/1/play?edition=11',
    );
    expect(within(hero).getByRole('link', { name: 'Details' })).toHaveAttribute('href', '/films/1');
  });

  it('offers a plain Play, with no edition and no time left, for a hero with no progress', async () => {
    homeWith({ hero: film(5, 'Zodiac', { director: 'David Fincher' }) });
    renderApp('/');
    const hero = await screen.findByRole('region', { name: 'Resume Zodiac' });
    expect(within(hero).getByRole('link', { name: 'Play' })).toHaveAttribute(
      'href',
      '/films/5/play',
    );
    expect(within(hero).getByText('2005 · David Fincher')).toBeInTheDocument();
  });

  it('leaves out an unknown director and does not invent an edition', async () => {
    homeWith({ hero: film(5, 'Zodiac', { progress: { positionMs: 60000, runtimeMs: 600000 } }) });
    renderApp('/');
    const hero = await screen.findByRole('region', { name: 'Resume Zodiac' });
    expect(within(hero).getByText('2005 · 9m left')).toBeInTheDocument();
    expect(within(hero).getByRole('link', { name: 'Resume from 1:00' })).toHaveAttribute(
      'href',
      '/films/5/play',
    );
  });
});

describe('Home hero backdrop crop', () => {
  it('crops the backdrop from near the top so heads stay in frame (real stylesheet)', async () => {
    const style = document.createElement('style');
    style.textContent = readFileSync('src/styles/browse.css', 'utf8');
    document.head.appendChild(style);
    try {
      homeWith({
        hero: film(1, 'Taken', {
          backdropPath: '/taken.jpg',
          progress: { positionMs: 1000, runtimeMs: 6000000 },
        }),
      });
      renderApp('/');
      const hero = await screen.findByRole('region', { name: 'Resume Taken' });
      const backdrop = within(within(hero).getByTestId('art')).getByAltText('');
      expect(getComputedStyle(backdrop).objectPosition).toBe('50% 15%');
    } finally {
      style.remove();
    }
  });
});

describe('Home rows', () => {
  it('shows every row with its sub-line and See all destination', async () => {
    installFetch();
    renderApp('/');
    await screen.findByRole('region', { name: 'Resume Heat' });
    const continueRow = screen.getByRole('region', { name: 'Continue watching' });
    expect(within(continueRow).getByText('5 in progress')).toBeInTheDocument();
    expect(within(continueRow).queryByRole('link', { name: /See all/ })).not.toBeInTheDocument();
    const recent = screen.getByRole('region', { name: 'Recently added' });
    expect(within(recent).getByText('Last 30 days')).toBeInTheDocument();
    expect(within(recent).getByRole('link', { name: 'See all: Recently added' })).toHaveAttribute(
      'href',
      '/library?sort=added',
    );
    const old = screen.getByRole('region', { name: 'Not watched in years' });
    expect(within(old).getByText('Five years or more')).toBeInTheDocument();
    expect(
      within(old).getByRole('link', { name: 'See all: Not watched in years' }),
    ).toHaveAttribute('href', '/library?show=watched&sort=title');
    const theme = screen.getByRole('region', { name: 'This week: the 1970s' });
    expect(within(theme).getByText('A new theme every Monday')).toBeInTheDocument();
    expect(within(theme).getByRole('link', { name: /See all/ })).toHaveAttribute(
      'href',
      '/library',
    );
    expect(screen.queryByRole('region', { name: 'Collections' })).not.toBeInTheDocument();
  });

  it('continue-watching cards link to the film and say how much is left', async () => {
    installFetch();
    renderApp('/');
    expect(await screen.findByRole('link', { name: 'Arrival, 56m left' })).toHaveAttribute(
      'href',
      '/films/2',
    );
  });

  it.each([
    ['continueWatching', 'Continue watching'],
    ['recentlyAdded', 'Recently added'],
    ['notWatchedInYears', 'Not watched in years'],
  ])('omits the %s row when it is empty', async (key, heading) => {
    const full = { ...EMPTY, hero: fixtures.home.hero, [key]: [] };
    for (const other of ['continueWatching', 'recentlyAdded', 'notWatchedInYears']) {
      if (other !== key) full[other] = [film(9, `In ${other}`)];
    }
    installFetch((m, url) => (url === '/api/home' ? respond(full) : null));
    renderApp('/');
    await screen.findByRole('region', { name: 'Resume Heat' });
    expect(screen.queryByRole('region', { name: heading })).not.toBeInTheDocument();
  });

  it('omits the theme row when the theme has no films', async () => {
    homeWith({
      hero: film(5, 'Zodiac'),
      weeklyTheme: { title: 'This week: noir', caption: 'x', films: [] },
    });
    renderApp('/');
    await screen.findByRole('region', { name: 'Resume Zodiac' });
    expect(screen.queryByRole('region', { name: 'This week: noir' })).not.toBeInTheDocument();
  });

  it('shows a lone theme row for a library with nothing else', async () => {
    homeWith({
      weeklyTheme: { title: 'This week: noir', caption: 'Mondays', films: [film(3, 'Laura')] },
    });
    renderApp('/');
    expect(await screen.findByRole('region', { name: 'This week: noir' })).toBeInTheDocument();
    expect(screen.queryByText('The house lights are up')).not.toBeInTheDocument();
  });

  it('tolerates a payload missing its optional lists', async () => {
    installFetch((m, url) => (url === '/api/home' ? respond({ hero: film(5, 'Zodiac') }) : null));
    renderApp('/');
    expect(await screen.findByRole('region', { name: 'Resume Zodiac' })).toBeInTheDocument();
  });
});

describe('Home states', () => {
  it('shows a Loading status while the first response is on its way', async () => {
    installFetch(() => new Promise(() => {}));
    renderApp('/');
    expect(screen.getByRole('status', { name: 'Loading' })).toBeInTheDocument();
  });

  it('shows the projector-jammed error, and Try again loads the page', async () => {
    let fail = true;
    installFetch((m, url) => (url === '/api/home' && fail ? respond(null, 500) : null));
    renderApp('/');
    expect(await screen.findByRole('alert')).toHaveTextContent('The projector jammed');
    fail = false;
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('region', { name: 'Resume Heat' })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('shows nothing for a successful but empty (null) response', async () => {
    installFetch((m, url) => (url === '/api/home' ? respond(null, 204) : null));
    renderApp('/');
    await vi.waitFor(() =>
      expect(screen.queryByRole('status', { name: 'Loading' })).not.toBeInTheDocument(),
    );
    expect(screen.getByRole('main')).toBeEmptyDOMElement();
  });
});
