import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { installFetch, renderApp, respond } from '../../test/mockApi.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('Admin Settings section', () => {
  it('is named Settings and holds Continue watching and Signed in, with the TMDB credit last', async () => {
    installFetch();
    renderApp('/admin/settings');
    expect(screen.getByRole('heading', { level: 1, name: 'Settings' })).toBeInTheDocument();
    await screen.findByRole('region', { name: 'Continue watching' });
    expect(screen.getByRole('region', { name: 'Signed in on this browser' })).toBeInTheDocument();
    expect(screen.getAllByRole('region')).toHaveLength(2);
    const credit = screen.getByText(
      'This product uses the TMDB API but is not endorsed or certified by TMDB.',
    );
    const session = screen.getByRole('region', { name: 'Signed in on this browser' });
    expect(session.compareDocumentPosition(credit)).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
    expect(screen.getByRole('img', { name: 'TMDB' })).toBeInTheDocument();
  });

  it('reads each data credit as a whole sentence', async () => {
    installFetch();
    renderApp('/admin/settings');
    await screen.findByRole('region', { name: 'Continue watching' });
    const items = within(screen.getByRole('list', { name: 'Data credits' })).getAllByRole('listitem');
    expect(items.map((item) => item.textContent)).toEqual([
      'Subtitles from OpenSubtitles.com (opens in a new tab).',
      'IMDb and Rotten Tomatoes ratings from OMDb (opens in a new tab), licensed CC BY-NC 4.0 (opens in a new tab).',
      'Network lookup by IPinfo (opens in a new tab).',
    ]);
  });

  it.each([
    ['OpenSubtitles.com (opens in a new tab)', 'https://www.opensubtitles.com'],
    ['OMDb (opens in a new tab)', 'https://www.omdbapi.com'],
    ['CC BY-NC 4.0 (opens in a new tab)', 'https://creativecommons.org/licenses/by-nc/4.0/'],
    ['IPinfo (opens in a new tab)', 'https://ipinfo.io'],
  ])('credits %s with a link that opens safely in a new tab', async (name, href) => {
    installFetch();
    renderApp('/admin/settings');
    await screen.findByRole('region', { name: 'Continue watching' });
    const link = screen.getByRole('link', { name });
    expect(link).toHaveAttribute('href', href);
    expect(link).toHaveAttribute('target', '_blank');
    expect(link.getAttribute('rel')).toContain('noopener');
    expect(link.getAttribute('rel')).toContain('noreferrer');
  });

  it('has no switches, and its one setting and Sign out are described by their hints', async () => {
    installFetch();
    renderApp('/admin/settings');
    await screen.findByRole('spinbutton', { name: 'Continue watching starts after' });
    expect(screen.queryByRole('switch')).not.toBeInTheDocument();
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
    const signedIn = screen.getByRole('region', { name: 'Signed in on this browser' });
    expect(within(signedIn).getByText('Signed in on this browser')).toBeInTheDocument();
    expect(within(signedIn).getByRole('button', { name: 'Sign out' })).toHaveAccessibleDescription(
      "Signing out here doesn't affect other devices.",
    );
  });

  it('signing out on an already-expired session ends on the sign-in card, not "Couldn\'t sign out"', async () => {
    installFetch((method, url) =>
      method === 'DELETE' && url === '/api/session' ? respond(null, 401) : null,
    );
    renderApp('/admin/settings');
    await userEvent.click(await screen.findByRole('button', { name: 'Sign out' }));
    expect(await screen.findByRole('dialog', { name: 'Signed out' })).toBeInTheDocument();
    expect(screen.queryByText("Couldn't sign out. Try again.")).not.toBeInTheDocument();
  });

  it('a server failure on sign out still says "Couldn\'t sign out"', async () => {
    installFetch((method, url) =>
      method === 'DELETE' && url === '/api/session' ? respond(null, 500) : null,
    );
    renderApp('/admin/settings');
    await userEvent.click(await screen.findByRole('button', { name: 'Sign out' }));
    expect(await screen.findByText("Couldn't sign out. Try again.")).toBeInTheDocument();
    expect(screen.queryByRole('dialog', { name: 'Signed out' })).not.toBeInTheDocument();
  });

  it('shows one skeleton while the setting loads', () => {
    installFetch(() => new Promise(() => {}));
    renderApp('/admin/settings');
    expect(screen.getAllByRole('status', { name: 'Loading the settings' })).toHaveLength(1);
  });
});
