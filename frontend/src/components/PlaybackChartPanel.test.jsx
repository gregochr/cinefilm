import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, respond } from '../test/mockApi.jsx';
import PlaybackChartPanel from './PlaybackChartPanel.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

const NOW = new Date('2026-11-14T12:00:00.000Z');

/** A session from the API-shaped fixture, as another one: the identity and the figures a test needs differ. */
const chartRow = (n, title, over = {}) => ({
  ...structuredClone(fixtures.adminPlaybackChart.sessions[0]),
  playbackId: `00000000-0000-4000-8000-00000000000${n}`,
  filmTitle: title,
  startedAt: `2026-11-0${n}T20:00:00Z`,
  watchedMs: 4_320_000,
  startupMs: 1000 * n,
  rungMs: { original: 4_320_000 },
  ...over,
});

const SESSIONS = [
  chartRow(1, 'Alpha'),
  chartRow(2, 'Beta', {
    environment: 'train',
    stallCount: 2,
    stallMs: 9400,
    rungMs: { '480p': 4_320_000 },
  }),
  chartRow(3, 'Gamma', { environment: 'away' }),
];

const minutes7 = structuredClone(fixtures.adminPlaybackRecord.report.minutes);
const minutes5 = minutes7.map((row) => row.slice(0, 5));

/** The API-shaped record fixture, as the record of `row`, with the figures a test reads. */
const recordOf = (row, minutes) => {
  const record = structuredClone(fixtures.adminPlaybackRecord);
  record.playbackId = row.playbackId;
  record.filmTitle = row.filmTitle;
  record.startedAt = row.startedAt;
  Object.assign(record.report, {
    environment: row.environment,
    startupMs: row.startupMs,
    watchedMs: row.watchedMs,
    stallCount: row.stallCount,
    stallMs: row.stallMs,
    rungMs: row.rungMs,
    minutes,
  });
  return record;
};

/** A server for the chart and the records; `sessions` and `records` can be changed by the test. */
function server({
  sessions = SESSIONS,
  records = {},
  chartStatus = 200,
  recordStatus = 200,
  hold = false,
} = {}) {
  const state = { sessions, records, chartStatus, recordStatus };
  // With `hold`, a record's answer waits for `release()`, so a test can look at the state before it arrives.
  const held = [];
  const mock = installFetch((method, url) => {
    if (url.startsWith('/api/admin/playback-health/chart')) {
      return state.chartStatus === 200
        ? respond({ sessions: state.sessions })
        : respond({ error: 'x' }, state.chartStatus);
    }
    const match = url.match(/^\/api\/admin\/playback-health\/([^/?]+)$/);
    if (match) {
      if (state.recordStatus !== 200) return respond({ error: 'x' }, state.recordStatus);
      const row = state.sessions.find((s) => s.playbackId === match[1]);
      const answer = () => respond(recordOf(row, state.records[match[1]] ?? minutes7));
      if (hold) return new Promise((resolve) => held.push(() => resolve(answer())));
      return answer();
    }
    return null;
  });
  const urls = (prefix) => mock.mock.calls.map((c) => c[0]).filter((u) => u.startsWith(prefix));
  return {
    state,
    release: () => held.splice(0).forEach((answer) => answer()),
    charts: () => urls('/api/admin/playback-health/chart'),
    records: () => urls('/api/admin/playback-health/').filter((u) => !u.includes('/chart')),
  };
}

const column = (title) => screen.getByRole('button', { name: new RegExp(`^${title},`) });

async function ready() {
  render(<PlaybackChartPanel />);
  await screen.findByRole('group', { name: 'Film sessions, oldest to newest' });
}

describe('PlaybackChartPanel', () => {
  it('shows a static skeleton, then the tiles, the columns oldest to newest and the latest session selected', async () => {
    vi.setSystemTime(NOW);
    const api = server();
    render(<PlaybackChartPanel />);
    expect(screen.getByRole('status', { name: 'Loading how films played' })).toBeInTheDocument();
    await screen.findByRole('group', { name: 'Film sessions, oldest to newest' });

    const titles = within(screen.getByRole('group', { name: 'Film sessions, oldest to newest' }))
      .getAllByRole('button')
      .map((b) => b.getAttribute('aria-label').split(',')[0]);
    expect(titles).toEqual(['Alpha', 'Beta', 'Gamma']);
    expect(column('Gamma')).toHaveAttribute('aria-pressed', 'true');
    expect(column('Alpha')).toHaveAttribute('aria-pressed', 'false');

    const tiles = screen.getAllByRole('listitem').map((li) => li.textContent);
    expect(tiles).toEqual([
      '3sessions watched',
      '2played without a stall',
      '1stalled (all on the train)',
      '2.0 stypical start',
      '67%of time at Original or 1080p',
    ]);
    // 30 days back from the clock.
    expect(api.charts()).toEqual([
      `/api/admin/playback-health/chart?since=${encodeURIComponent('2026-10-15T12:00:00.000Z')}`,
    ]);
    expect(
      screen.getByText(/Quality and stalls for each of the last 3 sessions in 30 days/),
    ).toBeInTheDocument();
    expect(screen.getByText('Stalled (bigger = longer)')).toBeInTheDocument();
    expect(screen.getByText(/Hover or tap a column/)).toBeInTheDocument();
  });

  it('shows the selected session at once and its minutes once the record arrives', async () => {
    const api = server({ hold: true });
    await ready();
    const selected = screen.getByRole('region', { name: 'Selected film session' });
    // The record has not answered: the heading comes from the chart's row alone, and the strip is a skeleton.
    expect(selected).toHaveTextContent(/^Gamma · 03 Nov 20:00 · Mac · Away · 1h 12m watched$/);
    expect(
      within(selected).getByRole('status', { name: 'Loading the minute by minute view' }),
    ).toBeInTheDocument();
    expect(within(selected).queryByRole('img')).not.toBeInTheDocument();
    await waitFor(() => expect(api.records()).toHaveLength(1)); // asked, and held
    act(() => api.release());
    expect(
      await within(selected).findByRole('img', {
        name: 'Speed and quality minute by minute for Gamma',
      }),
    ).toBeInTheDocument();
    expect(selected).toHaveTextContent('Away (EE Limited)');
    expect(within(selected).getByText(/^Start 3\.0 s · No stalls/)).toBeInTheDocument();
    // The record's own first line is the heading, not repeated.
    expect(within(selected).queryByText(/watched · Mac/)).not.toBeInTheDocument();
    expect(api.records()).toEqual([
      '/api/admin/playback-health/00000000-0000-4000-8000-000000000003',
    ]);
  });

  it('draws the strip from rows of seven figures and from rows of five', async () => {
    server({
      records: {
        '00000000-0000-4000-8000-000000000003': minutes7,
        '00000000-0000-4000-8000-000000000002': minutes5,
      },
    });
    const user = userEvent.setup();
    await ready();
    await screen.findByRole('img', { name: /Gamma/ });
    await user.click(column('Beta'));
    expect(await screen.findByRole('img', { name: /Beta/ })).toBeInTheDocument();
    expect(screen.queryByRole('img', { name: /Gamma/ })).not.toBeInTheDocument();
  });

  it('says so when a record has no readable minute', async () => {
    server({ records: { '00000000-0000-4000-8000-000000000003': [] } });
    await ready();
    expect(
      await screen.findByText('No minute by minute figures for this session.'),
    ).toBeInTheDocument();
  });

  it('keeps one column in the tab order and moves selection and focus with the arrows, Home and End', async () => {
    server();
    const user = userEvent.setup();
    await ready();
    await screen.findByRole('img', { name: /Gamma/ });
    expect(column('Gamma')).toHaveAttribute('tabindex', '0');
    expect(column('Alpha')).toHaveAttribute('tabindex', '-1');
    expect(column('Beta')).toHaveAttribute('tabindex', '-1');

    column('Gamma').focus();
    await user.keyboard('{ArrowLeft}');
    expect(column('Beta')).toHaveFocus();
    expect(column('Beta')).toHaveAttribute('aria-pressed', 'true');
    expect(column('Beta')).toHaveAttribute('tabindex', '0');
    expect(column('Gamma')).toHaveAttribute('tabindex', '-1');
    await user.keyboard('{Home}');
    expect(column('Alpha')).toHaveFocus();
    await user.keyboard('{ArrowLeft}'); // already the first
    expect(column('Alpha')).toHaveFocus();
    await user.keyboard('{ArrowRight}');
    expect(column('Beta')).toHaveFocus();
    await user.keyboard('{End}');
    expect(column('Gamma')).toHaveFocus();
    await user.keyboard('{ArrowRight}'); // already the last
    expect(column('Gamma')).toHaveFocus();
    await user.keyboard('x');
    expect(column('Gamma')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('region', { name: 'Selected film session' })).toHaveTextContent(
      'Gamma',
    );
  });

  it('selects on a click and on hovering with a mouse, and shows that session below', async () => {
    server();
    const user = userEvent.setup();
    await ready();
    await user.click(column('Alpha'));
    expect(screen.getByRole('region', { name: 'Selected film session' })).toHaveTextContent(
      'Alpha',
    );
    await user.hover(column('Beta'));
    expect(column('Beta')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('region', { name: 'Selected film session' })).toHaveTextContent('Beta');
    expect(await screen.findByRole('img', { name: /Beta/ })).toBeInTheDocument();
  });

  it('reads the chart again for 90 days, and filters an environment without reading', async () => {
    vi.setSystemTime(NOW);
    const api = server();
    const user = userEvent.setup();
    await ready();
    await user.click(screen.getByRole('button', { name: '90 days' }));
    await waitFor(() => expect(api.charts()).toHaveLength(2));
    expect(api.charts()[1]).toBe(
      `/api/admin/playback-health/chart?since=${encodeURIComponent('2026-08-16T12:00:00.000Z')}`,
    );
    await screen.findByRole('group', { name: 'Film sessions, oldest to newest' });
    expect(screen.getByRole('button', { name: '90 days' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByText(/in 90 days/)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Train' }));
    expect(screen.getAllByRole('button', { name: /^(Alpha|Beta|Gamma),/ })).toHaveLength(1);
    expect(column('Beta')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByText(/in 90 days on the train/)).toBeInTheDocument();
    expect(api.charts()).toHaveLength(2);
    await user.click(screen.getByRole('button', { name: 'All' }));
    expect(screen.getAllByRole('button', { name: /^(Alpha|Beta|Gamma),/ })).toHaveLength(3);
  });

  it('keeps a pick across a filter that still holds it, else falls back to the newest', async () => {
    server();
    const user = userEvent.setup();
    await ready();
    await user.click(column('Alpha'));
    await user.click(screen.getByRole('button', { name: 'Home' }));
    expect(column('Alpha')).toHaveAttribute('aria-pressed', 'true');
    await user.click(screen.getByRole('button', { name: 'Away' }));
    expect(column('Gamma')).toHaveAttribute('aria-pressed', 'true');
  });

  it('shows an unknown environment only under All, as Unknown', async () => {
    server({ sessions: [chartRow(1, 'Alpha', { environment: 'unknown' }), chartRow(2, 'Beta')] });
    const user = userEvent.setup();
    await ready();
    expect(column('Alpha')).toHaveAccessibleName(/Unknown\./);
    for (const name of ['Home', 'Away', 'Train']) {
      await user.click(screen.getByRole('button', { name }));
      expect(screen.queryByRole('button', { name: /^Alpha,/ })).not.toBeInTheDocument();
    }
    await user.click(screen.getByRole('button', { name: 'All' }));
    expect(column('Alpha')).toBeInTheDocument();
  });

  it('says so when there is nothing in the range, with no selected session', async () => {
    server({ sessions: [] });
    render(<PlaybackChartPanel />);
    expect(await screen.findByText('No film sessions in this range.')).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Selected film session' })).not.toBeInTheDocument();
    expect(screen.getByText('No film sessions in the last 30 days.')).toBeInTheDocument();
  });

  it('shows an error banner for the chart, and reads it again on Try again', async () => {
    const api = server({ chartStatus: 500 });
    const user = userEvent.setup();
    render(<PlaybackChartPanel />);
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Couldn’t load how films played.');
    expect(
      screen.queryByRole('group', { name: 'Film sessions, oldest to newest' }),
    ).not.toBeInTheDocument();
    api.state.chartStatus = 200;
    await user.click(within(alert).getByRole('button', { name: 'Try again' }));
    await screen.findByRole('group', { name: 'Film sessions, oldest to newest' });
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(api.charts()).toHaveLength(2);
  });

  it('keeps the chart when only the minutes fail, and retries them alone', async () => {
    const api = server({ recordStatus: 500 });
    const user = userEvent.setup();
    await ready();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Couldn’t load that session’s minutes.');
    expect(column('Gamma')).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Selected film session' })).toHaveTextContent(
      'Gamma',
    );
    api.state.recordStatus = 200;
    await user.click(within(alert).getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('img', { name: /Gamma/ })).toBeInTheDocument();
    expect(api.charts()).toHaveLength(1);
  });

  it('says there are no minutes for a record that is gone, without a Try again that cannot help', async () => {
    server({ recordStatus: 404 });
    await ready();
    expect(
      await screen.findByText('No minute by minute figures for this session.'),
    ).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument();
  });

  it('shows a skeleton for the minutes while they load', async () => {
    installFetch((method, url) => {
      if (url.startsWith('/api/admin/playback-health/chart'))
        return respond({ sessions: SESSIONS });
      return new Promise(() => {});
    });
    render(<PlaybackChartPanel />);
    await screen.findByRole('group', { name: 'Film sessions, oldest to newest' });
    expect(
      screen.getByRole('status', { name: 'Loading the minute by minute view' }),
    ).toBeInTheDocument();
    expect(column('Gamma')).toHaveAttribute('aria-pressed', 'true');
  });
});
