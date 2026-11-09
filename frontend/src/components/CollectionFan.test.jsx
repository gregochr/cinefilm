import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import CollectionFan from './CollectionFan.jsx';

const fan = (posters) =>
  render(<CollectionFan posters={posters} seed={7} name="Alien Collection" />).container;

describe('CollectionFan', () => {
  it.each([
    [1, 1],
    [2, 2],
    [3, 3],
    [5, 3],
  ])('renders min(%i, 3) = %i posters, never an empty slot', (owned, shown) => {
    const container = fan(Array.from({ length: owned }, (_, i) => `/p${i}.jpg`));
    expect(container.querySelectorAll('.fan__poster')).toHaveLength(shown);
    expect(container.querySelectorAll('img')).toHaveLength(shown);
  });

  it('paints the front (earliest) poster last, dims the others and shadows all but the rearmost', () => {
    const posters = fan(['/1.jpg', '/2.jpg', '/3.jpg']).querySelectorAll('.fan__poster');
    const srcs = [...posters].map((p) => p.querySelector('img').getAttribute('src'));
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
    const one = fan(['/1.jpg']).querySelector('.fan__poster');
    expect(one.style.getPropertyValue('--shift')).toBe('0');
    const three = [...fan(['/1.jpg', '/2.jpg', '/3.jpg']).querySelectorAll('.fan__poster')];
    expect(three.map((p) => p.style.getPropertyValue('--shift'))).toEqual(['-0.4', '0', '0.4']);
  });

  it('shows a colour block, not an empty slot, for a film with no artwork', () => {
    const container = fan([null, '/2.jpg']);
    const posters = container.querySelectorAll('.fan__poster');
    expect(posters).toHaveLength(2);
    expect(posters[0].querySelector('img')).toBeInTheDocument();
    expect(posters[1].querySelector('img')).not.toBeInTheDocument();
    expect(posters[1].style.background).not.toBe('');
  });

  it('is hidden from assistive technology', () => {
    expect(fan(['/1.jpg']).querySelector('.fan')).toHaveAttribute('aria-hidden', 'true');
  });
});
