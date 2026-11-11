import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import CollectionCard from './CollectionCard.jsx';

const wrap = (collection) =>
  render(
    <MemoryRouter>
      <CollectionCard collection={collection} />
    </MemoryRouter>,
  );

describe('CollectionCard', () => {
  it('links to the collection, with its display name and "owned of total films"', () => {
    wrap({
      id: 2,
      name: 'Alien Collection',
      ownedCount: 2,
      totalCount: 4,
      posterPaths: ['/a.jpg', '/b.jpg'],
    });
    const link = screen.getByRole('link', { name: 'Alien 2 of 4 films' });
    expect(link).toHaveAttribute('href', '/collections/2');
    expect(screen.queryByText(/Complete/)).not.toBeInTheDocument();
  });

  it('adds "✓ Complete" when every film is owned', () => {
    wrap({
      id: 3,
      name: 'Anchorman',
      ownedCount: 2,
      totalCount: 2,
      posterPaths: ['/a.jpg', '/b.jpg'],
    });
    expect(
      screen.getByRole('link', { name: 'Anchorman 2 of 2 films Complete' }),
    ).toBeInTheDocument();
  });

  it('draws one poster per owned film up to three', () => {
    wrap({
      id: 6,
      name: 'Many',
      ownedCount: 5,
      totalCount: 5,
      posterPaths: ['/1.jpg', '/2.jpg', '/3.jpg'],
    });
    expect(screen.getAllByTestId('fan-poster')).toHaveLength(3);
  });

  it('asks the server for the poster width of /api/art/ paths', () => {
    const art = `/api/art/${'b'.repeat(64)}.jpg`;
    wrap({
      id: 9,
      name: 'Mix',
      ownedCount: 2,
      totalCount: 2,
      posterPaths: [art, '/t.jpg'],
    });
    const srcs = screen.getAllByAltText('').map((i) => i.getAttribute('src'));
    expect(srcs).toContain(`${art}?w=342`);
    expect(srcs).toContain('https://image.tmdb.org/t/p/w342/t.jpg');
  });
});
