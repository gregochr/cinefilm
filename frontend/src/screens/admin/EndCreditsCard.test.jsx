import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { installFetch, renderApp, respond } from '../../test/mockApi.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('Library: the End credits card', () => {
  it('shows the three counts and a Review end credits… link to the list', async () => {
    installFetch();
    renderApp('/admin/library');

    const card = await screen.findByRole('region', { name: 'End credits' });
    const tiles = within(await within(card).findByRole('list', { name: 'End credits counts' }));
    expect(await tiles.findByText('Needs a look')).toBeInTheDocument();
    const items = tiles.getAllByRole('listitem');
    expect(items.map((item) => item.textContent)).toEqual([
      'Needs a look3',
      'From Plex1',
      'Yours1',
    ]);
    expect(within(card).getByRole('link', { name: 'Review end credits…' })).toHaveAttribute(
      'href',
      '/admin/library/credits',
    );
  });

  it('opens the list from the link', async () => {
    installFetch();
    renderApp('/admin/library');

    await userEvent.click(await screen.findByRole('link', { name: 'Review end credits…' }));

    expect(
      await screen.findByRole('heading', { level: 1, name: 'End credits' }),
    ).toBeInTheDocument();
  });

  it('says so when the counts cannot be read, and Check again reads them again', async () => {
    let fail = true;
    installFetch((method, url) =>
      String(url).startsWith('/api/admin/credits') && fail ? respond(null, 500) : null,
    );
    renderApp('/admin/library');

    const card = await screen.findByRole('region', { name: 'End credits' });
    expect(await within(card).findByRole('alert')).toHaveTextContent(
      'Couldn’t load the end credits counts.',
    );
    expect(within(card).getByRole('link', { name: 'Review end credits…' })).toBeInTheDocument();
    fail = false;
    await userEvent.click(within(card).getByRole('button', { name: 'Check again' }));

    expect(await within(card).findByText('From Plex')).toBeInTheDocument();
    expect(within(card).queryByRole('alert')).toBeNull();
  });
});
