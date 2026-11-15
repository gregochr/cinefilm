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

  it('reads the summary exactly once on a normal load, shared by the rail and the page', async () => {
    const fetchMock = installFetch();
    renderApp('/admin');
    await within(rail()).findByRole('link', { name: 'To do, 6 things' });
    await screen.findByRole('region', { name: 'Films needing attention' });
    const reads = () => fetchMock.mock.calls.filter(([url]) => url === '/api/admin/summary').length;
    expect(reads()).toBe(1);

    await userEvent.click(within(rail()).getByRole('link', { name: 'Library' }));
    await screen.findByRole('heading', { level: 1, name: 'Library' });

    expect(reads()).toBe(1);
    expect(within(rail()).getByRole('link', { name: 'To do, 6 things' })).toBeInTheDocument();
  });

  it('waits for the scan status before reading the summary', async () => {
    let releaseScan;
    const scanHeld = new Promise((resolve) => {
      releaseScan = resolve;
    });
    const fetchMock = installFetch((method, url) =>
      url === '/api/admin/scan' ? scanHeld.then(() => respond(fixtures.adminScan)) : null,
    );
    renderApp('/admin');
    await screen.findByRole('region', { name: 'Films needing attention' });
    const reads = () => fetchMock.mock.calls.filter(([url]) => url === '/api/admin/summary').length;
    expect(reads()).toBe(0);

    releaseScan();

    await within(rail()).findByRole('link', { name: 'To do, 6 things' });
    expect(reads()).toBe(1);
  });

  it('does not wait for Plex: with /api/admin/plex never answering the counts show after one read', async () => {
    const fetchMock = installFetch((method, url) =>
      url === '/api/admin/plex' ? new Promise(() => {}) : null,
    );
    renderApp('/admin');

    await within(rail()).findByRole('link', { name: 'To do, 6 things' });
    await screen.findByRole('region', { name: 'Films needing attention' });
    const reads = fetchMock.mock.calls.filter(([url]) => url === '/api/admin/summary').length;
    expect(reads).toBe(1);
  });

  it('reads once when the scan status fails: the error settles the wait', async () => {
    const fetchMock = installFetch((method, url) =>
      url === '/api/admin/scan' ? respond({ error: 'boom' }, 500) : null,
    );
    renderApp('/admin');

    await within(rail()).findByRole('link', { name: 'To do, 6 things' });
    await screen.findByRole('region', { name: 'Films needing attention' });
    const reads = fetchMock.mock.calls.filter(([url]) => url === '/api/admin/summary').length;
    expect(reads).toBe(1);
  });

  it('after a failed scan status, a Fix match reads the summary once more (readiness does not flip back)', async () => {
    const fetchMock = installFetch((method, url) =>
      url === '/api/admin/scan' ? respond({ error: 'boom' }, 500) : null,
    );
    renderApp('/admin');
    await within(rail()).findByRole('link', { name: 'To do, 6 things' });
    const reads = () => fetchMock.mock.calls.filter(([url]) => url === '/api/admin/summary').length;
    const attention = await screen.findByRole('region', { name: 'Films needing attention' });
    await userEvent.click(
      (await within(attention).findAllByRole('button', { name: 'Find the film…' }))[0],
    );
    const panel = await screen.findByRole('region', { name: 'Fix match' });
    await userEvent.click((await within(panel).findAllByRole('radio'))[0]);

    await userEvent.click(within(panel).getByRole('button', { name: 'Use this match' }));

    await waitFor(() => expect(reads()).toBe(2));
    await waitFor(() =>
      expect(screen.queryByRole('region', { name: 'Fix match' })).not.toBeInTheDocument(),
    );
    expect(reads()).toBe(2);
    expect(within(rail()).getByRole('link', { name: 'To do, 6 things' })).toBeInTheDocument();
  });

  it('reads the summary again after a Fix match is applied: the To do count drops by one', async () => {
    let reads = 0;
    installFetch((method, url) => {
      if (url !== '/api/admin/summary') return null;
      reads += 1;
      const todo = { ...fixtures.adminSummary.todo };
      if (reads > 1) todo.pickFilm -= 1;
      return respond({ ...fixtures.adminSummary, todo });
    });
    renderApp('/admin');
    await within(rail()).findByRole('link', { name: 'To do, 6 things' });
    const attention = await screen.findByRole('region', { name: 'Films needing attention' });
    await userEvent.click(
      (await within(attention).findAllByRole('button', { name: 'Find the film…' }))[0],
    );
    const panel = await screen.findByRole('region', { name: 'Fix match' });
    await userEvent.click((await within(panel).findAllByRole('radio'))[0]);

    await userEvent.click(within(panel).getByRole('button', { name: 'Use this match' }));

    expect(
      await within(rail()).findByRole('link', { name: 'To do, 5 things' }),
    ).toBeInTheDocument();
    expect(screen.getByText('5 things need you')).toBeInTheDocument();
  });

  it('keeps the counts, the heading count and the cards when a re-read fails, with no alert', async () => {
    let reads = 0;
    installFetch((method, url) => {
      if (url !== '/api/admin/summary') return null;
      reads += 1;
      return reads === 1 ? respond(fixtures.adminSummary) : respond({ error: 'boom' }, 500);
    });
    renderApp('/admin');
    await within(rail()).findByRole('link', { name: 'To do, 6 things' });
    const attention = await screen.findByRole('region', { name: 'Films needing attention' });
    await userEvent.click(
      (await within(attention).findAllByRole('button', { name: 'Find the film…' }))[0],
    );
    const panel = await screen.findByRole('region', { name: 'Fix match' });
    await userEvent.click((await within(panel).findAllByRole('radio'))[0]);
    await userEvent.click(within(panel).getByRole('button', { name: 'Use this match' }));
    await waitFor(() => expect(reads).toBe(2));
    await waitFor(() =>
      expect(screen.queryByRole('region', { name: 'Fix match' })).not.toBeInTheDocument(),
    );

    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(within(rail()).getByRole('link', { name: 'To do, 6 things' })).toBeInTheDocument();
    expect(screen.getByText('6 things need you')).toBeInTheDocument();
    expect(screen.getByText('538 films · scanned 12 min ago')).toBeInTheDocument();
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

  it('shows no counts when the first read fails', async () => {
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
