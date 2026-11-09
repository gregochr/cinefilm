import { act, fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { installFetch, renderApp, respond } from '../test/mockApi.jsx';

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

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
    expect(within(row()).getByText('3 in progress')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Arrival removed from Continue watching');
    const [put] = calls(fetchMock, '/api/editions/21/continue-watching');
    expect(put[1].method).toBe('PUT');
    expect(body(put)).toEqual({ hidden: true });
  });

  it('Undo brings the card back in its place, restores the count and reverses the call', async () => {
    const fetchMock = await open();
    await removeViaMenu('Arrival');

    await userEvent.click(screen.getByRole('button', { name: 'Undo' }));

    expect(hrefs()).toEqual(['/films/2', '/films/3', '/films/4', '/films/5']);
    expect(within(row()).getByText('4 in progress')).toBeInTheDocument();
    expect(screen.getByRole('status')).toBeEmptyDOMElement();
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
    expect(screen.getByRole('status')).toHaveTextContent('Chinatown marked as watched');
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

    expect(screen.getByRole('status')).toHaveTextContent('Moon marked as watched');
    expect(screen.getByRole('status')).not.toHaveTextContent('Arrival');
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
    expect(screen.getByRole('status')).toHaveTextContent('Chinatown marked as watched');
    expect(cardLink(/^Paris, Texas/)).toHaveFocus();
  });

  it('Delete and M do nothing when no Continue watching card has focus', async () => {
    const fetchMock = await open();
    await userEvent.keyboard('{Delete}m');
    expect(within(row()).getByText('4 in progress')).toBeInTheDocument();
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

    expect(await within(row()).findByRole('link', { name: 'Arrival, 56m left' })).toBeInTheDocument();
    expect(screen.getByRole('status')).toBeEmptyDOMElement();
    expect(within(row()).getByText('4 in progress')).toBeInTheDocument();
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
    expect(screen.getByRole('status')).toHaveTextContent('Moon removed from Continue watching');

    await userEvent.click(screen.getByRole('button', { name: 'Undo' }));
    expect(screen.getByRole('region', { name: 'Resume Moon' })).toBeInTheDocument();
  });

  it('keeps the Undo pill when removing the last film leaves nothing else on Home', async () => {
    homeWith({ continueWatching: [moon] });
    renderApp('/');
    await screen.findByRole('region', { name: 'Continue watching' });
    await removeViaMenu('Moon');

    expect(screen.getByRole('status')).toHaveTextContent('Moon removed from Continue watching');
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
    expect(within(theme).getByRole('link', { name: 'Chinatown, 1974, watched' })).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Undo' }));
    expect(within(theme).getByRole('link', { name: 'Chinatown, 1974' })).toBeInTheDocument();
    expect(within(theme).queryByRole('link', { name: 'Chinatown, 1974, watched' })).not.toBeInTheDocument();
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
});
