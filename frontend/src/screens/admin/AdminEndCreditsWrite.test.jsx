import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp, respond } from '../../test/mockApi.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
});

const PATH = '/admin/library/credits';
const creditsCalls = (fetchMock) =>
  fetchMock.mock.calls.filter(([url]) => String(url).startsWith('/api/admin/credits'));

/**
 * A server that keeps the list: `state.rows` is what GET answers (25 a page); a write calls `state.after(id)` to
 * change them, and answers `state.fail` (a Response) instead when that is set, or waits for `state.gate`.
 */
function writable(rows, { gate = null } = {}) {
  const state = { rows, after: () => {}, fail: null, gate, writes: [] };
  const fetchMock = installFetch((method, url, init) => {
    if (String(url).startsWith('/api/admin/credits')) {
      const page = Number(new URL(url, 'http://test.local').searchParams.get('page'));
      const items = state.rows.slice(page * 25, page * 25 + 25);
      const counts = { none: state.rows.length, plex: 1, owner: 1, all: state.rows.length + 2 };
      return respond({ counts, page, size: 25, total: state.rows.length, items });
    }
    const match = /^\/api\/admin\/editions\/(\d+)\/credits$/.exec(url);
    if (!match) return null;
    state.writes.push(`${method} ${init.body ?? ''}`.trim());
    const answer = (response) => {
      if (response) return response;
      state.after(Number(match[1]));
      return respond(fixtures.adminCredits.items[0]);
    };
    return state.gate ? state.gate.then(answer) : answer(state.fail);
  });
  return { state, fetchMock };
}

const row = (n, over = {}) => ({
  ...structuredClone(fixtures.adminCredits.items[0]),
  editionId: 100 + n,
  filmId: 100 + n,
  title: `Film ${n}`,
  ...over,
});
const without = (state, id) => {
  state.rows = state.rows.filter((r) => r.editionId !== id);
};
const toast = () => screen.getByRole('status', { name: 'Notification' });
const rowOf = (title) =>
  screen.getAllByRole('listitem').find((item) => within(item).queryByText(title));
const lookButton = (title) => within(rowOf(title)).getByRole('button', { name: 'Look and set…' });
const open = async (title) => {
  await userEvent.click(lookButton(title));
  return within(rowOf(title));
};
const held = () => {
  let release;
  const gate = new Promise((resolve) => {
    release = resolve;
  });
  return { gate, release };
};
const typeTime = async (panel, text) => {
  const field = panel.getByLabelText('End credits start at');
  await userEvent.clear(field);
  await userEvent.type(field, text);
  return field;
};

describe('End credits list: Set and Clear', () => {
  it('sets the typed time, says so in the toast, reads the list and counts again, and focuses the next row', async () => {
    const { state, fetchMock } = writable([row(1), row(2), row(3)]);
    state.after = (id) => without(state, id);
    renderApp(PATH);
    await screen.findByText('Film 1');
    const panel = await open('Film 2');
    await typeTime(panel, '1:52:10');
    const reads = creditsCalls(fetchMock).length;

    await userEvent.click(panel.getByRole('button', { name: 'Set this time' }));

    expect(await within(toast()).findByText('End credits set')).toBeInTheDocument();
    expect(state.writes).toEqual(['PUT {"markerMs":6730000}']);
    await waitFor(() => expect(screen.queryByText('Film 2')).toBeNull());
    expect(creditsCalls(fetchMock)).toHaveLength(reads + 1);
    expect(screen.getByRole('tab', { name: 'Needs a look 2' })).toBeInTheDocument();
    expect(screen.queryByRole('group')).toBeNull();
    await waitFor(() => expect(lookButton('Film 3')).toHaveFocus());
  });

  it('focuses the row’s own button when the row is still on the page', async () => {
    const { state } = writable([row(1), row(2)]);
    state.after = (id) => {
      state.rows = state.rows.map((r) => (r.editionId === id ? { ...r, source: 'OWNER' } : r));
    };
    renderApp(PATH);
    await screen.findByText('Film 1');
    const panel = await open('Film 1');

    await userEvent.click(panel.getByRole('button', { name: 'Set this time' }));

    await within(toast()).findByText('End credits set');
    await waitFor(() => expect(lookButton('Film 1')).toHaveFocus());
  });

  it('focuses the previous row’s button when the last row leaves', async () => {
    const { state } = writable([row(1), row(2)]);
    state.after = (id) => without(state, id);
    renderApp(PATH);
    await screen.findByText('Film 1');
    const panel = await open('Film 2');

    await userEvent.click(panel.getByRole('button', { name: 'Set this time' }));

    await waitFor(() => expect(screen.queryByText('Film 2')).toBeNull());
    await waitFor(() => expect(lookButton('Film 1')).toHaveFocus());
  });

  it('steps the page back when its last row leaves, and focuses the new page’s last row', async () => {
    const { state, fetchMock } = writable(Array.from({ length: 26 }, (_, i) => row(i + 1)));
    state.after = (id) => without(state, id);
    renderApp(PATH);
    await screen.findByText('Film 1');
    await userEvent.click(screen.getByRole('button', { name: 'Next' }));
    await screen.findByText('Film 26');
    const panel = await open('Film 26');

    await userEvent.click(panel.getByRole('button', { name: 'Set this time' }));

    await within(toast()).findByText('End credits set');
    expect(await screen.findByText('Showing 1–25 of 25')).toBeInTheDocument();
    await waitFor(() => expect(lookButton('Film 25')).toHaveFocus());
    expect(String(creditsCalls(fetchMock).at(-1)[0])).toBe('/api/admin/credits?filter=none&page=0');
  });

  it('moves focus to the section title when no row is left', async () => {
    const { state } = writable([row(1)]);
    state.after = (id) => without(state, id);
    renderApp(PATH);
    await screen.findByText('Film 1');
    const panel = await open('Film 1');

    await userEvent.click(panel.getByRole('button', { name: 'Set this time' }));

    expect(await screen.findByText('No films here')).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1, name: 'End credits' })).toHaveFocus(),
    );
  });

  it('shows Clear my time only on a Yours row, and clears with the toast', async () => {
    const { state } = writable([row(1), row(2, { source: 'OWNER', markerMs: 6730000 })]);
    state.after = (id) => without(state, id);
    renderApp(PATH);
    await screen.findByText('Film 1');
    expect((await open('Film 1')).queryByRole('button', { name: 'Clear my time' })).toBeNull();
    const panel = await open('Film 2');
    expect(panel.getByRole('button', { name: 'Clear my time' })).toHaveAccessibleDescription(
      'Goes back to Plex’s marker, or none',
    );

    await userEvent.click(panel.getByRole('button', { name: 'Clear my time' }));

    await within(toast()).findByText('Your time cleared');
    expect(state.writes).toEqual(['DELETE']);
    await waitFor(() => expect(lookButton('Film 1')).toHaveFocus());
  });

  it('shows the server’s words for a refused time, and keeps the panel open with the typed time', async () => {
    const { state } = writable([row(1)]);
    state.fail = respond({ error: 'That time is outside where end credits can start' }, 400);
    renderApp(PATH);
    await screen.findByText('Film 1');
    const panel = await open('Film 1');
    const field = await typeTime(panel, '1:50:00');

    await userEvent.click(panel.getByRole('button', { name: 'Set this time' }));

    expect(await panel.findByRole('alert')).toHaveTextContent(
      'That time is outside where end credits can start',
    );
    expect(field).toHaveValue('1:50:00');
    expect(field).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByRole('group', { name: 'Stills for Film 1' })).toBeInTheDocument();
    expect(within(toast()).queryByText('End credits set')).toBeNull();
    // Editing the time takes the message away.
    await userEvent.type(field, '1');
    expect(panel.queryByRole('alert')).toBeNull();
  });

  it('shows the lock holder on a 409, and its own words for the bare scan line', async () => {
    const { state } = writable([row(1)]);
    state.fail = respond({ error: 'A Plex import is running' }, 409);
    renderApp(PATH);
    await screen.findByText('Film 1');
    const panel = await open('Film 1');
    const field = await typeTime(panel, '1:50:00');

    await userEvent.click(panel.getByRole('button', { name: 'Set this time' }));

    expect(await panel.findByRole('alert')).toHaveTextContent(/^A Plex import is running\.$/);
    expect(field).toHaveValue('1:50:00');
    expect(field).not.toHaveAttribute('aria-invalid');

    state.fail = respond({ error: 'A scan is running' }, 409);
    await userEvent.click(panel.getByRole('button', { name: 'Set this time' }));
    await waitFor(() =>
      expect(panel.getByRole('alert')).toHaveTextContent(
        'A scan or import is running. Try again when it finishes.',
      ),
    );
  });

  it('says it could not save when the write fails some other way, and keeps the panel', async () => {
    const { state } = writable([row(1)]);
    state.fail = respond(null, 500);
    renderApp(PATH);
    await screen.findByText('Film 1');
    const panel = await open('Film 1');

    await userEvent.click(panel.getByRole('button', { name: 'Set this time' }));

    expect(await panel.findByRole('alert')).toHaveTextContent(
      'Couldn’t save that time. Try again.',
    );
    expect(screen.getByRole('group', { name: 'Stills for Film 1' })).toBeInTheDocument();
  });

  it('reads the list again when the edition is no longer there (404)', async () => {
    const { state, fetchMock } = writable([row(1), row(2)]);
    state.fail = respond({ error: 'No such edition' }, 404);
    renderApp(PATH);
    await screen.findByText('Film 1');
    const panel = await open('Film 1');
    const reads = creditsCalls(fetchMock).length;
    state.rows = [row(2)];

    await userEvent.click(panel.getByRole('button', { name: 'Set this time' }));

    await waitFor(() => expect(screen.queryByText('Film 1')).toBeNull());
    expect(creditsCalls(fetchMock)).toHaveLength(reads + 1);
  });

  it('brings up the sign-in card on a 401, keeps the panel, and works again once signed in', async () => {
    let signedIn = false;
    const { state } = writable([row(1)]);
    const route = globalThis.fetch;
    vi.stubGlobal('fetch', async (url, init = {}) => {
      if (String(url) === '/api/session' && init.method === 'POST') {
        signedIn = JSON.parse(init.body).password === 'right';
        return respond(signedIn ? { authenticated: true } : null, signedIn ? 200 : 401);
      }
      if (String(url).includes('/editions/') && !signedIn) return respond(null, 401);
      return route(url, init);
    });
    renderApp(PATH);
    await screen.findByText('Film 1');
    const panel = await open('Film 1');

    await userEvent.click(panel.getByRole('button', { name: 'Set this time' }));

    const card = await screen.findByRole('dialog', { name: 'Signed out' });
    expect(state.writes).toEqual([]);
    expect(screen.getByRole('group', { name: 'Stills for Film 1' })).toBeInTheDocument();
    expect(within(toast()).queryByText('End credits set')).toBeNull();

    await userEvent.type(within(card).getByLabelText('Password'), 'right');
    await userEvent.click(within(card).getByRole('button', { name: 'Sign in' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Signed out' })).toBeNull());
    state.after = (id) => without(state, id);
    await userEvent.click(
      within(await screen.findByRole('group', { name: 'Stills for Film 1' })).getByRole('button', {
        name: 'Set this time',
      }),
    );

    await within(toast()).findByText('End credits set');
    expect(state.writes).toEqual(['PUT {"markerMs":6720000}']);
  });
});

describe('End credits list: a write that is already out', () => {
  it('sends one request for a second press of Set, and none for a Clear while a Set is out', async () => {
    const answer = held();
    const { state } = writable([row(1, { source: 'OWNER', markerMs: 6730000 })], {
      gate: answer.gate,
    });
    state.after = (id) => without(state, id);
    renderApp(PATH);
    await screen.findByText('Film 1');
    const panel = await open('Film 1');

    await userEvent.click(panel.getByRole('button', { name: 'Set this time' }));
    await userEvent.click(panel.getByRole('button', { name: 'Set this time' }));
    await userEvent.click(panel.getByRole('button', { name: 'Clear my time' }));

    expect(state.writes).toEqual(['PUT {"markerMs":6730000}']);
    expect(panel.getByRole('button', { name: 'Set this time' })).toHaveAttribute(
      'aria-disabled',
      'true',
    );
    await act(async () => answer.release(undefined));
    await within(toast()).findByText('End credits set');
    expect(state.writes).toHaveLength(1);
  });

  it('still reads the list and shows the toast when the panel is closed mid-request, and takes no focus', async () => {
    const answer = held();
    const { state, fetchMock } = writable([row(1), row(2)], { gate: answer.gate });
    renderApp(PATH);
    await screen.findByText('Film 1');
    const panel = await open('Film 1');
    await userEvent.click(panel.getByRole('button', { name: 'Set this time' }));
    const reads = creditsCalls(fetchMock).length;

    await userEvent.click(lookButton('Film 2'));
    expect(screen.queryByRole('group', { name: 'Stills for Film 1' })).toBeNull();
    state.after = (id) => without(state, id);
    await act(async () => answer.release(undefined));

    await within(toast()).findByText('End credits set');
    await waitFor(() => expect(screen.queryByText('Film 1')).toBeNull());
    expect(creditsCalls(fetchMock)).toHaveLength(reads + 1);
    expect(lookButton('Film 2')).toHaveFocus();
    expect(screen.getByRole('group', { name: 'Stills for Film 2' })).toBeInTheDocument();
  });

  it('leaves focus on the tab the owner moved to when the write ends after a tab change', async () => {
    const answer = held();
    const { state } = writable([row(1), row(2)], { gate: answer.gate });
    renderApp(PATH);
    await screen.findByText('Film 1');
    const panel = await open('Film 1');
    await userEvent.click(panel.getByRole('button', { name: 'Set this time' }));
    state.after = (id) => without(state, id);

    await userEvent.click(screen.getByRole('tab', { name: /^From Plex/ }));
    await act(async () => answer.release(undefined));

    await within(toast()).findByText('End credits set');
    await screen.findByRole('list', { name: 'End credits' });
    expect(screen.getByRole('tab', { name: /^From Plex/ })).toHaveFocus();
  });

  it('shows a refusal in the toast when the panel was closed before it came', async () => {
    const answer = held();
    const { state } = writable([row(1), row(2)], { gate: answer.gate });
    renderApp(PATH);
    await screen.findByText('Film 1');
    const panel = await open('Film 1');
    await userEvent.click(panel.getByRole('button', { name: 'Set this time' }));

    await userEvent.click(lookButton('Film 1'));
    await act(async () => answer.release(respond({ error: 'A Plex import is running' }, 409)));

    await within(toast()).findByText('A Plex import is running.');
    expect(state.writes).toHaveLength(1);
    expect(screen.queryByRole('group')).toBeNull();
  });

  it('does not show an old refusal when the panel is opened again', async () => {
    const { state } = writable([row(1)]);
    state.fail = respond({ error: 'A Plex import is running' }, 409);
    renderApp(PATH);
    await screen.findByText('Film 1');
    const panel = await open('Film 1');
    await userEvent.click(panel.getByRole('button', { name: 'Set this time' }));
    await panel.findByRole('alert');

    await userEvent.click(lookButton('Film 1'));
    await userEvent.click(lookButton('Film 1'));

    expect(screen.queryByRole('alert')).toBeNull();
  });
});
