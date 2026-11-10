import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
});

const isSearch = (m, url) => m === 'GET' && /^\/api\/admin\/films\/31\/match\?/.test(url);
const isApply = (m, url) => m === 'POST' && url === '/api/admin/films/31/match';

async function openPanel() {
  renderApp('/admin');
  const attention = await screen.findByRole('region', { name: 'Films needing attention' });
  await userEvent.click(await within(attention).findByRole('button', { name: 'Fix match' }));
  return screen.findByRole('region', { name: 'Fix match' });
}

describe('Admin: Fix match', () => {
  it('only unmatched films get a Fix match button', async () => {
    installFetch();
    renderApp('/admin');
    const attention = await screen.findByRole('region', { name: 'Films needing attention' });
    expect(await within(attention).findAllByRole('button', { name: 'Fix match' })).toHaveLength(1);
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
      expect(screen.queryByRole('region', { name: 'Fix match' })).not.toBeInTheDocument(),
    );
    const post = fetchMock.mock.calls.find((c) => isApply(c[1]?.method, c[0]));
    expect(JSON.parse(post[1].body)).toEqual({ tmdbId: 16307 });
    await waitFor(() => expect(attentionCalls).toBeGreaterThan(before));
  });

  it('Cancel closes the panel without applying anything', async () => {
    const fetchMock = installFetch();
    const panel = await openPanel();

    await userEvent.click(within(panel).getByRole('button', { name: 'Cancel' }));

    expect(screen.queryByRole('region', { name: 'Fix match' })).not.toBeInTheDocument();
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
    installFetch((m, url) => (isSearch(m, url) ? respond({ error: 'x' }, status) : null));
    const panel = await openPanel();
    expect(await within(panel).findByRole('alert')).toHaveTextContent(text);
  });

  it('shows the problem from an apply and keeps the panel open', async () => {
    installFetch((m, url) =>
      isApply(m, url) ? respond({ error: 'A scan is running' }, 409) : null,
    );
    const panel = await openPanel();
    await userEvent.click((await within(panel).findAllByRole('radio'))[1]);
    await userEvent.click(within(panel).getByRole('button', { name: 'Use this match' }));

    expect(await within(panel).findByRole('alert')).toHaveTextContent(
      'A scan is running. Try again when it finishes.',
    );
    expect(screen.getByRole('region', { name: 'Fix match' })).toBeInTheDocument();
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

describe('Admin: grouping report', () => {
  it('lists the newest changes with a label, a time and what happened', async () => {
    installFetch();
    renderApp('/admin');
    const panel = await screen.findByRole('region', { name: 'Grouping report' });
    const list = await within(panel).findByRole('list', { name: 'Grouping changes' });

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
    renderApp('/admin');
    expect(await screen.findByText('Nothing has been grouped or split yet.')).toBeInTheDocument();
  });

  it('offers a retry when the report cannot load', async () => {
    installFetch((m, url) => (url === '/api/admin/grouping-report' ? respond(null, 500) : null));
    renderApp('/admin');
    const panel = await screen.findByRole('region', { name: 'Grouping report' });
    expect(await within(panel).findByText(/Couldn.t load the grouping report/)).toBeInTheDocument();
    expect(within(panel).getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });
});

it('keeps the fixtures honest', () => {
  expect(fixtures.adminMatch).toHaveLength(2);
});
