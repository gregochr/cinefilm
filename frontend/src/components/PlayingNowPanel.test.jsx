import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

const MAC = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)';
const IPHONE = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15';

const stream = (n, over = {}) => ({
  playbackId: `00000000-0000-4000-8000-00000000000${n}`,
  editionId: 10 + n,
  filmTitle: `Film ${n}`,
  device: MAC,
  touch: false,
  environment: 'home',
  quality: '720p',
  ...over,
});
const live = (n, over = {}) => ({
  id: `session-${n}`,
  filmTitle: `Film ${n}`,
  editionId: 10 + n,
  rung: '720p',
  speed: 3.4,
  segmentsAhead: 12,
  startedAt: '2026-11-09T19:02:11Z',
  ...over,
});

/** A server whose summary and live sessions can be changed between reads; DELETE goes to `onDelete`. */
function server({ playing, sessions, onDelete }) {
  const state = { playing, sessions };
  const mock = installFetch((method, url) => {
    if (url === '/api/admin/summary') {
      return respond({ ...fixtures.adminSummary, playingNow: state.playing });
    }
    if (url === '/api/admin/transcoding') {
      return respond({ ...fixtures.adminTranscoding, sessions: state.sessions });
    }
    if (method === 'DELETE') return onDelete?.(state, url) ?? respond(null, 204);
    return null;
  });
  const reads = (path) => mock.mock.calls.filter((c) => c[0] === path).length;
  const deletes = () => mock.mock.calls.filter((c) => c[1]?.method === 'DELETE');
  return { state, reads, deletes };
}

const card = () => screen.findByRole('region', { name: 'Now playing' });

describe('Admin Playback: Now playing', () => {
  it('lists one stream with its device, where, quality and Stop with its honest hint', async () => {
    server({ playing: [stream(1)], sessions: [live(1)] });
    renderApp('/admin/playback');
    const panel = await card();
    expect(await within(panel).findByText('Film 1')).toBeInTheDocument();
    expect(within(panel).getByText('1 stream')).toBeInTheDocument();
    expect(within(panel).getByText('Mac · Home · 720p, converting on the fly')).toBeInTheDocument();
    expect(
      within(panel).getByRole('button', { name: 'Stop this stream: Film 1 (Mac)' }),
    ).toHaveAccessibleDescription(
      'Stops converting this stream on the server; if it’s still playing, the player starts it again.',
    );
  });

  it('names an iPad by the touch hint', async () => {
    server({ playing: [stream(1, { touch: true })], sessions: [live(1)] });
    renderApp('/admin/playback');
    const panel = await card();
    expect(await within(panel).findByText(/^iPad · Home/)).toBeInTheDocument();
    expect(
      within(panel).getByRole('button', { name: 'Stop this stream: Film 1 (iPad)' }),
    ).toHaveAccessibleDescription(
      'Stops converting this stream on the server; if it’s still playing, the player starts it again.',
    );
  });

  it('lists several streams and offers Stop only where an HLS session of that edition is live', async () => {
    server({
      playing: [stream(1), stream(2, { device: IPHONE, quality: 'original' }), stream(3)],
      sessions: [live(1), live(3, { editionId: 99 })],
    });
    renderApp('/admin/playback');
    const panel = await card();
    expect(await within(panel).findByText('3 streams')).toBeInTheDocument();
    expect(within(panel).getAllByRole('listitem')).toHaveLength(3);
    expect(within(panel).getAllByRole('button')).toHaveLength(1);
    expect(
      within(panel).getByRole('button', { name: 'Stop this stream: Film 1 (Mac)' }),
    ).toBeInTheDocument();
    expect(within(panel).getByText('iPhone · Home · Original')).toBeInTheDocument();
    expect(within(panel).getByText('Mac · Home · 720p')).toBeInTheDocument();
  });

  it('says "Nothing playing." for none, with no count', async () => {
    server({ playing: [], sessions: [] });
    renderApp('/admin/playback');
    const panel = await card();
    expect(await within(panel).findByText('Nothing playing.')).toBeInTheDocument();
    expect(within(panel).queryByText(/stream/)).not.toBeInTheDocument();
    expect(within(panel).queryByRole('list')).not.toBeInTheDocument();
  });

  it('shows a skeleton while the summary loads', async () => {
    installFetch((method, url) => (url === '/api/admin/summary' ? new Promise(() => {}) : null));
    renderApp('/admin/playback');
    const panel = await card();
    expect(within(panel).getByRole('status', { name: 'Loading' })).toBeInTheDocument();
  });

  it('shows an inline error with Try again when the summary cannot be read, then recovers', async () => {
    let fail = true;
    installFetch((method, url) =>
      url === '/api/admin/summary' && fail ? respond(null, 500) : null,
    );
    renderApp('/admin/playback');
    const panel = await card();
    expect(await within(panel).findByText('Couldn’t load what is playing.')).toBeInTheDocument();
    fail = false;
    await userEvent.click(within(panel).getByRole('button', { name: 'Try again' }));
    expect(await within(panel).findByText('Heat')).toBeInTheDocument();
  });

  it('stops the stream with DELETE, hides it, re-reads both lists and moves focus to the title', async () => {
    const { state, reads, deletes } = server({
      playing: [stream(1)],
      sessions: [live(1)],
      onDelete: (s) => {
        s.playing = [];
        s.sessions = [];
        return null;
      },
    });
    renderApp('/admin/playback');
    const panel = await card();
    const stop = await within(panel).findByRole('button', {
      name: 'Stop this stream: Film 1 (Mac)',
    });
    const summaryReads = reads('/api/admin/summary');
    const sessionReads = reads('/api/admin/transcoding');
    stop.focus();
    await userEvent.click(stop);

    expect(await within(panel).findByText('Nothing playing.')).toBeInTheDocument();
    expect(deletes()).toHaveLength(1);
    expect(deletes()[0][0]).toBe('/api/admin/transcoding/sessions/session-1');
    expect(deletes()[0][1].headers['X-CineFilm']).toBe('1');
    await waitFor(() => expect(reads('/api/admin/summary')).toBeGreaterThan(summaryReads));
    await waitFor(() => expect(reads('/api/admin/transcoding')).toBeGreaterThan(sessionReads));
    expect(state.playing).toEqual([]);
    expect(screen.getByRole('heading', { level: 1, name: 'Playback' })).toHaveFocus();
  });

  it('leaves focus where it is when it had already moved off the stopped row', async () => {
    let release;
    server({
      playing: [stream(1)],
      sessions: [live(1)],
      onDelete: () =>
        new Promise((resolve) => {
          release = () => resolve(respond(null, 204));
        }),
    });
    renderApp('/admin/playback');
    const panel = await card();
    await userEvent.click(
      await within(panel).findByRole('button', { name: 'Stop this stream: Film 1 (Mac)' }),
    );
    const elsewhere = screen.getAllByRole('link', { name: 'Library' })[0];
    elsewhere.focus();
    release();
    expect(await within(panel).findByText('Nothing playing.')).toBeInTheDocument();
    expect(elsewhere).toHaveFocus();
  });

  it('ignores a second click while the stop is in flight, then settles', async () => {
    let release;
    const { deletes } = server({
      playing: [stream(1)],
      sessions: [live(1)],
      onDelete: () =>
        new Promise((resolve) => {
          release = () => resolve(respond(null, 204));
        }),
    });
    renderApp('/admin/playback');
    const panel = await card();
    const stop = await within(panel).findByRole('button', {
      name: 'Stop this stream: Film 1 (Mac)',
    });
    await userEvent.click(stop);
    await waitFor(() => expect(stop).toHaveAttribute('aria-busy', 'true'));
    expect(stop).toHaveAttribute('aria-disabled', 'true');
    await userEvent.click(stop);
    await userEvent.click(stop);
    expect(deletes()).toHaveLength(1);
    release();
    expect(await within(panel).findByText('Nothing playing.')).toBeInTheDocument();
    expect(deletes()).toHaveLength(1);
  });

  it('says so inline when Stop fails, keeps the entry and lets the next click try again', async () => {
    let fail = true;
    const { deletes } = server({
      playing: [stream(1)],
      sessions: [live(1)],
      onDelete: () => (fail ? respond(null, 500) : respond(null, 204)),
    });
    renderApp('/admin/playback');
    const panel = await card();
    const stop = await within(panel).findByRole('button', {
      name: 'Stop this stream: Film 1 (Mac)',
    });
    await userEvent.click(stop);
    expect(await within(panel).findByText('Couldn’t stop that stream. Try again.')).toHaveAttribute(
      'role',
      'status',
    );
    expect(within(panel).getByText('Film 1')).toBeInTheDocument();
    expect(stop).not.toHaveAttribute('aria-disabled');
    expect(stop).toHaveFocus();

    fail = false;
    await userEvent.click(stop);
    await waitFor(() =>
      expect(within(panel).queryByText('Couldn’t stop that stream. Try again.')).toBeNull(),
    );
    expect(deletes()).toHaveLength(2);
    expect(await within(panel).findByText('Nothing playing.')).toBeInTheDocument();
  });

  it('keeps a stopped entry hidden while only the session is gone, and shows it again with Stop on a new session', async () => {
    const { state, reads } = server({
      playing: [stream(1)],
      sessions: [live(1)],
      onDelete: (s) => {
        // The player is still playing and reporting: only the server's session ends.
        s.sessions = [];
        return null;
      },
    });
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderApp('/admin/playback');
    const panel = await card();
    const stop = await within(panel).findByRole('button', {
      name: 'Stop this stream: Film 1 (Mac)',
    });
    const before = reads('/api/admin/summary');
    await user.click(stop);
    expect(await within(panel).findByText('Nothing playing.')).toBeInTheDocument();
    // The summary was read again and still lists the stream, yet the row stays hidden.
    await waitFor(() => expect(reads('/api/admin/summary')).toBeGreaterThan(before));
    expect(state.playing).toHaveLength(1);
    expect(within(panel).getByText('Nothing playing.')).toBeInTheDocument();

    // The player recovers under the same playback id with a new session: the row returns, with Stop.
    state.sessions = [live(1, { id: 'session-new', startedAt: '2026-11-09T19:09:00Z' })];
    await act(() => vi.advanceTimersByTimeAsync(5000));
    expect(
      await within(panel).findByRole('button', { name: 'Stop this stream: Film 1 (Mac)' }),
    ).toBeInTheDocument();
    expect(within(panel).getByText('Film 1')).toBeInTheDocument();
  });

  it('stops the newest session of the edition when there are two', async () => {
    const { deletes } = server({
      playing: [stream(1)],
      sessions: [
        live(1, { id: 'older', startedAt: '2026-11-09T19:00:00Z' }),
        live(1, { id: 'newer', startedAt: '2026-11-09T19:05:00Z' }),
      ],
    });
    renderApp('/admin/playback');
    const panel = await card();
    await userEvent.click(
      await within(panel).findByRole('button', { name: 'Stop this stream: Film 1 (Mac)' }),
    );
    await waitFor(() => expect(deletes()).toHaveLength(1));
    expect(deletes()[0][0]).toBe('/api/admin/transcoding/sessions/newer');
  });
});
