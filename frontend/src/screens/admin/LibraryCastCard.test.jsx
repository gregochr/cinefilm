import { render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch } from '../../test/mockApi.jsx';
import LibraryCastCard from './LibraryCastCard.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
});

const withCounts = (counts) => ({
  ...fixtures.adminScan,
  counts: { ...fixtures.adminScan.counts, ...counts },
});

describe('LibraryCastCard', () => {
  it('says how many films have credits, with no Plex block when Plex is not configured', () => {
    const fetchMock = installFetch();
    render(<LibraryCastCard scan={fixtures.adminScan} loading={false} plex={null} />);

    const card = screen.getByRole('region', { name: 'Cast & crew' });
    expect(within(card).getByText('530 of 538 films have credits')).toBeInTheDocument();
    expect(within(card).queryByRole('group')).toBeNull();
    expect(within(card).queryByRole('button')).toBeNull();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('keeps a count of zero', () => {
    installFetch();
    render(
      <LibraryCastCard
        scan={withCounts({ films: 12, filmsWithCredits: 0 })}
        loading={false}
        plex={{ configured: false }}
      />,
    );
    expect(screen.getByText('0 of 12 films have credits')).toBeInTheDocument();
  });

  it('says there are no films when the library is empty', () => {
    installFetch();
    render(
      <LibraryCastCard
        scan={withCounts({ films: 0, filmsWithCredits: 0 })}
        loading={false}
        plex={null}
      />,
    );
    expect(screen.getByText('No films in the library yet')).toBeInTheDocument();
  });

  it('adds Import credits from Plex when Plex is configured, off while Plex is unreachable', async () => {
    installFetch();
    const { rerender } = render(
      <LibraryCastCard scan={fixtures.adminScan} loading={false} plex={fixtures.adminPlex} />,
    );
    const group = await screen.findByRole('group', { name: 'Credits from Plex' });
    expect(
      await within(group).findByRole('button', { name: 'Import credits from Plex' }),
    ).toBeEnabled();

    rerender(
      <LibraryCastCard
        scan={fixtures.adminScan}
        loading={false}
        plex={{ ...fixtures.adminPlex, reachable: false }}
      />,
    );
    expect(
      within(screen.getByRole('group', { name: 'Credits from Plex' })).getByRole('button', {
        name: 'Import credits from Plex',
      }),
    ).toBeDisabled();
  });

  it('shows a skeleton in place of the count while the scan status loads', () => {
    installFetch();
    render(<LibraryCastCard scan={undefined} loading plex={null} />);
    expect(screen.getByRole('region', { name: 'Cast & crew' })).toBeInTheDocument();
    expect(screen.getByRole('status', { name: 'Loading' })).toBeInTheDocument();
    expect(screen.queryByText(/films have credits/)).toBeNull();
  });

  it('is not drawn when the scan status failed and there is no Plex block', () => {
    installFetch();
    const { container } = render(<LibraryCastCard scan={undefined} loading={false} plex={null} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('still shows the Plex block when the scan status failed', async () => {
    installFetch();
    render(<LibraryCastCard scan={undefined} loading={false} plex={fixtures.adminPlex} />);
    expect(await screen.findByRole('group', { name: 'Credits from Plex' })).toBeInTheDocument();
    expect(screen.queryByText(/films have credits/)).toBeNull();
  });
});
