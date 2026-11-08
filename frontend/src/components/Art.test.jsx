import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import Art from './Art.jsx';
import { placeholderColor } from './placeholder.js';

describe('Art', () => {
  it('lazy-loads the TMDB poster at the poster size, as decoration', () => {
    render(<Art path="/a.jpg" id={1} title="Alien" />);
    // alt="" makes the image presentational, so it has no role; find it through its src.
    const img = screen.getByAltText('');
    expect(img).toHaveAttribute('src', 'https://image.tmdb.org/t/p/w342/a.jpg');
    expect(img).toHaveAttribute('loading', 'lazy');
  });

  it('uses the backdrop size for backdrops and stills, and an explicit size when given', () => {
    const { rerender } = render(<Art kind="backdrop" path="/b.jpg" id={1} title="Alien" />);
    expect(screen.getByAltText('')).toHaveAttribute(
      'src',
      'https://image.tmdb.org/t/p/w1280/b.jpg',
    );
    rerender(<Art kind="still" path="/b.jpg" id={1} title="Alien" size="w780" />);
    expect(screen.getByAltText('')).toHaveAttribute('src', 'https://image.tmdb.org/t/p/w780/b.jpg');
  });

  it('renders children over the image', () => {
    render(
      <Art path="/a.jpg" id={1} title="Alien">
        <span>Watched</span>
      </Art>,
    );
    expect(screen.getByText('Watched')).toBeInTheDocument();
  });

  it('falls back to a stable colour block with the title in serif for a missing poster', () => {
    render(<Art path={null} id={7} title="Zodiac" />);
    expect(screen.queryByAltText('')).not.toBeInTheDocument();
    expect(screen.getByText('Zodiac')).toBeInTheDocument();
    const art = screen.getByTestId('art');
    expect(art).toHaveClass('art--placeholder');
    expect(art).toHaveStyle({ background: placeholderColor(7, 'Zodiac') });
  });

  it('the same film always gets the same colour, different films can differ', () => {
    expect(placeholderColor(7, 'Zodiac')).toBe(placeholderColor(7, 'Zodiac'));
    const colours = new Set(
      ['Alien', 'Heat', 'Zodiac', 'Brazil', 'Seven'].map((t, i) => placeholderColor(i, t)),
    );
    expect(colours.size).toBeGreaterThan(1);
  });

  it('does not print the title on a placeholder when asked not to, or for non-posters', () => {
    const { rerender } = render(<Art path={null} id={7} title="Zodiac" showTitle={false} />);
    expect(screen.queryByText('Zodiac')).not.toBeInTheDocument();
    rerender(<Art kind="backdrop" path={null} id={7} title="Zodiac" />);
    expect(screen.queryByText('Zodiac')).not.toBeInTheDocument();
  });

  it('adds a caller class', () => {
    render(
      <Art path={null} id={1} title="T" className="hero__art">
        <span>child</span>
      </Art>,
    );
    expect(screen.getByTestId('art')).toHaveClass('art', 'art--poster', 'hero__art');
  });
});
