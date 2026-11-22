import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { prepareIndex, useSearchIndex } from '../../state/searchIndex.js';
import { fixtures, installFetch, renderApp, respond } from '../../test/mockApi.jsx';

vi.mock('../../state/searchIndex.js', async (importOriginal) => ({
  ...(await importOriginal()),
  useSearchIndex: vi.fn(),
}));

const INDEX = prepareIndex({
  films: [
    { id: 50, t: 'Batman', y: 1989 },
    { id: 21, t: 'Batman Returns', y: 1992 },
    { id: 51, t: 'Batman Begins', y: 2005 },
    { id: 4, t: 'Trading Places', y: 1983 },
    { id: 60, t: 'Mystery Film', y: 2001 },
  ],
  collections: [],
  people: [],
});

const BASE = '/api/admin/christmas';
const fresh = () => structuredClone(fixtures.christmas);

/** What `/states` says about each film in the index (21 is left out and 4 added in the fixture lists). */
const STATES = {
  50: { filmId: 50, tmdbId: 150, state: 'NOT_IN' },
  51: { filmId: 51, tmdbId: 151, state: 'NOT_IN' },
  21: { filmId: 21, tmdbId: 121, state: 'LEFT_OUT' },
  4: { filmId: 4, tmdbId: 104, state: 'IN_ADDED' },
  60: { filmId: 60, tmdbId: null, state: 'NO_TMDB_ID' },
};
const TITLES = { 50: ['Batman', 1989], 51: ['Batman Begins', 2005] };

/** The screen's body with the owner's INCLUDEs: Trading Places (104, in the fixture) and any of Batman (150, 151). */
function bodyWith(included, base = fresh()) {
  const data = base;
  if (!included.has(104)) {
    data.inWeek = data.inWeek.filter((f) => f.tmdbId !== 104);
    data.added = data.added.filter((f) => f.tmdbId !== 104);
  }
  for (const tmdbId of [150, 151]) {
    if (!included.has(tmdbId)) continue;
    const [title, year] = TITLES[tmdbId - 100];
    const film = { filmId: tmdbId - 100, tmdbId, title, year, posterUrl: null };
    data.inWeek.push({ ...film, reason: 'ADDED' });
    data.added.push(film);
  }
  return data;
}

function deferred() {
  let resolve;
  const promise = new Promise((r) => {
    resolve = r;
  });
  return { promise, resolve };
}

/** A server that keeps the owner's INCLUDEs (`put`, `get` and `states` are each replaceable). */
/** What the server answers for a film, given the owner's INCLUDEs: a film they included is in, else as in STATES. */
function stateNow(id, included) {
  const { tmdbId } = STATES[id];
  return tmdbId && [104, 150, 151].includes(tmdbId)
    ? { ...STATES[id], state: included.has(tmdbId) ? 'IN_ADDED' : 'NOT_IN' }
    : STATES[id];
}

function serve({ get, put, states, session } = {}) {
  const included = new Set([104]);
  return installFetch((method, url, init) => {
    if (session && method === 'POST' && url === '/api/session') return session(init);
    if (url.startsWith(`${BASE}/states`)) {
      const ids = new URL(url, 'http://x').searchParams.get('filmIds').split(',').map(Number);
      return states ? states(ids) : respond(ids.map((id) => stateNow(id, included)));
    }
    if (!url.startsWith(BASE)) return null;
    if (method === 'GET') return respond(get ? get() : bodyWith(included));
    const tmdbId = Number(url.slice(BASE.length + 1));
    const choice = JSON.parse(init.body).choice;
    if (put) return put(tmdbId, choice, included);
    if (choice === 'INCLUDE') included.add(tmdbId);
    else included.delete(tmdbId);
    return respond(bodyWith(included));
  });
}

const puts = (fetchMock) =>
  fetchMock.mock.calls.filter(([url, init]) => url.startsWith(BASE) && init?.method === 'PUT');
const gets = (fetchMock) =>
  fetchMock.mock.calls.filter(([url, init]) => url === BASE && (init?.method ?? 'GET') === 'GET');

beforeEach(() => useSearchIndex.mockReturnValue({ index: INDEX, status: 'ready' }));
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.resetAllMocks();
});

async function open() {
  renderApp('/admin/christmas');
  await screen.findByRole('tablist', { name: 'Christmas lists' });
}

const field = () => screen.getByRole('textbox', { name: 'Add a film' });
const toast = () => screen.getByRole('status', { name: 'Christmas notification' });
const panelList = () => screen.getByRole('list', { name: 'Films in your library' });

/** A button in the results panel (the In list can have a button of the same name). */
const panelButton = (name) => waitFor(() => within(panelList()).getByRole('button', { name }));

async function search(user, text, waitFor = 'Add Batman') {
  await user.click(field());
  await user.keyboard(text);
  return panelButton(waitFor);
}

describe('Christmas films: adding a film', () => {
  it('says in the rule line that you can add any film yourself', async () => {
    serve();
    await open();
    expect(
      screen.getByText(/unless you leave it out\. You can also add any film yourself\./),
    ).toBeInTheDocument();
    expect(field()).toBeInTheDocument();
  });

  it('adds a film in place: the button becomes Remove, the panel stays open and focus stays on it', async () => {
    const user = userEvent.setup();
    const fetchMock = serve();
    await open();
    const add = await search(user, 'batman');
    expect(await panelButton('Put back Batman Returns')).toBeInTheDocument();

    await user.click(add);
    const remove = await panelButton('Remove Batman');
    expect(remove).toBe(add);
    expect(puts(fetchMock).map(([url, init]) => [url, init.body])).toEqual([
      [`${BASE}/150`, JSON.stringify({ choice: 'INCLUDE' })],
    ]);
    expect(within(panelList()).getByText('Added by me')).toBeInTheDocument();
    expect(remove).toHaveFocus();
    expect(toast()).toHaveTextContent('Added Batman.');
    // The tab count follows the new data.
    expect(screen.getByRole('tab', { name: 'Added by me, 2' })).toBeInTheDocument();
  });

  it('removes it again in place, and the toast Undo puts it back with focus on that result', async () => {
    const user = userEvent.setup();
    const fetchMock = serve();
    await open();
    await user.click(await search(user, 'batman'));
    await user.click(await panelButton('Remove Batman'));
    expect(await panelButton('Add Batman')).toHaveFocus();
    expect(toast()).toHaveTextContent('Removed Batman.');
    expect(puts(fetchMock).at(-1)[1].body).toBe(JSON.stringify({ choice: null }));

    await user.click(within(toast()).getByRole('button', { name: 'Undo' }));
    const remove = await panelButton('Remove Batman');
    expect(puts(fetchMock).at(-1)[1].body).toBe(JSON.stringify({ choice: 'INCLUDE' }));
    expect(panelList()).toBeInTheDocument();
    expect(remove).toHaveFocus();
  });

  it('puts focus back on the result, not on the same film in the list behind the panel, after an Undo', async () => {
    const user = userEvent.setup();
    serve();
    await open();
    // Trading Places is in the In list (a row with Remove) and in the results.
    await user.click(await search(user, 'trading', 'Remove Trading Places'));
    await user.click(within(toast()).getByRole('button', { name: 'Undo' }));
    // The row is back in the list behind the panel, with a button of the same name.
    await waitFor(() =>
      expect(screen.getAllByRole('button', { name: 'Remove Trading Places' })).toHaveLength(2),
    );
    expect(await panelButton('Remove Trading Places')).toHaveFocus();
  });

  it('lands focus on the film list row when the panel was closed before the Undo', async () => {
    const user = userEvent.setup();
    const fetchMock = serve();
    await open();
    const remove = await search(user, 'trading', 'Remove Trading Places');
    // Once the states have been asked for and answered, the result keeps its button after the film leaves the lists.
    await waitFor(() =>
      expect(fetchMock.mock.calls.some(([url]) => url.startsWith(`${BASE}/states`))).toBe(true),
    );
    await act(async () => {});
    await user.click(remove);
    await panelButton('Add Trading Places');
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('list', { name: 'Films in your library' })).not.toBeInTheDocument();
    await user.click(within(toast()).getByRole('button', { name: 'Undo' }));
    const row = await screen.findByRole('button', { name: 'Remove Trading Places' });
    expect(row).toHaveFocus();
  });

  it('leaves focus in the field when a write lands while the owner has gone back to typing', async () => {
    const user = userEvent.setup();
    const gate = deferred();
    serve({ put: () => gate.promise });
    await open();
    await user.click(await search(user, 'batman'));
    await user.click(field());
    await act(async () => gate.resolve(respond(bodyWith(new Set([104, 150])))));
    expect(await panelButton('Remove Batman')).not.toHaveFocus();
    expect(field()).toHaveFocus();
  });

  it('does not move focus when a write lands after its result has left the panel', async () => {
    const user = userEvent.setup();
    const gate = deferred();
    serve({ put: () => gate.promise });
    await open();
    await user.click(await search(user, 'batman'));
    // The owner searches for something else while the write is in flight: the result's button goes.
    await user.clear(field());
    await user.keyboard('mystery');
    await panelButton('Find the film… Mystery Film');
    await act(async () => gate.resolve(respond(bodyWith(new Set([104, 150])))));
    expect(field()).toHaveFocus();
  });

  it('refuses a second action on a result while its write is in flight', async () => {
    const user = userEvent.setup();
    const gate = deferred();
    const fetchMock = serve({ put: () => gate.promise });
    await open();
    const add = await search(user, 'batman');
    await user.click(add);
    expect(add).toHaveAttribute('aria-disabled', 'true');
    await user.click(add);
    await user.keyboard('{Enter}');
    expect(puts(fetchMock)).toHaveLength(1);
    await act(async () => gate.resolve(respond(bodyWith(new Set([104, 150])))));
    expect(await panelButton('Remove Batman')).toBeInTheDocument();
  });

  it('shows the 409 text and leaves the result as it was', async () => {
    const user = userEvent.setup();
    serve({
      put: () => respond({ error: 'A scan is running — try again when it finishes' }, 409),
    });
    await open();
    await user.click(await search(user, 'batman'));
    await waitFor(() =>
      expect(toast()).toHaveTextContent('A scan is running — try again when it finishes'),
    );
    expect(await panelButton('Add Batman')).toBeInTheDocument();
  });

  it('ignores a /states answer for an older query', async () => {
    const user = userEvent.setup();
    const slow = deferred();
    let calls = 0;
    serve({
      states: (ids) => {
        calls += 1;
        return calls === 1 ? slow.promise : respond(ids.map((id) => STATES[id]));
      },
    });
    await open();
    await user.click(field());
    await user.keyboard('batman');
    await waitFor(() => expect(calls).toBe(1));
    // Narrowed to one film, answered at once; then the first question's answer lands, saying something else about it.
    await user.clear(field());
    await user.keyboard('begins');
    await panelButton('Add Batman Begins');
    await act(async () =>
      slow.resolve(respond([{ filmId: 51, tmdbId: 151, state: 'KEYWORDS_PENDING' }])),
    );
    expect(screen.queryByText('Keywords not fetched yet')).not.toBeInTheDocument();
    expect(within(panelList()).getByText('Not a Christmas film')).toBeInTheDocument();
  });

  it('is not put back by a /states answer asked before a change and landing after it', async () => {
    const user = userEvent.setup();
    const slow = deferred();
    let calls = 0;
    serve({
      states: (ids) => {
        calls += 1;
        return calls === 2 ? slow.promise : respond(ids.map((id) => STATES[id]));
      },
    });
    await open();
    await user.click(field());
    await user.keyboard('begins');
    await panelButton('Add Batman Begins');
    // A wider search asks again (and is slow), the result already known stays on screen.
    await user.clear(field());
    await user.keyboard('batman');
    await waitFor(() => expect(calls).toBe(2));
    await user.click(await panelButton('Add Batman Begins'));
    await panelButton('Remove Batman Begins');
    await act(async () => slow.resolve(respond([STATES[50], STATES[21], STATES[51]])));
    expect(
      within(panelList()).getByRole('button', { name: 'Remove Batman Begins' }),
    ).toBeInTheDocument();
    expect(
      within(panelList()).queryByRole('button', { name: 'Add Batman Begins' }),
    ).not.toBeInTheDocument();
  });

  it('keeps the toast up while the panel is open, and lets it go once the panel has closed', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    serve();
    await open();
    await user.click(await search(user, 'batman'));
    await panelButton('Remove Batman');
    // Focus stays on the result, out of reach of the toast's Undo: it waits well past its 8 seconds.
    await act(async () => vi.advanceTimersByTime(30000));
    expect(toast()).toHaveTextContent('Added Batman.');

    await user.keyboard('{Escape}');
    await act(async () => vi.advanceTimersByTime(7900));
    expect(toast()).toHaveTextContent('Added Batman.');
    await act(async () => vi.advanceTimersByTime(300));
    expect(toast()).not.toHaveTextContent('Added Batman.');
  });

  it('says Needs a match first and opens Fix match for a film with no TMDB id', async () => {
    const user = userEvent.setup();
    serve();
    await open();
    await user.click(await search(user, 'mystery', 'Find the film… Mystery Film'));
    expect(within(panelList()).getByText('Needs a match first')).toBeInTheDocument();
    const dialog = await screen.findByRole('dialog', { name: 'Fix match' });
    // A film opened without its files has no "File:" line.
    expect(within(dialog).queryByText(/^File:/)).not.toBeInTheDocument();
  });

  it('keeps the panel open while Fix match is used, and gives focus back to the button on Cancel', async () => {
    const user = userEvent.setup();
    serve();
    await open();
    const find = await search(user, 'mystery', 'Find the film… Mystery Film');
    await user.click(find);
    const dialog = await screen.findByRole('dialog', { name: 'Fix match' });
    // A press inside the dialog (it is not in the panel) is not a press outside the panel.
    await user.click(within(dialog).getByRole('heading', { name: 'Fix match' }));
    expect(panelList()).toBeInTheDocument();
    // Nor is a press on its backdrop, which is outside the dialog itself.
    await user.click(document.body);
    expect(panelList()).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Fix match' })).toBeNull());
    expect(panelList()).toBeInTheDocument();
    expect(await panelButton('Find the film… Mystery Film')).toHaveFocus();
  });

  it('asks /states again, and reads the lists again, after a match is saved in Fix match', async () => {
    const user = userEvent.setup();
    let matched = false;
    const fetchMock = serve({
      states: (ids) =>
        respond(
          ids.map((id) =>
            id === 60 && matched
              ? { filmId: 60, tmdbId: 160, state: 'KEYWORDS_PENDING' }
              : STATES[id],
          ),
        ),
    });
    await open();
    await user.click(await search(user, 'mystery', 'Find the film… Mystery Film'));
    const dialog = await screen.findByRole('dialog', { name: 'Fix match' });
    matched = true;
    await user.click((await within(dialog).findAllByRole('radio'))[0]);
    await user.click(within(dialog).getByRole('button', { name: 'Use this match' }));
    // The film now has a TMDB id and its keywords are not fetched: Add, with its chip.
    expect(await panelButton('Add Mystery Film')).toBeInTheDocument();
    expect(within(panelList()).getByText('Keywords not fetched yet')).toBeInTheDocument();
    await waitFor(() => expect(gets(fetchMock).length).toBeGreaterThanOrEqual(2));
  });

  it('keeps the search open when the first film is added from the empty screen', async () => {
    const user = userEvent.setup();
    const empty = (included = new Set()) => {
      const data = bodyWith(included);
      data.inWeek = data.inWeek.filter((f) => f.reason === 'ADDED' && included.has(f.tmdbId));
      data.added = [...data.inWeek];
      data.leftOut = [];
      data.keywords = { fetched: 1412, total: 1412, running: false };
      return data;
    };
    serve({ get: () => empty(), put: () => respond(empty(new Set([150]))) });
    renderApp('/admin/christmas');
    expect(await screen.findByText('No Christmas films yet')).toBeInTheDocument();
    expect(
      screen.getByText('No films in your library are tagged Christmas. Add any you count.'),
    ).toBeInTheDocument();
    await user.click(await search(user, 'batman'));
    expect(await screen.findByRole('tablist', { name: 'Christmas lists' })).toBeInTheDocument();
    expect(panelList()).toBeInTheDocument();
    expect(await panelButton('Remove Batman')).toHaveFocus();
  });

  it('offers the search while keywords are still being fetched, with the add-yourself line', async () => {
    serve({
      get: () => {
        const data = fresh();
        data.inWeek = [];
        data.added = [];
        data.leftOut = [];
        return data;
      },
    });
    renderApp('/admin/christmas');
    expect(
      await screen.findByText("You don't have to wait: add any film yourself."),
    ).toBeInTheDocument();
    expect(field()).toBeInTheDocument();
  });

  it('turns a removed film into Add in place, with focus kept, when the write lands before /states answers', async () => {
    const user = userEvent.setup();
    const slow = deferred();
    let calls = 0;
    serve({
      states: () => {
        calls += 1;
        return slow.promise;
      },
    });
    await open();
    await user.click(field());
    await user.keyboard('trading');
    // /states is asked, and held, before the write: the held answer is then older than the write.
    await waitFor(() => expect(calls).toBe(1));
    const remove = await panelButton('Remove Trading Places');
    await user.click(remove);
    const add = await panelButton('Add Trading Places');
    expect(add).toBe(remove);
    expect(add).toHaveFocus();

    await act(async () => slow.resolve(respond([STATES[4]])));
    expect(await panelButton('Add Trading Places')).toHaveFocus();
  });

  it('keeps the pressed button in place as Add, with focus, when a keyboard Remove finishes before the first /states answer', async () => {
    const user = userEvent.setup();
    const slow = deferred();
    let calls = 0;
    serve({
      states: () => {
        calls += 1;
        return slow.promise;
      },
    });
    await open();
    await user.click(field());
    await user.keyboard('trading{ArrowDown}');
    const remove = await panelButton('Remove Trading Places');
    expect(remove).toHaveFocus();
    // /states is asked, and held, before the write: the held answer is then older than the write.
    await waitFor(() => expect(calls).toBe(1));
    await user.keyboard('{Enter}');
    const add = await panelButton('Add Trading Places');
    expect(add).toBe(remove);
    expect(add).toHaveFocus();

    await act(async () => slow.resolve(respond([STATES[4]])));
    expect(await panelButton('Add Trading Places')).toBe(remove);
    expect(remove).toHaveFocus();
  });

  it('does the same when the press comes in the tick the result first renders and /states is only asked later', async () => {
    const user = userEvent.setup();
    let calls = 0;
    serve({
      states: (ids) => {
        calls += 1;
        return respond(ids.map((id) => ({ ...STATES[id], state: 'NOT_IN' })));
      },
    });
    await open();
    await user.click(field());
    await user.keyboard('trading{ArrowDown}{Enter}');
    const add = await panelButton('Add Trading Places');
    expect(add).toHaveFocus();
    await waitFor(() => expect(calls).toBeGreaterThan(0));
    expect(await panelButton('Add Trading Places')).toBe(add);
    expect(add).toHaveFocus();
  });

  it('shows Add after an early removal when the search text changes and comes back before an answer', async () => {
    const user = userEvent.setup();
    const slow = deferred();
    let calls = 0;
    serve({
      states: (ids) => {
        calls += 1;
        return calls === 1
          ? slow.promise
          : respond(ids.map((id) => ({ ...STATES[id], state: 'NOT_IN' })));
      },
    });
    await open();
    await user.click(field());
    await user.keyboard('trading');
    await waitFor(() => expect(calls).toBe(1));
    await user.click(await panelButton('Remove Trading Places'));
    await panelButton('Add Trading Places');

    await user.clear(field());
    await user.keyboard('begins');
    await panelButton('Add Batman Begins');
    await act(async () => slow.resolve(respond([STATES[4]])));
    await user.clear(field());
    await user.keyboard('trading');
    expect(await panelButton('Add Trading Places')).toBeInTheDocument();
  });
});

describe('Christmas films: adding a film on a phone', () => {
  let media;
  beforeEach(() => {
    // `media.change(false)` is the window being widened.
    const listeners = new Set();
    const list = {
      matches: true,
      addEventListener: (type, fn) => listeners.add(fn),
      removeEventListener: (type, fn) => listeners.delete(fn),
    };
    vi.stubGlobal(
      'matchMedia',
      vi.fn(() => list),
    );
    media = {
      change(next) {
        list.matches = next;
        act(() => listeners.forEach((fn) => fn()));
      },
    };
  });

  it('opens a full-screen sheet, acts with one tap, keeps the toast and its Undo inside, and closes on Cancel', async () => {
    const user = userEvent.setup();
    serve();
    renderApp('/admin/christmas');
    await screen.findByRole('tablist', { name: 'Christmas lists' });
    await user.click(screen.getByRole('button', { name: 'Add a film' }));
    const sheet = screen.getByRole('dialog', { name: 'Add a film' });
    await user.keyboard('batman');
    await user.click(await within(sheet).findByRole('button', { name: 'Add Batman' }));
    const remove = await within(sheet).findByRole('button', { name: 'Remove Batman' });
    expect(remove).toHaveFocus();

    // One toast, inside the dialog (its Tab trap and a screen reader reach it), and its Undo works.
    expect(screen.getAllByRole('status', { name: 'Christmas notification' })).toHaveLength(1);
    expect(within(sheet).getByRole('status', { name: 'Christmas notification' })).toHaveTextContent(
      'Added Batman.',
    );
    await user.click(within(sheet).getByRole('button', { name: 'Undo' }));
    expect(await within(sheet).findByRole('button', { name: 'Add Batman' })).toHaveFocus();
    expect(toast()).toHaveTextContent('Removed Batman.');

    await user.click(within(sheet).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog', { name: 'Add a film' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Add a film' })).toHaveFocus();
    // The toast is back in the page.
    expect(screen.getAllByRole('status', { name: 'Christmas notification' })).toHaveLength(1);
  });

  it('has exactly one toast region through every change of where the sheet is', async () => {
    const user = userEvent.setup();
    serve();
    renderApp('/admin/christmas');
    await screen.findByRole('tablist', { name: 'Christmas lists' });
    const regions = () => screen.getAllByRole('status', { name: 'Christmas notification' });
    expect(regions()).toHaveLength(1);

    await user.click(screen.getByRole('button', { name: 'Add a film' }));
    const sheet = screen.getByRole('dialog', { name: 'Add a film' });
    expect(regions()).toHaveLength(1);
    expect(sheet).toContainElement(regions()[0]);

    await user.keyboard('batman');
    await user.click(await within(sheet).findByRole('button', { name: 'Add Batman' }));
    await within(sheet).findByRole('button', { name: 'Remove Batman' });
    expect(within(sheet).getByRole('button', { name: 'Undo' })).toBeInTheDocument();

    // Widened while the sheet is up: the sheet and the toast's place change in the same render.
    media.change(false);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(regions()).toHaveLength(1);
    expect(screen.getByRole('textbox', { name: 'Add a film' })).toHaveFocus();
    // And back to a phone: the sheet stays shut and the toast stays in the page.
    media.change(true);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(regions()).toHaveLength(1);
    expect(toast()).toHaveTextContent('Added Batman.');
    // The Undo that moved with it still works.
    await user.click(within(toast()).getByRole('button', { name: 'Undo' }));
    expect(toast()).toHaveTextContent('Removed Batman.');
  });

  describe('Undo of a search change, pressed inside the Home preview sheet', () => {
    /** Adds Batman in the search sheet, closes it, opens the preview sheet and Tabs to the toast's Undo. */
    async function toUndoInPreview(user) {
      renderApp('/admin/christmas');
      await screen.findByRole('tablist', { name: 'Christmas lists' });
      await user.click(screen.getByRole('button', { name: 'Add a film' }));
      const search = screen.getByRole('dialog', { name: 'Add a film' });
      await user.keyboard('batman');
      await user.click(await within(search).findByRole('button', { name: 'Add Batman' }));
      await within(search).findByRole('button', { name: 'Remove Batman' });
      await user.click(within(search).getByRole('button', { name: 'Cancel' }));
      await user.click(screen.getByRole('button', { name: /Preview Home in Christmas week/ }));
      const sheet = screen.getByRole('dialog', { name: 'Home in Christmas week' });
      for (let i = 0; i < 30; i += 1) {
        if (document.activeElement === within(sheet).getByRole('button', { name: 'Undo' })) break;
        await user.tab();
      }
      expect(within(sheet).getByRole('button', { name: 'Undo' })).toHaveFocus();
      return sheet;
    }

    it('keeps focus in the sheet when the Undo works, and Esc closes it', async () => {
      const user = userEvent.setup();
      serve();
      const sheet = await toUndoInPreview(user);
      await user.keyboard('{Enter}');
      await within(sheet).findByText('Removed Batman.');
      expect(sheet).toContainElement(document.activeElement);
      await user.keyboard('{Escape}');
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });

    it('keeps focus in the sheet when the Undo fails, and Esc closes it', async () => {
      const user = userEvent.setup();
      serve({
        put: (tmdbId, choice, included) => {
          if (choice === null) return respond({}, 500);
          included.add(tmdbId);
          return respond(bodyWith(included));
        },
      });
      const sheet = await toUndoInPreview(user);
      await user.keyboard('{Enter}');
      await within(sheet).findByText(/Couldn.t save that change/);
      expect(sheet).toContainElement(document.activeElement);
      await user.keyboard('{Escape}');
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });
  });

  it('lets the toast time out after a widening that moved it while its Undo had focus', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    serve();
    renderApp('/admin/christmas');
    await screen.findByRole('tablist', { name: 'Christmas lists' });
    await user.click(screen.getByRole('button', { name: 'Add a film' }));
    const sheet = screen.getByRole('dialog', { name: 'Add a film' });
    await user.keyboard('batman');
    await user.click(await within(sheet).findByRole('button', { name: 'Add Batman' }));
    await within(sheet).findByRole('button', { name: 'Remove Batman' });
    for (let i = 0; i < 10; i += 1) {
      if (document.activeElement?.textContent === 'Undo') break;
      await user.tab();
    }
    expect(within(sheet).getByRole('button', { name: 'Undo' })).toHaveFocus();

    media.change(false);
    expect(toast()).toHaveTextContent('Added Batman.');
    // The desktop panel is open on the same query; closing it releases the pin.
    await user.keyboard('{Escape}');
    await act(async () => vi.advanceTimersByTime(9000));
    expect(toast()).not.toHaveTextContent('Added Batman.');
  });

  it('keeps focus inside the sheet after a sign-in that followed a 401', async () => {
    const user = userEvent.setup();
    let signedIn = false;
    serve({
      states: (ids) => (signedIn ? respond(ids.map((id) => STATES[id])) : respond(null, 401)),
      session: (init) => {
        signedIn = JSON.parse(init.body).password === 'right';
        return respond(signedIn ? { authenticated: true } : null, signedIn ? 200 : 401);
      },
    });
    renderApp('/admin/christmas');
    await screen.findByRole('tablist', { name: 'Christmas lists' });
    await user.click(screen.getByRole('button', { name: 'Add a film' }));
    await user.keyboard('batman');
    const card = await screen.findByRole('dialog', { name: 'Signed out' });
    await user.type(within(card).getByLabelText('Password'), 'right');
    await user.click(within(card).getByRole('button', { name: 'Sign in' }));
    await waitFor(() =>
      expect(screen.queryByRole('dialog', { name: 'Signed out' })).not.toBeInTheDocument(),
    );
    const sheet = screen.getByRole('dialog', { name: 'Add a film' });
    expect(sheet).toContainElement(document.activeElement);

    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog', { name: 'Add a film' })).not.toBeInTheDocument();
  });

  it('keeps focus in the sheet when Undo is pressed for a film the results no longer show', async () => {
    const user = userEvent.setup();
    serve();
    renderApp('/admin/christmas');
    await screen.findByRole('tablist', { name: 'Christmas lists' });
    await user.click(screen.getByRole('button', { name: 'Add a film' }));
    const sheet = screen.getByRole('dialog', { name: 'Add a film' });
    await user.keyboard('batman');
    await user.click(await within(sheet).findByRole('button', { name: 'Add Batman' }));
    await within(sheet).findByRole('button', { name: 'Remove Batman' });

    await user.clear(within(sheet).getByRole('textbox', { name: 'Add a film' }));
    await user.keyboard('trading');
    await within(sheet).findByRole('button', { name: /Trading Places/ });
    await user.click(within(sheet).getByRole('button', { name: 'Undo' }));
    await waitFor(() => expect(toast()).toHaveTextContent('Removed Batman.'));
    expect(sheet).toContainElement(document.activeElement);
    expect(document.activeElement).not.toBe(document.body);

    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog', { name: 'Add a film' })).not.toBeInTheDocument();
  });
});
