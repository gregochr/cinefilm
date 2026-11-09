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
  it('links to the collection with its name and "owned of total films"', () => {
    wrap({ id: 2, name: 'Alien', ownedCount: 2, totalCount: 4, posterPaths: ['/a.jpg', '/b.jpg'] });
    const link = screen.getByRole('link', { name: /Alien/ });
    expect(link).toHaveAttribute('href', '/collections/2');
    expect(link).toHaveTextContent('2 of 4 films');
  });

  it('says just "N films" when the total is unknown', () => {
    wrap({ id: 2, name: 'Alien', ownedCount: 3, totalCount: null });
    expect(screen.getByRole('link')).toHaveTextContent('3 films');
    expect(screen.getByRole('link')).not.toHaveTextContent(' of ');
  });

  it('says "0 films" when neither count is known', () => {
    wrap({ id: 2, name: 'Alien' });
    expect(screen.getByRole('link')).toHaveTextContent('0 films');
  });

  it('keeps the fan out of the accessible name', () => {
    wrap({ id: 2, name: 'Alien', ownedCount: 1, totalCount: 2, posterPaths: ['/a.jpg'] });
    expect(screen.getByRole('link').getAttribute('aria-label')).toBeNull();
    expect(screen.getByRole('link', { name: 'Alien 1 of 2 films' })).toBeInTheDocument();
  });

  it('works from a single poster path and from more than three', () => {
    wrap({ id: 5, name: 'Solo', ownedCount: 1, posterPath: '/solo.jpg' });
    expect(screen.getByRole('link', { name: 'Solo 1 films' })).toBeInTheDocument();
  });

  it('uses only the first three posters', () => {
    wrap({
      id: 6,
      name: 'Many',
      ownedCount: 5,
      totalCount: 5,
      posterPaths: ['/1.jpg', '/2.jpg', '/3.jpg', '/4.jpg', '/5.jpg'],
    });
    expect(screen.getByRole('link', { name: 'Many 5 of 5 films' })).toBeInTheDocument();
  });
});

describe('CollectionCard with artwork stored by the server', () => {
  it('uses /api/art/ paths as they are and TMDB paths as TMDB images', () => {
    const art = `/api/art/${'b'.repeat(64)}.jpg`;
    const { container } = wrap({ id: 9, name: 'Mix', ownedCount: 2, posterPaths: [art, '/t.jpg'] });
    const posters = container.querySelectorAll('.collection-card__poster');
    expect(posters[0].style.backgroundImage).toBe(`url("${art}")`);
    expect(posters[1].style.backgroundImage).toContain('https://image.tmdb.org/t/p/w342/t.jpg');
  });
});
