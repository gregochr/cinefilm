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
    servedReason: 'Offered in the Subtitles menu as English (full)',
    ...over,
  });

// A stateful fake of the subtitle routes that records the actions.
function serve(items) {
  const state = { items: structuredClone(items), calls: [] };
  const route = (method, url, init) => {
    const path = new URL(url, 'http://test.local').pathname;
    if (path === '/api/admin/subtitles') return respond(state.items);
    const match = path.match(/^\/api\/admin\/subtitles\/(\d+)\/(cues|offset)$/);
    if (!match) return null;
    const item = state.items.find((i) => i.id === Number(match[1]));
    if (match[2] === 'cues') return respond({ offsetMs: item.offsetMs, cues: [] });
    const body = JSON.parse(init.body);
    state.calls.push({ method, id: item.id, action: match[2], body });
    item.offsetMs = body.offsetMs;
    return respond(item);
  };
  return { state, route };
}

async function openPanel(items) {
  const served = serve(items);
  installFetch(served.route);
  renderApp('/admin');
  const panel = await screen.findByRole('region', { name: 'Subtitles' });
  return { ...served, panel };
}

describe('Admin: Subtitles panel, full subtitle files', () => {
  it('lists a full file with a Full label and the server’s sentence, and no Playing tag', async () => {
    const { panel } = await openPanel([full()]);

    const item = (await within(panel).findByText('Heat (1995)/Heat.en.srt')).closest('li');
    expect(within(item).getByText('Full')).toBeInTheDocument();
    expect(
      within(item).getByText('Offered in the Subtitles menu as English (full)'),
    ).toBeInTheDocument();
    expect(within(item).queryByText('Playing')).not.toBeInTheDocument();
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
