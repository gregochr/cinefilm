import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { failTrack, loadTrack } from '../test/trackEvents.js';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ADMIN_TIMEOUT_MS } from '../api/timeout.js';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

// The film's own answer: the rows the subtitle is made of and their offsets, in the same order.
const SIDECAR = (ids, offsetsMs = ids.map(() => 0)) => ({
  source: 'SIDECAR',
  version: 1700000000000,
  sidecarIds: ids,
  offsetsMs,
  ownerVerified: false, // the forced subtitle carries it (the full one does not)
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
  // The readout is a status region beside the menu, not inside it.
  const panelStatus = () =>
    within(screen.getByRole('group', { name: 'Audio and subtitles' })).getByRole('status');
  const readout = (text) => screen.findByText(text);
  const fullOn = (file) => (film) => {
    film.fullNeed = 'NEEDED';
    film.preferences.subtitle = 'full';
    film.editions[0].fullSubtitle = file;
  };
  const fullUrl = () => screen.getByTestId('full-subtitle-track').getAttribute('src');
  const pendingFull = () => screen.queryByTestId('full-subtitle-track-pending');
  // The pending track of a kind, which the browser would have loaded: its load event lets it take over.
  const settle = async (kind = 'full') => {
    const element = await screen.findByTestId(`${kind}-subtitle-track-pending`);
    loadTrack(element);
    return element;
  };
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
    await settle();
    await readout('Timing: +0.75 s');
    await userEvent.click(earlier());
    await settle();
    await readout('Timing: +0.5 s');
    await userEvent.click(earlier());
    await settle();
    await readout('Timing: +0.25 s');
    await userEvent.click(reset());
    await settle();
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

  it('Later is disabled at +10 minutes, and one step short of it still works', async () => {
    const { requests } = await openPlayer({ mutate: fullOn(SIDECAR([70], [599750])) });
    await openPanel();
    expect(later()).not.toHaveAttribute('aria-disabled');

    await userEvent.click(later());
    await settle();
    await readout('Timing: +600 s');

    expect(later()).toHaveAttribute('aria-disabled', 'true');
    expect(earlier()).not.toHaveAttribute('aria-disabled');
    await userEvent.click(later());
    expect(puts(requests)).toEqual(['/api/admin/subtitles/70/offset {"offsetMs":600000}']);
  });

  it.each([
    ['Later', 599750, 'later', 600000],
    ['Earlier', -599750, 'earlier', -600000],
  ])(
    'a swap that failed at the %s limit leaves the button enabled, and pressing it retries the load',
    async (_name, start, which, limit) => {
      const { requests } = await openPlayer({ mutate: fullOn(SIDECAR([70], [start])) });
      await openPanel();
      const button = which === 'later' ? later : earlier;

      await userEvent.click(button());
      failTrack(await screen.findByTestId('full-subtitle-track-pending'));
      await screen.findByRole('alert');

      // The server is at the limit, the screen is not: the press is a retry, not a no-op.
      expect(button()).not.toHaveAttribute('aria-disabled');
      await userEvent.click(button());
      const retried = await screen.findByTestId('full-subtitle-track-pending');
      loadTrack(retried);

      expect(puts(requests)).toEqual([`/api/admin/subtitles/70/offset {"offsetMs":${limit}}`]);
      await waitFor(() => expect(button()).toHaveAttribute('aria-disabled', 'true'));
    },
  );

  it('Earlier is disabled at -10 minutes, still focusable, and sends nothing', async () => {
    const { requests } = await openPlayer({ mutate: fullOn(SIDECAR([70], [-600000])) });
    await openPanel();

    expect(earlier()).toHaveAttribute('aria-disabled', 'true');
    expect(later()).not.toHaveAttribute('aria-disabled');
    earlier().focus();
    await userEvent.click(earlier());

    expect(earlier()).toHaveFocus();
    expect(puts(requests)).toEqual([]);
  });

  it('changes every part of a split film’s subtitle, each from its own offset', async () => {
    const { requests } = await openPlayer({
      mutate: fullOn(SIDECAR([70, 71], [1000, -500])),
    });
    await openPanel();
    await readout('Timing: differs by part');

    await userEvent.click(later());
    await settle();
    await readout('Timing: differs by part');
    await userEvent.click(reset());
    await settle();
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
      await readout('Saving…');

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

      // The readout says the new timing is still being loaded.
      expect(panelStatus()).toHaveTextContent('Saving…');
      loadTrack(incoming);

      // Now it takes over: one track of the kind, showing, at the new address; the old one is gone.
      await waitFor(() => expect(track).not.toBeInTheDocument());
      await readout('Timing: +0.25 s');
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
      await readout('Saving…');
      const incoming = pendingFull();

      failTrack(incoming);

      expect(await screen.findByRole('alert')).toHaveTextContent(
        'The new timing is saved but hasn’t loaded. Press again to retry.',
      );
      // The readout describes the timing that is on screen: the old one.
      await readout('Timing: No timing change');
      expect(incoming).not.toBeInTheDocument();
      // The server is at +0.25 s now, so there is something to reset even though the screen shows no change.
      expect(reset()).not.toHaveAttribute('aria-disabled');
      expect(screen.getByTestId('full-subtitle-track')).toBe(track);
      expect(track.track.mode).toBe('showing');
    });

    it('a later change takes over from one still loading: only the newest swaps in', async () => {
      await openPlayer({ mutate: fullOn(SIDECAR([70])) });
      await openPanel();
      const track = screen.getByTestId('full-subtitle-track');
      await userEvent.click(later());
      const first = await screen.findByTestId('full-subtitle-track-pending');
      await userEvent.click(later());

      await waitFor(() => expect(first).not.toBeInTheDocument());
      const second = pendingFull();
      expect(second.getAttribute('src')).toContain('5002_500');
      // A load event of the dropped one is not heard.
      loadTrack(first);
      expect(screen.getByTestId('full-subtitle-track')).toBe(track);
      loadTrack(second);

      await waitFor(() => expect(screen.getByTestId('full-subtitle-track')).toBe(second));
      expect(track).not.toBeInTheDocument();
      expect(second.track.mode).toBe('showing');
      await readout('Timing: +0.5 s');
    });

    it('switching to another subtitle while it loads unmounts the pending track, and mounts it again, hidden, on return', async () => {
      await openPlayer({ mutate: fullOn(SIDECAR([70])) });
      await openPanel();
      const track = screen.getByTestId('full-subtitle-track');
      await userEvent.click(later());
      await readout('Saving…');
      const incoming = pendingFull();

      await userEvent.click(
        within(subtitlesMenu()).getByRole('menuitemradio', { name: 'English' }),
      );

      // Nothing took over: the loaded old track stays (disabled). The pending one is not mounted, so no system caption
      // menu lists the subtitle twice.
      expect(screen.getByTestId('full-subtitle-track')).toBe(track);
      expect(incoming).not.toBeInTheDocument();
      expect(pendingFull()).toBeNull();
      expect(screen.getAllByTestId(/^full-subtitle-track/)).toHaveLength(1);
      expect(track.track.mode).toBe('disabled');
      expect(
        screen.getAllByTestId('subtitle-track').find((t) => t.getAttribute('label') === 'English')
          .track.mode,
      ).toBe('showing');

      await userEvent.click(
        within(subtitlesMenu()).getByRole('menuitemradio', { name: 'English (full)' }),
      );

      // Back on Full: the old one shows again and the new one loads hidden. If it fails now, the old one keeps showing.
      const again = await screen.findByTestId('full-subtitle-track-pending');
      expect(again.getAttribute('src')).toBe(incoming.getAttribute('src'));
      expect(track.track.mode).toBe('showing');
      expect(again.track.mode).toBe('hidden');
      failTrack(again);
      expect(await screen.findByRole('alert')).toHaveTextContent(
        'The new timing is saved but hasn’t loaded. Press again to retry.',
      );
      expect(screen.getByTestId('full-subtitle-track')).toBe(track);
      expect(track.track.mode).toBe('showing');
      expect(again).not.toBeInTheDocument();
    });

    it('a pending track waiting while another subtitle showed loads when its kind shows again, and then takes over', async () => {
      await openPlayer({ mutate: fullOn(SIDECAR([70])) });
      await openPanel();
      const track = screen.getByTestId('full-subtitle-track');
      await userEvent.click(later());
      await screen.findByTestId('full-subtitle-track-pending');
      await userEvent.click(
        within(subtitlesMenu()).getByRole('menuitemradio', { name: 'English' }),
      );
      expect(pendingFull()).toBeNull();

      await userEvent.click(
        within(subtitlesMenu()).getByRole('menuitemradio', { name: 'English (full)' }),
      );
      loadTrack(await screen.findByTestId('full-subtitle-track-pending'));

      await waitFor(() => expect(track).not.toBeInTheDocument());
      const taken = screen.getByTestId('full-subtitle-track');
      expect(taken.getAttribute('src')).toContain('_250');
      expect(taken.track.mode).toBe('showing');
      await readout('Timing: +0.25 s');
    });

    it('a pending track that is already loaded when its kind shows again takes over at once', async () => {
      await openPlayer({ mutate: fullOn(SIDECAR([70])) });
      await openPanel();
      await userEvent.click(later());
      await screen.findByTestId('full-subtitle-track-pending');
      await userEvent.click(
        within(subtitlesMenu()).getByRole('menuitemradio', { name: 'English' }),
      );
      // The remounted element has its cues already (a cached answer): its readyState says so before anyone listens.
      const readyState = vi
        .spyOn(HTMLTrackElement.prototype, 'readyState', 'get')
        .mockReturnValue(2);

      await userEvent.click(
        within(subtitlesMenu()).getByRole('menuitemradio', { name: 'English (full)' }),
      );

      await readout('Timing: +0.25 s');
      expect(pendingFull()).toBeNull();
      expect(screen.getByTestId('full-subtitle-track').getAttribute('src')).toContain('_250');
      readyState.mockRestore();
    });

    it('a pending track that neither loads nor fails within 20 seconds is dropped and the old one stays', async () => {
      await openPlayer({ mutate: fullOn(SIDECAR([70])) });
      await openPanel();
      const track = screen.getByTestId('full-subtitle-track');
      vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'], shouldAdvanceTime: true });
      await userEvent.click(later());
      const incoming = await screen.findByTestId('full-subtitle-track-pending');

      await act(async () => vi.advanceTimersByTime(19_000));
      expect(incoming).toBeInTheDocument();
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
      await act(async () => vi.advanceTimersByTime(1_500));

      expect(await screen.findByRole('alert')).toHaveTextContent(
        'The new timing is saved but hasn’t loaded. Press again to retry.',
      );
      expect(incoming).not.toBeInTheDocument();
      expect(screen.getByTestId('full-subtitle-track')).toBe(track);
      expect(track.track.mode).toBe('showing');
    });

    it('after a failed load, Reset (nothing to send) loads the same timing again and clears the line', async () => {
      const { requests } = await openPlayer({ mutate: fullOn(SIDECAR([70], [500])) });
      await openPanel();
      await userEvent.click(reset());
      const failing = await screen.findByTestId('full-subtitle-track-pending');
      failTrack(failing);
      await screen.findByRole('alert');
      await readout('Timing: +0.5 s');
      expect(puts(requests)).toHaveLength(1);

      await userEvent.click(reset());

      const retry = await screen.findByTestId('full-subtitle-track-pending');
      expect(retry).not.toBe(failing);
      expect(retry.getAttribute('src')).toBe(failing.getAttribute('src'));
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
      expect(puts(requests)).toHaveLength(1);
      loadTrack(retry);
      await readout('Timing: No timing change');
    });

    it('after a failed load the first Earlier press loads the saved timing again and sends no second step; the next one moves', async () => {
      const { requests } = await openPlayer({ mutate: fullOn(SIDECAR([70], [500])) });
      await openPanel();
      await userEvent.click(later());
      failTrack(await screen.findByTestId('full-subtitle-track-pending'));
      expect(await screen.findByRole('alert')).toHaveTextContent(
        'The new timing is saved but hasn’t loaded. Press again to retry.',
      );
      await readout('Timing: +0.5 s');
      expect(puts(requests)).toEqual(['/api/admin/subtitles/70/offset {"offsetMs":750}']);

      await userEvent.click(earlier());

      const reloaded = await screen.findByTestId('full-subtitle-track-pending');
      expect(reloaded.getAttribute('src')).toContain('_750');
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
      expect(puts(requests)).toHaveLength(1);
      loadTrack(reloaded);
      await readout('Timing: +0.75 s');

      await userEvent.click(earlier());
      await settle();
      await readout('Timing: +0.5 s');
      expect(puts(requests)).toEqual([
        '/api/admin/subtitles/70/offset {"offsetMs":750}',
        '/api/admin/subtitles/70/offset {"offsetMs":500}',
      ]);
    });

    it('the online event loads the saved timing again by itself, once, and sends nothing', async () => {
      const { requests } = await openPlayer({ mutate: fullOn(SIDECAR([70], [500])) });
      await openPanel();
      await userEvent.click(later());
      failTrack(await screen.findByTestId('full-subtitle-track-pending'));
      await screen.findByRole('alert');

      act(() => {
        window.dispatchEvent(new Event('online'));
      });

      const reloaded = await screen.findByTestId('full-subtitle-track-pending');
      expect(reloaded.getAttribute('src')).toContain('_750');
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
      loadTrack(reloaded);
      await readout('Timing: +0.75 s');
      act(() => {
        window.dispatchEvent(new Event('online'));
      });
      expect(pendingFull()).toBeNull();
      expect(puts(requests)).toHaveLength(1);
    });

    it('a pending track waits through another subtitle showing: the 20 seconds count only while its kind shows', async () => {
      await openPlayer({ mutate: fullOn(SIDECAR([70])) });
      await openPanel();
      const track = screen.getByTestId('full-subtitle-track');
      vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'], shouldAdvanceTime: true });
      await userEvent.click(later());
      await screen.findByTestId('full-subtitle-track-pending');
      await userEvent.click(
        within(subtitlesMenu()).getByRole('menuitemradio', { name: 'English' }),
      );
      await act(async () => vi.advanceTimersByTime(25_000));
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();

      await userEvent.click(
        within(subtitlesMenu()).getByRole('menuitemradio', { name: 'English (full)' }),
      );

      expect((await screen.findByTestId('full-subtitle-track-pending')).track.mode).toBe('hidden');
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
      await act(async () => vi.advanceTimersByTime(20_500));
      expect(await screen.findByRole('alert')).toHaveTextContent(
        'The new timing is saved but hasn’t loaded. Press again to retry.',
      );
      expect(screen.getByTestId('full-subtitle-track')).toBe(track);
      expect(track.track.mode).toBe('showing');
    });

    it('a change for the other kind does not restart the shown kind’s 20 seconds', async () => {
      let release;
      const gate = new Promise((resolve) => {
        release = resolve;
      });
      await openPlayer({
        mutate: (f) => {
          f.editions[0].forcedSubtitle = SIDECAR([71]);
          f.editions[0].fullSubtitle = SIDECAR([70]);
        },
        answer: (method, url) =>
          method === 'PUT' && url.endsWith('/71/offset')
            ? gate.then(() => respond(answerRow({ id: 71, offsetMs: 250, version: 5001 })))
            : null,
      });
      await openPanel();
      vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'], shouldAdvanceTime: true });
      // Off shows the forced file: a change to it is held while the viewer moves to the full one and changes that.
      await userEvent.click(later());
      await userEvent.click(
        within(subtitlesMenu()).getByRole('menuitemradio', { name: 'English (full)' }),
      );
      await userEvent.click(later());
      await screen.findByTestId('full-subtitle-track-pending');
      // The held request gives up after 15 s, so release it a little before.
      await act(async () => vi.advanceTimersByTime(14_000));

      release();
      await act(async () => vi.advanceTimersByTime(6_500));
      // The forced change was saved but its kind does not show: nothing of it is mounted.
      expect(screen.queryByTestId('forced-subtitle-track-pending')).not.toBeInTheDocument();

      expect(await screen.findByRole('alert')).toHaveTextContent(
        'The new timing is saved but hasn’t loaded. Press again to retry.',
      );
    });

    it('the 20 second wait is over once it has loaded', async () => {
      await openPlayer({ mutate: fullOn(SIDECAR([70])) });
      await openPanel();
      vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'], shouldAdvanceTime: true });
      await userEvent.click(later());
      await settle();
      await readout('Timing: +0.25 s');

      await act(async () => vi.advanceTimersByTime(30_000));

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
      const incoming = await settle('forced');

      await waitFor(() => expect(forced).not.toBeInTheDocument());
      expect(screen.getByTestId('forced-subtitle-track')).toBe(incoming);
      expect(incoming.track.mode).toBe('showing');
      expect(screen.getByTestId('full-subtitle-track')).toBe(full);
      expect(full.track.mode).toBe('disabled');
    });
  });

  it('does not load a new track when the server no longer serves the changed file; the timing stays as shown and is frozen', async () => {
    const { requests } = await openPlayer({
      mutate: fullOn(SIDECAR([70], [500])),
      answer: (method) =>
        method === 'PUT' ? respond(answerRow({ id: 70, offsetMs: 750, served: false })) : null,
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
    // What is on screen is still the old timing.
    await readout('Timing: +0.5 s');
    for (const button of [earlier(), later(), reset()]) {
      expect(button).toHaveAttribute('aria-disabled', 'true');
    }
    await userEvent.click(later());
    expect(puts(requests)).toHaveLength(1);
  });

  it('stops at the first part the server no longer serves, sends no later part, and mounts no pending track', async () => {
    const { requests } = await openPlayer({
      mutate: fullOn(SIDECAR([70, 71, 72])),
      answer: (method, url) =>
        method === 'PUT' && url.endsWith('/71/offset')
          ? respond(answerRow({ id: 71, offsetMs: 250, served: false }))
          : null,
    });
    await openPanel();
    const track = screen.getByTestId('full-subtitle-track');

    await userEvent.click(later());

    expect(await screen.findByRole('alert')).toHaveTextContent('This subtitle has changed');
    expect(puts(requests)).toEqual([
      '/api/admin/subtitles/70/offset {"offsetMs":250}',
      '/api/admin/subtitles/71/offset {"offsetMs":250}',
    ]);
    expect(pendingFull()).not.toBeInTheDocument();
    expect(screen.getByTestId('full-subtitle-track')).toBe(track);
    expect(later()).toHaveAttribute('aria-disabled', 'true');
  });

  it('a change the server does not serve cancels the earlier one still loading: its late load does not swap', async () => {
    let presses = 0;
    await openPlayer({
      mutate: fullOn(SIDECAR([70])),
      answer: (method) => {
        if (method !== 'PUT') return null;
        presses += 1;
        return presses === 2 ? respond(answerRow({ id: 70, offsetMs: 500, served: false })) : null;
      },
    });
    await openPanel();
    const track = screen.getByTestId('full-subtitle-track');
    await userEvent.click(later());
    const first = await screen.findByTestId('full-subtitle-track-pending');

    await userEvent.click(later());

    expect(await screen.findByRole('alert')).toHaveTextContent('This subtitle has changed');
    await waitFor(() => expect(first).not.toBeInTheDocument());
    expect(pendingFull()).not.toBeInTheDocument();
    loadTrack(first);
    expect(screen.getByTestId('full-subtitle-track')).toBe(track);
    expect(track.track.mode).toBe('showing');
    expect(screen.getByRole('alert')).toHaveTextContent('This subtitle has changed');
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
    await settle();
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
    loadTrack(pendingFull());
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

    // The full file's new track is saved but not mounted (its kind does not show); the forced file, which shows now, is
    // not touched. The full kind shows it again when it comes back.
    await waitFor(() => expect(panelStatus()).toHaveTextContent('Timing: +1 s'));
    expect(screen.queryByTestId('full-subtitle-track-pending')).not.toBeInTheDocument();
    expect(screen.getByTestId('full-subtitle-track')).toBe(full);
    expect(full.track.mode).toBe('disabled');
    expect(screen.getByTestId('forced-subtitle-track')).toBe(forced);
    expect(forced.track.mode).toBe('showing');
    expect(panelStatus()).toHaveTextContent('Timing: +1 s');
    expect(later()).not.toHaveAttribute('aria-disabled');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();

    // Back on Full the saved timing is mounted and loads hidden.
    await userEvent.click(
      within(subtitlesMenu()).getByRole('menuitemradio', { name: 'English (full)' }),
    );
    const incoming = await screen.findByTestId('full-subtitle-track-pending');
    expect(incoming.getAttribute('src')).toContain('5001_250');
    expect(incoming.track.mode).toBe('hidden');
  });

  it('a change that gets no answer fails after the timeout with the plain line, and the next press works', async () => {
    let hang = true;
    const { requests } = await openPlayer({
      mutate: fullOn(SIDECAR([70])),
      answer: (method) => (method === 'PUT' && hang ? new Promise(() => {}) : null),
    });
    await openPanel();
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'], shouldAdvanceTime: true });

    await userEvent.click(later());
    await readout('Saving…');
    await act(async () => vi.advanceTimersByTime(ADMIN_TIMEOUT_MS));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Couldn’t change the timing. Try again.',
    );
    expect(later()).not.toHaveAttribute('aria-disabled');
    hang = false;
    await userEvent.click(later());
    await settle();
    await readout('Timing: +0.25 s');
    expect(puts(requests)).toEqual([
      '/api/admin/subtitles/70/offset {"offsetMs":250}',
      '/api/admin/subtitles/70/offset {"offsetMs":250}',
    ]);
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
    await readout('Saving…');
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
