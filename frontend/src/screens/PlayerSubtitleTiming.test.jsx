import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';

afterEach(() => {
  vi.unstubAllGlobals();
});

// The film's own answer: the rows the subtitle is made of and their offsets, in the same order.
const SIDECAR = (ids, offsetsMs = ids.map(() => 0)) => ({
  source: 'SIDECAR',
  version: 1700000000000,
  sidecarIds: ids,
  offsetsMs,
});
const DOWNLOAD = (ids, offsetsMs = ids.map(() => 0)) => ({
  ...SIDECAR(ids, offsetsMs),
  source: 'DOWNLOAD',
});

const answerRow = (over) => ({
  ...fixtures.adminSubtitles[5],
  filmId: 1,
  editionId: 11,
  kind: 'FULL',
  served: true,
  version: 5000,
  offsetMs: 0,
  ...over,
});

/**
 * Serves film 1 changed by `mutate` and the offset route, which answers the changed row (`SidecarDto`) with its
 * `version` moved on, as the server does. `requests` records every call so a test can say exactly what went out.
 */
function serve({ mutate = () => {}, answer = () => null } = {}) {
  const film = structuredClone(fixtures.heat);
  mutate(film);
  const requests = [];
  const versions = new Map();
  installFetch((method, url, init) => {
    requests.push({ method, url, body: init?.body });
    const override = answer(method, url);
    if (override) return override;
    if (url === '/api/films/1') return respond(film);
    const put = url.match(/^\/api\/admin\/subtitles\/(\d+)\/offset$/);
    if (method === 'PUT' && put) {
      const id = Number(put[1]);
      const version = (versions.get(id) ?? 5000) + 1;
      versions.set(id, version);
      return respond(answerRow({ id, offsetMs: JSON.parse(init.body).offsetMs, version }));
    }
    return null;
  });
  return { requests };
}

const keyOf = (r) => `${r.method} ${r.url}`;
const puts = (requests) =>
  requests.filter((r) => r.method === 'PUT').map((r) => `${r.url} ${r.body}`);

async function openPlayer(options) {
  const served = serve(options);
  renderApp('/films/1/play?edition=11');
  const video = await screen.findByTestId('player-video');
  await waitFor(() => expect(video).toHaveAttribute('src'));
  fireEvent.loadedMetadata(video);
  fireEvent.timeUpdate(video);
  return { ...served, video };
}

describe('the player’s subtitle timing', () => {
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
  const panelButton = () => screen.getByRole('button', { name: 'Audio and subtitles' });
  const openPanel = () => userEvent.click(panelButton());
  const timingGroup = () =>
    within(subtitlesMenu()).queryByRole('group', { name: 'Subtitle timing' });
  const earlier = () =>
    within(subtitlesMenu()).getByRole('menuitem', { name: 'Show lines 0.25 seconds earlier' });
  const later = () =>
    within(subtitlesMenu()).getByRole('menuitem', { name: 'Show lines 0.25 seconds later' });
  const reset = () => within(subtitlesMenu()).getByRole('menuitem', { name: 'Reset timing' });
  const readout = (text) => within(subtitlesMenu()).findByText(text);
  const fullOn = (file) => (film) => {
    film.fullNeed = 'NEEDED';
    film.preferences.subtitle = 'full';
    film.editions[0].fullSubtitle = file;
  };
  const fullUrl = () => screen.getByTestId('full-subtitle-track').getAttribute('src');
  const pendingFull = () => screen.queryByTestId('full-subtitle-track-pending');
  const noListRead = (requests) =>
    expect(requests.some((r) => r.url === '/api/admin/subtitles')).toBe(false);

  describe('is offered', () => {
    it('while the owner’s own full file shows, with its timing from the film', async () => {
      await openPlayer({ mutate: fullOn(SIDECAR([70], [500])) });

      await openPanel();

      expect(timingGroup()).toBeInTheDocument();
      expect(await readout('Timing: +0.5 s')).toBeVisible();
    });

    it('while a downloaded full file shows', async () => {
      await openPlayer({ mutate: fullOn(DOWNLOAD([70])) });

      await openPanel();

      expect(timingGroup()).toBeInTheDocument();
    });

    it.each([
      ['the owner’s', SIDECAR],
      ['a downloaded', DOWNLOAD],
    ])('while Off shows %s forced file', async (_name, file) => {
      await openPlayer({
        mutate: (f) => (f.editions[0].forcedSubtitle = { ...file([70]), ownerVerified: false }),
      });

      await openPanel();

      expect(timingGroup()).toBeInTheDocument();
    });
  });

  describe('is not offered', () => {
    it('while an embedded subtitle is chosen, even if the edition has a file', async () => {
      await openPlayer({
        mutate: (f) => {
          fullOn(SIDECAR([70]))(f);
          f.preferences.subtitle = 113;
        },
      });

      await openPanel();

      expect(timingGroup()).not.toBeInTheDocument();
    });

    it('while Off shows only the embedded forced stream', async () => {
      await openPlayer({ mutate: (f) => (f.editions[0].fullSubtitle = SIDECAR([70])) });

      await openPanel();

      expect(timingGroup()).not.toBeInTheDocument();
    });

    it('for a film with no subtitle file', async () => {
      await openPlayer();

      await openPanel();

      expect(timingGroup()).not.toBeInTheDocument();
    });
  });

  it('reads and sends nothing on opening the panel: the offsets come with the film', async () => {
    const { requests } = await openPlayer({ mutate: fullOn(SIDECAR([70], [500])) });
    const before = requests.length;

    await openPanel();
    await readout('Timing: +0.5 s');

    expect(requests.length).toBe(before);
    noListRead(requests);
  });

  it('Later, Earlier and Reset send the absolute offset, nothing else, and the readout follows', async () => {
    const { requests } = await openPlayer({ mutate: fullOn(SIDECAR([70], [500])) });
    await openPanel();

    await userEvent.click(later());
    await readout('Timing: +0.75 s');
    await userEvent.click(earlier());
    await userEvent.click(earlier());
    await readout('Timing: +0.25 s');
    await userEvent.click(reset());
    await readout('Timing: No timing change');

    expect(puts(requests)).toEqual([
      '/api/admin/subtitles/70/offset {"offsetMs":750}',
      '/api/admin/subtitles/70/offset {"offsetMs":500}',
      '/api/admin/subtitles/70/offset {"offsetMs":250}',
      '/api/admin/subtitles/70/offset {"offsetMs":0}',
    ]);
    expect(
      requests.filter((r) => r.url.startsWith('/api/admin/')).every((r) => r.method === 'PUT'),
    ).toBe(true);
    expect(reset()).toHaveAttribute('aria-disabled', 'true');
  });

  it('changes every part of a split film’s subtitle, each from its own offset', async () => {
    const { requests } = await openPlayer({
      mutate: fullOn(SIDECAR([70, 71], [1000, -500])),
    });
    await openPanel();
    await readout('Timing: differs by part');

    await userEvent.click(later());
    await readout('Timing: differs by part');
    await userEvent.click(reset());
    await readout('Timing: No timing change');

    expect(puts(requests)).toEqual([
      '/api/admin/subtitles/70/offset {"offsetMs":1250}',
      '/api/admin/subtitles/71/offset {"offsetMs":-250}',
      '/api/admin/subtitles/70/offset {"offsetMs":0}',
      '/api/admin/subtitles/71/offset {"offsetMs":0}',
    ]);
  });

  describe('loading the new timing', () => {
    it('loads it beside the old track, hidden, and swaps only once it has loaded; the video is untouched', async () => {
      const load = vi.spyOn(HTMLMediaElement.prototype, 'load');
      const pause = vi.spyOn(HTMLMediaElement.prototype, 'pause');
      const play = vi.spyOn(HTMLMediaElement.prototype, 'play');
      const { requests, video } = await openPlayer({ mutate: fullOn(SIDECAR([70])) });
      const src = video.getAttribute('src');
      Object.defineProperty(video, 'currentTime', {
        configurable: true,
        writable: true,
        value: 12,
      });
      await openPanel();
      const track = screen.getByTestId('full-subtitle-track');
      const urlBefore = fullUrl();
      const before = requests.length;
      const loads = load.mock.calls.length;
      const pauses = pause.mock.calls.length;
      const plays = play.mock.calls.length;

      await userEvent.click(later());
      await readout('Timing: +0.25 s');

      // Only the offset request went out: no list, no session, no stream, no film read.
      expect(requests.slice(before).map(keyOf)).toEqual(['PUT /api/admin/subtitles/70/offset']);
      // The old track keeps showing, the new one loads hidden, with the server's own answer in its address.
      const incoming = pendingFull();
      expect(incoming).toBeInTheDocument();
      expect(incoming.getAttribute('src')).toContain('5001_250');
      expect(incoming.track.mode).toBe('hidden');
      expect(screen.getByTestId('full-subtitle-track')).toBe(track);
      expect(track.track.mode).toBe('showing');
      expect(fullUrl()).toBe(urlBefore);

      fireEvent.load(incoming);

      // Now it takes over: one track of the kind, showing, at the new address; the old one is gone.
      await waitFor(() => expect(track).not.toBeInTheDocument());
      expect(pendingFull()).not.toBeInTheDocument();
      expect(screen.getByTestId('full-subtitle-track')).toBe(incoming);
      expect(incoming.track.mode).toBe('showing');
      expect(fullUrl()).toContain('5001_250');
      // The video is untouched.
      expect(video).toHaveAttribute('src', src);
      expect(video.currentTime).toBe(12);
      expect(load.mock.calls.length).toBe(loads);
      expect(pause.mock.calls.length).toBe(pauses);
      expect(play.mock.calls.length).toBe(plays);
      load.mockRestore();
      pause.mockRestore();
      play.mockRestore();
    });

    it('if the new track fails to load the old one keeps showing and the viewer is told', async () => {
      await openPlayer({ mutate: fullOn(SIDECAR([70])) });
      await openPanel();
      const track = screen.getByTestId('full-subtitle-track');
      await userEvent.click(later());
      await readout('Timing: +0.25 s');
      const incoming = pendingFull();

      fireEvent.error(incoming);

      expect(await screen.findByRole('alert')).toHaveTextContent(
        'Couldn’t load the new timing. The old timing stays.',
      );
      expect(incoming).not.toBeInTheDocument();
      expect(screen.getByTestId('full-subtitle-track')).toBe(track);
      expect(track.track.mode).toBe('showing');
    });

    it('a later change takes over from one still loading: only the newest swaps in', async () => {
      await openPlayer({ mutate: fullOn(SIDECAR([70])) });
      await openPanel();
      const track = screen.getByTestId('full-subtitle-track');
      await userEvent.click(later());
      await readout('Timing: +0.25 s');
      const first = pendingFull();
      await userEvent.click(later());
      await readout('Timing: +0.5 s');

      await waitFor(() => expect(first).not.toBeInTheDocument());
      const second = pendingFull();
      expect(second.getAttribute('src')).toContain('5002_500');
      // A load event of the dropped one is not heard.
      fireEvent.load(first);
      expect(screen.getByTestId('full-subtitle-track')).toBe(track);
      fireEvent.load(second);

      await waitFor(() => expect(screen.getByTestId('full-subtitle-track')).toBe(second));
      expect(track).not.toBeInTheDocument();
      expect(second.track.mode).toBe('showing');
    });

    it('switching to another subtitle while it loads lets it take over without waiting, and it is not showing', async () => {
      await openPlayer({ mutate: fullOn(SIDECAR([70])) });
      await openPanel();
      const track = screen.getByTestId('full-subtitle-track');
      await userEvent.click(later());
      await readout('Timing: +0.25 s');
      const incoming = pendingFull();

      await userEvent.click(
        within(subtitlesMenu()).getByRole('menuitemradio', { name: 'English' }),
      );

      await waitFor(() => expect(pendingFull()).not.toBeInTheDocument());
      expect(track).not.toBeInTheDocument();
      expect(screen.getByTestId('full-subtitle-track')).toBe(incoming);
      expect(incoming.track.mode).toBe('disabled');
      expect(
        screen.getAllByTestId('subtitle-track').find((t) => t.getAttribute('label') === 'English')
          .track.mode,
      ).toBe('showing');
      // A load or error event that arrives late changes nothing.
      fireEvent.error(incoming);
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    });

    it('a forced file’s track loads the same way, and the full track is left alone', async () => {
      await openPlayer({
        mutate: (f) => {
          f.editions[0].forcedSubtitle = { ...SIDECAR([70]), ownerVerified: false };
          f.editions[0].fullSubtitle = SIDECAR([71]);
        },
      });
      await openPanel();
      const forced = screen.getByTestId('forced-subtitle-track');
      const full = screen.getByTestId('full-subtitle-track');

      await userEvent.click(later());
      await readout('Timing: +0.25 s');
      const incoming = screen.getByTestId('forced-subtitle-track-pending');
      fireEvent.load(incoming);

      await waitFor(() => expect(forced).not.toBeInTheDocument());
      expect(screen.getByTestId('forced-subtitle-track')).toBe(incoming);
      expect(incoming.track.mode).toBe('showing');
      expect(screen.getByTestId('full-subtitle-track')).toBe(full);
      expect(full.track.mode).toBe('disabled');
    });
  });

  it('does not load a new track when the server no longer serves the changed file, and says the subtitle changed', async () => {
    await openPlayer({
      mutate: fullOn(SIDECAR([70])),
      answer: (method) =>
        method === 'PUT' ? respond(answerRow({ id: 70, offsetMs: 250, served: false })) : null,
    });
    await openPanel();
    const track = screen.getByTestId('full-subtitle-track');

    await userEvent.click(later());

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'This subtitle has changed since the film started. Reload to see the new one.',
    );
    expect(pendingFull()).not.toBeInTheDocument();
    expect(screen.getByTestId('full-subtitle-track')).toBe(track);
    expect(track.track.mode).toBe('showing');
  });

  it('a double click sends one change; the buttons say they are busy and keep focus', async () => {
    let release;
    const gate = new Promise((resolve) => {
      release = resolve;
    });
    const { requests } = await openPlayer({
      mutate: fullOn(SIDECAR([70])),
      answer: (method) =>
        method === 'PUT'
          ? gate.then(() => respond(answerRow({ id: 70, offsetMs: 250, version: 5001 })))
          : null,
    });
    await openPanel();

    await userEvent.dblClick(later());

    await readout('Saving…');
    expect(later()).toHaveAttribute('aria-disabled', 'true');
    expect(earlier()).toHaveAttribute('aria-disabled', 'true');
    expect(later()).toHaveFocus();
    expect(pendingFull()).not.toBeInTheDocument();
    release();
    await readout('Timing: +0.25 s');
    expect(later()).toHaveFocus();
    expect(requests.filter((r) => r.method === 'PUT')).toHaveLength(1);
  });

  it('a change that finishes after the panel was closed still loads the new track, and nothing else changes', async () => {
    let release;
    const gate = new Promise((resolve) => {
      release = resolve;
    });
    const { video } = await openPlayer({
      mutate: fullOn(SIDECAR([70])),
      answer: (method) =>
        method === 'PUT'
          ? gate.then(() => respond(answerRow({ id: 70, offsetMs: 250, version: 5001 })))
          : null,
    });
    const src = video.getAttribute('src');
    await openPanel();
    const track = screen.getByTestId('full-subtitle-track');
    await userEvent.click(later());
    await userEvent.click(panelButton());
    expect(screen.queryByRole('menu', { name: 'Subtitles' })).not.toBeInTheDocument();

    release();

    await waitFor(() => expect(pendingFull()).toBeInTheDocument());
    fireEvent.load(pendingFull());
    await waitFor(() => expect(track).not.toBeInTheDocument());
    expect(fullUrl()).toContain('5001_250');
    expect(video).toHaveAttribute('src', src);
  });

  it('a late answer after the viewer switched to another subtitle file does not touch the new one', async () => {
    let release;
    const gate = new Promise((resolve) => {
      release = resolve;
    });
    await openPlayer({
      mutate: (f) => {
        fullOn(SIDECAR([70]))(f);
        f.editions[0].forcedSubtitle = { ...SIDECAR([71], [1000]), ownerVerified: false };
      },
      answer: (method, url) =>
        method === 'PUT' && url.endsWith('/70/offset')
          ? gate.then(() => respond(answerRow({ id: 70, offsetMs: 250, version: 5001 })))
          : null,
    });
    await openPanel();
    const forced = screen.getByTestId('forced-subtitle-track');
    const full = screen.getByTestId('full-subtitle-track');
    await userEvent.click(later());
    await userEvent.click(within(subtitlesMenu()).getByRole('menuitemradio', { name: /^Off/ }));
    await readout('Timing: +1 s');

    release();

    // The full file's own track is updated (its timing did change) but does not show; the forced file, which shows
    // now, is not touched.
    await waitFor(() => expect(full).not.toBeInTheDocument());
    const remounted = screen.getByTestId('full-subtitle-track');
    expect(remounted.getAttribute('src')).toContain('5001_250');
    expect(remounted.track.mode).toBe('disabled');
    expect(screen.getByTestId('forced-subtitle-track')).toBe(forced);
    expect(forced.track.mode).toBe('showing');
    expect(within(subtitlesMenu()).getByRole('status')).toHaveTextContent('Timing: +1 s');
    expect(later()).not.toHaveAttribute('aria-disabled');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('on an error shows an inline line and leaves the track, then recovers on the next try', async () => {
    let refuse = true;
    const { requests } = await openPlayer({
      mutate: fullOn(SIDECAR([70])),
      answer: (method) =>
        method === 'PUT' && refuse ? respond({ error: 'A scan is running' }, 409) : null,
    });
    await openPanel();
    const track = screen.getByTestId('full-subtitle-track');

    await userEvent.click(later());

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Couldn’t change the timing. Try again.',
    );
    expect(pendingFull()).not.toBeInTheDocument();
    expect(later()).toHaveFocus();
    expect(later()).not.toHaveAttribute('aria-disabled');
    refuse = false;
    await userEvent.click(later());
    await readout('Timing: +0.25 s');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(pendingFull()).toBeInTheDocument();
    expect(screen.getByTestId('full-subtitle-track')).toBe(track);
    expect(requests.filter((r) => r.method === 'PUT')).toHaveLength(2);
  });

  it('forgets the error when the shown subtitle changes', async () => {
    await openPlayer({
      mutate: fullOn(SIDECAR([70])),
      answer: (method) => (method === 'PUT' ? respond({ error: 'x' }, 500) : null),
    });
    await openPanel();
    await userEvent.click(later());
    await screen.findByRole('alert');

    await userEvent.click(within(subtitlesMenu()).getByRole('menuitemradio', { name: 'English' }));
    expect(timingGroup()).not.toBeInTheDocument();
    await userEvent.click(
      within(subtitlesMenu()).getByRole('menuitemradio', { name: 'English (full)' }),
    );

    expect(timingGroup()).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
