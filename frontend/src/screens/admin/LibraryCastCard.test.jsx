import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { fixtures } from '../../test/mockApi.jsx';
import LibraryCastCard from './LibraryCastCard.jsx';

const withCounts = (counts) => ({
  ...fixtures.adminScan,
  counts: { ...fixtures.adminScan.counts, ...counts },
});

describe('LibraryCastCard', () => {
  it('says how many films have credits, and offers nothing to press', () => {
    render(<LibraryCastCard scan={fixtures.adminScan} loading={false} />);

    const card = screen.getByRole('region', { name: 'Cast & crew' });
    expect(within(card).getByText('530 of 538 films have credits')).toBeInTheDocument();
    expect(within(card).queryByRole('button')).toBeNull();
    expect(within(card).queryByRole('group')).toBeNull();
  });

  it('keeps a count of zero', () => {
    render(
      <LibraryCastCard scan={withCounts({ films: 12, filmsWithCredits: 0 })} loading={false} />,
    );
    expect(screen.getByText('0 of 12 films have credits')).toBeInTheDocument();
  });

  it('says there are no films when the library is empty', () => {
    render(
      <LibraryCastCard scan={withCounts({ films: 0, filmsWithCredits: 0 })} loading={false} />,
    );
    expect(screen.getByText('No films in the library yet')).toBeInTheDocument();
  });

  it('shows a skeleton in place of the count while the scan status loads', () => {
    render(<LibraryCastCard scan={undefined} loading />);
    expect(screen.getByRole('region', { name: 'Cast & crew' })).toBeInTheDocument();
    expect(screen.getByRole('status', { name: 'Loading' })).toBeInTheDocument();
    expect(screen.queryByText(/films have credits/)).toBeNull();
  });

  it('is not drawn when the scan status failed', () => {
    const { container } = render(<LibraryCastCard scan={undefined} loading={false} />);
    expect(container).toBeEmptyDOMElement();
  });
});
