import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp, respond } from '../../test/mockApi.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('Admin Library file lists', () => {
  it('opens from the protected tile with the protected films, their reason and file names', async () => {
    installFetch();
    renderApp('/admin/library');

    await userEvent.click(
      await screen.findByRole('link', { name: /^Can’t play \(protected\): 1/ }),
    );

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Protected files' }),
    ).toBeInTheDocument();
    const list = await screen.findByRole('list', { name: 'Protected files' });
    const items = within(list).getAllByRole('listitem');
    expect(items).toHaveLength(1);
    expect(items[0]).toHaveTextContent('Kes (1969)');
    expect(items[0]).toHaveTextContent("Protected by FairPlay, so it can't be played");
    expect(items[0]).toHaveTextContent('Kes.m4v');
    // Kes has no playable edition, so no film page: its title is plain text, not a link.
    expect(within(items[0]).queryByRole('link')).toBeNull();
  });

  it('lists only the films of its own kind', async () => {
    installFetch();
    renderApp('/admin/library/files/needs-conversion');

    const list = await screen.findByRole('list', { name: 'Files that need converting' });
    const items = within(list).getAllByRole('listitem');
    expect(items).toHaveLength(1);
    expect(items[0]).toHaveTextContent('Alien (1979)');
    expect(items[0]).toHaveTextContent('Alien.avi');
    expect(screen.queryByText('Kes (1969)')).toBeNull();
  });

  const withAlien = (browsable) => {
    const attention = structuredClone(fixtures.adminAttention);
    attention.items.find((item) => item.kind === 'needs-conversion').browsable = browsable;
    return (m, url) => (url === '/api/admin/attention' ? respond(attention) : null);
  };

  it('links the title to the film’s page when the server says it has one', async () => {
    installFetch(withAlien(true));
    renderApp('/admin/library/files/needs-conversion');

    const list = await screen.findByRole('list', { name: 'Files that need converting' });
    expect(within(list).getByRole('link', { name: 'Alien (1979)' })).toHaveAttribute(
      'href',
      '/films/33',
    );
  });

  it('shows the title as plain text when the server says it has no page', async () => {
    installFetch(withAlien(false));
    renderApp('/admin/library/files/needs-conversion');

    const list = await screen.findByRole('list', { name: 'Files that need converting' });
    expect(within(list).getByText('Alien (1979)')).toBeInTheDocument();
    expect(within(list).queryByRole('link')).toBeNull();
  });

  it('says there is nothing when no film is of that kind', async () => {
    installFetch();
    renderApp('/admin/library/files/unreadable');

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Files that could not be read' }),
    ).toBeInTheDocument();
    expect(await screen.findByText('Nothing here.')).toBeInTheDocument();
    expect(screen.queryByRole('list')).toBeNull();
  });

  it('links back to Library', async () => {
    installFetch();
    renderApp('/admin/library/files/protected');

    await userEvent.click(await screen.findByRole('link', { name: '‹ Library' }));

    expect(await screen.findByRole('heading', { level: 1, name: 'Library' })).toBeInTheDocument();
  });

  it('keeps Library current in the Admin navigation', async () => {
    installFetch();
    renderApp('/admin/library/files/protected');
    await screen.findByRole('list', { name: 'Protected files' });
    expect(
      within(screen.getByRole('navigation', { name: 'Admin sections' })).getByRole('link', {
        name: 'Library',
      }),
    ).toHaveAttribute('aria-current', 'page');
  });

  it('shows a skeleton while the list loads, held open', async () => {
    installFetch(() => new Promise(() => {}));
    renderApp('/admin/library/files/protected');
    expect(await screen.findByRole('status', { name: 'Loading' })).toBeInTheDocument();
  });

  it('shows the error with Try again when the list cannot load, then the list', async () => {
    let fail = true;
    installFetch((m, url) => (url === '/api/admin/attention' && fail ? respond(null, 500) : null));
    renderApp('/admin/library/files/protected');
    expect(await screen.findByRole('alert')).toHaveTextContent('Couldn’t load these files.');
    expect(screen.queryByRole('heading', { name: 'The projector jammed' })).not.toBeInTheDocument();

    fail = false;
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));

    expect(await screen.findByRole('list', { name: 'Protected files' })).toBeInTheDocument();
  });

  it('answers an unknown file list with Nothing here', async () => {
    installFetch();
    renderApp('/admin/library/files/other');
    expect(await screen.findByRole('heading', { name: 'Nothing here' })).toBeInTheDocument();
  });
});
