import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp, respond } from '../../test/mockApi.jsx';

beforeEach(() => {
  // Only the clock: "scanned {ago}" counts from here, 12 min after the fixture's scan.
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(Date.parse('2026-11-08T12:53:10Z'));
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

const summary = (over) => ({ ...fixtures.adminSummary, ...over });
const route = (body) => (method, url) => (url === '/api/admin/summary' ? respond(body) : null);
const rail = () => screen.getByRole('navigation', { name: 'Admin sections' });

describe('Admin To do: heading, intro and status strip', () => {
  it('shows the count beside the heading, then the intro, then the three cards', async () => {
    installFetch();
    renderApp('/admin');

    expect(await screen.findByText('6 things need you')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: 'To do' })).toBeInTheDocument();
    expect(
      screen.getByText(
        "Only things CineFilm can't sort out by itself. Everything else carries on automatically. When this list is empty, there's nothing to do.",
      ),
    ).toBeInTheDocument();
    const cards = within(screen.getByRole('list', { name: 'Status' })).getAllByRole('listitem');
    expect(cards.map((card) => card.textContent)).toEqual([
      'Library538 films · scanned 12 min agoLibrary →',
      'PlaybackLast 30 days: 2 of 3 film sessions played smoothlyPlayback →',
      'Playing nowHeat · Mac · 720pSee stream →',
    ]);
  });

  it('says "1 thing needs you" for one', async () => {
    installFetch(
      route(summary({ todo: { pickFilm: 0, severalFiles: 0, fullMissing: 0, forcedQuestion: 1 } })),
    );
    renderApp('/admin');

    expect(await screen.findByText('1 thing needs you')).toBeInTheDocument();
  });

  it('leaves the count out at 0, with the strip still shown', async () => {
    installFetch(
      route(summary({ todo: { pickFilm: 0, severalFiles: 0, fullMissing: 0, forcedQuestion: 0 } })),
    );
    renderApp('/admin');

    expect(await screen.findByRole('list', { name: 'Status' })).toBeInTheDocument();
    expect(screen.queryByText(/things? needs? you/)).not.toBeInTheDocument();
  });

  it('keeps Films needing attention below the strip', async () => {
    installFetch();
    renderApp('/admin');

    expect(
      await screen.findByRole('region', { name: 'Films needing attention' }),
    ).toBeInTheDocument();
  });

  it('shows a static skeleton of the strip and no count while the summary loads', () => {
    installFetch((method, url) => (url === '/api/admin/summary' ? new Promise(() => {}) : null));
    renderApp('/admin');

    expect(screen.getByRole('status', { name: 'Loading the status' })).toBeInTheDocument();
    expect(screen.queryByText(/things? needs? you/)).not.toBeInTheDocument();
  });

  it('shows an error banner when the summary fails, and Try again reads it', async () => {
    let fail = true;
    installFetch((method, url) =>
      url === '/api/admin/summary' && fail ? respond({ error: 'boom' }, 500) : null,
    );
    renderApp('/admin');

    const banner = await screen.findByRole('alert');
    expect(banner).toHaveTextContent('Couldn’t load the status.');
    expect(screen.queryByText(/things? needs? you/)).not.toBeInTheDocument();
    fail = false;
    await userEvent.click(within(banner).getByRole('button', { name: 'Try again' }));

    expect(await screen.findByText('6 things need you')).toBeInTheDocument();
    expect(screen.queryByText('Couldn’t load the status.')).not.toBeInTheDocument();
  });

  it('shares one read between the rail and the page: moving to another section does not read again', async () => {
    const fetchMock = installFetch();
    renderApp('/admin');
    await within(rail()).findByRole('link', { name: 'To do, 6 things' });
    // The first read starts before the scan status has answered; its finish time then makes a second.
    const reads = () => fetchMock.mock.calls.filter(([url]) => url === '/api/admin/summary').length;
    await waitFor(() => expect(reads()).toBe(2));

    await userEvent.click(within(rail()).getByRole('link', { name: 'Library' }));
    await screen.findByRole('heading', { level: 1, name: 'Library' });

    expect(reads()).toBe(2);
    expect(within(rail()).getByRole('link', { name: 'To do, 6 things' })).toBeInTheDocument();
  });

  it('follows each card to its section', async () => {
    installFetch();
    renderApp('/admin');

    await userEvent.click(await screen.findByRole('link', { name: 'Library →' }));

    expect(await screen.findByRole('heading', { level: 1, name: 'Library' })).toBeInTheDocument();
  });
});

describe('Admin rail counts', () => {
  it('shows the counts, with the numbers in the accessible names', async () => {
    installFetch();
    renderApp('/admin');

    const todo = await within(rail()).findByRole('link', { name: 'To do, 6 things' });
    expect(todo).toHaveTextContent('To do6');
    expect(within(rail()).getByRole('link', { name: 'Subtitles, 2 unresolved' })).toHaveTextContent(
      'Subtitles2',
    );
    expect(within(rail()).getByRole('link', { name: 'Playback, 1 live' })).toHaveTextContent(
      'Playback1 live',
    );
    expect(within(rail()).getByRole('link', { name: 'Library' })).toHaveTextContent(/^Library$/);
    expect(within(rail()).getByRole('link', { name: 'Settings' })).toHaveTextContent(/^Settings$/);
  });

  it('shows no counts while the summary loads', () => {
    installFetch((method, url) => (url === '/api/admin/summary' ? new Promise(() => {}) : null));
    renderApp('/admin');

    for (const name of ['To do', 'Subtitles', 'Library', 'Playback', 'Settings']) {
      expect(within(rail()).getByRole('link', { name })).toHaveTextContent(new RegExp(`^${name}$`));
    }
  });

  it('shows no counts when the summary fails', async () => {
    installFetch((method, url) =>
      url === '/api/admin/summary' ? respond({ error: 'boom' }, 500) : null,
    );
    renderApp('/admin');
    await screen.findByRole('alert');

    for (const name of ['To do', 'Subtitles', 'Library', 'Playback', 'Settings']) {
      expect(within(rail()).getByRole('link', { name })).toHaveTextContent(new RegExp(`^${name}$`));
    }
  });

  it('shows no badge for a count of 0', async () => {
    installFetch(
      route(
        summary({
          todo: { pickFilm: 0, severalFiles: 0, fullMissing: 0, forcedQuestion: 0 },
          subtitlesUnresolved: 0,
          live: 0,
        }),
      ),
    );
    renderApp('/admin');
    await screen.findByRole('list', { name: 'Status' });

    for (const name of ['To do', 'Subtitles', 'Playback']) {
      expect(within(rail()).getByRole('link', { name })).toHaveTextContent(new RegExp(`^${name}$`));
    }
  });

  it('shows "1 live" at 1 and "{n} live" above', async () => {
    installFetch(route(summary({ live: 1 })));
    renderApp('/admin');
    expect(await within(rail()).findByRole('link', { name: 'Playback, 1 live' })).toHaveTextContent(
      'Playback1 live',
    );
  });

  it('shows the counts on every section, not just To do', async () => {
    installFetch();
    renderApp('/admin/settings');

    expect(await within(rail()).findByRole('link', { name: 'To do, 6 things' })).toBeVisible();
  });
});
