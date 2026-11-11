import { act, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

const film = (id, title, extra = {}) => ({
  id,
  title,
  year: 2000 + id,
  posterPath: null,
  backdropPath: null,
  watched: false,
  progress: { positionMs: 1800000, runtimeMs: 7200000, editionId: id * 10 },
  director: null,
  collectionId: null,
  sortTitle: title.toLowerCase(),
  inProgress: true,
  lastWatchedAt: '2026-11-09T20:00:00Z',
  hiddenFromContinueWatching: false,
  ...extra,
});
const ALPHA = film(1, 'Alpha');
const BETA = film(2, 'Beta', { hiddenFromContinueWatching: true });
const GAMMA = film(3, 'Gamma');
const DELTA = film(4, 'Delta', {
  inProgress: false,
  progress: { positionMs: 60000, runtimeMs: 7200000, editionId: 40 },
});
const FILMS = [ALPHA, BETA, GAMMA, DELTA];

/** A Library whose films list is `FILMS` (or `films`), with `route` for anything else. */
function library(route, films = FILMS) {
  return installFetch((method, url, init) => {
    if (url.startsWith('/api/films?') || url === '/api/films') {
      return respond({ total: films.length, films, facets: fixtures.films.facets });
    }
    return route?.(method, url, init) ?? null;
  });
}

const calls = (fetchMock, path) => fetchMock.mock.calls.filter((c) => c[0] === path);
const body = (call) => JSON.parse(call[1].body);
const more = (title) => screen.getByRole('button', { name: `More options for ${title}` });
const poster = (name) => screen.getByRole('link', { name });
const maybePoster = (name) => screen.queryByRole('link', { name });
const heading = (text) => screen.findByText(text);
const toast = () => screen.getByRole('status', { name: 'Notification' });
const removeItem = () => screen.queryByRole('menuitem', { name: 'Remove from Continue watching' });

async function openMenu(title) {
  await userEvent.click(more(title));
}

describe('Library: the ⋯ menu on in-progress posters', () => {
  it('is on in-progress posters only', async () => {
    library();
    renderApp('/library');
    await screen.findByRole('link', { name: 'Alpha, 2001' });
    expect(more('Alpha')).toBeInTheDocument();
    expect(more('Gamma')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'More options for Delta' })).toBeNull();
  });

  it('shows the bar and time left on in-progress posters and nothing on a film below the minimum', async () => {
    library();
    renderApp('/library');
    await screen.findByRole('link', { name: 'Alpha, 2001' });
    expect(screen.getAllByTestId('poster-fill')).toHaveLength(3);
    expect(screen.getAllByText('1h 30m left')).toHaveLength(3);
    expect(poster('Delta, 2004')).not.toHaveTextContent('left');
  });

  it('keeps the ⋯ button outside the poster link', async () => {
    library();
    renderApp('/library');
    await screen.findByRole('link', { name: 'Alpha, 2001' });
    expect(poster('Alpha, 2001')).not.toContainElement(more('Alpha'));
  });

  it('offers Remove only when the film is not already hidden', async () => {
    library();
    renderApp('/library');
    await screen.findByRole('link', { name: 'Alpha, 2001' });

    await openMenu('Alpha');
    expect(removeItem()).toBeInTheDocument();
    await userEvent.keyboard('{Escape}');

    await openMenu('Beta');
    expect(removeItem()).not.toBeInTheDocument();
    expect(screen.getByRole('menuitem', { name: 'Mark as watched' })).toBeInTheDocument();
    expect(screen.getByRole('menuitem', { name: 'Start from beginning' })).toBeInTheDocument();
  });

  it('Remove keeps the poster, calls the API and stops offering Remove; Undo brings it back', async () => {
    const fetchMock = library();
    renderApp('/library');
    await screen.findByRole('link', { name: 'Alpha, 2001' });

    await openMenu('Alpha');
    await userEvent.click(removeItem());

    expect(poster('Alpha, 2001')).toBeInTheDocument();
    expect(toast()).toHaveTextContent('Alpha removed from Continue watching');
    const [put] = calls(fetchMock, '/api/editions/10/continue-watching');
    expect(put[1].method).toBe('PUT');
    expect(body(put)).toEqual({ hidden: true });
    await openMenu('Alpha');
    expect(removeItem()).not.toBeInTheDocument();
    await userEvent.keyboard('{Escape}');

    await userEvent.click(screen.getByRole('button', { name: 'Undo' }));
    await openMenu('Alpha');
    expect(removeItem()).toBeInTheDocument();
    await vi.waitFor(() =>
      expect(calls(fetchMock, '/api/editions/10/continue-watching').map(body)).toEqual([
        { hidden: true },
        { hidden: false },
      ]),
    );
  });

  it('Mark as watched under In progress removes the poster and the count drops; Undo restores it in place', async () => {
    const fetchMock = library();
    renderApp('/library?show=in-progress&sort=last-watched');
    await heading('4 films in progress · sorted by last watched');

    await openMenu('Beta');
    await userEvent.click(screen.getByRole('menuitem', { name: 'Mark as watched' }));

    expect(maybePoster('Beta, 2002')).not.toBeInTheDocument();
    expect(screen.getByText('3 films in progress · sorted by last watched')).toBeInTheDocument();
    expect(toast()).toHaveTextContent('Beta marked as watched');
    expect(body(calls(fetchMock, '/api/editions/20/watched')[0])).toEqual({ watched: true });
    expect(screen.getByRole('button', { name: 'Undo' })).toHaveFocus();

    await userEvent.click(screen.getByRole('button', { name: 'Undo' }));

    expect(poster('Beta, 2002')).toHaveFocus();
    expect(screen.getByText('4 films in progress · sorted by last watched')).toBeInTheDocument();
    expect(
      screen.getAllByRole('link', { name: /, 200\d$/ }).map((l) => l.getAttribute('href')),
    ).toEqual(['/films/1', '/films/2', '/films/3', '/films/4']);
    await vi.waitFor(() => {
      const [restore] = calls(fetchMock, '/api/editions/20/playback-state');
      expect(restore[1].method).toBe('PUT');
      expect(body(restore)).toMatchObject({ watched: false, positionMs: 3600000 });
    });
  });

  it('Mark as watched under All keeps the poster, watched and without the bar; Undo restores it', async () => {
    library();
    renderApp('/library');
    await screen.findByRole('link', { name: 'Alpha, 2001' });
    expect(screen.getAllByTestId('poster-fill')).toHaveLength(3);

    await openMenu('Alpha');
    await userEvent.click(screen.getByRole('menuitem', { name: 'Mark as watched' }));

    expect(poster('Alpha, 2001, watched')).toBeInTheDocument();
    expect(screen.getAllByTestId('poster-fill')).toHaveLength(2);
    expect(screen.queryByRole('button', { name: 'More options for Alpha' })).toBeNull();
    expect(screen.getByText(/^4 films · sorted by/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Undo' }));

    expect(poster('Alpha, 2001')).toBeInTheDocument();
    expect(screen.getAllByTestId('poster-fill')).toHaveLength(3);
    expect(more('Alpha')).toBeInTheDocument();
  });

  it('Undo after the list was replaced reads it again, so the film is in progress once more', async () => {
    let watched = false;
    const fetchMock = installFetch((method, url) => {
      if (url.startsWith('/api/films')) {
        const alpha = watched ? { ...ALPHA, watched: true, inProgress: false } : ALPHA;
        return respond({ total: 2, films: [alpha, GAMMA], facets: fixtures.films.facets });
      }
      if (/\/watched$/.test(url)) {
        watched = true;
        return respond({ watched: true, previous: { positionMs: 1800000, watched: false } });
      }
      if (/\/playback-state$/.test(url)) {
        watched = false;
        return respond({});
      }
      return null;
    });
    renderApp('/library');
    await screen.findByRole('link', { name: 'Alpha, 2001' });

    await openMenu('Alpha');
    await userEvent.click(screen.getByRole('menuitem', { name: 'Mark as watched' }));
    await vi.waitFor(() => expect(calls(fetchMock, '/api/editions/10/watched')).toHaveLength(1));
    await userEvent.click(within(screen.getByRole('group', { name: 'Sort' })).getByText('Title'));
    await screen.findByRole('link', { name: 'Alpha, 2001, watched' });
    expect(screen.getAllByTestId('poster-fill')).toHaveLength(1); // only Gamma's

    await userEvent.click(screen.getByRole('button', { name: 'Undo' }));

    expect(await screen.findByRole('link', { name: 'Alpha, 2001' })).toBeInTheDocument();
    expect(screen.getAllByTestId('poster-fill')).toHaveLength(2);
    await vi.waitFor(() => expect(poster('Alpha, 2001')).toHaveFocus());
  });

  it('a failed Remove rolls the poster back to offering Remove', async () => {
    library((method, url) => (/continue-watching$/.test(url) ? respond({}, 500) : null));
    renderApp('/library');
    await screen.findByRole('link', { name: 'Alpha, 2001' });

    await openMenu('Alpha');
    await userEvent.click(removeItem());

    await vi.waitFor(() =>
      expect(screen.getByRole('status', { name: 'Notification' })).toBeEmptyDOMElement(),
    );
    await openMenu('Alpha');
    expect(removeItem()).toBeInTheDocument();
  });

  it('a failed Mark as watched under In progress puts the poster and the count back', async () => {
    library((method, url) => (/\/watched$/.test(url) ? respond({}, 500) : null));
    renderApp('/library?show=in-progress&sort=last-watched');
    await heading('4 films in progress · sorted by last watched');

    await openMenu('Gamma');
    await userEvent.click(screen.getByRole('menuitem', { name: 'Mark as watched' }));

    expect(await heading('4 films in progress · sorted by last watched')).toBeInTheDocument();
    expect(poster('Gamma, 2003')).toBeInTheDocument();
    expect(toast()).toBeEmptyDOMElement();
  });

  it('two actions within 8s, then Undo of the second, leaves the first standing', async () => {
    const fetchMock = library();
    renderApp('/library');
    await screen.findByRole('link', { name: 'Alpha, 2001' });
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'], shouldAdvanceTime: true });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });

    await user.click(more('Alpha'));
    await user.click(removeItem());
    act(() => vi.advanceTimersByTime(3000));
    await user.click(more('Gamma'));
    await user.click(screen.getByRole('menuitem', { name: 'Mark as watched' }));
    expect(toast()).toHaveTextContent('Gamma marked as watched');

    await user.click(screen.getByRole('button', { name: 'Undo' }));

    // Gamma is back; Alpha is still removed from Continue watching, and its toast is gone.
    expect(poster('Gamma, 2003')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'More options for Gamma' })).toBeInTheDocument();
    await user.click(more('Alpha'));
    expect(removeItem()).not.toBeInTheDocument();
    await user.keyboard('{Escape}');
    expect(toast()).toBeEmptyDOMElement();
    expect(calls(fetchMock, '/api/editions/10/continue-watching').map(body)).toEqual([
      { hidden: true },
    ]);
  });

  it('M and Delete work on a focused poster, and Delete does nothing on a hidden film', async () => {
    const fetchMock = library();
    renderApp('/library');
    await screen.findByRole('link', { name: 'Alpha, 2001' });

    poster('Beta, 2002').focus();
    await userEvent.keyboard('{Delete}');
    expect(calls(fetchMock, '/api/editions/20/continue-watching')).toHaveLength(0);

    poster('Gamma, 2003').focus();
    await userEvent.keyboard('{Delete}');
    expect(toast()).toHaveTextContent('Gamma removed from Continue watching');

    poster('Alpha, 2001').focus();
    await userEvent.keyboard('m');
    expect(poster('Alpha, 2001, watched')).toBeInTheDocument();
  });

  describe('focus when the Undo toast expires', () => {
    async function markWatchedInProgress(films) {
      library(undefined, films);
      renderApp('/library?show=in-progress&sort=last-watched');
      await screen.findByRole('link', { name: 'Alpha, 2001' });
      vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'], shouldAdvanceTime: true });
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
      await user.click(more('Alpha'));
      await user.click(screen.getByRole('menuitem', { name: 'Mark as watched' }));
      return user;
    }

    it('moves from Undo to the poster now in the removed film’s slot', async () => {
      await markWatchedInProgress([ALPHA, BETA, GAMMA]);
      expect(screen.getByRole('button', { name: 'Undo' })).toHaveFocus();
      act(() => vi.advanceTimersByTime(8000));
      expect(screen.queryByRole('button', { name: 'Undo' })).toBeNull();
      expect(poster('Beta, 2002')).toHaveFocus();
    });

    it('moves to the previous poster when the removed film was last', async () => {
      library(undefined, [BETA, ALPHA]);
      renderApp('/library?show=in-progress&sort=last-watched');
      await screen.findByRole('link', { name: 'Alpha, 2001' });
      vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'], shouldAdvanceTime: true });
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
      await user.click(more('Alpha'));
      await user.click(screen.getByRole('menuitem', { name: 'Mark as watched' }));
      act(() => vi.advanceTimersByTime(8000));
      expect(poster('Beta, 2002')).toHaveFocus();
    });

    it('falls back to the search box when no poster is left', async () => {
      await markWatchedInProgress([ALPHA]);
      act(() => vi.advanceTimersByTime(8000));
      expect(screen.getByRole('searchbox')).toHaveFocus();
    });

    it('leaves focus alone when it was not on Undo', async () => {
      await markWatchedInProgress([ALPHA, BETA, GAMMA]);
      poster('Gamma, 2003').focus();
      act(() => vi.advanceTimersByTime(8000));
      expect(poster('Gamma, 2003')).toHaveFocus();
    });
  });

  it('a Remove that fails after a newer action on the film re-reads the list, so Remove is offered again', async () => {
    let release;
    const gate = new Promise((resolve) => {
      release = resolve;
    });
    const fetchMock = library((method, url) =>
      /\/10\/continue-watching$/.test(url) ? gate : null,
    );
    renderApp('/library');
    await screen.findByRole('link', { name: 'Alpha, 2001' });
    const listCalls = () =>
      fetchMock.mock.calls.filter((c) => c[0].startsWith('/api/films?')).length;
    const before = listCalls();

    await openMenu('Alpha');
    await userEvent.click(removeItem());
    await openMenu('Alpha');
    await userEvent.click(screen.getByRole('menuitem', { name: 'Mark as watched' }));
    expect(poster('Alpha, 2001, watched')).toBeInTheDocument();

    release(respond({}, 500));
    await vi.waitFor(() => expect(listCalls()).toBeGreaterThan(before));
    expect(await screen.findByRole('link', { name: 'Alpha, 2001' })).toBeInTheDocument();
    await openMenu('Alpha');
    expect(removeItem()).toBeInTheDocument();
  });

  describe('focus after a failed Mark as watched under In progress', () => {
    async function failing() {
      let release;
      const gate = new Promise((resolve) => {
        release = resolve;
      });
      library((method, url) => (/\/watched$/.test(url) ? gate : null));
      renderApp('/library?show=in-progress&sort=last-watched');
      await heading('4 films in progress · sorted by last watched');
      await openMenu('Gamma');
      await userEvent.click(screen.getByRole('menuitem', { name: 'Mark as watched' }));
      expect(screen.getByRole('button', { name: 'Undo' })).toHaveFocus();
      return () => release(respond({}, 500));
    }

    it('moves from Undo to the reinstated poster', async () => {
      const fail = await failing();
      fail();
      await vi.waitFor(() => expect(poster('Gamma, 2003')).toHaveFocus());
    });

    it('leaves focus alone when it is elsewhere', async () => {
      const fail = await failing();
      await userEvent.click(screen.getByRole('searchbox'));
      fail();
      expect(await screen.findByRole('link', { name: 'Gamma, 2003' })).toBeInTheDocument();
      expect(screen.getByRole('searchbox')).toHaveFocus();
    });
  });

  it('has no menu on Home’s poster rows, even for a film in progress', async () => {
    installFetch((method, url) =>
      url === '/api/home'
        ? respond({ ...fixtures.home, recentlyAdded: [ALPHA], notWatchedInYears: [] })
        : null,
    );
    renderApp('/');
    const row = await screen.findByRole('region', { name: 'Recently added' });
    expect(within(row).getByTestId('poster-fill')).toBeInTheDocument();
    expect(within(row).queryAllByRole('button')).toHaveLength(0);
  });

  describe('focus after an action that keeps the poster', () => {
    it('Remove under In progress leaves focus on the poster', async () => {
      library();
      renderApp('/library?show=in-progress&sort=last-watched');
      await heading('4 films in progress · sorted by last watched');
      await openMenu('Alpha');
      await userEvent.click(removeItem());
      expect(poster('Alpha, 2001')).toHaveFocus();
    });

    it('Remove under All leaves focus on the poster', async () => {
      library();
      renderApp('/library');
      await screen.findByRole('link', { name: 'Alpha, 2001' });
      await openMenu('Alpha');
      await userEvent.click(removeItem());
      expect(poster('Alpha, 2001')).toHaveFocus();
    });

    it('Mark as watched under All, from the menu, leaves focus on the poster', async () => {
      library();
      renderApp('/library');
      await screen.findByRole('link', { name: 'Alpha, 2001' });
      await openMenu('Alpha');
      await userEvent.click(screen.getByRole('menuitem', { name: 'Mark as watched' }));
      expect(poster('Alpha, 2001, watched')).toHaveFocus();
    });

    it('the M shortcut under All leaves focus on the poster', async () => {
      library();
      renderApp('/library');
      await screen.findByRole('link', { name: 'Alpha, 2001' });
      poster('Alpha, 2001').focus();
      await userEvent.keyboard('m');
      expect(poster('Alpha, 2001, watched')).toHaveFocus();
    });
  });

  it('a change that commits after the list was read again is read again, so it shows', async () => {
    let watched = false;
    let finish;
    const gate = new Promise((resolve) => {
      finish = resolve;
    });
    const fetchMock = installFetch((method, url) => {
      if (url.startsWith('/api/films')) {
        const alpha = watched ? { ...ALPHA, watched: true, inProgress: false } : ALPHA;
        return respond({ total: 2, films: [alpha, GAMMA], facets: fixtures.films.facets });
      }
      if (/\/watched$/.test(url)) {
        return gate.then(() => {
          watched = true;
          return respond({ watched: true, previous: { positionMs: 1800000, watched: false } });
        });
      }
      return null;
    });
    renderApp('/library');
    await screen.findByRole('link', { name: 'Alpha, 2001' });

    await openMenu('Alpha');
    await userEvent.click(screen.getByRole('menuitem', { name: 'Mark as watched' }));
    const listCalls = () =>
      fetchMock.mock.calls.filter((c) => c[0].startsWith('/api/films')).length;
    const before = listCalls();
    await userEvent.click(within(screen.getByRole('group', { name: 'Sort' })).getByText('Title'));
    await vi.waitFor(() => expect(listCalls()).toBeGreaterThan(before));
    await vi.waitFor(() => expect(poster('Alpha, 2001')).toBeInTheDocument()); // the old state

    finish();
    expect(await screen.findByRole('link', { name: 'Alpha, 2001, watched' })).toBeInTheDocument();
    expect(screen.getAllByTestId('poster-fill')).toHaveLength(1);
  });
});
