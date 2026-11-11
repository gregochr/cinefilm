import { act, fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { installFetch, renderApp, respond } from '../test/mockApi.jsx';

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

// The header's search live region is also a status; the toast is told apart by its name.
const toastRegion = () => screen.getByRole('status', { name: 'Notification' });

const EMPTY = {
  hero: null,
  continueWatching: [],
  recentlyAdded: [],
  notWatchedInYears: [],
  weeklyTheme: null,
  collections: [],
};
const film = (id, title, extra = {}) => ({
  id,
  title,
  year: 2000 + id,
  posterPath: null,
  backdropPath: null,
  watched: false,
  progress: null,
  director: null,
  ...extra,
});
const homeWith = (patch) =>
  installFetch((m, url) => (url === '/api/home' ? respond({ ...EMPTY, ...patch }) : null));
const moon = film(5, 'Moon', { progress: { positionMs: 60000, runtimeMs: 600000, editionId: 50 } });

const calls = (fetchMock, path) => fetchMock.mock.calls.filter((c) => c[0] === path);
const body = (call) => JSON.parse(call[1].body);
const more = (title) => screen.getByRole('button', { name: `More options for ${title}` });
const row = () => screen.getByRole('region', { name: 'Continue watching' });
const cardLink = (name) => within(row()).getByRole('link', { name });
const maybeCard = (name) => within(row()).queryByRole('link', { name });
const hrefs = () =>
  within(row())
    .getAllByRole('link')
    .map((l) => l.getAttribute('href'));

async function open() {
  const fetchMock = installFetch();
  renderApp('/');
  await screen.findByRole('region', { name: 'Continue watching' });
  return fetchMock;
}

async function removeViaMenu(title) {
  await userEvent.click(more(title));
  await userEvent.click(screen.getByRole('menuitem', { name: 'Remove from Continue watching' }));
}

describe('Home: removing a film from Continue watching', () => {
  it('Remove drops the card, updates the count, calls the API and shows the Undo pill', async () => {
    const fetchMock = await open();
    await removeViaMenu('Arrival');

    expect(maybeCard('Arrival, 56m left')).not.toBeInTheDocument();
    expect(within(row()).getByText('4 in progress')).toBeInTheDocument();
    expect(toastRegion()).toHaveTextContent('Arrival removed from Continue watching');
    const [put] = calls(fetchMock, '/api/editions/21/continue-watching');
    expect(put[1].method).toBe('PUT');
    expect(body(put)).toEqual({ hidden: true });
  });

  it('Undo brings the card back in its place, restores the count and reverses the call', async () => {
    const fetchMock = await open();
    await removeViaMenu('Arrival');

    await userEvent.click(screen.getByRole('button', { name: 'Undo' }));

    expect(hrefs()).toEqual(['/films/2', '/films/3', '/films/4', '/films/5']);
    expect(within(row()).getByText('5 in progress')).toBeInTheDocument();
    expect(toastRegion()).toBeEmptyDOMElement();
    expect(cardLink('Arrival, 56m left')).toHaveFocus();
    await vi.waitFor(() =>
      expect(calls(fetchMock, '/api/editions/21/continue-watching').map(body)).toEqual([
        { hidden: true },
        { hidden: false },
      ]),
    );
  });

  it('Mark as watched drops the card, and Undo restores the previous state exactly', async () => {
    const fetchMock = await open();
    await userEvent.click(more('Chinatown'));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Mark as watched' }));

    expect(maybeCard(/^Chinatown/)).not.toBeInTheDocument();
    expect(toastRegion()).toHaveTextContent('Chinatown marked as watched');
    expect(body(calls(fetchMock, '/api/editions/31/watched')[0])).toEqual({ watched: true });

    await userEvent.click(screen.getByRole('button', { name: 'Undo' }));

    expect(hrefs()).toEqual(['/films/2', '/films/3', '/films/4', '/films/5']);
    await vi.waitFor(() => {
      const [restore] = calls(fetchMock, '/api/editions/31/playback-state');
      expect(restore[1].method).toBe('PUT');
      expect(body(restore)).toEqual({
        positionMs: 3600000,
        watched: false,
        watchedAt: null,
        hiddenFromContinueWatching: false,
        hiddenAt: null,
      });
    });
  });

  it('Esc closes the menu and returns focus to ⋯ without changing anything', async () => {
    const fetchMock = await open();
    await userEvent.click(more('Arrival'));
    await userEvent.keyboard('{Escape}');
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    expect(more('Arrival')).toHaveFocus();
    expect(calls(fetchMock, '/api/editions/21/continue-watching')).toHaveLength(0);
  });

  it('Start from beginning links to the player at 0:00', async () => {
    await open();
    await userEvent.click(more('Moon'));
    expect(screen.getByRole('menuitem', { name: 'Start from beginning' })).toHaveAttribute(
      'href',
      '/films/5/play?edition=51&start=0',
    );
  });

  it('a second removal replaces the toast', async () => {
    await open();
    await removeViaMenu('Arrival');
    await userEvent.click(more('Moon'));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Mark as watched' }));

    expect(toastRegion()).toHaveTextContent('Moon marked as watched');
    expect(toastRegion()).not.toHaveTextContent('Arrival');
  });

  it('Delete on a focused card removes it and moves focus to the next card', async () => {
    const fetchMock = await open();
    cardLink('Arrival, 56m left').focus();
    expect(cardLink('Arrival, 56m left')).toHaveFocus();
    await userEvent.keyboard('{Delete}');

    expect(maybeCard('Arrival, 56m left')).not.toBeInTheDocument();
    expect(body(calls(fetchMock, '/api/editions/21/continue-watching')[0])).toEqual({
      hidden: true,
    });
    expect(cardLink(/^Chinatown/)).toHaveFocus();
  });

  it('Backspace on the last card removes it and moves focus to the previous one', async () => {
    await open();
    cardLink(/^Moon/).focus();
    expect(cardLink(/^Moon/)).toHaveFocus();
    await userEvent.keyboard('{Backspace}');
    expect(maybeCard(/^Moon/)).not.toBeInTheDocument();
    expect(cardLink(/^Paris, Texas/)).toHaveFocus();
  });

  it('M marks the focused card watched and moves focus on', async () => {
    const fetchMock = await open();
    cardLink(/^Chinatown/).focus();
    expect(cardLink(/^Chinatown/)).toHaveFocus();
    await userEvent.keyboard('m');
    expect(body(calls(fetchMock, '/api/editions/31/watched')[0])).toEqual({ watched: true });
    expect(toastRegion()).toHaveTextContent('Chinatown marked as watched');
    expect(cardLink(/^Paris, Texas/)).toHaveFocus();
  });

  it('Delete and M do nothing when no Continue watching card has focus', async () => {
    const fetchMock = await open();
    await userEvent.keyboard('{Delete}m');
    expect(within(row()).getByText('5 in progress')).toBeInTheDocument();
    expect(calls(fetchMock, '/api/editions/21/continue-watching')).toHaveLength(0);
    expect(calls(fetchMock, '/api/editions/21/watched')).toHaveLength(0);
  });

  it('puts the card back and shows no toast if the server refuses', async () => {
    installFetch((method, url) =>
      url === '/api/editions/21/continue-watching' ? respond({ error: 'no' }, 500) : null,
    );
    renderApp('/');
    await screen.findByRole('region', { name: 'Continue watching' });
    await removeViaMenu('Arrival');

    expect(
      await within(row()).findByRole('link', { name: 'Arrival, 56m left' }),
    ).toBeInTheDocument();
    expect(toastRegion()).toBeEmptyDOMElement();
    expect(within(row()).getByText('5 in progress')).toBeInTheDocument();
  });

  it('hides the whole row when the last card goes, focuses Undo, and Undo brings the row back', async () => {
    homeWith({ hero: film(9, 'Zodiac'), continueWatching: [moon] });
    renderApp('/');
    await screen.findByRole('region', { name: 'Resume Zodiac' });
    await removeViaMenu('Moon');

    expect(screen.queryByRole('region', { name: 'Continue watching' })).not.toBeInTheDocument();
    expect(screen.queryByText('0 in progress')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Undo' })).toHaveFocus();

    await userEvent.click(screen.getByRole('button', { name: 'Undo' }));
    expect(screen.getByRole('region', { name: 'Continue watching' })).toBeInTheDocument();
    expect(cardLink(/^Moon/)).toHaveFocus();
  });

  it('hides the hero too when it shows the film that was removed, and Undo restores it', async () => {
    homeWith({ hero: moon, continueWatching: [moon] });
    renderApp('/');
    await screen.findByRole('region', { name: 'Resume Moon' });
    await removeViaMenu('Moon');

    expect(screen.queryByRole('region', { name: 'Resume Moon' })).not.toBeInTheDocument();
    expect(toastRegion()).toHaveTextContent('Moon removed from Continue watching');

    await userEvent.click(screen.getByRole('button', { name: 'Undo' }));
    expect(screen.getByRole('region', { name: 'Resume Moon' })).toBeInTheDocument();
  });

  it('keeps the Undo pill when removing the last film leaves nothing else on Home', async () => {
    homeWith({ continueWatching: [moon] });
    renderApp('/');
    await screen.findByRole('region', { name: 'Continue watching' });
    await removeViaMenu('Moon');

    expect(toastRegion()).toHaveTextContent('Moon removed from Continue watching');
    expect(screen.getByRole('button', { name: 'Undo' })).toBeInTheDocument();
  });

  // userEvent has no touch API, so the touch itself goes through fireEvent; the Cancel tap is userEvent.
  it('a long press opens the sheet and Cancel closes it', async () => {
    await open();
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    // Testing Library drains its async wrapper through `jest.advanceTimersByTime` when it sees a fake clock.
    vi.stubGlobal('jest', { advanceTimersByTime: vi.advanceTimersByTime });
    const user = userEvent.setup({ delay: null, advanceTimers: vi.advanceTimersByTime });
    fireEvent.touchStart(cardLink('Arrival, 56m left'), {
      touches: [{ clientX: 0, clientY: 0 }],
    });
    act(() => vi.advanceTimersByTime(500));
    const sheet = screen.getByRole('dialog', { name: 'Options for Arrival' });
    expect(sheet).toHaveTextContent('2016 · 56m left');
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});

describe('Home: a film marked watched shows as watched in the other rows', () => {
  it('the theme-row poster gains the watched label after Mark as watched and loses it after Undo', async () => {
    await open();
    const theme = screen.getByRole('region', { name: 'This week: the 1970s' });
    expect(within(theme).getByRole('link', { name: 'Chinatown, 1974' })).toBeInTheDocument();

    await userEvent.click(more('Chinatown'));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Mark as watched' }));
    expect(
      within(theme).getByRole('link', { name: 'Chinatown, 1974, watched' }),
    ).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Undo' }));
    expect(within(theme).getByRole('link', { name: 'Chinatown, 1974' })).toBeInTheDocument();
    expect(
      within(theme).queryByRole('link', { name: 'Chinatown, 1974, watched' }),
    ).not.toBeInTheDocument();
  });

  it('Remove leaves the other rows alone', async () => {
    await open();
    await removeViaMenu('Chinatown');
    const theme = screen.getByRole('region', { name: 'This week: the 1970s' });
    expect(within(theme).getByRole('link', { name: 'Chinatown, 1974' })).toBeInTheDocument();
  });

  it('the hero and the Recently added and Not watched in years posters follow too', async () => {
    const other = film(7, 'Zodiac');
    homeWith({
      hero: moon,
      continueWatching: [moon],
      recentlyAdded: [{ ...moon, progress: null }],
      notWatchedInYears: [{ ...moon, progress: null }, other],
    });
    renderApp('/');
    await screen.findByRole('region', { name: 'Continue watching' });
    await userEvent.click(more('Moon'));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Mark as watched' }));

    const recent = screen.getByRole('region', { name: 'Recently added' });
    expect(within(recent).getByRole('link', { name: 'Moon, 2005, watched' })).toBeInTheDocument();
    const old = screen.getByRole('region', { name: 'Not watched in years' });
    expect(within(old).getByRole('link', { name: 'Moon, 2005, watched' })).toBeInTheDocument();
    expect(within(old).getByRole('link', { name: 'Zodiac, 2007' })).toBeInTheDocument();
  });

  it('drops the bar and time left from the same film in other rows, and Undo brings them back', async () => {
    const inProgress = { ...moon, inProgress: true };
    const rewatch = film(8, 'Heat', {
      watched: true,
      inProgress: true,
      progress: { positionMs: 60000, runtimeMs: 600000, editionId: 80 },
    });
    homeWith({
      continueWatching: [inProgress],
      recentlyAdded: [inProgress, rewatch],
    });
    renderApp('/');
    await screen.findByRole('region', { name: 'Continue watching' });
    const recent = screen.getByRole('region', { name: 'Recently added' });
    expect(within(recent).getAllByTestId('poster-fill')).toHaveLength(2);

    await userEvent.click(more('Moon'));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Mark as watched' }));
    const moonPoster = within(recent).getByRole('link', { name: 'Moon, 2005, watched' });
    expect(moonPoster).not.toHaveTextContent('left');
    expect(within(moonPoster).queryByTestId('poster-fill')).toBeNull();
    // A rewatch keeps its progress.
    expect(within(recent).getAllByTestId('poster-fill')).toHaveLength(1);

    await userEvent.click(screen.getByRole('button', { name: 'Undo' }));
    expect(within(recent).getAllByTestId('poster-fill')).toHaveLength(2);
    expect(within(recent).getByRole('link', { name: 'Moon, 2005' })).toHaveTextContent('left');
  });
});

// Twelve spares after the hero, as the API sends them; film N has edition N * 10 and the link name "Film N, 9m left".
const spare = (n) =>
  film(n, `Film ${n}`, { progress: { positionMs: 60000, runtimeMs: 600000, editionId: n * 10 } });
const SPARES = Array.from({ length: 12 }, (_, i) => spare(i + 10));
const HERO = film(1, 'Heat', { progress: { positionMs: 60000, runtimeMs: 600000, editionId: 11 } });
const crowded = (total = 91, list = SPARES) =>
  homeWith({ hero: HERO, continueWatching: list, continueWatchingTotal: total });
const shown = () =>
  within(row())
    .getAllByRole('link', { name: /^Film / })
    .map((l) => l.getAttribute('href').replace('/films/', ''));
const named = (n) => cardLink(new RegExp(`^Film ${n},`));
const caption = (text) => within(row()).getByText(text);

describe('Home: one row of Continue watching', () => {
  it('shows the hero and only four cards when twelve come in, and does not repeat the hero', async () => {
    crowded();
    renderApp('/');
    await screen.findByRole('region', { name: 'Resume Heat' });
    expect(shown()).toEqual(['10', '11', '12', '13']);
    expect(maybeCard(/^Heat/)).not.toBeInTheDocument();
    expect(caption('91 in progress')).toBeInTheDocument();
  });

  it('offers no See all at a total of five, and the hero and four cards are everything', async () => {
    crowded(5, SPARES.slice(0, 4));
    renderApp('/');
    await screen.findByRole('region', { name: 'Resume Heat' });
    expect(caption('5 in progress')).toBeInTheDocument();
    expect(within(row()).queryByRole('link', { name: /See all/ })).not.toBeInTheDocument();
  });

  it('offers See all at a total of six, to the In progress list sorted by last watched', async () => {
    crowded(6, SPARES.slice(0, 5));
    renderApp('/');
    await screen.findByRole('region', { name: 'Resume Heat' });
    expect(shown()).toEqual(['10', '11', '12', '13']);
    expect(
      within(row()).getByRole('link', { name: 'See all 6 films in progress' }),
    ).toHaveAttribute('href', '/library?show=in-progress&sort=last-watched');
  });

  it('takes the count down on Remove and up again on Undo, and drops See all at five', async () => {
    crowded(6, SPARES.slice(0, 5));
    renderApp('/');
    await screen.findByRole('region', { name: 'Resume Heat' });
    await removeViaMenu('Film 11');
    expect(caption('5 in progress')).toBeInTheDocument();
    expect(within(row()).queryByRole('link', { name: /See all/ })).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Undo' }));
    expect(caption('6 in progress')).toBeInTheDocument();
    expect(
      within(row()).getByRole('link', { name: 'See all 6 films in progress' }),
    ).toBeInTheDocument();
  });

  it('counts 91, 90 and 91 again through Remove and Undo', async () => {
    crowded();
    renderApp('/');
    await screen.findByRole('region', { name: 'Resume Heat' });
    await removeViaMenu('Film 11');
    expect(caption('90 in progress')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Undo' }));
    expect(caption('91 in progress')).toBeInTheDocument();
  });

  it('backfills: removing the second card brings the fifth film in as the fourth card', async () => {
    crowded();
    renderApp('/');
    await screen.findByRole('region', { name: 'Resume Heat' });
    await removeViaMenu('Film 11');
    expect(shown()).toEqual(['10', '12', '13', '14']);
  });

  it('Undo after a backfill puts the original back and pushes the backfilled card out', async () => {
    crowded();
    renderApp('/');
    await screen.findByRole('region', { name: 'Resume Heat' });
    await removeViaMenu('Film 11');
    await userEvent.click(screen.getByRole('button', { name: 'Undo' }));
    expect(shown()).toEqual(['10', '11', '12', '13']);
    expect(maybeCard(/^Film 14,/)).not.toBeInTheDocument();
  });

  it('two Removes inside the Undo window, then Undo, restore the second film to its slot', async () => {
    crowded();
    renderApp('/');
    await screen.findByRole('region', { name: 'Resume Heat' });
    await removeViaMenu('Film 11');
    await removeViaMenu('Film 13');
    expect(shown()).toEqual(['10', '12', '14', '15']);

    await userEvent.click(screen.getByRole('button', { name: 'Undo' }));
    expect(shown()).toEqual(['10', '12', '13', '14']);
    expect(caption('90 in progress')).toBeInTheDocument();
  });

  it('a failed Remove puts the row and the count back', async () => {
    installFetch((method, url) => {
      if (url === '/api/home') {
        return respond({
          ...EMPTY,
          hero: HERO,
          continueWatching: SPARES,
          continueWatchingTotal: 91,
        });
      }
      return url === '/api/editions/110/continue-watching' ? respond({ error: 'no' }, 500) : null;
    });
    renderApp('/');
    await screen.findByRole('region', { name: 'Resume Heat' });
    await removeViaMenu('Film 11');

    await vi.waitFor(() => expect(shown()).toEqual(['10', '11', '12', '13']));
    expect(caption('91 in progress')).toBeInTheDocument();
    expect(toastRegion()).toBeEmptyDOMElement();
  });

  it('focus goes to the card now in the removed card’s slot, the backfilled one at the end', async () => {
    crowded();
    renderApp('/');
    await screen.findByRole('region', { name: 'Resume Heat' });
    await removeViaMenu('Film 11');
    expect(named(12)).toHaveFocus();

    await removeViaMenu('Film 14');
    expect(named(15)).toHaveFocus();
  });

  it('focus goes to the previous card when the removed one was last in the list', async () => {
    crowded(4, SPARES.slice(0, 3));
    renderApp('/');
    await screen.findByRole('region', { name: 'Resume Heat' });
    await removeViaMenu('Film 12');
    expect(named(11)).toHaveFocus();
  });

  it('focus goes to Undo when the row has gone, and to the restored card on Undo', async () => {
    crowded(2, SPARES.slice(0, 1));
    renderApp('/');
    await screen.findByRole('region', { name: 'Resume Heat' });
    await removeViaMenu('Film 10');
    expect(screen.queryByRole('region', { name: 'Continue watching' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Undo' })).toHaveFocus();

    await userEvent.click(screen.getByRole('button', { name: 'Undo' }));
    expect(named(10)).toHaveFocus();
  });

  it('the row heading can be focused by script only', async () => {
    crowded();
    renderApp('/');
    await screen.findByRole('region', { name: 'Resume Heat' });
    const heading = within(row()).getByRole('heading', { name: 'Continue watching' });
    expect(heading).toHaveAttribute('tabindex', '-1');
    await userEvent.tab();
    expect(heading).not.toHaveFocus();
  });

  it('with one film in progress, Home shows the hero and no row', async () => {
    homeWith({ hero: HERO, continueWatching: [], continueWatchingTotal: 1 });
    renderApp('/');
    expect(await screen.findByRole('region', { name: 'Resume Heat' })).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Continue watching' })).not.toBeInTheDocument();
  });

  it('with none in progress, Home shows no hero and no row and starts with Recently added', async () => {
    homeWith({
      continueWatching: [],
      continueWatchingTotal: 0,
      recentlyAdded: [film(7, 'Zodiac')],
    });
    renderApp('/');
    const recent = await screen.findByRole('region', { name: 'Recently added' });
    expect(screen.queryByRole('region', { name: /^Resume / })).not.toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Continue watching' })).not.toBeInTheDocument();
    expect(screen.getAllByRole('heading', { level: 2 })[0]).toBe(
      within(recent).getByRole('heading', { name: 'Recently added' }),
    );
  });

  it('only a card that joins after the first render is marked as entering', async () => {
    crowded();
    renderApp('/');
    await screen.findByRole('region', { name: 'Resume Heat' });
    for (const n of [10, 11, 12, 13]) expect(named(n)).not.toHaveAttribute('data-entering');

    await removeViaMenu('Film 11');
    expect(named(14)).toHaveAttribute('data-entering', 'true');
    for (const n of [10, 12, 13]) expect(named(n)).not.toHaveAttribute('data-entering');

    await userEvent.click(screen.getByRole('button', { name: 'Undo' }));
    expect(named(11)).toHaveAttribute('data-entering', 'true');
  });

  it('See all opens Library on In progress, sorted by Last watched', async () => {
    crowded(6, SPARES.slice(0, 5));
    renderApp('/');
    await screen.findByRole('region', { name: 'Resume Heat' });
    await userEvent.click(within(row()).getByRole('link', { name: 'See all 6 films in progress' }));

    expect(
      await screen.findByText(/films in progress · sorted by last watched/),
    ).toBeInTheDocument();
    const show = screen.getByRole('group', { name: 'Show' });
    expect(within(show).getByRole('button', { name: 'In progress' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    const sort = screen.getByRole('group', { name: 'Sort' });
    expect(within(sort).getByRole('button', { name: 'Last watched' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
  });
});

describe('Home: a failed Remove after focus has moved', () => {
  // The API refuses Remove for Film 13 (edition 130) only once `fail()` is called.
  function failLater() {
    let fail;
    const gate = new Promise((resolve) => {
      fail = () => resolve(respond({ error: 'no' }, 500));
    });
    installFetch((method, url) => {
      if (url === '/api/home') {
        return respond({
          ...EMPTY,
          hero: HERO,
          continueWatching: SPARES.slice(0, 5),
          continueWatchingTotal: 6,
        });
      }
      return url === '/api/editions/130/continue-watching' ? gate : null;
    });
    return fail;
  }

  it('moves focus to the reinstated card when the rollback pushes the focused backfill out', async () => {
    const fail = failLater();
    renderApp('/');
    await screen.findByRole('region', { name: 'Resume Heat' });
    await removeViaMenu('Film 13');
    expect(named(14)).toHaveFocus();

    fail();
    await vi.waitFor(() => expect(named(13)).toHaveFocus());
    expect(shown()).toEqual(['10', '11', '12', '13']);
  });

  it('leaves focus alone when it is outside the row', async () => {
    const fail = failLater();
    renderApp('/');
    await screen.findByRole('region', { name: 'Resume Heat' });
    await removeViaMenu('Film 13');
    const resume = screen.getByRole('link', { name: /^Resume from/ });
    resume.focus();
    expect(resume).toHaveFocus();

    fail();
    await vi.waitFor(() => expect(shown()).toEqual(['10', '11', '12', '13']));
    expect(resume).toHaveFocus();
  });
});
