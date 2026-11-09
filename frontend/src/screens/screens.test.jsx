import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { installFetch, renderApp, respond } from '../test/mockApi.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
});

const filmsCalls = (fetchMock) =>
  fetchMock.mock.calls.map((c) => c[0]).filter((u) => u.startsWith('/api/films?'));

describe('Home', () => {
  it('renders the hero and every row', async () => {
    installFetch();
    renderApp('/');
    expect(await screen.findByRole('heading', { name: 'Heat' })).toBeInTheDocument();
    expect(screen.getByText('Pick up where you left off')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Resume from 1:04:31/ })).toHaveAttribute(
      'href',
      '/films/1/play?edition=11',
    );
    for (const name of [
      'Continue watching',
      'Recently added',
      'Not watched in years',
      'This week: the 1970s',
      'Collections',
    ]) {
      expect(screen.getByRole('heading', { name })).toBeInTheDocument();
    }
    expect(screen.getByText('2 of 4 films')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Zodiac, 2007' })).toBeInTheDocument();
  });

  it('shows the empty-library state', async () => {
    installFetch((m, url) =>
      url === '/api/home'
        ? respond({
            hero: null,
            continueWatching: [],
            recentlyAdded: [],
            notWatchedInYears: [],
            weeklyTheme: null,
            collections: [],
          })
        : null,
    );
    renderApp('/');
    expect(await screen.findByText('The house lights are up')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Import from Plex' })).toHaveAttribute(
      'href',
      '/admin#library-scan',
    );
    expect(screen.getByRole('link', { name: 'Scan a folder' })).toHaveAttribute(
      'href',
      '/admin#library-scan',
    );
  });

  it('has the header with the settings icon and "/" focusing search', async () => {
    installFetch();
    renderApp('/');
    await screen.findByRole('heading', { name: 'Heat' });
    expect(screen.getByRole('link', { name: 'Settings' })).toHaveAttribute('href', '/admin');
    const search = screen.getByRole('searchbox', { name: 'Search the library' });
    await userEvent.keyboard('/');
    expect(search).toHaveFocus();
  });
});

describe('Library', () => {
  it('debounces live search and sends q', async () => {
    const fetchMock = installFetch();
    renderApp('/library');
    expect(await screen.findByText(/films · sorted by recently added/)).toBeInTheDocument();
    const box = screen.getByRole('searchbox', { name: /Search/ });
    await userEvent.type(box, 'heat');
    expect(filmsCalls(fetchMock).some((u) => u.includes('q='))).toBe(false);
    await waitFor(() => expect(filmsCalls(fetchMock).some((u) => u.includes('q=heat'))).toBe(true));
    expect(filmsCalls(fetchMock).filter((u) => u.includes('q='))).toHaveLength(1);
  });

  it('sort uses aria-pressed and refetches', async () => {
    const fetchMock = installFetch();
    renderApp('/library');
    await screen.findByText(/sorted by recently added/);
    const group = screen.getByRole('group', { name: 'Sort' });
    expect(within(group).getByRole('button', { name: 'Recently added' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    await userEvent.click(within(group).getByRole('button', { name: 'Title' }));
    expect(within(group).getByRole('button', { name: 'Title' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    await screen.findByText(/sorted by title/);
    expect(filmsCalls(fetchMock).at(-1)).toContain('sort=title');
  });

  it('shows no-results and clears search and filters', async () => {
    const fetchMock = installFetch();
    renderApp('/library?q=zzz');
    expect(await screen.findByText('Nothing on the shelf')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Clear search and filters' }));
    await waitFor(() => expect(screen.queryByText('Nothing on the shelf')).not.toBeInTheDocument());
    expect(screen.getByRole('searchbox', { name: /Search/ })).toHaveValue('');
    await waitFor(() => expect(filmsCalls(fetchMock).at(-1)).not.toContain('q='));
  });

  it('badges watched films', async () => {
    installFetch();
    renderApp('/library');
    const link = await screen.findByRole('link', { name: 'Alien, 1979, watched' });
    expect(within(link).getByTitle('Watched')).toBeInTheDocument();
  });
});

describe('Film detail', () => {
  it('shows resume, start over, dim switch, pickers and credits', async () => {
    installFetch();
    renderApp('/films/1');
    expect(await screen.findByRole('link', { name: /Resume from 1:04:31/ })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Start from beginning/ })).toHaveAttribute(
      'href',
      expect.stringContaining('start=0'),
    );
    expect(screen.getByRole('button', { name: 'Mark as watched' })).toBeInTheDocument();
    expect(screen.getByRole('switch', { name: 'Dim the lights' })).toBeChecked();
    expect(screen.getByText('1h 45m left')).toBeInTheDocument();
    expect(screen.getByLabelText('Quality')).toBeInTheDocument();
    expect(screen.getByLabelText('Subtitles')).toHaveDisplayValue('Off · forced when needed');
    expect(screen.getByRole('link', { name: 'Michael Mann' })).toHaveAttribute('href', '/people/1');
    expect(
      screen.getByText(/Original file · 1080p HEVC · 5.1 · added March 2024/),
    ).toBeInTheDocument();
  });

  it('switching dim off is carried in the play link', async () => {
    installFetch();
    renderApp('/films/1');
    await userEvent.click(await screen.findByRole('switch', { name: 'Dim the lights' }));
    expect(screen.getByRole('link', { name: /Resume from/ })).toHaveAttribute(
      'href',
      expect.stringContaining('dim=0'),
    );
  });

  it('edition switching changes the Play label and runtime', async () => {
    const fetchMock = installFetch();
    renderApp('/films/6');
    expect(await screen.findByRole('link', { name: 'Play The Final Cut' })).toBeInTheDocument();
    expect(screen.getByText('1h 57m')).toBeInTheDocument();
    expect(screen.getByText(/Edition .* 3 in your library/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Blade Runner collection' })).toHaveAttribute(
      'href',
      '/collections/2',
    );

    await userEvent.click(screen.getByRole('radio', { name: /Theatrical Cut/ }));
    expect(screen.getByRole('link', { name: 'Play Theatrical Cut' })).toHaveAttribute(
      'href',
      expect.stringContaining('edition=63'),
    );
    expect(screen.getByText('1h 58m')).toBeInTheDocument();
    const put = fetchMock.mock.calls.find((c) => c[1]?.method === 'PUT');
    expect(put[0]).toBe('/api/films/6/preferences');
    expect(JSON.parse(put[1].body)).toEqual({ editionId: 63 });

    await userEvent.click(screen.getByRole('radio', { name: /Director's Cut/ }));
    expect(
      screen.getByRole('link', { name: "Resume Director's Cut from 20:00" }),
    ).toBeInTheDocument();
  });

  it('shows the inert subtitles state when a film has none', async () => {
    installFetch();
    renderApp('/films/8');
    expect(await screen.findByText('None for this film')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Find subtitles in Admin' })).toBeInTheDocument();
  });

  it('marks as watched through the API', async () => {
    const fetchMock = installFetch();
    renderApp('/films/1');
    await userEvent.click(await screen.findByRole('button', { name: 'Mark as watched' }));
    const post = fetchMock.mock.calls.find((c) => c[1]?.method === 'POST');
    expect(post[0]).toBe('/api/editions/11/watched');
    expect(JSON.parse(post[1].body)).toEqual({ watched: true });
    expect(await screen.findByRole('button', { name: 'Mark as unwatched' })).toBeInTheDocument();
  });
});

describe('Person and Collection', () => {
  it('lists owned films with roles', async () => {
    installFetch();
    renderApp('/people/11');
    expect(await screen.findByRole('heading', { name: 'Robert De Niro' })).toBeInTheDocument();
    expect(screen.getByText('Actor · 2 films in your library')).toBeInTheDocument();
    expect(screen.getByText('as Neil McCauley')).toBeInTheDocument();
  });

  it('shows missing parts as dashed "Not in your library" tiles', async () => {
    installFetch();
    renderApp('/collections/1');
    expect(await screen.findByRole('heading', { name: 'Alien' })).toBeInTheDocument();
    expect(screen.getAllByText('Not in your library')).toHaveLength(2);
    expect(screen.getByText('Aliens · 1986')).toBeInTheDocument();
  });
});

describe('Session expiry', () => {
  it('shows the sign-in card on a 401 and returns to the same route after sign-in', async () => {
    let signedIn = false;
    const fetchMock = installFetch((method, url) => {
      if (method === 'POST' && url === '/api/session') {
        signedIn = true;
        return respond({ authenticated: true });
      }
      if (!signedIn && url.startsWith('/api/films/6')) return respond(null, 401);
      return null;
    });
    renderApp('/films/6');
    const card = await screen.findByRole('dialog');
    expect(
      within(card).getByText(/signed out while you were away.*back where you were/),
    ).toBeInTheDocument();
    await userEvent.type(within(card).getByLabelText('Password'), 'changeme');
    await userEvent.click(within(card).getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByRole('heading', { name: 'Blade Runner' })).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    const post = fetchMock.mock.calls.find((c) => c[1]?.method === 'POST');
    expect(post[1].headers['X-CineFilm']).toBe('1');
  });
});

describe('Player', () => {
  const openPlayer = async (path = '/films/1/play?edition=11') => {
    const fetchMock = installFetch();
    renderApp(path);
    const video = await screen.findByTestId('player-video');
    await waitFor(() => expect(video).toHaveAttribute('src'));
    fireEvent.loadedMetadata(video);
    fireEvent.timeUpdate(video); // the resume seek lands
    return { video, fetchMock, player: screen.getByRole('region', { name: 'Video player' }) };
  };

  it('streams the edition and resumes 10 s before the saved position', async () => {
    const { video } = await openPlayer();
    expect(video).toHaveAttribute('src', '/api/editions/11/stream'); // direct play: the default API makes no session
    expect(video.currentTime).toBe(3861);
    expect(screen.getByText('Heat')).toBeInTheDocument();
    expect(screen.getByText('Auto · Original · Home')).toBeInTheDocument();
    expect(screen.getByText('01:04:21')).toBeInTheDocument();
    expect(screen.getByText('−01:45:39')).toBeInTheDocument();
    const slider = screen.getByRole('slider', { name: 'Seek' });
    expect(slider).toHaveAttribute('aria-valuetext', '1 hour 4 minutes 21 seconds');
  });

  it('start=0 begins at the start', async () => {
    const { video } = await openPlayer('/films/1/play?edition=11&start=0');
    expect(video.currentTime).toBe(0);
  });

  it('handles the keyboard map', async () => {
    const { video, player } = await openPlayer();
    player.focus();
    await userEvent.keyboard('{ArrowRight}');
    expect(video.currentTime).toBe(3871);
    await userEvent.keyboard('{ArrowLeft}{ArrowLeft}');
    expect(video.currentTime).toBe(3851);
    await userEvent.keyboard('{ArrowDown}');
    expect(video.volume).toBeCloseTo(0.9);

    await userEvent.keyboard('q');
    const menu = screen.getByRole('menu', { name: 'Quality' });
    const rows = within(menu).getAllByRole('menuitemradio');
    expect(rows).toHaveLength(6); // Auto, Original, 1080p, 720p, 480p and the new 360p
    expect(within(menu).getByRole('menuitemradio', { name: /^360p/ })).toBeDisabled();
    expect(within(menu).getByRole('menuitemradio', { name: /Auto/ })).toHaveAttribute(
      'aria-checked',
      'true',
    );
    expect(within(menu).getByRole('menuitemradio', { name: /^1080p/ })).toBeDisabled();
    expect(within(menu).getByText('now Original')).toBeInTheDocument();
    await userEvent.keyboard('{Escape}');
    expect(screen.queryByRole('menu', { name: 'Quality' })).not.toBeInTheDocument();
  });

  it('C cycles captions and saves the choice; A opens the two-column panel', async () => {
    const { fetchMock, player } = await openPlayer();
    player.focus();
    await userEvent.keyboard('c');
    expect(await screen.findByText('Subtitles: English')).toBeInTheDocument();
    await userEvent.keyboard('c');
    expect(await screen.findByText('Subtitles: English (forced)')).toBeInTheDocument();
    const saves = fetchMock.mock.calls.filter((c) => c[0] === '/api/films/1/preferences');
    expect(saves).toHaveLength(2);

    await userEvent.keyboard('a');
    const audio = screen.getByRole('menu', { name: 'Audio' });
    expect(within(audio).getAllByRole('menuitemradio')).toHaveLength(2);
    const subs = screen.getByRole('menu', { name: 'Subtitles' });
    expect(within(subs).getByRole('menuitemradio', { name: /English \(forced\)/ })).toHaveAttribute(
      'aria-checked',
      'true',
    );
  });

  it('C does nothing when the film has no subtitles', async () => {
    const { player } = await openPlayer('/films/8/play?edition=71');
    player.focus();
    await userEvent.keyboard('c');
    expect(screen.queryByText(/Subtitles:/)).not.toBeInTheDocument();
  });

  it('shows the end-of-film overlay at 92% and Esc keeps watching', async () => {
    const { video, player } = await openPlayer();
    player.focus();
    video.currentTime = 9400;
    fireEvent.timeUpdate(video);
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('The end')).toBeInTheDocument();
    expect(within(dialog).getByText('1995 · Michael Mann')).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Mark as watched' })).toHaveFocus();
    expect(within(dialog).getByRole('button', { name: 'Back to library' })).toBeInTheDocument();
    await userEvent.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('shows buffering with role=status', async () => {
    const { video } = await openPlayer();
    fireEvent.waiting(video);
    expect(screen.getByText('BUFFERING')).toBeInTheDocument();
    fireEvent.playing(video);
    expect(screen.queryByText('BUFFERING')).not.toBeInTheDocument();
  });

  it('auto-hides after 3 s of playing, not while a menu is open', async () => {
    const { video, player } = await openPlayer();
    vi.useFakeTimers({ shouldAdvanceTime: false });
    try {
      fireEvent.pause(video); // restart the idle timer under fake timers
      fireEvent.play(video);
      expect(player).not.toHaveClass('player--hidden');
      await act(async () => {
        await vi.advanceTimersByTimeAsync(3100);
      });
      expect(player).toHaveClass('player--hidden');
      fireEvent.mouseMove(player);
      expect(player).not.toHaveClass('player--hidden');
      fireEvent.keyDown(player, { key: 'q' });
      await act(async () => {
        await vi.advanceTimersByTimeAsync(5000);
      });
      expect(player).not.toHaveClass('player--hidden');
    } finally {
      vi.useRealTimers();
    }
  });

  it('pauses behind the sign-in card on a 401 and keeps the position', async () => {
    const { video, player } = await openPlayer();
    player.focus();
    fireEvent.play(video);
    const before = video.currentTime;
    window.dispatchEvent(new CustomEvent('cinefilm:session-expired'));
    expect(await screen.findByRole('dialog')).toBeInTheDocument();
    expect(video.paused).toBe(true);
    expect(video.currentTime).toBe(before);
  });
});
