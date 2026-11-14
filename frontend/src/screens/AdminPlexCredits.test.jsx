import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
});

const plexRoute = (plex) => (m, url) => (url === '/api/admin/plex' ? respond(plex) : null);

describe('Admin: Import credits from Plex', () => {
  it('is not asked for or shown when Plex is not configured', async () => {
    const fetchMock = installFetch();
    renderApp('/admin/library');
    await screen.findByRole('region', { name: 'Library scan' });

    expect(screen.queryByRole('group', { name: 'Credits from Plex' })).not.toBeInTheDocument();
    expect(fetchMock.mock.calls.filter(([url]) => url === '/api/admin/plex/credits')).toHaveLength(
      0,
    );
  });

  it('sits inside the Plex import card beside Import from Plex', async () => {
    installFetch(plexRoute(fixtures.adminPlex));
    renderApp('/admin/library');
    const panel = await screen.findByRole('region', { name: 'Plex import' });

    const group = await within(panel).findByRole('group', { name: 'Credits from Plex' });
    await waitFor(() =>
      expect(within(group).getByRole('button', { name: 'Import credits from Plex' })).toBeEnabled(),
    );
    expect(within(panel).getByRole('button', { name: 'Import from Plex again' })).toBeEnabled();
  });

  it('is disabled while Plex is not reachable', async () => {
    installFetch(plexRoute({ ...fixtures.adminPlex, reachable: false }));
    renderApp('/admin/library');
    const panel = await screen.findByRole('region', { name: 'Plex import' });

    const button = await within(panel).findByRole('button', { name: 'Import credits from Plex' });
    expect(button).toBeDisabled();
  });

  it('starts the credits import without touching the Plex import', async () => {
    const fetchMock = installFetch(plexRoute(fixtures.adminPlex));
    renderApp('/admin/library');
    const panel = await screen.findByRole('region', { name: 'Plex import' });

    const button = await within(panel).findByRole('button', { name: 'Import credits from Plex' });
    await waitFor(() => expect(button).toBeEnabled());
    await userEvent.click(button);

    expect(
      fetchMock.mock.calls.filter(
        (c) => c[0] === '/api/admin/plex/credits/import' && c[1]?.method === 'POST',
      ),
    ).toHaveLength(1);
    expect(fetchMock.mock.calls.filter((c) => c[0] === '/api/admin/plex/import')).toHaveLength(0);
  });
});
