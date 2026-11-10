import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import CollectionFan from './CollectionFan.jsx';

const fan = (posters) =>
  render(<CollectionFan posters={posters} seed={7} name="Alien Collection" />);

describe('CollectionFan', () => {
  it.each([
    [1, 1],
    [2, 2],
    [3, 3],
    [5, 3],
  ])('renders min(%i, 3) = %i posters, never an empty slot', (owned, shown) => {
    fan(Array.from({ length: owned }, (_, i) => `/p${i}.jpg`));
    expect(screen.getAllByTestId('fan-poster')).toHaveLength(shown);
    expect(screen.getAllByAltText('')).toHaveLength(shown);
  });

  it('paints the front (earliest) poster last, dims the others and shadows all but the rearmost', () => {
    fan(['/1.jpg', '/2.jpg', '/3.jpg']);
    const posters = screen.getAllByTestId('fan-poster');
    const srcs = posters.map((p) => within(p).getByAltText('').getAttribute('src'));
    expect(srcs).toEqual([
      'https://image.tmdb.org/t/p/w342/3.jpg',
      'https://image.tmdb.org/t/p/w342/2.jpg',
      'https://image.tmdb.org/t/p/w342/1.jpg',
    ]);
    expect(posters[0].style.filter).toBe('brightness(0.6)');
    expect(posters[1].style.filter).toBe('brightness(0.8)');
    expect(posters[2].style.filter).toBe('');
    expect(posters[0]).not.toHaveClass('fan__poster--shadow');
    expect(posters[1]).toHaveClass('fan__poster--shadow');
    expect(posters[2]).toHaveClass('fan__poster--shadow');
    posters.forEach((p) => expect(p.style.opacity).toBe(''));
  });

  it('centres the group: a single poster is not shifted, three are spread about the middle', () => {
    const { unmount } = fan(['/1.jpg']);
    expect(screen.getByTestId('fan-poster').style.getPropertyValue('--shift')).toBe('0');
    unmount();
    fan(['/1.jpg', '/2.jpg', '/3.jpg']);
    expect(
      screen.getAllByTestId('fan-poster').map((p) => p.style.getPropertyValue('--shift')),
    ).toEqual(['-0.4', '0', '0.4']);
  });

  it('shows a colour block, not an empty slot, for a film with no artwork', () => {
    fan([null, '/2.jpg']);
    const posters = screen.getAllByTestId('fan-poster');
    expect(posters).toHaveLength(2);
    // Rendered rear to front: the film with no artwork is the front one.
    expect(within(posters[1]).queryByAltText('')).not.toBeInTheDocument();
    expect(within(posters[0]).getByAltText('')).toBeInTheDocument();
    expect(posters[1].style.background).not.toBe('');
  });

  it('is hidden from assistive technology', () => {
    fan(['/1.jpg']);
    expect(screen.getByTestId('fan')).toHaveAttribute('aria-hidden', 'true');
  });
});
