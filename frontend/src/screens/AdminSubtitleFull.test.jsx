import { screen, within } from '@testing-library/react';
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

// The file list is read-only: the fake answers the list and the full-need list the Subtitles section also reads.
function serve(items) {
  const state = { items: structuredClone(items) };
  const route = (method, url) => {
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
    return null;
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

const chip = (panel, name) =>
  within(within(panel).getByRole('group', { name: 'Show subtitle files' })).getByRole('button', {
    name,
  });

async function openPanel(items) {
  const served = serve(items);
  installFetch(served.route);
  renderApp('/admin/subtitles');
  await userEvent.click(await screen.findByRole('button', { name: 'Show all subtitle files' }));
  const panel = await screen.findByRole('region', { name: 'Subtitle files' });
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

  it('has no tool of any kind on a hand-attached full file', async () => {
    const { panel } = await openPanel([full({ ownerAttached: true })]);
    const item = (await within(panel).findByText('Heat (1995)/Heat.en.srt')).closest('li');

    expect(within(item).queryAllByRole('button')).toHaveLength(0);
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
});
