import { act, render, screen, within } from '@testing-library/react';
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

  it('says neither "Not scanned yet" nor "changed nothing" while the scan status has not answered', () => {
    renderCard({
      scan: undefined,
      loading: true,
      changes: { data: { items: [] }, loading: false, error: null, reload: () => {} },
    });

    expect(screen.queryByText('Not scanned yet')).not.toBeInTheDocument();
    expect(screen.queryByText('The last scan changed nothing.')).not.toBeInTheDocument();
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
    expect(screen.getByRole('button', { name: 'Scanning…' })).toHaveAttribute(
      'aria-disabled',
      'true',
    );
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

  it('says not scanned yet, not that the last scan changed nothing, before any scan has finished', () => {
    renderCard({
      scan: { ...fixtures.adminScan, lastScan: null },
      changes: { ...CHANGES, data: { items: [] } },
    });
    expect(screen.getAllByText('Not scanned yet')).toHaveLength(2);
    expect(screen.queryByText('The last scan changed nothing.')).toBeNull();
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
    expect(screen.getByRole('alert')).toHaveTextContent('Couldn’t load the library scan.');
    // An inline banner: the card's own heading is the only one.
    expect(screen.getAllByRole('heading')).toHaveLength(1);
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  describe('while the start request runs', () => {
    const startedFocus = async () => {
      let finish;
      const onScan = vi.fn(
        () =>
          new Promise((resolve) => {
            finish = resolve;
          }),
      );
      const view = renderCard({ onScan });
      const button = screen.getByRole('button', { name: 'Scan library now' });
      await userEvent.click(button);
      return { onScan, finish, view, button };
    };

    it('keeps focus on the button and makes a second press do nothing', async () => {
      const { onScan, view } = await startedFocus();
      view.rerender(
        <MemoryRouter>
          <LibraryScanCard
            scan={fixtures.adminScan}
            starting
            notice={null}
            onScan={onScan}
            onRetry={() => {}}
            changes={CHANGES}
            now={NOW}
          />
        </MemoryRouter>,
      );
      const busy = screen.getByRole('button', { name: 'Scanning…' });
      expect(busy).toHaveFocus();

      await userEvent.click(busy);

      expect(onScan).toHaveBeenCalledTimes(1);
    });

    const withScan = (scan, props) => (
      <MemoryRouter>
        <LibraryScanCard
          scan={scan}
          starting={false}
          notice={null}
          onScan={() => {}}
          onRetry={() => {}}
          changes={CHANGES}
          now={NOW}
          {...props}
        />
      </MemoryRouter>
    );

    it('moves focus to the card heading and announces the start once the progress replaces the button', async () => {
      const { finish, view } = await startedFocus();
      await act(async () => finish(true));
      expect(screen.getByRole('status')).toHaveTextContent('Scan started.');

      view.rerender(withScan({ ...fixtures.adminScan, running: true, progress: null }));

      expect(screen.queryByRole('button', { name: /Scan library now|Scanning/ })).toBeNull();
      expect(screen.getByRole('heading', { name: 'Library scan' })).toHaveFocus();
    });

    it('does not announce a start when a double press was ignored and the first start then fails', async () => {
      let finishFirst;
      const onScan = vi
        .fn()
        .mockImplementationOnce(
          () =>
            new Promise((resolve) => {
              finishFirst = resolve;
            }),
        )
        .mockResolvedValue(undefined);
      renderCard({ onScan });
      const button = screen.getByRole('button', { name: 'Scan library now' });

      await userEvent.click(button);
      await userEvent.click(button);
      expect(onScan).toHaveBeenCalledTimes(2);
      await act(async () => finishFirst(false));

      expect(screen.getByRole('status')).toBeEmptyDOMElement();
    });

    it('does not move focus that has gone elsewhere, nor announce a scan that could not start', async () => {
      const { finish, view } = await startedFocus();
      await act(async () => finish(false));
      expect(screen.getByRole('status')).toBeEmptyDOMElement();
      const tile = screen.getAllByRole('link', { name: /open the list/ })[0];
      tile.focus();

      view.rerender(withScan({ ...fixtures.adminScan, running: true, progress: null }));

      expect(screen.getByRole('heading', { name: 'Library scan' })).not.toHaveFocus();
      expect(tile).toHaveFocus();
    });
  });
});
