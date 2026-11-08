import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { installFetch, renderApp, respond } from './test/mockApi.jsx';

afterEach(() => vi.unstubAllGlobals());

describe('App routes', () => {
  it('shows "Nothing here" for an unknown address, inside the normal page chrome', async () => {
    installFetch();
    renderApp('/no/such/page');
    expect(await screen.findByRole('heading', { level: 1, name: 'Nothing here' })).toBeVisible();
    expect(screen.getByText('That page isn’t in the library.')).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: 'Primary' })).toBeInTheDocument();
  });

  it('the Admin placeholder lists the panels the handover describes', () => {
    installFetch();
    renderApp('/admin');
    expect(screen.getByRole('heading', { level: 1, name: 'Admin' })).toBeInTheDocument();
    expect(
      screen.getByText('Admin arrives with the real library in a later phase.'),
    ).toBeInTheDocument();
    for (const panel of [
      'Plex import',
      'Library scan',
      'Transcode worker',
      'Pre-made versions',
      'Films needing attention',
      'Fix match',
    ]) {
      expect(screen.getByRole('heading', { level: 2, name: panel })).toBeInTheDocument();
    }
  });

  it('the player route has no header or tab bar', async () => {
    installFetch();
    renderApp('/films/1/play');
    expect(await screen.findByRole('region', { name: 'Video player' })).toBeInTheDocument();
    expect(screen.queryByRole('navigation', { name: 'Primary' })).not.toBeInTheDocument();
    expect(screen.queryByRole('navigation', { name: 'Tabs' })).not.toBeInTheDocument();
  });

  it('every other page has the header and the tab bar', async () => {
    installFetch();
    renderApp('/');
    await screen.findByRole('region', { name: 'Resume Heat' });
    expect(screen.getByRole('navigation', { name: 'Primary' })).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: 'Tabs' })).toBeInTheDocument();
    expect(screen.getByRole('main')).toBeInTheDocument();
  });

  it('the Settings icon opens Admin', async () => {
    installFetch();
    renderApp('/');
    await userEvent.click(await screen.findByRole('link', { name: 'Settings' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Admin' })).toBeInTheDocument();
  });

  it('searching from the header lands in the library with the search applied', async () => {
    const fetchMock = installFetch();
    renderApp('/');
    await userEvent.type(
      await screen.findByRole('searchbox', { name: 'Search the library' }),
      'heat{Enter}',
    );
    expect(await screen.findByRole('heading', { level: 1, name: 'Library' })).toBeInTheDocument();
    expect(screen.getByRole('searchbox', { name: /Search/ })).toHaveValue('heat');
    expect(
      fetchMock.mock.calls
        .map((c) => c[0])
        .filter((u) => u.startsWith('/api/films?'))
        .at(-1),
    ).toContain('q=heat');
  });
});

describe('App session expiry', () => {
  it('does not show the sign-in card while signed in', async () => {
    installFetch();
    renderApp('/');
    await screen.findByRole('region', { name: 'Resume Heat' });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('a wrong password keeps the card open with the handover error', async () => {
    installFetch((method, url) => {
      if (method === 'POST' && url === '/api/session') return respond(null, 401);
      if (url === '/api/home') return respond(null, 401);
      return null;
    });
    renderApp('/');
    const card = await screen.findByRole('dialog', { name: 'Signed out' });
    await userEvent.type(within(card).getByLabelText('Password'), 'nope');
    await userEvent.click(within(card).getByRole('button', { name: 'Sign in' }));
    expect(await within(card).findByRole('alert')).toHaveTextContent(
      "That password didn't work. Try again.",
    );
    expect(screen.getByRole('dialog', { name: 'Signed out' })).toBeInTheDocument();
    expect(within(card).getByLabelText('Password')).toHaveValue('nope');
  });
});
