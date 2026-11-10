import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import CollectionTile from './CollectionTile.jsx';

const wrap = (collection, films) =>
  render(
    <MemoryRouter>
      <CollectionTile collection={collection} films={films} />
    </MemoryRouter>,
  );
const film = (id, posterPath) => ({ id, title: `Film ${id}`, year: 2000 + id, posterPath });

describe('CollectionTile', () => {
  it('links to the collection page and is named by its display name and film count', () => {
    wrap({ id: 4, name: 'Harry Potter Collection' }, [film(1, '/a.jpg'), film(2, null)]);
    const link = screen.getByRole('link', { name: 'Harry Potter, collection of 2 films' });
    expect(link).toHaveAttribute('href', '/collections/4');
    expect(screen.getByText('Collection · 2 films')).toBeInTheDocument();
  });

  it('shows the count badge and the name, and a fan of the films’ posters', () => {
    wrap({ id: 4, name: 'Alien Collection' }, [
      film(1, '/a.jpg'),
      film(2, '/b.jpg'),
      film(3, '/c.jpg'),
      film(4, '/d.jpg'),
    ]);
    expect(screen.getByTestId('collection-tile-badge')).toHaveTextContent('4');
    expect(screen.getByTestId('collection-tile-name')).toHaveTextContent(/^Alien$/);
    expect(screen.getAllByTestId('fan-poster')).toHaveLength(3);
  });

  it('says "1 film" in the singular, with a single poster', () => {
    wrap({ id: 5, name: 'Dune' }, [film(1, '/a.jpg')]);
    expect(screen.getByRole('link', { name: 'Dune, collection of 1 film' })).toBeInTheDocument();
    expect(screen.getByText('Collection · 1 film')).toBeInTheDocument();
    expect(screen.getAllByTestId('fan-poster')).toHaveLength(1);
  });
});
