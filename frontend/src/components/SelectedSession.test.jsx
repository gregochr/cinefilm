import { act, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { installFetch, respond } from '../test/mockApi.jsx';
import SelectedSession from './SelectedSession.jsx';

afterEach(() => vi.unstubAllGlobals());

const MAC = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)';
const session = (id, title) => ({
  playbackId: id,
  filmTitle: title,
  startedAt: '2026-11-10T19:43:05Z',
  watchedMs: 4_320_000,
  stallCount: 0,
  stallMs: 0,
  environment: 'home',
  userAgent: MAC,
  touch: true,
});
const A = session('00000000-0000-4000-8000-00000000000a', 'Alpha');
const B = session('00000000-0000-4000-8000-00000000000b', 'Beta');
const record = (s, operator = 'AS5089 Virgin Media Limited') => ({
  playbackId: s.playbackId,
  startedAt: s.startedAt,
  report: {
    environment: 'home',
    operator,
    latencyMs: 12,
    startupMs: 1500,
    watchedMs: s.watchedMs,
    stallCount: 0,
    stallMs: 0,
    rungMs: { original: 4_320_000 },
    kbpsMedian: 1500,
    kbpsLow: 800,
    kbpsHigh: 2000,
    kbpsSamples: 4,
    minutes: [[0, 1000, 1, 60000, 0]],
    client: { userAgent: MAC, touch: true },
  },
});

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
    expect(within(region).getByText('Start 1.5 s · No stalls')).toBeInTheDocument();
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
});
