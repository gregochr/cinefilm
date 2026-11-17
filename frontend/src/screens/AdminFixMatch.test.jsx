import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { FADE_MS } from '../components/useDialog.js';
import { SCAN_IDLE_POLL_MS } from '../state/useAdmin.js';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

const isSearch = (m, url) => m === 'GET' && /^\/api\/admin\/films\/31\/match\?/.test(url);
const isApply = (m, url) => m === 'POST' && url === '/api/admin/films/31/match';

async function openPanel() {
  renderApp('/admin');
  const attention = await screen.findByRole('region', { name: 'Pick the right film' });
  // The first unmatched film is The Wicker Man (id 31); the fixtures list a second one after the other kinds.
  const openers = await within(attention).findAllByRole('button', { name: 'Find the film…' });
  await userEvent.click(openers[0]);
  return screen.findByRole('dialog', { name: 'Fix match' });
}

describe('Admin: Fix match', () => {
  it('only unmatched films get a Fix match button', async () => {
    installFetch();
    renderApp('/admin');
    const attention = await screen.findByRole('region', { name: 'Pick the right film' });
    const buttons = await within(attention).findAllByRole('button', { name: 'Find the film…' });
    expect(buttons).toHaveLength(2);
    expect(buttons[0]).toHaveAccessibleDescription(
      expect.stringContaining('Search TMDB and choose which film this file is.'),
    );
  });

  it('opens with the title in the search box, the file name, and the TMDB results to pick from', async () => {
    const fetchMock = installFetch();
    const panel = await openPanel();

    expect(within(panel).getByRole('searchbox', { name: 'Search TMDB' })).toHaveValue(
      'The Wicker Man',
    );
    expect(within(panel).getByText('File: The Wicker Man.m4v')).toBeInTheDocument();
    const radios = await within(panel).findAllByRole('radio');
    expect(radios).toHaveLength(2);
    expect(within(panel).getByText('The Wicker Man (1973)')).toBeInTheDocument();
    expect(
      within(panel).getByText('Robin Hardy · Edward Woodward, Christopher Lee'),
    ).toBeInTheDocument();
    expect(within(panel).getByText('1h 28m')).toBeInTheDocument();
    expect(within(panel).getByText('The Wicker Man (2006)')).toBeInTheDocument();
    expect(within(panel).getByRole('button', { name: 'Use this match' })).toBeDisabled();
    const search = fetchMock.mock.calls.map((c) => c[0]).find((u) => u.includes('/match?'));
    expect(search).toBe('/api/admin/films/31/match?query=The+Wicker+Man');
  });

  it('searches again with the typed title and a four-digit year, ignoring a bad year', async () => {
    const fetchMock = installFetch();
    const panel = await openPanel();
    await within(panel).findAllByRole('radio');

    const box = within(panel).getByRole('searchbox', { name: 'Search TMDB' });
    await userEvent.clear(box);
    await userEvent.type(box, 'Wicker Tree');
    await userEvent.type(within(panel).getByRole('textbox', { name: /Year/ }), '2011');
    await userEvent.click(within(panel).getByRole('button', { name: 'Search' }));
    await waitFor(() =>
      expect(fetchMock.mock.calls.map((c) => c[0])).toContain(
        '/api/admin/films/31/match?query=Wicker+Tree&year=2011',
      ),
    );

    await userEvent.clear(within(panel).getByRole('textbox', { name: /Year/ }));
    await userEvent.type(within(panel).getByRole('textbox', { name: /Year/ }), 'abc');
    await userEvent.click(within(panel).getByRole('button', { name: 'Search' }));
    await waitFor(() =>
      expect(fetchMock.mock.calls.map((c) => c[0])).toContain(
        '/api/admin/films/31/match?query=Wicker+Tree',
      ),
    );
  });

  it('applies the picked result, closes, and refreshes the attention list', async () => {
    let attentionCalls = 0;
    const fetchMock = installFetch((m, url) => {
      if (url === '/api/admin/attention') attentionCalls += 1;
      return null;
    });
    const panel = await openPanel();
    await userEvent.click((await within(panel).findAllByRole('radio'))[0]);
    const before = attentionCalls;

    await userEvent.click(within(panel).getByRole('button', { name: 'Use this match' }));

    await waitFor(() =>
      expect(screen.queryByRole('dialog', { name: 'Fix match' })).not.toBeInTheDocument(),
    );
    const post = fetchMock.mock.calls.find((c) => isApply(c[1]?.method, c[0]));
    expect(JSON.parse(post[1].body)).toEqual({ tmdbId: 16307 });
    await waitFor(() => expect(attentionCalls).toBeGreaterThan(before));
  });

  it('Cancel closes the panel without applying anything', async () => {
    const fetchMock = installFetch();
    const panel = await openPanel();

    await userEvent.click(within(panel).getByRole('button', { name: 'Cancel' }));

    expect(screen.queryByRole('dialog', { name: 'Fix match' })).not.toBeInTheDocument();
    expect(
      fetchMock.mock.calls.some((c) => c[1]?.method === 'POST' && c[0].includes('/match')),
    ).toBe(false);
  });

  it('says so when TMDB has nothing', async () => {
    installFetch((m, url) => (isSearch(m, url) ? respond([]) : null));
    const panel = await openPanel();
    expect(await within(panel).findByText(/TMDB has nothing for that/)).toBeInTheDocument();
    expect(within(panel).queryByRole('radio')).not.toBeInTheDocument();
  });

  it.each([
    [503, 'TMDB isn’t available right now. Try again in a moment.'],
    [409, 'A scan is running. Try again when it finishes.'],
    [400, 'Type a title to search for.'],
    [500, 'Something went wrong. Try again.'],
  ])('shows the %s problem from a search in the panel', async (status, text) => {
    installFetch((m, url) =>
      isSearch(m, url) ? respond(status === 409 ? null : { error: 'x' }, status) : null,
    );
    const panel = await openPanel();
    expect(await within(panel).findByRole('alert')).toHaveTextContent(text);
  });

  it('shows the problem from an apply and keeps the panel open', async () => {
    installFetch((m, url) => (isApply(m, url) ? respond(null, 409) : null));
    const panel = await openPanel();
    await userEvent.click((await within(panel).findAllByRole('radio'))[1]);
    await userEvent.click(within(panel).getByRole('button', { name: 'Use this match' }));

    expect(await within(panel).findByRole('alert')).toHaveTextContent(
      'A scan is running. Try again when it finishes.',
    );
    expect(screen.getByRole('dialog', { name: 'Fix match' })).toBeInTheDocument();
  });

  it('shows a skeleton while searching', async () => {
    installFetch((m, url) => (isSearch(m, url) ? new Promise(() => {}) : null));
    const panel = await openPanel();
    expect(within(panel).getByRole('status', { name: 'Searching TMDB' })).toBeInTheDocument();
    expect(within(panel).getByRole('button', { name: 'Search' })).toBeDisabled();
  });

  it('lists a result with no poster, people or runtime without breaking', async () => {
    installFetch((m, url) =>
      isSearch(m, url)
        ? respond([
            {
              tmdbId: 5,
              title: 'Bare',
              year: null,
              runtimeMinutes: null,
              posterPath: null,
              director: null,
              cast: [],
            },
          ])
        : null,
    );
    const panel = await openPanel();
    expect(await within(panel).findByText('Bare')).toBeInTheDocument();
  });
});

describe('Admin: the Fix match dialog', () => {
  it('opens with focus in the search box and a close button', async () => {
    installFetch();
    const dialog = await openPanel();
    expect(within(dialog).getByRole('searchbox', { name: 'Search TMDB' })).toHaveFocus();
    expect(dialog).toHaveAccessibleDescription('File: The Wicker Man.m4v');
    expect(within(dialog).getByRole('button', { name: 'Close' })).toBeInTheDocument();
  });

  it('Esc closes it and focus goes back to "Find the film…"', async () => {
    installFetch();
    renderApp('/admin');
    const opener = (await screen.findAllByRole('button', { name: 'Find the film…' }))[0];
    await userEvent.click(opener);
    await screen.findByRole('dialog', { name: 'Fix match' });

    await userEvent.keyboard('{Escape}');

    expect(screen.queryByRole('dialog', { name: 'Fix match' })).not.toBeInTheDocument();
    expect(opener).toHaveFocus();
  });

  it('the close button closes it without applying anything', async () => {
    const fetchMock = installFetch();
    const dialog = await openPanel();

    await userEvent.click(within(dialog).getByRole('button', { name: 'Close' }));

    expect(screen.queryByRole('dialog', { name: 'Fix match' })).not.toBeInTheDocument();
    expect(fetchMock.mock.calls.some((c) => c[1]?.method === 'POST')).toBe(false);
  });

  it('keeps Tab inside the dialog, wrapping at both ends', async () => {
    installFetch();
    const dialog = await openPanel();
    await within(dialog).findAllByRole('radio');
    const close = within(dialog).getByRole('button', { name: 'Close' });
    const cancel = within(dialog).getByRole('button', { name: 'Cancel' });

    await userEvent.tab({ shift: true }); // search box -> close button
    expect(close).toHaveFocus();
    await userEvent.tab({ shift: true }); // first element wraps to the last enabled one
    expect(cancel).toHaveFocus();
    await userEvent.tab();
    expect(close).toHaveFocus();
  });

  it('applies once on a double click and closes with the lists refreshed', async () => {
    let applies = 0;
    installFetch((m, url) => {
      if (isApply(m, url)) {
        applies += 1;
        return respond({ filmId: 31, joined: false });
      }
      return null;
    });
    const dialog = await openPanel();
    await userEvent.click((await within(dialog).findAllByRole('radio'))[0]);

    await userEvent.dblClick(within(dialog).getByRole('button', { name: 'Use this match' }));

    await waitFor(() =>
      expect(screen.queryByRole('dialog', { name: 'Fix match' })).not.toBeInTheDocument(),
    );
    expect(applies).toBe(1);
  });

  it('during the fade after an apply it is still on screen, sends nothing more and reads the lists once', async () => {
    vi.stubGlobal('matchMedia', () => ({ matches: false }));
    let attentionCalls = 0;
    let applies = 0;
    const fetchMock = installFetch((m, url) => {
      if (url === '/api/admin/attention') attentionCalls += 1;
      if (isApply(m, url)) {
        applies += 1;
        return respond({ filmId: 31, joined: false });
      }
      return null;
    });
    const dialog = await openPanel();
    await userEvent.click((await within(dialog).findAllByRole('radio'))[0]);
    const before = attentionCalls;

    await userEvent.click(within(dialog).getByRole('button', { name: 'Use this match' }));
    // The apply has answered, the dialog is still fading: a second click and Esc change nothing.
    await waitFor(() => expect(applies).toBe(1));
    expect(screen.getByRole('dialog', { name: 'Fix match' })).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Use this match' })).toBeDisabled();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Use this match' }));
    await userEvent.click(within(dialog).getByRole('button', { name: 'Search' }));
    await userEvent.keyboard('{Escape}');

    await waitFor(() =>
      expect(screen.queryByRole('dialog', { name: 'Fix match' })).not.toBeInTheDocument(),
    );
    await waitFor(() => expect(attentionCalls).toBe(before + 1));
    await act(async () => {}); // let anything still pending land before the exact count
    expect(attentionCalls).toBe(before + 1);
    expect(applies).toBe(1);
    expect(fetchMock.mock.calls.filter((c) => c[0].includes('/match?'))).toHaveLength(1);
  });

  it('Esc with motion on fades out and then focus is back on "Find the film…"', async () => {
    vi.stubGlobal('matchMedia', () => ({ matches: false }));
    installFetch();
    renderApp('/admin');
    const opener = (await screen.findAllByRole('button', { name: 'Find the film…' }))[0];
    await userEvent.click(opener);
    await screen.findByRole('dialog', { name: 'Fix match' });

    await userEvent.keyboard('{Escape}');
    expect(screen.getByRole('dialog', { name: 'Fix match' })).toBeInTheDocument();

    await waitFor(() =>
      expect(screen.queryByRole('dialog', { name: 'Fix match' })).not.toBeInTheDocument(),
    );
    expect(opener).toHaveFocus();
  });

  it('Cancel while an apply is running closes it, and the apply still refreshes the lists', async () => {
    let release;
    const slow = new Promise((resolve) => {
      release = resolve;
    });
    let attentionCalls = 0;
    installFetch((m, url) => {
      if (url === '/api/admin/attention') attentionCalls += 1;
      return isApply(m, url) ? slow : null;
    });
    const dialog = await openPanel();
    await userEvent.click((await within(dialog).findAllByRole('radio'))[0]);
    await userEvent.click(within(dialog).getByRole('button', { name: 'Use this match' }));
    expect(within(dialog).getByRole('button', { name: 'Applying…' })).toBeDisabled();
    const before = attentionCalls;

    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog', { name: 'Fix match' })).not.toBeInTheDocument();
    await act(async () => release(respond({ filmId: 31, joined: false })));

    await waitFor(() => expect(attentionCalls).toBeGreaterThan(before));
    expect(screen.queryByRole('dialog', { name: 'Fix match' })).not.toBeInTheDocument();
  });

  it('an apply that lands during the fade-out is still reported, once', async () => {
    vi.stubGlobal('matchMedia', () => ({ matches: false }));
    let release;
    const slow = new Promise((resolve) => {
      release = resolve;
    });
    let attentionCalls = 0;
    installFetch((m, url) => {
      if (url === '/api/admin/attention') attentionCalls += 1;
      return isApply(m, url) ? slow : null;
    });
    const dialog = await openPanel();
    await userEvent.click((await within(dialog).findAllByRole('radio'))[0]);
    await userEvent.click(within(dialog).getByRole('button', { name: 'Use this match' }));
    const before = attentionCalls;

    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    await act(async () => release(respond({ filmId: 31, joined: false })));

    await waitFor(() =>
      expect(screen.queryByRole('dialog', { name: 'Fix match' })).not.toBeInTheDocument(),
    );
    await waitFor(() => expect(attentionCalls).toBe(before + 1));
    await act(async () => {}); // let anything still pending land before the exact count
    expect(attentionCalls).toBe(before + 1);
  });

  it('an apply that lands after Cancel and the row going leaves focus on the section heading', async () => {
    let release;
    const slow = new Promise((resolve) => {
      release = resolve;
    });
    let applied = false;
    installFetch((m, url) => {
      if (url === '/api/admin/attention' && applied) {
        return respond({ items: fixtures.adminAttention.items.slice(1) });
      }
      return isApply(m, url) ? slow : null;
    });
    const dialog = await openPanel();
    await userEvent.click((await within(dialog).findAllByRole('radio'))[0]);
    await userEvent.click(within(dialog).getByRole('button', { name: 'Use this match' }));
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    applied = true;

    await act(async () => release(respond({ filmId: 31, joined: false })));

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1, name: 'To do' })).toHaveFocus(),
    );
  });
});

describe('Admin: focus after an apply with motion on', () => {
  it('stays in the dialog while it fades and ends on the heading once it is gone', async () => {
    vi.stubGlobal('matchMedia', () => ({ matches: false }));
    let applied = false;
    installFetch((m, url) => {
      if (url === '/api/admin/attention' && applied) {
        return respond({ items: fixtures.adminAttention.items.slice(1) });
      }
      if (isApply(m, url)) {
        applied = true;
        return respond({ filmId: 31, joined: false });
      }
      return null;
    });
    const dialog = await openPanel();
    await userEvent.click((await within(dialog).findAllByRole('radio'))[0]);

    await userEvent.click(within(dialog).getByRole('button', { name: 'Use this match' }));
    await userEvent.tab();
    // The row is gone and the dialog still fades: focus is inside it, not on the heading behind it.
    expect(dialog).toContainElement(document.activeElement);
    expect(screen.getByRole('heading', { level: 1, name: 'To do' })).not.toHaveFocus();

    await waitFor(() =>
      expect(screen.queryByRole('dialog', { name: 'Fix match' })).not.toBeInTheDocument(),
    );
    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1, name: 'To do' })).toHaveFocus(),
    );
  });
});

/**
 * Holds the clock (setTimeout only) so a 150 ms fade ends only when the test advances it. Testing Library finds a held
 * clock through the `jest` global, so that is pointed at Vitest's. Call it after the dialog is open: opening waits on
 * real timers.
 */
function holdClock() {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
  vi.stubGlobal('jest', { advanceTimersByTime: (ms) => vi.advanceTimersByTime(ms) });
  return userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
}
const flush = () => act(async () => vi.advanceTimersByTimeAsync(0));
const heading = () => screen.getByRole('heading', { level: 1, name: 'To do' });
const rows = () => screen.getAllByRole('button', { name: 'Find the film…' });
const railLink = (name) =>
  within(screen.getByRole('navigation', { name: 'Admin sections' })).getByRole('link', { name });

describe('Admin: the heading does not take focus behind the fading dialog', () => {
  it('keeps focus off the heading until the dialog is gone, without any Tab', async () => {
    vi.stubGlobal('matchMedia', () => ({ matches: false }));
    let applied = false;
    let landReread;
    const reread = new Promise((resolve) => {
      landReread = resolve;
    });
    installFetch((m, url) => {
      if (url === '/api/admin/attention' && applied) return reread;
      if (isApply(m, url)) {
        applied = true;
        return respond({ filmId: 31, joined: false });
      }
      return null;
    });
    const dialog = await openPanel();
    await userEvent.click((await within(dialog).findAllByRole('radio'))[0]);
    const user = holdClock();

    await user.click(within(dialog).getByRole('button', { name: 'Use this match' }));
    // The apply has answered and the dialog is fading out, its button disabled; the re-read is still on its way.
    await flush();
    expect(within(dialog).getByRole('button', { name: 'Use this match' })).toBeDisabled();
    expect(rows()).toHaveLength(2);

    // The re-read lands: the row is gone while the dialog is still on screen, and focus is on the page body.
    landReread(respond({ items: fixtures.adminAttention.items.slice(1) }));
    await vi.waitFor(() => expect(rows()).toHaveLength(1));
    expect(screen.getByRole('dialog', { name: 'Fix match' })).toBeInTheDocument();
    expect(heading()).not.toHaveFocus();

    await act(async () => vi.advanceTimersByTimeAsync(FADE_MS));

    expect(screen.queryByRole('dialog', { name: 'Fix match' })).not.toBeInTheDocument();
    expect(heading()).toHaveFocus();
  });

  it('forgets a due fallback when the dialog goes with the section, so a later Cancel arms nothing', async () => {
    vi.stubGlobal('matchMedia', () => ({ matches: false }));
    let scanFinished = false;
    installFetch((m, url) => {
      if (isApply(m, url)) return respond({ filmId: 31, joined: false });
      if (url === '/api/admin/attention' && scanFinished) {
        return respond({ items: fixtures.adminAttention.items.slice(1) });
      }
      if (url === '/api/admin/scan' && scanFinished) {
        return respond({ ...fixtures.adminScan, lastScan: { finishedAt: '2026-11-17T09:00:00Z' } });
      }
      return null;
    });
    const dialog = await openPanel();
    await userEvent.click((await within(dialog).findAllByRole('radio'))[0]);
    const user = holdClock();

    // Apply, then leave for another section while the dialog fades: it unmounts without closing itself.
    await user.click(within(dialog).getByRole('button', { name: 'Use this match' }));
    await user.click(railLink('Library'));
    await vi.waitFor(() =>
      expect(screen.getByRole('heading', { level: 1, name: 'Library' })).toBeInTheDocument(),
    );
    await user.click(railLink(/^To do/));
    await vi.waitFor(() => expect(rows()).toHaveLength(2));

    // A plain Cancel on a new dialog, then focus rests on the page body.
    await user.click(rows()[0]);
    const again = screen.getByRole('dialog', { name: 'Fix match' });
    await user.click(within(again).getByRole('button', { name: 'Cancel' }));
    await act(async () => vi.advanceTimersByTimeAsync(FADE_MS));
    expect(screen.queryByRole('dialog', { name: 'Fix match' })).not.toBeInTheDocument();
    act(() => document.activeElement.blur());

    // A scan finishing later changes the To do list: the row goes, and nothing sends focus to the heading.
    scanFinished = true;
    await act(async () => vi.advanceTimersByTimeAsync(SCAN_IDLE_POLL_MS));
    await vi.waitFor(() => expect(rows()).toHaveLength(1));
    expect(heading()).not.toHaveFocus();
  });
});

describe('Admin: leaving during the fade after an apply', () => {
  it('still reads the lists again, because the match was saved', async () => {
    vi.stubGlobal('matchMedia', () => ({ matches: false }));
    let summaryReads = 0;
    installFetch((m, url) => {
      if (url === '/api/admin/summary') summaryReads += 1;
      return isApply(m, url) ? respond({ filmId: 31, joined: false }) : null;
    });
    const dialog = await openPanel();
    await userEvent.click((await within(dialog).findAllByRole('radio'))[0]);
    const before = summaryReads;
    const user = holdClock();

    await user.click(within(dialog).getByRole('button', { name: 'Use this match' }));
    // The clock is held, so the dialog is certainly still fading when the section changes.
    expect(screen.getByRole('dialog', { name: 'Fix match' })).toBeInTheDocument();
    await user.click(railLink('Library'));

    await vi.waitFor(() =>
      expect(screen.getByRole('heading', { level: 1, name: 'Library' })).toBeInTheDocument(),
    );
    expect(screen.queryByRole('dialog', { name: 'Fix match' })).not.toBeInTheDocument();
    // The summary behind the rail counts is read again by the shell, not by the To do list.
    await vi.waitFor(() => expect(summaryReads).toBeGreaterThan(before));
  });
});

describe('Admin: a slow apply for a film that is no longer on screen', () => {
  it('refreshes the lists but leaves the newer panel open with its search and selection', async () => {
    const second = {
      ...fixtures.adminAttention.items[0],
      filmId: 32,
      title: 'Local Hero',
      files: ['Local Hero.m4v'],
    };
    const attention = { items: [fixtures.adminAttention.items[0], second] };
    let release;
    const slow = new Promise((resolve) => {
      release = resolve;
    });
    let attentionCalls = 0;
    installFetch((m, url) => {
      if (url === '/api/admin/attention') {
        attentionCalls += 1;
        return respond(attention);
      }
      if (isApply(m, url)) return slow;
      return null;
    });
    renderApp('/admin');
    const list = await screen.findByRole('region', { name: 'Pick the right film' });
    const buttons = await within(list).findAllByRole('button', { name: 'Find the film…' });
    await userEvent.click(buttons[0]);
    const first = await screen.findByRole('dialog', { name: 'Fix match' });
    await userEvent.click((await within(first).findAllByRole('radio'))[0]);
    await userEvent.click(within(first).getByRole('button', { name: 'Use this match' }));

    await userEvent.click(buttons[1]);
    await waitFor(() =>
      expect(
        within(screen.getByRole('dialog', { name: 'Fix match' })).getByRole('searchbox'),
      ).toHaveValue('Local Hero'),
    );
    const open = screen.getByRole('dialog', { name: 'Fix match' });
    await userEvent.click((await within(open).findAllByRole('radio'))[1]);
    const attentionBefore = attentionCalls;

    release(respond({ filmId: 31, joined: false }));

    await waitFor(() => expect(attentionCalls).toBeGreaterThan(attentionBefore));
    const still = screen.getByRole('dialog', { name: 'Fix match' });
    expect(within(still).getByRole('searchbox')).toHaveValue('Local Hero');
    expect(within(still).getAllByRole('radio')[1]).toBeChecked();
  });
});

describe('Admin: grouping report', () => {
  it('lists the newest changes with a label, a time and what happened', async () => {
    installFetch();
    renderApp('/admin/library');
    const card = await screen.findByRole('region', { name: 'Library scan' });
    const list = await within(card).findByRole('list', { name: 'Grouping changes' });

    const items = within(list).getAllByRole('listitem');
    expect(items).toHaveLength(3);
    expect(items[0]).toHaveTextContent('Joined');
    expect(items[0]).toHaveTextContent('08 Nov 12:41');
    expect(items[0]).toHaveTextContent(
      'Joined 2 files of "The Two Towers" into one edition "Original"',
    );
    expect(items[1]).toHaveTextContent('Split');
    expect(items[2]).toHaveTextContent('Check');
  });

  it('says nothing was grouped when the report is empty', async () => {
    installFetch((m, url) =>
      url === '/api/admin/grouping-report' ? respond({ items: [] }) : null,
    );
    renderApp('/admin/library');
    expect(await screen.findByText('The last scan changed nothing.')).toBeInTheDocument();
  });

  it('offers a retry when the report cannot load', async () => {
    installFetch((m, url) => (url === '/api/admin/grouping-report' ? respond(null, 500) : null));
    renderApp('/admin/library');
    const card = await screen.findByRole('region', { name: 'Library scan' });
    expect(
      await within(card).findByText('Couldn’t load what the last scan changed.'),
    ).toBeInTheDocument();
    expect(within(card).getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });
});

it('keeps the fixtures honest', () => {
  expect(fixtures.adminMatch).toHaveLength(2);
});
