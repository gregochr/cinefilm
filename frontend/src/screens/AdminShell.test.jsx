import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

const SECTIONS = ['To do', 'Subtitles', 'Library', 'Playback', 'Settings'];

describe('Admin shell: routes and navigation', () => {
  it.each([
    ['/admin', 'To do'],
    ['/admin/subtitles', 'Subtitles'],
    ['/admin/library', 'Library'],
    ['/admin/playback', 'Playback'],
    ['/admin/settings', 'Settings'],
  ])('%s shows its section and marks only its own link current', async (path, name) => {
    installFetch();
    renderApp(path);
    expect(await screen.findByRole('heading', { level: 1, name })).toBeInTheDocument();
    const nav = screen.getByRole('navigation', { name: 'Admin sections' });
    for (const label of SECTIONS) {
      const link = within(nav).getByRole('link', { name: label });
      if (label === name) expect(link).toHaveAttribute('aria-current', 'page');
      else expect(link).not.toHaveAttribute('aria-current');
    }
  });

  it('has one Admin sections navigation, the Admin title and a Home link back', async () => {
    installFetch();
    renderApp('/admin/library');
    await screen.findByRole('heading', { level: 1, name: 'Library' });
    expect(screen.getAllByRole('navigation', { name: 'Admin sections' })).toHaveLength(1);
    expect(screen.getByText('Admin')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Back to Home' })).toHaveAttribute('href', '/');
    expect(screen.getByText(/Each section has its own address/)).toBeInTheDocument();
  });

  it('the links go to the five addresses and moving between sections swaps the page', async () => {
    installFetch();
    renderApp('/admin');
    const nav = await screen.findByRole('navigation', { name: 'Admin sections' });
    expect(
      within(nav)
        .getAllByRole('link')
        .map((a) => a.getAttribute('href')),
    ).toEqual([
      '/admin',
      '/admin/subtitles',
      '/admin/library',
      '/admin/playback',
      '/admin/settings',
    ]);

    await userEvent.click(within(nav).getByRole('link', { name: 'Playback' }));

    expect(await screen.findByRole('heading', { level: 1, name: 'Playback' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { level: 1, name: 'To do' })).not.toBeInTheDocument();
    expect(within(nav).getByRole('link', { name: 'To do' })).not.toHaveAttribute('aria-current');
  });

  it('an unknown address under /admin is the Nothing here page, with the app chrome kept', async () => {
    installFetch();
    renderApp('/admin/foo');
    expect(await screen.findByRole('heading', { name: 'Nothing here' })).toBeInTheDocument();
    expect(screen.queryByRole('navigation', { name: 'Admin sections' })).not.toBeInTheDocument();
    expect(screen.getByTestId('app')).not.toHaveClass('app--bare-phone');
  });

  // data-testid is the one handle here: the class only matters to a media query, so nothing else observes it.
  it('marks the five Admin pages (and no other) so a phone drops the app header and tab bar', async () => {
    installFetch();
    renderApp('/admin/playback');
    await screen.findByRole('heading', { level: 1, name: 'Playback' });
    expect(screen.getByTestId('app')).toHaveClass('app--bare-phone');
    // Wider screens keep both: the flag is a class the phone CSS keys off.
    expect(screen.getByRole('banner')).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: 'Tabs' })).toBeInTheDocument();
  });

  it('keeps the app header and tab bar on the other pages', async () => {
    installFetch();
    renderApp('/library');
    await screen.findByRole('heading', { level: 1, name: 'Library' });
    expect(screen.getByTestId('app')).not.toHaveClass('app--bare-phone');
  });
});

describe('Admin shell: the old scan address', () => {
  it('redirects /admin#library-scan to /admin/library#scan, scrolls once, and never loads To do', async () => {
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;
    try {
      const fetchMock = installFetch();
      renderApp('/admin#library-scan', { showLocation: true });
      await screen.findByRole('button', { name: 'Scan now' });
      await waitFor(() => expect(scrollIntoView).toHaveBeenCalledTimes(1));
      expect(screen.getByTestId('location')).toHaveTextContent('/admin/library#scan');
      expect(fetchMock.mock.calls.some((c) => c[0] === '/api/admin/attention')).toBe(false);
    } finally {
      delete Element.prototype.scrollIntoView;
    }
  });

  it('scrolls to the Plex import card for #plex-import once Plex is configured', async () => {
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;
    try {
      installFetch((m, url) =>
        url === '/api/admin/plex'
          ? respond({
              configured: true,
              reachable: true,
              running: false,
              progress: null,
              lastImport: null,
            })
          : null,
      );
      renderApp('/admin/library#plex-import');
      await screen.findByRole('region', { name: 'Plex import' });
      await waitFor(() => expect(scrollIntoView).toHaveBeenCalledTimes(1));
    } finally {
      delete Element.prototype.scrollIntoView;
    }
  });

  it('redirects the old address typed in another case, as the router matches it', async () => {
    installFetch();
    renderApp('/Admin#library-scan', { showLocation: true });
    await screen.findByRole('button', { name: 'Scan now' });
    expect(screen.getByTestId('location')).toHaveTextContent('/admin/library#scan');
  });

  it('does not redirect /admin without the hash', async () => {
    installFetch();
    renderApp('/admin', { showLocation: true });
    await screen.findByRole('heading', { level: 1, name: 'To do' });
    expect(screen.getByTestId('location')).toHaveTextContent(/^\/admin$/);
  });
});

describe('Admin shell: dialogs hosted by the layout', () => {
  it('Fix match returns focus to the button that opened it when cancelled', async () => {
    installFetch();
    renderApp('/admin');
    const opener = (await screen.findAllByRole('button', { name: 'Fix match' }))[0];
    await userEvent.click(opener);
    const panel = await screen.findByRole('region', { name: 'Fix match' });

    await userEvent.click(within(panel).getByRole('button', { name: 'Cancel' }));

    expect(screen.queryByRole('region', { name: 'Fix match' })).not.toBeInTheDocument();
    expect(opener).toHaveFocus();
  });

  it('Fix match returns focus to its opener after a match is applied', async () => {
    installFetch((m, url) =>
      m === 'POST' && /\/api\/admin\/films\/\d+\/match$/.test(url)
        ? respond({ filmId: fixtures.adminAttention.items[0].filmId, joined: false })
        : null,
    );
    renderApp('/admin');
    const opener = (await screen.findAllByRole('button', { name: 'Fix match' }))[0];
    await userEvent.click(opener);
    const panel = await screen.findByRole('region', { name: 'Fix match' });
    await userEvent.click((await within(panel).findAllByRole('radio'))[0]);

    await userEvent.click(within(panel).getByRole('button', { name: 'Use this match' }));

    await waitFor(() =>
      expect(screen.queryByRole('region', { name: 'Fix match' })).not.toBeInTheDocument(),
    );
    expect(opener).toHaveFocus();
  });

  it('Edit editions returns focus to the button that opened it', async () => {
    installFetch();
    renderApp('/admin');
    const opener = await screen.findByRole('button', { name: 'Edit editions' });
    await userEvent.click(opener);
    await screen.findByRole('dialog', { name: 'Blade Runner · 1982' });
    await act(async () => {});

    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(opener).toHaveFocus();
  });
});
