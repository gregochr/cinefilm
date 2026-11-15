import { screen, within } from '@testing-library/react';
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

// The file list is read-only: the fake answers only the list.
function serve({ items }) {
  const route = (method, url) =>
    new URL(url, 'http://test.local').pathname === '/api/admin/subtitles' ? respond(items) : null;
  return { route };
}

async function openPanel(options) {
  const served = serve(options);
  installFetch(served.route);
  const view = renderApp('/admin/subtitles');
  await userEvent.click(await screen.findByRole('button', { name: 'Show all subtitle files' }));
  const panel = await screen.findByRole('region', { name: 'Subtitle files' });
  return { ...served, panel, view };
}

/** The row listing `text`: rows are the list items that show it (the lists inside a row never do). */
const rowOf = async (panel, text) => {
  await within(panel).findByText(text);
  return within(panel)
    .getAllByRole('listitem')
    .find((li) => within(li).queryByText(text) !== null);
};

describe('Admin: Subtitles panel, where a file came from and what plays', () => {
  it('says a download came from OpenSubtitles with its release, and has no file path or Attach', async () => {
    const { panel } = await openPanel({ items: [download()] });
    const row = await rowOf(panel, 'Downloaded from OpenSubtitles');
    expect(within(row).getByText('Heat.1995.1080p.BluRay.x264')).toBeVisible();
    expect(within(row).queryByText(/opensubtitles-50\.srt/)).not.toBeInTheDocument();
    expect(within(row).queryByRole('button', { name: /^Move|^Attach/ })).not.toBeInTheDocument();
    expect(within(row).queryAllByRole('button')).toHaveLength(0);
  });

  it('copes with a download that has no release', async () => {
    const { panel } = await openPanel({ items: [download({ release: null })] });
    const row = await rowOf(panel, 'Downloaded from OpenSubtitles');
    expect(within(row).queryAllByRole('button')).toHaveLength(0);
  });

  it('shows the owner’s own file by its path, with no Remove', async () => {
    const { panel } = await openPanel({ items: [owner()] });
    const row = await rowOf(panel, 'Heat (1995)/Heat.en.forced.srt');
    expect(within(row).queryByText('Downloaded from OpenSubtitles')).not.toBeInTheDocument();
    expect(within(row).queryByRole('button', { name: /^Remove/ })).not.toBeInTheDocument();
    expect(within(row).queryAllByRole('button')).toHaveLength(0);
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
