import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { Poster } from './Poster.jsx';

const wrap = (ui) => render(<MemoryRouter>{ui}</MemoryRouter>);

const FILM = { id: 3, title: 'Alien', year: 1979, posterPath: '/alien.jpg', watched: false };

describe('Poster', () => {
  it('is one link to the film named with title and year', () => {
    wrap(<Poster film={FILM} />);
    const link = screen.getByRole('link', { name: 'Alien, 1979' });
    expect(link).toHaveAttribute('href', '/films/3');
    expect(link).toHaveTextContent('Alien · 1979');
  });

  it('says "watched" in the name and shows the tick for a watched film', () => {
    wrap(<Poster film={{ ...FILM, watched: true }} />);
    const link = screen.getByRole('link', { name: 'Alien, 1979, watched' });
    expect(link).toContainElement(screen.getByTitle('Watched'));
  });

  it('shows no watched tick for an unwatched film', () => {
    wrap(<Poster film={FILM} />);
    expect(screen.queryByTitle('Watched')).not.toBeInTheDocument();
  });

  it('shows the optional role caption', () => {
    wrap(<Poster film={FILM} caption="as Ripley" />);
    expect(screen.getByText('as Ripley')).toBeInTheDocument();
  });

  it('shows the title on a placeholder when there is no poster', () => {
    wrap(<Poster film={{ ...FILM, posterPath: null }} />);
    expect(screen.getAllByText('Alien')).toHaveLength(1); // the placeholder title; caption reads "Alien · 1979"
  });
});
