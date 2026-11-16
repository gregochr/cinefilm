import { act, fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { measureThroughput } from '../player/probe.js';
import { FakeHls } from '../test/fakeHls.js';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';
import { resetThroughputState } from '../player/throughput.js';

// hls.js at its event API, and the probe as a number: both have unit tests of their own.
vi.mock('hls.js/light', async () => ({ default: (await import('../test/fakeHls.js')).FakeHls }));
vi.mock('../player/probe.js', () => ({ measureThroughput: vi.fn() }));

beforeEach(() => {
  FakeHls.reset();
  window.MediaSource = class {};
  measureThroughput.mockReset().mockResolvedValue(null);
  sessionStorage.clear();
  resetThroughputState();
  // jsdom has no TextTrack, so give every <track> a stand-in that remembers its mode.
  const modes = new WeakMap();
  Object.defineProperty(HTMLTrackElement.prototype, 'track', {
    configurable: true,
    get() {
      if (!modes.has(this)) modes.set(this, { mode: 'disabled' });
      return modes.get(this);
    },
  });
});
afterEach(() => {
  delete window.MediaSource;
  delete HTMLTrackElement.prototype.track;
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

/**
 * Play again after Admin stopped the playback keeps the same player, so the subtitle timing changed in it stays
 * (Later ×2, stop, Play again, Earlier sends +250).
 */
function serve() {
  const film = structuredClone(fixtures.heat);
  film.fullNeed = 'NEEDED';
  film.preferences.subtitle = 'full';
  film.editions[0].fullSubtitle = {
    source: 'SIDECAR',
    version: 1700000000000,
    sidecarIds: [70],
    offsetsMs: [0],
  };
  const log = { offsets: [], filmReads: 0, posted: 0 };
  installFetch((method, url, init) => {
    if (url === '/api/films/1') {
      log.filmReads += 1;
      return respond(film);
    }
    if (method === 'POST' && url === '/api/editions/11/sessions') {
      log.posted += 1;
      return respond(
        {
          ...fixtures.session,
          sessionId: `s${log.posted}`,
          masterUrl: `/api/sessions/s${log.posted}/master.m3u8`,
        },
        201,
      );
    }
    const put = url.match(/^\/api\/admin\/subtitles\/(\d+)\/offset$/);
    if (method === 'PUT' && put) {
      const { offsetMs } = JSON.parse(init.body);
      log.offsets.push(offsetMs);
      // The server keeps it: the film read again carries it, with the version moved on.
      film.editions[0].fullSubtitle = {
        ...film.editions[0].fullSubtitle,
        offsetsMs: [offsetMs],
        version: film.editions[0].fullSubtitle.version + 1,
      };
      return respond({
        ...fixtures.adminSubtitles[5],
        id: 70,
        filmId: 1,
        editionId: 11,
        kind: 'FULL',
        served: true,
        version: film.editions[0].fullSubtitle.version,
        offsetMs,
      });
    }
    return null;
  });
  return log;
}

const hlsEvent = (fn) => act(async () => fn());
const subtitlesMenu = () => screen.getByRole('menu', { name: 'Subtitles' });
const openPanel = () =>
  userEvent.click(screen.getByRole('button', { name: 'Audio and subtitles' }));
const later = () =>
  within(subtitlesMenu()).getByRole('menuitem', { name: 'Show lines 0.25 seconds later' });
const earlier = () =>
  within(subtitlesMenu()).getByRole('menuitem', { name: 'Show lines 0.25 seconds earlier' });
const reset = () => within(subtitlesMenu()).getByRole('menuitem', { name: 'Reset timing' });

const stop = () =>
  hlsEvent(() =>
    FakeHls.last.error({ fatal: true, type: 'networkError', response: { code: 410 } }),
  );

async function laterTwiceThenStop(log) {
  renderApp('/films/1/play?edition=11');
  await screen.findByTestId('player-video');
  await vi.waitFor(() => expect(FakeHls.instances).toHaveLength(1));
  await openPanel();
  await userEvent.click(later());
  await vi.waitFor(() => expect(log.offsets).toEqual([250]));
  await userEvent.click(later());
  await vi.waitFor(() => expect(log.offsets).toEqual([250, 500]));
  await stop();
}

describe('Play again and the subtitle timing', () => {
  it('works from the timing the player already holds: Earlier sends +250 and Reset is enabled', async () => {
    const log = serve();
    await laterTwiceThenStop(log);
    await userEvent.click(screen.getByRole('button', { name: 'Play again' }));
    await vi.waitFor(() => expect(FakeHls.instances).toHaveLength(2));
    expect(log.filmReads).toBe(1); // nothing is read again: the same player goes on
    // The stop closed the panel; opening it again shows the timing the player already holds.
    await openPanel();
    expect(reset()).not.toHaveAttribute('aria-disabled');
    await userEvent.click(earlier());
    await vi.waitFor(() => expect(log.offsets).toEqual([250, 500, 250]));
  });
});

describe('Admin stops the playback', () => {
  const openPlaying = async () => {
    const log = serve();
    renderApp('/films/1/play?edition=11');
    const video = await screen.findByTestId('player-video');
    await vi.waitFor(() => expect(FakeHls.instances).toHaveLength(1));
    return { log, video };
  };
  const pendingTrack = () => screen.queryByTestId('full-subtitle-track-pending');

  describe('on iPhone, with Safari’s native fullscreen player up', () => {
    let displaying;
    let exit;
    beforeEach(() => {
      displaying = true;
      exit = vi.fn();
      Object.defineProperty(HTMLVideoElement.prototype, 'webkitDisplayingFullscreen', {
        configurable: true,
        get: () => displaying,
      });
      HTMLVideoElement.prototype.webkitExitFullscreen = exit;
    });
    afterEach(() => {
      delete HTMLVideoElement.prototype.webkitDisplayingFullscreen;
      delete HTMLVideoElement.prototype.webkitExitFullscreen;
    });

    it('leaves it, so the stopped card is not under the native player', async () => {
      await openPlaying();
      expect(exit).not.toHaveBeenCalled();

      await stop();

      expect(await screen.findByText('Playback was stopped from Admin.')).toBeInTheDocument();
      expect(exit).toHaveBeenCalledTimes(1);
    });

    it('does nothing when the native player is not showing', async () => {
      displaying = false;
      await openPlaying();

      await stop();

      await screen.findByText('Playback was stopped from Admin.');
      expect(exit).not.toHaveBeenCalled();
    });
  });

  it('closes an open menu, so focus is on Play again and Escape has no menu to close', async () => {
    await openPlaying();
    await openPanel();
    expect(screen.getByRole('menu', { name: 'Audio' })).toBeInTheDocument();

    await stop();

    expect(screen.queryByRole('menu', { name: 'Audio' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Audio and subtitles' })).toHaveAttribute(
      'aria-expanded',
      'false',
    );
    expect(screen.getByRole('button', { name: 'Play again' })).toHaveFocus();
  });

  it('Quality is aria-disabled while stopped: a press changes nothing and starts no session', async () => {
    const { log } = await openPlaying();
    await stop();

    await userEvent.click(screen.getByRole('button', { name: 'Quality' }));
    const menu = screen.getByRole('menu', { name: 'Quality' });
    const original = within(menu).getByRole('menuitemradio', { name: /^Original/ });
    expect(original).toHaveAttribute('aria-disabled', 'true');
    original.focus();
    await userEvent.click(original);

    expect(original).toHaveFocus();
    expect(within(menu).getByRole('menuitemradio', { name: /Auto/ })).toHaveAttribute(
      'aria-checked',
      'true',
    );
    expect(menu).toHaveAccessibleDescription('Quality can’t change while playback is stopped.');
    expect(log.posted).toBe(1);
    expect(FakeHls.instances).toHaveLength(1);
  });

  it('a timing change still loading when the stop comes takes over when its load arrives, and the right timing shows', async () => {
    const log = serve();
    renderApp('/films/1/play?edition=11');
    await screen.findByTestId('player-video');
    await vi.waitFor(() => expect(FakeHls.instances).toHaveLength(1));
    await openPanel();
    await userEvent.click(later());
    await vi.waitFor(() => expect(log.offsets).toEqual([250]));
    const incoming = await screen.findByTestId('full-subtitle-track-pending');
    await stop();
    expect(pendingTrack()).toBe(incoming);

    fireEvent.load(incoming);

    await vi.waitFor(() => expect(pendingTrack()).not.toBeInTheDocument());
    expect(screen.getByTestId('full-subtitle-track')).toBe(incoming);
    expect(screen.getByText('Playback was stopped from Admin.')).toBeInTheDocument();
    await openPanel();
    expect(await screen.findByText('Timing: +0.25 s')).toBeInTheDocument();
  });

  it('a timing change whose load arrives only after Play again takes over then, and nothing sends a second step', async () => {
    const log = serve();
    renderApp('/films/1/play?edition=11');
    await screen.findByTestId('player-video');
    await vi.waitFor(() => expect(FakeHls.instances).toHaveLength(1));
    await openPanel();
    await userEvent.click(later());
    await vi.waitFor(() => expect(log.offsets).toEqual([250]));
    const incoming = await screen.findByTestId('full-subtitle-track-pending');
    await stop();
    await userEvent.click(screen.getByRole('button', { name: 'Play again' }));
    await vi.waitFor(() => expect(FakeHls.instances).toHaveLength(2));

    fireEvent.load(incoming);

    await vi.waitFor(() => expect(pendingTrack()).not.toBeInTheDocument());
    await openPanel();
    expect(await screen.findByText('Timing: +0.25 s')).toBeInTheDocument();
    expect(log.offsets).toEqual([250]);
  });
});
