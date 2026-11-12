import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

const file = (over = {}) => ({ ...fixtures.adminSubtitles[0], ...over });

const CUES = {
  offsetMs: 0,
  cues: [
    { startMs: 2196778, endMs: 2200239, text: 'He is behind you.' },
    { startMs: 2201000, endMs: 2203500, text: 'Line one\nLine two' },
    { startMs: 1500, endMs: 2500, text: '<b>not bold</b> & <script>x</script>' },
  ],
};

// A stateful fake of the subtitle routes: each action changes the list the way the server would.
function serve({ items = [file()], cues = CUES, onCall = () => {}, fail = {} } = {}) {
  const state = { items: structuredClone(items), calls: [] };
  const route = (method, url, init) => {
    const path = new URL(url, 'http://test.local').pathname;
    if (path === '/api/admin/subtitles') return respond(state.items);
    const match = path.match(
      /^\/api\/admin\/subtitles\/(\d+)\/(cues|attach|detach|offset|verify)$/,
    );
    if (!match) return null;
    const id = Number(match[1]);
    const action = match[2];
    const item = state.items.find((i) => i.id === id);
    if (action === 'cues') return respond({ ...cues, offsetMs: item.offsetMs });
    const body = init.body ? JSON.parse(init.body) : null;
    state.calls.push({ method, id, action, body });
    onCall({ method, id, action, body });
    if (fail[action]) return fail[action]();
    if (action === 'offset') item.offsetMs = body.offsetMs;
    if (action === 'verify') item.verified = method === 'POST';
    if (action === 'detach') Object.assign(item, { ownerAttached: false });
    if (action === 'attach') {
      Object.assign(item, {
        state: 'matched',
        ownerAttached: true,
        filmId: 1,
        filmTitle: 'Heat',
        editionId: body.editionId,
        editionName: 'Theatrical Cut',
        parts: 1,
        part: 1,
      });
    }
    return respond(item);
  };
  return { state, route };
}

async function openPanel(options) {
  const served = serve(options);
  const fetchMock = installFetch(served.route);
  renderApp('/admin');
  const panel = await screen.findByRole('region', { name: 'Subtitles' });
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
    ).toEqual([
      'Which films need them',
      'Unmatched (1)',
      'Conflicts (2)',
      'Missing (1)',
      'Matched (1)',
    ]);
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
    expect(await within(panel).findByText(/No forced subtitle files found/)).toBeInTheDocument();
  });

  it('shows a loading status while the list loads', async () => {
    installFetch((method, url) => (url === '/api/admin/subtitles' ? new Promise(() => {}) : null));
    renderApp('/admin');
    const panel = await screen.findByRole('region', { name: 'Subtitles' });
    expect(within(panel).getByRole('status', { name: 'Loading' })).toBeInTheDocument();
  });

  it('shows an error with Try again that loads the list', async () => {
    let fail = true;
    installFetch((method, url) =>
      url === '/api/admin/subtitles' && fail ? respond(null, 500) : null,
    );
    renderApp('/admin');
    const panel = await screen.findByRole('region', { name: 'Subtitles' });
    expect(await within(panel).findByText(/Couldn.t load the subtitle files/)).toBeInTheDocument();

    fail = false;
    await userEvent.click(within(panel).getByRole('button', { name: 'Try again' }));

    expect(await within(panel).findByText('Heat (1995)/Heat.en.forced.srt')).toBeInTheDocument();
  });
});

describe('Admin: Subtitles panel, the cues', () => {
  it('Show cues opens the cue list with each cue’s time and its text, Hide cues closes it', async () => {
    const { panel } = await openPanel();
    const item = await within(panel).findByText('Heat (1995)/Heat.en.forced.srt');
    const li = item.closest('li');
    const toggle = within(li).getByRole('button', { name: /Show the cues of Heat.en.forced.srt/ });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');

    await userEvent.click(toggle);

    const list = await within(li).findByRole('list', { name: 'Cues in Heat.en.forced.srt' });
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(toggle).toHaveAttribute('aria-controls', list.id);
    const cues = within(list).getAllByRole('listitem');
    expect(cues).toHaveLength(3);
    expect(cues[0]).toHaveTextContent('00:36:36He is behind you.');
    expect(cues[1]).toHaveTextContent('00:36:41Line one Line two');

    await userEvent.click(toggle);
    expect(within(li).queryByRole('list', { name: /Cues in/ })).not.toBeInTheDocument();
    expect(toggle).toHaveTextContent('Show cues');
  });

  it('renders cue text as text, never as markup', async () => {
    const { panel } = await openPanel();
    const li = (await within(panel).findByText('Heat (1995)/Heat.en.forced.srt')).closest('li');
    await userEvent.click(within(li).getByRole('button', { name: /Show the cues/ }));

    expect(await within(li).findByText('<b>not bold</b> & <script>x</script>')).toBeInTheDocument();
    // Had it been rendered as markup, "not bold" would be an element of its own.
    expect(within(li).queryByText('not bold')).not.toBeInTheDocument();
  });

  it('Play from here opens the player on that film and edition 3 seconds before the cue', async () => {
    const { panel } = await openPanel();
    const li = (await within(panel).findByText('Heat (1995)/Heat.en.forced.srt')).closest('li');
    await userEvent.click(within(li).getByRole('button', { name: /Show the cues/ }));

    const links = await within(li).findAllByRole('link', { name: /^Play from here/ });

    expect(links[0]).toHaveAttribute('href', '/films/1/play?edition=11&at=2193778');
    expect(links[0]).toHaveAccessibleName('Play from here: He is behind you.');
    expect(links[1]).toHaveAccessibleName('Play from here: Line one Line two');
    // 1.5 s is inside the lead, so it starts at 0 rather than before the film.
    expect(links[2]).toHaveAttribute('href', '/films/1/play?edition=11&at=0');
  });

  it('has no Play from here for a file that is not attached, and no timing buttons', async () => {
    const { panel } = await openPanel({
      items: [file({ state: 'unmatched', filmId: null, editionId: null })],
    });
    const li = (await within(panel).findByText('Heat (1995)/Heat.en.forced.srt')).closest('li');
    await userEvent.click(within(li).getByRole('button', { name: /Show the cues/ }));

    await within(li).findByRole('list', { name: /Cues in/ });
    expect(within(li).queryByRole('link')).not.toBeInTheDocument();
    expect(within(li).queryByRole('group', { name: 'Timing' })).not.toBeInTheDocument();
    expect(within(li).queryByRole('button', { name: 'Looks right' })).not.toBeInTheDocument();
  });

  it('says so when a file has no cues, and offers Try again when they cannot be loaded', async () => {
    const { panel } = await openPanel({ cues: { offsetMs: 0, cues: [] } });
    const li = (await within(panel).findByText('Heat (1995)/Heat.en.forced.srt')).closest('li');
    await userEvent.click(within(li).getByRole('button', { name: /Show the cues/ }));
    expect(await within(li).findByText('This file has no cues.')).toBeInTheDocument();
  });

  it('shows a loading status while the cues load, and an error with Try again', async () => {
    let fail = true;
    installFetch((method, url) => {
      if (url === '/api/admin/subtitles/1/cues') return fail ? respond(null, 500) : respond(CUES);
      return null;
    });
    renderApp('/admin');
    const panel = await screen.findByRole('region', { name: 'Subtitles' });
    const li = (await within(panel).findByText('Heat (1995)/Heat.en.forced.srt')).closest('li');
    await userEvent.click(within(li).getByRole('button', { name: /Show the cues/ }));

    expect(await within(li).findByText(/Couldn.t load the cues/)).toBeInTheDocument();
    fail = false;
    await userEvent.click(within(li).getByRole('button', { name: 'Try again' }));
    expect(await within(li).findByRole('list', { name: /Cues in/ })).toBeInTheDocument();
  });
});

describe('Admin: Subtitles panel, timing and checking', () => {
  const open = async (options) => {
    const served = await openPanel(options);
    const li = (await within(served.panel).findByText('Heat (1995)/Heat.en.forced.srt')).closest(
      'li',
    );
    await userEvent.click(within(li).getByRole('button', { name: /Show the cues/ }));
    await within(li).findByRole('list', { name: /Cues in/ });
    return { ...served, li };
  };

  it('each nudge adds its amount to the offset and the new offset is shown and the cues read again', async () => {
    const { li, state, fetchMock } = await open({ items: [file({ offsetMs: 1000 })] });
    const timing = within(li).getByRole('group', { name: 'Timing' });
    expect(within(timing).getByRole('status')).toHaveTextContent('+1 s');

    await userEvent.click(
      within(timing).getByRole('button', { name: 'Show lines 0.25 seconds later' }),
    );
    await waitFor(() => expect(within(timing).getByRole('status')).toHaveTextContent('+1.25 s'));
    await userEvent.click(
      within(timing).getByRole('button', { name: 'Show lines 1 second earlier' }),
    );
    await waitFor(() => expect(within(timing).getByRole('status')).toHaveTextContent('+0.25 s'));
    await userEvent.click(
      within(timing).getByRole('button', { name: 'Show lines 0.25 seconds earlier' }),
    );
    await waitFor(() =>
      expect(within(timing).getByRole('status')).toHaveTextContent('No timing change'),
    );
    await userEvent.click(
      within(timing).getByRole('button', { name: 'Show lines 1 second later' }),
    );
    await waitFor(() => expect(within(timing).getByRole('status')).toHaveTextContent('+1 s'));

    expect(state.calls.map((c) => c.body)).toEqual([
      { offsetMs: 1250 },
      { offsetMs: 250 },
      { offsetMs: 0 },
      { offsetMs: 1000 },
    ]);
    expect(state.calls.every((c) => c.method === 'PUT' && c.action === 'offset')).toBe(true);
    const cueReads = fetchMock.mock.calls.filter((c) => c[0] === '/api/admin/subtitles/1/cues');
    expect(cueReads.length).toBeGreaterThanOrEqual(5);
  });

  it('Reset timing sets the offset to 0 and is disabled when it already is', async () => {
    const { li, state } = await open({ items: [file({ offsetMs: -2500 })] });
    const reset = within(li).getByRole('button', { name: 'Reset timing' });
    expect(within(li).getByText('−2.5 s')).toBeInTheDocument();

    await userEvent.click(reset);

    await waitFor(() => expect(within(li).getByText('No timing change')).toBeInTheDocument());
    expect(state.calls).toEqual([
      { method: 'PUT', id: 1, action: 'offset', body: { offsetMs: 0 } },
    ]);
    expect(reset).toBeDisabled();
  });

  it('Looks right checks the file and Undo takes the check away', async () => {
    const { li, state } = await open();

    await userEvent.click(within(li).getByRole('button', { name: 'Looks right' }));

    expect(await within(li).findByText('You checked this: the lines look right.')).toBeVisible();
    expect(within(li).getByText('Checked')).toBeInTheDocument();
    await userEvent.click(within(li).getByRole('button', { name: 'Undo' }));
    expect(await within(li).findByRole('button', { name: 'Looks right' })).toBeInTheDocument();
    expect(within(li).queryByText('Checked')).not.toBeInTheDocument();
    expect(state.calls.map((c) => [c.method, c.action])).toEqual([
      ['POST', 'verify'],
      ['DELETE', 'verify'],
    ]);
  });

  it('shows the server’s message when an offset is refused, and a general one when the network fails', async () => {
    const { li } = await open({
      fail: {
        offset: () => respond({ error: 'The offset must be within 10 minutes either way' }, 400),
      },
    });

    await userEvent.click(within(li).getByRole('button', { name: 'Show lines 1 second later' }));

    expect(await within(li).findByRole('alert')).toHaveTextContent(
      'The offset must be within 10 minutes either way',
    );
    expect(within(li).getByText('No timing change')).toBeInTheDocument();
  });

  it('shows that a file is gone when an action finds it missing (404)', async () => {
    const { li } = await open({
      fail: { verify: () => respond({ error: 'No such subtitle' }, 404) },
    });

    await userEvent.click(within(li).getByRole('button', { name: 'Looks right' }));

    expect(await within(li).findByRole('alert')).toHaveTextContent(
      'That is no longer there. Reload the list and look again.',
    );
  });

  it('shows a general message for any other failure', async () => {
    const { li } = await open({ fail: { verify: () => respond(null, 500) } });

    await userEvent.click(within(li).getByRole('button', { name: 'Looks right' }));

    expect(await within(li).findByRole('alert')).toHaveTextContent(
      'Something went wrong. Try again.',
    );
  });
});

describe('Admin: Subtitles panel, one request at a time', () => {
  it('disables every row’s actions while one row’s request is pending, then enables them again', async () => {
    let release;
    const pending = new Promise((resolve) => {
      release = resolve;
    });
    const items = [
      file({ id: 1, path: 'A/One.en.forced.srt' }),
      file({ id: 2, path: 'B/Two.en.forced.srt' }),
    ];
    installFetch((method, url, init) => {
      if (url === '/api/admin/subtitles') return respond(items);
      if (/\/cues$/.test(url)) return respond(CUES);
      if (url === '/api/admin/subtitles/1/offset') {
        return pending.then(() =>
          respond({ ...items[0], offsetMs: JSON.parse(init.body).offsetMs }),
        );
      }
      return null;
    });
    renderApp('/admin');
    const panel = await screen.findByRole('region', { name: 'Subtitles' });
    const rowOne = (await within(panel).findByText('A/One.en.forced.srt')).closest('li');
    const rowTwo = within(panel).getByText('B/Two.en.forced.srt').closest('li');
    for (const row of [rowOne, rowTwo]) {
      await userEvent.click(within(row).getByRole('button', { name: /Show the cues/ }));
      await within(row).findByRole('group', { name: 'Timing' });
    }
    const nudgeTwo = within(rowTwo).getByRole('button', { name: 'Show lines 1 second later' });
    expect(nudgeTwo).toBeEnabled();

    await userEvent.click(
      within(rowOne).getByRole('button', { name: 'Show lines 1 second later' }),
    );

    expect(nudgeTwo).toBeDisabled();
    expect(within(rowTwo).getByRole('button', { name: 'Looks right' })).toBeDisabled();
    expect(within(rowTwo).getByRole('button', { name: /^Move/ })).toBeDisabled();
    release();
    await waitFor(() => expect(nudgeTwo).toBeEnabled());
  });
});

describe('Admin: Subtitles panel, quick nudges', () => {
  it('a second nudge starts from the first one’s answer while the refreshed list has not arrived', async () => {
    const bodies = [];
    let lists = 0;
    installFetch((method, url, init) => {
      if (url === '/api/admin/subtitles') {
        lists += 1;
        return lists === 1 ? respond([file()]) : new Promise(() => {}); // the refresh is slow
      }
      if (url === '/api/admin/subtitles/1/offset') {
        const body = JSON.parse(init.body);
        bodies.push(body);
        return respond(file({ offsetMs: body.offsetMs }));
      }
      if (url === '/api/admin/subtitles/1/cues') return respond(CUES);
      return null;
    });
    renderApp('/admin');
    const panel = await screen.findByRole('region', { name: 'Subtitles' });
    const li = (await within(panel).findByText('Heat (1995)/Heat.en.forced.srt')).closest('li');
    await userEvent.click(within(li).getByRole('button', { name: /Show the cues/ }));
    const timing = await within(li).findByRole('group', { name: 'Timing' });

    await userEvent.click(
      within(timing).getByRole('button', { name: 'Show lines 1 second later' }),
    );
    await waitFor(() => expect(within(timing).getByRole('status')).toHaveTextContent('+1 s'));
    await userEvent.click(
      within(timing).getByRole('button', { name: 'Show lines 1 second later' }),
    );

    await waitFor(() => expect(bodies).toEqual([{ offsetMs: 1000 }, { offsetMs: 2000 }]));
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
    renderApp('/admin');
    const panel = await screen.findByRole('region', { name: 'Subtitles' });
    expect(await within(panel).findByText('Heat (1995)/Heat.en.forced.srt')).toBeInTheDocument();

    await act(() => vi.advanceTimersByTimeAsync(1500));

    expect(
      await within(panel).findByText(/Couldn.t refresh the subtitle files/),
    ).toBeInTheDocument();
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

describe('Admin: Subtitles panel, cues that fail to refresh', () => {
  it('shows the error and Try again after a nudge, hides the old cues and their links, and recovers on retry', async () => {
    let offset = 0;
    let failCues = false;
    installFetch((method, url, init) => {
      if (url === '/api/admin/subtitles') return respond([file({ offsetMs: offset })]);
      if (url === '/api/admin/subtitles/1/offset') {
        offset = JSON.parse(init.body).offsetMs;
        failCues = true;
        return respond(file({ offsetMs: offset }));
      }
      if (url === '/api/admin/subtitles/1/cues') {
        return failCues
          ? respond(null, 500)
          : respond({ offsetMs: 0, cues: [{ startMs: 10000, endMs: 12000, text: 'Hello' }] });
      }
      return null;
    });
    renderApp('/admin');
    const panel = await screen.findByRole('region', { name: 'Subtitles' });
    const li = (await within(panel).findByText('Heat (1995)/Heat.en.forced.srt')).closest('li');
    await userEvent.click(within(li).getByRole('button', { name: /Show the cues/ }));
    expect(await within(li).findByRole('link', { name: /Play from here/ })).toBeInTheDocument();

    await userEvent.click(within(li).getByRole('button', { name: 'Show lines 1 second later' }));

    expect(await within(li).findByText(/Couldn.t load the cues/)).toBeInTheDocument();
    expect(within(li).queryByRole('link', { name: /Play from here/ })).not.toBeInTheDocument();
    failCues = false;
    await userEvent.click(within(li).getByRole('button', { name: 'Try again' }));
    expect(await within(li).findByRole('link', { name: /Play from here/ })).toBeInTheDocument();
    expect(within(li).queryByText(/Couldn.t load the cues/)).not.toBeInTheDocument();
  });
});

describe('Admin: Subtitles panel, cues after a scan changes the file', () => {
  it('reads the open cues again when the row’s version changes though its attachment and offset do not', async () => {
    let scans = 0;
    let changed = false;
    installFetch((method, url) => {
      if (url === '/api/admin/scan') {
        scans += 1;
        if (scans > 1) changed = true;
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
        return respond([file({ version: changed ? 1700000099999 : 1700000000000 })]);
      }
      if (url === '/api/admin/subtitles/1/cues') {
        return respond({
          offsetMs: 0,
          cues: [{ startMs: 1000, endMs: 2000, text: changed ? 'New text' : 'Old text' }],
        });
      }
      return null;
    });
    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderApp('/admin');
    const panel = await screen.findByRole('region', { name: 'Subtitles' });
    const li = (await within(panel).findByText('Heat (1995)/Heat.en.forced.srt')).closest('li');
    await userEvent.click(within(li).getByRole('button', { name: /Show the cues/ }));
    expect(await within(li).findByText('Old text')).toBeInTheDocument();

    await act(() => vi.advanceTimersByTimeAsync(1500));

    expect(await within(panel).findByText('New text')).toBeInTheDocument();
  });
});

describe('Admin: Subtitles panel, cues after a re-attach', () => {
  it('reads the open cue list again when the file is attached to another part, so Play from here is not stale', async () => {
    let part = 1;
    const base = file({ parts: 2, part: 1, ownerAttached: true });
    installFetch((method, url) => {
      if (url === '/api/admin/subtitles') return respond([{ ...base, part }]);
      if (url === '/api/admin/subtitles/1/cues') {
        return respond({
          offsetMs: 0,
          cues: [{ startMs: part === 1 ? 10000 : 3610000, endMs: 12000, text: 'Hello' }],
        });
      }
      if (url === '/api/admin/subtitles/1/attach') {
        part = 2;
        return respond({});
      }
      return null;
    });
    renderApp('/admin');
    const panel = await screen.findByRole('region', { name: 'Subtitles' });
    const li = (await within(panel).findByText('Heat (1995)/Heat.en.forced.srt')).closest('li');
    await userEvent.click(within(li).getByRole('button', { name: /Show the cues/ }));
    expect(await within(li).findByRole('link', { name: /Play from here/ })).toHaveAttribute(
      'href',
      '/films/1/play?edition=11&at=7000',
    );

    await userEvent.click(within(li).getByRole('button', { name: /^Move/ }));
    await userEvent.type(within(li).getByLabelText('Find a film'), 'heat');
    await userEvent.click(await within(li).findByRole('radio', { name: 'Heat (1995)' }));
    await waitFor(() => expect(within(li).getByRole('button', { name: 'Attach' })).toBeEnabled());
    await userEvent.click(within(li).getByRole('button', { name: 'Attach' }));

    await waitFor(() =>
      expect(within(panel).getByRole('link', { name: /Play from here/ })).toHaveAttribute(
        'href',
        '/films/1/play?edition=11&at=3607000',
      ),
    );
  });
});

describe('Admin: Subtitles panel, attaching', () => {
  const openUnmatched = async (options = {}) => {
    const served = await openPanel({
      items: [
        file({
          id: 2,
          path: 'The Lord of the Rings - The Two Towers.en.forced.srt',
          state: 'unmatched',
          filmId: null,
          filmTitle: null,
          editionId: null,
          editionName: null,
          parts: 0,
          part: null,
        }),
      ],
      ...options,
    });
    const li = (
      await within(served.panel).findByText('The Lord of the Rings - The Two Towers.en.forced.srt')
    ).closest('li');
    return { ...served, li };
  };

  const towers = {
    ...fixtures.heat,
    id: 5,
    title: 'The Two Towers',
    year: 2002,
    editions: [
      { ...fixtures.heat.editions[0], id: 51, name: 'Theatrical Cut', parts: 1 },
      { ...fixtures.heat.editions[0], id: 52, name: 'Extended Edition', parts: 2 },
    ],
  };
  const withFilms = (extra) => (method, url, init) => {
    if (url.startsWith('/api/films?q=two')) {
      return respond({
        total: 1,
        films: [{ id: 5, title: 'The Two Towers', year: 2002 }],
        facets: { genres: [], decades: [] },
      });
    }
    if (url === '/api/films/5') return respond(towers);
    return extra ? extra(method, url, init) : null;
  };

  it('Attach to… opens a form and Cancel closes it', async () => {
    const { li } = await openUnmatched();
    const open = within(li).getByRole('button', { name: /^Attach .* to a film$/ });
    expect(open).toHaveAttribute('aria-expanded', 'false');

    await userEvent.click(open);

    const form = within(li).getByRole('form', {
      name: 'Attach The Lord of the Rings - The Two Towers.en.forced.srt to a film',
    });
    expect(open).toHaveAttribute('aria-expanded', 'true');
    expect(within(form).getByRole('button', { name: 'Attach' })).toBeDisabled();
    await userEvent.click(within(form).getByRole('button', { name: 'Cancel' }));
    expect(within(li).queryByRole('form')).not.toBeInTheDocument();
    expect(open).toHaveAttribute('aria-expanded', 'false');
  });

  it('a film with one edition is attached to the whole film: find it, pick it, Attach', async () => {
    const { li, panel, state } = await openUnmatched();
    await userEvent.click(within(li).getByRole('button', { name: /^Attach .* to a film$/ }));
    const form = within(li).getByRole('form');

    await userEvent.type(within(form).getByLabelText('Find a film'), 'heat');
    await userEvent.click(await within(form).findByRole('radio', { name: 'Heat (1995)' }));
    await waitFor(() => expect(within(form).getByRole('button', { name: 'Attach' })).toBeEnabled());
    expect(within(form).queryByRole('group', { name: 'Edition' })).not.toBeInTheDocument();
    await userEvent.click(within(form).getByRole('button', { name: 'Attach' }));

    await waitFor(() =>
      expect(state.calls).toEqual([
        { method: 'POST', id: 2, action: 'attach', body: { editionId: 11, part: null } },
      ]),
    );
    // The file moves from Unmatched to Matched, so it is a new row.
    expect(await within(panel).findByText('Heat · Theatrical Cut')).toBeInTheDocument();
    expect(within(panel).queryByRole('form')).not.toBeInTheDocument();
    expect(within(panel).getByRole('button', { name: /Move .* to another film/ })).toBeVisible();
    expect(
      within(panel).getByRole('button', { name: /Give .* back to automatic matching/ }),
    ).toBeVisible();
  });

  const openSplit = async () => {
    const served = serve({
      items: [file({ id: 2, path: 'T.en.forced.srt', state: 'unmatched', filmId: null, parts: 0 })],
    });
    installFetch(withFilms(served.route));
    renderApp('/admin');
    const panel = await screen.findByRole('region', { name: 'Subtitles' });
    const li = (await within(panel).findByText('T.en.forced.srt')).closest('li');
    await userEvent.click(within(li).getByRole('button', { name: /^Attach/ }));
    const form = within(li).getByRole('form');
    await userEvent.type(within(form).getByLabelText('Find a film'), 'two');
    await userEvent.click(
      await within(form).findByRole('radio', { name: 'The Two Towers (2002)' }),
    );
    const editions = await within(form).findByRole('group', { name: 'Edition' });
    return { ...served, li, form, editions };
  };

  it('a film with several editions asks which one, and a split edition asks whole film or part', async () => {
    const { form, editions, state } = await openSplit();
    expect(
      within(editions)
        .getAllByRole('radio')
        .map((r) => r.labels[0].textContent),
    ).toEqual(['Theatrical Cut', 'Extended Edition · 2 files']);
    expect(within(form).getByRole('button', { name: 'Attach' })).toBeDisabled();

    await userEvent.click(within(editions).getByRole('radio', { name: 'Theatrical Cut' }));
    expect(within(form).queryByRole('group', { name: 'Timed for' })).not.toBeInTheDocument();
    await userEvent.click(
      within(editions).getByRole('radio', { name: 'Extended Edition · 2 files' }),
    );
    const timed = within(form).getByRole('group', { name: 'Timed for' });
    expect(
      within(timed)
        .getAllByRole('radio')
        .map((r) => r.labels[0].textContent),
    ).toEqual(['Whole film', 'Part 1', 'Part 2']);
    expect(within(timed).getByRole('radio', { name: 'Whole film' })).toBeChecked();

    await userEvent.click(within(timed).getByRole('radio', { name: 'Part 2' }));
    await userEvent.click(within(form).getByRole('button', { name: 'Attach' }));

    await waitFor(() =>
      expect(state.calls).toEqual([
        { method: 'POST', id: 2, action: 'attach', body: { editionId: 52, part: 2 } },
      ]),
    );
  });

  it('attaches a split edition to the whole film unless a part is picked', async () => {
    const { form, editions, state } = await openSplit();
    await userEvent.click(
      within(editions).getByRole('radio', { name: 'Extended Edition · 2 files' }),
    );

    await userEvent.click(within(form).getByRole('button', { name: 'Attach' }));

    await waitFor(() =>
      expect(state.calls).toEqual([
        { method: 'POST', id: 2, action: 'attach', body: { editionId: 52, part: null } },
      ]),
    );
  });

  it('switching edition forgets the part picked for the other one', async () => {
    const { form, editions, state } = await openSplit();
    await userEvent.click(
      within(editions).getByRole('radio', { name: 'Extended Edition · 2 files' }),
    );
    await userEvent.click(
      within(within(form).getByRole('group', { name: 'Timed for' })).getByRole('radio', {
        name: 'Part 1',
      }),
    );
    await userEvent.click(within(editions).getByRole('radio', { name: 'Theatrical Cut' }));
    await userEvent.click(
      within(editions).getByRole('radio', { name: 'Extended Edition · 2 files' }),
    );

    expect(
      within(within(form).getByRole('group', { name: 'Timed for' })).getByRole('radio', {
        name: 'Whole film',
      }),
    ).toBeChecked();
    expect(state.calls).toEqual([]);
  });

  it('says when a film’s editions could not be loaded and loads them on Try again', async () => {
    let fail = true;
    const { route } = serve({
      items: [file({ id: 2, path: 'T.en.forced.srt', state: 'unmatched', filmId: null, parts: 0 })],
    });
    installFetch(
      withFilms((method, url, init) => (url === '/api/films/5' ? null : route(method, url, init))),
    );
    installFetch((method, url, init) => {
      if (url === '/api/films/5' && fail) return respond(null, 500);
      return withFilms(route)(method, url, init);
    });
    renderApp('/admin');
    const panel = await screen.findByRole('region', { name: 'Subtitles' });
    const li = (await within(panel).findByText('T.en.forced.srt')).closest('li');
    await userEvent.click(within(li).getByRole('button', { name: /^Attach/ }));
    const form = within(li).getByRole('form');
    await userEvent.type(within(form).getByLabelText('Find a film'), 'two');
    await userEvent.click(
      await within(form).findByRole('radio', { name: 'The Two Towers (2002)' }),
    );

    expect(await within(form).findByRole('alert')).toHaveTextContent(/editions/);
    expect(within(form).getByRole('button', { name: 'Attach' })).toBeDisabled();
    fail = false;
    await userEvent.click(within(form).getByRole('button', { name: 'Try again' }));

    expect(await within(form).findByRole('group', { name: 'Edition' })).toBeInTheDocument();
    expect(within(form).queryByRole('alert')).not.toBeInTheDocument();
  });

  it('changing the search starts again: the picked film and edition are forgotten', async () => {
    const { route } = serve({
      items: [file({ id: 2, path: 'T.en.forced.srt', state: 'unmatched', filmId: null, parts: 0 })],
    });
    installFetch(withFilms(route));
    renderApp('/admin');
    const panel = await screen.findByRole('region', { name: 'Subtitles' });
    const li = (await within(panel).findByText('T.en.forced.srt')).closest('li');
    await userEvent.click(within(li).getByRole('button', { name: /^Attach/ }));
    const form = within(li).getByRole('form');
    await userEvent.type(within(form).getByLabelText('Find a film'), 'two');
    await userEvent.click(
      await within(form).findByRole('radio', { name: 'The Two Towers (2002)' }),
    );
    await within(form).findByRole('group', { name: 'Edition' });

    await userEvent.type(within(form).getByLabelText('Find a film'), 's');

    expect(within(form).queryByRole('group', { name: 'Edition' })).not.toBeInTheDocument();
    expect(within(form).getByRole('button', { name: 'Attach' })).toBeDisabled();
  });

  it('asks nothing of the library for a search that is too short', async () => {
    const { li, fetchMock } = await openUnmatched();
    vi.useFakeTimers({ shouldAdvanceTime: true });
    await userEvent.click(within(li).getByRole('button', { name: /^Attach/ }));

    await userEvent.type(within(li).getByLabelText('Find a film'), 'h');
    await act(() => vi.advanceTimersByTimeAsync(400)); // past the search debounce

    expect(fetchMock.mock.calls.some((c) => String(c[0]).startsWith('/api/films?'))).toBe(false);
    expect(within(li).queryByRole('radio')).not.toBeInTheDocument();
    expect(within(li).queryByText('No film by that name.')).not.toBeInTheDocument();
  });

  it('says when the library has no film by that name', async () => {
    const { li } = await openUnmatched();
    await userEvent.click(within(li).getByRole('button', { name: /^Attach/ }));

    await userEvent.type(within(li).getByLabelText('Find a film'), 'zzz');

    expect(await within(li).findByText('No film by that name.')).toBeInTheDocument();
  });

  it('says when the search failed and searches again on Try again', async () => {
    let fail = true;
    const served = await openUnmatched();
    installFetch((method, url, init) =>
      url.startsWith('/api/films?') && fail ? respond(null, 500) : served.route(method, url, init),
    );
    await userEvent.click(within(served.li).getByRole('button', { name: /^Attach/ }));

    await userEvent.type(within(served.li).getByLabelText('Find a film'), 'heat');

    expect(await within(served.li).findByRole('alert')).toHaveTextContent(/search the library/);
    fail = false;
    await userEvent.click(within(served.li).getByRole('button', { name: 'Try again' }));
    expect(
      await within(served.li).findByRole('radio', { name: 'Heat (1995)' }),
    ).toBeInTheDocument();
  });

  it('shows the server’s message and keeps the form open when the attach is refused', async () => {
    const { li } = await openUnmatched({
      fail: { attach: () => respond({ error: 'That edition has no film file' }, 400) },
    });
    await userEvent.click(within(li).getByRole('button', { name: /^Attach/ }));
    await userEvent.type(within(li).getByLabelText('Find a film'), 'heat');
    await userEvent.click(await within(li).findByRole('radio', { name: 'Heat (1995)' }));
    await waitFor(() => expect(within(li).getByRole('button', { name: 'Attach' })).toBeEnabled());

    await userEvent.click(within(li).getByRole('button', { name: 'Attach' }));

    expect(await within(li).findByText('That edition has no film file')).toBeInTheDocument();
    expect(within(li).getByRole('form')).toBeInTheDocument();
  });

  it('Back to automatic gives a hand-attached file back to matching by name', async () => {
    const { panel, state } = await openPanel({ items: [file({ ownerAttached: true })] });
    const li = (await within(panel).findByText('Heat (1995)/Heat.en.forced.srt')).closest('li');

    await userEvent.click(
      within(li).getByRole('button', {
        name: /Give Heat.en.forced.srt back to automatic matching/,
      }),
    );

    await waitFor(() =>
      expect(
        within(li).queryByRole('button', { name: /back to automatic/ }),
      ).not.toBeInTheDocument(),
    );
    expect(state.calls).toEqual([{ method: 'POST', id: 1, action: 'detach', body: null }]);
  });

  it('a file the owner did not attach has no Back to automatic', async () => {
    const { panel } = await openPanel();
    await within(panel).findByText('Heat (1995)/Heat.en.forced.srt');
    expect(
      within(panel).queryByRole('button', { name: /back to automatic/ }),
    ).not.toBeInTheDocument();
  });
});
