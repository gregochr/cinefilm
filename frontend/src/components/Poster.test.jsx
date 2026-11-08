import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { ContinueCard, Poster } from './Poster.jsx';

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

describe('ContinueCard', () => {
  const film = {
    id: 1,
    title: 'Heat',
    backdropPath: '/heat.jpg',
    progress: { positionMs: 3_871_000, runtimeMs: 10_200_000 },
  };

  it('links to the film, naming the time left', () => {
    wrap(<ContinueCard film={film} />);
    const link = screen.getByRole('link', { name: 'Heat, 1h 45m left' });
    expect(link).toHaveAttribute('href', '/films/1');
    expect(link).toHaveTextContent('Heat');
    expect(link).toHaveTextContent('1h 45m left');
  });

  it('fills the progress bar to the watched fraction', () => {
    wrap(<ContinueCard film={film} />);
    // The fill is decorative (aria-hidden), so it carries a test-id instead of a role.
    expect(screen.getByTestId('continue-fill')).toHaveStyle({ width: '38%' });
  });

  it('copes with a film that has no progress yet', () => {
    wrap(<ContinueCard film={{ ...film, progress: undefined }} />);
    expect(screen.getByRole('link', { name: 'Heat,' })).toBeInTheDocument();
  });

  it('shows an empty bar when the runtime is unknown', () => {
    wrap(<ContinueCard film={{ ...film, progress: { positionMs: 1000, runtimeMs: 0 } }} />);
    expect(screen.getByTestId('continue-fill')).toHaveStyle({ width: '0%' });
  });
});
