import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, respond } from '../test/mockApi.jsx';
import SelectedSession from './SelectedSession.jsx';

afterEach(() => vi.unstubAllGlobals());

/** A session from the API-shaped chart fixture (on a Mac with a touch hint, so it reads as an iPad), as another one. */
const session = (id, title) => ({
  ...structuredClone(fixtures.adminPlaybackChart.sessions[0]),
  playbackId: id,
  filmTitle: title,
  startedAt: '2026-11-10T19:43:05Z',
  endedAt: '2026-11-10T20:55:35Z',
  watchedMs: 4_320_000,
  environment: 'home',
  touch: true,
  parts: [
    {
      playbackId: id,
      startedAt: '2026-11-10T19:43:05Z',
      endedAt: '2026-11-10T20:55:35Z',
      watchedMs: 4_320_000,
      environment: 'home',
    },
  ],
});
const A = session('00000000-0000-4000-8000-00000000000a', 'Alpha');
const B = session('00000000-0000-4000-8000-00000000000b', 'Beta');
/** The API-shaped record fixture, as the record of `s` with a quiet, smooth report. */
const record = (s, operator = 'AS5089 Virgin Media Limited') => {
  const full = structuredClone(fixtures.adminPlaybackRecord);
  full.playbackId = s.playbackId;
  full.startedAt = s.startedAt;
  Object.assign(full.report, {
    environment: 'home',
    operator,
    latencyMs: 12,
    startupMs: 1500,
    watchedMs: s.watchedMs,
    stallCount: 0,
    stallMs: 0,
    reconnectCount: 0,
    rungMs: { original: 4_320_000 },
    kbpsMedian: 1500,
    kbpsLow: 800,
    kbpsHigh: 2000,
    kbpsSamples: 4,
  });
  return full;
};

describe('SelectedSession', () => {
  it('names the film and its facts at once, the device read with the touch hint', () => {
    installFetch(() => new Promise(() => {}));
    render(<SelectedSession session={A} />);
    expect(screen.getByRole('region', { name: 'Selected film session' })).toHaveTextContent(
      'Alpha · 10 Nov 19:43 · iPad · Home · 1h 12m watched',
    );
    expect(
      screen.getByRole('status', { name: 'Loading the minute by minute view' }),
    ).toBeInTheDocument();
  });

  it('adds the operator and the figures lines in mono once the record arrives', async () => {
    installFetch((m, url) => (url.endsWith(A.playbackId) ? respond(record(A)) : null));
    render(<SelectedSession session={A} />);
    const region = screen.getByRole('region', { name: 'Selected film session' });
    await within(region).findByRole('img', { name: /Alpha/ });
    expect(region).toHaveTextContent('Home (Virgin Media Limited)');
    expect(within(region).getByText('19:43–20:55 · Start 1.5 s · No stalls')).toBeInTheDocument();
    expect(
      within(region).getByText(/^Original 100% · 0\.8 to 2\.0 Mbit\/s, typically 1\.5$/),
    ).toBeInTheDocument();
    expect(within(region).getByText('Virgin Media Limited · 12 ms')).toBeInTheDocument();
  });

  it('leaves the place bare when the record has no operator', async () => {
    installFetch(() => respond(record(A, null)));
    render(<SelectedSession session={A} />);
    await screen.findByRole('img', { name: /Alpha/ });
    expect(screen.getByRole('region', { name: 'Selected film session' })).toHaveTextContent(
      'iPad · Home · 1h 12m',
    );
  });

  it('shows only the newer selection when the older one answers last', async () => {
    const gates = {};
    installFetch((m, url) => {
      const id = url.split('/').pop();
      return new Promise((resolve) => {
        gates[id] = resolve;
      });
    });
    const { rerender } = render(<SelectedSession session={A} />);
    rerender(<SelectedSession session={B} />);
    await act(async () => gates[B.playbackId](respond(record(B))));
    expect(await screen.findByRole('img', { name: /Beta/ })).toBeInTheDocument();
    await act(async () => gates[A.playbackId](respond(record(A))));
    expect(screen.getByRole('img', { name: /Beta/ })).toBeInTheDocument();
    expect(screen.queryByRole('img', { name: /Alpha/ })).not.toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Selected film session' })).toHaveTextContent('Beta');
  });

  it('says there are no minutes for a record that is gone (404), and offers no retry', async () => {
    installFetch(() => respond({ error: 'No such playback' }, 404));
    render(<SelectedSession session={A} />);
    expect(
      await screen.findByText('No minute by minute figures for this session.'),
    ).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument();
  });

  it('shows an error banner with a retry for any other failure (500)', async () => {
    installFetch(() => respond({ error: 'x' }, 500));
    render(<SelectedSession session={A} />);
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Couldn’t load that session’s minutes.');
    expect(within(alert).getByRole('button', { name: 'Try again' })).toBeInTheDocument();
    expect(
      screen.queryByText('No minute by minute figures for this session.'),
    ).not.toBeInTheDocument();
  });

  it('shows the times of a row from an older server (no parts, no end): just the start', async () => {
    const old = { ...A };
    delete old.parts;
    delete old.endedAt;
    installFetch(() => respond(record(A)));
    render(<SelectedSession session={old} />);
    expect(await screen.findByText('19:43 · Start 1.5 s · No stalls')).toBeInTheDocument();
    expect(screen.queryByText(/in 2 parts|Paused|Picked up/)).not.toBeInTheDocument();
  });

  describe('a viewing of two parts', () => {
    const ID1 = 'fb22ac5e-0000-4000-8000-000000000001';
    const ID2 = '323111c2-0000-4000-8000-000000000002';
    const part = (id, startedAt, endedAt, watchedMs) => ({
      playbackId: id,
      startedAt,
      endedAt,
      watchedMs,
      environment: 'train',
    });
    const two = (second = ['2026-11-12T17:56:53.956Z', '2026-11-12T20:33:53.080Z']) => ({
      ...session(ID1, 'The Rock'),
      startedAt: '2026-11-12T17:23:30.519Z',
      endedAt: second[1],
      watchedMs: 8_280_000,
      environment: 'train',
      parts: [
        part(ID1, '2026-11-12T17:23:30.519Z', '2026-11-12T17:56:47.761Z', 1_920_000),
        part(ID2, second[0], second[1], 6_360_000),
      ],
    });
    const partRecord = (id, startedAt, startupMs, stallCount, stallMs) => {
      const full = record(A);
      full.playbackId = id;
      full.startedAt = startedAt;
      Object.assign(full.report, { startupMs, stallCount, stallMs, environment: 'train' });
      return full;
    };
    const serve = (overrides = {}) =>
      installFetch((m, url) => {
        const id = url.split('/').pop();
        if (id in overrides) return overrides[id];
        if (id === ID1) return respond(partRecord(ID1, '2026-11-12T17:23:30.519Z', 4800, 4, 9000));
        if (id === ID2) {
          return respond(partRecord(ID2, '2026-11-12T17:56:53.956Z', 6300, 155, 2_011_000));
        }
        return null;
      });

    it('heads the viewing with the first part’s time and the total, in 2 parts', () => {
      serve();
      render(<SelectedSession session={two()} />);
      expect(screen.getByRole('region', { name: 'Selected film session' })).toHaveTextContent(
        'The Rock · 12 Nov 17:23 · iPad · Train · 2h 18m watched in 2 parts',
      );
    });

    it('shows each part’s strip and figures, its times first, with the gap line between', async () => {
      serve();
      render(<SelectedSession session={two()} />);
      expect(
        await screen.findByText('17:23–17:56 · Start 4.8 s · 4 stalls, 9 s'),
      ).toBeInTheDocument();
      expect(
        screen.getByText('17:56–20:33 · Start 6.3 s · 155 stalls, 33m 31s'),
      ).toBeInTheDocument();
      expect(screen.getAllByRole('img', { name: /The Rock/ })).toHaveLength(2);
      expect(screen.getByText('Picked up again after 6 s')).toBeInTheDocument();
    });

    it('says how long the pause was when the gap is a minute or more', async () => {
      serve();
      render(
        <SelectedSession session={two(['2026-11-12T19:16:47.761Z', '2026-11-12T20:33:53Z'])} />,
      );
      expect(await screen.findByText('Paused 1h 20m')).toBeInTheDocument();
      expect(screen.queryByText(/Picked up again/)).not.toBeInTheDocument();
    });

    it('draws the gap line at the exact boundary: 59,999 ms picks up, 60,000 ms pauses', async () => {
      serve();
      const { unmount } = render(
        <SelectedSession session={two(['2026-11-12T17:57:47.760Z', '2026-11-12T20:33:53Z'])} />,
      );
      expect(await screen.findByText('Picked up again after 59 s')).toBeInTheDocument();
      unmount();
      render(
        <SelectedSession session={two(['2026-11-12T17:57:47.761Z', '2026-11-12T20:33:53Z'])} />,
      );
      expect(await screen.findByText('Paused 1m')).toBeInTheDocument();
    });

    it('puts the date on the end of a part that runs past midnight', async () => {
      serve();
      const midnight = two();
      midnight.parts[1] = part(ID2, '2026-11-11T23:40:00Z', '2026-11-12T01:10:00Z', 6_360_000);
      midnight.parts[0] = part(ID1, '2026-11-11T22:00:00Z', '2026-11-11T23:39:50Z', 1_920_000);
      render(<SelectedSession session={midnight} />);
      expect(await screen.findByText(/^23:40–12 Nov 01:10 · /)).toBeInTheDocument();
    });

    it('lets one part’s record be gone (404) while the other still shows its figures', async () => {
      serve({ [ID1]: respond({ error: 'No such playback' }, 404) });
      render(<SelectedSession session={two()} />);
      expect(
        await screen.findByText('17:56–20:33 · Start 6.3 s · 155 stalls, 33m 31s'),
      ).toBeInTheDocument();
      expect(screen.getByText('No minute by minute figures for this session.')).toBeInTheDocument();
      expect(screen.getAllByRole('img', { name: /The Rock/ })).toHaveLength(1);
      expect(screen.getByText('Picked up again after 6 s')).toBeInTheDocument();
    });

    it('shows a loading skeleton for the part still loading, and an error with its own retry for a failed one', async () => {
      const user = userEvent.setup();
      let tries = 0;
      installFetch((m, url) => {
        const id = url.split('/').pop();
        if (id === ID2) return new Promise(() => {});
        tries += 1;
        return tries === 1
          ? respond({ error: 'x' }, 500)
          : respond(partRecord(ID1, '2026-11-12T17:23:30.519Z', 4800, 4, 9000));
      });
      render(<SelectedSession session={two()} />);
      const alert = await screen.findByRole('alert');
      expect(
        screen.getByRole('status', { name: 'Loading the minute by minute view, 17:56–20:33' }),
      ).toBeInTheDocument();
      await user.click(within(alert).getByRole('button', { name: /Try again.*17:23/ }));
      expect(
        await screen.findByText('17:23–17:56 · Start 4.8 s · 4 stalls, 9 s'),
      ).toBeInTheDocument();
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
      expect(
        screen.getByRole('status', { name: 'Loading the minute by minute view, 17:56–20:33' }),
      ).toBeInTheDocument();
    });

    it('tells two failing parts apart: one retry button each, re-reading only its own part', async () => {
      const user = userEvent.setup();
      const reads = [];
      installFetch((m, url) => {
        reads.push(url.split('/').pop());
        return respond({ error: 'x' }, 500);
      });
      render(<SelectedSession session={two()} />);
      const retry = await screen.findByRole('button', { name: /Try again.*17:23/ });
      expect(screen.getByRole('button', { name: /Try again, 17:56/ })).toBeInTheDocument();
      expect(screen.getAllByRole('alert')).toHaveLength(2);
      reads.length = 0;
      await user.click(retry);
      await screen.findByRole('button', { name: /Try again.*17:23/ });
      expect(reads).toEqual([ID1]);
    });

    it('shows the place on a later part only when it differs from the first part’s', async () => {
      installFetch((m, url) => {
        const id = url.split('/').pop();
        const full = partRecord(id, '2026-11-12T17:56:53.956Z', 6300, 155, 2_011_000);
        full.report.operator = id === ID1 ? 'AS5089 Virgin Media Limited' : 'AS12576 EE Limited';
        return respond(full);
      });
      render(<SelectedSession session={two()} />);
      expect(
        await screen.findByText(/^Train \(EE Limited\) · 17:56–20:33 · Start 6\.3 s/),
      ).toBeInTheDocument();
      expect(screen.getByText(/^17:23–17:56 · Start/)).toBeInTheDocument();
    });

    it('shows no place on a later part that is on the same network as the first', async () => {
      serve();
      render(<SelectedSession session={two()} />);
      expect(
        await screen.findByText('17:56–20:33 · Start 6.3 s · 155 stalls, 33m 31s'),
      ).toBeInTheDocument();
    });

    it('takes the operator for the heading from the first part’s record', async () => {
      serve();
      render(<SelectedSession session={two()} />);
      await screen.findByText('17:23–17:56 · Start 4.8 s · 4 stalls, 9 s');
      expect(screen.getByRole('region', { name: 'Selected film session' })).toHaveTextContent(
        'Train (Virgin Media Limited)',
      );
    });
  });
});
