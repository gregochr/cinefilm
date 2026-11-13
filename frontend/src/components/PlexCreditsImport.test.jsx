import { StrictMode } from 'react';
import { act, render, renderHook, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LIBRARY_CHANGED_EVENT } from '../api/client.js';
import { CREDITS_READ_TIMEOUT_MS, SCAN_POLL_MS, usePlexCreditsImport } from '../state/useAdmin.js';
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

  it('shows that it is loading, and keeps the button off until the status is known', async () => {
    installFetch(() => new Promise(() => {}));
    render(<PlexCreditsImport reachable />);
    const group = await block();

    expect(
      within(group).getByRole('status', { name: 'Loading credits import' }),
    ).toBeInTheDocument();
    expect(within(group).queryByText('Not run yet')).toBeNull();
    expect(within(group).getByRole('button', { name: 'Import credits from Plex' })).toBeDisabled();
  });

  it('says the status could not be loaded instead of Not run yet, and recovers on Check again', async () => {
    let fail = true;
    installFetch((m, url) =>
      url === '/api/admin/plex/credits' ? respond(fail ? null : IDLE, fail ? 500 : 200) : null,
    );
    render(<PlexCreditsImport reachable />);
    const group = await block();

    expect(
      await within(group).findByText('Couldn\u2019t load the credits import status.'),
    ).toBeInTheDocument();
    expect(within(group).queryByText('Not run yet')).toBeNull();
    expect(within(group).getByRole('button', { name: 'Import credits from Plex' })).toBeDisabled();

    fail = false;
    await userEvent.click(within(group).getByRole('button', { name: 'Check again' }));

    expect(await within(group).findByText('Not run yet')).toBeInTheDocument();
    expect(within(group).getByRole('button', { name: 'Import credits from Plex' })).toBeEnabled();
    expect(within(group).queryByRole('button', { name: 'Check again' })).toBeNull();
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
    vi.useFakeTimers();
    render(<PlexCreditsImport reachable />);
    await act(() => vi.advanceTimersByTimeAsync(0));
    const group = screen.getByRole('group', { name: 'Credits from Plex' });

    expect(within(group).getByText('Reading Plex…')).toBeInTheDocument();
    expect(within(group).getByRole('button', { name: 'Importing credits…' })).toBeDisabled();
  });

  it('falls back to its own words when a 409 carries no reason', async () => {
    installFetch((method, url) =>
      url === '/api/admin/plex/credits/import'
        ? respond(null, 409)
        : statusRoute(IDLE)(method, url),
    );
    render(<PlexCreditsImport reachable />);
    const group = await block();

    await userEvent.click(within(group).getByRole('button', { name: 'Import credits from Plex' }));

    expect(
      await within(group).findByText('A credits import is already running.'),
    ).toBeInTheDocument();
  });

  it('says the status could not be refreshed while still showing the last one it had', async () => {
    let fail = false;
    installFetch((m, url) => {
      if (url === '/api/admin/plex/credits/import') {
        return respond({ ...fixtures.adminPlexCredits, running: true }, 202);
      }
      return url === '/api/admin/plex/credits'
        ? respond(fail ? null : fixtures.adminPlexCredits, fail ? 500 : 200)
        : null;
    });
    render(<PlexCreditsImport reachable />);
    const group = await block();
    await within(group).findByRole('list', { name: 'Credits import report' });

    fail = true;
    await userEvent.click(within(group).getByRole('button', { name: 'Import credits from Plex' }));

    expect(
      await within(group).findByText('Couldn\u2019t refresh the credits import status.'),
    ).toBeInTheDocument();
    expect(within(group).getByRole('list', { name: 'Credits import report' })).toBeInTheDocument();
  });

  it('keeps Check again focused when there is no status yet and it looks again', async () => {
    let reads = 0;
    let release;
    const gate = new Promise((resolve) => {
      release = resolve;
    });
    installFetch(async (m, url) => {
      if (url !== '/api/admin/plex/credits') return null;
      reads += 1;
      if (reads === 1) return respond(null, 500);
      await gate;
      return respond(IDLE);
    });
    render(<PlexCreditsImport reachable />);
    const group = await block();
    const again = await within(group).findByRole('button', { name: 'Check again' });

    await userEvent.click(again);

    expect(reads).toBe(2);
    expect(within(group).getByRole('button', { name: 'Checking\u2026' })).toBe(again);
    expect(again).toHaveAttribute('aria-busy', 'true');
    expect(again).toHaveFocus();
    release();
    expect(await within(group).findByText('Not run yet')).toBeInTheDocument();
  });

  it('keeps Check again where it is, and focused, while a refresh looks again', async () => {
    let reads = 0;
    let release;
    const gate = new Promise((resolve) => {
      release = resolve;
    });
    installFetch(async (m, url) => {
      if (url !== '/api/admin/plex/credits') return null;
      reads += 1;
      if (reads === 1) return respond(fixtures.adminPlexCredits);
      if (reads === 2) return respond(null, 500);
      await gate;
      return respond(fixtures.adminPlexCredits);
    });
    render(<PlexCreditsImport reachable />);
    const group = await block();
    await within(group).findByRole('list', { name: 'Credits import report' });
    await userEvent.click(within(group).getByRole('button', { name: 'Import credits from Plex' }));
    const again = await within(group).findByRole('button', { name: 'Check again' });

    await userEvent.click(again);

    expect(reads).toBe(3);
    expect(within(group).getByRole('button', { name: 'Checking\u2026' })).toBe(again);
    expect(again).toHaveFocus();
    release();
    await waitFor(() => expect(within(group).queryByRole('alert')).toBeNull());
  });

  it('shows the import running from the start’s answer even when the next status read fails', async () => {
    let started = false;
    installFetch((method, url) => {
      if (url === '/api/admin/plex/credits/import') {
        started = true;
        return respond(running(0, 0), 202);
      }
      if (url === '/api/admin/plex/credits') return started ? respond(null, 500) : respond(IDLE);
      return null;
    });
    render(<PlexCreditsImport reachable />);
    const group = await block();
    const button = within(group).getByRole('button', { name: 'Import credits from Plex' });
    await waitFor(() => expect(button).toBeEnabled());

    await userEvent.click(button);

    expect(await within(group).findByText('Reading Plex…')).toBeInTheDocument();
    expect(within(group).getByRole('button', { name: 'Importing credits…' })).toBeDisabled();
    expect(
      await within(group).findByText('Couldn\u2019t refresh the credits import status.'),
    ).toBeInTheDocument();
  });

  it('announces in a status region when an import finishes, not for the first status', async () => {
    let calls = 0;
    installFetch((m, url) => {
      if (url !== '/api/admin/plex/credits') return null;
      calls += 1;
      return respond(calls === 1 ? running(5) : fixtures.adminPlexCredits);
    });
    vi.useFakeTimers();
    render(<PlexCreditsImport reachable />);
    await act(() => vi.advanceTimersByTimeAsync(0));
    expect(screen.getByText('5 / 40 films')).toBeInTheDocument();
    expect(screen.queryByText(/^Credits import (finished|stopped)/)).toBeNull();

    await act(() => vi.advanceTimersByTimeAsync(SCAN_POLL_MS));
    await act(() => vi.advanceTimersByTimeAsync(0));

    const sentence = screen.getByText('Credits import finished: 860 imported.');
    expect(sentence.closest('[role="status"]')).not.toBeNull();
  });

  it('keeps the same alert mounted while polls keep failing, so it is not announced again', async () => {
    let reads = 0;
    installFetch((method, url) => {
      if (url !== '/api/admin/plex/credits') return null;
      reads += 1;
      return reads === 1 ? respond(running(5)) : respond(null, 500);
    });
    vi.useFakeTimers();
    render(<PlexCreditsImport reachable />);
    await act(() => vi.advanceTimersByTimeAsync(0));
    await act(() => vi.advanceTimersByTimeAsync(SCAN_POLL_MS));
    const alert = screen.getByRole('alert');
    const again = screen.getByRole('button', { name: 'Check again' });

    for (let poll = 0; poll < 3; poll += 1) {
      await act(() => vi.advanceTimersByTimeAsync(SCAN_POLL_MS));
    }

    expect(reads).toBe(5);
    expect(screen.getByRole('alert')).toBe(alert);
    expect(screen.getByRole('button', { name: 'Check again' })).toBe(again);
  });

  it('says nothing for a finished import that was already there when the block opened', async () => {
    installFetch(statusRoute(fixtures.adminPlexCredits));
    render(<PlexCreditsImport reachable />);
    const group = await block();
    await within(group).findByRole('list', { name: 'Credits import report' });

    expect(within(group).queryByText(/^Credits import finished/)).toBeNull();
  });

  it('shows the server’s text when a credits import is already running (409)', async () => {
    installFetch((method, url) =>
      url === '/api/admin/plex/credits/import'
        ? respond({ error: 'A credits import is already running.' }, 409)
        : statusRoute(IDLE)(method, url),
    );
    render(<PlexCreditsImport reachable />);
    const group = await block();
    const button = within(group).getByRole('button', { name: 'Import credits from Plex' });
    await waitFor(() => expect(button).toBeEnabled());

    await userEvent.click(button);

    expect(
      await within(group).findByText('A credits import is already running.'),
    ).toBeInTheDocument();
  });

  it('falls back to its own words when the 409 reason is an empty string', async () => {
    installFetch((method, url) =>
      url === '/api/admin/plex/credits/import'
        ? respond({ error: '' }, 409)
        : statusRoute(IDLE)(method, url),
    );
    render(<PlexCreditsImport reachable />);
    const group = await block();
    const button = within(group).getByRole('button', { name: 'Import credits from Plex' });
    await waitFor(() => expect(button).toBeEnabled());

    await userEvent.click(button);

    expect(
      await within(group).findByText('A credits import is already running.'),
    ).toBeInTheDocument();
  });

  it('shows the server’s own reason when the Plex import is running (409)', async () => {
    installFetch((method, url) =>
      url === '/api/admin/plex/credits/import'
        ? respond({ error: 'Import credits after the Plex import finishes.' }, 409)
        : statusRoute(IDLE)(method, url),
    );
    render(<PlexCreditsImport reachable />);
    const group = await block();
    const button = within(group).getByRole('button', { name: 'Import credits from Plex' });
    await waitFor(() => expect(button).toBeEnabled());

    await userEvent.click(button);

    expect(
      await within(group).findByText('Import credits after the Plex import finishes.'),
    ).toBeInTheDocument();
    expect(within(group).queryByText('A credits import is already running.')).toBeNull();
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

  it('announces a library change when an import finishes, but not for the first status', async () => {
    const changed = vi.fn();
    window.addEventListener(LIBRARY_CHANGED_EVENT, changed);
    let calls = 0;
    installFetch((m, url) => {
      if (url !== '/api/admin/plex/credits') return null;
      calls += 1;
      return respond(calls === 1 ? running(5) : fixtures.adminPlexCredits);
    });
    vi.useFakeTimers();
    renderHook(() => usePlexCreditsImport());
    await settle();
    expect(changed).not.toHaveBeenCalled();

    await settle(SCAN_POLL_MS);

    expect(changed).toHaveBeenCalledTimes(1);
    window.removeEventListener(LIBRARY_CHANGED_EVENT, changed);
  });

  it('keeps polling after a start whose next reads fail, one read at a time, until one answers', async () => {
    let started = false;
    let reads = 0;
    installFetch((method, url) => {
      if (url === '/api/admin/plex/credits/import') {
        started = true;
        return respond(running(0, 0), 202);
      }
      if (url !== '/api/admin/plex/credits') return null;
      if (!started) return respond(IDLE);
      reads += 1;
      return reads < 3 ? respond(null, 500) : respond(running(9));
    });
    vi.useFakeTimers();
    const { result } = renderHook(() => usePlexCreditsImport());
    await settle();
    await act(() => result.current.startImport());
    await settle();
    expect(reads).toBe(1);
    expect(result.current.credits.running).toBe(true);
    expect(result.current.error).not.toBeNull();

    await settle(SCAN_POLL_MS - 1);
    expect(reads).toBe(1);
    await settle(1);
    expect(reads).toBe(2);
    expect(result.current.error).not.toBeNull();
    await settle(SCAN_POLL_MS);
    expect(reads).toBe(3);

    expect(result.current.error).toBeNull();
    expect(result.current.credits.progress.processed).toBe(9);
  });

  it('stops showing the start’s status once a read answers, even one that looks like the start', async () => {
    let started = false;
    let reads = 0;
    installFetch((method, url) => {
      if (url === '/api/admin/plex/credits/import') {
        started = true;
        return respond(running(0, 0), 202);
      }
      if (url !== '/api/admin/plex/credits') return null;
      if (started) reads += 1;
      // The server restarted mid-run: it is idle again with nothing recorded, like the start's own answer.
      return respond(IDLE);
    });
    vi.useFakeTimers();
    const { result } = renderHook(() => usePlexCreditsImport());
    await settle();
    await act(() => result.current.startImport());
    await settle();

    expect(reads).toBe(1);
    expect(result.current.credits.running).toBe(false);
    await settle(SCAN_POLL_MS * 3);
    expect(reads).toBe(1);
  });

  it('does not let the next poll abort a read that is still in flight', async () => {
    let release;
    const gate = new Promise((resolve) => {
      release = resolve;
    });
    let reads = 0;
    installFetch(async (method, url) => {
      if (url === '/api/admin/plex/credits/import') return respond(running(0, 0), 202);
      if (url !== '/api/admin/plex/credits') return null;
      reads += 1;
      if (reads === 2) {
        await gate;
        return respond(running(7));
      }
      return respond(IDLE);
    });
    vi.useFakeTimers();
    const { result } = renderHook(() => usePlexCreditsImport());
    await settle();
    await act(() => result.current.startImport());

    await settle(SCAN_POLL_MS * 3);
    expect(reads).toBe(2);
    release();
    await settle();

    expect(result.current.credits.progress.processed).toBe(7);
    await settle(SCAN_POLL_MS);
    expect(reads).toBe(3);
  });

  it('does not let a pending poll timer abort the read Check again just issued', async () => {
    let release;
    const gate = new Promise((resolve) => {
      release = resolve;
    });
    let reads = 0;
    installFetch(async (method, url) => {
      if (url !== '/api/admin/plex/credits') return null;
      reads += 1;
      if (reads === 1) return respond(running(1));
      if (reads === 2) return respond(null, 500);
      await gate;
      return respond(running(8));
    });
    vi.useFakeTimers();
    const { result } = renderHook(() => usePlexCreditsImport());
    await settle();
    await settle(SCAN_POLL_MS);
    expect(reads).toBe(2);
    expect(result.current.error).not.toBeNull();

    await settle(SCAN_POLL_MS / 2);
    act(() => result.current.reload());
    await settle(SCAN_POLL_MS * 2);
    expect(reads).toBe(3);
    release();
    await settle();

    expect(result.current.credits.progress.processed).toBe(8);
    expect(result.current.error).toBeNull();
  });

  it('lets Check again replace a pending poll read, and no poll timer aborts the new read', async () => {
    let releasePoll;
    const pollGate = new Promise((resolve) => {
      releasePoll = resolve;
    });
    let releaseCheck;
    const checkGate = new Promise((resolve) => {
      releaseCheck = resolve;
    });
    let reads = 0;
    installFetch(async (method, url) => {
      if (url !== '/api/admin/plex/credits') return null;
      reads += 1;
      if (reads === 2) {
        await pollGate;
        return respond(running(2));
      }
      if (reads === 3) {
        await checkGate;
        return respond(running(3));
      }
      return respond(running(reads));
    });
    vi.useFakeTimers();
    const { result } = renderHook(() => usePlexCreditsImport());
    await settle();
    await settle(SCAN_POLL_MS);
    expect(reads).toBe(2);

    act(() => result.current.reload());
    await settle();
    expect(reads).toBe(3);
    releasePoll();
    await settle(SCAN_POLL_MS * 3);
    expect(reads).toBe(3);
    releaseCheck();
    await settle();

    expect(result.current.credits.progress.processed).toBe(3);
  });

  it('keeps the start’s status when a read called before the start answers in the same batch', async () => {
    let releaseOld;
    const oldGate = new Promise((resolve) => {
      releaseOld = resolve;
    });
    let releaseNew;
    const newGate = new Promise((resolve) => {
      releaseNew = resolve;
    });
    let reads = 0;
    installFetch(async (method, url) => {
      if (url === '/api/admin/plex/credits/import') return respond(running(0, 0), 202);
      if (url !== '/api/admin/plex/credits') return null;
      reads += 1;
      if (reads === 2) {
        await oldGate;
        return respond(IDLE);
      }
      if (reads === 3) {
        await newGate;
        return respond(IDLE);
      }
      return respond(IDLE);
    });
    vi.useFakeTimers();
    const { result } = renderHook(() => usePlexCreditsImport());
    await settle();
    act(() => result.current.reload());
    await settle();
    expect(reads).toBe(2);

    // The old read's answer and the start's 202 are applied in one batch, before React renders.
    await act(async () => {
      releaseOld();
      await result.current.startImport();
    });
    await settle();

    expect(reads).toBe(3);
    expect(result.current.credits.running).toBe(true);
    releaseNew();
    await settle();
    expect(result.current.credits.running).toBe(false);
  });

  it('does not arm the poll while a non-quiet read is loading', async () => {
    let release;
    const gate = new Promise((resolve) => {
      release = resolve;
    });
    let reads = 0;
    installFetch(async (method, url) => {
      if (url !== '/api/admin/plex/credits') return null;
      reads += 1;
      if (reads === 2) {
        await gate;
      }
      return respond(running(reads));
    });
    vi.useFakeTimers();
    const { result } = renderHook(() => usePlexCreditsImport());
    await settle();
    // The sign-in tick and "Check again" without a status are non-quiet reads: `loading` is true while they run.
    act(() => window.dispatchEvent(new CustomEvent('cinefilm:signed-in')));
    await settle();
    expect(result.current.loading).toBe(true);

    await settle(SCAN_POLL_MS * 3);
    expect(reads).toBe(2);
    release();
    await settle();
    await settle(SCAN_POLL_MS);

    expect(reads).toBe(3);
  });

  it('announces a repeat finish with the same words when the start answers already finished', async () => {
    const finished = (finishedAt) => ({
      running: false,
      progress: null,
      lastImport: { ...fixtures.adminPlexCredits.lastImport, finishedAt },
    });
    const script = [
      finished('2026-11-13T10:10:00Z'),
      finished('2026-11-13T11:10:00Z'),
      finished('2026-11-13T12:10:00Z'),
    ];
    let posts = 0;
    installFetch((method, url) => {
      if (url === '/api/admin/plex/credits/import') return respond(script[++posts], 202);
      if (url !== '/api/admin/plex/credits') return null;
      return respond(script[posts]);
    });
    vi.useFakeTimers();
    const { result } = renderHook(() => usePlexCreditsImport());
    await settle();
    const sentence = 'Credits import finished: 860 imported.';

    await act(() => result.current.startImport());
    expect(result.current.announcement).toBe('');
    await settle();
    expect(result.current.announcement).toBe(sentence);

    await act(() => result.current.startImport());
    expect(result.current.announcement).toBe('');
    await settle();
    expect(result.current.announcement).toBe(sentence);
  });

  it('announces every finish, even one with the same words, and clears it while the next run goes', async () => {
    const finished = (startedAt, finishedAt) => ({
      running: false,
      progress: null,
      lastImport: {
        ...fixtures.adminPlexCredits.lastImport,
        startedAt,
        finishedAt,
      },
    });
    const sentence = 'Credits import finished: 860 imported.';
    const script = [
      finished('2026-11-13T10:00:00Z', '2026-11-13T10:10:00Z'),
      running(3),
      finished('2026-11-13T11:00:00Z', '2026-11-13T11:10:00Z'),
      running(4),
      finished('2026-11-13T12:00:00Z', '2026-11-13T12:10:00Z'),
    ];
    let next = 0;
    installFetch((method, url) => {
      if (url === '/api/admin/plex/credits/import') return respond(running(0, 0), 202);
      if (url !== '/api/admin/plex/credits') return null;
      return respond(script[Math.min(next++, script.length - 1)]);
    });
    vi.useFakeTimers();
    const { result } = renderHook(() => usePlexCreditsImport());
    await settle();
    expect(result.current.announcement).toBe('');

    await act(() => result.current.startImport());
    await settle();
    expect(result.current.credits.running).toBe(true);
    expect(result.current.announcement).toBe('');
    await settle(SCAN_POLL_MS);
    await settle();
    expect(result.current.announcement).toBe(sentence);

    await act(() => result.current.startImport());
    await settle();
    expect(result.current.announcement).toBe('');
    await settle(SCAN_POLL_MS);
    await settle();
    expect(result.current.announcement).toBe(sentence);
  });

  it('stops saying it is checking when the read it started fails again', async () => {
    installFetch((m, url) => (url === '/api/admin/plex/credits' ? respond(null, 500) : null));
    vi.useFakeTimers();
    const { result } = renderHook(() => usePlexCreditsImport());
    await settle();
    const first = result.current.error;
    expect(first).not.toBeNull();

    act(() => result.current.reload());
    expect(result.current.checking).toBe(true);
    await settle();

    expect(result.current.error).not.toBe(first);
    expect(result.current.checking).toBe(false);
  });

  it('stops saying it is checking when the read it started times out', async () => {
    let reads = 0;
    installFetch((m, url) => {
      if (url !== '/api/admin/plex/credits') return null;
      reads += 1;
      return reads === 1 ? respond(null, 500) : new Promise(() => {});
    });
    vi.useFakeTimers();
    const { result } = renderHook(() => usePlexCreditsImport());
    await settle();
    act(() => result.current.reload());
    await settle(CREDITS_READ_TIMEOUT_MS - 1);
    expect(result.current.checking).toBe(true);

    await settle(1);

    expect(result.current.checking).toBe(false);
  });

  it('shows Check again, not busy, once the read it started has failed again', async () => {
    let reads = 0;
    let release;
    const gate = new Promise((resolve) => {
      release = resolve;
    });
    installFetch(async (m, url) => {
      if (url !== '/api/admin/plex/credits') return null;
      reads += 1;
      if (reads === 2) await gate;
      return respond(null, 500);
    });
    render(<PlexCreditsImport reachable />);
    const group = await block();
    await userEvent.click(await within(group).findByRole('button', { name: 'Check again' }));
    expect(within(group).getByRole('button', { name: 'Checking\u2026' })).toHaveAttribute(
      'aria-busy',
      'true',
    );

    release();

    const again = await within(group).findByRole('button', { name: 'Check again' });
    expect(again).toHaveAttribute('aria-busy', 'false');
  });

  it('aborts the underlying request when the read times out', async () => {
    const signals = [];
    installFetch((m, url, init) => {
      if (url !== '/api/admin/plex/credits') return null;
      signals.push(init.signal);
      return new Promise(() => {});
    });
    vi.useFakeTimers();
    renderHook(() => usePlexCreditsImport());
    await settle();
    expect(signals[0].aborted).toBe(false);

    await settle(CREDITS_READ_TIMEOUT_MS);

    expect(signals[0].aborted).toBe(true);
  });

  it('aborts the underlying request of a read that Check again replaces', async () => {
    const signals = [];
    installFetch((m, url, init) => {
      if (url !== '/api/admin/plex/credits') return null;
      signals.push(init.signal);
      return new Promise(() => {});
    });
    vi.useFakeTimers();
    const { result } = renderHook(() => usePlexCreditsImport());
    await settle();
    expect(signals).toHaveLength(1);
    expect(signals[0].aborted).toBe(false);

    act(() => result.current.reload());
    await settle();

    expect(signals).toHaveLength(2);
    expect(signals[0].aborted).toBe(true);
    expect(signals[1].aborted).toBe(false);
  });

  it('sends no live request for a read whose resource signal is already aborted (StrictMode)', async () => {
    const atCall = [];
    installFetch((m, url, init) => {
      if (url !== '/api/admin/plex/credits') return null;
      atCall.push(init.signal.aborted);
      return respond(IDLE);
    });
    vi.useFakeTimers();
    renderHook(() => usePlexCreditsImport(), { wrapper: StrictMode });
    await settle();

    expect(atCall).toEqual([true, false]);
  });

  it('ends a hung read with an error after the timeout, then polls again', async () => {
    let reads = 0;
    installFetch((method, url) => {
      if (url !== '/api/admin/plex/credits') return null;
      reads += 1;
      if (reads === 2) return new Promise(() => {});
      return respond(running(reads));
    });
    vi.useFakeTimers();
    const { result } = renderHook(() => usePlexCreditsImport());
    await settle();
    await settle(SCAN_POLL_MS);
    expect(reads).toBe(2);
    expect(result.current.error).toBeNull();

    await settle(CREDITS_READ_TIMEOUT_MS - 1);
    expect(result.current.error).toBeNull();
    await settle(1);
    expect(result.current.error).not.toBeNull();
    expect(result.current.credits.running).toBe(true);

    await settle(SCAN_POLL_MS);
    expect(reads).toBe(3);
    expect(result.current.error).toBeNull();
    expect(result.current.credits.progress.processed).toBe(3);
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
