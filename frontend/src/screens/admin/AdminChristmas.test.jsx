import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp, respond } from '../../test/mockApi.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

const URL_BASE = '/api/admin/christmas';
const fresh = () => structuredClone(fixtures.christmas);

/** The body the server would answer after leaving a keyword film out. */
function leftOut(data, tmdbId) {
  const rest = { ...data.inWeek.find((f) => f.tmdbId === tmdbId) };
  delete rest.reason;
  return {
    ...data,
    inWeek: data.inWeek.filter((f) => f.tmdbId !== tmdbId),
    leftOut: [...data.leftOut, { ...rest, taggedByTmdb: true }],
  };
}

/** The body after a choice was deleted for a left-out film (it counts again, in Home's order: here, last). */
function putBack(data, tmdbId) {
  const rest = { ...data.leftOut.find((f) => f.tmdbId === tmdbId), reason: 'KEYWORD' };
  delete rest.taggedByTmdb;
  return {
    ...data,
    leftOut: data.leftOut.filter((f) => f.tmdbId !== tmdbId),
    inWeek: [...data.inWeek, rest],
  };
}

/** A promise a test settles when it chooses. */
function deferred() {
  let resolve;
  const promise = new Promise((r) => {
    resolve = r;
  });
  return { promise, resolve };
}

/** Serves GET with `get()` and PUT with `put(tmdbId, choice)` (a Response or a promise of one). */
function serve({ get = fresh, put = () => respond(fresh()) } = {}) {
  return installFetch((method, url, init) => {
    if (!url.startsWith(URL_BASE)) return null;
    if (method === 'GET') {
      const answer = get();
      return answer instanceof Response || answer instanceof Promise ? answer : respond(answer);
    }
    const tmdbId = Number(url.slice(URL_BASE.length + 1));
    return put(tmdbId, JSON.parse(init.body).choice);
  });
}

const putCalls = (fetchMock) =>
  fetchMock.mock.calls.filter(([url, init]) => url.startsWith(URL_BASE) && init?.method === 'PUT');

async function open() {
  renderApp('/admin/christmas');
  await screen.findByRole('heading', { level: 1, name: 'Christmas films' });
  await screen.findByRole('tablist', { name: 'Christmas lists' });
}

const row = (title) => screen.getByRole('listitem', { name: title });
const toast = () => screen.getByRole('status', { name: 'Christmas notification' });

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
    expect(card).toHaveTextContent('will show in Christmas week (Mon 14 Dec to Fri 25 Dec).');
    expect(card).toHaveTextContent(
      'Home shows up to 12: films you added first, then a different mix each day. Under 5 and it falls back to the usual weekly theme.',
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

  it('counts the three tabs and lists the In tab, numbered, with reason chips and one action each', async () => {
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
    expect(within(die).getAllByRole('button')).toHaveLength(1);
    expect(within(die).getByRole('button', { name: 'Leave out Die Hard' })).toHaveTextContent(
      'Leave out',
    );
    const trading = row('Trading Places');
    expect(trading).toHaveTextContent('Added by me');
    expect(within(trading).getByRole('button', { name: 'Remove Trading Places' })).toBeVisible();
  });

  it('draws the cut line after row 12 and dims the rows below it, which still work', async () => {
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
    expect(
      within(row('Arthur Christmas')).getByRole('button', { name: 'Leave out Arthur Christmas' }),
    ).toBeEnabled();
  });

  it('shows the Added tab with its extra line and the Left out tab with Put back', async () => {
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
    expect(within(iron).getByRole('button', { name: 'Put back Iron Man 3' })).toBeVisible();
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

describe('Christmas films: changes and undo', () => {
  it('leaves a film out, redraws from the answer, toasts, and Undo puts it back and refocuses its button', async () => {
    const original = fresh();
    const out = leftOut(original, 106);
    const fetchMock = serve({
      put: (id, choice) => respond(choice === 'EXCLUDE' ? out : original),
    });
    await open();

    await userEvent.click(screen.getByRole('button', { name: 'Leave out Die Hard' }));

    await waitFor(() => expect(screen.queryByRole('listitem', { name: 'Die Hard' })).toBeNull());
    expect(toast()).toHaveTextContent("Left out Die Hard. It won't show at Christmas.");
    expect(screen.getByRole('tab', { name: 'In Christmas week, 13' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Left out by me, 4' })).toBeInTheDocument();
    // The row's button is gone: focus goes to the next row's button, not to the toast after the list.
    expect(screen.getByRole('button', { name: 'Leave out Scrooged' })).toHaveFocus();
    expect(within(toast()).getByRole('button', { name: 'Undo' })).not.toHaveFocus();
    const [url, init] = putCalls(fetchMock)[0];
    expect(url).toBe('/api/admin/christmas/106');
    expect(init.headers['X-CineFilm']).toBe('1');
    expect(JSON.parse(init.body)).toEqual({ choice: 'EXCLUDE' });

    await userEvent.click(within(toast()).getByRole('button', { name: 'Undo' }));

    const back = await screen.findByRole('button', { name: 'Leave out Die Hard' });
    expect(back).toHaveFocus();
    expect(JSON.parse(putCalls(fetchMock)[1][1].body)).toEqual({ choice: null });
    expect(toast()).toHaveTextContent("Put back Die Hard. It'll show at Christmas.");
    expect(within(toast()).queryByRole('button', { name: 'Undo' })).not.toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'In Christmas week, 14' })).toBeInTheDocument();
  });

  it('after an Undo, focus lands on the clicked row even when a sibling shares its TMDB id', async () => {
    const original = fresh();
    const sibling = {
      filmId: 99,
      tmdbId: 104,
      title: 'Trading Places (TV)',
      year: 1983,
      posterUrl: null,
      reason: 'KEYWORD',
    };
    original.inWeek.push(sibling);
    const out = { ...original, inWeek: original.inWeek.filter((f) => f.filmId !== 99) };
    serve({
      get: () => original,
      put: (id, choice) => respond(choice === 'EXCLUDE' ? out : original),
    });
    await open();

    await userEvent.click(screen.getByRole('button', { name: 'Leave out Trading Places (TV)' }));
    await userEvent.click(await within(toast()).findByRole('button', { name: 'Undo' }));

    const back = await screen.findByRole('button', { name: 'Leave out Trading Places (TV)' });
    expect(back).toHaveFocus();
    expect(screen.getByRole('button', { name: 'Remove Trading Places' })).not.toHaveFocus();
  });

  it('removes an added film and Undo adds it back with INCLUDE', async () => {
    const original = fresh();
    const removed = {
      ...original,
      inWeek: original.inWeek.filter((f) => f.tmdbId !== 104),
      added: [],
    };
    const fetchMock = serve({ put: (id, choice) => respond(choice === null ? removed : original) });
    await open();

    await userEvent.click(screen.getByRole('button', { name: 'Remove Trading Places' }));
    await waitFor(() => expect(toast()).toHaveTextContent('Removed Trading Places.'));
    expect(JSON.parse(putCalls(fetchMock)[0][1].body)).toEqual({ choice: null });
    expect(screen.getByRole('tab', { name: 'Added by me, 0' })).toBeInTheDocument();

    await userEvent.click(within(toast()).getByRole('button', { name: 'Undo' }));
    await waitFor(() => expect(toast()).toHaveTextContent('Added Trading Places.'));
    expect(JSON.parse(putCalls(fetchMock)[1][1].body)).toEqual({ choice: 'INCLUDE' });
    expect(await screen.findByRole('button', { name: 'Remove Trading Places' })).toHaveFocus();
  });

  it('puts a left-out film back from the Left out tab and Undo leaves it out again', async () => {
    const original = fresh();
    const back = putBack(original, 120);
    const fetchMock = serve({ put: (id, choice) => respond(choice === null ? back : original) });
    await open();
    await userEvent.click(screen.getByRole('tab', { name: 'Left out by me, 3' }));

    await userEvent.click(screen.getByRole('button', { name: 'Put back Iron Man 3' }));
    await waitFor(() =>
      expect(toast()).toHaveTextContent("Put back Iron Man 3. It'll show at Christmas."),
    );
    expect(JSON.parse(putCalls(fetchMock)[0][1].body)).toEqual({ choice: null });
    expect(screen.queryByRole('listitem', { name: 'Iron Man 3' })).not.toBeInTheDocument();

    await userEvent.click(within(toast()).getByRole('button', { name: 'Undo' }));
    const button = await screen.findByRole('button', { name: 'Put back Iron Man 3' });
    expect(JSON.parse(putCalls(fetchMock)[1][1].body)).toEqual({ choice: 'EXCLUDE' });
    expect(button).toHaveFocus();
    expect(toast()).toHaveTextContent("Left out Iron Man 3. It won't show at Christmas.");
  });

  it('works from the keyboard: Enter on the focused button leaves the film out', async () => {
    const original = fresh();
    const fetchMock = serve({ put: () => respond(leftOut(original, 105)) });
    await open();
    screen.getByRole('button', { name: 'Leave out Gremlins' }).focus();
    await userEvent.keyboard('{Enter}');
    await waitFor(() => expect(toast()).toHaveTextContent('Left out Gremlins.'));
    expect(putCalls(fetchMock)).toHaveLength(1);
  });

  it('shows the server words for a 409 in the toast and changes nothing', async () => {
    serve({
      put: () => respond({ error: 'A scan is running — try again when it finishes' }, 409),
    });
    await open();
    await userEvent.click(screen.getByRole('button', { name: 'Leave out Die Hard' }));
    await waitFor(() =>
      expect(toast()).toHaveTextContent('A scan is running — try again when it finishes'),
    );
    expect(within(toast()).queryByRole('button', { name: 'Undo' })).not.toBeInTheDocument();
    expect(row('Die Hard')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'In Christmas week, 14' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Leave out Die Hard' })).not.toHaveAttribute(
      'aria-disabled',
    );
  });

  it('says the scan is running for a 409 with no words, and a plain failure otherwise', async () => {
    let status = 409;
    serve({ put: () => respond(null, status) });
    await open();
    await userEvent.click(screen.getByRole('button', { name: 'Leave out Die Hard' }));
    await waitFor(() =>
      expect(toast()).toHaveTextContent('A scan is running — try again when it finishes'),
    );
    status = 500;
    await userEvent.click(screen.getByRole('button', { name: 'Leave out Die Hard' }));
    await waitFor(() =>
      expect(toast()).toHaveTextContent("Couldn't save that change. Nothing was changed."),
    );
    expect(row('Die Hard')).toBeInTheDocument();
  });

  it('disables a row while its request is in flight: a second click sends nothing', async () => {
    const original = fresh();
    const gate = deferred();
    const fetchMock = serve({ put: () => gate.promise });
    await open();
    const button = screen.getByRole('button', { name: 'Leave out Die Hard' });
    await userEvent.click(button);
    expect(button).toHaveAttribute('aria-disabled', 'true');
    await userEvent.click(button);
    expect(putCalls(fetchMock)).toHaveLength(1);
    // Another row is not held.
    expect(screen.getByRole('button', { name: 'Leave out Elf' })).not.toHaveAttribute(
      'aria-disabled',
    );

    await act(async () => gate.resolve(respond(leftOut(original, 106))));
    await waitFor(() => expect(toast()).toHaveTextContent('Left out Die Hard.'));
  });

  it('drops the older of two answers, then reads again so a change the newest answer lacks comes back', async () => {
    const original = fresh();
    const onlyDieHard = leftOut(original, 106);
    const onlyGremlins = leftOut(original, 105);
    const both = leftOut(onlyDieHard, 105);
    const slow = deferred();
    let truth = original;
    const fetchMock = serve({
      get: () => truth,
      put: (id) => (id === 106 ? slow.promise : respond(onlyGremlins)),
    });
    await open();

    await userEvent.click(screen.getByRole('button', { name: 'Leave out Die Hard' }));
    await userEvent.click(screen.getByRole('button', { name: 'Leave out Gremlins' }));
    await waitFor(() => expect(screen.queryByRole('listitem', { name: 'Gremlins' })).toBeNull());
    // The server handled Gremlins first: its answer lacks the Die Hard change, which is still listed.
    expect(row('Die Hard')).toBeInTheDocument();

    // The older answer lands last and is dropped; the read that follows shows both changes.
    truth = both;
    await act(async () => slow.resolve(respond(onlyDieHard)));
    await waitFor(() => expect(screen.queryByRole('listitem', { name: 'Die Hard' })).toBeNull());
    expect(screen.queryByRole('listitem', { name: 'Gremlins' })).not.toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'In Christmas week, 12' })).toBeInTheDocument();
    expect(toast()).toHaveTextContent('Left out Die Hard.');
    expect(fetchMock.mock.calls.filter(([url]) => url === URL_BASE)).toHaveLength(2);
  });

  it('puts focus on the list when a failed Undo replaces the toast its button was in', async () => {
    const original = fresh();
    let fail = false;
    serve({
      put: () =>
        fail
          ? respond({ error: 'A scan is running — try again when it finishes' }, 409)
          : respond(leftOut(original, 106)),
    });
    await open();
    await userEvent.click(screen.getByRole('button', { name: 'Leave out Die Hard' }));
    const undo = await within(toast()).findByRole('button', { name: 'Undo' });
    fail = true;
    await userEvent.click(undo);
    await waitFor(() => expect(toast()).toHaveTextContent('A scan is running'));
    expect(screen.getByRole('tabpanel')).toHaveFocus();
  });

  it('puts focus on the empty state when the last film goes and a failed Undo removes the focused button', async () => {
    const original = fresh();
    original.inWeek = original.inWeek.filter((f) => f.tmdbId === 104);
    original.leftOut = [];
    original.fallsBack = true;
    original.keywords = { fetched: 1412, total: 1412, running: false };
    const emptied = { ...original, inWeek: [], added: [] };
    let fail = false;
    serve({
      get: () => original,
      put: () =>
        fail
          ? respond({ error: 'A scan is running — try again when it finishes' }, 409)
          : respond(emptied),
    });
    await open();

    await userEvent.click(screen.getByRole('button', { name: 'Remove Trading Places' }));
    const undo = await within(toast()).findByRole('button', { name: 'Undo' });
    const empty = screen.getByRole('region', { name: 'No Christmas films' });
    expect(screen.queryByRole('tabpanel')).not.toBeInTheDocument();
    expect(empty).toHaveFocus();
    undo.focus();

    fail = true;
    await userEvent.click(undo);
    await waitFor(() => expect(toast()).toHaveTextContent('A scan is running'));
    expect(empty).toHaveFocus();
  });

  it('moves focus on to the same row when it is still listed, else the next row, else the list', async () => {
    const original = fresh();
    let answer = original;
    serve({ put: () => respond(answer) });
    await open();

    // The answer still lists the row: focus stays on its button.
    await userEvent.click(screen.getByRole('button', { name: 'Leave out Die Hard' }));
    await within(toast()).findByRole('button', { name: 'Undo' });
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Leave out Die Hard' })).toHaveFocus(),
    );

    // The last row goes with nothing after it: the tab panel.
    const last = original.inWeek[original.inWeek.length - 1];
    answer = { ...original, inWeek: original.inWeek.filter((f) => f.filmId !== last.filmId) };
    await userEvent.click(screen.getByRole('button', { name: `Leave out ${last.title}` }));
    await waitFor(() => expect(screen.getByRole('tabpanel')).toHaveFocus());
  });

  it('keeps the toast while Undo holds focus, then counts 8 seconds from when focus leaves', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const original = fresh();
    serve({ put: () => respond(leftOut(original, 106)) });
    await open();
    await userEvent.click(screen.getByRole('button', { name: 'Leave out Die Hard' }));
    const undo = await within(toast()).findByRole('button', { name: 'Undo' });

    expect(undo).not.toHaveFocus();
    undo.focus();
    act(() => vi.advanceTimersByTime(20000));
    expect(toast()).toHaveTextContent('Left out Die Hard.');
    act(() => undo.blur());
    act(() => vi.advanceTimersByTime(7900));
    expect(toast()).toHaveTextContent('Left out Die Hard.');
    act(() => vi.advanceTimersByTime(200));
    expect(toast()).toBeEmptyDOMElement();
  });

  it('after a keyboard row action holds the toast while focus rests on the moved-to button, and counts 8 seconds from Tab', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const original = fresh();
    serve({ put: () => respond(leftOut(original, 106)) });
    await open();
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    screen.getByRole('button', { name: 'Leave out Die Hard' }).focus();
    await user.keyboard('{Enter}');
    const next = await screen.findByRole('button', { name: 'Leave out Scrooged' });
    await waitFor(() => expect(next).toHaveFocus());

    act(() => vi.advanceTimersByTime(20000));
    expect(toast()).toHaveTextContent('Left out Die Hard.');
    await user.tab();
    expect(next).not.toHaveFocus();
    act(() => vi.advanceTimersByTime(7900));
    expect(toast()).toHaveTextContent('Left out Die Hard.');
    act(() => vi.advanceTimersByTime(200));
    expect(toast()).toBeEmptyDOMElement();
  });

  it('counts the 8 seconds once focus, lost with the held button, lands on another control', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const original = fresh();
    serve({ put: () => respond(leftOut(original, 106)) });
    await open();
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    screen.getByRole('button', { name: 'Leave out Die Hard' }).focus();
    await user.keyboard('{Enter}');
    const held = await screen.findByRole('button', { name: 'Leave out Scrooged' });
    await waitFor(() => expect(held).toHaveFocus());

    // The button goes (a quiet re-read dropped its row): no blur is sent, focus falls to the page body.
    act(() => held.remove());
    expect(document.body).toHaveFocus();
    act(() => vi.advanceTimersByTime(20000));
    expect(toast()).toHaveTextContent('Left out Die Hard.');

    act(() => screen.getByRole('combobox').focus());
    act(() => vi.advanceTimersByTime(7900));
    expect(toast()).toHaveTextContent('Left out Die Hard.');
    act(() => vi.advanceTimersByTime(200));
    expect(toast()).toBeEmptyDOMElement();
  });

  it('removes its listeners and starts no timer when the screen goes while it holds the toast', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const original = fresh();
    serve({ put: () => respond(leftOut(original, 106)) });
    const view = renderApp('/admin/christmas');
    await screen.findByRole('tablist', { name: 'Christmas lists' });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    screen.getByRole('button', { name: 'Leave out Die Hard' }).focus();
    await user.keyboard('{Enter}');
    const held = await screen.findByRole('button', { name: 'Leave out Scrooged' });
    await waitFor(() => expect(held).toHaveFocus());

    const removed = vi.spyOn(document, 'removeEventListener');
    const timers = vi.getTimerCount();
    view.unmount();
    expect(removed).toHaveBeenCalledWith('focusin', expect.any(Function));
    // Nothing is left to fire on the gone screen.
    expect(vi.getTimerCount()).toBeLessThanOrEqual(timers);
    act(() => vi.advanceTimersByTime(20000));
    removed.mockRestore();
  });

  it('leaves focus alone when the owner moved it before the answer came', async () => {
    const original = fresh();
    const gate = deferred();
    serve({
      get: () => original,
      put: () => gate.promise,
    });
    await open();
    screen.getByRole('button', { name: 'Leave out Die Hard' }).focus();
    await userEvent.keyboard('{Enter}');
    const tab = screen.getByRole('tab', { name: /Added by me/ });
    tab.focus();
    await act(async () => gate.resolve(respond(leftOut(original, 106))));
    await waitFor(() => expect(screen.queryByRole('listitem', { name: 'Die Hard' })).toBeNull());
    expect(tab).toHaveFocus();
  });

  it('leaves focus in the search field when the owner went there before the answer came', async () => {
    const original = fresh();
    const gate = deferred();
    serve({ put: () => gate.promise });
    await open();
    screen.getByRole('button', { name: 'Leave out Die Hard' }).focus();
    await userEvent.keyboard('{Enter}');
    const field = screen.getByRole('combobox');
    field.focus();
    await act(async () => gate.resolve(respond(leftOut(original, 106))));
    await waitFor(() => expect(screen.queryByRole('listitem', { name: 'Die Hard' })).toBeNull());
    expect(field).toHaveFocus();
  });

  it('replaces the toast with a newer action, and it goes after 8 seconds', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const original = fresh();
    serve({ put: (id) => respond(leftOut(original, id)) });
    await open();

    await userEvent.click(screen.getByRole('button', { name: 'Leave out Die Hard' }));
    await waitFor(() => expect(toast()).toHaveTextContent('Left out Die Hard.'));
    await userEvent.click(screen.getByRole('button', { name: 'Leave out Gremlins' }));
    await waitFor(() => expect(toast()).toHaveTextContent('Left out Gremlins.'));
    expect(toast()).not.toHaveTextContent('Die Hard');

    // Focus is on a row, not on Undo, so nothing holds the toast: it counts down at once.
    expect(within(toast()).getByRole('button', { name: 'Undo' })).not.toHaveFocus();
    act(() => vi.advanceTimersByTime(7900));
    expect(toast()).toHaveTextContent('Left out Gremlins.');
    act(() => vi.advanceTimersByTime(200));
    expect(toast()).toBeEmptyDOMElement();
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
    // One polite status only: the keyword strip lives in the status card, which this state replaces.
    expect(screen.getByText(/212 of 1,412 films so far/)).toHaveAttribute('role', 'status');
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
    expect(
      screen.getByText('No films in your library are tagged Christmas. Add any you count.'),
    ).toBeInTheDocument();
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

  it('after a 401, signing in through the sign-in card shows the list without pressing Try again', async () => {
    let signedIn = false;
    const fetchMock = installFetch((method, url, init) => {
      if (method === 'POST' && url === '/api/session') {
        signedIn = JSON.parse(init.body).password === 'right';
        return respond(signedIn ? { authenticated: true } : null, signedIn ? 200 : 401);
      }
      if (url === URL_BASE) return signedIn ? respond(fresh()) : respond(null, 401);
      return null;
    });
    renderApp('/admin/christmas');
    // The 401 shows the error under the sign-in card, which is over the page.
    await screen.findByText(/Couldn’t load Christmas films/);
    const card = await screen.findByRole('dialog', { name: 'Signed out' });

    await userEvent.type(within(card).getByLabelText('Password'), 'right');
    await userEvent.click(within(card).getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('tablist', { name: 'Christmas lists' })).toBeInTheDocument();
    expect(screen.queryByRole('dialog', { name: 'Signed out' })).not.toBeInTheDocument();
    expect(screen.queryByText(/Couldn’t load Christmas films/)).not.toBeInTheDocument();
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
    expect(card).toHaveTextContent('Add 2 more and Home shows Christmas films.');
    expect(screen.queryByText(/Home shows 12\. These/)).not.toBeInTheDocument();
  });
});

describe('Christmas films: answers that arrive late or fail', () => {
  it('drops a poll that answers after a change started', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const original = fresh();
    original.keywords = { fetched: 1, total: 1412, running: true };
    const poll = deferred();
    let reads = 0;
    serve({
      get: () => {
        reads += 1;
        return reads === 1 ? original : poll.promise;
      },
      put: () => respond(leftOut(original, 106)),
    });
    await open();
    await act(async () => vi.advanceTimersByTime(30500));
    expect(reads).toBe(2);

    await userEvent.click(screen.getByRole('button', { name: 'Leave out Die Hard' }));
    await waitFor(() => expect(screen.queryByRole('listitem', { name: 'Die Hard' })).toBeNull());
    await act(async () => poll.resolve(respond(original)));
    expect(screen.queryByRole('listitem', { name: 'Die Hard' })).not.toBeInTheDocument();
  });

  it('does not poll while a change is in flight', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const original = fresh();
    original.keywords = { fetched: 1, total: 1412, running: true };
    const gate = deferred();
    const fetchMock = serve({ get: () => original, put: () => gate.promise });
    await open();
    await userEvent.click(screen.getByRole('button', { name: 'Leave out Die Hard' }));
    await act(async () => vi.advanceTimersByTime(65000));
    expect(fetchMock.mock.calls.filter(([url]) => url === URL_BASE)).toHaveLength(1);
    await act(async () => gate.resolve(respond(leftOut(original, 106))));
  });
});

/** Opens the page when there is no tablist to wait for. */
async function open0() {
  renderApp('/admin/christmas');
  await screen.findByRole('heading', { name: 'No Christmas films yet' });
}
