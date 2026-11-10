import { act, screen, within } from '@testing-library/react';
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
    // The header lockup (30px) plus the 48px mark above the message.
    const widths = screen.getAllByTestId('logo-mark').map((m) => m.getAttribute('width'));
    expect(widths).toEqual(['30', '48']);
    expect(screen.getByRole('navigation', { name: 'Primary' })).toBeInTheDocument();
  });

  it('the Admin route shows the Library scan and Films needing attention panels', async () => {
    installFetch();
    renderApp('/admin');
    expect(screen.getByRole('heading', { level: 1, name: 'Admin' })).toBeInTheDocument();
    for (const panel of ['Library scan', 'Films needing attention']) {
      expect(await screen.findByRole('heading', { level: 2, name: panel })).toBeInTheDocument();
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

  it('shows the signed-out copy after signing out from Admin, and keeps it through later 401s', async () => {
    let signedOut = false;
    installFetch((method, url) => {
      if (method === 'DELETE' && url === '/api/session') {
        signedOut = true;
        return respond(null, 204);
      }
      if (signedOut && url !== '/api/session') return respond(null, 401);
      return null;
    });
    renderApp('/admin');
    await userEvent.click(await screen.findByRole('button', { name: 'Sign out' }));
    const card = await screen.findByRole('dialog', { name: 'Signed out' });
    expect(card).toHaveTextContent(
      'You’ve signed out of CineFilm. Enter the password to sign back in.',
    );
    await act(async () => {
      window.dispatchEvent(new CustomEvent('cinefilm:session-expired'));
    });
    expect(screen.getByRole('dialog', { name: 'Signed out' })).toHaveTextContent(
      'You’ve signed out of CineFilm.',
    );
  });

  it('shows the expired copy for a plain 401', async () => {
    installFetch((method, url) => (url === '/api/home' ? respond(null, 401) : null));
    renderApp('/');
    const card = await screen.findByRole('dialog', { name: 'Signed out' });
    expect(card).toHaveTextContent('while you were away');
  });
});
