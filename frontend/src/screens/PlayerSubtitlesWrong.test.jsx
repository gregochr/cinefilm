import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
});

const DOWNLOAD = { source: 'DOWNLOAD', version: 1700000000000, sidecarIds: [70] };
const SIDECAR = { source: 'SIDECAR', version: 1700000000000 };

// Heat (film 1, edition 11) has English audio and an embedded English subtitle (113) and forced one (114).
// The player never refetches the film under a playing viewing, so everything it knows comes from this answer.
const row = (over) => ({
  ...fixtures.adminSubtitles[5],
  id: 70,
  filmId: 1,
  editionId: 11,
  kind: 'FULL',
  origin: 'OPENSUBTITLES',
  served: true,
  ...over,
});

/**
 * Serves film 1 changed by `mutate`, the subtitle list `list` and the delete. `requests` records every call so a test
 * can say exactly what the player asked for.
 */
function serve({ mutate = () => {}, list = [row({})], answer = () => null } = {}) {
  const film = structuredClone(fixtures.heat);
  mutate(film);
  const requests = [];
  const fetchMock = installFetch((method, url, init) => {
    requests.push({ method, url, init });
    const override = answer(method, url);
    if (override) return override;
    if (url === '/api/films/1') return respond(film);
    if (url === '/api/admin/subtitles' && method === 'GET') return respond(list);
    if (method === 'DELETE' && url.startsWith('/api/admin/subtitles/')) return respond(null, 204);
    return null;
  });
  return { fetchMock, requests };
}

const keyOf = (r) => `${r.method} ${r.url}`;

async function openPlayer(options) {
  const served = serve(options);
  renderApp('/films/1/play?edition=11');
  const video = await screen.findByTestId('player-video');
  await waitFor(() => expect(video).toHaveAttribute('src'));
  fireEvent.loadedMetadata(video);
  fireEvent.timeUpdate(video);
  return { ...served, video };
}

describe('the player’s "Subtitles are wrong"', () => {
  // jsdom has no TextTrack, so give every <track> a stand-in that remembers its mode.
  const modes = new WeakMap();
  const original = Object.getOwnPropertyDescriptor(HTMLTrackElement.prototype, 'track');
  beforeEach(() => {
    Object.defineProperty(HTMLTrackElement.prototype, 'track', {
      configurable: true,
      get() {
        if (!modes.has(this)) modes.set(this, { mode: 'disabled' });
        return modes.get(this);
      },
    });
  });
  afterEach(() => {
    if (original) Object.defineProperty(HTMLTrackElement.prototype, 'track', original);
    else delete HTMLTrackElement.prototype.track;
  });

  const subtitlesMenu = () => screen.getByRole('menu', { name: 'Subtitles' });
  const openPanel = () =>
    userEvent.click(screen.getByRole('button', { name: 'Audio and subtitles' }));
  const wrongAction = () =>
    within(subtitlesMenu()).queryByRole('menuitem', { name: 'Subtitles are wrong' });
  const streamTrack = (label) =>
    screen.getAllByTestId('subtitle-track').find((t) => t.getAttribute('label') === label);
  const fullOn = (film) => {
    film.fullNeed = 'NEEDED';
    film.preferences.subtitle = 'full';
    film.editions[0].fullSubtitle = DOWNLOAD;
  };

  describe('is offered', () => {
    it('while a downloaded full subtitle shows', async () => {
      await openPlayer({ mutate: (f) => fullOn(f) });

      await openPanel();

      expect(wrongAction()).toBeInTheDocument();
    });

    it('while Off shows a downloaded forced subtitle', async () => {
      await openPlayer({
        mutate: (f) => (f.editions[0].forcedSubtitle = { ...DOWNLOAD, ownerVerified: false }),
      });

      await openPanel();

      expect(wrongAction()).toBeInTheDocument();
    });
  });

  describe('is not offered', () => {
    it('for the owner’s own full file', async () => {
      await openPlayer({
        mutate: (f) => {
          fullOn(f);
          f.editions[0].fullSubtitle = SIDECAR;
        },
      });

      await openPanel();

      expect(wrongAction()).not.toBeInTheDocument();
    });

    it('for the owner’s own forced file', async () => {
      await openPlayer({
        mutate: (f) => (f.editions[0].forcedSubtitle = { ...SIDECAR, ownerVerified: false }),
      });

      await openPanel();

      expect(wrongAction()).not.toBeInTheDocument();
    });

    it('while an embedded subtitle is chosen, even if the edition has a downloaded one', async () => {
      await openPlayer({
        mutate: (f) => {
          fullOn(f);
          f.preferences.subtitle = 113;
        },
      });

      await openPanel();

      expect(wrongAction()).not.toBeInTheDocument();
    });

    it('for a film with no downloaded subtitle', async () => {
      await openPlayer();

      await openPanel();

      expect(wrongAction()).not.toBeInTheDocument();
    });
  });

  it('deletes the download, stops showing it, falls back to Off with the embedded forced lines, and says so', async () => {
    const { requests } = await openPlayer({ mutate: (f) => fullOn(f) });
    expect(screen.getByTestId('full-subtitle-track').track.mode).toBe('showing');
    await openPanel();

    await userEvent.click(wrongAction());

    expect(
      await screen.findByText('Subtitle download removed. CineFilm won’t use it again.'),
    ).toBeInTheDocument();
    expect(requests.map(keyOf).filter((k) => k.includes('/api/admin/subtitles'))).toEqual([
      'GET /api/admin/subtitles',
      'DELETE /api/admin/subtitles/70',
    ]);
    expect(screen.queryByTestId('full-subtitle-track')).not.toBeInTheDocument();
    expect(streamTrack('English (forced)').track.mode).toBe('showing');
    expect(streamTrack('English').track.mode).toBe('disabled');
    expect(within(subtitlesMenu()).getByRole('menuitemradio', { name: /^Off/ })).toHaveAttribute(
      'aria-checked',
      'true',
    );
    expect(
      within(subtitlesMenu()).queryByRole('menuitemradio', { name: 'English (full)' }),
    ).not.toBeInTheDocument();
  });

  it('after the removal the action is gone and focus is on the checked subtitle, not lost', async () => {
    await openPlayer({ mutate: (f) => fullOn(f) });
    await openPanel();

    await userEvent.click(wrongAction());

    await waitFor(() => expect(wrongAction()).not.toBeInTheDocument());
    await waitFor(() =>
      expect(within(subtitlesMenu()).getByRole('menuitemradio', { name: /^Off/ })).toHaveFocus(),
    );
  });

  it('removing a downloaded forced subtitle drops its track and the embedded forced lines show instead', async () => {
    const { requests } = await openPlayer({
      mutate: (f) => (f.editions[0].forcedSubtitle = { ...DOWNLOAD, ownerVerified: false }),
      list: [row({ kind: 'FORCED' })],
    });
    expect(screen.getByTestId('forced-subtitle-track').track.mode).toBe('showing');
    await openPanel();

    await userEvent.click(wrongAction());

    await waitFor(() =>
      expect(screen.queryByTestId('forced-subtitle-track')).not.toBeInTheDocument(),
    );
    expect(streamTrack('English (forced)').track.mode).toBe('showing');
    expect(requests.map(keyOf)).toContain('DELETE /api/admin/subtitles/70');
  });

  it('deletes nothing that is not the playing download of this film, edition and kind', async () => {
    const { requests } = await openPlayer({
      mutate: (f) => fullOn(f),
      list: [
        row({ id: 71, editionId: 99 }),
        row({ id: 72, filmId: 2 }),
        row({ id: 73, kind: 'FORCED' }),
        row({ id: 74, origin: 'FILE' }),
        row({ id: 75, served: false }),
        row({ id: 70 }),
      ],
    });
    await openPanel();

    await userEvent.click(wrongAction());

    await screen.findByText('Subtitle download removed. CineFilm won’t use it again.');
    expect(requests.filter((r) => r.method === 'DELETE').map(keyOf)).toEqual([
      'DELETE /api/admin/subtitles/70',
    ]);
  });

  it('a double click sends one delete', async () => {
    let release;
    const gate = new Promise((resolve) => {
      release = resolve;
    });
    const { requests } = await openPlayer({
      mutate: (f) => fullOn(f),
      answer: (method) => (method === 'DELETE' ? gate.then(() => respond(null, 204)) : null),
    });
    await openPanel();

    await userEvent.dblClick(wrongAction());
    expect(within(subtitlesMenu()).getByRole('menuitem', { name: 'Removing…' })).toHaveAttribute(
      'aria-disabled',
      'true',
    );
    release();

    await screen.findByText('Subtitle download removed. CineFilm won’t use it again.');
    expect(requests.filter((r) => r.method === 'DELETE')).toHaveLength(1);
  });

  it('on failure keeps the subtitle showing, shows the line and lets the viewer try again', async () => {
    let refuse = true;
    const { requests } = await openPlayer({
      mutate: (f) => fullOn(f),
      answer: (method) =>
        method === 'DELETE' && refuse ? respond({ error: 'A scan is running' }, 409) : null,
    });
    await openPanel();

    await userEvent.click(wrongAction());

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Couldn’t remove the download. Try again.',
    );
    expect(screen.getByTestId('full-subtitle-track').track.mode).toBe('showing');
    expect(
      screen.queryByText('Subtitle download removed. CineFilm won’t use it again.'),
    ).not.toBeInTheDocument();
    refuse = false;
    await userEvent.click(wrongAction());
    await screen.findByText('Subtitle download removed. CineFilm won’t use it again.');
    expect(requests.filter((r) => r.method === 'DELETE')).toHaveLength(2);
  });

  it('says so when the playing download cannot be found in the list, and deletes nothing', async () => {
    const { requests } = await openPlayer({ mutate: (f) => fullOn(f), list: [] });
    await openPanel();

    await userEvent.click(wrongAction());

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Couldn’t find that download to remove.',
    );
    expect(requests.some((r) => r.method === 'DELETE')).toBe(false);
    expect(screen.getByTestId('full-subtitle-track')).toBeInTheDocument();
  });

  it('never touches the playback: no session, seek, reload or source change, and only the two subtitle requests', async () => {
    const load = vi.spyOn(HTMLMediaElement.prototype, 'load');
    const { requests, video } = await openPlayer({ mutate: (f) => fullOn(f) });
    const src = video.getAttribute('src');
    const before = requests.length;
    const loads = load.mock.calls.length;
    Object.defineProperty(video, 'currentTime', { configurable: true, writable: true, value: 12 });
    await openPanel();

    await userEvent.click(wrongAction());
    await screen.findByText('Subtitle download removed. CineFilm won’t use it again.');

    expect(requests.slice(before).map(keyOf)).toEqual([
      'GET /api/admin/subtitles',
      'DELETE /api/admin/subtitles/70',
    ]);
    expect(video).toHaveAttribute('src', src);
    expect(video.currentTime).toBe(12);
    expect(load.mock.calls.length).toBe(loads);
    load.mockRestore();
  });

  it('keeps a subtitle the viewer picked while the delete was running', async () => {
    let release;
    const gate = new Promise((resolve) => {
      release = resolve;
    });
    await openPlayer({
      mutate: (f) => fullOn(f),
      answer: (method) => (method === 'DELETE' ? gate.then(() => respond(null, 204)) : null),
    });
    await openPanel();
    await userEvent.click(wrongAction());
    await userEvent.click(within(subtitlesMenu()).getByRole('menuitemradio', { name: 'English' }));

    release();
    await screen.findByText('Subtitle download removed. CineFilm won’t use it again.');

    expect(within(subtitlesMenu()).getByRole('menuitemradio', { name: 'English' })).toHaveAttribute(
      'aria-checked',
      'true',
    );
    expect(streamTrack('English').track.mode).toBe('showing');
    expect(screen.queryByTestId('full-subtitle-track')).not.toBeInTheDocument();
  });

  it('deletes nothing and says so when another download plays now than the one the film showed', async () => {
    // The film loaded with download 70; since then another tab chose 71, so the list serves 71 and not 70.
    const { requests } = await openPlayer({
      mutate: (f) => fullOn(f),
      list: [row({ id: 71 })],
    });
    await openPanel();

    await userEvent.click(wrongAction());

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'This subtitle has changed since the film started. Reload to see the new one.',
    );
    expect(requests.some((r) => r.method === 'DELETE')).toBe(false);
    expect(screen.getByTestId('full-subtitle-track')).toBeInTheDocument();
  });

  it('deletes only the download the film showed when the list also serves another', async () => {
    const { requests } = await openPlayer({
      mutate: (f) => fullOn(f),
      list: [row({ id: 71 }), row({ id: 70 })],
    });
    await openPanel();

    await userEvent.click(wrongAction());

    await screen.findByText('Subtitle download removed. CineFilm won’t use it again.');
    expect(requests.filter((r) => r.method === 'DELETE').map(keyOf)).toEqual([
      'DELETE /api/admin/subtitles/70',
    ]);
  });
});
