import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp, respond } from '../../test/mockApi.jsx';
import { forcedRow, fullRow, serveNeeds, unlabelledRow } from '../../test/needRoutes.js';

afterEach(() => {
  vi.unstubAllGlobals();
});

const FULL = '/api/admin/subtitles/full-need';
const FORCED = '/api/admin/forced-subtitles/films';
const SUMMARY = '/api/admin/summary';

const rashomon = () => fullRow(); // unresolved, owner-set, nothing found
const downfall = () => unlabelledRow(); // unresolved, unlabelled audio
const taken = () => forcedRow(); // unresolved forced, OpenSubtitles has 2 files
const dasBoot = () =>
  fullRow({
    filmId: 3,
    title: 'Das Boot',
    year: 1981,
    unresolved: false,
    fullSubtitle: { source: 'SIDECAR' },
    subtitled: true,
    originalLanguage: 'de',
    ownerChoice: null,
    reason: 'FOREIGN_AUDIO',
  });

/** A server with the two lists and the choice routes; every other route answers with the defaults. */
function serve(options = {}) {
  const needs = serveNeeds({
    full: [rashomon(), downfall(), dasBoot()],
    forced: [taken()],
    ...options,
  });
  const fetchMock = installFetch(needs.route);
  const puts = () => fetchMock.mock.calls.filter(([, init]) => init?.method === 'PUT');
  const summaryReads = () => fetchMock.mock.calls.filter(([url]) => url === SUMMARY).length;
  return { ...needs, fetchMock, puts, summaryReads };
}

const tab = (name) => screen.findByRole('tab', { name });
const rowOf = (title) =>
  within(screen.getByRole('tabpanel'))
    .getAllByRole('listitem')
    .find((row) => within(row).queryByText(title));

describe('Admin Subtitles section: the page', () => {
  it('has the title, the lead copy and Everything else', async () => {
    serve();
    renderApp('/admin/subtitles');

    expect(screen.getByRole('heading', { level: 1, name: 'Subtitles' })).toBeInTheDocument();
    expect(screen.getByText('You only need two kinds, both in English:')).toBeInTheDocument();
    expect(screen.getByText('full subtitles').tagName).toBe('B');
    expect(screen.getByText('forced subtitles').tagName).toBe('B');
    expect(
      screen.getByText(
        /for English films with scenes in another language\. CineFilm finds and downloads both/,
      ),
    ).toHaveTextContent('this page lists only what it couldn’t.');
    expect(await screen.findByRole('heading', { name: 'Everything else' })).toBeInTheDocument();
    expect(
      screen.getByText(/Subtitles look wrong on a film\? Fix them from that film’s page/),
    ).toBeInTheDocument();
  });

  it('shows the two tabs with the count of unresolved films, and the server order of rows', async () => {
    serve();
    renderApp('/admin/subtitles');

    const foreign = await tab('Foreign-language films 2');
    expect(foreign).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tab', { name: 'Forced subtitles not found 1' })).toHaveAttribute(
      'aria-selected',
      'false',
    );
    const panel = screen.getByRole('tabpanel', { name: 'Foreign-language films 2' });
    expect(within(panel).getByText('2 films')).toBeInTheDocument();
    expect(
      within(panel)
        .getAllByRole('listitem')
        .map((row) => within(row).getByText(/Rashomon|Downfall/).textContent),
    ).toEqual(['Rashomon · 1950', 'Downfall · 2004']);
    // Das Boot is settled (a subtitle plays): the server did not flag it, so it is not listed.
    expect(within(panel).queryByText('Das Boot')).not.toBeInTheDocument();
  });

  it('words each foreign row and offers the search, the attach and the choices', async () => {
    serve();
    renderApp('/admin/subtitles');

    await tab('Foreign-language films 2');
    const rash = await screen.findByText('Rashomon');
    expect(rash).toBeInTheDocument();
    expect(screen.getByText('· 1950')).toBeInTheDocument();
    expect(
      screen.getByText(
        'Spoken: Japanese · Set by you · nothing found automatically on OpenSubtitles',
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        'Audio isn’t labelled · CineFilm assumed German, the original language TMDB lists',
      ),
    ).toBeInTheDocument();
    const rashomonRow = rowOf('Rashomon');
    expect(
      within(rashomonRow)
        .getAllByRole('button')
        .map((b) => b.textContent),
    ).toEqual(['Search for English subtitles…', 'Add my own file…', 'No English subtitles exist']);
    const downfallRow = rowOf('Downfall');
    expect(
      within(downfallRow)
        .getAllByRole('button')
        .map((b) => b.textContent),
    ).toEqual([
      'It’s in German — find subtitles',
      'Add my own file…',
      'It’s English',
      'No English subtitles exist',
    ]);
    expect(
      within(downfallRow).getByRole('button', { name: 'It’s English' }),
    ).toHaveAccessibleDescription(
      expect.stringContaining('“It’s English” marks it done and stops asking'),
    );
    // No distrusted-download buttons: the API has no trust.
    for (const name of ['Use it anyway', 'Search again…']) {
      expect(screen.queryByRole('button', { name })).not.toBeInTheDocument();
    }
  });

  it('words the forced rows and offers the search and "No foreign scenes"', async () => {
    serve();
    renderApp('/admin/subtitles');

    await userEvent.click(await tab('Forced subtitles not found 1'));

    const panel = screen.getByRole('tabpanel', { name: 'Forced subtitles not found 1' });
    expect(within(panel).getByText('Taken')).toBeInTheDocument();
    expect(within(panel).getByText('· 2008')).toBeInTheDocument();
    expect(
      within(panel).getByText('OpenSubtitles has 2 foreign-parts-only files'),
    ).toBeInTheDocument();
    expect(
      within(panel).getByRole('button', { name: 'No foreign scenes' }),
    ).toHaveAccessibleDescription(
      expect.stringContaining('“No foreign scenes” marks it done and stops asking'),
    );
    expect(
      within(panel).getByRole('button', { name: 'Find forced subtitles…' }),
    ).toBeInTheDocument();
    // Switching tabs hides the other panel.
    expect(
      screen.queryByRole('tabpanel', { name: /Foreign-language films/ }),
    ).not.toBeInTheDocument();
  });

  it('shows the being-checked count from the server, and the other counts', async () => {
    serve({
      english: 530,
      full: [
        rashomon(),
        dasBoot(),
        fullRow({ filmId: 9, title: 'Léon', unresolved: false, beingChecked: true }),
      ],
      forced: [
        taken(),
        forcedRow({ filmId: 10, title: 'Heat', unresolved: false, beingChecked: true }),
      ],
    });
    renderApp('/admin/subtitles');

    const figures = await screen.findByRole('list', { name: 'Everything else' });
    // The figures are the server's counts: one subtitled film, and 530 English films outside the list.
    expect(
      await within(figures).findByText('foreign film has English subtitles'),
    ).toBeInTheDocument();
    expect(await within(figures).findByText('530')).toBeInTheDocument();
    expect(within(figures).getByText('films are in English')).toBeInTheDocument();
    expect(within(figures).getByText('being checked online now')).toBeInTheDocument();
    expect(within(figures).getByText('2')).toBeInTheDocument();
  });

  it('says how many are being checked only once both lists have been read', async () => {
    const server = serveNeeds({ full: [rashomon()], forced: [taken()], english: 5 });
    installFetch((method, url, init) =>
      url === FORCED ? respond(null, 500) : server.route(method, url, init),
    );
    renderApp('/admin/subtitles');

    const figures = await screen.findByRole('list', { name: 'Everything else' });
    expect(await within(figures).findByText('films are in English')).toBeInTheDocument();
    expect(within(figures).queryByText('being checked online now')).not.toBeInTheDocument();
  });
});

describe('Admin Subtitles section: owner choices', () => {
  it('"It’s English" saves NOT_NEEDED, the row leaves, the lists and summary read again, and it stays gone', async () => {
    const server = serve();
    const first = renderApp('/admin/subtitles');
    await tab('Foreign-language films 2');
    await screen.findByText('Downfall');
    const summaryBefore = server.summaryReads();

    await userEvent.click(within(rowOf('Downfall')).getByRole('button', { name: 'It’s English' }));

    await waitFor(() => expect(screen.queryByText('Downfall')).not.toBeInTheDocument());
    expect(server.calls).toEqual([{ filmId: 8, kind: 'full', body: { choice: 'NOT_NEEDED' } }]);
    expect(server.puts()[0][0]).toBe('/api/admin/films/8/full-need');
    expect(screen.getByRole('tab', { name: 'Foreign-language films 1' })).toBeInTheDocument();
    expect(screen.getByText('Rashomon')).toBeInTheDocument();
    // The rail, the To do groups and these lists read again (the summary is the rail's source).
    await waitFor(() => expect(server.summaryReads()).toBeGreaterThan(summaryBefore));
    expect(server.reads.full).toBeGreaterThan(1);

    // A reload: a fresh page against the same server still does not list it.
    first.unmount();
    renderApp('/admin/subtitles');
    await tab('Foreign-language films 1');
    expect(screen.queryByText('Downfall')).not.toBeInTheDocument();
  });

  it('"No English subtitles exist" saves NONE_EXIST, the row leaves, Your choices words it, and giving it back restores the row', async () => {
    const server = serve();
    renderApp('/admin/subtitles');
    await tab('Foreign-language films 2');
    await screen.findByText('Downfall');

    await userEvent.click(
      within(rowOf('Downfall')).getByRole('button', { name: 'No English subtitles exist' }),
    );

    await waitFor(() => expect(screen.queryByText('Downfall')).not.toBeInTheDocument());
    expect(server.calls).toEqual([{ filmId: 8, kind: 'full', body: { choice: 'NONE_EXIST' } }]);
    await userEvent.click(await screen.findByRole('button', { name: 'Your choices (2)' }));
    expect(
      screen.getByText(/Full subtitles: you said no English subtitles exist/),
    ).toBeInTheDocument();

    await userEvent.click(
      screen.getByRole('button', { name: 'Let CineFilm decide: Downfall (2004), full subtitles' }),
    );

    expect(await screen.findByText('Downfall')).toBeInTheDocument();
    expect(server.calls[1]).toEqual({ filmId: 8, kind: 'full', body: { choice: null } });
  });

  it('"No foreign scenes" saves NOT_NEEDED for the film, the row leaves, and it stays gone after a reload', async () => {
    const server = serve();
    const first = renderApp('/admin/subtitles');
    await userEvent.click(await tab('Forced subtitles not found 1'));

    await userEvent.click(screen.getByRole('button', { name: 'No foreign scenes' }));

    await waitFor(() => expect(screen.queryByText('Taken')).not.toBeInTheDocument());
    expect(server.calls).toEqual([{ filmId: 2, kind: 'forced', body: { choice: 'NOT_NEEDED' } }]);
    expect(server.puts()[0][0]).toBe('/api/admin/films/2/forced-need');
    expect(screen.getByRole('tab', { name: 'Forced subtitles not found 0' })).toBeInTheDocument();
    expect(
      screen.getByText('Nothing here. No English film is waiting for forced subtitles.'),
    ).toBeInTheDocument();

    first.unmount();
    renderApp('/admin/subtitles');
    await userEvent.click(await tab('Forced subtitles not found 0'));
    expect(screen.queryByText('Taken')).not.toBeInTheDocument();
  });

  it('keeps the row and says so inline when the save fails, and the choice can be tried again', async () => {
    let failing = true;
    const server = serve({ fail: () => (failing ? respond({ error: 'boom' }, 500) : null) });
    renderApp('/admin/subtitles');
    await tab('Foreign-language films 2');
    await screen.findByText('Downfall');

    await userEvent.click(within(rowOf('Downfall')).getByRole('button', { name: 'It’s English' }));

    expect(await within(rowOf('Downfall')).findByRole('alert')).toHaveTextContent(
      "Couldn't save: try again",
    );
    expect(screen.getByRole('tab', { name: 'Foreign-language films 2' })).toBeInTheDocument();
    const retry = within(rowOf('Downfall')).getByRole('button', { name: 'It’s English' });
    expect(retry).not.toHaveAttribute('aria-disabled');

    failing = false;
    await userEvent.click(retry);
    await waitFor(() => expect(screen.queryByText('Downfall')).not.toBeInTheDocument());
    expect(server.calls).toHaveLength(2);
  });

  it('says a scan is running when the server refuses the choice with 409', async () => {
    serve({ fail: () => respond(null, 409) });
    renderApp('/admin/subtitles');
    await userEvent.click(await tab('Forced subtitles not found 1'));

    await userEvent.click(screen.getByRole('button', { name: 'No foreign scenes' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'A scan is running: try again when it finishes',
    );
    expect(screen.getByText('Taken')).toBeInTheDocument();
  });

  it('ignores a second click while the first is in flight', async () => {
    let release;
    const held = new Promise((resolve) => {
      release = resolve;
    });
    const server = serve({ gate: () => held });
    renderApp('/admin/subtitles');
    await tab('Foreign-language films 2');
    await screen.findByText('Downfall');
    const button = within(rowOf('Downfall')).getByRole('button', { name: 'It’s English' });

    await userEvent.click(button);
    await waitFor(() => expect(button).toHaveAttribute('aria-disabled', 'true'));
    await userEvent.click(button);
    await userEvent.click(button);
    expect(server.calls).toHaveLength(1);

    await act(async () => release());
    await waitFor(() => expect(screen.queryByText('Downfall')).not.toBeInTheDocument());
    expect(server.calls).toHaveLength(1);
  });

  it('moves focus to the panel when the row that had it leaves', async () => {
    serve();
    renderApp('/admin/subtitles');
    await tab('Foreign-language films 2');
    await screen.findByText('Downfall');

    await userEvent.click(within(rowOf('Downfall')).getByRole('button', { name: 'It’s English' }));

    await waitFor(() => expect(screen.queryByText('Downfall')).not.toBeInTheDocument());
    await waitFor(() =>
      expect(screen.getByRole('tabpanel', { name: 'Foreign-language films 1' })).toHaveFocus(),
    );
  });

  it('leaves focus alone when it had already moved somewhere real', async () => {
    let release;
    const held = new Promise((resolve) => {
      release = resolve;
    });
    serve({ gate: () => held });
    renderApp('/admin/subtitles');
    await tab('Foreign-language films 2');
    await screen.findByText('Downfall');
    await userEvent.click(within(rowOf('Downfall')).getByRole('button', { name: 'It’s English' }));
    await userEvent.click(screen.getByRole('tab', { name: 'Forced subtitles not found 1' }));
    const other = screen.getByRole('tab', { name: 'Forced subtitles not found 1' });

    await act(async () => release());
    await waitFor(() =>
      expect(screen.getByRole('tab', { name: 'Foreign-language films 1' })).toBeInTheDocument(),
    );

    expect(other).toHaveFocus();
  });
});

describe('Admin Subtitles section: Add my own file…', () => {
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

  /** The need lists plus the attach flow: attaching the file settles Rashomon, as the server would. */
  function serveAttach() {
    const needs = serveNeeds({ full: [rashomon(), downfall()], forced: [taken()] });
    const attaches = [];
    let fileReads = 0;
    let attached = false;
    installFetch((method, url, init) => {
      if (url === '/api/admin/subtitles') {
        fileReads += 1;
        return respond([
          attached
            ? {
                ...ownFile,
                state: 'matched',
                ownerAttached: true,
                filmId: 6,
                filmTitle: 'Rashomon',
              }
            : ownFile,
        ]);
      }
      if (url === '/api/films/6') {
        return respond({
          id: 6,
          title: 'Rashomon',
          editions: [{ id: 60, name: 'Original', parts: 1 }],
        });
      }
      if (url === '/api/admin/subtitles/31/attach' && method === 'POST') {
        attaches.push(JSON.parse(init.body));
        attached = true;
        needs.lists.full[0] = {
          ...needs.lists.full[0],
          unresolved: false,
          fullSubtitle: { source: 'SIDECAR' },
        };
        return respond(ownFile);
      }
      return needs.route(method, url, init);
    });
    return { attaches, reads: () => fileReads };
  }

  it('re-reads an open file list after an attach, so it shows the new attachment', async () => {
    const { reads } = serveAttach();
    renderApp('/admin/subtitles');
    await userEvent.click(await screen.findByRole('button', { name: 'Show all subtitle files' }));
    const files = await screen.findByRole('region', { name: 'Subtitle files' });
    expect(await within(files).findByText('Not attached to a film')).toBeInTheDocument();
    const before = reads();
    await userEvent.click(
      within(rowOf('Rashomon')).getByRole('button', { name: 'Add my own file…' }),
    );
    await userEvent.click(await screen.findByRole('radio', { name: /Rashomon\.en\.srt/ }));

    await userEvent.click(screen.getByRole('button', { name: 'Attach' }));

    await waitFor(() => expect(reads()).toBeGreaterThan(before));
    expect(await within(files).findByText('Rashomon · Theatrical Cut')).toBeInTheDocument();
    expect(within(files).queryByText('Not attached to a film')).not.toBeInTheDocument();
  });

  it('attaches the chosen file to the film, the row leaves, focus goes to the panel, and it stays gone', async () => {
    const { attaches } = serveAttach();
    const first = renderApp('/admin/subtitles');
    await tab('Foreign-language films 2');
    await screen.findByText('Rashomon');
    await userEvent.click(
      within(rowOf('Rashomon')).getByRole('button', { name: 'Add my own file…' }),
    );
    await userEvent.click(await screen.findByRole('radio', { name: /Rashomon\.en\.srt/ }));

    await userEvent.click(screen.getByRole('button', { name: 'Attach' }));

    await waitFor(() => expect(screen.queryByText('Rashomon')).not.toBeInTheDocument());
    expect(attaches).toEqual([{ editionId: 60, part: null }]);
    expect(screen.getByRole('tab', { name: 'Foreign-language films 1' })).toBeInTheDocument();
    // The focus move is a passive effect of the render that drops the row: it can land after the row is gone.
    await waitFor(() => expect(screen.getByRole('tabpanel')).toHaveFocus());

    first.unmount();
    renderApp('/admin/subtitles');
    await tab('Foreign-language films 1');
    expect(screen.queryByText('Rashomon')).not.toBeInTheDocument();
  });

  it('keeps the row, and the form open with the server’s words, when attaching is refused', async () => {
    const needs = serveNeeds({ full: [rashomon()], forced: [] });
    installFetch((method, url, init) => {
      if (url === '/api/admin/subtitles') return respond([ownFile]);
      if (url === '/api/films/6') {
        return respond({ id: 6, editions: [{ id: 60, name: 'Original', parts: 1 }] });
      }
      if (url === '/api/admin/subtitles/31/attach') {
        return respond({ error: 'That edition has no such part' }, 400);
      }
      return needs.route(method, url, init);
    });
    renderApp('/admin/subtitles');
    await screen.findByText('Rashomon');
    await userEvent.click(
      within(rowOf('Rashomon')).getByRole('button', { name: 'Add my own file…' }),
    );
    await userEvent.click(await screen.findByRole('radio', { name: /Rashomon\.en\.srt/ }));

    await userEvent.click(screen.getByRole('button', { name: 'Attach' }));

    expect(await screen.findByText('That edition has no such part')).toBeInTheDocument();
    expect(screen.getByText('Rashomon')).toBeInTheDocument();
  });
});

describe('Admin Subtitles section: Your choices', () => {
  it('gives a choice back with null, the film leaves Your choices, the page reads again, and it stays gone', async () => {
    const server = serve();
    const first = renderApp('/admin/subtitles');
    // Rashomon (owner: needed) and the forced list's film are not in this fake with a choice: one choice only.
    await userEvent.click(await screen.findByRole('button', { name: 'Your choices (1)' }));
    const summaryBefore = server.summaryReads();
    const fullReadsBefore = server.reads.full;

    await userEvent.click(
      screen.getByRole('button', { name: 'Let CineFilm decide: Rashomon (1950), full subtitles' }),
    );

    await waitFor(() =>
      expect(screen.queryByRole('button', { name: /^Your choices/ })).not.toBeInTheDocument(),
    );
    expect(server.calls).toEqual([{ filmId: 6, kind: 'full', body: { choice: null } }]);
    expect(server.puts()[0][0]).toBe('/api/admin/films/6/full-need');
    await waitFor(() => expect(server.summaryReads()).toBeGreaterThan(summaryBefore));
    expect(server.reads.full).toBeGreaterThan(fullReadsBefore);
    await waitFor(() =>
      expect(screen.getByRole('heading', { name: 'Everything else' })).toHaveFocus(),
    );

    first.unmount();
    renderApp('/admin/subtitles');
    await screen.findByRole('heading', { name: 'Everything else' });
    await screen.findByText('Rashomon');
    expect(screen.queryByRole('button', { name: /^Your choices/ })).not.toBeInTheDocument();
  });

  it('keeps the choice and says so on its row when giving it back fails', async () => {
    serve({ fail: () => respond({ error: 'boom' }, 500) });
    renderApp('/admin/subtitles');
    await userEvent.click(await screen.findByRole('button', { name: 'Your choices (1)' }));

    await userEvent.click(
      screen.getByRole('button', { name: 'Let CineFilm decide: Rashomon (1950), full subtitles' }),
    );

    const choices = screen.getByRole('list', { name: 'Your choices' });
    expect(await within(choices).findByRole('alert')).toHaveTextContent("Couldn't save: try again");
    expect(screen.getByRole('button', { name: 'Your choices (1)' })).toBeInTheDocument();
  });

  it('lists a choice made on the page, so "It’s English" can be undone from here', async () => {
    const server = serve();
    renderApp('/admin/subtitles');
    await tab('Foreign-language films 2');
    await screen.findByText('Downfall');
    await userEvent.click(within(rowOf('Downfall')).getByRole('button', { name: 'It’s English' }));

    await userEvent.click(await screen.findByRole('button', { name: 'Your choices (2)' }));
    await userEvent.click(
      screen.getByRole('button', { name: 'Let CineFilm decide: Downfall (2004), full subtitles' }),
    );

    await waitFor(() => expect(server.calls).toHaveLength(2));
    expect(server.calls[1]).toEqual({ filmId: 8, kind: 'full', body: { choice: null } });
    await screen.findByRole('button', { name: 'Your choices (1)' });
  });
});

describe('Admin Subtitles section: tabs by keyboard', () => {
  it('moves between the tabs with the arrows, Home and End, and keeps one tab in the tab order', async () => {
    serve();
    renderApp('/admin/subtitles');
    const foreign = await tab('Foreign-language films 2');
    const forced = screen.getByRole('tab', { name: 'Forced subtitles not found 1' });
    expect(foreign).toHaveAttribute('tabindex', '0');
    expect(forced).toHaveAttribute('tabindex', '-1');

    foreign.focus();
    await userEvent.keyboard('{ArrowRight}');
    expect(forced).toHaveFocus();
    expect(forced).toHaveAttribute('aria-selected', 'true');
    expect(forced).toHaveAttribute('tabindex', '0');
    expect(foreign).toHaveAttribute('tabindex', '-1');
    expect(screen.getByRole('tabpanel')).toHaveAccessibleName('Forced subtitles not found 1');

    await userEvent.keyboard('{ArrowRight}');
    expect(foreign).toHaveFocus();
    await userEvent.keyboard('{End}');
    expect(forced).toHaveFocus();
    await userEvent.keyboard('{Home}');
    expect(foreign).toHaveFocus();
    await userEvent.keyboard('{ArrowLeft}');
    expect(forced).toHaveFocus();
  });
});

describe('Admin Subtitles section: the tab lives in the address', () => {
  it('opens on the foreign tab with no hash, and on the forced tab for #forced', async () => {
    serve();
    const { unmount } = renderApp('/admin/subtitles');
    expect(await tab('Foreign-language films 2')).toHaveAttribute('aria-selected', 'true');
    unmount();

    renderApp('/admin/subtitles#forced');
    expect(await tab('Forced subtitles not found 1')).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tabpanel')).toHaveAccessibleName('Forced subtitles not found 1');
  });

  it('opens on the foreign tab for #foreign and for a hash it does not know', async () => {
    serve();
    const { unmount } = renderApp('/admin/subtitles#foreign');
    expect(await tab('Foreign-language films 2')).toHaveAttribute('aria-selected', 'true');
    unmount();

    renderApp('/admin/subtitles#nonsense');
    expect(await tab('Foreign-language films 2')).toHaveAttribute('aria-selected', 'true');
  });

  it('writes the chosen tab into the address, by click and by arrow key', async () => {
    serve();
    renderApp('/admin/subtitles', { showLocation: true });
    const foreign = await tab('Foreign-language films 2');
    const forced = screen.getByRole('tab', { name: 'Forced subtitles not found 1' });

    await userEvent.click(forced);
    expect(screen.getByTestId('location')).toHaveTextContent('/admin/subtitles#forced');
    expect(forced).toHaveAttribute('aria-selected', 'true');

    foreign.focus();
    await userEvent.keyboard('{ArrowLeft}');
    expect(screen.getByTestId('location')).toHaveTextContent('/admin/subtitles#foreign');
    expect(foreign).toHaveFocus();
    expect(foreign).toHaveAttribute('aria-selected', 'true');
  });
});

describe('Admin Subtitles section: the address keeps its query', () => {
  it('keeps the query string when a tab change writes the hash', async () => {
    serve();
    renderApp('/admin/subtitles?from=todo', { showLocation: true });
    await userEvent.click(await tab('Forced subtitles not found 1'));

    expect(screen.getByTestId('location')).toHaveTextContent('/admin/subtitles?from=todo#forced');
  });
});

describe('Admin Subtitles section: when a list fails', () => {
  it('shows a banner in the failed tab while the other tab and Everything else render', async () => {
    const server = serveNeeds({ full: [rashomon()], forced: [taken()] });
    installFetch((method, url, init) =>
      url === FULL ? respond(null, 500) : server.route(method, url, init),
    );
    renderApp('/admin/subtitles');

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Couldn’t load the foreign-language films.');
    expect(screen.getByRole('tab', { name: 'Foreign-language films' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('tab', { name: 'Forced subtitles not found 1' }));
    expect(within(screen.getByRole('tabpanel')).getByText('Taken')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Everything else' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Show all subtitle files' })).toBeInTheDocument();
  });

  it('retries the failed list from its banner', async () => {
    let failing = true;
    const server = serveNeeds({ full: [rashomon()], forced: [] });
    installFetch((method, url, init) =>
      url === FULL && failing ? respond(null, 500) : server.route(method, url, init),
    );
    renderApp('/admin/subtitles');
    const banner = await screen.findByRole('alert');

    failing = false;
    await userEvent.click(within(banner).getByRole('button', { name: 'Try again' }));

    expect(await screen.findByText('Rashomon')).toBeInTheDocument();
  });

  it('shows a static skeleton per list while it loads, and no count on its tab', async () => {
    installFetch(() => new Promise(() => {}));
    renderApp('/admin/subtitles');

    expect(
      screen.getByRole('status', { name: 'Loading the foreign-language films' }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('status', { name: 'Loading the forced subtitles to find', hidden: true }),
    ).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Foreign-language films' })).toBeInTheDocument();
  });
});

describe('Admin Subtitles section: Everything else', () => {
  it('shows the read-only list of the foreign films that have English subtitles', async () => {
    serve();
    renderApp('/admin/subtitles');

    await userEvent.click(await screen.findByRole('button', { name: 'Show the 1 film' }));

    const list = screen.getByRole('list', { name: 'Foreign films with English subtitles' });
    expect(within(list).getByText('Das Boot (1981)')).toBeInTheDocument();
    expect(within(list).getByText('· Playing: your file')).toBeInTheDocument();
    expect(within(list).queryByRole('button')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Hide the 1 film' }));
    expect(
      screen.queryByRole('list', { name: 'Foreign films with English subtitles' }),
    ).not.toBeInTheDocument();
  });

  it('counts and lists the same rows: a film that plays a file but is not flagged subtitled is in neither', async () => {
    const hidden = fullRow({
      filmId: 12,
      title: 'Hidden Film',
      year: 1999,
      unresolved: false,
      fullSubtitle: { source: 'SIDECAR' },
      subtitled: false,
    });
    serve({ full: [rashomon(), dasBoot(), hidden] });
    renderApp('/admin/subtitles');

    await userEvent.click(await screen.findByRole('button', { name: 'Show the 1 film' }));

    const list = screen.getByRole('list', { name: 'Foreign films with English subtitles' });
    expect(within(list).getAllByRole('listitem')).toHaveLength(1);
    expect(within(list).getByText('Das Boot (1981)')).toBeInTheDocument();
    expect(within(list).queryByText(/Hidden Film/)).not.toBeInTheDocument();
  });

  it('keeps the read-only file list behind "Show all subtitle files"', async () => {
    serve();
    renderApp('/admin/subtitles');

    const toggle = await screen.findByRole('button', { name: 'Show all subtitle files' });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('region', { name: 'Subtitle files' })).not.toBeInTheDocument();
    await userEvent.click(toggle);

    const files = await screen.findByRole('region', { name: 'Subtitle files' });
    expect(await within(files).findByText('Heat (1995)/Heat.en.forced.srt')).toBeInTheDocument();
    expect(
      within(files).queryByRole('button', { name: /cues|Looks right/ }),
    ).not.toBeInTheDocument();
    expect(toggle).toHaveTextContent('Hide the subtitle files');
    await userEvent.click(toggle);
    expect(screen.queryByRole('region', { name: 'Subtitle files' })).not.toBeInTheDocument();
  });

  it('shows the OpenSubtitles search status here', async () => {
    serve();
    renderApp('/admin/subtitles');

    const status = await screen.findByText('Subtitles searched 12 Nov 06:00');

    expect(status).toHaveAttribute('role', 'status');
  });

  it('no longer shows the search status on the Library scan card', async () => {
    installFetch();
    renderApp('/admin/library');

    await screen.findByRole('button', { name: 'Scan library now' });

    expect(fixtures.adminScan.openSubtitlesEnabled).toBe(true);
    expect(screen.queryByText(/Subtitles searched/)).not.toBeInTheDocument();
  });
});
