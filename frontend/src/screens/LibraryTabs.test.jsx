import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';

afterEach(() => vi.unstubAllGlobals());

describe('Library tabs', () => {
  it('has Films and Collections tabs with their counts, Films selected on /library', async () => {
    installFetch();
    renderApp('/library');
    const list = screen.getByRole('tablist', { name: 'Library' });
    expect(list).toBeInTheDocument();
    const films = screen.getByRole('tab', { name: /^Films/ });
    const collections = screen.getByRole('tab', { name: /^Collections/ });
    expect(films).toHaveAttribute('href', '/library');
    expect(collections).toHaveAttribute('href', '/library/collections');
    expect(films).toHaveAttribute('aria-selected', 'true');
    expect(collections).toHaveAttribute('aria-selected', 'false');
    expect(films).toHaveAttribute('tabindex', '0');
    expect(collections).toHaveAttribute('tabindex', '-1');
    expect(
      await screen.findByRole('tab', { name: `Films ${fixtures.collections.filmCount}` }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('tab', { name: `Collections ${fixtures.collections.collections.length}` }),
    ).toBeInTheDocument();
    expect(screen.getByRole('tabpanel')).toHaveAttribute('aria-labelledby', 'library-tab-films');
  });

  it('opens the Collections tab at its own URL and says how many collections', async () => {
    installFetch();
    renderApp('/library/collections');
    expect(await screen.findByText('3 collections')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /^Collections/ })).toHaveAttribute(
      'aria-selected',
      'true',
    );
    expect(screen.getByRole('link', { name: /Alien/ })).toHaveAttribute('href', '/collections/1');
    const primary = screen.getByRole('navigation', { name: 'Primary' });
    expect(within(primary).getByRole('link', { name: 'Library' })).toHaveAttribute(
      'aria-current',
      'page',
    );
  });

  it('clicking a tab switches to it', async () => {
    installFetch();
    renderApp('/library');
    await userEvent.click(screen.getByRole('tab', { name: /^Collections/ }));
    expect(await screen.findByText('3 collections')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('tab', { name: /^Films/ }));
    expect(await screen.findByText(/films · sorted by/)).toBeInTheDocument();
  });

  it('arrow keys, Home and End move between the tabs and navigate', async () => {
    installFetch();
    renderApp('/library');
    const films = screen.getByRole('tab', { name: /^Films/ });
    films.focus();
    await userEvent.keyboard('{ArrowRight}');
    await waitFor(() =>
      expect(screen.getByRole('tab', { name: /^Collections/ })).toHaveAttribute(
        'aria-selected',
        'true',
      ),
    );
    expect(screen.getByRole('tab', { name: /^Collections/ })).toHaveFocus();
    await userEvent.keyboard('{ArrowRight}');
    await waitFor(() =>
      expect(screen.getByRole('tab', { name: /^Films/ })).toHaveAttribute('aria-selected', 'true'),
    );
    await userEvent.keyboard('{ArrowLeft}');
    await waitFor(() => expect(screen.getByRole('tab', { name: /^Collections/ })).toHaveFocus());
    await userEvent.keyboard('{Home}');
    await waitFor(() => expect(screen.getByRole('tab', { name: /^Films/ })).toHaveFocus());
    await userEvent.keyboard('{End}');
    await waitFor(() => expect(screen.getByRole('tab', { name: /^Collections/ })).toHaveFocus());
    await userEvent.keyboard('a');
    expect(screen.getByRole('tab', { name: /^Collections/ })).toHaveFocus();
  });

  it('shows no counts when the collections request fails, and the films tab still works', async () => {
    installFetch((m, url) => (url === '/api/collections' ? respond(null, 500) : null));
    renderApp('/library');
    expect(await screen.findByRole('link', { name: 'Heat, 1995' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Films' })).toBeInTheDocument();
  });

  it('shows a loading skeleton, then an error with retry, on the Collections tab', async () => {
    let fail = true;
    installFetch((m, url) => (url === '/api/collections' && fail ? respond(null, 500) : null));
    renderApp('/library/collections');
    expect(await screen.findByRole('alert')).toHaveTextContent('The projector jammed');
    fail = false;
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByText('3 collections')).toBeInTheDocument();
  });

  it('says "1 collection" in the singular and ignores a trailing slash', async () => {
    installFetch((m, url) =>
      url === '/api/collections'
        ? respond({ filmCount: 2, collections: [fixtures.collections.collections[0]] })
        : null,
    );
    renderApp('/library/collections/');
    expect(await screen.findByText('1 collection')).toBeInTheDocument();
  });

  it('shows a Loading status while the collections load', () => {
    installFetch((m, url) => (url === '/api/collections' ? new Promise(() => {}) : null));
    renderApp('/library/collections');
    expect(screen.getByRole('status', { name: 'Loading' })).toBeInTheDocument();
  });
});
