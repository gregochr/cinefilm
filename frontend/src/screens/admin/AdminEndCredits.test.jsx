import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp, respond } from '../../test/mockApi.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
});

const PATH = '/admin/library/credits';
const creditsCalls = (fetchMock) =>
  fetchMock.mock.calls.filter(([url]) => String(url).startsWith('/api/admin/credits'));
const stills = () => screen.queryAllByRole('img', { name: /^Frame at/ });

/** `count` rows for a page of a 52-row filter (size 25), with distinct ids and titles. */
function pageOf(page, { total = 52, size = 25 } = {}) {
  const from = page * size;
  const items = Array.from({ length: Math.max(0, Math.min(size, total - from)) }, (_, i) => ({
    ...structuredClone(fixtures.adminCredits.items[1]),
    editionId: 100 + from + i,
    filmId: 100 + from + i,
    title: `Film ${from + i + 1}`,
  }));
  return { ...structuredClone(fixtures.adminCredits), page, size, total, items };
}

/** Serves the list by `filter` and page from `answer(filter, page)`; the rest is the defaults. */
const serve = (answer) =>
  installFetch((method, url) => {
    if (!String(url).startsWith('/api/admin/credits')) return null;
    const params = new URL(url, 'http://test.local').searchParams;
    return answer(params.get('filter'), Number(params.get('page')));
  });

describe('End credits list: the page and its tabs', () => {
  it('has a back link to Library, its own title and the rows with their lines', async () => {
    installFetch();
    renderApp(PATH);

    expect(
      await screen.findByRole('heading', { level: 1, name: 'End credits' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '← Library' })).toHaveAttribute(
      'href',
      '/admin/library',
    );
    const list = await screen.findByRole('list', { name: 'End credits' });
    const [alien, heat, kes] = within(list).getAllByRole('listitem');
    // The edition's name only where the film has more than one; the year muted, none when unknown.
    expect(alien).toHaveTextContent('Alien · 1979 · Director’s Cut');
    expect(alien).toHaveTextContent('Not settled yet · 1:52:00');
    expect(heat).toHaveTextContent('Heat · 1995');
    expect(heat).not.toHaveTextContent('Theatrical');
    expect(heat).toHaveTextContent('No marker · the end screen shows at 97%');
    expect(heat).toHaveTextContent('Plex has no marker for this film');
    expect(kes).toHaveTextContent('This film’s length isn’t known');
  });

  it('shows the four tabs with their counts, Needs a look first, and reads that filter', async () => {
    const fetchMock = installFetch();
    renderApp(PATH);

    expect(await screen.findByRole('tablist', { name: 'End credits lists' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Needs a look 3' })).toHaveAttribute(
      'aria-selected',
      'true',
    );
    expect(screen.getByRole('tab', { name: 'From Plex 1' })).toHaveAttribute(
      'aria-selected',
      'false',
    );
    expect(screen.getByRole('tab', { name: 'Yours 1' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'All 5' })).toBeInTheDocument();
    expect(creditsCalls(fetchMock).map(([url]) => url)).toEqual([
      '/api/admin/credits?filter=none&page=0',
    ]);
  });

  it('opens on the tab named by ?show=, and a tab press changes the address and the read', async () => {
    const fetchMock = installFetch();
    renderApp(`${PATH}?show=plex`, { showLocation: true });

    expect(await screen.findByRole('tab', { name: 'From Plex 1' })).toHaveAttribute(
      'aria-selected',
      'true',
    );
    await userEvent.click(screen.getByRole('tab', { name: 'Yours 1' }));

    expect(screen.getByTestId('location')).toHaveTextContent(`${PATH}?show=owner`);
    expect(screen.getByRole('tab', { name: 'Yours 1' })).toHaveAttribute('aria-selected', 'true');
    expect(creditsCalls(fetchMock).map(([url]) => url)).toEqual([
      '/api/admin/credits?filter=plex&page=0',
      '/api/admin/credits?filter=owner&page=0',
    ]);
  });

  it('treats an unknown ?show= as Needs a look', async () => {
    installFetch();
    renderApp(`${PATH}?show=bogus`);

    expect(await screen.findByRole('tab', { name: 'Needs a look 3' })).toHaveAttribute(
      'aria-selected',
      'true',
    );
  });

  it('says "No films here" for an empty tab, with no pager', async () => {
    serve(() => respond({ ...pageOf(0, { total: 0 }) }));
    renderApp(PATH);

    expect(await screen.findByText('No films here')).toBeInTheDocument();
    expect(screen.queryByRole('list', { name: 'End credits' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Next' })).toBeNull();
  });
});

describe('End credits list: paging', () => {
  it('pages with Previous and Next, words the range, and keeps the ends focusable but inert', async () => {
    const fetchMock = serve((filter, page) => respond(pageOf(page)));
    renderApp(PATH);

    expect(await screen.findByText('Film 1')).toBeInTheDocument();
    expect(screen.getByText('Showing 1–25 of 52')).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: 'Pages' })).toBeInTheDocument();
    const previous = screen.getByRole('button', { name: 'Previous' });
    expect(previous).toHaveAttribute('aria-disabled', 'true');
    await userEvent.click(previous);
    expect(creditsCalls(fetchMock)).toHaveLength(1);

    await userEvent.click(screen.getByRole('button', { name: 'Next' }));
    expect(await screen.findByText('Film 26')).toBeInTheDocument();
    expect(screen.getByText('Showing 26–50 of 52')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Previous' })).not.toHaveAttribute('aria-disabled');

    await userEvent.click(screen.getByRole('button', { name: 'Next' }));
    expect(await screen.findByText('Film 51')).toBeInTheDocument();
    expect(screen.getByText('Showing 51–52 of 52')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Next' })).toHaveAttribute('aria-disabled', 'true');
    expect(creditsCalls(fetchMock).map(([url]) => url)).toEqual([
      '/api/admin/credits?filter=none&page=0',
      '/api/admin/credits?filter=none&page=1',
      '/api/admin/credits?filter=none&page=2',
    ]);
  });

  it('keeps focus on the pressed Next and Previous button after the page arrives', async () => {
    serve((filter, page) => respond(pageOf(page)));
    renderApp(PATH);

    await userEvent.click(await screen.findByRole('button', { name: 'Next' }));
    expect(await screen.findByText('Film 26')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Next' })).toHaveFocus();

    await userEvent.click(screen.getByRole('button', { name: 'Previous' }));
    expect(await screen.findByText('Film 1')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Previous' })).toHaveFocus();
  });

  it('keeps the pager on the page on screen while the next one loads', async () => {
    let release;
    const held = new Promise((resolve) => {
      release = resolve;
    });
    serve((filter, page) => (page === 1 ? held : respond(pageOf(page))));
    renderApp(PATH);

    await userEvent.click(await screen.findByRole('button', { name: 'Next' }));

    expect(screen.getByText('Film 1')).toBeInTheDocument();
    expect(screen.getByText('Showing 1–25 of 52')).toBeInTheDocument();
    release(respond(pageOf(1)));
    expect(await screen.findByText('Showing 26–50 of 52')).toBeInTheDocument();
  });

  it('shows the skeleton, not the last rows, when Check again re-reads a page that failed', async () => {
    let release;
    let reads = 0;
    serve((filter, page) => {
      if (page === 0) return respond(pageOf(0));
      if (++reads === 1) return respond(null, 500);
      return new Promise((resolve) => {
        release = () => resolve(respond(pageOf(1)));
      });
    });
    renderApp(PATH);
    await userEvent.click(await screen.findByRole('button', { name: 'Next' }));
    expect(await screen.findByRole('alert')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Check again' }));

    expect(await screen.findByRole('status', { name: 'Loading end credits' })).toBeInTheDocument();
    expect(screen.queryByText('Film 1')).toBeNull();
    release();
    expect(await screen.findByText('Film 26')).toBeInTheDocument();
  });

  it('goes back to page 0 when the tab changes', async () => {
    const fetchMock = serve((filter, page) => respond(pageOf(page)));
    renderApp(PATH);
    await userEvent.click(await screen.findByRole('button', { name: 'Next' }));
    expect(await screen.findByText('Film 26')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('tab', { name: /^All/ }));

    expect(await screen.findByText('Film 1')).toBeInTheDocument();
    expect(creditsCalls(fetchMock).at(-1)[0]).toBe('/api/admin/credits?filter=all&page=0');
  });

  it('shows the skeleton, not the last tab’s rows, while another tab loads', async () => {
    let release;
    const held = new Promise((resolve) => {
      release = resolve;
    });
    serve((filter, page) => (filter === 'all' ? held : respond(pageOf(page))));
    renderApp(PATH);
    await screen.findByText('Film 1');

    await userEvent.click(screen.getByRole('tab', { name: /^All/ }));

    expect(await screen.findByRole('status', { name: 'Loading end credits' })).toBeInTheDocument();
    expect(screen.queryByText('Film 1')).toBeNull();
    release(
      respond({
        ...pageOf(0),
        items: pageOf(0)
          .items.slice(0, 1)
          .map((r) => ({ ...r, title: 'Only film' })),
      }),
    );
    expect(await screen.findByText('Only film')).toBeInTheDocument();
  });

  it('steps back one page when the current page comes back empty but the filter still has films', async () => {
    let shrunk = false;
    let release;
    let reads = 0;
    const fetchMock = serve((filter, page) => {
      // The step-back read of page 0 (its third) is held, to see what shows meanwhile.
      if (page === 0 && ++reads === 3 && shrunk) {
        return new Promise((resolve) => {
          release = () => resolve(respond(pageOf(0)));
        });
      }
      if (page === 1 && shrunk) return respond({ ...pageOf(1, { total: 25 }), items: [] });
      return respond(pageOf(page));
    });
    renderApp(PATH);
    await userEvent.click(await screen.findByRole('button', { name: 'Next' }));
    expect(await screen.findByText('Film 26')).toBeInTheDocument();
    shrunk = true;
    // A read of the same page: the rows went meanwhile (Check again after a failure re-reads it).
    await userEvent.click(screen.getByRole('button', { name: 'Previous' }));
    expect(await screen.findByText('Film 1')).toBeInTheDocument();
    const next = screen.getByRole('button', { name: 'Next' });
    await userEvent.click(next);

    await waitFor(() => expect(creditsCalls(fetchMock)).toHaveLength(5));
    // The empty answer is not shown: the pressed button keeps focus and the earlier range stays.
    expect(screen.queryByText('No films here')).toBeNull();
    expect(next).toHaveFocus();
    expect(screen.getByText('Showing 1–25 of 52')).toBeInTheDocument();
    release();
    expect(
      creditsCalls(fetchMock)
        .map(([url]) => url)
        .slice(-3),
    ).toEqual([
      '/api/admin/credits?filter=none&page=0',
      '/api/admin/credits?filter=none&page=1',
      '/api/admin/credits?filter=none&page=0',
    ]);
    expect(await screen.findByText('Film 1')).toBeInTheDocument();
    expect(screen.getByText('Showing 1–25 of 52')).toBeInTheDocument();
  });
});

describe('End credits list: loading and failing', () => {
  it('shows a static skeleton while the first read is out', async () => {
    installFetch(() => new Promise(() => {}));
    renderApp(PATH);

    expect(await screen.findByRole('status', { name: 'Loading end credits' })).toBeInTheDocument();
    expect(screen.queryByRole('list', { name: 'End credits' })).toBeNull();
  });

  it('shows the error banner with Check again, which reads again', async () => {
    let fail = true;
    const fetchMock = serve(() => (fail ? respond(null, 500) : respond(fixtures.adminCredits)));
    renderApp(PATH);

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Couldn’t load the end credits list.',
    );
    fail = false;
    await userEvent.click(screen.getByRole('button', { name: 'Check again' }));

    expect(await screen.findByRole('list', { name: 'End credits' })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).toBeNull();
    expect(creditsCalls(fetchMock)).toHaveLength(2);
  });

  it('after a 401, signing in through the sign-in card loads the list', async () => {
    let signedIn = false;
    const fetchMock = installFetch((method, url, init) => {
      if (method === 'POST' && url === '/api/session') {
        signedIn = JSON.parse(init.body).password === 'right';
        return respond(signedIn ? { authenticated: true } : null, signedIn ? 200 : 401);
      }
      if (String(url).startsWith('/api/admin/credits')) {
        return signedIn ? respond(fixtures.adminCredits) : respond(null, 401);
      }
      return null;
    });
    renderApp(PATH);
    const card = await screen.findByRole('dialog', { name: 'Signed out' });
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Couldn’t load the end credits list.',
    );

    await userEvent.type(within(card).getByLabelText('Password'), 'right');
    await userEvent.click(within(card).getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('list', { name: 'End credits' })).toBeInTheDocument();
    expect(screen.queryByRole('dialog', { name: 'Signed out' })).toBeNull();
    expect(creditsCalls(fetchMock)).toHaveLength(2);
  });
});

describe('End credits list: a row’s panel', () => {
  const lookButtons = () => screen.getAllByRole('button', { name: 'Look and set…' });

  it('shows no still until a panel opens, then exactly two, 30 s apart', async () => {
    installFetch();
    renderApp(PATH);
    await screen.findByRole('list', { name: 'End credits' });
    expect(stills()).toHaveLength(0);

    await userEvent.click(lookButtons()[0]);

    const frames = stills();
    expect(frames.map((img) => img.getAttribute('src'))).toEqual([
      '/api/editions/33/still?atMs=6690000',
      '/api/editions/33/still?atMs=6720000',
    ]);
    expect(frames.map((img) => img.getAttribute('alt'))).toEqual([
      'Frame at 1:51:30',
      'Frame at 1:52:00',
    ]);
  });

  it('has the selected tab name the tab panel that exists', async () => {
    installFetch();
    renderApp(PATH);

    const tab = await screen.findByRole('tab', { name: 'Needs a look 3' });
    const panel = screen.getByRole('tabpanel');

    expect(tab).toHaveAttribute('aria-controls', 'admin-credits-panel-none');
    expect(document.getElementById(tab.getAttribute('aria-controls'))).toBe(panel);
    expect(panel).toHaveAttribute('aria-labelledby', tab.id);
  });

  it('has the button describe its panel with aria-expanded and aria-controls', async () => {
    installFetch();
    renderApp(PATH);
    await screen.findByRole('list', { name: 'End credits' });
    const [alien] = lookButtons();
    expect(alien).toHaveAttribute('aria-expanded', 'false');

    await userEvent.click(alien);

    expect(alien).toHaveAttribute('aria-expanded', 'true');
    const panel = screen.getByRole('group', { name: 'Stills for Alien' });
    expect(alien).toHaveAttribute('aria-controls', panel.id);

    await userEvent.click(alien);
    expect(alien).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('group', { name: 'Stills for Alien' })).toBeNull();
  });

  it('keeps one panel open: opening another closes the first, and its button says so', async () => {
    installFetch();
    renderApp(PATH);
    await screen.findByRole('list', { name: 'End credits' });
    const [alien, heat] = lookButtons();

    await userEvent.click(alien);
    await userEvent.click(heat);

    expect(alien).toHaveAttribute('aria-expanded', 'false');
    expect(heat).toHaveAttribute('aria-expanded', 'true');
    expect(screen.queryByRole('group', { name: 'Stills for Alien' })).toBeNull();
    expect(screen.getByRole('group', { name: 'Stills for Heat' })).toBeInTheDocument();
    // Heat has no marker: the stills are at 97% of its runtime.
    expect(stills().map((img) => img.getAttribute('src'))).toEqual([
      '/api/editions/11/still?atMs=9864000',
      '/api/editions/11/still?atMs=9894000',
    ]);
  });

  it('shows no stills for a film with no known length, only the reason', async () => {
    installFetch();
    renderApp(PATH);
    await screen.findByRole('list', { name: 'End credits' });

    await userEvent.click(lookButtons()[2]);

    const panel = screen.getByRole('group', { name: 'Stills for Kes' });
    expect(within(panel).getByText('This film’s length isn’t known')).toBeInTheDocument();
    expect(stills()).toHaveLength(0);
  });

  it('closes the open panel when the page changes', async () => {
    serve((filter, page) => respond(pageOf(page)));
    renderApp(PATH);
    await screen.findByText('Film 1');
    await userEvent.click(lookButtons()[0]);
    expect(screen.getByRole('group', { name: 'Stills for Film 1' })).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Next' }));

    expect(await screen.findByText('Film 26')).toBeInTheDocument();
    expect(screen.queryByRole('group')).toBeNull();
  });

  it('closes the open panel when the tab changes', async () => {
    installFetch();
    renderApp(PATH);
    await screen.findByRole('list', { name: 'End credits' });
    await userEvent.click(lookButtons()[0]);

    await userEvent.click(screen.getByRole('tab', { name: 'From Plex 1' }));

    expect(screen.queryByRole('group')).toBeNull();
  });

  it('keeps a loaded still in place once its image has arrived', async () => {
    installFetch();
    renderApp(PATH);
    await screen.findByRole('list', { name: 'End credits' });
    await userEvent.click(lookButtons()[0]);
    expect(screen.getAllByTestId('skeleton').length).toBeGreaterThanOrEqual(2);

    stills().forEach((img) => fireEvent.load(img));

    expect(screen.queryAllByTestId('skeleton')).toHaveLength(0);
    expect(stills()).toHaveLength(2);
  });
});
