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
  it('is named Library and holds the scan, ratings, Plex import, End credits and Cast & crew cards in order', async () => {
    installFetch((m, url) => (url === '/api/admin/plex' ? respond(PLEX) : null));
    renderApp('/admin/library');
    expect(screen.getByRole('heading', { level: 1, name: 'Library' })).toBeInTheDocument();
    await screen.findByRole('region', { name: 'Import from Plex' });
    await screen.findByRole('region', { name: 'Ratings' });
    expect(screen.getAllByRole('region').map((r) => r.getAttribute('id'))).toEqual([
      'scan',
      'ratings',
      'plex-import',
      'end-credits',
      'cast-crew',
    ]);
    expect(screen.queryByRole('region', { name: 'Grouping report' })).toBeNull();
    expect(screen.queryByRole('region', { name: 'Pick the right film' })).toBeNull();
  });

  it('has no TMDB credits download and no subtitle search line', async () => {
    installFetch((m, url) => (url === '/api/admin/plex' ? respond(PLEX) : null));
    renderApp('/admin/library');
    await screen.findByRole('region', { name: 'Import from Plex' });
    expect(screen.queryByRole('button', { name: /TMDB/i })).toBeNull();
    expect(screen.queryByText(/Subtitles searched/)).toBeNull();
  });

  it('shows one skeleton per loading card: the scan, what it changed and the Cast & crew count', () => {
    installFetch(() => new Promise(() => {}));
    renderApp('/admin/library');
    expect(screen.getAllByRole('status', { name: 'Loading' })).toHaveLength(3);
  });

  it('shows the scan error with Try again, and the grouping report error on its own', async () => {
    installFetch((m, url) =>
      url === '/api/admin/scan' || url === '/api/admin/grouping-report' ? respond(null, 500) : null,
    );
    renderApp('/admin/library');
    expect(await screen.findByText('Couldn’t load the library scan.')).toBeInTheDocument();
    expect(
      await screen.findByText('Couldn’t load what the last scan changed.'),
    ).toBeInTheDocument();
  });

  it('lists the grouping changes from the report', async () => {
    installFetch();
    renderApp('/admin/library');
    const card = await screen.findByRole('region', { name: 'Library scan' });
    expect(await within(card).findByRole('list', { name: 'Grouping changes' })).toBeInTheDocument();
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
    const panel = await screen.findByRole('dialog', { name: 'Fix match' });
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
    await screen.findByRole('region', { name: 'Import from Plex' });
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
    const card = await screen.findByRole('region', { name: 'Import from Plex' });
    await waitFor(() => expect(scrollIntoView).toHaveBeenCalledTimes(1));

    await act(async () => scan.release(respond(fixtures.adminScan)));
    await screen.findByRole('button', { name: 'Scan library now' });
    await act(async () => {});

    expect(scrollIntoView).toHaveBeenCalledTimes(1);
    expect(scrollIntoView.mock.contexts[0]).toBe(card);
  });

  it('#plex-import scrolls to the Plex card when its first read failed, so the error and Try again are in view', async () => {
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;
    installFetch((m, url) => (url === '/api/admin/plex' ? respond(null, 500) : null));
    renderApp('/admin/library#plex-import');
    const card = await screen.findByRole('region', { name: 'Import from Plex' });

    await waitFor(() => expect(scrollIntoView).toHaveBeenCalledTimes(1));
    expect(scrollIntoView.mock.contexts[0]).toBe(card);
  });

  it('#plex-import scrolls once, not again after each failed Try again', async () => {
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;
    const fetchMock = installFetch((m, url) =>
      url === '/api/admin/plex' ? respond(null, 500) : null,
    );
    const plexReads = () =>
      fetchMock.mock.calls.filter(([url]) => url === '/api/admin/plex').length;
    renderApp('/admin/library#plex-import');
    const card = await screen.findByRole('region', { name: 'Import from Plex' });
    await waitFor(() => expect(scrollIntoView).toHaveBeenCalledTimes(1));

    for (let n = 1; n <= 2; n += 1) {
      const reads = plexReads();
      await userEvent.click(within(card).getByRole('button', { name: 'Try again' }));
      await waitFor(() => expect(plexReads()).toBe(reads + 1));
      await act(async () => {});
    }

    expect(scrollIntoView).toHaveBeenCalledTimes(1);
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
    await screen.findByRole('region', { name: 'Import from Plex' });
    await act(async () => {});
    expect(scrollIntoView).not.toHaveBeenCalled();
  });
});
