import { act, fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { measureThroughput } from '../player/probe.js';
import { FakeHls } from '../test/fakeHls.js';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';
import { resetThroughputState } from '../player/throughput.js';

// hls.js at its event API, and the probe as a number: both have unit tests of their own.
vi.mock('hls.js', async () => ({ default: (await import('../test/fakeHls.js')).FakeHls }));
vi.mock('../player/probe.js', () => ({ measureThroughput: vi.fn() }));

beforeEach(() => {
  FakeHls.reset();
  window.MediaSource = class {};
  measureThroughput.mockReset().mockResolvedValue(null);
  sessionStorage.clear();
  resetThroughputState();
});
afterEach(() => {
  delete window.MediaSource;
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

// hls.js lists levels lowest bitrate first: the session fixture is original 1080p, 720p, 480p, 360p.
const LEVELS = [
  { height: 360, bitrate: 600000 },
  { height: 480, bitrate: 1000000 },
  { height: 720, bitrate: 2500000 },
  { height: 1080, bitrate: 24000000 },
];
const [P360, P480, P720, ORIGINAL] = [0, 1, 2, 3];
const DIRECT = '/api/editions/11/stream';

// Heat with every rung available (the worker is up), or Auto and the rungs off (it is down).
function heat({ offline = false, quality = 'auto' } = {}) {
  const film = structuredClone(fixtures.heat);
  film.preferences.quality = quality;
  for (const q of film.editions[0].qualities) q.available = !offline || q.id === 'original';
  return film;
}

// The API as the player sees it: Heat, session create/remove, everything else the defaults.
function serve({ film = heat(), create } = {}) {
  const log = { posted: [], deleted: [], progress: [] };
  let n = 0;
  const route = (method, url, init = {}) => {
    if (url === '/api/films/1') return respond(film);
    if (method === 'POST' && url === '/api/editions/11/sessions') {
      log.posted.push(JSON.parse(init.body));
      if (create) return create(log.posted.length);
      n += 1;
      return respond(
        {
          ...fixtures.session,
          sessionId: `s${n}`,
          masterUrl: `/api/sessions/s${n}/master.m3u8`,
        },
        201,
      );
    }
    if (method === 'DELETE' && url.startsWith('/api/sessions/')) {
      log.deleted.push({ url, init });
      return respond(null, 204);
    }
    if (method === 'PUT' && url === '/api/editions/11/progress') {
      log.progress.push(JSON.parse(init.body).positionMs);
    }
    return null;
  };
  return { log, route };
}

const instances = (count) => vi.waitFor(() => expect(FakeHls.instances).toHaveLength(count));
const hlsEvent = (fn) => act(async () => fn());

// Opens the player and waits for the first hls.js session, whose manifest has arrived at `startLevel`.
async function open({ film, create, startLevel = ORIGINAL } = {}) {
  const srv = serve({ film, create });
  const fetchMock = installFetch(srv.route);
  const view = renderApp('/films/1/play?edition=11');
  const video = await screen.findByTestId('player-video');
  await instances(1);
  await hlsEvent(() => FakeHls.last.manifest(LEVELS, startLevel));
  await hlsEvent(() => FakeHls.last.switchTo(startLevel));
  return { ...srv, fetchMock, video, view, hls: () => FakeHls.last };
}

// The page-load resume lands: metadata, then the first time update at the resume position.
async function landResume(video) {
  await hlsEvent(() => fireEvent.loadedMetadata(video));
  await hlsEvent(() => fireEvent.timeUpdate(video));
}

async function moveTo(video, seconds) {
  video.currentTime = seconds;
  await hlsEvent(() => fireEvent.timeUpdate(video));
}

describe('starting a session', () => {
  it('creates an Auto session at the resume position, with the saved audio and the probe result', async () => {
    measureThroughput.mockResolvedValue(3000);
    const { log, hls } = await open();
    expect(measureThroughput).toHaveBeenCalledWith(DIRECT, expect.any(Object));
    // Heat is saved at 3,871,000 ms and resumes 10 s earlier.
    expect(log.posted).toEqual([
      { quality: 'auto', startMs: 3861000, audioStreamId: 111, probeKbps: 3000 },
    ]);
    expect(hls().loadSource).toHaveBeenCalledWith('/api/sessions/s1/master.m3u8');
    expect(hls().config.startPosition).toBe(3861);
  });

  it('sends probeKbps 0 (the lowest rung) when the probe measured nothing', async () => {
    const { log } = await open();
    expect(log.posted[0]).toEqual({
      quality: 'auto',
      startMs: 3861000,
      audioStreamId: 111,
      probeKbps: 0,
    });
  });

  it('a chosen quality pins the session to that rung and skips the probe', async () => {
    const { log } = await open({ film: heat({ quality: '720p' }), startLevel: P720 });
    expect(log.posted[0].quality).toBe('720p');
    expect(measureThroughput).not.toHaveBeenCalled();
  });

  it('Original pins the copy rung when the session has one', async () => {
    const { log, video } = await open({ film: heat({ quality: 'original' }) });
    expect(log.posted[0].quality).toBe('original');
    expect(measureThroughput).not.toHaveBeenCalled();
    expect(video).not.toHaveAttribute('src');
  });

  it('Original direct-plays the file when the session has no copy rung, and drops the session', async () => {
    const noCopy = {
      ...fixtures.session,
      renditions: fixtures.session.renditions.map((r) => ({ ...r, copy: false })),
    };
    const srv = serve({ film: heat({ quality: 'original' }), create: () => respond(noCopy, 201) });
    installFetch(srv.route);
    renderApp('/films/1/play?edition=11');
    const video = await screen.findByTestId('player-video');
    await vi.waitFor(() => expect(video).toHaveAttribute('src', DIRECT));
    expect(FakeHls.instances).toHaveLength(0);
    expect(srv.log.deleted.map((d) => d.url)).toEqual([`/api/sessions/${noCopy.sessionId}`]);
  });

  it('with the worker offline Original direct-plays without asking for a session', async () => {
    const srv = serve({ film: heat({ offline: true }) });
    installFetch(srv.route);
    renderApp('/films/1/play?edition=11');
    const video = await screen.findByTestId('player-video');
    await vi.waitFor(() => expect(video).toHaveAttribute('src', DIRECT));
    expect(srv.log.posted).toEqual([]);
    expect(screen.getByRole('button', { name: 'Quality' })).toHaveTextContent('Original');
  });

  it('a remembered quality the edition does not offer starts on Auto', async () => {
    const { log } = await open({ film: heat({ quality: '240p' }) });
    expect(log.posted[0].quality).toBe('auto');
    expect(screen.getByRole('button', { name: 'Quality' })).toHaveTextContent('Auto');
  });

  it.each([409, 404, 400])(
    'direct-plays when the session is refused with %s (no encoder, or no keyframe index yet)',
    async (status) => {
      const srv = serve({ create: () => respond({ error: 'No encoder' }, status) });
      installFetch(srv.route);
      renderApp('/films/1/play?edition=11');
      const video = await screen.findByTestId('player-video');
      await vi.waitFor(() => expect(video).toHaveAttribute('src', DIRECT));
      expect(FakeHls.instances).toHaveLength(0);
    },
  );

  it('direct-plays when the browser can play no HLS', async () => {
    FakeHls.supported = false;
    const srv = serve();
    installFetch(srv.route);
    renderApp('/films/1/play?edition=11');
    const video = await screen.findByTestId('player-video');
    await vi.waitFor(() => expect(video).toHaveAttribute('src', DIRECT));
    expect(srv.log.deleted.map((d) => d.url)).toEqual(['/api/sessions/s1']);
  });

  it('direct-plays when hls.js reports a fatal error it cannot recover from', async () => {
    const { log, video, hls } = await open();
    await hlsEvent(() => hls().error({ fatal: true, type: 'otherError' }));
    await vi.waitFor(() => expect(video).toHaveAttribute('src', DIRECT));
    expect(hls().destroy).toHaveBeenCalled();
    expect(log.deleted.map((d) => d.url)).toContain('/api/sessions/s1');
  });
});

describe('automatic level changes: toasts, indicator, quality menu', () => {
  it('says nothing about the level hls.js starts on', async () => {
    await open({ startLevel: P480 });
    expect(screen.queryByText(/Switching to|Back to/)).not.toBeInTheDocument();
  });

  it('toasts a step down: "Switching to 720p for smoother playback"', async () => {
    const { hls } = await open();
    await hlsEvent(() => hls().switchTo(P720));
    expect(await screen.findByText('Switching to 720p for smoother playback')).toBeInTheDocument();
  });

  it('toasts a step up: "Back to Original" and "Back to 720p"', async () => {
    const { hls } = await open({ startLevel: P480 });
    await hlsEvent(() => hls().switchTo(P720));
    expect(await screen.findByText('Back to 720p')).toBeInTheDocument();
    await hlsEvent(() => hls().switchTo(ORIGINAL));
    expect(await screen.findByText('Back to Original')).toBeInTheDocument();
  });

  it('does not toast level changes for a pinned quality', async () => {
    const { hls } = await open({ film: heat({ quality: '720p' }), startLevel: P720 });
    await hlsEvent(() => hls().switchTo(P480));
    expect(screen.queryByText(/Switching to|Back to/)).not.toBeInTheDocument();
  });

  it('reads Auto · Original · Home on a fast link', async () => {
    const { hls } = await open();
    await hlsEvent(() =>
      hls().fragLoaded({ loaded: 3_750_000, loading: { start: 0, first: 0, end: 1000 } }),
    );
    expect(screen.getByText('Auto · Original · Home')).toBeInTheDocument();
  });

  it('reads Auto · 480p · Remote · slow after a step down on a slow link, with an amber dot', async () => {
    const { hls } = await open();
    await hlsEvent(() => {
      hls().fragLoaded({ loaded: 250_000, loading: { start: 0, first: 0, end: 1000 } }); // 2,000 kbit/s
      hls().switchTo(P480);
    });
    expect(screen.getByText('Auto · 480p · Remote · slow')).toBeInTheDocument();
    // The dot is decorative; its meaning is the hidden text beside it.
    expect(screen.getByText('Connection is limited.')).toBeInTheDocument();
    expect(screen.queryByText('Connection is good.')).not.toBeInTheDocument();
  });

  it('shows the dot green at home on the top rung', async () => {
    const { hls } = await open();
    await hlsEvent(() =>
      hls().fragLoaded({ loaded: 3_750_000, loading: { start: 0, first: 0, end: 1000 } }),
    );
    expect(screen.getByText('Auto · Original · Home')).toBeInTheDocument();
    expect(screen.getByText('Connection is good.')).toBeInTheDocument();
    expect(screen.queryByText('Connection is limited.')).not.toBeInTheDocument();
  });

  it('the Auto row of the quality menu says what is playing now', async () => {
    const { hls } = await open();
    await hlsEvent(() => hls().switchTo(P720));
    await userEvent.click(screen.getByRole('button', { name: 'Quality' }));
    const menu = screen.getByRole('menu', { name: 'Quality' });
    expect(within(menu).getByRole('menuitemradio', { name: /Auto/ })).toHaveTextContent('now 720p');
  });
});

const SLOW_SEGMENT = { loaded: 250_000, loading: { start: 0, first: 0, end: 1000 } };

describe('the slow-connection card (decision 11)', () => {
  // The card needs a known speed below home: a slow segment (2,000 kbit/s) first, then the step down.
  const slowDown = async (hls) => {
    await hlsEvent(() => hls().fragLoaded(SLOW_SEGMENT));
    await hlsEvent(() => hls().switchTo(P480));
    return screen.findByRole('dialog', { name: 'You’re on a slow connection' });
  };

  it('appears when Auto lands on a slow rung, with the handover copy', async () => {
    const { hls } = await open();
    const card = await slowDown(hls);
    expect(card).toHaveTextContent(
      'Playing at 480p to avoid stalls. Auto will step up if things improve.',
    );
  });

  it('OK closes it and hands focus back to the player', async () => {
    const { hls } = await open();
    const card = await slowDown(hls);
    await userEvent.click(within(card).getByRole('button', { name: 'OK' }));
    expect(
      screen.queryByRole('dialog', { name: 'You’re on a slow connection' }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Video player' })).toHaveFocus();
  });

  it('Choose quality closes it and opens the quality menu', async () => {
    const { hls } = await open();
    const card = await slowDown(hls);
    await userEvent.click(within(card).getByRole('button', { name: 'Choose quality' }));
    expect(
      screen.queryByRole('dialog', { name: 'You’re on a slow connection' }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole('menu', { name: 'Quality' })).toBeInTheDocument();
  });

  it('shows once per session: a second slow stretch shows only the indicator', async () => {
    const { hls, view } = await open();
    await slowDown(hls);
    view.unmount();
    FakeHls.reset();
    installFetch(serve().route);
    renderApp('/films/1/play?edition=11');
    await instances(1);
    await hlsEvent(() => FakeHls.last.manifest(LEVELS, ORIGINAL));
    await hlsEvent(() => FakeHls.last.switchTo(P480));
    expect(screen.getByText(/480p · .*slow/)).toBeInTheDocument();
    expect(
      screen.queryByRole('dialog', { name: 'You’re on a slow connection' }),
    ).not.toBeInTheDocument();
  });

  it('does not appear for a quality the viewer pinned', async () => {
    await open({ film: heat({ quality: '480p' }), startLevel: P480 });
    expect(
      screen.queryByRole('dialog', { name: 'You’re on a slow connection' }),
    ).not.toBeInTheDocument();
  });
});

describe('the worker-offline state', () => {
  it('disables Auto and the lower qualities and explains why, with Original still playable', async () => {
    const srv = serve({ film: heat({ offline: true }) });
    installFetch(srv.route);
    renderApp('/films/1/play?edition=11');
    await screen.findByTestId('player-video');
    await userEvent.click(screen.getByRole('button', { name: 'Quality' }));
    const menu = screen.getByRole('menu', { name: 'Quality' });
    expect(within(menu).getByRole('menuitemradio', { name: /Auto/ })).toBeDisabled();
    expect(within(menu).getByRole('menuitemradio', { name: /720p/ })).toBeDisabled();
    expect(within(menu).getByRole('menuitemradio', { name: /360p/ })).toBeDisabled();
    expect(within(menu).getByRole('menuitemradio', { name: /^Original/ })).toBeEnabled();
    expect(menu).toHaveTextContent(
      'Lower qualities unavailable right now — the transcode worker is offline. Pre-made versions still play.',
    );
  });

  it('shows no footer when the worker is up', async () => {
    await open();
    await userEvent.click(screen.getByRole('button', { name: 'Quality' }));
    expect(screen.getByRole('menu', { name: 'Quality' })).not.toHaveTextContent(
      'transcode worker is offline',
    );
  });
});

describe('360p', () => {
  it('is in the quality menu and choosing it saves the preference and pins a new session', async () => {
    const { log, fetchMock, video } = await open();
    await landResume(video);
    await moveTo(video, 2000);
    await userEvent.click(screen.getByRole('button', { name: 'Quality' }));
    const menu = screen.getByRole('menu', { name: 'Quality' });
    await userEvent.click(within(menu).getByRole('menuitemradio', { name: /360p/ }));
    const saved = fetchMock.mock.calls
      .filter((c) => c[1]?.method === 'PUT' && c[0] === '/api/films/1/preferences')
      .map((c) => JSON.parse(c[1].body));
    expect(saved).toEqual([{ quality: '360p' }]);
    await instances(2);
    expect(log.posted[1]).toEqual({ quality: '360p', startMs: 2000000, audioStreamId: 111 });
    expect(screen.getByRole('button', { name: 'Quality' })).toHaveTextContent('360p');
  });

  it('is remembered: a film saved at 360p starts pinned to it', async () => {
    const { log } = await open({ film: heat({ quality: '360p' }), startLevel: P360 });
    expect(log.posted[0].quality).toBe('360p');
    expect(screen.getByText('360p · Remote · slow')).toBeInTheDocument();
  });
});

describe('changing quality or audio restarts the session at the current position', () => {
  it('a new quality makes a new session, drops the old one and seeks back to the position', async () => {
    const { log, video } = await open();
    await landResume(video);
    await moveTo(video, 4000);
    await userEvent.click(screen.getByRole('button', { name: 'Quality' }));
    await userEvent.click(
      within(screen.getByRole('menu', { name: 'Quality' })).getByRole('menuitemradio', {
        name: /720p/,
      }),
    );
    await instances(2);
    expect(log.posted[1]).toEqual({ quality: '720p', startMs: 4000000, audioStreamId: 111 });
    expect(FakeHls.instances[0].destroy).toHaveBeenCalledTimes(1);
    expect(log.deleted).toEqual([
      { url: '/api/sessions/s1', init: expect.objectContaining({ keepalive: true }) },
    ]);
    // The new source starts at 0 until the seek lands; the page-load resume (3,861 s) is not used.
    video.currentTime = 0;
    await hlsEvent(() => fireEvent.loadedMetadata(video));
    expect(video.currentTime).toBe(4000);
  });

  it('a new audio track makes a new session with that audioStreamId at the current position', async () => {
    const { log, video } = await open();
    await landResume(video);
    await moveTo(video, 4000);
    await userEvent.click(screen.getByRole('button', { name: 'Audio and subtitles' }));
    const audio = screen.getByRole('menu', { name: 'Audio' });
    await userEvent.click(within(audio).getByRole('menuitemradio', { name: /Commentary/ }));
    await instances(2);
    expect(log.posted[1]).toEqual({
      quality: 'auto',
      startMs: 4000000,
      audioStreamId: 112,
      probeKbps: 0,
    });
    expect(FakeHls.instances[0].destroy).toHaveBeenCalledTimes(1);
  });

  describe('the paused state survives the restart', () => {
    const chooseQuality = async () => {
      await userEvent.click(screen.getByRole('button', { name: 'Quality' }));
      await userEvent.click(
        within(screen.getByRole('menu', { name: 'Quality' })).getByRole('menuitemradio', {
          name: /720p/,
        }),
      );
    };
    const chooseAudio = async () => {
      await userEvent.click(screen.getByRole('button', { name: 'Audio and subtitles' }));
      await userEvent.click(
        within(screen.getByRole('menu', { name: 'Audio' })).getByRole('menuitemradio', {
          name: /Commentary/,
        }),
      );
    };

    it.each([
      ['quality', chooseQuality],
      ['audio', chooseAudio],
    ])('a paused film stays paused after a %s change', async (_name, choose) => {
      const { video } = await open();
      await landResume(video);
      await moveTo(video, 4000);
      await hlsEvent(() => video.pause());
      await choose();
      await instances(2);
      await hlsEvent(() => fireEvent.loadedMetadata(video));
      expect(video.paused).toBe(true);
    });

    it.each([
      ['quality', chooseQuality],
      ['audio', chooseAudio],
    ])('a playing film plays on after a %s change', async (_name, choose) => {
      const { video } = await open();
      await landResume(video);
      await moveTo(video, 4000);
      expect(video.paused).toBe(false);
      await choose();
      await instances(2);
      // The browser pauses the element when its source is replaced.
      await hlsEvent(() => video.pause());
      await hlsEvent(() => fireEvent.loadedMetadata(video));
      expect(video.paused).toBe(false);
    });
  });

  it('a new audio track on a direct-played film only saves the choice', async () => {
    const srv = serve({ create: () => respond({ error: 'No encoder' }, 409) });
    installFetch(srv.route);
    renderApp('/films/1/play?edition=11');
    const video = await screen.findByTestId('player-video');
    await vi.waitFor(() => expect(video).toHaveAttribute('src', DIRECT));
    await userEvent.click(screen.getByRole('button', { name: 'Audio and subtitles' }));
    await userEvent.click(
      within(screen.getByRole('menu', { name: 'Audio' })).getByRole('menuitemradio', {
        name: /Commentary/,
      }),
    );
    expect(srv.log.posted).toHaveLength(1);
  });
});

describe('a dropped connection', () => {
  it('shows Reconnecting… (polite) instead of BUFFERING, then goes away when video flows again', async () => {
    const { hls } = await open();
    await hlsEvent(() => hls().error({ fatal: true, type: 'networkError', response: { code: 0 } }));
    const notice = screen.getByText('Reconnecting…');
    expect(notice.closest('[role="status"]')).toBeInTheDocument();
    expect(screen.queryByText('BUFFERING')).not.toBeInTheDocument();
    await hlsEvent(() =>
      hls().fragLoaded({ loaded: 1_250_000, loading: { start: 0, first: 0, end: 1000 } }),
    );
    expect(screen.queryByText('Reconnecting…')).not.toBeInTheDocument();
  });
});

describe('an expired session (404)', () => {
  it('creates a new session at the current position, not the page-load resume position', async () => {
    const { log, video, hls } = await open();
    await landResume(video);
    await moveTo(video, 1000);
    await hlsEvent(() =>
      hls().error({ fatal: true, type: 'networkError', response: { code: 404 } }),
    );
    await instances(2);
    expect(log.posted[1]).toEqual({
      quality: 'auto',
      startMs: 1000000,
      audioStreamId: 111,
      probeKbps: 0,
    });
    expect(FakeHls.instances[1].loadSource).toHaveBeenCalledWith('/api/sessions/s2/master.m3u8');
    expect(FakeHls.instances[1].config.startPosition).toBe(1000);
    video.currentTime = 0;
    await hlsEvent(() => fireEvent.loadedMetadata(video));
    expect(video.currentTime).toBe(1000);
  });

  it('falls back to direct play when new sessions keep expiring (more than 3 in a minute)', async () => {
    const { video } = await open();
    await landResume(video);
    await moveTo(video, 1000);
    for (let i = 1; i <= 3; i += 1) {
      await hlsEvent(() =>
        FakeHls.last.error({ fatal: true, type: 'networkError', response: { code: 404 } }),
      );
      await instances(i + 1);
    }
    await hlsEvent(() =>
      FakeHls.last.error({ fatal: true, type: 'networkError', response: { code: 404 } }),
    );
    await vi.waitFor(() => expect(video).toHaveAttribute('src', DIRECT));
    expect(FakeHls.instances).toHaveLength(4);
  });
});

describe('position and progress across source loads', () => {
  it('never reports 0 over the saved position while a reloaded source is at the start', async () => {
    const { log, video, hls } = await open();
    await landResume(video);
    await moveTo(video, 1000);
    await hlsEvent(() =>
      hls().error({ fatal: true, type: 'networkError', response: { code: 404 } }),
    );
    await instances(2);
    // The new source reads 0 until the seek lands.
    video.currentTime = 0;
    await hlsEvent(() => fireEvent.timeUpdate(video));
    expect(screen.getByRole('slider', { name: 'Seek' })).toHaveAttribute(
      'aria-valuetext',
      '16 minutes 40 seconds',
    );
    await hlsEvent(() => video.play());
    await hlsEvent(() => video.pause());
    expect(log.progress).not.toContain(0);
    expect(log.progress.at(-1)).toBe(1000000);
  });

  it('follows the playhead again once the seek has landed', async () => {
    const { video, hls } = await open();
    await landResume(video);
    await moveTo(video, 1000);
    await hlsEvent(() =>
      hls().error({ fatal: true, type: 'networkError', response: { code: 404 } }),
    );
    await instances(2);
    video.currentTime = 0;
    await hlsEvent(() => fireEvent.loadedMetadata(video));
    await moveTo(video, 1001);
    expect(screen.getByRole('slider', { name: 'Seek' })).toHaveAttribute(
      'aria-valuetext',
      '16 minutes 41 seconds',
    );
  });

  describe('subtitle tracks', () => {
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

    it('re-applies the track modes after every source load', async () => {
      const { video } = await open();
      const tracks = screen.getAllByTestId('subtitle-track').map((t) => t.track);
      // Heat is saved with Subtitles Off and has a forced English track (id 114, the last one), which shows.
      const forced = tracks.at(-1);
      expect(forced.mode).toBe('showing');
      // A new source resets the element's text tracks.
      for (const track of tracks) track.mode = 'disabled';
      await hlsEvent(() => fireEvent.loadedMetadata(video));
      expect(forced.mode).toBe('showing');
      expect(tracks.slice(0, -1).map((t) => t.mode)).toEqual(['disabled']);
    });
  });
});

describe('leaving the player', () => {
  const deletesOf = (fetchMock) => fetchMock.mock.calls.filter((c) => c[1]?.method === 'DELETE');

  it('removes the session with a keepalive request and the CSRF header on unmount', async () => {
    const { fetchMock, view } = await open();
    view.unmount();
    const deletes = deletesOf(fetchMock);
    expect(deletes).toHaveLength(1);
    expect(deletes[0][0]).toBe('/api/sessions/s1');
    expect(deletes[0][1]).toMatchObject({
      keepalive: true,
      headers: expect.objectContaining({ 'X-CineFilm': '1' }),
    });
  });

  it('removes the session when the page is closing', async () => {
    const { fetchMock } = await open();
    await hlsEvent(() => window.dispatchEvent(new Event('pagehide')));
    expect(deletesOf(fetchMock).map((c) => c[0])).toEqual(['/api/sessions/s1']);
  });

  it('removes a session that was created after the player was already closed', async () => {
    let release;
    const gate = new Promise((resolve) => (release = resolve));
    const srv = serve({
      create: () =>
        gate.then(() => respond({ ...fixtures.session, sessionId: 'late', masterUrl: '/m' }, 201)),
    });
    installFetch(srv.route);
    const view = renderApp('/films/1/play?edition=11');
    await screen.findByTestId('player-video');
    await vi.waitFor(() => expect(srv.log.posted).toHaveLength(1));
    view.unmount();
    release();
    await vi.waitFor(() =>
      expect(srv.log.deleted.map((d) => d.url)).toEqual(['/api/sessions/late']),
    );
    expect(FakeHls.instances).toHaveLength(0);
  });
});

describe('iPhone Safari: native HLS', () => {
  // `master.status` is what a GET of the master playlist answers (the reload checks the session is alive).
  const nativeOpen = async (film) => {
    delete window.MediaSource; // iPhone Safari: no Media Source, so native HLS
    vi.spyOn(HTMLMediaElement.prototype, 'canPlayType').mockReturnValue('maybe');
    const srv = serve({ film });
    const master = { status: 200 };
    const fetchMock = installFetch((method, url, init) => {
      if (method === 'GET' && url.endsWith('/master.m3u8')) return respond(null, master.status);
      return srv.route(method, url, init);
    });
    const view = renderApp('/films/1/play?edition=11');
    const video = await screen.findByTestId('player-video');
    await vi.waitFor(() => expect(video).toHaveAttribute('src', '/api/sessions/s1/master.m3u8'));
    return { ...srv, fetchMock, video, view, master };
  };
  const resize = (video, height) => {
    Object.defineProperty(video, 'videoHeight', { value: height, configurable: true });
    return hlsEvent(() => video.dispatchEvent(new Event('resize')));
  };

  it('plays the master natively, without hls.js', async () => {
    await nativeOpen();
    expect(FakeHls.instances).toHaveLength(0);
  });

  it('reads the rung from the video height: the first is silent, a drop toasts', async () => {
    const { video } = await nativeOpen();
    await resize(video, 1080);
    expect(screen.queryByText(/Switching to|Back to/)).not.toBeInTheDocument();
    await resize(video, 720);
    expect(await screen.findByText('Switching to 720p for smoother playback')).toBeInTheDocument();
    await resize(video, 1080);
    expect(await screen.findByText('Back to Original')).toBeInTheDocument();
  });

  it('reloads at the current position, not the page-load resume, after a fatal error', async () => {
    const { video, fetchMock } = await nativeOpen();
    await landResume(video);
    await moveTo(video, 1000);
    await hlsEvent(() => video.dispatchEvent(new Event('error')));
    expect(await screen.findByText('Reconnecting…')).toBeInTheDocument();
    await vi.waitFor(() =>
      expect(
        fetchMock.mock.calls.filter((c) => c[0] === '/api/sessions/s1/master.m3u8'),
      ).toHaveLength(1),
    );
    video.currentTime = 0;
    await hlsEvent(() => fireEvent.loadedMetadata(video));
    expect(video.currentTime).toBe(1000);
  });

  it('makes a new session when the reload finds the old one gone', async () => {
    const { video, log, master } = await nativeOpen();
    await landResume(video);
    await moveTo(video, 1000);
    master.status = 404;
    await hlsEvent(() => video.dispatchEvent(new Event('error')));
    await vi.waitFor(() => expect(log.posted).toHaveLength(2));
    expect(log.posted[1].startMs).toBe(1000000);
  });
});

// A poor link: a session request that gets no answer is asked again under "Reconnecting…", never direct-played.
// The `online` event wakes the retry at once, so no test waits out a backoff.
describe('a session request that gets no answer', () => {
  const NOTICE = 'Reconnecting…';
  const session = (n = 1) =>
    respond(
      { ...fixtures.session, sessionId: `s${n}`, masterUrl: `/api/sessions/s${n}/master.m3u8` },
      201,
    );
  const noAnswer = () => {
    throw new TypeError('Failed to fetch');
  };
  const online = () => act(async () => window.dispatchEvent(new Event('online')));
  const noNotice = () =>
    vi.waitFor(() => expect(screen.queryByText(NOTICE)).not.toBeInTheDocument());
  const openPending = async (create) => {
    const srv = serve({ create });
    const fetchMock = installFetch(srv.route);
    renderApp('/films/1/play?edition=11');
    const video = await screen.findByTestId('player-video');
    return { ...srv, fetchMock, video };
  };
  const chooseAudio = async () => {
    await userEvent.click(screen.getByRole('button', { name: 'Audio and subtitles' }));
    await userEvent.click(
      within(screen.getByRole('menu', { name: 'Audio' })).getByRole('menuitemradio', {
        name: /Commentary/,
      }),
    );
  };

  it('shows Reconnecting… (polite), sets no src, and streams through hls.js once a later request works', async () => {
    const { video, log } = await openPending((n) => (n === 1 ? noAnswer() : session()));
    const notice = await screen.findByText(NOTICE);
    expect(notice.closest('[role="status"]')).toBeInTheDocument();
    expect(video).not.toHaveAttribute('src');
    expect(FakeHls.instances).toHaveLength(0);
    expect(log.posted).toHaveLength(1);
    await online(); // no real wait: the wake-up ends the backoff
    await instances(1);
    await noNotice();
    expect(log.posted).toHaveLength(2);
    expect(video).not.toHaveAttribute('src');
    expect(FakeHls.last.loadSource).toHaveBeenCalledWith('/api/sessions/s1/master.m3u8');
  });

  it('does the same at a recovery: the rebuild retries instead of falling back to direct play', async () => {
    const { video, hls } = await open({
      create: (n) => (n === 2 ? noAnswer() : session(n === 1 ? 1 : 2)),
    });
    await landResume(video);
    await moveTo(video, 1000);
    await hlsEvent(() =>
      hls().error({ fatal: true, type: 'networkError', response: { code: 404 } }),
    );
    expect(await screen.findByText(NOTICE)).toBeInTheDocument();
    expect(video).not.toHaveAttribute('src');
    await online();
    await instances(2);
    await noNotice();
    expect(video).not.toHaveAttribute('src');
    expect(FakeHls.last.loadSource).toHaveBeenCalledWith('/api/sessions/s2/master.m3u8');
  });

  it('a 502 (a proxy whose app is down) is asked again, then streams through hls.js, never direct', async () => {
    const { video } = await openPending((n) =>
      n === 1 ? respond({ error: 'Bad gateway' }, 502) : session(),
    );
    expect(await screen.findByText(NOTICE)).toBeInTheDocument();
    expect(video).not.toHaveAttribute('src');
    await online();
    await instances(1);
    expect(video).not.toHaveAttribute('src');
  });

  it('a 401 shows the sign-in card and no Reconnecting…, and streams as soon as the viewer signs in', async () => {
    const { log, video } = await openPending((n) =>
      n === 1 ? respond({ error: 'Unauthorised' }, 401) : session(),
    );
    const card = await screen.findByRole('dialog', { name: 'Signed out' });
    expect(screen.queryByText(NOTICE)).not.toBeInTheDocument();
    expect(FakeHls.instances).toHaveLength(0);
    expect(video).not.toHaveAttribute('src');
    await userEvent.type(within(card).getByLabelText('Password'), 'secret');
    await userEvent.click(within(card).getByRole('button', { name: 'Sign in' }));
    await instances(1);
    expect(log.posted).toHaveLength(2);
    expect(screen.queryByText(NOTICE)).not.toBeInTheDocument();
    expect(video).not.toHaveAttribute('src');
  });

  it('keeps the chosen start speed in a retried request when the network has not changed', async () => {
    sessionStorage.setItem('cinefilm.speed', JSON.stringify({ kbps: 100000, at: Date.now() }));
    const { log } = await openPending((n) => (n === 1 ? noAnswer() : session()));
    await screen.findByText(NOTICE);
    await vi.waitFor(() => expect(FakeHls.instances).toHaveLength(1), { timeout: 4000 }); // the retry waits its real 1 s: `online` would itself be a network change
    expect(log.posted[1].probeKbps).toBe(100000);
  });

  it('sends probeKbps 0 in a retried request when the network changed while it was retried', async () => {
    sessionStorage.setItem('cinefilm.speed', JSON.stringify({ kbps: 100000, at: Date.now() }));
    const { log } = await openPending((n) => (n === 1 ? noAnswer() : session()));
    await screen.findByText(NOTICE);
    await act(async () => window.dispatchEvent(new Event('offline')));
    await online();
    await instances(1);
    expect(log.posted[0].probeKbps).toBe(100000);
    expect(log.posted[1].probeKbps).toBe(0);
  });

  it('asks for the audio track chosen while the request is being retried', async () => {
    const { log } = await openPending((n) => (n === 1 ? noAnswer() : session()));
    await screen.findByText(NOTICE);
    await chooseAudio();
    await online();
    await instances(1);
    expect(log.posted[0].audioStreamId).toBe(111);
    expect(log.posted[1].audioStreamId).toBe(112);
  });

  it('keeps the notice for the new request when the quality changes during Reconnecting…', async () => {
    const { log } = await openPending((n) => (n < 3 ? noAnswer() : session()));
    await screen.findByText(NOTICE);
    await userEvent.click(screen.getByRole('button', { name: 'Quality' }));
    await userEvent.click(
      within(screen.getByRole('menu', { name: 'Quality' })).getByRole('menuitemradio', {
        name: /720p/,
      }),
    );
    await vi.waitFor(() => expect(log.posted).toHaveLength(2));
    expect(await screen.findByText(NOTICE)).toBeInTheDocument();
    await online();
    await instances(1);
    expect(log.posted[1].quality).toBe('720p');
    expect(log.posted[2].quality).toBe('720p');
  });

  it('hands the probe a check that asks the server cheaply and hears it answer', async () => {
    let answered = null;
    measureThroughput.mockImplementation(async (_url, options) => {
      answered = await options.checkReachable();
      return null;
    });
    const { fetchMock } = await openPending(() => session());
    await instances(1);
    expect(answered).toBe(true);
    expect(fetchMock.mock.calls.some(([url]) => url === '/api/session')).toBe(true);
  });

  it('that check says no when only a proxy answers (a 502 from /api/session)', async () => {
    let answered = null;
    measureThroughput.mockImplementation(async (_url, options) => {
      answered = await options.checkReachable();
      return null;
    });
    const srv = serve({ create: () => session() });
    installFetch((method, url, init) =>
      url === '/api/session' ? respond(null, 502) : srv.route(method, url, init),
    );
    renderApp('/films/1/play?edition=11');
    await instances(1);
    expect(answered).toBe(false);
  });

  it('sends each session request with an abort signal of its own', async () => {
    const { fetchMock } = await openPending(() => session());
    await instances(1);
    const post = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST');
    expect(post[1].signal).toBeInstanceOf(AbortSignal);
  });

  it('stops listening for wake-ups at once when the viewer leaves a pending request', async () => {
    let release;
    const answer = new Promise((resolve) => {
      release = resolve;
    });
    const srv = serve({ create: () => answer });
    installFetch(srv.route);
    const view = renderApp('/films/1/play?edition=11');
    await screen.findByTestId('player-video');
    await vi.waitFor(() => expect(srv.log.posted).toHaveLength(1));
    const remove = vi.spyOn(window, 'removeEventListener');
    view.unmount();
    expect(remove.mock.calls.some(([name]) => name === 'cinefilm:signed-in')).toBe(true);
    release(session());
  });

  it('starts nothing when the viewer leaves while the probe runs', async () => {
    let finish;
    measureThroughput.mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    const srv = serve();
    installFetch(srv.route);
    const view = renderApp('/films/1/play?edition=11');
    await screen.findByTestId('player-video');
    await vi.waitFor(() => expect(measureThroughput).toHaveBeenCalled());
    view.unmount();
    finish(3000);
    await act(async () => {});
    expect(srv.log.posted).toEqual([]);
  });

  it('does not report or retry a session request that fails after the viewer left', async () => {
    let fail;
    const pending = new Promise((_resolve, reject) => {
      fail = reject;
    });
    const srv = serve({ create: () => pending });
    installFetch(srv.route);
    const view = renderApp('/films/1/play?edition=11');
    await screen.findByTestId('player-video');
    await vi.waitFor(() => expect(srv.log.posted).toHaveLength(1));
    view.unmount();
    fail(new TypeError('Failed to fetch'));
    await act(async () => {});
    expect(srv.log.posted).toHaveLength(1);
    expect(FakeHls.instances).toHaveLength(0);
  });

  it('a probe that finds the server unreachable shows Reconnecting… before any session request answers', async () => {
    measureThroughput.mockImplementation(async (_url, options) => {
      options.onUnreachable();
      return null;
    });
    let release;
    const answer = new Promise((resolve) => {
      release = resolve;
    });
    const { log } = await openPending((n) => (n === 1 ? answer : session()));
    expect(await screen.findByText(NOTICE)).toBeInTheDocument();
    await vi.waitFor(() => expect(log.posted).toHaveLength(1));
    expect(FakeHls.instances).toHaveLength(0);
    release(session());
    await instances(1);
    await noNotice();
  });
});

describe('the start rung: reuse a measured speed, probe, or start low', () => {
  const recover = async (video, hls) => {
    await landResume(video);
    await moveTo(video, 1000);
    await hlsEvent(() =>
      hls().error({ fatal: true, type: 'networkError', response: { code: 404 } }),
    );
  };

  afterEach(() => vi.useRealTimers());

  it('a recovery reuses the speed: no new probe, probeKbps from the samples', async () => {
    measureThroughput.mockResolvedValue(3000);
    const { log, video, hls } = await open();
    await recover(video, hls);
    await instances(2);
    expect(measureThroughput).toHaveBeenCalledTimes(1);
    expect(log.posted[1].probeKbps).toBe(3000);
  });

  it('an audio change reuses the speed too', async () => {
    measureThroughput.mockResolvedValue(3000);
    const { log, video } = await open();
    await landResume(video);
    await moveTo(video, 4000);
    await userEvent.click(screen.getByRole('button', { name: 'Audio and subtitles' }));
    await userEvent.click(
      within(screen.getByRole('menu', { name: 'Audio' })).getByRole('menuitemradio', {
        name: /Commentary/,
      }),
    );
    await instances(2);
    expect(measureThroughput).toHaveBeenCalledTimes(1);
    expect(log.posted[1].probeKbps).toBe(3000);
  });

  it('uses the lower middle of the samples: a probe of 100,000 and one segment at 500 start low', async () => {
    measureThroughput.mockResolvedValue(100000);
    const { log, video, hls } = await open();
    await hlsEvent(() =>
      hls().fragLoaded({ loaded: 62_500, loading: { start: 0, first: 0, end: 1000 } }),
    ); // 500 kbit/s
    await recover(video, hls);
    await instances(2);
    expect(log.posted[1].probeKbps).toBe(500);
  });

  it('a second film within 10 minutes reuses the speed, and a reused speed is not written back', async () => {
    measureThroughput.mockResolvedValue(3000);
    const first = await open();
    first.view.unmount();
    FakeHls.reset();
    measureThroughput.mockClear();
    const written = sessionStorage.getItem('cinefilm.speed');

    const second = await open();
    expect(measureThroughput).not.toHaveBeenCalled();
    expect(second.log.posted[0].probeKbps).toBe(3000);
    expect(sessionStorage.getItem('cinefilm.speed')).toBe(written); // its age stays honest
  });

  it('a film started more than 10 minutes after the speed was measured probes again', async () => {
    // The exact boundary (599,999 ms reused, 600,000 not) is pinned in throughput.test.js.
    sessionStorage.setItem(
      'cinefilm.speed',
      JSON.stringify({ kbps: 3000, at: Date.now() - 601_000 }),
    );
    measureThroughput.mockResolvedValue(8000);
    const { log } = await open();
    expect(measureThroughput).toHaveBeenCalledTimes(1);
    expect(log.posted[0].probeKbps).toBe(8000);
  });

  it('a second film after an offline event probes again: the network changed', async () => {
    measureThroughput.mockResolvedValue(3000);
    const first = await open();
    first.view.unmount();
    FakeHls.reset();
    measureThroughput.mockClear();
    await act(async () => window.dispatchEvent(new Event('offline')));
    await open();
    expect(measureThroughput).toHaveBeenCalledTimes(1);
  });

  it('a speed measured before a network change is not reused inside the film either', async () => {
    measureThroughput.mockResolvedValue(3000);
    const { log, video, hls } = await open();
    await act(async () => window.dispatchEvent(new Event('offline')));
    await recover(video, hls);
    await instances(2);
    expect(measureThroughput).toHaveBeenCalledTimes(2);
    expect(log.posted[1].probeKbps).toBe(3000); // the new probe's own figure
  });

  it('a cautious low start opens no slow card; a slow segment then does', async () => {
    const { hls } = await open({ startLevel: P480 });
    await hlsEvent(() => {});
    expect(
      screen.queryByRole('dialog', { name: 'You’re on a slow connection' }),
    ).not.toBeInTheDocument();
    await hlsEvent(() => hls().fragLoaded(SLOW_SEGMENT));
    expect(
      await screen.findByRole('dialog', { name: 'You’re on a slow connection' }),
    ).toBeInTheDocument();
  });

  it('forgets what was measured when the network changes: the indicator no longer reads it', async () => {
    const { hls } = await open({ startLevel: P720 });
    await hlsEvent(() =>
      hls().fragLoaded({ loaded: 3_750_000, loading: { start: 0, first: 0, end: 1000 } }),
    ); // 30,000 kbit/s
    expect(screen.getByText('Auto · 720p · Home')).toBeInTheDocument();
    await act(async () => window.dispatchEvent(new Event('offline')));
    expect(screen.getByText('Auto · 720p · Remote')).toBeInTheDocument();
  });

  it('a slow speed measured before a network change does not open the slow card', async () => {
    const { hls } = await open({ startLevel: P720 });
    await hlsEvent(() => hls().fragLoaded(SLOW_SEGMENT));
    await act(async () => window.dispatchEvent(new Event('offline')));
    await hlsEvent(() => hls().switchTo(P480));
    expect(
      screen.queryByRole('dialog', { name: 'You’re on a slow connection' }),
    ).not.toBeInTheDocument();
  });

  it('a speed reused for the start reads on the indicator at once', async () => {
    measureThroughput.mockResolvedValue(30000);
    const first = await open();
    first.view.unmount();
    FakeHls.reset();
    // Below the top rung, where only a known speed makes it Home.
    await open({ startLevel: P720 });
    expect(screen.getByText('Auto · 720p · Home')).toBeInTheDocument();
  });

  it('reuses its own samples when the remembered speed cannot be read', async () => {
    measureThroughput.mockResolvedValue(3000);
    const { log, video, hls } = await open();
    sessionStorage.clear();
    await recover(video, hls);
    await instances(2);
    expect(measureThroughput).toHaveBeenCalledTimes(1);
    expect(log.posted[1].probeKbps).toBe(3000);
  });

  describe('the player’s own samples have an age too', () => {
    // A clock the test moves; every Date.now() in the player reads it.
    let now;
    beforeEach(() => {
      now = 1_000_000;
      vi.spyOn(Date, 'now').mockImplementation(() => now);
    });

    it.each([
      [599_999, 3000, 1],
      [600_000, 8000, 2],
    ])(
      '%i ms after the last measurement a recovery uses probeKbps %i (probes: %i)',
      async (age, kbps, probes) => {
        measureThroughput.mockResolvedValueOnce(3000).mockResolvedValue(8000);
        const { log, video, hls } = await open();
        sessionStorage.clear(); // only the player’s own samples are left to reuse
        now += age;
        await recover(video, hls);
        await instances(2);
        expect(measureThroughput).toHaveBeenCalledTimes(probes);
        expect(log.posted[1].probeKbps).toBe(kbps);
      },
    );
  });

  it('keeps no sample from before a network change: a later segment stands alone', async () => {
    measureThroughput.mockResolvedValue(3000);
    const { log, video, hls } = await open();
    await act(async () => window.dispatchEvent(new Event('offline')));
    await hlsEvent(() =>
      hls().fragLoaded({ loaded: 1_125_000, loading: { start: 0, first: 0, end: 1000 } }),
    ); // 9,000 kbit/s
    await recover(video, hls);
    await instances(2);
    expect(measureThroughput).toHaveBeenCalledTimes(1);
    expect(log.posted[1].probeKbps).toBe(9000);
  });

  it('stops listening for wake-ups once the stream has started', async () => {
    const remove = vi.spyOn(window, 'removeEventListener');
    await open();
    const signedIn = remove.mock.calls.filter(([name]) => name === 'cinefilm:signed-in');
    expect(signedIn.length).toBeGreaterThanOrEqual(1);
  });
});
