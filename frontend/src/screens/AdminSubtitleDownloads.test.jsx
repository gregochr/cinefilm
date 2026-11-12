import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const owner = (over = {}) => ({
  ...fixtures.adminSubtitles[0],
  origin: 'FILE',
  release: null,
  openSubtitlesFileId: null,
  servedReason: null,
  offsetSource: null,
  alignment: null,
  check: null,
  ...over,
});

const download = (over = {}) =>
  owner({
    id: 50,
    path: 'Heat (1995)/.cinefilm/opensubtitles-50.srt',
    origin: 'OPENSUBTITLES',
    release: 'Heat.1995.1080p.BluRay.x264',
    openSubtitlesFileId: 123,
    whole: true,
    part: null,
    servedReason: 'Replaced by an OpenSubtitles download that agrees',
    ...over,
  });

const fitOf = (over = {}) => ({
  verdict: 'ALIGNED',
  reasons: [],
  fittedOffsetMs: 1200,
  matchedShare: 0.9,
  matchedCount: 9,
  servedCount: 10,
  applied: true,
  checkedAt: '2026-11-12T06:00:00Z',
  ...over,
});

// A fake of the subtitle routes whose DELETE removes the row (unless `stale`, when the list keeps showing it).
function serve({ items, fail = {}, gate = null, stale = false } = {}) {
  const state = { items: structuredClone(items), calls: [], lists: 0 };
  const route = (method, url, init) => {
    const path = new URL(url, 'http://test.local').pathname;
    if (path === '/api/admin/subtitles') {
      state.lists += 1;
      return respond(state.items);
    }
    const match = path.match(/^\/api\/admin\/subtitles\/(\d+)(?:\/(verify))?$/);
    if (!match) return null;
    const id = Number(match[1]);
    state.calls.push({ method, id, action: match[2] ?? 'remove', body: init.body ?? null });
    if (match[2] === 'verify') {
      const row = state.items.find((i) => i.id === id);
      row.verified = method === 'POST';
      return respond(row);
    }
    if (method !== 'DELETE') return null;
    const failing = fail[id]?.();
    if (failing) return failing;
    const done = () => {
      if (!stale) state.items = state.items.filter((i) => i.id !== id);
      return respond(null);
    };
    return gate ? gate(id).then(done) : done();
  };
  return { state, route };
}

async function openPanel(options) {
  const served = serve(options);
  installFetch(served.route);
  const view = renderApp('/admin');
  const panel = await screen.findByRole('region', { name: 'Subtitles' });
  return { ...served, panel, view };
}

/** The row listing `text`: rows are the list items that show it (the lists inside a row never do). */
const rowOf = async (panel, text) => {
  await within(panel).findByText(text);
  return within(panel)
    .getAllByRole('listitem')
    .find((li) => within(li).queryByText(text) !== null);
};

const REMOVE_NAME = (release) => `Remove the OpenSubtitles download ${release}`;

describe('Admin: Subtitles panel, where a file came from and what plays', () => {
  it('says a download came from OpenSubtitles with its release, and has no file path or Attach', async () => {
    const { panel } = await openPanel({ items: [download()] });
    const row = await rowOf(panel, 'Downloaded from OpenSubtitles');
    expect(within(row).getByText('Heat.1995.1080p.BluRay.x264')).toBeVisible();
    expect(within(row).queryByText(/opensubtitles-50\.srt/)).not.toBeInTheDocument();
    expect(within(row).queryByRole('button', { name: /^Move|^Attach/ })).not.toBeInTheDocument();
    expect(within(row).getByRole('button', { name: /^Show the cues of/ })).toBeVisible();
  });

  it('copes with a download that has no release', async () => {
    const { panel } = await openPanel({ items: [download({ release: null })] });
    const row = await rowOf(panel, 'Downloaded from OpenSubtitles');
    expect(within(row).getByRole('button', { name: REMOVE_NAME('download') })).toBeVisible();
  });

  it('shows the owner’s own file by its path, with no Remove', async () => {
    const { panel } = await openPanel({ items: [owner()] });
    const row = await rowOf(panel, 'Heat (1995)/Heat.en.forced.srt');
    expect(within(row).queryByText('Downloaded from OpenSubtitles')).not.toBeInTheDocument();
    expect(within(row).queryByRole('button', { name: /^Remove/ })).not.toBeInTheDocument();
    expect(within(row).getByRole('button', { name: /^Move/ })).toBeVisible();
  });

  it('treats a file with no origin as the owner’s own', async () => {
    const bare = owner();
    delete bare.origin;
    const { panel } = await openPanel({ items: [bare] });
    const row = await rowOf(panel, 'Heat (1995)/Heat.en.forced.srt');
    expect(within(row).queryByRole('button', { name: /^Remove/ })).not.toBeInTheDocument();
  });

  it('marks the file that plays with real text, and shows the server’s reason exactly as sent', async () => {
    const reason = 'Replaced by an OpenSubtitles download that agrees';
    const { panel } = await openPanel({
      items: [
        download({ served: true, servedReason: reason }),
        owner({ id: 2, path: 'B/Mine.en.forced.srt', served: false, servedReason: 'Not playing' }),
      ],
    });
    const playing = await rowOf(panel, 'Downloaded from OpenSubtitles');
    expect(within(playing).getByText('Playing')).toBeVisible();
    expect(within(playing).getByText(reason)).toBeVisible();
    const idle = await rowOf(panel, 'B/Mine.en.forced.srt');
    expect(within(idle).queryByText('Playing')).not.toBeInTheDocument();
    expect(within(idle).getByText('Not playing')).toBeVisible();
  });

  it('shows no reason line when the server sends none', async () => {
    const { panel } = await openPanel({ items: [owner({ servedReason: null })] });
    const row = await rowOf(panel, 'Heat (1995)/Heat.en.forced.srt');
    expect(within(row).getByText('Playing')).toBeVisible();
    expect(within(row).getByText('12 cues · No problems found')).toBeVisible();
  });
});

describe('Admin: Subtitles panel, timing against the film’s English track', () => {
  const rowWith = async (alignment, extra = {}) => {
    const { panel } = await openPanel({ items: [owner({ alignment, ...extra })] });
    return rowOf(panel, 'Heat (1995)/Heat.en.forced.srt');
  };

  it('ALIGNED and applied: lines up, and the timing was set automatically with its offset', async () => {
    const row = await rowWith(fitOf());
    expect(within(row).getByText("Lines up with the film's English track")).toBeVisible();
    expect(within(row).getByText('Timing set automatically (+1.2 s)')).toBeVisible();
  });

  it('ALIGNED with a negative offset shows the minus sign', async () => {
    const row = await rowWith(fitOf({ fittedOffsetMs: -450 }));
    expect(within(row).getByText('Timing set automatically (−0.5 s)')).toBeVisible();
  });

  it('ALIGNED but not applied: lines up, with no automatic-timing note', async () => {
    const row = await rowWith(fitOf({ applied: false }));
    expect(within(row).getByText("Lines up with the film's English track")).toBeVisible();
    expect(within(row).queryByText(/Timing set automatically/)).not.toBeInTheDocument();
  });

  it('CHECK: timing needs checking, with the reasons', async () => {
    const row = await rowWith(
      fitOf({ verdict: 'CHECK', applied: false, reasons: ['Only 3 of 9 lines line up'] }),
    );
    expect(within(row).getByText('Timing needs checking')).toBeVisible();
    const list = within(row).getByRole('list', { name: 'Timing needs checking: details' });
    expect(within(list).getByText('Only 3 of 9 lines line up')).toBeVisible();
  });

  it.each(['TOO_FEW', 'NO_REFERENCE'])(
    '%s: timing not checked, with the reason',
    async (verdict) => {
      const row = await rowWith(fitOf({ verdict, applied: false, reasons: ['No English track'] }));
      expect(within(row).getByText('Timing not checked')).toBeVisible();
      expect(within(row).getByText('No English track')).toBeVisible();
    },
  );

  it('says when the owner set the timing', async () => {
    const row = await rowWith(null, { offsetSource: 'OWNER', offsetMs: 500 });
    expect(within(row).getByText('Timing set by you')).toBeVisible();
  });

  it('shows the owner’s timing beside an alignment result', async () => {
    const row = await rowWith(fitOf({ applied: false }), { offsetSource: 'OWNER' });
    expect(within(row).getByText("Lines up with the film's English track")).toBeVisible();
    expect(within(row).getByText('Timing set by you')).toBeVisible();
  });

  it('shows nothing about timing without a result or an owner nudge', async () => {
    const row = await rowWith(null);
    expect(within(row).queryByText(/Timing/)).not.toBeInTheDocument();
  });
});

describe('Admin: Subtitles panel, keeping your own file', () => {
  const pair = (mine = {}, theirs = {}) => [
    owner({
      id: 1,
      served: false,
      replacedByDownload: true,
      servedReason: 'Replaced by a download',
      ...mine,
    }),
    download({ id: 2, served: true, ...theirs }),
  ];

  it('offers "Use my file (looks right)" on the file a download replaced, and it verifies the file', async () => {
    const { panel, state } = await openPanel({ items: pair() });
    const row = await rowOf(panel, 'Heat (1995)/Heat.en.forced.srt');
    await userEvent.click(within(row).getByRole('button', { name: 'Use my file (looks right)' }));
    await waitFor(() =>
      expect(state.calls).toEqual([
        expect.objectContaining({ method: 'POST', id: 1, action: 'verify' }),
      ]),
    );
    await within(row).findByText('Checked');
    expect(
      within(row).queryByRole('button', { name: 'Use my file (looks right)' }),
    ).not.toBeInTheDocument();
  });

  it('has no second "Looks right" among the details of a replaced file', async () => {
    const { panel } = await openPanel({ items: pair() });
    const row = await rowOf(panel, 'Heat (1995)/Heat.en.forced.srt');
    await userEvent.click(within(row).getByRole('button', { name: /^Show the cues of/ }));
    await within(row).findByRole('group', { name: 'Timing' });
    expect(within(row).queryByRole('button', { name: 'Looks right' })).not.toBeInTheDocument();
  });

  it('offers nothing on a file that is not playing but was not replaced by a download', async () => {
    const { panel } = await openPanel({ items: pair({ replacedByDownload: false }) });
    const row = await rowOf(panel, 'Heat (1995)/Heat.en.forced.srt');
    expect(
      within(row).queryByRole('button', { name: 'Use my file (looks right)' }),
    ).not.toBeInTheDocument();
  });

  it('keeps the ordinary "Looks right" for a file that is not replaced', async () => {
    const { panel } = await openPanel({ items: [owner()] });
    const row = await rowOf(panel, 'Heat (1995)/Heat.en.forced.srt');
    expect(
      within(row).queryByRole('button', { name: 'Use my file (looks right)' }),
    ).not.toBeInTheDocument();
    await userEvent.click(within(row).getByRole('button', { name: /^Show the cues of/ }));
    expect(await within(row).findByRole('button', { name: 'Looks right' })).toBeVisible();
  });

  it('offers nothing when the owner already checked the replaced file', async () => {
    const { panel } = await openPanel({ items: pair({ verified: true }) });
    const row = await rowOf(panel, 'Heat (1995)/Heat.en.forced.srt');
    expect(
      within(row).queryByRole('button', { name: 'Use my file (looks right)' }),
    ).not.toBeInTheDocument();
  });
});

describe('Admin: Subtitles panel, removing a download', () => {
  const two = () => [
    download({ id: 50, release: 'First.Release', editionId: 11 }),
    download({ id: 51, release: 'Second.Release', editionId: 12 }),
  ];

  it('asks first, in the agreed words, and sends nothing until confirmed', async () => {
    const { panel, state } = await openPanel({ items: two() });
    const row = await rowOf(panel, 'First.Release');
    await userEvent.click(within(row).getByRole('button', { name: REMOVE_NAME('First.Release') }));
    const group = within(row).getByRole('group', { name: 'Remove this download' });
    expect(
      within(group).getByText(
        "Remove this download? CineFilm won't use this file for this edition again.",
      ),
    ).toBeVisible();
    expect(state.calls).toEqual([]);
    expect(within(group).getByRole('button', { name: 'Keep it' })).toHaveFocus();
  });

  it('Keep it closes the question, changes nothing, and returns focus to Remove', async () => {
    const { panel, state } = await openPanel({ items: two() });
    const row = await rowOf(panel, 'First.Release');
    await userEvent.click(within(row).getByRole('button', { name: REMOVE_NAME('First.Release') }));
    await userEvent.click(within(row).getByRole('button', { name: 'Keep it' }));
    expect(
      within(row).queryByRole('group', { name: 'Remove this download' }),
    ).not.toBeInTheDocument();
    expect(within(row).getByRole('button', { name: REMOVE_NAME('First.Release') })).toHaveFocus();
    expect(state.calls).toEqual([]);
  });

  it('Remove download sends DELETE, drops the row, reads the list again, and says so in a status that has focus', async () => {
    const { panel, state } = await openPanel({ items: two() });
    const row = await rowOf(panel, 'First.Release');
    await userEvent.click(within(row).getByRole('button', { name: REMOVE_NAME('First.Release') }));
    const listsBefore = state.lists;
    await userEvent.click(within(row).getByRole('button', { name: 'Remove download' }));

    const status = await within(panel).findByText(/Download removed/);
    expect(status).toHaveAttribute('role', 'status');
    expect(status).toHaveTextContent(
      "Download removed. CineFilm won't use it for this edition again.",
    );
    expect(status).toHaveFocus();
    expect(state.calls).toEqual([{ method: 'DELETE', id: 50, action: 'remove', body: null }]);
    await waitFor(() => expect(state.lists).toBeGreaterThan(listsBefore));
    expect(within(panel).queryByText('First.Release')).not.toBeInTheDocument();
    expect(within(panel).getByText('Second.Release')).toBeVisible();
  });

  it('keeps the row out when a refresh that was already in flight still lists it', async () => {
    const { panel, state } = await openPanel({ items: two(), stale: true });
    const row = await rowOf(panel, 'First.Release');
    await userEvent.click(within(row).getByRole('button', { name: REMOVE_NAME('First.Release') }));
    await userEvent.click(within(row).getByRole('button', { name: 'Remove download' }));
    await within(panel).findByText(/Download removed/);
    await waitFor(() => expect(state.lists).toBeGreaterThan(1));
    expect(within(panel).queryByText('First.Release')).not.toBeInTheDocument();
  });

  it('shows the panel’s problem wording on failure, keeps the row, and Try again removes it', async () => {
    let failures = 1;
    const { panel, state } = await openPanel({
      items: two(),
      fail: {
        50: () =>
          failures-- > 0 ? respond({ error: 'Only a download can be removed' }, 400) : null,
      },
    });
    const row = await rowOf(panel, 'First.Release');
    await userEvent.click(within(row).getByRole('button', { name: REMOVE_NAME('First.Release') }));
    await userEvent.click(within(row).getByRole('button', { name: 'Remove download' }));

    expect(await within(row).findByRole('alert')).toHaveTextContent(
      'Only a download can be removed',
    );
    expect(within(panel).queryByText(/Download removed/)).not.toBeInTheDocument();

    await userEvent.click(within(row).getByRole('button', { name: 'Try again' }));
    await within(panel).findByText(/Download removed/);
    expect(state.calls.map((c) => c.method)).toEqual(['DELETE', 'DELETE']);
    expect(within(panel).queryByText('First.Release')).not.toBeInTheDocument();
  });

  it('says a download is gone when it was already removed (404), and a general message for anything else', async () => {
    const { panel } = await openPanel({
      items: two(),
      fail: { 50: () => respond({ error: 'x' }, 404), 51: () => respond(null, 500) },
    });
    const first = await rowOf(panel, 'First.Release');
    await userEvent.click(
      within(first).getByRole('button', { name: REMOVE_NAME('First.Release') }),
    );
    await userEvent.click(within(first).getByRole('button', { name: 'Remove download' }));
    expect(await within(first).findByRole('alert')).toHaveTextContent(
      'That is no longer there. Reload the list and look again.',
    );
    await userEvent.click(within(first).getByRole('button', { name: 'Keep it' }));

    const second = await rowOf(panel, 'Second.Release');
    await userEvent.click(
      within(second).getByRole('button', { name: REMOVE_NAME('Second.Release') }),
    );
    await userEvent.click(within(second).getByRole('button', { name: 'Remove download' }));
    expect(await within(second).findByRole('alert')).toHaveTextContent(
      'Something went wrong. Try again.',
    );
  });

  it('serialises two removals: the second row’s Remove waits while the first is running', async () => {
    let release;
    const hold = new Promise((resolve) => {
      release = resolve;
    });
    const { panel, state } = await openPanel({
      items: two(),
      gate: (id) => (id === 50 ? hold : Promise.resolve()),
    });
    const first = await rowOf(panel, 'First.Release');
    const second = await rowOf(panel, 'Second.Release');
    await userEvent.click(
      within(first).getByRole('button', { name: REMOVE_NAME('First.Release') }),
    );
    await userEvent.click(within(first).getByRole('button', { name: 'Remove download' }));
    expect(
      within(second).getByRole('button', { name: REMOVE_NAME('Second.Release') }),
    ).toBeDisabled();

    release();
    await waitFor(() => expect(within(panel).queryByText('First.Release')).not.toBeInTheDocument());
    const next = within(panel).getByRole('button', { name: REMOVE_NAME('Second.Release') });
    await waitFor(() => expect(next).toBeEnabled());
    await userEvent.click(next);
    await userEvent.click(
      within(await rowOf(panel, 'Second.Release')).getByRole('button', { name: 'Remove download' }),
    );
    await waitFor(() =>
      expect(within(panel).queryByText('Second.Release')).not.toBeInTheDocument(),
    );
    expect(state.calls.map((c) => c.id)).toEqual([50, 51]);
    // The second removal is announced again: the status is a new element and takes focus once more.
    expect(await within(panel).findByText(/Download removed/)).toHaveFocus();
  });

  it('does not report a removal that finishes after the panel has gone', async () => {
    let release;
    const hold = new Promise((resolve) => {
      release = resolve;
    });
    const errors = vi.spyOn(console, 'error').mockImplementation(() => {});
    const { panel, state, view } = await openPanel({ items: two(), gate: () => hold });
    const row = await rowOf(panel, 'First.Release');
    await userEvent.click(within(row).getByRole('button', { name: REMOVE_NAME('First.Release') }));
    await userEvent.click(within(row).getByRole('button', { name: 'Remove download' }));
    view.unmount();
    release();
    await waitFor(() => expect(state.calls).toHaveLength(1));
    await Promise.resolve();
    expect(errors).not.toHaveBeenCalled();
  });
});

describe('Admin: Subtitles panel, the Downloads filter', () => {
  const chip = (panel, name) =>
    within(within(panel).getByRole('group', { name: 'Show subtitle files' })).getByRole('button', {
      name,
    });

  it('counts the downloads and lists only them, with aria-pressed on the chip', async () => {
    const { panel } = await openPanel({
      items: [
        owner({ id: 1, path: 'A/Mine.en.forced.srt' }),
        download({ id: 2, release: 'One.Release' }),
        download({ id: 3, release: 'Two.Release' }),
      ],
    });
    await within(panel).findByText('A/Mine.en.forced.srt');
    expect(chip(panel, /^Downloads/)).toHaveTextContent('Downloads (2)');
    expect(chip(panel, /^All/)).toHaveTextContent('All (3)');

    await userEvent.click(chip(panel, /^Downloads/));
    expect(chip(panel, /^Downloads/)).toHaveAttribute('aria-pressed', 'true');
    expect(chip(panel, /^All/)).toHaveAttribute('aria-pressed', 'false');
    expect(within(panel).getByText('One.Release')).toBeVisible();
    expect(within(panel).getByText('Two.Release')).toBeVisible();
    expect(within(panel).queryByText('A/Mine.en.forced.srt')).not.toBeInTheDocument();
  });

  it('says there are no files when none is a download', async () => {
    const { panel } = await openPanel({ items: [owner()] });
    await within(panel).findByText('Heat (1995)/Heat.en.forced.srt');
    await userEvent.click(chip(panel, /^Downloads/));
    expect(within(panel).getByText('No files here.')).toBeVisible();
  });
});
