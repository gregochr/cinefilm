import { render, screen, within } from '@testing-library/react';
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

  describe('in progress', () => {
    const progress = { positionMs: 2400000, runtimeMs: 6600000, editionId: 31 };

    it('shows the progress fill and the time left under the caption', () => {
      wrap(<Poster film={{ ...FILM, inProgress: true, progress }} />);
      const link = screen.getByRole('link', { name: 'Alien, 1979' });
      expect(screen.getByTestId('poster-fill')).toHaveStyle({ width: '36%' });
      expect(link).toHaveTextContent('Alien · 1979');
      expect(within(link).getByText('1h 10m left')).toBeInTheDocument();
      expect(link).toHaveAccessibleDescription('1h 10m left');
    });

    it('shows neither when only progress is set, as for a film below the minimum', () => {
      wrap(<Poster film={{ ...FILM, inProgress: false, progress }} />);
      expect(screen.queryByTestId('poster-fill')).not.toBeInTheDocument();
      expect(screen.queryByText(/left$/)).not.toBeInTheDocument();
      expect(screen.getByRole('link', { name: 'Alien, 1979' })).not.toHaveAccessibleDescription();
    });

    it('captions a film with no year with its title alone', () => {
      wrap(<Poster film={{ ...FILM, year: null, inProgress: true, progress }} />);
      const link = screen.getByRole('link', { name: 'Alien' });
      expect(link).toHaveTextContent(/^Alien/);
      expect(link).not.toHaveTextContent('·');
      expect(within(link).getByText('1h 10m left')).toBeInTheDocument();
    });

    it('copes with an in-progress film that carries no progress', () => {
      wrap(<Poster film={{ ...FILM, inProgress: true }} />);
      expect(screen.getByTestId('poster-fill')).toHaveStyle({ width: '0%' });
      expect(screen.queryByText(/left$/)).not.toBeInTheDocument();
    });

    it('shows an empty fill when the runtime is unknown', () => {
      wrap(
        <Poster
          film={{
            ...FILM,
            inProgress: true,
            progress: { positionMs: 1000, runtimeMs: 0, editionId: 31 },
          }}
        />,
      );
      expect(screen.getByTestId('poster-fill')).toHaveStyle({ width: '0%' });
    });
  });

  it('shows the title on a placeholder when there is no poster', () => {
    wrap(<Poster film={{ ...FILM, posterPath: null }} />);
    expect(screen.getAllByText('Alien')).toHaveLength(1); // the placeholder title; caption reads "Alien · 1979"
  });
});
