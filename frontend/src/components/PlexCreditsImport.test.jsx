import { act, render, renderHook, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { SCAN_POLL_MS, usePlexCreditsImport } from '../state/useAdmin.js';
import { fixtures, installFetch, respond } from '../test/mockApi.jsx';
import PlexCreditsImport from './PlexCreditsImport.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

const IDLE = { running: false, progress: null, lastImport: null };
const running = (processed, total = 40) => ({
  running: true,
  progress: { processed, total },
  lastImport: null,
});

const statusRoute = (body) => (m, url) =>
  url === '/api/admin/plex/credits' ? respond(body) : null;

const block = () => screen.findByRole('group', { name: 'Credits from Plex' });

describe('PlexCreditsImport', () => {
  it('says Not run yet before the first import, shows no report and offers the button', async () => {
    installFetch(statusRoute(IDLE));
    render(<PlexCreditsImport reachable />);
    const group = await block();

    expect(within(group).getByText('Not run yet')).toBeInTheDocument();
    expect(within(group).queryByRole('list', { name: 'Credits import report' })).toBeNull();
    expect(within(group).getByRole('button', { name: 'Import credits from Plex' })).toBeEnabled();
  });

  it('says what it does and that nothing is changed', async () => {
    installFetch(statusRoute(IDLE));
    render(<PlexCreditsImport reachable />);
    const group = await block();

    expect(within(group).getByText(/Nothing is changed in Plex/)).toBeInTheDocument();
  });

  it('shows the last import with its counts and what was not stored', async () => {
    installFetch(statusRoute(fixtures.adminPlexCredits));
    render(<PlexCreditsImport reachable />);
    const group = await block();

    expect(await within(group).findByText('13 Nov 14:20 · 860 markers stored')).toBeInTheDocument();
    const tiles = within(group).getByRole('list', { name: 'Credits import report' });
    expect(within(tiles).getByText('Plex films').nextSibling).toHaveTextContent('1087');
    expect(within(tiles).getByText('Matched').nextSibling).toHaveTextContent('1030');
    expect(within(tiles).getByText('Stored').nextSibling).toHaveTextContent('860');
    expect(within(tiles).getByText('Unmatched files').nextSibling).toHaveTextContent('2');
    const skipped = within(group).getByRole('list', { name: 'Not stored' });
    expect(
      within(skipped)
        .getAllByRole('listitem')
        .map((item) => item.textContent),
    ).toEqual(['Films split into parts: 3', 'Films whose versions differ in length: 1']);
  });

  it('leaves out the not-stored list when nothing was skipped', async () => {
    const report = { ...fixtures.adminPlexCredits.lastImport.report, skipped: [] };
    installFetch(
      statusRoute({
        ...fixtures.adminPlexCredits,
        lastImport: { ...fixtures.adminPlexCredits.lastImport, report },
      }),
    );
    render(<PlexCreditsImport reachable />);
    const group = await block();

    await within(group).findByRole('list', { name: 'Credits import report' });
    expect(within(group).queryByRole('list', { name: 'Not stored' })).toBeNull();
  });

  it('shows the error of an import that stopped, with no report', async () => {
    installFetch(
      statusRoute({
        ...IDLE,
        lastImport: {
          startedAt: '2026-11-13T14:00:00Z',
          finishedAt: '2026-11-13T14:20:00Z',
          error: 'Plex is not reachable',
          report: null,
        },
      }),
    );
    render(<PlexCreditsImport reachable />);
    const group = await block();

    expect(
      await within(group).findByText('13 Nov 14:20 · Plex is not reachable'),
    ).toBeInTheDocument();
    expect(within(group).queryByRole('list', { name: 'Credits import report' })).toBeNull();
  });

  it('disables the button when Plex is not reachable', async () => {
    installFetch(statusRoute(IDLE));
    render(<PlexCreditsImport reachable={false} />);
    const group = await block();

    expect(within(group).getByRole('button', { name: 'Import credits from Plex' })).toBeDisabled();
  });

  it('starts an import with one POST and then shows live progress with the button disabled', async () => {
    let started = false;
    const fetchMock = installFetch((method, url) => {
      if (url === '/api/admin/plex/credits/import' && method === 'POST') {
        started = true;
        return respond(running(0, 0), 202);
      }
      if (url === '/api/admin/plex/credits') return respond(started ? running(12) : IDLE);
      return null;
    });
    render(<PlexCreditsImport reachable />);
    const group = await block();

    await userEvent.click(within(group).getByRole('button', { name: 'Import credits from Plex' }));

    expect(await within(group).findByText('12 / 40 films')).toBeInTheDocument();
    expect(within(group).getByRole('button', { name: 'Importing credits…' })).toBeDisabled();
    const bar = within(group).getByRole('progressbar', { name: 'Credits import progress' });
    expect(bar).toHaveAttribute('aria-valuenow', '30');
    expect(bar).toHaveAttribute('aria-valuetext', '12 / 40 films');
    expect(
      fetchMock.mock.calls.filter(
        (c) => c[0] === '/api/admin/plex/credits/import' && c[1]?.method === 'POST',
      ),
    ).toHaveLength(1);
  });

  it('shows an import that is already running when it opens, with a neutral line before the total is known', async () => {
    installFetch(statusRoute(running(0, 0)));
    render(<PlexCreditsImport reachable />);
    const group = await block();

    expect(await within(group).findByText('Reading Plex…')).toBeInTheDocument();
    expect(within(group).getByRole('button', { name: 'Importing credits…' })).toBeDisabled();
  });

  it('says so when one is already running (409) and shows no error', async () => {
    installFetch((method, url) =>
      url === '/api/admin/plex/credits/import'
        ? respond({ error: 'A credits import is already running' }, 409)
        : statusRoute(IDLE)(method, url),
    );
    render(<PlexCreditsImport reachable />);
    const group = await block();

    await userEvent.click(within(group).getByRole('button', { name: 'Import credits from Plex' }));

    expect(
      await within(group).findByText('A credits import is already running.'),
    ).toBeInTheDocument();
  });

  it('says when the import could not be started', async () => {
    installFetch((method, url) =>
      url === '/api/admin/plex/credits/import'
        ? respond({ error: 'boom' }, 500)
        : statusRoute(IDLE)(method, url),
    );
    render(<PlexCreditsImport reachable />);
    const group = await block();

    await userEvent.click(within(group).getByRole('button', { name: 'Import credits from Plex' }));

    expect(
      await within(group).findByText('Could not start the credits import.'),
    ).toBeInTheDocument();
    expect(within(group).getByRole('button', { name: 'Import credits from Plex' })).toBeEnabled();
  });
});

describe('usePlexCreditsImport', () => {
  const settle = (ms = 0) => act(() => vi.advanceTimersByTimeAsync(ms));

  it('does not poll while idle and polls every 1.5 s while running, then stops', async () => {
    let calls = 0;
    const fetchMock = installFetch((method, url) => {
      if (url !== '/api/admin/plex/credits') return null;
      calls += 1;
      return respond(calls < 3 ? running(calls * 10) : IDLE);
    });
    vi.useFakeTimers();
    const { result, unmount } = renderHook(() => usePlexCreditsImport());
    await settle();
    expect(result.current.credits.progress.processed).toBe(10);

    await settle(SCAN_POLL_MS - 1);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    await settle(1);
    expect(result.current.credits.progress.processed).toBe(20);
    await settle(SCAN_POLL_MS);
    expect(result.current.credits.running).toBe(false);
    await settle(SCAN_POLL_MS * 4);

    expect(fetchMock).toHaveBeenCalledTimes(3);
    unmount();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('leaves no timer behind when it unmounts while an import runs', async () => {
    installFetch(statusRoute(running(5)));
    vi.useFakeTimers();
    const { unmount } = renderHook(() => usePlexCreditsImport());
    await settle();
    expect(vi.getTimerCount()).toBeGreaterThan(0);

    unmount();

    expect(vi.getTimerCount()).toBe(0);
  });

  it('ignores a second start while the first is still in flight', async () => {
    let release;
    const gate = new Promise((resolve) => {
      release = resolve;
    });
    const fetchMock = installFetch(async (method, url) => {
      if (url === '/api/admin/plex/credits/import') {
        await gate;
        return respond(running(0, 0), 202);
      }
      return statusRoute(IDLE)(method, url);
    });
    const { result } = renderHook(() => usePlexCreditsImport());
    await waitFor(() => expect(result.current.credits).toBeDefined());

    let first;
    let second;
    await act(async () => {
      first = result.current.startImport();
      second = result.current.startImport();
      release();
      await Promise.all([first, second]);
    });

    expect(
      fetchMock.mock.calls.filter(
        (c) => c[0] === '/api/admin/plex/credits/import' && c[1]?.method === 'POST',
      ),
    ).toHaveLength(1);
    expect(result.current.starting).toBe(false);
  });

  it('clears an old notice on the next start and starts again after a failure', async () => {
    let fail = true;
    const fetchMock = installFetch((method, url) => {
      if (url === '/api/admin/plex/credits/import') {
        return fail ? respond({ error: 'boom' }, 500) : respond(running(0, 0), 202);
      }
      return statusRoute(IDLE)(method, url);
    });
    const { result } = renderHook(() => usePlexCreditsImport());
    await waitFor(() => expect(result.current.credits).toBeDefined());
    await act(() => result.current.startImport());
    expect(result.current.notice).toBe('Could not start the credits import.');

    fail = false;
    await act(() => result.current.startImport());

    expect(result.current.notice).toBeNull();
    expect(
      fetchMock.mock.calls.filter(
        (c) => c[0] === '/api/admin/plex/credits/import' && c[1]?.method === 'POST',
      ),
    ).toHaveLength(2);
  });
});
