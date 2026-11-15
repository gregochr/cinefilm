import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch } from '../../test/mockApi.jsx';
import LibraryPlexCard from './LibraryPlexCard.jsx';

beforeEach(() => {
  installFetch();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function renderCard(props) {
  return render(
    <LibraryPlexCard
      plex={fixtures.adminPlex}
      starting={false}
      notice={null}
      onImport={() => {}}
      onRecheck={() => {}}
      {...props}
    />,
  );
}

describe('LibraryPlexCard', () => {
  it('draws nothing until the status says Plex is configured', () => {
    const { container, unmount } = renderCard({ plex: null });
    expect(container).toBeEmptyDOMElement();
    unmount();

    const second = renderCard({ plex: { ...fixtures.adminPlex, configured: false } });
    expect(second.container).toBeEmptyDOMElement();
  });

  it('shows the last import, the server state and what was brought across', () => {
    renderCard();
    const card = screen.getByRole('region', { name: 'Import from Plex' });

    expect(card).toHaveAttribute('id', 'plex-import');
    expect(
      within(card).getByText('Last import 08 Nov 14:09 · 1037 films paired'),
    ).toBeInTheDocument();
    expect(within(card).getByText('Plex server').nextSibling).toHaveTextContent('Reachable');
    expect(within(card).getByText('Brought across').nextSibling).toHaveTextContent(
      '1037 paired · 378 watched · 143 resume points · 1030 posters',
    );
  });

  it('imports again on press, with the hint', async () => {
    const onImport = vi.fn();
    renderCard({ onImport });

    const again = screen.getByRole('button', { name: 'Import from Plex again' });
    expect(again).toBeEnabled();
    expect(again).not.toHaveClass('btn--primary');
    expect(again).toHaveAccessibleDescription(
      'Copies watched state, resume points and artwork; your newer CineFilm changes win.',
    );
    await userEvent.click(again);
    expect(onImport).toHaveBeenCalledTimes(1);
  });

  it('offers Try connecting to Plex only while Plex is unreachable, and turns the import off', async () => {
    const onRecheck = vi.fn();
    const { unmount } = renderCard({
      plex: { ...fixtures.adminPlex, reachable: false },
      onRecheck,
    });

    expect(screen.getByText('Plex server').nextSibling).toHaveTextContent('Not reachable');
    expect(screen.getByRole('button', { name: 'Import from Plex again' })).toBeDisabled();
    const retry = screen.getByRole('button', { name: 'Try connecting to Plex' });
    expect(retry).toHaveAccessibleDescription('Checks whether the Plex server answers now.');
    await userEvent.click(retry);
    expect(onRecheck).toHaveBeenCalledTimes(1);
    unmount();

    renderCard();
    expect(screen.queryByRole('button', { name: 'Try connecting to Plex' })).toBeNull();
  });

  it('keeps the last report behind a quiet button that opens and closes it', async () => {
    renderCard();
    const toggle = screen.getByRole('button', { name: 'Last report' });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(toggle).not.toHaveAttribute('aria-controls');
    expect(screen.queryByRole('list', { name: 'Import report' })).toBeNull();

    await userEvent.click(toggle);

    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    const tiles = screen.getByRole('list', { name: 'Import report' });
    expect(toggle).toHaveAttribute(
      'aria-controls',
      screen.getByRole('group', { name: 'Last import report' }).id,
    );
    expect(within(tiles).getByText('Paired').nextSibling).toHaveTextContent('1037');
    expect(within(tiles).getByText('Failed').nextSibling).toHaveTextContent('1');
    const missing = screen.getByRole('list', { name: 'In Plex, not on disk' });
    expect(within(missing).getAllByRole('listitem')).toHaveLength(2);
    expect(screen.getByText('and 48 more')).toBeInTheDocument();

    await userEvent.click(toggle);

    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('list', { name: 'Import report' })).toBeNull();
  });

  it('has no Last report button before the first import or after one that stopped without a report', () => {
    const { unmount } = renderCard({ plex: { ...fixtures.adminPlex, lastImport: null } });
    expect(screen.getByText('Not imported yet')).toBeInTheDocument();
    expect(screen.getByText('Brought across').nextSibling).toHaveTextContent('Nothing yet');
    expect(screen.queryByRole('button', { name: 'Last report' })).toBeNull();
    unmount();

    renderCard({
      plex: {
        ...fixtures.adminPlex,
        lastImport: {
          ...fixtures.adminPlex.lastImport,
          error: 'Plex is not reachable',
          report: null,
        },
      },
    });
    expect(
      screen.getByText('Last import 08 Nov 14:09 · Plex is not reachable'),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Last report' })).toBeNull();
  });

  it('shows live progress while an import runs, and turns the import and the retry off', () => {
    renderCard({
      plex: {
        ...fixtures.adminPlex,
        reachable: false,
        running: true,
        progress: { processed: 12, total: 40, current: 'Heat' },
      },
    });

    expect(screen.getByText('Plex server').nextSibling).toHaveTextContent('Importing');
    const bar = screen.getByRole('progressbar', { name: 'Import progress' });
    expect(bar).toHaveAttribute('aria-valuenow', '30');
    expect(bar).toHaveAttribute('aria-valuetext', '12 / 40 films');
    expect(screen.getByText('Now: Heat')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Importing…' })).toBeDisabled();
    expect(screen.queryByRole('button', { name: 'Try connecting to Plex' })).toBeNull();
  });

  it('leaves out the current film while Plex is first read', () => {
    renderCard({
      plex: {
        ...fixtures.adminPlex,
        running: true,
        progress: { processed: 0, total: 0, current: null },
      },
    });
    expect(screen.getByText('Reading Plex…')).toBeInTheDocument();
    expect(screen.queryByText(/^Now:/)).toBeNull();
  });

  it('shows a notice beside the buttons, and disables the import while the start request runs', () => {
    renderCard({ notice: 'Could not start the import.', starting: true });
    expect(screen.getByText('Could not start the import.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Import from Plex again' })).toBeDisabled();
  });

  it('holds Import credits from Plex, which copies end-credits markers, below the import', async () => {
    installFetch();
    renderCard();

    const group = await screen.findByRole('group', { name: 'Credits from Plex' });
    expect(
      await within(group).findByRole('button', { name: 'Import credits from Plex' }),
    ).toBeEnabled();
    expect(within(group).getByText(/credits start Plex holds/)).toBeInTheDocument();
  });

  it('turns the credits import off while Plex is unreachable', async () => {
    installFetch();
    renderCard({ plex: { ...fixtures.adminPlex, reachable: false } });
    const group = await screen.findByRole('group', { name: 'Credits from Plex' });
    expect(
      await within(group).findByRole('button', { name: 'Import credits from Plex' }),
    ).toBeDisabled();
  });

  it('shows the card with an inline error and Try again when the first status read failed', async () => {
    const onRecheck = vi.fn();
    renderCard({ plex: undefined, error: new Error('x'), onRecheck });

    const card = screen.getByRole('region', { name: 'Import from Plex' });
    expect(within(card).getByText('Couldn’t load the Plex import status.')).toBeInTheDocument();
    expect(within(card).queryByRole('group')).toBeNull();
    await userEvent.click(within(card).getByRole('button', { name: 'Try again' }));
    expect(onRecheck).toHaveBeenCalledTimes(1);
  });

  it('draws nothing while the first read is still running, and keeps the card when a later read fails', () => {
    installFetch();
    const { container, unmount } = renderCard({ plex: undefined, error: null });
    expect(container).toBeEmptyDOMElement();
    unmount();

    renderCard({ error: new Error('x') });
    expect(screen.queryByText('Couldn’t load the Plex import status.')).toBeNull();
    expect(screen.getByText('Plex server').nextSibling).toHaveTextContent('Reachable');
  });
});
