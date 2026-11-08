import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import FilmRow from './FilmRow.jsx';

const wrap = (props) =>
  render(
    <MemoryRouter>
      <FilmRow title="Recently added" {...props}>
        <span>tile</span>
      </FilmRow>
    </MemoryRouter>,
  );

describe('FilmRow', () => {
  it('is a region named by its heading, holding its tiles', () => {
    wrap({});
    const region = screen.getByRole('region', { name: 'Recently added' });
    expect(within(region).getByRole('heading', { level: 2, name: 'Recently added' })).toBeVisible();
    expect(within(region).getByText('tile')).toBeInTheDocument();
  });

  it('names the region from a title with punctuation', () => {
    wrap({ title: 'This week: the 1970s' });
    expect(screen.getByRole('region', { name: 'This week: the 1970s' })).toBeInTheDocument();
  });

  it('shows the sub-line when there is one and nothing when there is not', () => {
    const { unmount } = wrap({ sub: 'Last 30 days' });
    expect(screen.getByText('Last 30 days')).toBeInTheDocument();
    unmount();
    wrap({});
    expect(screen.queryByText('Last 30 days')).not.toBeInTheDocument();
  });

  it('offers a See all link that names its row', () => {
    wrap({ seeAllTo: '/library?sort=added' });
    expect(screen.getByRole('link', { name: 'See all: Recently added' })).toHaveAttribute(
      'href',
      '/library?sort=added',
    );
  });

  it('has no See all link unless given a destination', () => {
    wrap({});
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
  });
});
