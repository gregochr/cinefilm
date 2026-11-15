import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { installFetch, renderApp, respond } from '../test/mockApi.jsx';
import { forcedRow, fullRow, serveNeeds, unlabelledRow } from '../test/needRoutes.js';

afterEach(() => {
  vi.unstubAllGlobals();
});

const deferred = () => {
  let resolve;
  const promise = new Promise((res) => {
    resolve = res;
  });
  return { promise, resolve };
};

const SEARCH_FULL = 'Search for English subtitles…';
const rashomon = () => fullRow(); // edition 102
const downfall = () => unlabelledRow(); // edition 82, unlabelled German audio
const taken = () => forcedRow(); // edition 101

/** The lists, the choice routes and the picker's routes; every other route answers with the defaults. */
function serve(options = {}) {
  const needs = serveNeeds({
    full: [rashomon(), downfall()],
    forced: [taken()],
    ...options,
  });
  const fetchMock = installFetch(needs.route);
  return { ...needs, fetchMock };
}

const tab = (name) => screen.findByRole('tab', { name });
const rowOf = (container, title) =>
  within(container)
    .getAllByRole('listitem')
    .find((row) => within(row).queryByText(title));
const picker = () => screen.findByRole('dialog');

describe('Subtitles: the picker from the foreign-language tab', () => {
  it('opens for the film’s edition as a full search, and Cancel returns focus to the button and keeps the row', async () => {
    const { fetchMock } = serve();
    renderApp('/admin/subtitles');
    await tab('Foreign-language films 2');
    const panel = screen.getByRole('tabpanel');
    const search = within(rowOf(panel, 'Rashomon')).getByRole('button', { name: SEARCH_FULL });
    expect(search).toHaveAccessibleDescription(
      'Shows OpenSubtitles results for this film so you can pick one',
    );

    await userEvent.click(search);

    const dialog = await picker();
    expect(dialog).toHaveAccessibleName('Rashomon (1950)');
    expect(
      fetchMock.mock.calls.some(
        ([url]) => url === '/api/admin/editions/102/opensubtitles?kind=full',
      ),
    ).toBe(true);
    await within(dialog).findByRole('list', { name: 'OpenSubtitles results' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(search).toHaveFocus();
    expect(within(screen.getByRole('tabpanel')).getByText('Rashomon')).toBeInTheDocument();
  });

  it('downloads the chosen file, closes, and the film leaves the list, stays gone after a reload, and focus lands on the panel', async () => {
    const { downloads, reads } = serve();
    const { unmount } = renderApp('/admin/subtitles');
    await tab('Foreign-language films 2');
    const panel = screen.getByRole('tabpanel');
    await userEvent.click(
      within(rowOf(panel, 'Rashomon')).getByRole('button', { name: SEARCH_FULL }),
    );
    const dialog = await picker();
    await within(dialog).findByRole('list', { name: 'OpenSubtitles results' });
    const readsBefore = reads.full;

    await userEvent.click(
      within(dialog).getByRole('button', { name: 'Use this file: Heat.1995.BluRay.SDH' }),
    );

    expect(downloads).toEqual([{ editionId: 102, fileId: 6011, kind: 'full' }]);
    await tab('Foreign-language films 1');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(within(screen.getByRole('tabpanel')).queryByText('Rashomon')).not.toBeInTheDocument();
    expect(within(screen.getByRole('tabpanel')).getByText('Downfall')).toBeInTheDocument();
    expect(reads.full).toBeGreaterThan(readsBefore);
    await waitFor(() => expect(screen.getByRole('tabpanel')).toHaveFocus());

    unmount();
    renderApp('/admin/subtitles');
    await tab('Foreign-language films 1');
    expect(within(screen.getByRole('tabpanel')).queryByText('Rashomon')).not.toBeInTheDocument();
  });

  it('offers "It’s in German — find subtitles" for unlabelled audio, which opens the same picker for that film', async () => {
    const { fetchMock } = serve();
    renderApp('/admin/subtitles');
    await tab('Foreign-language films 2');
    const row = rowOf(screen.getByRole('tabpanel'), 'Downfall');

    expect(within(row).queryByRole('button', { name: SEARCH_FULL })).not.toBeInTheDocument();
    await userEvent.click(
      within(row).getByRole('button', { name: 'It’s in German — find subtitles' }),
    );

    expect(await picker()).toHaveAccessibleName('Downfall (2004)');
    expect(
      fetchMock.mock.calls.some(
        ([url]) => url === '/api/admin/editions/82/opensubtitles?kind=full',
      ),
    ).toBe(true);
  });

  it('offers no search for a film with no edition to search', async () => {
    serve({ full: [{ ...rashomon(), editionId: null }] });
    renderApp('/admin/subtitles');

    await tab('Foreign-language films 1');

    const row = rowOf(screen.getByRole('tabpanel'), 'Rashomon');
    expect(within(row).queryByRole('button', { name: SEARCH_FULL })).not.toBeInTheDocument();
    expect(within(row).getByRole('button', { name: 'Add my own file…' })).toBeInTheDocument();
  });

  it('shows the daily-limit text in the dialog and keeps the row', async () => {
    serve({
      failDownload: () =>
        respond({ error: 'OpenSubtitles daily limit reached — try again tomorrow.' }, 429),
    });
    renderApp('/admin/subtitles');
    await tab('Foreign-language films 2');
    await userEvent.click(
      within(rowOf(screen.getByRole('tabpanel'), 'Rashomon')).getByRole('button', {
        name: SEARCH_FULL,
      }),
    );
    const dialog = await picker();
    await within(dialog).findByRole('list', { name: 'OpenSubtitles results' });

    await userEvent.click(
      within(dialog).getByRole('button', { name: 'Use this file: Heat.1995.BluRay.SDH' }),
    );

    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'OpenSubtitles daily limit reached — try again tomorrow.',
    );
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Foreign-language films 2' })).toBeInTheDocument();
  });

  it('closing while the download runs ignores the late answer in the dialog, yet the lists read again and show the server’s truth', async () => {
    const held = deferred();
    // Hold only the download: wrap the route so its answer waits for the test.
    const needs = serveNeeds({ full: [rashomon(), downfall()], forced: [taken()] });
    installFetch((m, url, init) => {
      if (m === 'POST' && url.endsWith('/opensubtitles/download')) {
        return held.promise.then(() => needs.route(m, url, init));
      }
      return needs.route(m, url, init);
    });
    renderApp('/admin/subtitles');
    await tab('Foreign-language films 2');
    const search = within(rowOf(screen.getByRole('tabpanel'), 'Rashomon')).getByRole('button', {
      name: SEARCH_FULL,
    });
    await userEvent.click(search);
    const dialog = await picker();
    await within(dialog).findByRole('list', { name: 'OpenSubtitles results' });
    await userEvent.click(
      within(dialog).getByRole('button', { name: 'Use this file: Heat.1995.BluRay.SDH' }),
    );
    await userEvent.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    // Nothing has come back: the row is still there.
    expect(within(screen.getByRole('tabpanel')).getByText('Rashomon')).toBeInTheDocument();
    const readsBefore = needs.reads.full;

    await act(async () => held.resolve());

    await tab('Foreign-language films 1');
    expect(needs.reads.full).toBeGreaterThan(readsBefore);
  });
});

describe('Subtitles: the picker from the forced tab', () => {
  it('"Find forced subtitles…" opens a forced search, and a picked file takes the film off the list', async () => {
    const { fetchMock, downloads } = serve();
    renderApp('/admin/subtitles');
    await userEvent.click(await tab('Forced subtitles not found 1'));
    const panel = screen.getByRole('tabpanel', { name: 'Forced subtitles not found 1' });

    await userEvent.click(within(panel).getByRole('button', { name: 'Find forced subtitles…' }));

    const dialog = await picker();
    expect(dialog).toHaveAccessibleName('Taken (2008)');
    expect(within(dialog).getByText('Forced subtitles from OpenSubtitles')).toBeInTheDocument();
    expect(
      fetchMock.mock.calls.some(
        ([url]) => url === '/api/admin/editions/101/opensubtitles?kind=forced',
      ),
    ).toBe(true);
    // It does not save "needed" first: the film is on this list because it already is.
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'PUT')).toHaveLength(0);
    await within(dialog).findByRole('list', { name: 'OpenSubtitles results' });
    await userEvent.click(
      within(dialog).getByRole('button', {
        name: 'Use this file: Heat.1995.Directors.Cut.BluRay.x264',
      }),
    );

    await tab('Forced subtitles not found 0');
    expect(downloads).toEqual([{ editionId: 101, fileId: 5012, kind: 'forced' }]);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(
      within(screen.getByRole('tabpanel')).getByText(
        'Nothing here. No English film is waiting for forced subtitles.',
      ),
    ).toBeInTheDocument();
  });
});

describe('To do: the picker from the subtitle groups', () => {
  it('puts the same buttons on the English-subtitles and forced rows and removes the row once a file is picked', async () => {
    const { downloads } = serve();
    renderApp('/admin');
    const english = await screen.findByRole('region', { name: 'English subtitles missing' });
    const forced = await screen.findByRole('region', { name: 'Forced subtitles not found' });

    expect(
      within(rowOf(english, 'Downfall')).getByRole('button', {
        name: 'It’s in German — find subtitles',
      }),
    ).toBeInTheDocument();
    expect(
      within(forced).getByRole('button', { name: 'Find forced subtitles…' }),
    ).toBeInTheDocument();
    const search = within(rowOf(english, 'Rashomon')).getByRole('button', { name: SEARCH_FULL });
    await userEvent.click(search);
    const dialog = await picker();
    await within(dialog).findByRole('list', { name: 'OpenSubtitles results' });
    await userEvent.click(
      within(dialog).getByRole('button', {
        name: 'Use this file: Heat.1995.Directors.Cut.BluRay.x264',
      }),
    );

    await waitFor(() => expect(downloads).toHaveLength(1));
    expect(downloads[0]).toEqual({ editionId: 102, fileId: 6012, kind: 'full' });
    await waitFor(() =>
      expect(
        within(screen.getByRole('region', { name: 'English subtitles missing' })).queryByText(
          'Rashomon',
        ),
      ).not.toBeInTheDocument(),
    );
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    // The opener left with its row, so focus goes to the section title.
    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1, name: 'To do' })).toHaveFocus(),
    );
  });

  it('Cancel returns focus to the To do row’s button', async () => {
    serve();
    renderApp('/admin');
    const forced = await screen.findByRole('region', { name: 'Forced subtitles not found' });
    const button = within(forced).getByRole('button', { name: 'Find forced subtitles…' });

    await userEvent.click(button);
    await userEvent.click(await screen.findByRole('button', { name: 'Cancel' }));

    expect(button).toHaveFocus();
  });

  it('leaves a subtitle row with no edition without a button', async () => {
    serve({ forced: [{ ...taken(), editionId: null }] });
    renderApp('/admin');

    const forced = await screen.findByRole('region', { name: 'Forced subtitles not found' });

    expect(within(forced).getByText('Taken')).toBeInTheDocument();
    expect(within(forced).queryByRole('button')).not.toBeInTheDocument();
  });
});
