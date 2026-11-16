import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

const running = (processed, total = 40, current = 'Heat.m4v') => ({
  ...fixtures.adminScan,
  running: true,
  progress: { processed, total, current },
});

describe('Admin: Library scan panel', () => {
  it('shows the folder, the last scan and the counts, with Scan now ready', async () => {
    installFetch();
    renderApp('/admin/library');
    const panel = await screen.findByRole('region', { name: 'Library scan' });

    expect(await within(panel).findByText('/media')).toBeInTheDocument();
    expect(within(panel).getByText(/^Last scan .+ · took 1m 8s$/)).toBeInTheDocument();
    const counts = within(panel).getByRole('list', { name: 'Library counts' });
    const tiles = within(counts)
      .getAllByRole('listitem')
      .map((item) => item.textContent);
    expect(tiles).toEqual([
      '538Films',
      '2New',
      '0Missing',
      '1Can’t play (protected)',
      '3Needs conversion',
      '2Need a match',
    ]);
    const scan = within(panel).getByRole('button', { name: 'Scan library now' });
    expect(scan).toBeEnabled();
    expect(scan).toHaveAccessibleDescription(
      'Looks through your movie folders for new, changed and removed files. A few minutes; you can keep watching.',
    );
    // In Admin only this button is amber.
    expect(scan).toHaveClass('btn--primary');
    for (const other of screen.getAllByRole('button')) {
      if (other !== scan) expect(other).not.toHaveClass('btn--primary');
    }
  });

  it('says Not scanned yet before the first scan', async () => {
    installFetch((m, url) =>
      url === '/api/admin/scan' ? respond({ ...fixtures.adminScan, lastScan: null }) : null,
    );
    renderApp('/admin/library');
    expect(await screen.findByText('Not scanned yet')).toBeInTheDocument();
  });

  it('shows a note from the last scan, such as files refused for leaving the folder', async () => {
    installFetch((m, url) =>
      url === '/api/admin/scan'
        ? respond({
            ...fixtures.adminScan,
            lastScan: {
              ...fixtures.adminScan.lastScan,
              error: '1 files outside the library folder were refused',
            },
          })
        : null,
    );
    renderApp('/admin/library');
    expect(
      await screen.findByText('1 files outside the library folder were refused'),
    ).toBeInTheDocument();
  });

  it('shows unreadable files when there are some', async () => {
    installFetch((m, url) =>
      url === '/api/admin/scan'
        ? respond({
            ...fixtures.adminScan,
            counts: { ...fixtures.adminScan.counts, unreadable: 4 },
          })
        : null,
    );
    renderApp('/admin/library');
    const counts = await screen.findByRole('list', { name: 'Library counts' });
    expect(within(counts).getAllByRole('listitem').at(-1)).toHaveTextContent('4Unreadable');
  });

  it('Scan now starts a scan, and the panel shows live progress in the button’s place', async () => {
    let started = false;
    installFetch((method, url) => {
      if (url === '/api/admin/scan' && method === 'POST') {
        started = true;
        return respond(running(0, 0, null), 202);
      }
      if (url === '/api/admin/scan') return respond(started ? running(12) : fixtures.adminScan);
      return null;
    });
    renderApp('/admin/library');
    const panel = await screen.findByRole('region', { name: 'Library scan' });

    await userEvent.click(within(panel).getByRole('button', { name: 'Scan library now' }));

    const bar = await within(panel).findByRole('progressbar', { name: 'Scan progress' });
    expect(bar).toHaveAttribute('aria-valuenow', '30');
    expect(bar).toHaveAttribute('aria-valuetext', '12 / 40 files');
    expect(within(panel).getByText('12 / 40 files')).toBeInTheDocument();
    expect(within(panel).getByText('Now: Heat.m4v')).toBeInTheDocument();
    expect(within(panel).queryByRole('button', { name: /Scan library now|Scanning/ })).toBeNull();
  });

  it('polls for progress while running and brings the button back when the scan finishes', async () => {
    let polls = 0;
    installFetch((method, url) => {
      if (url !== '/api/admin/scan') return null;
      polls += 1;
      if (polls === 1) return respond(running(10));
      if (polls === 2) return respond(running(30));
      return respond({
        ...fixtures.adminScan,
        counts: { ...fixtures.adminScan.counts, newFiles: 7 },
      });
    });
    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderApp('/admin/library');
    expect(await screen.findByText('10 / 40 files')).toBeInTheDocument();

    await act(() => vi.advanceTimersByTimeAsync(1500));
    expect(await screen.findByText('30 / 40 files')).toBeInTheDocument();
    await act(() => vi.advanceTimersByTimeAsync(1500));

    expect(await screen.findByRole('button', { name: 'Scan library now' })).toBeEnabled();
    expect(screen.queryByRole('progressbar', { name: 'Scan progress' })).toBeNull();
    const counts = screen.getByRole('list', { name: 'Library counts' });
    expect(within(counts).getByText('New').previousSibling).toHaveTextContent('7');
  });

  it('shows a neutral line while the folder is still being walked', async () => {
    installFetch((m, url) => (url === '/api/admin/scan' ? respond(running(0, 0, null)) : null));
    renderApp('/admin/library');
    expect(await screen.findByText('Looking through the folder…')).toBeInTheDocument();
    expect(screen.queryByText(/^Now:/)).not.toBeInTheDocument();
  });

  it('shows progress, not Scan now, for a scan already running when the page opens', async () => {
    installFetch((m, url) => (url === '/api/admin/scan' ? respond(running(5)) : null));
    renderApp('/admin/library');
    expect(await screen.findByRole('progressbar', { name: 'Scan progress' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Scan library now|Scanning/ })).toBeNull();
  });

  it('handles a 409 from Scan now by saying a scan is already running', async () => {
    installFetch((method, url) => {
      if (url === '/api/admin/scan' && method === 'POST') {
        return respond({ error: 'A scan is already running' }, 409);
      }
      if (url === '/api/admin/scan' && method === 'GET') return respond(fixtures.adminScan);
      return null;
    });
    renderApp('/admin/library');

    await userEvent.click(await screen.findByRole('button', { name: 'Scan library now' }));

    expect(await screen.findByText('A scan is already running.')).toBeInTheDocument();
  });

  it('reports a scan that could not be started', async () => {
    installFetch((method) => (method === 'POST' ? respond({ error: 'boom' }, 500) : null));
    renderApp('/admin/library');

    await userEvent.click(await screen.findByRole('button', { name: 'Scan library now' }));

    expect(await screen.findByText('Could not start the scan.')).toBeInTheDocument();
  });

  it('shows a loading state, then an error with Try again if the status cannot be loaded', async () => {
    let fail = true;
    installFetch((m, url) => (url === '/api/admin/scan' && fail ? respond(null, 500) : null));
    renderApp('/admin/library');
    const alert = await screen.findByText('Couldn’t load the library scan.');
    expect(alert).toBeInTheDocument();

    fail = false;
    await userEvent.click(screen.getAllByRole('button', { name: 'Try again' })[0]);
    expect(await screen.findByRole('button', { name: 'Scan library now' })).toBeInTheDocument();
  });

  it('shows a loading skeleton while the status loads', () => {
    installFetch(() => new Promise(() => {}));
    renderApp('/admin/library');
    // The scan card, what it changed, and the Cast & crew count.
    expect(screen.getAllByRole('status', { name: 'Loading' })).toHaveLength(3);
  });

  it('redirects the old /admin#library-scan address to the scan, scrolls once and never loads To do', async () => {
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;
    try {
      const fetchMock = installFetch();
      renderApp('/admin#library-scan', { showLocation: true });
      await screen.findByRole('button', { name: 'Scan library now' });
      await waitFor(() => expect(scrollIntoView).toHaveBeenCalledTimes(1));
      expect(screen.getByTestId('location')).toHaveTextContent('/admin/library#scan');
      expect(screen.getByRole('heading', { level: 1, name: 'Library' })).toBeInTheDocument();
      expect(fetchMock.mock.calls.some((c) => c[0] === '/api/admin/attention')).toBe(false);
    } finally {
      delete Element.prototype.scrollIntoView;
    }
  });

  it('does not scroll again, or show a loading state, when the status is polled in the background', async () => {
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;
    try {
      let scans = 0;
      installFetch((method, url) => {
        if (url !== '/api/admin/scan') return null;
        scans += 1;
        return respond({
          ...fixtures.adminScan,
          counts: { ...fixtures.adminScan.counts, films: 600 + scans },
        });
      });
      vi.useFakeTimers({ shouldAdvanceTime: true });
      renderApp('/admin/library#scan');
      const card = await screen.findByRole('region', { name: 'Library scan' });
      await waitFor(() => expect(scrollIntoView).toHaveBeenCalledTimes(1));

      await act(() => vi.advanceTimersByTimeAsync(60000));

      // The idle poll answered with new numbers, with the old ones on screen until then and no skeleton in between.
      expect(await within(card).findByText('602')).toBeVisible();
      expect(scans).toBeGreaterThan(1);
      expect(within(card).queryByRole('status', { name: 'Loading' })).not.toBeInTheDocument();
      expect(scrollIntoView).toHaveBeenCalledTimes(1);
    } finally {
      delete Element.prototype.scrollIntoView;
    }
  });

  it('does not scroll without the hash', async () => {
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;
    try {
      installFetch();
      renderApp('/admin/library');
      await screen.findByRole('button', { name: 'Scan library now' });
      expect(scrollIntoView).not.toHaveBeenCalled();
    } finally {
      delete Element.prototype.scrollIntoView;
    }
  });

  it('copes with a browser that cannot scroll an element into view', async () => {
    installFetch();
    renderApp('/admin/library#scan');
    expect(await screen.findByRole('button', { name: 'Scan library now' })).toBeInTheDocument();
  });
});

describe('Admin: To do lists', () => {
  it('refreshes when a scan finishes', async () => {
    let scans = 0;
    installFetch((method, url) => {
      if (url === '/api/admin/attention') {
        return scans < 2
          ? null
          : respond({
              items: [{ ...fixtures.adminAttention.items[3], title: 'Fresh Film', year: 1969 }],
            });
      }
      if (url === '/api/admin/scan') {
        scans += 1;
        return respond(
          scans === 1
            ? running(5)
            : {
                ...fixtures.adminScan,
                lastScan: { ...fixtures.adminScan.lastScan, finishedAt: '2026-11-09T04:31:00Z' },
              },
        );
      }
      return null;
    });
    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderApp('/admin');
    expect(await screen.findByText('Blade Runner')).toBeInTheDocument();
    expect(screen.queryByText('Fresh Film')).not.toBeInTheDocument();

    await act(() => vi.advanceTimersByTimeAsync(1500));

    expect(await screen.findByText('Fresh Film')).toBeInTheDocument();
  });
});

describe('Admin: Subtitles refresh', () => {
  it('reads the subtitle files again when a scan finishes, so new files show without reopening Admin', async () => {
    let scans = 0;
    const fetchMock = installFetch((method, url) => {
      // Plex never finishes an import, so its stamp never changes and causes no read of its own.
      if (url === '/api/admin/plex') return respond({ ...fixtures.adminPlex, lastImport: null });
      if (url === '/api/admin/scan') {
        scans += 1;
        return respond(
          scans === 1
            ? running(5)
            : {
                ...fixtures.adminScan,
                lastScan: { ...fixtures.adminScan.lastScan, finishedAt: '2026-11-09T04:31:00Z' },
              },
        );
      }
      return null;
    });
    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderApp('/admin/subtitles');
    const reads = () => fetchMock.mock.calls.filter((c) => c[0] === '/api/admin/subtitles').length;
    // The first scan answer has landed before the file list is opened, so its stamp is the one the list starts with.
    await waitFor(() => expect(scans).toBe(1));
    // Each click advances the fake timers, as they run by themselves.
    await userEvent
      .setup({ advanceTimers: (ms) => vi.advanceTimersByTime(ms) })
      .click(await screen.findByRole('button', { name: 'Show all subtitle files' }));
    await screen.findByRole('region', { name: 'Subtitle files' });
    await waitFor(() => expect(reads()).toBe(1));

    await act(() => vi.advanceTimersByTimeAsync(1500));

    // The second answer finished the scan: exactly one more read.
    await waitFor(() => expect(reads()).toBe(2));
    await act(() => vi.advanceTimersByTimeAsync(1500));
    expect(reads()).toBe(2);
  });
});

describe('Admin: page', () => {
  it('has Continue watching, Signed in and the TMDB credit on Settings', async () => {
    installFetch();
    renderApp('/admin/settings');
    expect(await screen.findByRole('heading', { level: 1, name: 'Settings' })).toBeInTheDocument();
    expect(await screen.findAllByRole('region')).toHaveLength(2);
    expect(screen.getByRole('region', { name: 'Continue watching' })).toBeInTheDocument();
    expect(
      screen.getByText('This product uses the TMDB API but is not endorsed or certified by TMDB.'),
    ).toBeInTheDocument();
    expect(screen.getByRole('img', { name: 'TMDB' })).toBeInTheDocument();
    expect(screen.queryByText(/arrives with the real library/)).not.toBeInTheDocument();
  });

  it('renders every entry as text, never as markup', async () => {
    installFetch((m, url) =>
      url === '/api/admin/attention'
        ? respond({
            items: [
              {
                ...fixtures.adminAttention.items[0],
                title: '<img src=x onerror=alert(1)>',
                files: ['<b>bold</b>.m4v'],
              },
            ],
          })
        : null,
    );
    renderApp('/admin');
    expect(await screen.findByText('<img src=x onerror=alert(1)>')).toBeInTheDocument();
    expect(screen.getByText(/<b>bold<\/b>\.m4v/)).toBeInTheDocument();
    expect(screen.queryAllByRole('img')).toEqual([]);
  });
});

describe('Admin: Conversion card', () => {
  it('shows the encoder with its status, the seek index and the stream cache size', async () => {
    installFetch();
    renderApp('/admin/playback');
    const panel = await screen.findByRole('region', { name: 'Conversion' });
    expect(await within(panel).findByText('VAAPI (hardware) · ready')).toBeInTheDocument();
    expect(within(panel).getByText('412 of 538 films')).toBeInTheDocument();
    expect(within(panel).getByText('1.2 GB')).toBeInTheDocument();
  });

  it('names the software encoder', async () => {
    installFetch((m, url) =>
      url === '/api/admin/transcoding'
        ? respond({ ...fixtures.adminTranscoding, encoder: 'software' })
        : null,
    );
    renderApp('/admin/playback');
    const panel = await screen.findByRole('region', { name: 'Conversion' });
    expect(await within(panel).findByText('Software (x264) · ready')).toBeInTheDocument();
  });

  it('shows the projector-jammed error with Try again when the state cannot be read', async () => {
    let fail = true;
    installFetch((m, url) =>
      url === '/api/admin/transcoding' && fail ? respond(null, 500) : null,
    );
    renderApp('/admin/playback');
    const panel = await screen.findByRole('region', { name: 'Conversion' });
    expect(await within(panel).findByRole('alert')).toHaveTextContent(
      'Couldn’t load the conversion state.',
    );
    // An inline banner under the card's own heading: no second, page-sized heading.
    expect(within(panel).getAllByRole('heading')).toHaveLength(1);
    fail = false;
    await userEvent.click(within(panel).getByRole('button', { name: 'Try again' }));
    expect(await within(panel).findByText('VAAPI (hardware) · ready')).toBeInTheDocument();
  });

  it('refreshes every 5 s while it is open', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const fetchMock = installFetch();
    renderApp('/admin/playback');
    await screen.findByText('VAAPI (hardware) · ready');
    const reads = () =>
      fetchMock.mock.calls.filter((c) => c[0] === '/api/admin/transcoding').length;
    expect(reads()).toBe(1);
    await act(() => vi.advanceTimersByTimeAsync(4900));
    expect(reads()).toBe(1);
    await act(() => vi.advanceTimersByTimeAsync(200));
    expect(reads()).toBe(2);
  });
});

describe('Admin: Session panel', () => {
  it('renders Sign out and signs out through the API', async () => {
    const fetchMock = installFetch((m, url) =>
      m === 'DELETE' && url === '/api/session' ? respond(null, 204) : null,
    );
    renderApp('/admin/settings');
    const panel = await screen.findByRole('region', { name: 'Signed in on this browser' });
    expect(within(panel).getByText('Signed in on this browser')).toBeInTheDocument();
    expect(within(panel).getByRole('button', { name: 'Sign out' })).toHaveAccessibleDescription(
      "Signing out here doesn't affect other devices.",
    );
    await userEvent.click(within(panel).getByRole('button', { name: 'Sign out' }));
    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some(
          ([url, init]) => url === '/api/session' && init?.method === 'DELETE',
        ),
      ).toBe(true),
    );
    const call = fetchMock.mock.calls.find(([, init]) => init?.method === 'DELETE');
    expect(call[1].headers['X-CineFilm']).toBe('1');
  });

  it('is disabled and reads Signing out… while the request is in flight', async () => {
    let finish;
    installFetch((m, url) =>
      m === 'DELETE' && url === '/api/session'
        ? new Promise((resolve) => (finish = resolve))
        : null,
    );
    // installFetch awaits the route result, so a pending promise holds the response.
    renderApp('/admin/settings');
    await userEvent.click(await screen.findByRole('button', { name: 'Sign out' }));
    const busy = await screen.findByRole('button', { name: 'Signing out…' });
    expect(busy).toHaveAttribute('aria-disabled', 'true');
    expect(busy).toHaveFocus();
    await act(async () => finish(respond(null, 204)));
  });

  it('keeps focus on Sign out while it runs, and a second press sends nothing', async () => {
    let finish;
    const fetchMock = installFetch((m, url) =>
      m === 'DELETE' && url === '/api/session'
        ? new Promise((resolve) => (finish = resolve))
        : null,
    );
    renderApp('/admin/settings');
    await userEvent.click(await screen.findByRole('button', { name: 'Sign out' }));
    const busy = await screen.findByRole('button', { name: 'Signing out…' });
    expect(busy).toHaveFocus();

    await userEvent.click(busy);

    const deletes = fetchMock.mock.calls.filter(([, init]) => init?.method === 'DELETE');
    expect(deletes).toHaveLength(1);
    await act(async () => finish(respond(null, 204)));
  });

  it('shows a notice and re-enables the button when sign-out fails', async () => {
    installFetch((m, url) =>
      m === 'DELETE' && url === '/api/session' ? respond(null, 500) : null,
    );
    renderApp('/admin/settings');
    await userEvent.click(await screen.findByRole('button', { name: 'Sign out' }));
    const notice = await screen.findByText("Couldn't sign out. Try again.");
    expect(notice).toHaveAttribute('role', 'status');
    expect(screen.getByRole('button', { name: 'Sign out' })).toBeEnabled();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});
