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
    renderApp('/admin');
    const panel = await screen.findByRole('region', { name: 'Library scan' });

    expect(within(panel).getByText('/media')).toBeInTheDocument();
    expect(within(panel).getByText('08 Nov 12:41 · 2 new, 0 missing')).toBeInTheDocument();
    expect(within(panel).getByText('Idle')).toBeInTheDocument();
    const counts = within(panel).getByRole('list', { name: 'Library counts' });
    const tiles = within(counts)
      .getAllByRole('listitem')
      .map((item) => item.textContent);
    expect(tiles).toEqual([
      'Films538',
      'New2',
      'Missing0',
      'Protected1',
      'Needs conversion3',
      'Need a match2',
    ]);
    expect(within(panel).getByRole('button', { name: 'Scan now' })).toBeEnabled();
  });

  it('says Never before the first scan', async () => {
    installFetch((m, url) =>
      url === '/api/admin/scan' ? respond({ ...fixtures.adminScan, lastScan: null }) : null,
    );
    renderApp('/admin');
    expect(await screen.findByText('Never')).toBeInTheDocument();
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
    renderApp('/admin');
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
    renderApp('/admin');
    const counts = await screen.findByRole('list', { name: 'Library counts' });
    expect(within(counts).getAllByRole('listitem').at(-1)).toHaveTextContent('Unreadable4');
  });

  it('Scan now starts a scan, and the panel shows live progress with the button disabled', async () => {
    let started = false;
    installFetch((method, url) => {
      if (url === '/api/admin/scan' && method === 'POST') {
        started = true;
        return respond(running(0, 0, null), 202);
      }
      if (url === '/api/admin/scan') return respond(started ? running(12) : fixtures.adminScan);
      return null;
    });
    renderApp('/admin');
    const panel = await screen.findByRole('region', { name: 'Library scan' });

    await userEvent.click(within(panel).getByRole('button', { name: 'Scan now' }));

    expect(await within(panel).findByText('Running')).toBeInTheDocument();
    const bar = within(panel).getByRole('progressbar', { name: 'Scan progress' });
    expect(bar).toHaveAttribute('aria-valuenow', '30');
    expect(bar).toHaveAttribute('aria-valuetext', '12 / 40 files');
    expect(within(panel).getByText('12 / 40 files')).toBeInTheDocument();
    expect(within(panel).getByText('Now: Heat.m4v')).toBeInTheDocument();
    expect(within(panel).getByRole('button', { name: 'Scanning…' })).toBeDisabled();
  });

  it('polls for progress while running and returns to Idle when the scan finishes', async () => {
    let polls = 0;
    installFetch((method, url) => {
      if (url !== '/api/admin/scan') return null;
      polls += 1;
      if (polls === 1) return respond(running(10));
      if (polls === 2) return respond(running(30));
      return respond({
        ...fixtures.adminScan,
        lastScan: { ...fixtures.adminScan.lastScan, newFiles: 7 },
      });
    });
    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderApp('/admin');
    expect(await screen.findByText('10 / 40 files')).toBeInTheDocument();

    await act(() => vi.advanceTimersByTimeAsync(1500));
    expect(await screen.findByText('30 / 40 files')).toBeInTheDocument();
    await act(() => vi.advanceTimersByTimeAsync(1500));

    expect(await screen.findByText('Idle')).toBeInTheDocument();
    expect(screen.getByText('08 Nov 12:41 · 7 new, 0 missing')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Scan now' })).toBeEnabled();
  });

  it('shows a neutral line while the folder is still being walked', async () => {
    installFetch((m, url) => (url === '/api/admin/scan' ? respond(running(0, 0, null)) : null));
    renderApp('/admin');
    expect(await screen.findByText('Looking through the folder…')).toBeInTheDocument();
    expect(screen.queryByText(/^Now:/)).not.toBeInTheDocument();
  });

  it('keeps Scan now disabled for a scan already running when the page opens', async () => {
    installFetch((m, url) => (url === '/api/admin/scan' ? respond(running(5)) : null));
    renderApp('/admin');
    expect(await screen.findByRole('button', { name: 'Scanning…' })).toBeDisabled();
  });

  it('handles a 409 from Scan now by saying a scan is already running', async () => {
    installFetch((method, url) => {
      if (url === '/api/admin/scan' && method === 'POST') {
        return respond({ error: 'A scan is already running' }, 409);
      }
      if (url === '/api/admin/scan' && method === 'GET') return respond(fixtures.adminScan);
      return null;
    });
    renderApp('/admin');

    await userEvent.click(await screen.findByRole('button', { name: 'Scan now' }));

    expect(await screen.findByText('A scan is already running.')).toBeInTheDocument();
  });

  it('reports a scan that could not be started', async () => {
    installFetch((method) => (method === 'POST' ? respond({ error: 'boom' }, 500) : null));
    renderApp('/admin');

    await userEvent.click(await screen.findByRole('button', { name: 'Scan now' }));

    expect(await screen.findByText('Could not start the scan.')).toBeInTheDocument();
  });

  it('shows a loading state, then an error with Try again if the status cannot be loaded', async () => {
    let fail = true;
    installFetch((m, url) => (url === '/api/admin/scan' && fail ? respond(null, 500) : null));
    renderApp('/admin');
    const alert = await screen.findByText('Couldn’t load the library scan.');
    expect(alert).toBeInTheDocument();

    fail = false;
    await userEvent.click(screen.getAllByRole('button', { name: 'Try again' })[0]);
    expect(await screen.findByRole('button', { name: 'Scan now' })).toBeInTheDocument();
  });

  it('shows a loading skeleton while the status loads', () => {
    installFetch(() => new Promise(() => {}));
    renderApp('/admin');
    expect(screen.getAllByRole('status', { name: 'Loading' })).toHaveLength(6);
  });

  it('scrolls the panel into view for the empty-library link (#library-scan)', async () => {
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;
    try {
      installFetch();
      renderApp('/admin#library-scan');
      await screen.findByRole('button', { name: 'Scan now' });
      await waitFor(() => expect(scrollIntoView).toHaveBeenCalled());
    } finally {
      delete Element.prototype.scrollIntoView;
    }
  });

  it('does not scroll without the hash', async () => {
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;
    try {
      installFetch();
      renderApp('/admin');
      await screen.findByRole('button', { name: 'Scan now' });
      expect(scrollIntoView).not.toHaveBeenCalled();
    } finally {
      delete Element.prototype.scrollIntoView;
    }
  });

  it('copes with a browser that cannot scroll an element into view', async () => {
    installFetch();
    renderApp('/admin#library-scan');
    expect(await screen.findByRole('button', { name: 'Scan now' })).toBeInTheDocument();
  });
});

describe('Admin: Films needing attention panel', () => {
  it('lists each entry with its title, year, kind, detail and file names', async () => {
    installFetch();
    renderApp('/admin');
    const panel = await screen.findByRole('region', { name: 'Films needing attention' });
    const items = await within(panel).findAllByRole('listitem');

    expect(items).toHaveLength(5 + 2);
    expect(within(panel).getByText('The Wicker Man')).toBeInTheDocument();
    expect(within(panel).getByText('Unmatched')).toBeInTheDocument();
    expect(within(panel).getByText('2 possible matches')).toBeInTheDocument();
    expect(within(panel).getByText('The Wicker Man.m4v')).toBeInTheDocument();
    expect(within(panel).getByText('Kes (1969)')).toBeInTheDocument();
    expect(within(panel).getByText('Protected')).toBeInTheDocument();
    expect(
      within(panel).getByText("Protected by FairPlay, so it can't be played"),
    ).toBeInTheDocument();
    expect(within(panel).getByText('Alien (1979)')).toBeInTheDocument();
    expect(within(panel).getByText('Needs conversion')).toBeInTheDocument();
    expect(within(panel).getByText('Blade Runner (1982)')).toBeInTheDocument();
    expect(within(panel).getByText('Several files')).toBeInTheDocument();
    expect(
      within(panel).getByText('2 untagged versions of one film, runtimes differ by 22%'),
    ).toBeInTheDocument();
    expect(
      within(panel).getByText('Parts don\u2019t match \u2014 will transcode'),
    ).toBeInTheDocument();
    expect(
      within(panel).getByText('Part 2: video codec is h264, part 1 has hevc'),
    ).toBeInTheDocument();
    expect(
      within(panel).getByText('Blade Runner.m4v · Blade Runner Final Cut.m4v'),
    ).toBeInTheDocument();
  });

  it('shows the candidates of an unmatched film read-only, with year and runtime', async () => {
    installFetch();
    renderApp('/admin');
    const candidates = await screen.findByRole('list', { name: 'Possible matches' });

    const [first, second] = within(candidates).getAllByRole('listitem');
    expect(first).toHaveTextContent('The Wicker Man (1973) · 1h 33m');
    expect(second).toHaveTextContent('The Wicker Man (2006) · 1h 42m');
    expect(within(candidates).queryByRole('button')).not.toBeInTheDocument();
    expect(within(candidates).queryByRole('radio')).not.toBeInTheDocument();
  });

  it('leaves the runtime out when TMDB gave none, and shows no list for other kinds', async () => {
    installFetch((m, url) =>
      url === '/api/admin/attention'
        ? respond({
            items: [
              {
                ...fixtures.adminAttention.items[0],
                candidates: [
                  { tmdbId: 1, title: 'Dune', year: 1984, runtimeMinutes: null, posterPath: null },
                ],
              },
              fixtures.adminAttention.items[1],
            ],
          })
        : null,
    );
    renderApp('/admin');
    const candidates = await screen.findByRole('list', { name: 'Possible matches' });

    expect(candidates).toHaveTextContent('Dune (1984)');
    expect(candidates).not.toHaveTextContent('·');
    expect(screen.getAllByRole('list', { name: 'Possible matches' })).toHaveLength(1);
  });

  it('says nothing needs attention when the list is empty', async () => {
    installFetch((m, url) => (url === '/api/admin/attention' ? respond({ items: [] }) : null));
    renderApp('/admin');
    expect(await screen.findByText('Nothing needs attention.')).toBeInTheDocument();
  });

  it('shows an error with Try again when the list cannot be loaded', async () => {
    let fail = true;
    installFetch((m, url) => (url === '/api/admin/attention' && fail ? respond(null, 500) : null));
    renderApp('/admin');
    expect(
      await screen.findByText('Couldn’t load the films needing attention.'),
    ).toBeInTheDocument();

    fail = false;
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByText('Kes (1969)')).toBeInTheDocument();
  });

  it('refreshes when a scan finishes', async () => {
    let scans = 0;
    const fetchMock = installFetch((method, url) => {
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
    await screen.findByText('5 / 40 files');
    const attentionCalls = () =>
      fetchMock.mock.calls.filter((c) => c[0] === '/api/admin/attention').length;
    const before = attentionCalls();

    await act(() => vi.advanceTimersByTimeAsync(1500));

    await waitFor(() => expect(attentionCalls()).toBeGreaterThan(before));
  });
});

describe('Admin: page', () => {
  it('has the Admin heading, its seven panels, and the TMDB credit', async () => {
    installFetch();
    renderApp('/admin');
    expect(await screen.findByRole('heading', { level: 1, name: 'Admin' })).toBeInTheDocument();
    expect(screen.getAllByRole('region')).toHaveLength(7);
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
                ...fixtures.adminAttention.items[1],
                title: '<img src=x onerror=alert(1)>',
                files: ['<b>bold</b>.m4v'],
              },
            ],
          })
        : null,
    );
    renderApp('/admin');
    expect(await screen.findByText('<img src=x onerror=alert(1)> (1969)')).toBeInTheDocument();
    expect(screen.getByText('<b>bold</b>.m4v')).toBeInTheDocument();
    expect(screen.getAllByRole('img').map((img) => img.getAttribute('aria-label'))).toEqual([
      'TMDB',
    ]);
  });
});

const session = (n) => ({
  ...fixtures.adminTranscoding.sessions[0],
  id: `session-${n}`,
  filmTitle: `Film ${n}`,
});

describe('Admin: Transcoding panel', () => {
  it('shows the encoder, the keyframe index progress and the stream cache size', async () => {
    installFetch();
    renderApp('/admin');
    const panel = await screen.findByRole('region', { name: 'Transcoding' });
    expect(within(panel).getByText('VAAPI (hardware)')).toBeInTheDocument();
    expect(within(panel).getByText('412 of 538 films indexed')).toBeInTheDocument();
    expect(within(panel).getByText('1.2 GB')).toBeInTheDocument();
  });

  it('lists each session with its film, rung, speed, segments ahead and start time', async () => {
    installFetch();
    renderApp('/admin');
    const panel = await screen.findByRole('region', { name: 'Transcoding' });
    const sessions = within(panel).getByRole('list', { name: 'Playback sessions' });
    const [first] = within(sessions).getAllByRole('listitem');
    expect(within(first).getByText('Heat')).toBeInTheDocument();
    expect(within(first).getByText('720p')).toBeInTheDocument();
    expect(first).toHaveTextContent('3.4× · 12 segments ahead · started 09 Nov 19:02');
  });

  it('names the software encoder, and shows a dash before a speed is known', async () => {
    installFetch((m, url) =>
      url === '/api/admin/transcoding'
        ? respond({
            ...fixtures.adminTranscoding,
            encoder: 'software',
            sessions: [{ ...session(1), speed: null, segmentsAhead: 1 }],
          })
        : null,
    );
    renderApp('/admin');
    const panel = await screen.findByRole('region', { name: 'Transcoding' });
    expect(within(panel).getByText('Software (x264)')).toBeInTheDocument();
    expect(panel).toHaveTextContent('— · 1 segment ahead');
  });

  it('says so when nothing is being transcoded', async () => {
    installFetch((m, url) =>
      url === '/api/admin/transcoding'
        ? respond({ ...fixtures.adminTranscoding, encoder: 'unavailable', sessions: [] })
        : null,
    );
    renderApp('/admin');
    const panel = await screen.findByRole('region', { name: 'Transcoding' });
    expect(within(panel).getByText('Unavailable')).toBeInTheDocument();
    expect(within(panel).getByText('Nothing is being transcoded.')).toBeInTheDocument();
    expect(
      within(panel).queryByRole('list', { name: 'Playback sessions' }),
    ).not.toBeInTheDocument();
  });

  it('Stop ends that session with DELETE and refreshes the list', async () => {
    let stopped = false;
    const fetchMock = installFetch((method, url) => {
      if (url === '/api/admin/transcoding')
        return respond({
          ...fixtures.adminTranscoding,
          sessions: stopped ? [] : [session(1), session(2)],
        });
      if (method === 'DELETE') {
        stopped = true;
        return respond(null, 204);
      }
      return null;
    });
    renderApp('/admin');
    await userEvent.click(await screen.findByRole('button', { name: 'Stop Film 2' }));
    const stop = fetchMock.mock.calls.find((c) => c[1]?.method === 'DELETE');
    expect(stop[0]).toBe('/api/admin/transcoding/sessions/session-2');
    expect(stop[1].headers['X-CineFilm']).toBe('1');
    expect(await screen.findByText('Nothing is being transcoded.')).toBeInTheDocument();
  });

  it('says so when Stop fails, and keeps the session listed', async () => {
    installFetch((method, url) => {
      if (url === '/api/admin/transcoding')
        return respond({ ...fixtures.adminTranscoding, sessions: [session(1)] });
      return method === 'DELETE' ? respond(null, 500) : null;
    });
    renderApp('/admin');
    await userEvent.click(await screen.findByRole('button', { name: 'Stop Film 1' }));
    expect(await screen.findByText('Could not stop the session.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Stop Film 1' })).toBeInTheDocument();
  });

  it('shows the projector-jammed error with Try again when the state cannot be read', async () => {
    let fail = true;
    installFetch((m, url) =>
      url === '/api/admin/transcoding' && fail ? respond(null, 500) : null,
    );
    renderApp('/admin');
    const panel = await screen.findByRole('region', { name: 'Transcoding' });
    expect(await within(panel).findByRole('alert')).toHaveTextContent('The projector jammed');
    fail = false;
    await userEvent.click(within(panel).getByRole('button', { name: 'Try again' }));
    expect(await within(panel).findByText('VAAPI (hardware)')).toBeInTheDocument();
  });

  it('refreshes every 5 s while it is open', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const fetchMock = installFetch();
    renderApp('/admin');
    await screen.findByText('VAAPI (hardware)');
    const reads = () =>
      fetchMock.mock.calls.filter((c) => c[0] === '/api/admin/transcoding').length;
    expect(reads()).toBe(1);
    await act(() => vi.advanceTimersByTimeAsync(4900));
    expect(reads()).toBe(1);
    await act(() => vi.advanceTimersByTimeAsync(200));
    expect(reads()).toBe(2);
  });
});

describe('Admin: Playback health panel', () => {
  it('comes directly after the Transcoding card, with the recent playbacks', async () => {
    installFetch();
    renderApp('/admin');
    const panel = await screen.findByRole('region', { name: 'Playback health' });
    const titles = screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent);
    expect(titles.indexOf('Playback health')).toBe(titles.indexOf('Transcoding') + 1);
    const list = await within(panel).findByRole('list', { name: 'Recent playbacks' });
    expect(within(list).getAllByRole('listitem')).toHaveLength(3);
    expect(within(list).getByText('Heat')).toBeInTheDocument();
  });
});

describe('Admin: Session panel', () => {
  it('renders Sign out and signs out through the API', async () => {
    const fetchMock = installFetch((m, url) =>
      m === 'DELETE' && url === '/api/session' ? respond(null, 204) : null,
    );
    renderApp('/admin');
    const panel = await screen.findByRole('region', { name: 'Session' });
    expect(within(panel).getByText('Signed in to this browser.')).toBeInTheDocument();
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
    renderApp('/admin');
    await userEvent.click(await screen.findByRole('button', { name: 'Sign out' }));
    const busy = await screen.findByRole('button', { name: 'Signing out…' });
    expect(busy).toBeDisabled();
    await act(async () => finish(respond(null, 204)));
  });

  it('shows a notice and re-enables the button when sign-out fails', async () => {
    installFetch((m, url) =>
      m === 'DELETE' && url === '/api/session' ? respond(null, 500) : null,
    );
    renderApp('/admin');
    await userEvent.click(await screen.findByRole('button', { name: 'Sign out' }));
    const notice = await screen.findByText("Couldn't sign out. Try again.");
    expect(notice).toHaveAttribute('role', 'status');
    expect(screen.getByRole('button', { name: 'Sign out' })).toBeEnabled();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});
