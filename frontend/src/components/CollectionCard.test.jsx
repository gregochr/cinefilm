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
});

const posterEls = (container) => [...container.querySelectorAll('.collection-card__poster')];
const urlOf = (el) => el.style.backgroundImage;

describe('CollectionCard fan', () => {
  it.each([
    [1, 1],
    [2, 2],
    [3, 3],
    [5, 3],
  ])('renders %i paths as %i posters with a fan--%i modifier', (given, shown) => {
    const posterPaths = ['/1.jpg', '/2.jpg', '/3.jpg', '/4.jpg', '/5.jpg'].slice(0, given);
    const { container } = wrap({ id: 6, name: 'Many', ownedCount: given, posterPaths });
    expect(posterEls(container)).toHaveLength(shown);
    expect(container.querySelector('.collection-card__fan')).toHaveClass(`collection-card__fan--${shown}`);
  });

  it('uses the first three paths in order and never pads a two-film collection', () => {
    const { container } = wrap({
      id: 6,
      name: 'Many',
      ownedCount: 5,
      posterPaths: ['/1.jpg', '/2.jpg', '/3.jpg', '/4.jpg', '/5.jpg'],
    });
    const byDepth = (d) => container.querySelector(`.collection-card__poster--depth-${d}`);
    expect(urlOf(byDepth(0))).toContain('/1.jpg');
    expect(urlOf(byDepth(1))).toContain('/2.jpg');
    expect(urlOf(byDepth(2))).toContain('/3.jpg');
    expect(container.innerHTML).not.toContain('/4.jpg');
  });

  it('has no placeholder slots for a two-film collection', () => {
    const { container } = wrap({ id: 3, name: 'Duo', ownedCount: 2, posterPaths: ['/a.jpg', '/b.jpg'] });
    const posters = posterEls(container);
    expect(posters).toHaveLength(2);
    posters.forEach((el) => expect(urlOf(el)).toContain('https://image.tmdb.org/t/p/w342/'));
    expect(urlOf(container.querySelector('.collection-card__poster--depth-0'))).toContain('/a.jpg');
    expect(urlOf(container.querySelector('.collection-card__poster--depth-1'))).toContain('/b.jpg');
  });

  it('puts the earliest film at the front, painted last', () => {
    const { container } = wrap({
      id: 6,
      name: 'Trio',
      ownedCount: 3,
      posterPaths: ['/1.jpg', '/2.jpg', '/3.jpg'],
    });
    const posters = posterEls(container);
    const front = posters[posters.length - 1];
    expect(front).toHaveClass('collection-card__poster--depth-0');
    expect(urlOf(front)).toContain('/1.jpg');
    expect(posters.map((el) => el.className.match(/depth-(\d)/)[1])).toEqual(['2', '1', '0']);
  });

  it('falls back to posterPath, then to a placeholder, as a single poster', () => {
    const withPath = wrap({ id: 5, name: 'Solo', ownedCount: 1, posterPath: '/solo.jpg' });
    expect(posterEls(withPath.container)).toHaveLength(1);
    expect(urlOf(posterEls(withPath.container)[0])).toContain('/solo.jpg');
    withPath.unmount();

    const neither = wrap({ id: 5, name: 'Solo', ownedCount: 1, posterPath: null, posterPaths: [] });
    const [only] = posterEls(neither.container);
    expect(posterEls(neither.container)).toHaveLength(1);
    expect(urlOf(only)).not.toContain('url(');
    expect(only.style.background).not.toBe('');
  });

  it('keeps a null entry as a placeholder poster that counts', () => {
    const { container } = wrap({ id: 7, name: 'Gap', ownedCount: 2, posterPaths: ['/a.jpg', null] });
    const posters = posterEls(container);
    expect(posters).toHaveLength(2);
    const back = container.querySelector('.collection-card__poster--depth-1');
    expect(urlOf(back)).not.toContain('url(');
    expect(back.style.background).not.toBe('');
    expect(container.querySelector('.collection-card__fan')).toHaveClass('collection-card__fan--2');
  });
});

describe('CollectionCard with artwork stored by the server', () => {
  it('uses /api/art/ paths as they are and TMDB paths as TMDB images', () => {
    const art = `/api/art/${'b'.repeat(64)}.jpg`;
    const { container } = wrap({ id: 9, name: 'Mix', ownedCount: 2, posterPaths: [art, '/t.jpg'] });
    const front = container.querySelector('.collection-card__poster--depth-0');
    const back = container.querySelector('.collection-card__poster--depth-1');
    expect(front.style.backgroundImage).toBe(`url("${art}")`);
    expect(back.style.backgroundImage).toContain('https://image.tmdb.org/t/p/w342/t.jpg');
  });
});
