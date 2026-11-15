import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp, respond } from '../../test/mockApi.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
});

const ATTENTION = '/api/admin/attention';
const FULL = '/api/admin/subtitles/full-need';
const FORCED = '/api/admin/forced-subtitles/films';
const SUSPIRIA_MATCH = '/api/admin/films/34/match';
const rail = () => screen.getByRole('navigation', { name: 'Admin sections' });
const group = (name) => screen.findByRole('region', { name });
const rowOf = (region, title) =>
  within(region)
    .getAllByRole('listitem')
    .find((row) => within(row).queryByText(title));

/** Routes the three lists to the "many" fixtures: more than five rows in every group. */
const many = (m, url) => {
  if (url === ATTENTION) return respond(fixtures.adminAttentionMany);
  if (url === FULL) return respond(fixtures.adminFullNeedMany);
  if (url === FORCED) return respond(fixtures.adminForcedNeedMany);
  return null;
};

describe('Admin To do groups', () => {
  it('shows the four groups in order, each with its why-line, and none of the excluded kinds', async () => {
    installFetch();
    renderApp('/admin');

    await group('Pick the right film');
    await group('English subtitles missing');
    expect(screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)).toEqual([
      'Pick the right film',
      'Sort out several files',
      'English subtitles missing',
      'Forced subtitles not found',
    ]);
    expect(
      screen.getByText('CineFilm couldn’t tell which film these files are'),
    ).toBeInTheDocument();
    expect(
      screen.getByText('One film has more than one file and CineFilm can’t tell how they fit'),
    ).toBeInTheDocument();
    // Protected, needs-conversion and unreadable kinds are Library counts, not To do rows.
    expect(screen.queryByText('Kes (1969)')).not.toBeInTheDocument();
    expect(screen.queryByText('Alien (1979)')).not.toBeInTheDocument();
  });

  it('words each row from the server: best guess without a percentage, suggestion, spoken audio, reason', async () => {
    installFetch();
    renderApp('/admin');

    const pick = await group('Pick the right film');
    expect(
      within(pick).getByText('The Wicker Man.m4v · best guess: The Wicker Man (1973)'),
    ).toBeInTheDocument();
    expect(
      within(pick).getByText('Suspiria.m4v · best guess: Suspiria (1977)'),
    ).toBeInTheDocument();
    expect(within(pick).queryByText(/%/)).not.toBeInTheDocument();
    const several = await group('Sort out several files');
    expect(
      within(several).getByText(
        '2 files · 2 untagged versions of one film, runtimes differ by 22%',
      ),
    ).toBeInTheDocument();
    expect(
      within(several).getByText('2 files · Part 2: video codec is h264, part 1 has hevc'),
    ).toBeInTheDocument();
    const english = await group('English subtitles missing');
    expect(within(english).getByText('Rashomon')).toBeInTheDocument();
    expect(within(english).getByText('· 1950')).toBeInTheDocument();
    expect(
      within(english).getByText('Spoken: Japanese · none found automatically on OpenSubtitles'),
    ).toBeInTheDocument();
    const forced = await group('Forced subtitles not found');
    expect(within(forced).getByText('Taken')).toBeInTheDocument();
    expect(
      within(forced).getByText('OpenSubtitles has 2 foreign-parts-only files'),
    ).toBeInTheDocument();
  });

  it('offers "Yes, it’s …" only where the server is confident, and "Find the film…" on every match row', async () => {
    installFetch();
    renderApp('/admin');

    const pick = await group('Pick the right film');
    const wicker = rowOf(pick, 'The Wicker Man');
    const suspiria = rowOf(pick, 'Suspiria');
    expect(within(wicker).queryByRole('button', { name: /^Yes/ })).not.toBeInTheDocument();
    expect(within(wicker).getByRole('button', { name: 'Find the film…' })).toBeInTheDocument();
    const yes = within(suspiria).getByRole('button', { name: 'Yes, it’s Suspiria (1977)' });
    expect(yes).toHaveAccessibleDescription(
      'Confirms the guess and fetches its poster and details',
    );
    expect(within(suspiria).getByRole('button', { name: 'Find the film…' })).toBeInTheDocument();
  });

  it('agrees with the status counts: as many rows as the summary says things need you', async () => {
    installFetch();
    renderApp('/admin');

    await within(rail()).findByRole('link', { name: 'To do, 6 things' });
    await group('Forced subtitles not found');
    const rows = screen
      .getAllByRole('region')
      .flatMap((region) => within(region).queryAllByRole('listitem'));
    // The strip's three cards are a list too, but not inside a group region.
    expect(rows).toHaveLength(6);
  });

  it('hides groups with no rows', async () => {
    installFetch((m, url) => {
      if (url === ATTENTION) return respond({ items: [] });
      if (url === FORCED) return respond({ ...fixtures.adminForcedNeed, films: [] });
      return null;
    });
    renderApp('/admin');

    await group('English subtitles missing');
    expect(screen.queryByRole('region', { name: 'Pick the right film' })).not.toBeInTheDocument();
    expect(
      screen.queryByRole('region', { name: 'Sort out several files' }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole('region', { name: 'Forced subtitles not found' }),
    ).not.toBeInTheDocument();
    expect(screen.queryByText('Nothing to do.')).not.toBeInTheDocument();
  });

  it('says "Nothing to do." under the strip when every group is empty', async () => {
    installFetch((m, url) => {
      if (url === ATTENTION) return respond({ items: [] });
      if (url === FULL) return respond({ ...fixtures.adminFullNeed, films: [] });
      if (url === FORCED) return respond({ ...fixtures.adminForcedNeed, films: [] });
      return null;
    });
    renderApp('/admin');

    expect(await screen.findByText('Nothing to do.')).toBeInTheDocument();
    expect(await screen.findByRole('list', { name: 'Status' })).toBeInTheDocument();
    expect(screen.queryAllByRole('region')).toEqual([]);
  });

  it('does not say "Nothing to do." while a list is still loading or has failed', async () => {
    installFetch((m, url) => {
      if (url === ATTENTION) return respond({ items: [] });
      if (url === FULL) return respond({ ...fixtures.adminFullNeed, films: [] });
      if (url === FORCED) return respond(null, 500);
      return null;
    });
    renderApp('/admin');

    await screen.findByText('Couldn’t load the films with forced subtitles to find.');
    expect(screen.queryByText('Nothing to do.')).not.toBeInTheDocument();
  });
});

describe('Admin To do loading', () => {
  it('shows a static skeleton per source while the lists load', () => {
    installFetch((m, url) =>
      [ATTENTION, FULL, FORCED].includes(url) ? new Promise(() => {}) : null,
    );
    renderApp('/admin');

    expect(
      screen.getByRole('status', { name: 'Loading the films that need sorting out' }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('status', { name: 'Loading the films missing English subtitles' }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('status', { name: 'Loading the forced subtitles to find' }),
    ).toBeInTheDocument();
    expect(screen.queryByText('Nothing to do.')).not.toBeInTheDocument();
  });

  it('shows one list failing as its own banner while the others render, and Try again reads only it', async () => {
    let fail = true;
    const fetchMock = installFetch((m, url) => (url === FULL && fail ? respond(null, 500) : null));
    renderApp('/admin');

    const banner = await screen.findByRole('alert');
    expect(banner).toHaveTextContent('Couldn’t load the films missing English subtitles.');
    expect(await group('Pick the right film')).toBeInTheDocument();
    expect(await group('Forced subtitles not found')).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'English subtitles missing' })).toBeNull();
    const attentionReads = () => fetchMock.mock.calls.filter(([url]) => url === ATTENTION).length;
    const before = attentionReads();
    fail = false;

    await userEvent.click(within(banner).getByRole('button', { name: 'Try again' }));

    expect(await group('English subtitles missing')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(attentionReads()).toBe(before);
  });

  it('shows the attention list failing as one banner for both film groups', async () => {
    installFetch((m, url) => (url === ATTENTION ? respond(null, 500) : null));
    renderApp('/admin');

    expect(
      await screen.findByText('Couldn’t load the films that need sorting out.'),
    ).toBeInTheDocument();
    expect(await group('English subtitles missing')).toBeInTheDocument();
    expect(screen.getAllByRole('alert')).toHaveLength(1);
  });
});

describe('Admin To do: Yes, it’s …', () => {
  /** A summary that drops pickFilm by one on every read after the first, as the server would after a match. */
  function summaryAfterMatch() {
    let reads = 0;
    return (m, url) => {
      if (url !== '/api/admin/summary') return null;
      reads += 1;
      const todo = { ...fixtures.adminSummary.todo };
      if (reads > 1) todo.pickFilm -= 1;
      return respond({ ...fixtures.adminSummary, todo });
    };
  }

  it('posts the top candidate, the row leaves and the rail count drops after the re-read', async () => {
    let matched = false;
    const summary = summaryAfterMatch();
    const fetchMock = installFetch((m, url, init) => {
      if (m === 'POST' && url === SUSPIRIA_MATCH) {
        matched = true;
        return respond({ filmId: 34, joined: false });
      }
      if (url === ATTENTION && matched) {
        return respond({ items: fixtures.adminAttention.items.filter((i) => i.filmId !== 34) });
      }
      return summary(m, url, init);
    });
    renderApp('/admin');
    await within(rail()).findByRole('link', { name: 'To do, 6 things' });
    const pick = await group('Pick the right film');

    await userEvent.click(
      within(rowOf(pick, 'Suspiria')).getByRole('button', { name: /^Yes, it’s/ }),
    );

    await waitFor(() => expect(within(pick).queryByText('Suspiria')).not.toBeInTheDocument());
    expect(
      await within(rail()).findByRole('link', { name: 'To do, 5 things' }),
    ).toBeInTheDocument();
    const post = fetchMock.mock.calls.find(
      ([url, init]) => url === SUSPIRIA_MATCH && init?.method === 'POST',
    );
    expect(JSON.parse(post[1].body)).toEqual({ tmdbId: 37557 });
    expect(screen.getByText('5 things need you')).toBeInTheDocument();
    // The lists are read again, not patched in place.
    expect(fetchMock.mock.calls.filter(([url]) => url === ATTENTION).length).toBeGreaterThan(1);
  });

  it('puts focus on the heading once the row that held it has gone', async () => {
    let matched = false;
    installFetch((m, url) => {
      if (m === 'POST' && url === SUSPIRIA_MATCH) {
        matched = true;
        return respond({ filmId: 34, joined: false });
      }
      if (url === ATTENTION && matched) {
        return respond({ items: fixtures.adminAttention.items.filter((i) => i.filmId !== 34) });
      }
      return null;
    });
    renderApp('/admin');
    const pick = await group('Pick the right film');
    await userEvent.click(
      within(rowOf(pick, 'Suspiria')).getByRole('button', { name: /^Yes, it’s/ }),
    );

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1, name: 'To do' })).toHaveFocus(),
    );
  });

  it('does nothing on a second click while the post is in flight', async () => {
    let release;
    let matched = false;
    const fetchMock = installFetch((m, url) => {
      if (m === 'POST' && url === SUSPIRIA_MATCH) {
        return new Promise((resolve) => {
          release = () => {
            matched = true;
            resolve(respond({ filmId: 34, joined: false }));
          };
        });
      }
      if (url === ATTENTION && matched) {
        return respond({ items: fixtures.adminAttention.items.filter((i) => i.filmId !== 34) });
      }
      return null;
    });
    renderApp('/admin');
    const pick = await group('Pick the right film');
    const yes = within(rowOf(pick, 'Suspiria')).getByRole('button', { name: /^Yes, it’s/ });

    await userEvent.click(yes);
    await userEvent.click(yes);

    expect(yes).toHaveAttribute('aria-disabled', 'true');
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1);
    release();
    await waitFor(() => expect(within(pick).queryByText('Suspiria')).not.toBeInTheDocument());
  });

  it('shows an inline error and keeps the row when the post fails, and the next click tries again', async () => {
    let fail = true;
    let matched = false;
    const fetchMock = installFetch((m, url) => {
      if (m === 'POST' && url === SUSPIRIA_MATCH) {
        if (fail) return respond({ error: 'boom' }, 500);
        matched = true;
        return respond({ filmId: 34, joined: false });
      }
      // Once matched the server no longer lists the film, so the re-read agrees with the hiding.
      if (url === ATTENTION && matched) {
        return respond({ items: fixtures.adminAttention.items.filter((i) => i.filmId !== 34) });
      }
      return null;
    });
    renderApp('/admin');
    const pick = await group('Pick the right film');
    const row = rowOf(pick, 'Suspiria');
    const yes = within(row).getByRole('button', { name: /^Yes, it’s/ });

    await userEvent.click(yes);

    expect(await within(row).findByRole('alert')).toHaveTextContent(
      'Something went wrong. Try again.',
    );
    expect(within(pick).getByText('Suspiria')).toBeInTheDocument();
    expect(yes).not.toHaveAttribute('aria-disabled');
    // A failed post changes nothing, so nothing is read again.
    expect(fetchMock.mock.calls.filter(([url]) => url === '/api/admin/summary')).toHaveLength(1);
    fail = false;

    await userEvent.click(yes);

    await waitFor(() => expect(within(pick).queryByText('Suspiria')).not.toBeInTheDocument());
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('says a scan is running when the post answers 409', async () => {
    installFetch((m, url) => (m === 'POST' && url === SUSPIRIA_MATCH ? respond({}, 409) : null));
    renderApp('/admin');
    const pick = await group('Pick the right film');

    await userEvent.click(
      within(rowOf(pick, 'Suspiria')).getByRole('button', { name: /^Yes, it’s/ }),
    );

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'A scan is running. Try again when it finishes.',
    );
  });
});

describe('Admin To do: Yes, it’s … on the row', () => {
  it('moves focus straight to the section title when the row is hidden, without waiting for a list read', async () => {
    let matched = false;
    installFetch((m, url) => {
      if (m === 'POST' && url === SUSPIRIA_MATCH) {
        matched = true;
        return respond({ filmId: 34, joined: false });
      }
      // The re-read never answers: nothing but the hiding can have moved focus.
      return url === ATTENTION && matched ? new Promise(() => {}) : null;
    });
    renderApp('/admin');
    const pick = await group('Pick the right film');
    const yes = within(rowOf(pick, 'Suspiria')).getByRole('button', { name: /^Yes, it’s/ });

    await userEvent.click(yes);

    await waitFor(() => expect(within(pick).queryByText('Suspiria')).not.toBeInTheDocument());
    expect(screen.getByRole('heading', { level: 1, name: 'To do' })).toHaveFocus();
  });

  it('leaves focus alone when it had already moved off the row', async () => {
    let release;
    installFetch((m, url) =>
      m === 'POST' && url === SUSPIRIA_MATCH
        ? new Promise((resolve) => {
            release = () => resolve(respond({ filmId: 34, joined: false }));
          })
        : null,
    );
    renderApp('/admin');
    const pick = await group('Pick the right film');
    await userEvent.click(
      within(rowOf(pick, 'Suspiria')).getByRole('button', { name: /^Yes, it’s/ }),
    );
    const elsewhere = within(rowOf(pick, 'The Wicker Man')).getByRole('button', {
      name: 'Find the film…',
    });
    elsewhere.focus();

    release();

    await waitFor(() => expect(within(pick).queryByText('Suspiria')).not.toBeInTheDocument());
    expect(elsewhere).toHaveFocus();
  });

  it('dims both buttons of the row while its post runs and ignores Find the film… then', async () => {
    let release;
    installFetch((m, url) =>
      m === 'POST' && url === SUSPIRIA_MATCH
        ? new Promise((resolve) => {
            release = () => resolve(respond({ filmId: 34, joined: false }));
          })
        : null,
    );
    renderApp('/admin');
    const pick = await group('Pick the right film');
    const row = rowOf(pick, 'Suspiria');

    await userEvent.click(within(row).getByRole('button', { name: /^Yes, it’s/ }));
    const find = within(row).getByRole('button', { name: 'Find the film…' });
    await userEvent.click(find);

    expect(find).toHaveAttribute('aria-disabled', 'true');
    expect(screen.queryByRole('region', { name: 'Fix match' })).not.toBeInTheDocument();
    release();
    await waitFor(() => expect(within(pick).queryByText('Suspiria')).not.toBeInTheDocument());
  });

  it('says the film has gone for a 404, in the row’s own words, and reads the lists again', async () => {
    const fetchMock = installFetch((m, url) =>
      m === 'POST' && url === SUSPIRIA_MATCH ? respond({ error: 'gone' }, 404) : null,
    );
    renderApp('/admin');
    const pick = await group('Pick the right film');
    const attentionReads = () => fetchMock.mock.calls.filter(([url]) => url === ATTENTION).length;
    const before = attentionReads();

    await userEvent.click(
      within(rowOf(pick, 'Suspiria')).getByRole('button', { name: /^Yes, it’s/ }),
    );

    await waitFor(() => expect(attentionReads()).toBeGreaterThan(before));
    expect(screen.queryByText(/Close this|Type a title/)).not.toBeInTheDocument();
  });

  it('says something went wrong, with no dialog wording, for a 400', async () => {
    installFetch((m, url) =>
      m === 'POST' && url === SUSPIRIA_MATCH ? respond({ error: 'bad' }, 400) : null,
    );
    renderApp('/admin');
    const pick = await group('Pick the right film');

    await userEvent.click(
      within(rowOf(pick, 'Suspiria')).getByRole('button', { name: /^Yes, it’s/ }),
    );

    expect(await screen.findByRole('alert')).toHaveTextContent('Something went wrong. Try again.');
  });

  it('keeps the last list with no banner when a re-read fails after the first answer', async () => {
    let matched = false;
    installFetch((m, url) => {
      if (m === 'POST' && url === SUSPIRIA_MATCH) {
        matched = true;
        return respond({ filmId: 34, joined: false });
      }
      return url === ATTENTION && matched ? respond({ error: 'boom' }, 500) : null;
    });
    renderApp('/admin');
    const pick = await group('Pick the right film');

    await userEvent.click(
      within(rowOf(pick, 'Suspiria')).getByRole('button', { name: /^Yes, it’s/ }),
    );

    await waitFor(() => expect(within(pick).queryByText('Suspiria')).not.toBeInTheDocument());
    await act(async () => {});
    expect(within(pick).getByText('The Wicker Man')).toBeInTheDocument();
    expect(await group('Sort out several files')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});

describe('Admin To do: more than five rows', () => {
  it('caps each group at five, with the footers: Show all for the film groups, All in Subtitles for the others', async () => {
    installFetch(many);
    renderApp('/admin');

    const pick = await group('Pick the right film');
    const several = await group('Sort out several files');
    const english = await group('English subtitles missing');
    const forced = await group('Forced subtitles not found');
    for (const region of [pick, several, english, forced]) {
      expect(within(region).getAllByRole('listitem')).toHaveLength(5);
    }
    expect(within(pick).getByText('Showing 5 of 7')).toBeInTheDocument();
    expect(within(pick).getByRole('button', { name: 'Show all 7' })).toBeInTheDocument();
    expect(within(several).getByText('Showing 5 of 6')).toBeInTheDocument();
    expect(within(several).getByRole('button', { name: 'Show all 6' })).toBeInTheDocument();
    for (const region of [english, forced]) {
      expect(within(region).getByText('Showing 5 of 7')).toBeInTheDocument();
      expect(within(region).getByRole('link', { name: 'All in Subtitles →' })).toHaveAttribute(
        'href',
        '/admin/subtitles',
      );
      expect(within(region).queryByRole('button', { name: /^Show/ })).not.toBeInTheDocument();
    }
  });

  it('shows no footer on a film group of five or fewer, and the Subtitles link on a subtitle group of one', async () => {
    installFetch();
    renderApp('/admin');

    const pick = await group('Pick the right film');
    expect(within(pick).queryByText(/^Showing/)).not.toBeInTheDocument();
    const english = await group('English subtitles missing');
    expect(within(english).getByText('Showing 1 of 1')).toBeInTheDocument();
    expect(within(english).getByRole('link', { name: 'All in Subtitles →' })).toBeInTheDocument();
  });

  it('expands Show all in place, keeps focus on the same button, and Show fewer folds it back', async () => {
    const fetchMock = installFetch(many);
    renderApp('/admin');
    const pick = await group('Pick the right film');
    const reads = () => fetchMock.mock.calls.length;
    const before = reads();

    await userEvent.click(within(pick).getByRole('button', { name: 'Show all 7' }));

    const fewer = within(pick).getByRole('button', { name: 'Show fewer' });
    expect(fewer).toHaveFocus();
    expect(within(pick).getByText('Showing 7 of 7')).toBeInTheDocument();
    expect(within(pick).getByText('Match film 7')).toBeInTheDocument();
    // In place: nothing is requested to show the rest.
    expect(reads()).toBe(before);

    await userEvent.click(fewer);

    expect(within(pick).getByRole('button', { name: 'Show all 7' })).toHaveFocus();
    expect(within(pick).queryByText('Match film 7')).not.toBeInTheDocument();
  });

  it('leaves the other groups alone when one expands', async () => {
    installFetch(many);
    renderApp('/admin');
    const pick = await group('Pick the right film');
    const several = await group('Sort out several files');

    await userEvent.click(within(pick).getByRole('button', { name: 'Show all 7' }));

    expect(within(several).getByText('Showing 5 of 6')).toBeInTheDocument();
  });
});

describe('Admin To do: the dialogs', () => {
  it('opens Fix match from the quiet "Find the film…" beside a confident guess and returns focus to it on Cancel', async () => {
    installFetch();
    renderApp('/admin');
    const pick = await group('Pick the right film');
    const opener = within(rowOf(pick, 'Suspiria')).getByRole('button', {
      name: 'Find the film…',
    });

    await userEvent.click(opener);
    const panel = await screen.findByRole('region', { name: 'Fix match' });
    await userEvent.click(within(panel).getByRole('button', { name: 'Cancel' }));

    expect(screen.queryByRole('region', { name: 'Fix match' })).not.toBeInTheDocument();
    expect(opener).toHaveFocus();
  });

  it('opens Edit editions from a parts-mismatch row and returns focus to its opener on Cancel', async () => {
    installFetch();
    renderApp('/admin');
    const several = await group('Sort out several files');
    const opener = within(rowOf(several, 'The Two Towers')).getByRole('button', {
      name: 'Sort out editions…',
    });

    await userEvent.click(opener);
    await screen.findByRole('dialog', { name: 'The Two Towers · 2002' });
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(opener).toHaveFocus();
  });

  it('closes Fix match when you move to another section', async () => {
    installFetch();
    renderApp('/admin');
    const pick = await group('Pick the right film');
    await userEvent.click(
      within(rowOf(pick, 'The Wicker Man')).getByRole('button', { name: 'Find the film…' }),
    );
    expect(await screen.findByRole('region', { name: 'Fix match' })).toBeInTheDocument();

    await userEvent.click(within(rail()).getByRole('link', { name: 'Library' }));

    expect(await screen.findByRole('heading', { level: 1, name: 'Library' })).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Fix match' })).not.toBeInTheDocument();
  });

  it('closes Edit editions when you move to another section', async () => {
    installFetch();
    renderApp('/admin');
    const several = await group('Sort out several files');
    await userEvent.click(
      within(rowOf(several, 'Blade Runner')).getByRole('button', {
        name: 'Sort out editions…',
      }),
    );
    await screen.findByRole('dialog', { name: 'Blade Runner · 1982' });

    await userEvent.click(within(rail()).getByRole('link', { name: 'Settings' }));

    expect(await screen.findByRole('heading', { level: 1, name: 'Settings' })).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('reads the lists again after a Fix match is applied, and the group follows', async () => {
    let applied = false;
    installFetch((m, url) => {
      if (m === 'POST' && url === '/api/admin/films/31/match') {
        applied = true;
        return respond({ filmId: 31, joined: false });
      }
      if (url === ATTENTION && applied) {
        return respond({ items: fixtures.adminAttention.items.filter((i) => i.filmId !== 31) });
      }
      return null;
    });
    renderApp('/admin');
    const pick = await group('Pick the right film');
    await userEvent.click(
      within(rowOf(pick, 'The Wicker Man')).getByRole('button', { name: 'Find the film…' }),
    );
    const panel = await screen.findByRole('region', { name: 'Fix match' });
    await userEvent.click((await within(panel).findAllByRole('radio'))[0]);

    await userEvent.click(within(panel).getByRole('button', { name: 'Use this match' }));

    await waitFor(() => expect(within(pick).queryByText('The Wicker Man')).not.toBeInTheDocument());
  });
});
