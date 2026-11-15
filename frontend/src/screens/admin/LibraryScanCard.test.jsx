import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { fixtures } from '../../test/mockApi.jsx';
import LibraryScanCard from './LibraryScanCard.jsx';

// 12 min after the fixture's scan finished (12:41:10).
const NOW = Date.parse('2026-11-08T12:53:30Z');
const CHANGES = { data: fixtures.adminGrouping, loading: false, error: null, reload: () => {} };

function renderCard(props) {
  return render(
    <MemoryRouter>
      <LibraryScanCard
        scan={fixtures.adminScan}
        loading={false}
        error={null}
        starting={false}
        notice={null}
        onScan={() => {}}
        onRetry={() => {}}
        changes={CHANGES}
        now={NOW}
        {...props}
      />
    </MemoryRouter>,
  );
}

describe('LibraryScanCard', () => {
  it('says when the last scan ran and how long it took, beside the title', () => {
    renderCard();
    const card = screen.getByRole('region', { name: 'Library scan' });
    expect(card).toHaveAttribute('id', 'scan');
    expect(within(card).getByText('Last scan 12 min ago · took 1m 8s')).toBeInTheDocument();
  });

  it('says not scanned yet before the first scan', () => {
    renderCard({ scan: { ...fixtures.adminScan, lastScan: null } });
    expect(screen.getByText('Not scanned yet')).toBeInTheDocument();
  });

  it('shows the folder and the count tiles', () => {
    renderCard();
    expect(screen.getByText('/media')).toBeInTheDocument();
    const tiles = within(screen.getByRole('list', { name: 'Library counts' }));
    expect(tiles.getByText('Films').previousSibling).toHaveTextContent('538');
    expect(tiles.getByText('Need a match').previousSibling).toHaveTextContent('2');
  });

  it('links the protected, needs-conversion and unreadable tiles to their lists, and no other tile', () => {
    renderCard({
      scan: { ...fixtures.adminScan, counts: { ...fixtures.adminScan.counts, unreadable: 2 } },
    });
    const links = within(screen.getByRole('list', { name: 'Library counts' })).getAllByRole('link');
    expect(links.map((l) => [l.getAttribute('aria-label'), l.getAttribute('href')])).toEqual([
      ['Can’t play (protected): 1, open the list', '/admin/library/files/protected'],
      ['Needs conversion: 3, open the list', '/admin/library/files/needs-conversion'],
      ['Unreadable: 2, open the list', '/admin/library/files/unreadable'],
    ]);
    expect(
      screen.getByRole('link', { name: 'Can’t play (protected): 1, open the list' }),
    ).toBeInTheDocument();
  });

  it('puts each tile’s number above its label', () => {
    renderCard();
    const tiles = within(screen.getByRole('list', { name: 'Library counts' }));
    expect(tiles.getAllByRole('listitem')[0]).toHaveTextContent(/^538Films$/);
  });

  it('draws Scan library now as the one primary button, with its hint', async () => {
    const onScan = vi.fn();
    renderCard({ onScan });

    const scan = screen.getByRole('button', { name: 'Scan library now' });
    expect(scan).toHaveClass('btn--primary');
    expect(scan).toHaveAccessibleDescription(/Looks through your movie folders/);
    await userEvent.click(scan);
    expect(onScan).toHaveBeenCalledTimes(1);
    for (const other of screen.getAllByRole('button')) {
      if (other !== scan) expect(other).not.toHaveClass('btn--primary');
    }
  });

  it('swaps the button for the progress line while a scan runs', () => {
    renderCard({
      scan: {
        ...fixtures.adminScan,
        running: true,
        progress: { processed: 12, total: 40, current: 'Heat.m4v' },
      },
    });

    expect(screen.queryByRole('button', { name: /Scan library now|Scanning/ })).toBeNull();
    const bar = screen.getByRole('progressbar', { name: 'Scan progress' });
    expect(bar).toHaveAttribute('aria-valuenow', '30');
    expect(bar).toHaveAttribute('aria-valuetext', '12 / 40 files');
    expect(screen.getByText('Now: Heat.m4v')).toBeInTheDocument();
  });

  it('shows a neutral progress line while a scan runs before any figures arrive', () => {
    renderCard({ scan: { ...fixtures.adminScan, running: true, progress: null } });
    expect(screen.getByRole('progressbar', { name: 'Scan progress' })).toHaveAttribute(
      'aria-valuetext',
      'Looking through the folder…',
    );
    expect(screen.queryByText(/^Now:/)).toBeNull();
  });

  it('disables the button and says Scanning… while the start request is in flight', () => {
    renderCard({ starting: true });
    expect(screen.getByRole('button', { name: 'Scanning…' })).toBeDisabled();
  });

  it('shows a notice beside the button, and the last scan’s own error as a warning', () => {
    renderCard({
      notice: 'Could not start the scan.',
      scan: {
        ...fixtures.adminScan,
        lastScan: { ...fixtures.adminScan.lastScan, error: 'The folder is not mounted' },
      },
    });
    expect(screen.getByText('Could not start the scan.')).toBeInTheDocument();
    expect(screen.getByText('The folder is not mounted')).toBeInTheDocument();
  });

  it('holds what the last scan changed, closed', () => {
    renderCard();
    expect(screen.getByTestId('library-changes-summary')).toHaveTextContent(
      'What the last scan changed · 1 split, 1 joined, 1 flagged',
    );
  });

  it('has no subtitle search line: it lives in Subtitles', () => {
    renderCard();
    expect(screen.queryByText(/Subtitles searched|Searching for subtitles/)).toBeNull();
  });

  it('shows a skeleton while loading, and the error with Try again when it failed', async () => {
    const onRetry = vi.fn();
    const { unmount } = renderCard({ scan: undefined, loading: true });
    expect(screen.getAllByRole('status', { name: 'Loading' })).toHaveLength(1);
    expect(screen.queryByText(/Last scan/)).toBeNull();
    unmount();

    renderCard({ scan: undefined, error: new Error('x'), onRetry });
    expect(screen.getByText('Couldn’t load the library scan.')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });
});
