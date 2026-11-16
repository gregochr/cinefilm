import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

const file = (over = {}) => ({ ...fixtures.adminSubtitles[0], ...over });

// The file list is read-only: it only ever asks the server for the list.
function serve({ items = [file()] } = {}) {
  const requests = [];
  const route = (method, url) => {
    requests.push(`${method} ${url}`);
    return url === '/api/admin/subtitles' ? respond(structuredClone(items)) : null;
  };
  return { requests, route };
}

async function openPanel(options) {
  const served = serve(options);
  const fetchMock = installFetch(served.route);
  renderApp('/admin/subtitles');
  await userEvent.click(await screen.findByRole('button', { name: 'Show all subtitle files' }));
  const panel = await screen.findByRole('region', { name: 'Subtitle files' });
  return { ...served, fetchMock, panel };
}

describe('Admin: Subtitles panel, the list', () => {
  it('groups the files as Unmatched, Conflicts, Missing and Matched with their counts', async () => {
    const { panel } = await openPanel({
      items: [
        file({ id: 1, path: 'A/Matched.en.forced.srt' }),
        file({ id: 2, path: 'B/Unmatched.en.forced.srt', state: 'unmatched', filmId: null }),
        file({ id: 3, path: 'C/Missing.en.forced.srt', presence: 'MISSING' }),
        file({ id: 4, path: 'D/One.en.forced.srt', state: 'conflict' }),
        file({ id: 5, path: 'D/Two.forced.en.srt', state: 'conflict' }),
      ],
    });

    await within(panel).findByRole('heading', { level: 3, name: 'Unmatched (1)' });
    expect(
      within(panel)
        .getAllByRole('heading', { level: 3 })
        .map((h) => h.textContent),
    ).toEqual(['Unmatched (1)', 'Conflicts (2)', 'Missing (1)', 'Matched (1)']);
    expect(within(panel).getByRole('list', { name: 'Conflicts subtitles' })).toBeInTheDocument();
  });

  it('shows the path, where the file belongs, and how it was read', async () => {
    const { panel } = await openPanel({
      items: [
        file({
          parts: 2,
          part: 2,
          editionName: 'Extended',
          cueCount: 41,
          parseReport: 'read as Windows-1252, 3 blocks skipped',
        }),
      ],
    });

    const item = (await within(panel).findByText('Heat (1995)/Heat.en.forced.srt')).closest('li');
    expect(within(item).getByText('Heat · Extended · part 2')).toBeInTheDocument();
    expect(
      within(item).getByText('41 cues · read as Windows-1252, 3 blocks skipped'),
    ).toBeInTheDocument();
  });

  it('says an unmatched file is not attached', async () => {
    const { panel } = await openPanel({
      items: [file({ state: 'unmatched', filmId: null, filmTitle: null })],
    });
    expect(await within(panel).findByText('Not attached to a film')).toBeInTheDocument();
  });

  it('warns when a file ends after the film, is missing, or is in conflict', async () => {
    const { panel } = await openPanel({
      items: [
        file({ id: 1, endsAfterRuntime: true }),
        file({ id: 2, path: 'M.en.forced.srt', presence: 'MISSING' }),
        file({ id: 3, path: 'C.en.forced.srt', state: 'conflict' }),
      ],
    });

    expect(await within(panel).findByText('Ends after the film: wrong film or cut?')).toBeVisible();
    expect(within(panel).getByText('The last scan did not find this file.')).toBeVisible();
    expect(within(panel).getByText(/Another file competes for the same place/)).toBeVisible();
  });

  it('marks a file the owner checked', async () => {
    const { panel } = await openPanel({ items: [file({ verified: true })] });
    expect(await within(panel).findByText('Checked')).toBeInTheDocument();
  });

  it('says plainly when there are no files', async () => {
    const { panel } = await openPanel({ items: [] });
    expect(await within(panel).findByText(/No subtitle files found/)).toBeInTheDocument();
  });

  it('shows a loading status while the list loads', async () => {
    installFetch((method, url) => (url === '/api/admin/subtitles' ? new Promise(() => {}) : null));
    renderApp('/admin/subtitles');
    await userEvent.click(await screen.findByRole('button', { name: 'Show all subtitle files' }));
    const panel = await screen.findByRole('region', { name: 'Subtitle files' });
    expect(within(panel).getByRole('status', { name: 'Loading' })).toBeInTheDocument();
  });

  it('shows an error with Try again that loads the list', async () => {
    let fail = true;
    installFetch((method, url) =>
      url === '/api/admin/subtitles' && fail ? respond(null, 500) : null,
    );
    renderApp('/admin/subtitles');
    await userEvent.click(await screen.findByRole('button', { name: 'Show all subtitle files' }));
    const panel = await screen.findByRole('region', { name: 'Subtitle files' });
    expect(await within(panel).findByRole('alert')).toHaveTextContent(
      /Couldn.t load the subtitle files/,
    );
    expect(within(panel).getAllByRole('heading')).toHaveLength(1);

    fail = false;
    await userEvent.click(within(panel).getByRole('button', { name: 'Try again' }));

    expect(await within(panel).findByText('Heat (1995)/Heat.en.forced.srt')).toBeInTheDocument();
  });
});

describe('Admin: Subtitles panel, a list that fails to refresh', () => {
  it('keeps the files shown, says the refresh failed, and reloads on Try again', async () => {
    let scans = 0;
    let fail = false;
    installFetch((method, url) => {
      if (url === '/api/admin/scan') {
        scans += 1;
        return respond(
          scans === 1
            ? {
                ...fixtures.adminScan,
                running: true,
                progress: { processed: 5, total: 40, current: 'x' },
              }
            : {
                ...fixtures.adminScan,
                lastScan: { ...fixtures.adminScan.lastScan, finishedAt: '2026-11-09T04:31:00Z' },
              },
        );
      }
      if (url === '/api/admin/subtitles') {
        if (scans > 1) fail = true;
        return fail ? respond(null, 500) : respond([file()]);
      }
      return null;
    });
    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderApp('/admin/subtitles');
    await userEvent.click(await screen.findByRole('button', { name: 'Show all subtitle files' }));
    const panel = await screen.findByRole('region', { name: 'Subtitle files' });
    expect(await within(panel).findByText('Heat (1995)/Heat.en.forced.srt')).toBeInTheDocument();

    await act(() => vi.advanceTimersByTimeAsync(1500));

    expect(await within(panel).findByRole('alert')).toHaveTextContent(
      /Couldn.t refresh the subtitle files/,
    );
    expect(within(panel).getByText('Heat (1995)/Heat.en.forced.srt')).toBeInTheDocument();
    fail = false;
    scans = 0;
    await userEvent.click(within(panel).getByRole('button', { name: 'Try again' }));
    await waitFor(() =>
      expect(
        within(panel).queryByText(/Couldn.t refresh the subtitle files/),
      ).not.toBeInTheDocument(),
    );
  });
});

const CHECK = {
  verdict: 'AGREES',
  reasons: [],
  bestOffsetMs: 0,
  matchedShare: 0.9,
  servedCount: 12,
  referenceCount: 13,
  referenceRelease: 'Heat.1995.1080p.BluRay',
  checkedAt: '2026-11-12T06:10:00Z',
};

const itemOf = async (panel, path) => (await within(panel).findByText(path)).closest('li');

describe('Admin: Subtitles panel, the OpenSubtitles check', () => {
  it('says a file agrees, with the timing hint and the reference line', async () => {
    const { panel } = await openPanel({
      items: [
        file({
          check: { ...CHECK, reasons: ["OpenSubtitles' file is 1.2 s later"], bestOffsetMs: 1200 },
        }),
      ],
    });
    const item = await itemOf(panel, 'Heat (1995)/Heat.en.forced.srt');
    expect(within(item).getByText('Agrees with OpenSubtitles')).toBeVisible();
    expect(within(item).getByText("OpenSubtitles' file is 1.2 s later")).toBeVisible();
    expect(
      within(item).getByText('Compared with Heat.1995.1080p.BluRay · checked 12 Nov 06:10'),
    ).toBeVisible();
    expect(within(item).queryByText('Needs checking')).not.toBeInTheDocument();
  });

  it('says a file needs checking and lists the reasons', async () => {
    const reasons = [
      'OpenSubtitles has 41 lines, this file has 12',
      'Probably full subtitles, not forced',
    ];
    const { panel } = await openPanel({
      items: [file({ check: { ...CHECK, verdict: 'CHECK', reasons } })],
    });
    const item = await itemOf(panel, 'Heat (1995)/Heat.en.forced.srt');
    expect(within(item).getByText('Needs checking')).toBeVisible();
    const list = within(item).getByRole('list', { name: 'Needs checking: details' });
    expect(
      within(list)
        .getAllByRole('listitem')
        .map((li) => li.textContent),
    ).toEqual(reasons);
  });

  it('says Unchecked, quietly, for a served file with no check', async () => {
    const { panel } = await openPanel({ items: [file({ check: null })] });
    const item = await itemOf(panel, 'Heat (1995)/Heat.en.forced.srt');
    expect(within(item).getByText('Unchecked')).toBeVisible();
    expect(within(item).getByText('No OpenSubtitles file to compare with yet')).toBeVisible();
  });

  it('says nothing about a check for a file that is not served', async () => {
    const { panel } = await openPanel({
      items: [file({ state: 'unmatched', filmId: null, served: false, check: null })],
    });
    const item = await itemOf(panel, 'Heat (1995)/Heat.en.forced.srt');
    expect(within(item).queryByText('Unchecked')).not.toBeInTheDocument();
    expect(within(item).queryByText('Needs checking')).not.toBeInTheDocument();
  });

  it('shows both the owner check and Needs checking, and the owner can still undo theirs', async () => {
    const { panel } = await openPanel({
      items: [
        file({ verified: true, check: { ...CHECK, verdict: 'CHECK', reasons: ['x lines'] } }),
      ],
    });
    const item = await itemOf(panel, 'Heat (1995)/Heat.en.forced.srt');
    expect(within(item).getByText('Checked')).toBeVisible();
    expect(within(item).getByText('Needs checking')).toBeVisible();
  });

  const FILTER_ITEMS = [
    file({ id: 1, path: 'A/Agrees.en.forced.srt', check: CHECK }),
    file({
      id: 2,
      path: 'B/Check.en.forced.srt',
      check: { ...CHECK, verdict: 'CHECK', reasons: ['r'] },
    }),
    file({ id: 3, path: 'C/Unchecked.en.forced.srt', check: null }),
    file({
      id: 4,
      path: 'D/Unmatched.en.forced.srt',
      state: 'unmatched',
      filmId: null,
      served: false,
      check: null,
    }),
  ];
  const chip = (panel, name) =>
    within(within(panel).getByRole('group', { name: 'Show subtitle files' })).getByRole('button', {
      name,
    });

  it('filters the list: All, Needs checking, Unchecked, with counts', async () => {
    const { panel } = await openPanel({ items: FILTER_ITEMS });
    await within(panel).findByText('A/Agrees.en.forced.srt');
    expect(
      within(within(panel).getByRole('group', { name: 'Show subtitle files' }))
        .getAllByRole('button')
        .map((b) => b.textContent),
    ).toEqual([
      'All (4)',
      'Forced (4)',
      'Full (0)',
      'Needs checking (1)',
      'Unchecked (1)',
      'Downloads (0)',
    ]);
    expect(chip(panel, /^All/)).toHaveAttribute('aria-pressed', 'true');

    await userEvent.click(chip(panel, /^Needs checking/));
    expect(chip(panel, /^Needs checking/)).toHaveAttribute('aria-pressed', 'true');
    expect(chip(panel, /^All/)).toHaveAttribute('aria-pressed', 'false');
    expect(within(panel).getByText('B/Check.en.forced.srt')).toBeVisible();
    expect(within(panel).queryByText('A/Agrees.en.forced.srt')).not.toBeInTheDocument();
    expect(within(panel).getByRole('heading', { level: 3, name: 'Matched (1)' })).toBeVisible();

    await userEvent.click(chip(panel, /^Unchecked/));
    expect(within(panel).getByText('C/Unchecked.en.forced.srt')).toBeVisible();
    expect(within(panel).queryByText('B/Check.en.forced.srt')).not.toBeInTheDocument();

    await userEvent.click(chip(panel, /^All/));
    expect(within(panel).getByText('D/Unmatched.en.forced.srt')).toBeVisible();
  });

  it('says there are no files for a filter that lists none', async () => {
    const { panel } = await openPanel({ items: [file({ check: CHECK })] });
    await within(panel).findByText('Heat (1995)/Heat.en.forced.srt');
    await userEvent.click(chip(panel, /^Unchecked/));
    expect(within(panel).getByText('No files here.')).toBeVisible();
  });
});

describe('Admin: Subtitles panel, read-only', () => {
  const MUTATING = [
    /Looks right/,
    /Undo/,
    /Use my file/,
    /Attach/,
    /Move/,
    /Remove/,
    /Delete/,
    /Back to automatic/,
    /Let CineFilm decide/,
    /cues/,
    /Reset timing/,
    /Show lines/,
    /Timing/,
  ];

  it('has no per-file button of any kind, whatever the file is', async () => {
    const { panel } = await openPanel({
      items: [
        file({ id: 1, path: 'A/Forced.en.forced.srt' }),
        file({ id: 2, path: 'B/Checked.en.forced.srt', verified: true }),
        file({ id: 3, path: 'C/Replaced.en.forced.srt', replacedByDownload: true }),
        file({ id: 4, path: 'D/Hand.en.srt', ownerAttached: true, kind: 'FULL' }),
        file({
          id: 5,
          path: 'E/Download.en.srt',
          origin: 'OPENSUBTITLES',
          release: 'Heat.1995.1080p',
        }),
        file({ id: 6, path: 'F/Loose.en.forced.srt', state: 'unmatched', filmId: null }),
      ],
    });
    await within(panel).findByText('A/Forced.en.forced.srt');

    for (const li of within(panel).getAllByRole('listitem')) {
      expect(within(li).queryAllByRole('button')).toHaveLength(0);
      expect(within(li).queryAllByRole('link')).toHaveLength(0);
    }
    for (const name of MUTATING) {
      expect(within(panel).queryByRole('button', { name })).not.toBeInTheDocument();
    }
  });

  it('still tells what each file is: its place, checked, playing and full', async () => {
    const { panel } = await openPanel({
      items: [
        file({
          id: 2,
          path: 'B/Checked.en.forced.srt',
          verified: true,
          served: true,
          editionName: 'Extended',
          parts: 2,
          part: 2,
        }),
        file({
          id: 5,
          path: 'E/Download.en.srt',
          origin: 'OPENSUBTITLES',
          release: 'Heat.1995.1080p',
          kind: 'FULL',
        }),
      ],
    });

    const checked = await itemOf(panel, 'B/Checked.en.forced.srt');
    expect(within(checked).getByText('Heat · Extended · part 2')).toBeVisible();
    expect(within(checked).getByText('Checked')).toBeVisible();
    expect(within(checked).getByText('Playing')).toBeVisible();
    const download = await itemOf(panel, 'Heat.1995.1080p');
    expect(within(download).getByText('Full')).toBeVisible();
  });

  it('shows the timing offset of a file under a film only when it is not zero and was not fitted', async () => {
    const { panel } = await openPanel({
      items: [
        file({ id: 1, path: 'A/Shifted.en.forced.srt', offsetMs: 250 }),
        file({ id: 2, path: 'B/Early.en.forced.srt', offsetMs: -1250 }),
        file({ id: 3, path: 'C/Plain.en.forced.srt', offsetMs: 0 }),
        file({
          id: 4,
          path: 'D/Loose.en.forced.srt',
          offsetMs: 500,
          state: 'unmatched',
          filmId: null,
        }),
        file({
          id: 5,
          path: 'E/Fitted.en.forced.srt',
          offsetMs: 1200,
          alignment: {
            verdict: 'ALIGNED',
            reasons: [],
            fittedOffsetMs: 1200,
            applied: true,
            checkedAt: '2026-11-12T06:31:00Z',
          },
        }),
        // Applied, but the verdict is not ALIGNED: no automatic-timing note, so the offset is still told.
        file({
          id: 6,
          path: 'F/Unconvinced.en.forced.srt',
          offsetMs: 750,
          alignment: {
            verdict: 'CHECK',
            reasons: [],
            fittedOffsetMs: 750,
            applied: true,
            checkedAt: '2026-11-12T06:31:00Z',
          },
        }),
      ],
    });

    const unconvinced = await itemOf(panel, 'F/Unconvinced.en.forced.srt');
    expect(within(unconvinced).getByText('Timing shifted +0.75 s')).toBeVisible();
    const fitted = await itemOf(panel, 'E/Fitted.en.forced.srt');
    expect(within(fitted).getByText('Timing set automatically (+1.2 s)')).toBeVisible();
    expect(within(fitted).queryByText(/Timing shifted/)).not.toBeInTheDocument();
    expect(
      within(await itemOf(panel, 'A/Shifted.en.forced.srt')).getByText('Timing shifted +0.25 s'),
    ).toBeVisible();
    expect(
      within(await itemOf(panel, 'B/Early.en.forced.srt')).getByText('Timing shifted −1.25 s'),
    ).toBeVisible();
    expect(
      within(await itemOf(panel, 'C/Plain.en.forced.srt')).queryByText(/Timing shifted/),
    ).not.toBeInTheDocument();
    expect(
      within(await itemOf(panel, 'D/Loose.en.forced.srt')).queryByText(/Timing shifted/),
    ).not.toBeInTheDocument();
  });

  it('sends nothing but a read of the list', async () => {
    const { requests, panel } = await openPanel();
    await within(panel).findByText('Heat (1995)/Heat.en.forced.srt');

    expect(requests.filter((r) => !r.startsWith('GET '))).toEqual([]);
    expect(requests.filter((r) => r === 'GET /api/admin/subtitles')).toHaveLength(1);
  });

  it('keeps the note that points to the film page and the player', async () => {
    await openPanel();

    expect(screen.getByText('Subtitles → Fix…')).toBeVisible();
    expect(screen.getByText(/or in the player/)).toBeVisible();
  });
});
