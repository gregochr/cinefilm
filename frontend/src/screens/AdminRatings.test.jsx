import { act, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

const panelOf = () => screen.findByRole('region', { name: 'Library scan' });

describe('Admin: ratings in the Library scan card', () => {
  it('says when ratings were last refreshed, with Refresh ratings ready', async () => {
    installFetch();
    renderApp('/admin/library');
    const panel = await panelOf();
    expect(within(panel).getByText('Ratings last refreshed 08 Nov 12:41')).toBeInTheDocument();
    expect(within(panel).getByRole('button', { name: 'Refresh ratings' })).toBeEnabled();
  });

  it('says ratings were never refreshed', async () => {
    installFetch((m, url) =>
      url === '/api/admin/scan'
        ? respond({ ...fixtures.adminScan, ratingsLastRefreshedAt: null })
        : null,
    );
    renderApp('/admin/library');
    expect(await screen.findByText('Ratings never refreshed')).toBeInTheDocument();
  });

  it('asks for an OMDb key and disables the button when ratings are off', async () => {
    installFetch((m, url) =>
      url === '/api/admin/scan' ? respond({ ...fixtures.adminScan, ratingsEnabled: false }) : null,
    );
    renderApp('/admin/library');
    const panel = await panelOf();
    expect(within(panel).getByText('Ratings need an OMDb API key')).toBeInTheDocument();
    expect(within(panel).queryByText(/Ratings last refreshed/)).not.toBeInTheDocument();
    expect(within(panel).getByRole('button', { name: 'Refresh ratings' })).toBeDisabled();
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

    await userEvent.click(within(panel).getByRole('button', { name: 'Refresh ratings' }));

    expect(posted).toBe(1);
    expect(
      await within(panel).findByRole('button', { name: 'Refreshing ratings…' }),
    ).toBeDisabled();
    await act(() => vi.advanceTimersByTimeAsync(1500));
    await act(() => vi.advanceTimersByTimeAsync(1500));
    expect(
      await within(panel).findByText('Ratings last refreshed 09 Nov 05:35'),
    ).toBeInTheDocument();
    expect(within(panel).getByRole('button', { name: 'Refresh ratings' })).toBeEnabled();
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
    await userEvent.click(within(panel).getByRole('button', { name: 'Refresh ratings' }));
    expect(
      await within(panel).findByText('Could not start the ratings refresh.'),
    ).toBeInTheDocument();
  });
});
