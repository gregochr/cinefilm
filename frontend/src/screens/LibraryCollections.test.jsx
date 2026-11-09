import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';

afterEach(() => vi.unstubAllGlobals());

// Visible cards, in order, by their display names.
const cardNames = () =>
  [...document.querySelectorAll('.collection-card__name')].map((n) => n.textContent);

const c = (id, name, ownedCount, totalCount, lastAddedAt) => ({
  id,
  name,
  posterPath: null,
  ownedCount,
  totalCount,
  posterPaths: [null, null],
  lastAddedAt,
});
const SAMPLE = {
  filmCount: 40,
  collections: [
    c(1, 'The Lord of the Rings Collection', 3, 3, '2026-01-01T00:00:00Z'),
    c(2, 'Alien Collection', 2, 4, '2026-03-01T00:00:00Z'),
    c(3, 'Harry Potter Collection', 7, 8, '2026-02-01T00:00:00Z'),
  ],
};
const withSample = () =>
  installFetch((m, url) => (url === '/api/collections' ? respond(SAMPLE) : null));

describe('Collections tab', () => {
  it('lists the collections by display name, sorted by name ignoring a leading The', async () => {
    withSample();
    renderApp('/library/collections');
    await screen.findByText('3 collections');
    expect(cardNames()).toEqual(['Alien', 'Harry Potter', 'The Lord of the Rings']);
    expect(screen.getByRole('link', { name: 'Alien 2 of 4 films' })).toHaveAttribute(
      'href',
      '/collections/2',
    );
    expect(screen.queryByText(/Collection$/)).not.toBeInTheDocument();
  });

  it('has the toolbar: search, sort and show, with the default pressed', async () => {
    withSample();
    renderApp('/library/collections');
    await screen.findByText('3 collections');
    expect(screen.getByRole('searchbox', { name: /Search/ })).toHaveAttribute(
      'placeholder',
      'Collection name',
    );
    const sort = screen.getByRole('group', { name: 'Sort' });
    expect(
      within(sort)
        .getAllByRole('button')
        .map((b) => b.textContent),
    ).toEqual(['Name', 'Recently added to', 'Size']);
    expect(within(sort).getByRole('button', { name: 'Name' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    const show = screen.getByRole('group', { name: 'Show' });
    expect(
      within(show)
        .getAllByRole('button')
        .map((b) => b.textContent),
    ).toEqual(['All', 'Complete', 'Missing films']);
  });

  it('sorts by recently added to and by size, and Name returns to the default', async () => {
    withSample();
    renderApp('/library/collections');
    await screen.findByText('3 collections');
    const sort = screen.getByRole('group', { name: 'Sort' });
    await userEvent.click(within(sort).getByRole('button', { name: 'Recently added to' }));
    expect(cardNames()).toEqual(['Alien', 'Harry Potter', 'The Lord of the Rings']);
    await userEvent.click(within(sort).getByRole('button', { name: 'Size' }));
    expect(cardNames()).toEqual(['Harry Potter', 'The Lord of the Rings', 'Alien']);
    await userEvent.click(within(sort).getByRole('button', { name: 'Name' }));
    expect(cardNames()).toEqual(['Alien', 'Harry Potter', 'The Lord of the Rings']);
  });

  it('Complete and Missing films filter, and All clears the filter', async () => {
    withSample();
    renderApp('/library/collections');
    await screen.findByText('3 collections');
    const show = screen.getByRole('group', { name: 'Show' });
    await userEvent.click(within(show).getByRole('button', { name: 'Complete' }));
    expect(cardNames()).toEqual(['The Lord of the Rings']);
    expect(screen.getByText('1 collection')).toBeInTheDocument();
    expect(
      screen.getByText('Complete', { selector: '.collection-card__complete' }),
    ).toBeInTheDocument();
    await userEvent.click(within(show).getByRole('button', { name: 'Missing films' }));
    expect(cardNames()).toEqual(['Alien', 'Harry Potter']);
    await userEvent.click(within(show).getByRole('button', { name: 'All' }));
    expect(cardNames()).toHaveLength(3);
  });

  it('starts from the URL: q, sort and show', async () => {
    withSample();
    renderApp('/library/collections?q=potter&sort=size&show=missing');
    await screen.findByText('1 collection');
    expect(cardNames()).toEqual(['Harry Potter']);
    expect(screen.getByRole('searchbox', { name: /Search/ })).toHaveValue('potter');
    expect(
      within(screen.getByRole('group', { name: 'Sort' })).getByRole('button', { name: 'Size' }),
    ).toHaveAttribute('aria-pressed', 'true');
  });

  it('searches the collection name as you type, case-insensitively', async () => {
    withSample();
    renderApp('/library/collections');
    await screen.findByText('3 collections');
    await userEvent.type(screen.getByRole('searchbox', { name: /Search/ }), 'LORD');
    await waitFor(() => expect(cardNames()).toEqual(['The Lord of the Rings']));
  });

  it('Escape empties the search box; Escape in an empty box does nothing', async () => {
    withSample();
    renderApp('/library/collections?q=potter');
    const box = await screen.findByRole('searchbox', { name: /Search/ });
    box.focus();
    await userEvent.keyboard('{Escape}');
    expect(box).toHaveValue('');
    await waitFor(() => expect(cardNames()).toHaveLength(3));
    await userEvent.keyboard('{Escape}');
    expect(box).toHaveValue('');
  });

  it('typing only spaces searches for nothing', async () => {
    withSample();
    renderApp('/library/collections?q=potter');
    const box = await screen.findByRole('searchbox', { name: /Search/ });
    await userEvent.clear(box);
    await userEvent.type(box, '   ');
    await waitFor(() => expect(cardNames()).toHaveLength(3));
  });

  it('is focused when opened with focus in the URL', async () => {
    withSample();
    renderApp('/library/collections?focus=search');
    expect(await screen.findByRole('searchbox', { name: /Search/ })).toHaveFocus();
  });

  it('shows no-results, naming collections, and clearing restores the list', async () => {
    withSample();
    renderApp('/library/collections?q=zzz&show=complete');
    expect(await screen.findByText('Nothing on the shelf')).toBeInTheDocument();
    expect(screen.getByText(/^No collections match “zzz” with these filters/)).toHaveTextContent(
      'Search looks at collection names.',
    );
    expect(await screen.findByText('0 collections')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Clear search and filters' }));
    await screen.findByText('3 collections');
    expect(screen.getByRole('searchbox', { name: /Search/ })).toHaveValue('');
  });

  it('says so when the library has no collections at all', async () => {
    installFetch((m, url) =>
      url === '/api/collections' ? respond({ filmCount: 3, collections: [] }) : null,
    );
    renderApp('/library/collections');
    expect(await screen.findByText(/No collections yet/)).toBeInTheDocument();
    expect(screen.queryByText('Nothing on the shelf')).not.toBeInTheDocument();
  });

  it('the default fixtures render the fan from the owned posters', async () => {
    installFetch();
    renderApp('/library/collections');
    await screen.findByText(`${fixtures.collections.collections.length} collections`);
    const potter = screen.getByRole('link', { name: 'Harry Potter 7 of 8 films' });
    expect(potter.querySelectorAll('img')).toHaveLength(3);
  });
});
