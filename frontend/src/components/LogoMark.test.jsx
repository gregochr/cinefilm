import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import LogoMark from './LogoMark.jsx';

describe('LogoMark', () => {
  it('is decorative (hidden from assistive tech, no role) when it has no title', () => {
    render(<LogoMark />);
    expect(screen.queryByRole('img')).not.toBeInTheDocument();
    expect(screen.getByTestId('logo-mark')).toHaveAttribute('aria-hidden', 'true');
  });

  it('is an image named by its title when one is given', () => {
    render(<LogoMark title="CineFilm" />);
    const img = screen.getByRole('img', { name: 'CineFilm' });
    expect(img).not.toHaveAttribute('aria-hidden');
  });

  it('is 30px square by default', () => {
    render(<LogoMark title="CineFilm" />);
    const img = screen.getByRole('img', { name: 'CineFilm' });
    expect(img).toHaveAttribute('width', '30');
    expect(img).toHaveAttribute('height', '30');
  });

  it('takes its size from the size prop', () => {
    render(<LogoMark size={64} title="CineFilm" />);
    const img = screen.getByRole('img', { name: 'CineFilm' });
    expect(img).toHaveAttribute('width', '64');
    expect(img).toHaveAttribute('height', '64');
  });
});
