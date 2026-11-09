import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import RatingsRow from './RatingsRow.jsx';

const BOTH = {
  imdb: { score: 8.3, votes: 1234567, url: 'https://www.imdb.com/title/tt0113277/' },
  rottenTomatoes: { score: 84, url: 'https://www.rottentomatoes.com/m/heat_1995' },
};

describe('RatingsRow', () => {
  it('shows both scores as text: IMDb with /10, Rotten Tomatoes as a percentage', () => {
    render(<RatingsRow ratings={BOTH} />);
    const items = within(screen.getByRole('list', { name: 'Ratings' })).getAllByRole('listitem');
    expect(items).toHaveLength(2);
    expect(items[0]).toHaveTextContent('IMDb8.3/10');
    expect(items[1]).toHaveTextContent('Rotten Tomatoes84%');
  });

  it('opens each source in a new tab, safely, with a descriptive name', () => {
    render(<RatingsRow ratings={BOTH} />);
    const imdb = screen.getByRole('link', {
      name: 'IMDb rating 8.3 out of 10 (opens in a new tab)',
    });
    expect(imdb).toHaveAttribute('href', 'https://www.imdb.com/title/tt0113277/');
    expect(imdb).toHaveAttribute('target', '_blank');
    expect(imdb).toHaveAttribute('rel', 'noopener noreferrer');
    const rt = screen.getByRole('link', { name: 'Rotten Tomatoes score 84% (opens in a new tab)' });
    expect(rt).toHaveAttribute('href', 'https://www.rottentomatoes.com/m/heat_1995');
    expect(rt).toHaveAttribute('target', '_blank');
    expect(rt).toHaveAttribute('rel', 'noopener noreferrer');
  });

  it('shows only IMDb when Rotten Tomatoes has no score', () => {
    render(<RatingsRow ratings={{ ...BOTH, rottenTomatoes: null }} />);
    expect(screen.getAllByRole('listitem')).toHaveLength(1);
    expect(screen.getByText('IMDb')).toBeInTheDocument();
    expect(screen.queryByText('Rotten Tomatoes')).not.toBeInTheDocument();
  });

  it('shows only Rotten Tomatoes when IMDb has no score', () => {
    render(<RatingsRow ratings={{ ...BOTH, imdb: null }} />);
    expect(screen.getAllByRole('listitem')).toHaveLength(1);
    expect(screen.getByText('Rotten Tomatoes')).toBeInTheDocument();
    expect(screen.queryByText('IMDb')).not.toBeInTheDocument();
  });

  it('renders no row at all when neither score exists', () => {
    const { container } = render(<RatingsRow ratings={{ imdb: null, rottenTomatoes: null }} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders no row when the film carries no ratings block', () => {
    const { container } = render(<RatingsRow />);
    expect(container).toBeEmptyDOMElement();
  });

  it('shows Rotten Tomatoes without a page as plain text, not a link', () => {
    render(<RatingsRow ratings={{ ...BOTH, rottenTomatoes: { score: 84, url: null } }} />);
    expect(screen.getAllByRole('link')).toHaveLength(1);
    expect(screen.getByRole('link')).toHaveAccessibleName(/^IMDb rating/);
    expect(screen.getByText('Rotten Tomatoes')).toBeInTheDocument();
    expect(screen.getByText('84%')).toBeInTheDocument();
  });
});
