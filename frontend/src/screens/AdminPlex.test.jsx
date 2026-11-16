import { act, renderHook, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { PLEX_RECHECK_MS, SCAN_POLL_MS, usePlexImport } from '../state/useAdmin.js';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

const running = (processed, total = 40, current = 'Heat') => ({
  ...fixtures.adminPlex,
  running: true,
  progress: { processed, total, current },
});

const plexRoute = (body) => (m, url) => (url === '/api/admin/plex' ? respond(body) : null);

describe('Admin: Import from Plex card', () => {
  it('is hidden when Plex is not configured', async () => {
    const fetchMock = installFetch((m, url) =>
      url === '/api/admin/plex' ? respond({ ...fixtures.adminPlex, configured: false }) : null,
    );
    renderApp('/admin/library');
    await screen.findByRole('region', { name: 'Library scan' });
    // Not before the Plex status has been asked for and has answered: an unarrived answer would pass too.
    await waitFor(() =>
      expect(fetchMock.mock.calls.some(([url]) => url === '/api/admin/plex')).toBe(true),
    );
    await act(async () => {});
    expect(screen.queryByRole('region', { name: 'Import from Plex' })).not.toBeInTheDocument();
  });

  it('shows the card with an error and Try again when the status cannot be loaded, then recovers', async () => {
    let fail = true;
    installFetch((m, url) =>
      url === '/api/admin/plex' ? (fail ? respond(null, 500) : respond(fixtures.adminPlex)) : null,
    );
    renderApp('/admin/library');
    const card = await screen.findByRole('region', { name: 'Import from Plex' });
    expect(
      await within(card).findByText('Couldn’t load the Plex import status.'),
    ).toBeInTheDocument();

    fail = false;
    await userEvent.click(within(card).getByRole('button', { name: 'Try again' }));

    expect(
      await screen.findByRole('button', { name: 'Import from Plex again' }),
    ).toBeInTheDocument();
    expect(screen.queryByText('Couldn’t load the Plex import status.')).toBeNull();
  });

  it('is not drawn while the status is still loading', () => {
    installFetch((m, url) => (url === '/api/admin/plex' ? new Promise(() => {}) : null));
    renderApp('/admin/library');
    expect(screen.queryByRole('region', { name: 'Import from Plex' })).toBeNull();
  });

  it('shows reachability, the last import and its report', async () => {
    installFetch(plexRoute(fixtures.adminPlex));
    renderApp('/admin/library');
    const panel = await screen.findByRole('region', { name: 'Import from Plex' });

    expect(within(panel).getByText('Plex server').nextSibling).toHaveTextContent('Reachable');
    expect(
      within(panel).getByText('Last import 08 Nov 14:09 · 1037 films paired'),
    ).toBeInTheDocument();
    expect(within(panel).getByText('Brought across').nextSibling).toHaveTextContent(
      '1037 paired · 378 watched · 143 resume points · 1030 posters',
    );
    expect(within(panel).queryByRole('list', { name: 'Import report' })).toBeNull();
    await userEvent.click(within(panel).getByRole('button', { name: 'Last report' }));
    const tiles = within(panel).getByRole('list', { name: 'Import report' });
    expect(within(tiles).getByText('Paired').nextSibling).toHaveTextContent('1037');
    expect(within(tiles).getByText('Watched').nextSibling).toHaveTextContent('378');
    expect(within(tiles).getByText('Resume points').nextSibling).toHaveTextContent('143');
    expect(within(tiles).getByText('Failed').nextSibling).toHaveTextContent('1');
    const again = within(panel).getByRole('button', { name: 'Import from Plex again' });
    expect(again).toBeEnabled();
    expect(again).toHaveAccessibleDescription(
      'Copies watched state, resume points and artwork; your newer CineFilm changes win.',
    );
  });

  it('lists the films not on disk with how many more there are, and the Plex collections', async () => {
    installFetch(plexRoute(fixtures.adminPlex));
    renderApp('/admin/library');
    const panel = await screen.findByRole('region', { name: 'Import from Plex' });
    await userEvent.click(within(panel).getByRole('button', { name: 'Last report' }));

    const missing = within(panel).getByRole('list', { name: 'In Plex, not on disk' });
    expect(
      within(missing)
        .getAllByRole('listitem')
        .map((i) => i.textContent),
    ).toEqual(['Gone Film (1999)', 'Another Gone Film']);
    expect(within(panel).getByText('and 48 more')).toBeInTheDocument();
    const collections = within(panel).getByRole('list', { name: 'Plex collections not imported' });
    expect(within(collections).getByText('Bond')).toBeInTheDocument();
    expect(within(panel).getByText('and 93 more')).toBeInTheDocument();
    expect(within(panel).getByRole('list', { name: 'Could not import' })).toHaveTextContent('Kes');
  });

  it('says it was not imported yet before the first import, and has no Last report button', async () => {
    installFetch(plexRoute({ ...fixtures.adminPlex, lastImport: null }));
    renderApp('/admin/library');
    const panel = await screen.findByRole('region', { name: 'Import from Plex' });
    expect(within(panel).getByText('Not imported yet')).toBeInTheDocument();
    expect(within(panel).getByText('Brought across').nextSibling).toHaveTextContent('Nothing yet');
    expect(within(panel).queryByRole('button', { name: 'Last report' })).toBeNull();
  });

  it('shows the error of an import that stopped, with no report', async () => {
    installFetch(
      plexRoute({
        ...fixtures.adminPlex,
        lastImport: {
          ...fixtures.adminPlex.lastImport,
          error: 'Plex is not reachable',
          report: null,
        },
      }),
    );
    renderApp('/admin/library');
    expect(
      await screen.findByText('Last import 08 Nov 14:09 · Plex is not reachable'),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Last report' })).toBeNull();
  });

  it('disables the button and says so when Plex is not reachable', async () => {
    installFetch(plexRoute({ ...fixtures.adminPlex, reachable: false }));
    renderApp('/admin/library');
    const panel = await screen.findByRole('region', { name: 'Import from Plex' });
    expect(within(panel).getByText('Plex server').nextSibling).toHaveTextContent('Not reachable');
    expect(within(panel).getByRole('button', { name: 'Import from Plex again' })).toBeDisabled();
  });

  it('offers Try connecting to Plex while Plex is unreachable, and Import is enabled once it answers', async () => {
    let reachable = false;
    installFetch((m, url) =>
      url === '/api/admin/plex' ? respond({ ...fixtures.adminPlex, reachable }) : null,
    );
    renderApp('/admin/library');
    const panel = await screen.findByRole('region', { name: 'Import from Plex' });
    expect(within(panel).getByRole('button', { name: 'Import from Plex again' })).toBeDisabled();

    expect(
      within(panel).getByRole('button', { name: 'Try connecting to Plex' }),
    ).toHaveAccessibleDescription('Checks whether the Plex server answers now.');

    reachable = true;
    await userEvent.click(within(panel).getByRole('button', { name: 'Try connecting to Plex' }));

    await waitFor(() =>
      expect(within(panel).getByRole('button', { name: 'Import from Plex again' })).toBeEnabled(),
    );
    expect(within(panel).getByText('Plex server').nextSibling).toHaveTextContent('Reachable');
    expect(
      within(panel).queryByRole('button', { name: 'Try connecting to Plex' }),
    ).not.toBeInTheDocument();
  });

  it('has no Try connecting to Plex while Plex is reachable or an import is running', async () => {
    installFetch(plexRoute(running(3)));
    renderApp('/admin/library');
    const panel = await screen.findByRole('region', { name: 'Import from Plex' });
    expect(
      within(panel).queryByRole('button', { name: 'Try connecting to Plex' }),
    ).not.toBeInTheDocument();
  });

  it('starts an import and shows live progress with the button disabled', async () => {
    let started = false;
    installFetch((method, url) => {
      if (url === '/api/admin/plex/import' && method === 'POST') {
        started = true;
        return respond(running(0, 0, null), 202);
      }
      if (url === '/api/admin/plex') return respond(started ? running(12) : fixtures.adminPlex);
      return null;
    });
    renderApp('/admin/library');
    const panel = await screen.findByRole('region', { name: 'Import from Plex' });

    await userEvent.click(within(panel).getByRole('button', { name: 'Import from Plex again' }));

    expect(await within(panel).findByText('Importing')).toBeInTheDocument();
    expect(within(panel).getByText('12 / 40 films')).toBeInTheDocument();
    expect(within(panel).getByRole('button', { name: 'Importing…' })).toBeDisabled();
  });

  it('says why when the import is refused with a 409', async () => {
    installFetch((method, url) =>
      url === '/api/admin/plex/import'
        ? respond({ error: 'Run a library scan first' }, 409)
        : plexRoute(fixtures.adminPlex)(method, url),
    );
    renderApp('/admin/library');
    await userEvent.click(await screen.findByRole('button', { name: 'Import from Plex again' }));
    expect(
      await screen.findByText('A scan or import is already running, or no scan has finished yet.'),
    ).toBeInTheDocument();
  });

  it('reports an import that could not be started', async () => {
    installFetch((method, url) =>
      url === '/api/admin/plex/import'
        ? respond({ error: 'boom' }, 500)
        : plexRoute(fixtures.adminPlex)(method, url),
    );
    renderApp('/admin/library');
    await userEvent.click(await screen.findByRole('button', { name: 'Import from Plex again' }));
    expect(await screen.findByText('Could not start the import.')).toBeInTheDocument();
  });

  it('shows progress for an import already running when the page opens', async () => {
    installFetch(plexRoute(running(12)));
    renderApp('/admin/library');
    const panel = await screen.findByRole('region', { name: 'Import from Plex' });

    expect(within(panel).getByText('Importing')).toBeInTheDocument();
    const bar = within(panel).getByRole('progressbar', { name: 'Import progress' });
    expect(bar).toHaveAttribute('aria-valuenow', '30');
    expect(bar).toHaveAttribute('aria-valuetext', '12 / 40 films');
    expect(within(panel).getByText('Now: Heat')).toBeInTheDocument();
    expect(within(panel).getByRole('button', { name: 'Importing…' })).toBeDisabled();
  });

  it('shows a neutral line and no current film while Plex is first read', async () => {
    installFetch(plexRoute(running(0, 0, null)));
    renderApp('/admin/library');
    expect(await screen.findByText('Reading Plex…')).toBeInTheDocument();
    expect(screen.queryByText(/^Now:/)).not.toBeInTheDocument();
  });
});

describe('Admin: the grouping report after an import', () => {
  it('reloads the grouping report when an import finishes', async () => {
    let imported = false;
    let reportCalls = 0;
    installFetch((method, url) => {
      if (url === '/api/admin/plex/import') {
        imported = true;
        return respond(running(0), 202);
      }
      if (url === '/api/admin/plex') {
        return respond(
          imported
            ? {
                ...fixtures.adminPlex,
                lastImport: {
                  ...fixtures.adminPlex.lastImport,
                  finishedAt: '2026-11-09T10:00:00Z',
                },
              }
            : fixtures.adminPlex,
        );
      }
      if (url === '/api/admin/grouping-report') {
        reportCalls += 1;
        return null;
      }
      return null;
    });
    renderApp('/admin/library');
    await screen.findByRole('region', { name: 'Import from Plex' });
    await screen.findByRole('button', { name: 'Scan library now' });
    await waitFor(() => expect(reportCalls).toBeGreaterThan(0));
    const before = reportCalls;

    await userEvent.click(screen.getByRole('button', { name: 'Import from Plex again' }));

    await waitFor(() => expect(reportCalls).toBeGreaterThan(before));
  });
});

describe('usePlexImport', () => {
  const settle = (ms = 0) => act(() => vi.advanceTimersByTimeAsync(ms));

  it('does not poll while idle and polls every 1.5 s while running', async () => {
    let calls = 0;
    const fetchMock = installFetch((method, url) => {
      if (url !== '/api/admin/plex') return null;
      calls += 1;
      return respond(calls < 3 ? running(calls * 10) : fixtures.adminPlex);
    });
    vi.useFakeTimers();
    const { result } = renderHook(() => usePlexImport());
    await settle();
    expect(result.current.plex.progress.processed).toBe(10);

    await settle(SCAN_POLL_MS);
    expect(result.current.plex.progress.processed).toBe(20);
    await settle(SCAN_POLL_MS);
    expect(result.current.plex.running).toBe(false);
    await settle(SCAN_POLL_MS * 4);
    // Only the import status calls: finishing also announces a library change, which refreshes the search index.
    expect(fetchMock.mock.calls.filter(([url]) => url === '/api/admin/plex')).toHaveLength(3);
  });

  it('starts with POST to the import route, then refetches, and clears an old notice', async () => {
    let fail = true;
    const fetchMock = installFetch((method, url) => {
      if (url === '/api/admin/plex/import') {
        return fail ? respond({ error: 'boom' }, 500) : respond(running(0), 202);
      }
      if (url === '/api/admin/plex') return respond(fixtures.adminPlex);
      return null;
    });
    const { result } = renderHook(() => usePlexImport());
    await waitFor(() => expect(result.current.plex).not.toBeNull());
    await act(() => result.current.startImport());
    expect(result.current.notice).toBe('Could not start the import.');

    fail = false;
    await act(() => result.current.startImport());

    expect(result.current.notice).toBeNull();
    expect(result.current.starting).toBe(false);
    expect(
      fetchMock.mock.calls.filter(
        (c) => c[0] === '/api/admin/plex/import' && c[1]?.method === 'POST',
      ),
    ).toHaveLength(2);
  });

  it('rechecks every 30 s while Plex is unreachable, stops once it is reachable, and leaves no timer', async () => {
    let calls = 0;
    const fetchMock = installFetch((method, url) => {
      if (url !== '/api/admin/plex') return null;
      calls += 1;
      return respond({ ...fixtures.adminPlex, reachable: calls >= 3 });
    });
    vi.useFakeTimers();
    const { result, unmount } = renderHook(() => usePlexImport());
    await settle();
    expect(result.current.plex.reachable).toBe(false);
    expect(PLEX_RECHECK_MS).toBe(30000);

    await settle(PLEX_RECHECK_MS - 1);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    await settle(1);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    await settle(PLEX_RECHECK_MS);
    expect(result.current.plex.reachable).toBe(true);
    expect(fetchMock).toHaveBeenCalledTimes(3);

    await settle(PLEX_RECHECK_MS * 4);
    expect(fetchMock).toHaveBeenCalledTimes(3);
    unmount();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('clears the recheck timer on unmount while still unreachable', async () => {
    const fetchMock = installFetch((m, url) =>
      url === '/api/admin/plex' ? respond({ ...fixtures.adminPlex, reachable: false }) : null,
    );
    vi.useFakeTimers();
    const { unmount } = renderHook(() => usePlexImport());
    await settle();
    expect(vi.getTimerCount()).toBeGreaterThan(0);

    unmount();
    await settle(PLEX_RECHECK_MS * 2);

    expect(vi.getTimerCount()).toBe(0);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('does not recheck when Plex is not configured', async () => {
    const fetchMock = installFetch((m, url) =>
      url === '/api/admin/plex'
        ? respond({
            configured: false,
            reachable: false,
            running: false,
            progress: null,
            lastImport: null,
          })
        : null,
    );
    vi.useFakeTimers();
    renderHook(() => usePlexImport());
    await settle();

    await settle(PLEX_RECHECK_MS * 3);

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
