import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { SIGNED_IN_EVENT } from '../../api/client.js';
import { fixtures, installFetch, renderApp, respond } from '../../test/mockApi.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

const URL_BASE = '/api/admin/christmas';
const fresh = () => structuredClone(fixtures.christmas);

/** A promise a test settles when it chooses. */
function deferred() {
  let resolve;
  const promise = new Promise((r) => {
    resolve = r;
  });
  return { promise, resolve };
}

/** Serves the screen's GET with `get()` (a body, a Response or a promise of one). */
function serve({ get = fresh } = {}) {
  return installFetch((method, url) => {
    if (url !== URL_BASE) return null;
    const answer = get();
    return answer instanceof Response || answer instanceof Promise ? answer : respond(answer);
  });
}

async function open() {
  renderApp('/admin/christmas');
  await screen.findByRole('heading', { level: 1, name: 'Christmas films' });
  await screen.findByRole('tablist', { name: 'Christmas lists' });
}

const row = (title) => screen.getByRole('listitem', { name: title });

describe('Christmas films: the page', () => {
  it('shows the eyebrow, title, rule line, status card, meter and keyword strip', async () => {
    serve();
    await open();
    // The rail's name and the page's eyebrow.
    expect(screen.getAllByText('Admin')).toHaveLength(2);
    expect(
      screen.getByText(/A film counts if TMDB tags it with the “christmas” keyword/),
    ).toBeInTheDocument();
    const card = screen.getByRole('region', { name: 'Christmas week status' });
    expect(within(card).getByText('14 films')).toBeInTheDocument();
    expect(card).toHaveTextContent('will show in Christmas week (Mon 14 Dec to Sun 20 Dec).');
    expect(card).toHaveTextContent(
      'Home shows up to 12, oldest first. Under 5 and it falls back to the usual weekly theme.',
    );
    expect(card).toHaveTextContent('12 places on Home');
    expect(card).toHaveTextContent('2 over');
    const meter = within(card).getByTestId('meter');
    expect(meter).toHaveAttribute('aria-hidden', 'true');
    expect(meter).toHaveAttribute('data-filled', '12');
    expect(meter).toHaveAttribute('data-over', '2');
    expect(card).toHaveTextContent(
      'Keywords fetched for 1,374 of 1,412 films. The other 38 are on their way, so this list may still grow.',
    );
    expect(within(card).getByTestId('keyword-fill')).toHaveStyle({ width: '97%' });
  });

  it('counts the three tabs and lists the In tab, numbered, with reason chips', async () => {
    serve();
    await open();
    expect(screen.getByRole('tab', { name: 'In Christmas week, 14' })).toHaveAttribute(
      'aria-selected',
      'true',
    );
    expect(screen.getByRole('tab', { name: 'Added by me, 1' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Left out by me, 3' })).toBeInTheDocument();
    const die = row('Die Hard');
    expect(die).toHaveTextContent('6');
    expect(die).toHaveTextContent('Die Hard · 1988');
    expect(die).toHaveTextContent('TMDB keyword');
    // Read only: the actions arrive with the next change.
    expect(within(die).queryByRole('button')).not.toBeInTheDocument();
    expect(row('Trading Places')).toHaveTextContent('Added by me');
  });

  it('draws the cut line after row 12 and dims the rows below it', async () => {
    serve();
    await open();
    const items = within(screen.getByRole('tabpanel')).getAllByRole('listitem');
    expect(items).toHaveLength(15);
    expect(items[12]).toHaveTextContent(
      "Home shows 12. These 2 are in, but won't fit. Leave out an older film to make room.",
    );
    expect(row('Elf')).not.toHaveClass('xmas-row--cut');
    expect(row('Love Actually')).toHaveClass('xmas-row--cut');
    expect(row('Arthur Christmas')).toHaveClass('xmas-row--cut');
  });

  it('shows the Added tab with its extra line and the Left out tab with its own', async () => {
    serve();
    await open();
    await userEvent.click(screen.getByRole('tab', { name: 'Added by me, 1' }));
    const added = row('Trading Places');
    expect(added).toHaveTextContent("TMDB doesn't tag it, so it's only in because you added it.");
    expect(added).not.toHaveTextContent('4');
    expect(screen.queryByText(/Home shows 12/)).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('tab', { name: 'Left out by me, 3' }));
    const iron = row('Iron Man 3');
    expect(iron).toHaveTextContent('Left out by me');
    expect(iron).toHaveTextContent('TMDB tags it “christmas”. Put back and it shows again.');
    expect(row('Rare Exports')).toHaveTextContent("TMDB doesn't tag it, so Put back");
  });

  it('says so when a tab has nothing in it', async () => {
    const data = fresh();
    data.added = [];
    serve({ get: () => data });
    await open();
    await userEvent.click(screen.getByRole('tab', { name: 'Added by me, 0' }));
    expect(screen.getByText("You haven't added any films.")).toBeInTheDocument();
  });
});

describe('Christmas films: keywords still arriving', () => {
  it('asks again every 30 seconds while running and shows what arrives', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    let data = fresh();
    data.keywords = { fetched: 1374, total: 1412, running: true };
    const fetchMock = serve({ get: () => data });
    await open();
    const gets = () => fetchMock.mock.calls.filter(([url]) => url === URL_BASE).length;
    expect(gets()).toBe(1);

    data = { ...data, keywords: { fetched: 1400, total: 1412, running: true } };
    act(() => vi.advanceTimersByTime(29000));
    expect(gets()).toBe(1);
    await act(async () => vi.advanceTimersByTime(1500));
    await screen.findByText(/Keywords fetched for 1,400 of 1,412/);
    expect(gets()).toBe(2);

    data = { ...data, keywords: { fetched: 1412, total: 1412, running: false } };
    await act(async () => vi.advanceTimersByTime(30000));
    await waitFor(() => expect(screen.queryByText(/Keywords fetched for/)).not.toBeInTheDocument());
    const after = gets();
    act(() => vi.advanceTimersByTime(120000));
    expect(gets()).toBe(after);
  });

  it('does not ask again when keywords are not running', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const fetchMock = serve();
    await open();
    act(() => vi.advanceTimersByTime(120000));
    expect(fetchMock.mock.calls.filter(([url]) => url === URL_BASE)).toHaveLength(1);
  });

  it('stops asking when the page is left', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const data = fresh();
    data.keywords = { fetched: 1, total: 1412, running: true };
    const fetchMock = serve({ get: () => data });
    await open();
    const nav = screen.getByRole('navigation', { name: 'Admin sections' });
    await userEvent.click(within(nav).getByRole('link', { name: 'Library' }));
    await screen.findByRole('heading', { level: 1, name: 'Library' });
    const before = fetchMock.mock.calls.filter(([url]) => url === URL_BASE).length;
    act(() => vi.advanceTimersByTime(120000));
    expect(fetchMock.mock.calls.filter(([url]) => url === URL_BASE)).toHaveLength(before);
  });

  it('keeps the list and says nothing when a poll fails', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const data = fresh();
    data.keywords = { fetched: 1, total: 1412, running: true };
    let fail = false;
    serve({ get: () => (fail ? respond(null, 500) : data) });
    await open();
    fail = true;
    await act(async () => vi.advanceTimersByTime(30500));
    expect(row('Die Hard')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});

describe('Christmas films: states', () => {
  it('shows a static skeleton while loading, with the header already there', async () => {
    const gate = deferred();
    installFetch((method, url) => (url === URL_BASE ? gate.promise : null));
    renderApp('/admin/christmas');
    expect(await screen.findByRole('status', { name: 'Loading' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: 'Christmas films' })).toBeInTheDocument();
    expect(screen.queryByRole('tablist')).not.toBeInTheDocument();
    await act(async () => gate.resolve(respond(fresh())));
    expect(await screen.findByRole('tablist', { name: 'Christmas lists' })).toBeInTheDocument();
    expect(screen.queryByRole('status', { name: 'Loading' })).not.toBeInTheDocument();
  });

  it('says no Christmas films yet while keywords are still being fetched', async () => {
    const data = fresh();
    data.inWeek = [];
    data.added = [];
    data.leftOut = [];
    data.fallsBack = true;
    data.keywords = { fetched: 212, total: 1412, running: true };
    serve({ get: () => data });
    await open0();
    expect(screen.getByRole('heading', { name: 'No Christmas films yet' })).toBeInTheDocument();
    expect(
      screen.getByText(
        "CineFilm finds them from TMDB keywords, and it's still fetching those: 212 of 1,412 films so far. Films appear here as their keywords arrive, usually within the hour.",
      ),
    ).toBeInTheDocument();
    expect(screen.getByText('212 / 1,412')).toBeInTheDocument();
    expect(screen.queryByRole('tablist')).not.toBeInTheDocument();
  });

  it('says nothing is tagged when keywords are complete and the lists are still empty', async () => {
    const data = fresh();
    data.inWeek = [];
    data.added = [];
    data.leftOut = [];
    data.fallsBack = true;
    data.keywords = { fetched: 1412, total: 1412, running: false };
    serve({ get: () => data });
    await open0();
    expect(screen.getByText('No films in your library are tagged Christmas.')).toBeInTheDocument();
    expect(screen.queryByText('212 / 1,412')).not.toBeInTheDocument();
    expect(screen.queryByText(/still fetching/)).not.toBeInTheDocument();
  });

  it('keeps the tabs when only the In list is empty but a film was left out', async () => {
    const data = fresh();
    data.inWeek = [];
    data.added = [];
    data.fallsBack = true;
    serve({ get: () => data });
    await open();
    expect(screen.getByText('No films are in Christmas week yet.')).toBeInTheDocument();
    expect(screen.getByText(/0 films/)).toBeInTheDocument();
  });

  it('shows the error with Try again, which asks again and shows the list', async () => {
    let fail = true;
    const fetchMock = serve({ get: () => (fail ? respond(null, 500) : fresh()) });
    renderApp('/admin/christmas');
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(
      'Couldn’t load Christmas films. CineFilm didn’t answer. Your choices are safe; nothing was changed.',
    );
    expect(screen.queryByRole('tablist')).not.toBeInTheDocument();

    fail = false;
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('tablist', { name: 'Christmas lists' })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(fetchMock.mock.calls.filter(([url]) => url === URL_BASE)).toHaveLength(2);
  });

  it('reads again by itself once the owner has signed in after a 401', async () => {
    let status = 401;
    const fetchMock = serve({ get: () => (status === 401 ? respond(null, 401) : fresh()) });
    renderApp('/admin/christmas');
    await screen.findByRole('alert');

    status = 200;
    act(() => {
      window.dispatchEvent(new CustomEvent(SIGNED_IN_EVENT));
    });
    expect(await screen.findByRole('tablist', { name: 'Christmas lists' })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(fetchMock.mock.calls.filter(([url]) => url === URL_BASE)).toHaveLength(2);
  });

  it('keeps the error and its button while Try again is running, and ignores a second press', async () => {
    let mode = 'fail';
    const gate = deferred();
    const fetchMock = installFetch((method, url) => {
      if (url !== URL_BASE) return null;
      return mode === 'fail' ? respond(null, 500) : gate.promise;
    });
    renderApp('/admin/christmas');
    await screen.findByRole('alert');
    mode = 'hold';
    const retry = screen.getByRole('button', { name: 'Try again' });
    await userEvent.click(retry);
    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(retry).toHaveAttribute('aria-busy', 'true');
    await userEvent.click(retry);
    expect(fetchMock.mock.calls.filter(([url]) => url === URL_BASE)).toHaveLength(2);
    await act(async () => gate.resolve(respond(fresh())));
    expect(await screen.findByRole('tablist', { name: 'Christmas lists' })).toBeInTheDocument();
  });

  it('under 5 says the week falls back and how many more are needed', async () => {
    const data = fresh();
    data.inWeek = data.inWeek.slice(4, 7);
    data.added = [];
    data.fallsBack = true;
    data.keywords = { fetched: 1412, total: 1412, running: false };
    serve({ get: () => data });
    await open();
    const card = screen.getByRole('region', { name: 'Christmas week status' });
    expect(card).toHaveTextContent(
      '3 films are in, which is under 5, so Christmas week shows the usual weekly theme instead.',
    );
    expect(card).toHaveTextContent('Home needs at least 5 Christmas films to show them.');
    expect(screen.queryByText(/Home shows 12\. These/)).not.toBeInTheDocument();
  });
});

/** Opens the page when there is no tablist to wait for. */
async function open0() {
  renderApp('/admin/christmas');
  await screen.findByRole('heading', { name: 'No Christmas films yet' });
}
