import { act, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

const panelOf = () => screen.findByRole('region', { name: 'Ratings' });

describe('Admin: the Ratings card', () => {
  it('says when ratings were last updated, with Refresh all ratings ready', async () => {
    installFetch();
    renderApp('/admin/library');
    const panel = await panelOf();
    expect(within(panel).getByText('IMDb and Rotten Tomatoes · updated 8 Nov')).toBeInTheDocument();
    const refresh = within(panel).getByRole('button', { name: 'Refresh all ratings' });
    expect(refresh).toBeEnabled();
    expect(refresh).toHaveAccessibleDescription(
      'Re-fetches IMDb and Rotten Tomatoes scores for every film.',
    );
  });

  it('says ratings were not fetched yet', async () => {
    installFetch((m, url) =>
      url === '/api/admin/scan'
        ? respond({ ...fixtures.adminScan, ratingsLastRefreshedAt: null })
        : null,
    );
    renderApp('/admin/library');
    expect(
      await screen.findByText('IMDb and Rotten Tomatoes · not fetched yet'),
    ).toBeInTheDocument();
  });

  it('asks for an OMDb key and disables the button when ratings are off', async () => {
    installFetch((m, url) =>
      url === '/api/admin/scan' ? respond({ ...fixtures.adminScan, ratingsEnabled: false }) : null,
    );
    renderApp('/admin/library');
    const panel = await panelOf();
    expect(within(panel).getByText('Ratings need an OMDb API key')).toBeInTheDocument();
    expect(within(panel).queryByText(/updated/)).not.toBeInTheDocument();
    expect(within(panel).getByRole('button', { name: 'Refresh all ratings' })).toBeDisabled();
  });

  it('Refresh ratings posts, shows it running, and returns to ready with the new date', async () => {
    let posted = 0;
    let polls = 0;
    installFetch((method, url) => {
      if (url === '/api/admin/ratings/refresh' && method === 'POST') {
        posted += 1;
        return respond({ ...fixtures.adminScan, ratingsRunning: true }, 202);
      }
      if (url === '/api/admin/scan') {
        if (posted === 0) return respond(fixtures.adminScan);
        polls += 1;
        return polls < 3
          ? respond({ ...fixtures.adminScan, ratingsRunning: true })
          : respond({ ...fixtures.adminScan, ratingsLastRefreshedAt: '2026-11-09T05:35:00Z' });
      }
      return null;
    });
    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderApp('/admin/library');
    const panel = await panelOf();

    await userEvent.click(within(panel).getByRole('button', { name: 'Refresh all ratings' }));

    expect(posted).toBe(1);
    expect(
      await within(panel).findByRole('button', { name: 'Refreshing ratings…' }),
    ).toBeDisabled();
    await act(() => vi.advanceTimersByTimeAsync(1500));
    await act(() => vi.advanceTimersByTimeAsync(1500));
    expect(
      await within(panel).findByText('IMDb and Rotten Tomatoes · updated 9 Nov'),
    ).toBeInTheDocument();
    expect(within(panel).getByRole('button', { name: 'Refresh all ratings' })).toBeEnabled();
  });

  it('a refresh already running when the page opens keeps the button disabled', async () => {
    installFetch((m, url) =>
      url === '/api/admin/scan' ? respond({ ...fixtures.adminScan, ratingsRunning: true }) : null,
    );
    renderApp('/admin/library');
    expect(await screen.findByRole('button', { name: 'Refreshing ratings…' })).toBeDisabled();
  });

  it('shows why when the refresh could not start', async () => {
    installFetch((method, url) =>
      method === 'POST' && url === '/api/admin/ratings/refresh'
        ? respond({ error: 'boom' }, 500)
        : null,
    );
    renderApp('/admin/library');
    const panel = await panelOf();
    await userEvent.click(within(panel).getByRole('button', { name: 'Refresh all ratings' }));
    expect(
      await within(panel).findByText('Could not start the ratings refresh.'),
    ).toBeInTheDocument();
  });
});
