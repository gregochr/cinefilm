import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp, respond } from '../../test/mockApi.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('Admin To do section', () => {
  it('is named To do and holds only Films needing attention', async () => {
    installFetch();
    renderApp('/admin');
    expect(screen.getByRole('heading', { level: 1, name: 'To do' })).toBeInTheDocument();
    expect(
      await screen.findByRole('region', { name: 'Films needing attention' }),
    ).toBeInTheDocument();
    expect(screen.getAllByRole('region')).toHaveLength(1);
  });

  it('shows one skeleton while the list loads', () => {
    installFetch(() => new Promise(() => {}));
    renderApp('/admin');
    expect(screen.getAllByRole('status', { name: 'Loading' })).toHaveLength(1);
  });

  it('shows an error with Try again when the list cannot load, then the list', async () => {
    let fail = true;
    installFetch((m, url) => (url === '/api/admin/attention' && fail ? respond(null, 500) : null));
    renderApp('/admin');
    expect(
      await screen.findByText('Couldn’t load the films needing attention.'),
    ).toBeInTheDocument();
    fail = false;
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByText('Kes (1969)')).toBeInTheDocument();
  });

  it('opens Fix match from a row, and closes it when you move to another section', async () => {
    installFetch();
    renderApp('/admin');
    await userEvent.click((await screen.findAllByRole('button', { name: 'Fix match' }))[0]);
    expect(await screen.findByRole('region', { name: 'Fix match' })).toBeInTheDocument();

    await userEvent.click(
      within(screen.getByRole('navigation', { name: 'Admin sections' })).getByRole('link', {
        name: 'Library',
      }),
    );

    expect(await screen.findByRole('heading', { level: 1, name: 'Library' })).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Fix match' })).not.toBeInTheDocument();
  });

  it('closes Edit editions when you move to another section', async () => {
    installFetch();
    renderApp('/admin');
    await userEvent.click(await screen.findByRole('button', { name: 'Edit editions' }));
    await screen.findByRole('dialog', { name: 'Blade Runner · 1982' });

    await userEvent.click(
      within(screen.getByRole('navigation', { name: 'Admin sections' })).getByRole('link', {
        name: 'Settings',
      }),
    );

    expect(await screen.findByRole('heading', { level: 1, name: 'Settings' })).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('puts focus on the heading when the row that opened Fix match goes after the match', async () => {
    let applied = false;
    installFetch((m, url) => {
      if (m === 'POST' && url === '/api/admin/films/31/match') {
        applied = true;
        return respond({ filmId: 31, joined: false });
      }
      if (url === '/api/admin/attention' && applied) {
        return respond({ items: fixtures.adminAttention.items.filter((i) => i.filmId !== 31) });
      }
      return null;
    });
    renderApp('/admin');
    const opener = (await screen.findAllByRole('button', { name: 'Fix match' }))[0];
    await userEvent.click(opener);
    const panel = await screen.findByRole('region', { name: 'Fix match' });
    await userEvent.click((await within(panel).findAllByRole('radio'))[0]);

    await userEvent.click(within(panel).getByRole('button', { name: 'Use this match' }));

    await waitFor(() => expect(opener).not.toBeInTheDocument());
    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1, name: 'To do' })).toHaveFocus(),
    );
  });

  it('leaves focus alone when the opener is still there after the lists reload', async () => {
    let applied = false;
    installFetch((m, url) => {
      if (m === 'POST' && url === '/api/admin/films/31/match') {
        applied = true;
        return respond({ filmId: 31, joined: false });
      }
      if (url === '/api/admin/attention' && applied) {
        const [first, ...rest] = fixtures.adminAttention.items;
        return respond({
          items: [{ ...first, detail: 'Still unmatched after the reload' }, ...rest],
        });
      }
      return null;
    });
    renderApp('/admin');
    const opener = (await screen.findAllByRole('button', { name: 'Fix match' }))[0];
    await userEvent.click(opener);
    const panel = await screen.findByRole('region', { name: 'Fix match' });
    await userEvent.click((await within(panel).findAllByRole('radio'))[0]);
    await userEvent.click(within(panel).getByRole('button', { name: 'Use this match' }));

    expect(await screen.findByText('Still unmatched after the reload')).toBeInTheDocument();
    expect(opener).toBeInTheDocument();
    expect(opener).toHaveFocus();
  });
});
