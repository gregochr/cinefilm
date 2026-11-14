import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
});

const forced = (over = {}) => ({ ...fixtures.adminSubtitles[0], ...over });

const full = (over = {}) =>
  forced({
    id: 9,
    path: 'Heat (1995)/Heat.en.srt',
    kind: 'FULL',
    check: null,
    alignment: null,
    servedReason: 'Ready to play as English (full)',
    ...over,
  });

const download = (over = {}) =>
  full({
    id: 20,
    path: 'opensubtitles:11:5012',
    origin: 'OPENSUBTITLES',
    release: 'Heat.1995.1080p.BluRay.x264',
    openSubtitlesFileId: 5012,
    whole: true,
    part: null,
    servedReason: 'Playing as English (full); not checked yet',
    ...over,
  });

// The films the full-need list shows: what each plays is worked out from the files, as the server does.
const FILMS = [
  { filmId: 1, title: 'Heat', year: 1995 },
  { filmId: 2, title: 'Rashomon', year: 1950 },
];

function playing(items, filmId) {
  const file = items.find((i) => i.filmId === filmId && i.kind === 'FULL' && i.served);
  if (!file) return null;
  return { source: file.origin === 'OPENSUBTITLES' ? 'DOWNLOAD' : 'SIDECAR' };
}

// A stateful fake of the subtitle routes that records the actions; a DELETE removes the row.
function serve(items) {
  const state = { items: structuredClone(items), calls: [] };
  const route = (method, url, init) => {
    const path = new URL(url, 'http://test.local').pathname;
    if (path === '/api/admin/subtitles') return respond(state.items);
    if (path === '/api/admin/subtitles/full-need') {
      return respond({
        films: FILMS.map((f) => ({
          ...fixtures.adminFullNeed.films[0],
          ...f,
          fullSubtitle: playing(state.items, f.filmId),
        })),
        counts: {},
      });
    }
    const match = path.match(/^\/api\/admin\/subtitles\/(\d+)(?:\/(cues|offset))?$/);
    if (!match) return null;
    const item = state.items.find((i) => i.id === Number(match[1]));
    if (method === 'DELETE') {
      state.calls.push({ method, id: item.id, action: 'remove' });
      state.items = state.items.filter((i) => i.id !== item.id);
      return respond(null);
    }
    if (match[2] === 'cues') return respond({ offsetMs: item.offsetMs, cues: [] });
    const body = JSON.parse(init.body);
    state.calls.push({ method, id: item.id, action: match[2], body });
    item.offsetMs = body.offsetMs;
    return respond(item);
  };
  return { state, route };
}

/** The row listing `text`: the list items that show it (the lists inside a row never do). */
const rowOf = async (panel, text) => {
  await within(panel).findByText(text);
  return within(panel)
    .getAllByRole('listitem')
    .find((li) => within(li).queryByText(text) !== null);
};

/** A film's row in the full-need list: the list item holding its choice buttons. */
const needRow = async (panel, title) => {
  const name = `Full subtitles for ${title}`;
  await within(panel).findByRole('group', { name });
  return within(panel)
    .getAllByRole('listitem')
    .find((li) => within(li).queryByRole('group', { name }) !== null);
};

const chip = (panel, name) =>
  within(within(panel).getByRole('group', { name: 'Show subtitle files' })).getByRole('button', {
    name,
  });

async function openPanel(items) {
  const served = serve(items);
  installFetch(served.route);
  renderApp('/admin/subtitles');
  const panel = await screen.findByRole('region', { name: 'Subtitles' });
  return { ...served, panel };
}

describe('Admin: Subtitles panel, full subtitle files', () => {
  it('lists a full file with a Full label, the server’s sentence and a Playing tag when it plays', async () => {
    const { panel } = await openPanel([full()]);

    const item = (await within(panel).findByText('Heat (1995)/Heat.en.srt')).closest('li');
    expect(within(item).getByText('Full')).toBeInTheDocument();
    expect(within(item).getByText('Ready to play as English (full)')).toBeInTheDocument();
    expect(within(item).getByText('Playing')).toBeInTheDocument();
    expect(within(item).queryByText('Unchecked')).not.toBeInTheDocument();
    expect(within(item).queryByText('Checked')).not.toBeInTheDocument();
  });

  it('labels only the full file and leaves the forced one as it was', async () => {
    const { panel } = await openPanel([forced({ id: 1 }), full()]);

    const row = (await within(panel).findByText('Heat (1995)/Heat.en.forced.srt')).closest('li');
    expect(within(row).queryByText('Full')).not.toBeInTheDocument();
    expect(within(row).getByText('Playing')).toBeInTheDocument();
  });

  it('does not count a full file as needing a check, as unchecked or as a download', async () => {
    const { panel } = await openPanel([forced({ id: 1 }), full()]);

    expect(await within(panel).findByRole('button', { name: 'All (2)' })).toBeInTheDocument();
    expect(within(panel).getByRole('button', { name: 'Needs checking (0)' })).toBeInTheDocument();
    expect(within(panel).getByRole('button', { name: 'Unchecked (0)' })).toBeInTheDocument();
    expect(within(panel).getByRole('button', { name: 'Downloads (0)' })).toBeInTheDocument();
  });

  it('can be moved and given back to matching, and nudged, but has no "Looks right"', async () => {
    const { panel, state } = await openPanel([full({ ownerAttached: true })]);
    const item = (await within(panel).findByText('Heat (1995)/Heat.en.srt')).closest('li');

    expect(within(item).getByRole('button', { name: /Move Heat.en.srt/ })).toBeInTheDocument();
    expect(
      within(item).getByRole('button', { name: /Give Heat.en.srt back to automatic matching/ }),
    ).toBeInTheDocument();
    await userEvent.click(within(item).getByRole('button', { name: /Show the cues of/ }));
    await userEvent.click(within(item).getByRole('button', { name: 'Show lines 1 second later' }));

    await waitFor(() =>
      expect(state.calls).toContainEqual(expect.objectContaining({ action: 'offset' })),
    );
    expect(within(item).queryByRole('button', { name: 'Looks right' })).not.toBeInTheDocument();
  });
});

describe('Admin: Subtitles panel, full downloads', () => {
  it('shows a playing download with its origin, the Playing tag and why, and no check or Looks right', async () => {
    const { panel } = await openPanel([download()]);

    const row = await rowOf(panel, 'Downloaded from OpenSubtitles');
    expect(within(row).getByText('Heat.1995.1080p.BluRay.x264')).toBeVisible();
    expect(within(row).getByText('Full')).toBeVisible();
    expect(within(row).getByText('Playing')).toBeVisible();
    expect(within(row).getByText('Playing as English (full); not checked yet')).toBeVisible();
    expect(within(row).queryByText('Unchecked')).not.toBeInTheDocument();
    expect(within(row).queryByRole('button', { name: /Looks right/ })).not.toBeInTheDocument();
    expect(within(row).queryByRole('button', { name: /Use my file/ })).not.toBeInTheDocument();
  });

  it('says a download beaten by your own full file is not used, with no Playing tag', async () => {
    const { panel } = await openPanel([
      full(),
      download({
        served: false,
        servedReason: 'Not used: your own full file plays instead',
      }),
    ]);

    const row = await rowOf(panel, 'Downloaded from OpenSubtitles');
    expect(within(row).getByText('Not used: your own full file plays instead')).toBeVisible();
    expect(within(row).queryByText('Playing')).not.toBeInTheDocument();
  });

  it('removes a full download after the confirmation, and the film’s line says none yet', async () => {
    const { panel, state } = await openPanel([download()]);
    const heat = await needRow(panel, 'Heat (1995)');
    expect(within(heat).getByText('Playing: download (not checked yet)')).toBeVisible();

    const row = await rowOf(panel, 'Downloaded from OpenSubtitles');
    await userEvent.click(
      within(row).getByRole('button', {
        name: 'Remove the OpenSubtitles download Heat.1995.1080p.BluRay.x264',
      }),
    );
    expect(
      within(row).getByText(/CineFilm won.t use this file for this edition again/),
    ).toBeVisible();
    expect(state.calls).toEqual([]);
    await userEvent.click(within(row).getByRole('button', { name: 'Remove download' }));

    expect(await within(panel).findByText(/Download removed/)).toBeVisible();
    expect(state.calls).toEqual([{ method: 'DELETE', id: 20, action: 'remove' }]);
    await waitFor(() =>
      expect(within(panel).queryByText('Downloaded from OpenSubtitles')).not.toBeInTheDocument(),
    );
    await waitFor(async () =>
      expect(within(await needRow(panel, 'Heat (1995)')).getByText('None yet')).toBeVisible(),
    );
  });

  it('nudges the timing of a full download', async () => {
    const { panel, state } = await openPanel([download()]);
    const row = await rowOf(panel, 'Downloaded from OpenSubtitles');

    await userEvent.click(within(row).getByRole('button', { name: /Show the cues of/ }));
    await userEvent.click(within(row).getByRole('button', { name: 'Show lines 1 second later' }));

    await waitFor(() =>
      expect(state.calls).toEqual([
        { method: 'PUT', id: 20, action: 'offset', body: { offsetMs: 1000 } },
      ]),
    );
  });
});

describe('Admin: Subtitles panel, the Forced and Full chips', () => {
  const unchecked = (over = {}) => forced({ id: 1, check: null, alignment: null, ...over });

  it('lists the full files and full downloads together under Full, and the forced ones under Forced', async () => {
    const { panel } = await openPanel([unchecked(), full(), download()]);
    await within(panel).findByText('Heat (1995)/Heat.en.forced.srt');
    expect(
      within(within(panel).getByRole('group', { name: 'Show subtitle files' }))
        .getAllByRole('button')
        .map((b) => b.textContent),
    ).toEqual([
      'All (3)',
      'Forced (1)',
      'Full (2)',
      'Needs checking (0)',
      'Unchecked (1)',
      'Downloads (0)',
    ]);
    expect(chip(panel, /^All/)).toHaveAttribute('aria-pressed', 'true');

    await userEvent.click(chip(panel, /^Full/));
    expect(chip(panel, /^Full/)).toHaveAttribute('aria-pressed', 'true');
    expect(within(panel).getByText('Heat (1995)/Heat.en.srt')).toBeVisible();
    expect(within(panel).getByText('Downloaded from OpenSubtitles')).toBeVisible();
    expect(within(panel).queryByText('Heat (1995)/Heat.en.forced.srt')).not.toBeInTheDocument();
    expect(within(panel).getAllByText('Full', { selector: 'span' })).toHaveLength(2);

    await userEvent.click(chip(panel, /^Forced/));
    expect(within(panel).getByText('Heat (1995)/Heat.en.forced.srt')).toBeVisible();
    expect(within(panel).queryByText('Heat (1995)/Heat.en.srt')).not.toBeInTheDocument();
    expect(within(panel).queryByText('Downloaded from OpenSubtitles')).not.toBeInTheDocument();
  });

  it('keeps the Full chip, and the forced chips’ counts, when a download is removed', async () => {
    const { panel } = await openPanel([unchecked(), full(), download()]);
    await within(panel).findByText('Heat (1995)/Heat.en.forced.srt');
    await userEvent.click(chip(panel, /^Full/));
    const row = await rowOf(panel, 'Downloaded from OpenSubtitles');
    await userEvent.click(
      within(row).getByRole('button', { name: /^Remove the OpenSubtitles download/ }),
    );
    await userEvent.click(within(row).getByRole('button', { name: 'Remove download' }));

    await within(panel).findByText(/Download removed/);
    await waitFor(() => expect(chip(panel, /^Full/)).toHaveTextContent('Full (1)'));
    expect(chip(panel, /^Full/)).toHaveAttribute('aria-pressed', 'true');
    expect(chip(panel, /^Unchecked/)).toHaveTextContent('Unchecked (1)');
    expect(chip(panel, /^Downloads/)).toHaveTextContent('Downloads (0)');
  });
});

describe('Admin: Subtitles panel, from the full-need list to a film’s files', () => {
  const otherFull = () =>
    full({
      id: 10,
      path: 'Rashomon (1950)/Rashomon.en.srt',
      filmId: 2,
      filmTitle: 'Rashomon',
      editionId: 21,
    });

  it('shows only that film’s full files, says so, and gives the list back on All', async () => {
    const { panel } = await openPanel([forced({ id: 1 }), full(), download(), otherFull()]);
    const heat = await needRow(panel, 'Heat (1995)');
    expect(within(heat).getByText('Playing: your file')).toBeVisible();
    await userEvent.click(
      within(heat).getByRole('button', { name: 'Show the full subtitle files of Heat (1995)' }),
    );

    expect(chip(panel, /^Full/)).toHaveAttribute('aria-pressed', 'true');
    const status = within(panel).getByText('Showing the full subtitle files of Heat (1995).');
    expect(status).toHaveFocus();
    expect(within(panel).getByText('Heat (1995)/Heat.en.srt')).toBeVisible();
    expect(within(panel).getByText('Downloaded from OpenSubtitles')).toBeVisible();
    expect(within(panel).queryByText('Rashomon (1950)/Rashomon.en.srt')).not.toBeInTheDocument();
    expect(within(panel).queryByText('Heat (1995)/Heat.en.forced.srt')).not.toBeInTheDocument();

    await userEvent.click(chip(panel, /^All/));
    expect(within(panel).queryByText(/Showing the full subtitle files/)).not.toBeInTheDocument();
    expect(within(panel).getByText('Rashomon (1950)/Rashomon.en.srt')).toBeVisible();
    expect(within(panel).getByText('Heat (1995)/Heat.en.forced.srt')).toBeVisible();
  });

  it('shows that nothing is there for a film with no full files, and Show all files ends it', async () => {
    const { panel } = await openPanel([forced({ id: 1 }), otherFull()]);
    const heat = await needRow(panel, 'Heat (1995)');
    expect(within(heat).getByText('None yet')).toBeVisible();
    await userEvent.click(
      within(heat).getByRole('button', { name: 'Show the full subtitle files of Heat (1995)' }),
    );

    expect(within(panel).getByText('No files here.')).toBeVisible();
    await userEvent.click(within(panel).getByRole('button', { name: 'Show all files' }));
    expect(within(panel).queryByText('No files here.')).not.toBeInTheDocument();
    expect(within(panel).getByText('Heat (1995)/Heat.en.forced.srt')).toBeVisible();
  });
});
