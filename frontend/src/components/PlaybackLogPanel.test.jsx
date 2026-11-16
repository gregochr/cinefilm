import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AdminContext } from '../state/adminContext.js';
import { fixtures, installFetch, respond } from '../test/mockApi.jsx';
import PlaybackLogPanel from './PlaybackLogPanel.jsx';

afterEach(() => vi.unstubAllGlobals());

const BASE = '/api/admin/playback-health';
const ALL_0 = `${BASE}?page=0&size=50`;
const ALL_1 = `${BASE}?page=1&size=50`;
const FILMS_0 = `${BASE}?minWatchedMs=180000&page=0&size=50`;
const PROBLEMS_0 = `${BASE}?problems=true&page=0&size=50`;

const ROWS = fixtures.adminPlaybackHealth.playbacks;
const ALL = { playbacks: ROWS, total: ROWS.length };

function renderLog(showToast = vi.fn()) {
  render(
    <AdminContext.Provider value={{ showToast }}>
      <PlaybackLogPanel />
    </AdminContext.Provider>,
  );
  return { showToast };
}

/** The log's body rows (the header row and the Details rows left out), as Testing Library rows. */
const dataRows = () => screen.getAllByRole('row').slice(1);

/** `count` rows cloned from the fixture with their own ids, for paging. */
const manyRows = (prefix, count) =>
  Array.from({ length: count }, (_, n) => ({
    ...structuredClone(ROWS[1]),
    playbackId: `${prefix}-${n}`,
    filmTitle: `Film ${prefix} ${n}`,
  }));

/** A fixture row with its watched time replaced. */
const watched = (index, watchedMs) => {
  const row = structuredClone(ROWS[index]);
  row.report.watchedMs = watchedMs;
  return row;
};

describe('PlaybackLogPanel', () => {
  it('shows a skeleton while the first page loads, with the card’s title and subtitle', () => {
    installFetch(() => new Promise(() => {}));
    renderLog();
    const card = screen.getByRole('region', { name: 'Playback log' });
    expect(
      within(card).getByRole('status', { name: 'Loading the playback log' }),
    ).toBeInTheDocument();
    expect(
      within(card).getByText('Every time play was pressed · newest first · keeps the last 2,000'),
    ).toBeInTheDocument();
  });

  it('lists the plays with AR4’s columns, in the order the server sent them', async () => {
    installFetch((m, url) => (url === ALL_0 ? respond(ALL) : null));
    renderLog();
    const table = await screen.findByRole('table', { name: 'Playbacks, newest first' });
    expect(
      within(table)
        .getAllByRole('columnheader')
        .map((h) => h.textContent),
    ).toEqual(['Started', 'Film', 'Watched', 'Where', 'Start', 'Stalls', 'Quality', 'Details']);
    const rows = dataRows();
    expect(rows).toHaveLength(5);
    expect(
      within(rows[1])
        .getAllByRole('cell')
        .map((c) => c.textContent),
    ).toEqual(['10 Nov 19:43', 'Heat', '1h 12m', 'Train · Mac', '2.4 s', '2', '360p', 'Details']);
    expect(within(rows[3]).getAllByRole('cell')[4]).toHaveTextContent('—');
    expect(screen.getByText('Showing 5 of 5')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Show 50 more' })).not.toBeInTheDocument();
  });

  it.each([
    [179_999, true],
    [180_000, false],
    [180_001, false],
  ])('marks a play of %s ms watched as browsing: %s', async (watchedMs, browsing) => {
    installFetch((m, url) =>
      url === ALL_0 ? respond({ playbacks: [watched(0, watchedMs)], total: 1 }) : null,
    );
    renderLog();
    await screen.findByRole('table');
    expect(screen.queryAllByText('browsing')).toHaveLength(browsing ? 1 : 0);
  });

  it('puts the browsing pill on the short plays only', async () => {
    installFetch((m, url) => (url === ALL_0 ? respond(ALL) : null));
    renderLog();
    await screen.findByRole('table');
    const rows = dataRows();
    expect(within(rows[0]).getByText('browsing')).toBeInTheDocument();
    expect(within(rows[1]).queryByText('browsing')).not.toBeInTheDocument();
    expect(within(rows[2]).queryByText('browsing')).not.toBeInTheDocument();
    expect(within(rows[3]).getByText('browsing')).toBeInTheDocument();
    expect(within(rows[4]).queryByText('browsing')).not.toBeInTheDocument();
  });

  it('colours the stall count of a stalled play, and only that', async () => {
    installFetch((m, url) => (url === ALL_0 ? respond(ALL) : null));
    renderLog();
    await screen.findByRole('table');
    const rows = dataRows();
    expect(within(rows[1]).getByText('2')).toHaveClass('plog__stalls--err');
    expect(within(rows[0]).getByText('0')).not.toHaveClass('plog__stalls--err');
  });

  it('asks the server for each filter and shows exactly what it answers', async () => {
    const fetchMock = installFetch((m, url) => {
      if (url === ALL_0) return respond(ALL);
      if (url === FILMS_0) return respond({ playbacks: [ROWS[0]], total: 1 });
      if (url === PROBLEMS_0) return respond({ playbacks: [ROWS[1]], total: 1 });
      return null;
    });
    renderLog();
    await screen.findByRole('table');
    expect(screen.getByRole('button', { name: 'All' })).toHaveAttribute('aria-pressed', 'true');

    await userEvent.click(screen.getByRole('button', { name: 'Films only (3 min+)' }));
    await waitFor(() => expect(dataRows()).toHaveLength(1));
    // ROWS[0] watched under three minutes: the browser does not hide it, the server chose it.
    expect(within(dataRows()[0]).getByText('browsing')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Films only (3 min+)' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.getByRole('button', { name: 'All' })).toHaveAttribute('aria-pressed', 'false');

    await userEvent.click(screen.getByRole('button', { name: 'With problems' }));
    await waitFor(() => expect(within(dataRows()[0]).getByText('2')).toBeInTheDocument());
    await userEvent.click(screen.getByRole('button', { name: 'All' }));
    await waitFor(() => expect(dataRows()).toHaveLength(5));
    expect(fetchMock.mock.calls.map((c) => c[0])).toEqual([ALL_0, FILMS_0, PROBLEMS_0, ALL_0]);
  });

  it('says what is missing for each filter when the server sends nothing', async () => {
    installFetch((m, url) => (url.startsWith(BASE) ? respond({ playbacks: [], total: 0 }) : null));
    renderLog();
    expect(await screen.findByText('No playbacks recorded yet.')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Films only (3 min+)' }));
    expect(await screen.findByText('No plays of 3 minutes or more.')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'With problems' }));
    expect(await screen.findByText('No plays with problems.')).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('offers Show 50 more, not the empty line, when a page had no readable report but the total says more', async () => {
    const fetchMock = installFetch((m, url) => {
      if (url === ALL_0) return respond({ playbacks: [], total: 120 });
      if (url === ALL_1) return respond({ playbacks: manyRows('b', 50), total: 120 });
      return null;
    });
    renderLog();
    expect(await screen.findByText('Showing 0 of 120')).toBeInTheDocument();
    expect(screen.queryByText('No playbacks recorded yet.')).not.toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Show 50 more' }));
    await waitFor(() => expect(dataRows()).toHaveLength(50));
    expect(screen.getByText('Showing 50 of 120')).toBeInTheDocument();
    expect(fetchMock.mock.calls.map((c) => c[0])).toEqual([ALL_0, ALL_1]);
  });

  it('says the filter has nothing, with no footer, when the total is 0', async () => {
    installFetch((m, url) => (url === ALL_0 ? respond({ playbacks: [], total: 0 }) : null));
    renderLog();
    expect(await screen.findByText('No playbacks recorded yet.')).toBeInTheDocument();
    expect(screen.queryByText(/^Showing/)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Show 50 more' })).not.toBeInTheDocument();
  });

  it('shows 50, then appends the next page on Show 50 more, and stops at the total', async () => {
    let releaseSecond;
    const fetchMock = installFetch((m, url) => {
      if (url === ALL_0) return respond({ playbacks: manyRows('a', 50), total: 60 });
      if (url === ALL_1) {
        return new Promise((resolve) => {
          releaseSecond = () => resolve(respond({ playbacks: manyRows('b', 10), total: 60 }));
        });
      }
      return null;
    });
    renderLog();
    await screen.findByRole('table');
    expect(dataRows()).toHaveLength(50);
    expect(screen.getByText('Showing 50 of 60')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Show 50 more' }));
    expect(screen.getByRole('button', { name: 'Show 50 more' })).toHaveAttribute(
      'aria-disabled',
      'true',
    );
    await userEvent.click(screen.getByRole('button', { name: 'Show 50 more' }));
    expect(fetchMock.mock.calls.filter((c) => c[0] === ALL_1)).toHaveLength(1);
    releaseSecond();
    await waitFor(() => expect(dataRows()).toHaveLength(60));
    expect(screen.getByText('Showing 60 of 60')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Show 50 more' })).not.toBeInTheDocument();
  });

  it('takes Show 50 more away when a play that started meanwhile leaves the total out of reach', async () => {
    const second = manyRows('b', 10);
    second[0] = { ...second[0], playbackId: 'a-49' };
    installFetch((m, url) => {
      if (url === ALL_0) return respond({ playbacks: manyRows('a', 50), total: 60 });
      if (url === ALL_1) return respond({ playbacks: second, total: 61 });
      return null;
    });
    renderLog();
    await screen.findByRole('table');
    await userEvent.click(screen.getByRole('button', { name: 'Show 50 more' }));
    expect(await screen.findByText('Showing 59 of 61')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Show 50 more' })).not.toBeInTheDocument();
  });

  it('closes every open Details when the filter changes', async () => {
    installFetch((m, url) => {
      if (url === ALL_0) return respond(ALL);
      if (url === FILMS_0) return respond(ALL);
      return null;
    });
    renderLog();
    await screen.findByRole('table');
    await userEvent.click(within(dataRows()[1]).getByRole('button', { name: /^Details/ }));
    expect(screen.getAllByRole('button', { name: /^Hide details/ })).toHaveLength(1);
    await userEvent.click(screen.getByRole('button', { name: 'Films only (3 min+)' }));
    await screen.findByRole('table');
    expect(screen.queryByRole('button', { name: /^Hide details/ })).not.toBeInTheDocument();
  });

  it('keeps the rows and says so when more cannot be loaded', async () => {
    installFetch((m, url) => {
      if (url === ALL_0) return respond({ playbacks: manyRows('a', 50), total: 60 });
      if (url === ALL_1) return respond(null, 500);
      return null;
    });
    renderLog();
    await screen.findByRole('table');
    await userEvent.click(screen.getByRole('button', { name: 'Show 50 more' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Couldn’t load more. Try again.');
    expect(dataRows()).toHaveLength(50);
    expect(screen.getByRole('button', { name: 'Show 50 more' })).not.toHaveAttribute(
      'aria-disabled',
    );
  });

  it('announces a failed first page and Try again reads it again', async () => {
    let fail = true;
    installFetch((m, url) => (url === ALL_0 ? (fail ? respond(null, 500) : respond(ALL)) : null));
    renderLog();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Couldn’t load the playback log.');
    expect(screen.queryByRole('heading', { name: 'The projector jammed' })).not.toBeInTheDocument();
    fail = false;
    await userEvent.click(within(alert).getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('table')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  describe('Details', () => {
    const open = async (index) => {
      installFetch((m, url) => (url === ALL_0 ? respond(ALL) : null));
      const toast = { ...renderLog(), user: userEvent.setup() };
      await screen.findByRole('table');
      await toast.user.click(within(dataRows()[index]).getByRole('button', { name: /^Details/ }));
      return toast;
    };

    it('opens Player, Network, Quality and Interruptions in words and closes again', async () => {
      await open(1);
      const toggle = screen.getByRole('button', { name: 'Hide details: Heat, 10 Nov 19:43' });
      expect(toggle).toHaveAttribute('aria-expanded', 'true');
      expect(toggle).toHaveTextContent('Hide details');
      const details = document.getElementById(toggle.getAttribute('aria-controls'));
      expect(details).not.toBeNull();
      // One cell across the seven columns and the Details button's.
      expect(within(details).getByRole('cell')).toHaveAttribute('colspan', '8');
      expect(
        within(details)
          .getAllByRole('term')
          .map((t) => t.textContent),
      ).toEqual(['Player', 'Network', 'Quality', 'Interruptions']);
      expect(details).toHaveTextContent('hls.js · quality Auto · Mac · 1512×982');
      expect(details).toHaveTextContent(
        '2 stalls, 9 s · 1 seek wait · 0 reloads · 1 reconnect · 4 errors',
      );

      await userEvent.click(toggle);
      const closed = screen.getByRole('button', { name: 'Details: Heat, 10 Nov 19:43' });
      expect(closed).toHaveAttribute('aria-expanded', 'false');
      expect(closed).toHaveTextContent('Details');
      expect(document.getElementById(details.id)).toBeNull();
    });

    it('never shows the report as JSON', async () => {
      await open(1);
      const details = document.getElementById(
        screen.getByRole('button', { name: /^Hide details/ }).getAttribute('aria-controls'),
      );
      const text = within(details)
        .getAllByRole('definition')
        .map((d) => d.textContent)
        .join(' ');
      expect(text).not.toMatch(/[{}"]/);
      expect(text).not.toContain('rungMs');
      expect(text).not.toContain('stallCount');
    });

    it('opens one play without opening the others', async () => {
      await open(1);
      expect(screen.getAllByRole('button', { name: /^Hide details/ })).toHaveLength(1);
      expect(screen.getAllByRole('button', { name: /^Details/ })).toHaveLength(4);
    });

    it('copies the raw report as JSON and says Copied', async () => {
      const { showToast, user } = await open(1);
      // `userEvent.setup()` (in `open`) installed user-event's own clipboard stub once; the page's clipboard is
      // replaced after it, and the clicks go through the same set-up `user`, which does not install it again.
      const writeText = vi.fn().mockResolvedValue(undefined);
      vi.stubGlobal('navigator', { clipboard: { writeText } });
      await user.click(screen.getByRole('button', { name: 'Copy raw report' }));
      await waitFor(() => expect(showToast).toHaveBeenCalledExactlyOnceWith('Copied'));
      expect(writeText).toHaveBeenCalledExactlyOnceWith(JSON.stringify(ROWS[1].report, null, 2));
    });

    it('says honestly that the report could not be copied when the clipboard refuses', async () => {
      const { showToast, user } = await open(1);
      vi.stubGlobal('navigator', {
        clipboard: { writeText: vi.fn().mockRejectedValue(new Error('denied')) },
      });
      await user.click(screen.getByRole('button', { name: 'Copy raw report' }));
      await waitFor(() =>
        expect(showToast).toHaveBeenCalledExactlyOnceWith(
          'Couldn’t copy the report. Your browser did not allow it.',
        ),
      );
    });
  });
});
