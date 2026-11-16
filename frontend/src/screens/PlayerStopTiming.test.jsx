import { act, screen, within } from '@testing-library/react';
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

async function laterTwiceThenStop(log) {
  renderApp('/films/1/play?edition=11');
  await screen.findByTestId('player-video');
  await vi.waitFor(() => expect(FakeHls.instances).toHaveLength(1));
  await openPanel();
  await userEvent.click(later());
  await vi.waitFor(() => expect(log.offsets).toEqual([250]));
  await userEvent.click(later());
  await vi.waitFor(() => expect(log.offsets).toEqual([250, 500]));
  await hlsEvent(() =>
    FakeHls.last.error({ fatal: true, type: 'networkError', response: { code: 410 } }),
  );
}

describe('Play again and the subtitle timing', () => {
  it('works from the timing the player already holds: Earlier sends +250 and Reset is enabled', async () => {
    const log = serve();
    await laterTwiceThenStop(log);
    await userEvent.click(screen.getByRole('button', { name: 'Play again' }));
    await vi.waitFor(() => expect(FakeHls.instances).toHaveLength(2));
    expect(log.filmReads).toBe(1); // nothing is read again: the same player goes on
    // The panel stays open across the stop: nothing was remounted.
    expect(reset()).not.toHaveAttribute('aria-disabled');
    await userEvent.click(earlier());
    await vi.waitFor(() => expect(log.offsets).toEqual([250, 500, 250]));
  });
});
