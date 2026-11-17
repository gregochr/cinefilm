import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp, respond } from '../../test/mockApi.jsx';
import { forcedRow, fullRow, serveNeeds } from '../../test/needRoutes.js';

afterEach(() => {
  vi.unstubAllGlobals();
});

const SUMMARY = '/api/admin/summary';
const FULL = '/api/admin/subtitles/full-need';
const NONE_EXIST = 'No English subtitles exist';

/** A server with the two need lists (one unresolved full film, one forced) and the owner-choice routes. */
function serve(options = {}) {
  const needs = serveNeeds({ full: [fullRow()], forced: [forcedRow()], ...options });
  const fetchMock = installFetch(needs.route);
  const summaryReads = () => fetchMock.mock.calls.filter(([url]) => url === SUMMARY).length;
  const fullReads = () => fetchMock.mock.calls.filter(([url]) => url === FULL).length;
  return { ...needs, summaryReads, fullReads };
}

const fullRegion = () => screen.findByRole('region', { name: 'English subtitles missing' });

describe('Admin To do: the English-subtitles rows offer the Subtitles tab’s actions', () => {
  it('shows search, "Add my own file…" and "No English subtitles exist" on the row', async () => {
    serve();
    renderApp('/admin');
    const region = await fullRegion();

    expect(
      within(region)
        .getAllByRole('button')
        .map((b) => b.textContent),
    ).toEqual(['Search for English subtitles…', 'Add my own file…', NONE_EXIST]);
  });

  it('saves NONE_EXIST, the row and group leave, the lists and summary read again, and focus goes to the title', async () => {
    const server = serve();
    renderApp('/admin');
    const region = await fullRegion();
    const summaryBefore = server.summaryReads();
    const fullBefore = server.fullReads();

    await userEvent.click(within(region).getByRole('button', { name: NONE_EXIST }));

    await waitFor(() => expect(screen.queryByText('Rashomon')).not.toBeInTheDocument());
    expect(server.calls).toEqual([{ filmId: 6, kind: 'full', body: { choice: 'NONE_EXIST' } }]);
    await waitFor(() => expect(server.summaryReads()).toBeGreaterThan(summaryBefore));
    expect(server.fullReads()).toBeGreaterThan(fullBefore);
    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1, name: 'To do' })).toHaveFocus(),
    );
  });

  it('sends one save however often the button is clicked while it is in flight', async () => {
    let release;
    const held = new Promise((resolve) => {
      release = resolve;
    });
    const server = serve({ gate: () => held });
    renderApp('/admin');
    const region = await fullRegion();
    const button = within(region).getByRole('button', { name: NONE_EXIST });

    await userEvent.click(button);
    await waitFor(() => expect(button).toHaveAttribute('aria-disabled', 'true'));
    await userEvent.click(button);
    await userEvent.click(button);
    expect(server.calls).toHaveLength(1);

    await act(async () => release());
    await waitFor(() => expect(screen.queryByText('Rashomon')).not.toBeInTheDocument());
    expect(server.calls).toHaveLength(1);
  });

  it('keeps the row and says so when the save fails, and the next click tries again', async () => {
    let failing = true;
    const server = serve({ fail: () => (failing ? respond({ error: 'boom' }, 500) : null) });
    renderApp('/admin');
    const region = await fullRegion();
    const button = within(region).getByRole('button', { name: NONE_EXIST });

    await userEvent.click(button);

    expect(await within(region).findByRole('alert')).toHaveTextContent("Couldn't save: try again");
    expect(within(region).getByText('Rashomon')).toBeInTheDocument();
    expect(button).not.toHaveAttribute('aria-disabled');
    // The row keeps its button and focus: a failed save does not arm the focus fallback.
    expect(button).toHaveFocus();
    // A failed save changes nothing, so the summary is not read again.
    expect(server.summaryReads()).toBe(1);

    failing = false;
    await userEvent.click(button);

    await waitFor(() => expect(screen.queryByText('Rashomon')).not.toBeInTheDocument());
    expect(server.calls).toHaveLength(2);
  });

  it('opens the OpenSubtitles picker from the row as before', async () => {
    serve();
    renderApp('/admin');
    const region = await fullRegion();

    await userEvent.click(
      within(region).getByRole('button', { name: 'Search for English subtitles…' }),
    );

    expect(await screen.findByRole('dialog')).toBeInTheDocument();
  });

  it('opens the add-a-file form from the row', async () => {
    serve();
    renderApp('/admin');
    const region = await fullRegion();

    await userEvent.click(within(region).getByRole('button', { name: 'Add my own file…' }));

    expect(within(region).getByRole('button', { name: 'Add my own file…' })).toHaveAttribute(
      'aria-expanded',
      'true',
    );
  });

  it('attaches your own file from the row: the row leaves and the lists read again', async () => {
    const needs = serveNeeds({ full: [fullRow()], forced: [forcedRow()] });
    const ownFile = {
      ...structuredClone(fixtures.adminSubtitles[0]),
      id: 31,
      kind: 'FULL',
      origin: 'FILE',
      path: 'Rashomon (1950)/Rashomon.en.srt',
      filmId: null,
      filmTitle: null,
      state: 'unmatched',
    };
    let attached = false;
    const fetchMock = installFetch((method, url) => {
      if (url === '/api/admin/subtitles') return respond([ownFile]);
      if (url === '/api/films/6') {
        return respond({
          id: 6,
          title: 'Rashomon',
          editions: [{ id: 60, name: 'Original', parts: 1 }],
        });
      }
      if (url === '/api/admin/subtitles/31/attach' && method === 'POST') {
        attached = true;
        needs.lists.full[0] = {
          ...needs.lists.full[0],
          unresolved: false,
          fullSubtitle: { source: 'SIDECAR' },
        };
        return respond(ownFile);
      }
      return needs.route(method, url);
    });
    renderApp('/admin');
    const region = await fullRegion();
    const summaryBefore = fetchMock.mock.calls.filter(([url]) => url === SUMMARY).length;
    await userEvent.click(within(region).getByRole('button', { name: 'Add my own file…' }));
    await userEvent.click(await screen.findByRole('radio', { name: /Rashomon\.en\.srt/ }));

    await userEvent.click(screen.getByRole('button', { name: 'Attach' }));

    await waitFor(() => expect(screen.queryByText('Rashomon')).not.toBeInTheDocument());
    expect(attached).toBe(true);
    expect(fetchMock.mock.calls.filter(([url]) => url === SUMMARY).length).toBeGreaterThan(
      summaryBefore,
    );
  });
});
