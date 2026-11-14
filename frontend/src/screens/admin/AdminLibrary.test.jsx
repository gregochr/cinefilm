import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp, respond } from '../../test/mockApi.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
  delete Element.prototype.scrollIntoView;
});

const PLEX = {
  configured: true,
  reachable: true,
  running: false,
  progress: null,
  lastImport: null,
};

/** Holds one endpoint's answer until the test releases it. */
function held(url) {
  let release;
  const gate = new Promise((resolve) => {
    release = resolve;
  });
  return { url, gate, release: (response) => release(response) };
}

describe('Admin Library section', () => {
  it('is named Library and holds the scan, grouping report and Plex import cards', async () => {
    installFetch((m, url) => (url === '/api/admin/plex' ? respond(PLEX) : null));
    renderApp('/admin/library');
    expect(screen.getByRole('heading', { level: 1, name: 'Library' })).toBeInTheDocument();
    for (const name of ['Library scan', 'Grouping report', 'Plex import']) {
      expect(await screen.findByRole('region', { name })).toBeInTheDocument();
    }
    expect(screen.queryByRole('region', { name: 'Films needing attention' })).toBeNull();
  });

  it('shows one skeleton per loading card: the scan and the grouping report', () => {
    installFetch(() => new Promise(() => {}));
    renderApp('/admin/library');
    expect(screen.getAllByRole('status', { name: 'Loading' })).toHaveLength(2);
  });

  it('shows the scan error with Try again, and the grouping report error on its own', async () => {
    installFetch((m, url) =>
      url === '/api/admin/scan' || url === '/api/admin/grouping-report' ? respond(null, 500) : null,
    );
    renderApp('/admin/library');
    expect(await screen.findByText('Couldn’t load the library scan.')).toBeInTheDocument();
    expect(await screen.findByText('Couldn’t load the grouping report.')).toBeInTheDocument();
  });

  it('lists the grouping changes from the report', async () => {
    installFetch();
    renderApp('/admin/library');
    const report = await screen.findByRole('region', { name: 'Grouping report' });
    expect(
      await within(report).findByRole('list', { name: 'Grouping changes' }),
    ).toBeInTheDocument();
  });

  it('reads the grouping report again when a match applies while Library is open', async () => {
    let release;
    const post = new Promise((resolve) => {
      release = resolve;
    });
    let reads = 0;
    installFetch((m, url) => {
      if (m === 'POST' && /\/api\/admin\/films\/\d+\/match$/.test(url)) return post;
      if (url === '/api/admin/grouping-report') {
        reads += 1;
        return reads === 1
          ? null
          : respond({
              items: [{ ...fixtures.adminGrouping.items[0], summary: 'Joined after the match' }],
            });
      }
      return null;
    });
    renderApp('/admin');
    const opener = (await screen.findAllByRole('button', { name: 'Find the film…' }))[0];
    await userEvent.click(opener);
    const panel = await screen.findByRole('region', { name: 'Fix match' });
    await userEvent.click((await within(panel).findAllByRole('radio'))[0]);
    await userEvent.click(within(panel).getByRole('button', { name: 'Use this match' }));
    await userEvent.click(
      within(screen.getByRole('navigation', { name: 'Admin sections' })).getByRole('link', {
        name: 'Library',
      }),
    );
    await screen.findByRole('list', { name: 'Grouping changes' });
    expect(screen.queryByText('Joined after the match')).not.toBeInTheDocument();

    await act(async () => release(respond({ filmId: 31, joined: false })));

    expect(await screen.findByText('Joined after the match')).toBeInTheDocument();
  });
});

describe('Admin Library section: scrolling to an anchor', () => {
  it('#scan scrolls to the scan card once, even when the Plex status answers later', async () => {
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;
    const plex = held('/api/admin/plex');
    installFetch((m, url) => (url === plex.url ? plex.gate : null));
    renderApp('/admin/library#scan');
    const card = await screen.findByRole('region', { name: 'Library scan' });
    await waitFor(() => expect(scrollIntoView).toHaveBeenCalledTimes(1));

    await act(async () => plex.release(respond(PLEX)));
    await screen.findByRole('region', { name: 'Plex import' });
    await act(async () => {});

    expect(scrollIntoView).toHaveBeenCalledTimes(1);
    expect(scrollIntoView.mock.contexts[0]).toBe(card);
  });

  it('#plex-import scrolls to the Plex card once, even when the scan status answers later', async () => {
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;
    const scan = held('/api/admin/scan');
    installFetch((m, url) => {
      if (url === scan.url) return scan.gate;
      return url === '/api/admin/plex' ? respond(PLEX) : null;
    });
    renderApp('/admin/library#plex-import');
    const card = await screen.findByRole('region', { name: 'Plex import' });
    await waitFor(() => expect(scrollIntoView).toHaveBeenCalledTimes(1));

    await act(async () => scan.release(respond(fixtures.adminScan)));
    await screen.findByRole('button', { name: 'Scan library now' });
    await act(async () => {});

    expect(scrollIntoView).toHaveBeenCalledTimes(1);
    expect(scrollIntoView.mock.contexts[0]).toBe(card);
  });

  it('does not scroll again when a background poll brings a new scan status', async () => {
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;
    let polls = 0;
    installFetch((m, url) => {
      if (url !== '/api/admin/scan') return null;
      polls += 1;
      return respond({
        ...fixtures.adminScan,
        running: true,
        progress: { processed: polls * 5, total: 40, current: 'Heat.m4v' },
      });
    });
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    renderApp('/admin/library#scan');
    // Only the timers are faked, so the clock moves only here: the first answer lands, then one poll later the next.
    await act(() => vi.advanceTimersByTimeAsync(0));
    expect(screen.getByText('5 / 40 files')).toBeInTheDocument();
    expect(scrollIntoView).toHaveBeenCalledTimes(1);

    await act(() => vi.advanceTimersByTimeAsync(1500));

    expect(screen.getByText('10 / 40 files')).toBeInTheDocument();
    expect(scrollIntoView).toHaveBeenCalledTimes(1);
  });

  it('does not scroll when the address has no anchor', async () => {
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;
    installFetch((m, url) => (url === '/api/admin/plex' ? respond(PLEX) : null));
    renderApp('/admin/library');
    await screen.findByRole('region', { name: 'Plex import' });
    await act(async () => {});
    expect(scrollIntoView).not.toHaveBeenCalled();
  });
});
