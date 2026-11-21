import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp, respond } from '../../test/mockApi.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
});

const ATTENTION = '/api/admin/attention';
const LIST = '/api/admin/films/left-unmatched';
const PUT = '/api/admin/films/5/put-back';
const HOME_VIDEO = { id: 5, title: 'Home video', year: 1999 };
const HOLIDAY = { id: 6, title: 'Holiday', year: null };
// What To do lists for the film once it is back: a needs-match row like the fixture's.
const BACK_ROW = {
  ...fixtures.adminAttention.items.find((i) => i.filmId === 34),
  filmId: 5,
  title: 'Home video',
  year: 1999,
  files: ['Home video.m4v'],
};

/**
 * A tiny server: the left list, and the To do match list once a film has been put back. `put` answers the post; the
 * film leaves the left list only when it says 204.
 */
function server({ left = [HOME_VIDEO, HOLIDAY], put = () => respond(null, 204) } = {}) {
  const state = { left, back: false };
  const fetchMock = installFetch((m, url) => {
    if (url === LIST) return respond(state.left);
    if (url === ATTENTION && state.back) {
      return respond({ items: [...fixtures.adminAttention.items, BACK_ROW] });
    }
    if (m === 'POST' && url === PUT) {
      const answer = put();
      if (answer.status === 204) {
        state.left = state.left.filter((f) => f.id !== 5);
        state.back = true;
      }
      return answer;
    }
    return null;
  });
  return { fetchMock, state };
}

const sectionButton = (n) => screen.findByRole('button', { name: `Left unmatched (${n})` });
const putBacks = (fetchMock) =>
  fetchMock.mock.calls.filter(([url, init]) => url === PUT && init?.method === 'POST');

describe('Admin To do: Left unmatched', () => {
  it('draws nothing when no film was left unmatched', async () => {
    const { fetchMock } = server({ left: [] });
    renderApp('/admin');

    await screen.findByRole('region', { name: 'Pick the right film' });
    await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => url === LIST)).toBe(true));

    expect(screen.queryByRole('button', { name: /Left unmatched/ })).not.toBeInTheDocument();
  });

  it('is folded to begin with, and opens to the films with a Put back button each', async () => {
    server();
    renderApp('/admin');

    const toggle = await sectionButton(2);
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('button', { name: 'Put back' })).not.toBeInTheDocument();

    await userEvent.click(toggle);

    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    const region = screen.getByRole('region', { name: 'Left unmatched (2)' });
    expect(within(region).getByText('Home video')).toBeInTheDocument();
    expect(within(region).getByText('· 1999')).toBeInTheDocument();
    expect(within(region).getByText('Holiday')).toBeInTheDocument();
    expect(within(region).getAllByRole('button', { name: 'Put back' })).toHaveLength(2);

    await userEvent.click(toggle);
    expect(screen.queryByRole('button', { name: 'Put back' })).not.toBeInTheDocument();
  });

  it('puts a film back: it leaves the list, the count drops, focus stays on the heading and To do lists it', async () => {
    const { fetchMock } = server();
    renderApp('/admin');
    const toggle = await sectionButton(2);
    await userEvent.click(toggle);
    const region = screen.getByRole('region', { name: 'Left unmatched (2)' });
    const row = within(region)
      .getAllByRole('listitem')
      .find((li) => within(li).queryByText('Home video'));

    await userEvent.click(within(row).getByRole('button', { name: 'Put back' }));

    await waitFor(() =>
      expect(screen.getByRole('region', { name: 'Left unmatched (1)' })).toBeInTheDocument(),
    );
    expect(putBacks(fetchMock)).toHaveLength(1);
    expect(putBacks(fetchMock)[0][1].body).toBeUndefined();
    expect(screen.getByRole('button', { name: 'Left unmatched (1)' })).toHaveFocus();
    const pick = await screen.findByRole('region', { name: 'Pick the right film' });
    await waitFor(() => expect(within(pick).getByText('Home video')).toBeInTheDocument());
    expect(
      within(screen.getByRole('region', { name: 'Left unmatched (1)' })).queryByText('Home video'),
    ).not.toBeInTheDocument();
  });

  it('removes the section when the last film is put back, and focus goes to the page title', async () => {
    server({ left: [HOME_VIDEO] });
    renderApp('/admin');
    await userEvent.click(await sectionButton(1));

    await userEvent.click(screen.getByRole('button', { name: 'Put back' }));

    await waitFor(() =>
      expect(screen.queryByRole('button', { name: /Left unmatched/ })).not.toBeInTheDocument(),
    );
    expect(screen.getByRole('heading', { level: 1, name: 'To do' })).toHaveFocus();
  });

  it('does nothing on a second click while the post is in flight', async () => {
    let release;
    const { fetchMock } = server({ left: [HOME_VIDEO] });
    // Hold the answer: wrap the mock's POST so it waits for the test.
    const inner = fetchMock.getMockImplementation();
    const gate = new Promise((resolve) => {
      release = resolve;
    });
    fetchMock.mockImplementation(async (url, init = {}) => {
      if (url === PUT && init.method === 'POST') await gate;
      return inner(url, init);
    });
    renderApp('/admin');
    await userEvent.click(await sectionButton(1));
    const button = screen.getByRole('button', { name: 'Put back' });

    await userEvent.click(button);
    expect(button).toHaveAttribute('aria-disabled', 'true');
    await userEvent.click(button);
    release();

    await waitFor(() =>
      expect(screen.queryByRole('button', { name: /Left unmatched/ })).not.toBeInTheDocument(),
    );
    expect(putBacks(fetchMock)).toHaveLength(1);
  });

  it('keeps the film and says so plainly when the post fails', async () => {
    server({ put: () => respond({ error: 'x' }, 500) });
    renderApp('/admin');
    await userEvent.click(await sectionButton(2));
    const region = screen.getByRole('region', { name: 'Left unmatched (2)' });
    const row = within(region)
      .getAllByRole('listitem')
      .find((li) => within(li).queryByText('Home video'));

    await userEvent.click(within(row).getByRole('button', { name: 'Put back' }));

    expect(await within(row).findByRole('alert')).toHaveTextContent(
      'Couldn’t put it back — try again.',
    );
    expect(within(row).getByRole('button', { name: 'Put back' })).not.toHaveAttribute(
      'aria-disabled',
    );
    expect(screen.getByRole('region', { name: 'Left unmatched (2)' })).toBeInTheDocument();
  });

  it('says what holds the lock when a scan is running', async () => {
    server({ put: () => respond({ error: 'A scan is running' }, 409) });
    renderApp('/admin');
    await userEvent.click(await sectionButton(2));
    const region = screen.getByRole('region', { name: 'Left unmatched (2)' });
    const row = within(region)
      .getAllByRole('listitem')
      .find((li) => within(li).queryByText('Home video'));

    await userEvent.click(within(row).getByRole('button', { name: 'Put back' }));

    expect(await within(row).findByRole('alert')).toHaveTextContent(
      'A scan is running — try again when it finishes.',
    );
  });

  it('leaves focus alone when it had already moved off the row', async () => {
    let release;
    const { fetchMock } = server();
    const inner = fetchMock.getMockImplementation();
    const gate = new Promise((resolve) => {
      release = resolve;
    });
    fetchMock.mockImplementation(async (url, init = {}) => {
      if (url === PUT && init.method === 'POST') await gate;
      return inner(url, init);
    });
    renderApp('/admin');
    await userEvent.click(await sectionButton(2));
    const region = screen.getByRole('region', { name: 'Left unmatched (2)' });
    const row = within(region)
      .getAllByRole('listitem')
      .find((li) => within(li).queryByText('Home video'));
    await userEvent.click(within(row).getByRole('button', { name: 'Put back' }));
    const elsewhere = screen.getByRole('button', { name: 'Left unmatched (2)' });
    // Focus another control on the page while the post is out.
    const find = within(
      await screen.findByRole('region', { name: 'Pick the right film' }),
    ).getAllByRole('button', { name: 'Find the film…' })[0];
    find.focus();

    release();

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Left unmatched (1)' })).toBeInTheDocument(),
    );
    expect(find).toHaveFocus();
    expect(elsewhere).not.toHaveFocus();
  });
});
