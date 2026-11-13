import { act, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, useNavigate } from 'react-router-dom';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { measureThroughput } from '../player/probe.js';
import { FakeHls } from '../test/fakeHls.js';
import App from '../App.jsx';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';
import { resetThroughputState } from '../player/throughput.js';

// The playback-health recorder as the player wires it: the media events and the fake hls.js go in, the body of
// the PUT comes out. The clock is `performance.now`, which the counter reads, so every figure is exact.
vi.mock('hls.js/light', async () => ({ default: (await import('../test/fakeHls.js')).FakeHls }));
vi.mock('../player/probe.js', () => ({ measureThroughput: vi.fn() }));

let clock;
beforeEach(() => {
  FakeHls.reset();
  window.MediaSource = class {};
  measureThroughput.mockReset().mockResolvedValue(null);
  sessionStorage.clear();
  resetThroughputState();
  clock = 1000;
  vi.spyOn(performance, 'now').mockImplementation(() => clock);
});
afterEach(() => {
  delete window.MediaSource;
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const LEVELS = [
  { height: 360, bitrate: 600000 },
  { height: 480, bitrate: 1000000 },
  { height: 720, bitrate: 2500000 },
  { height: 1080, bitrate: 24000000 },
];
const [, , P720, ORIGINAL] = [0, 1, 2, 3];
const DIRECT = '/api/editions/11/stream';
const HEALTH = /^\/api\/playback-health\//;

function heat({ offline = false, quality = 'auto' } = {}) {
  const film = structuredClone(fixtures.heat);
  film.preferences.quality = quality;
  for (const q of film.editions[0].qualities) q.available = !offline || q.id === 'original';
  return film;
}

function serve({ film = heat(), create } = {}) {
  const log = { posted: [] };
  let n = 0;
  const route = (method, url, init = {}) => {
    if (url === '/api/films/1') return respond(film);
    if (method === 'POST' && url === '/api/editions/11/sessions') {
      log.posted.push(JSON.parse(init.body));
      if (create) return create(log.posted.length);
      n += 1;
      return respond(
        { ...fixtures.session, sessionId: `s${n}`, masterUrl: `/api/sessions/s${n}/master.m3u8` },
        201,
      );
    }
    if (method === 'DELETE' && url.startsWith('/api/sessions/')) return respond(null, 204);
    return null;
  };
  return { log, route };
}

const instances = (count) => vi.waitFor(() => expect(FakeHls.instances).toHaveLength(count));
const inAct = (fn) => act(async () => fn());
const sendsOf = (fetchMock) =>
  fetchMock.mock.calls.filter(([url, init]) => init?.method === 'PUT' && HEALTH.test(url));
const lastReport = (fetchMock) => JSON.parse(sendsOf(fetchMock).at(-1)[1].body);
const leave = () => inAct(() => window.dispatchEvent(new Event('pagehide')));

async function open({ film, create, startLevel = ORIGINAL } = {}) {
  const srv = serve({ film, create });
  const fetchMock = installFetch(srv.route);
  const view = renderApp('/films/1/play?edition=11');
  const video = await screen.findByTestId('player-video');
  await instances(1);
  await inAct(() => FakeHls.last.manifest(LEVELS, startLevel));
  await inAct(() => FakeHls.last.switchTo(startLevel));
  return { ...srv, fetchMock, video, view, hls: () => FakeHls.last };
}

// Opens a film that direct-plays (no session), waiting for the file to be attached.
async function openDirect({ film, create, expectSrc = true } = {}) {
  const srv = serve({ film, create });
  const fetchMock = installFetch(srv.route);
  const view = renderApp('/films/1/play?edition=11');
  const video = await screen.findByTestId('player-video');
  if (expectSrc) await vi.waitFor(() => expect(video).toHaveAttribute('src', DIRECT));
  return { ...srv, fetchMock, video, view };
}

const landResume = async (video) => {
  await inAct(() => fireEvent.loadedMetadata(video));
  await inAct(() => fireEvent.timeUpdate(video));
};
const moveTo = async (video, seconds) => {
  video.currentTime = seconds;
  await inAct(() => fireEvent.timeUpdate(video));
};
// The first frame at `at` ms on the clock.
const firstFrame = async (video, at) => {
  clock = at;
  await inAct(() => fireEvent.playing(video));
};

describe('the first report', () => {
  it('is sent at the first frame with the start-up, the engine, Auto and an unknown environment', async () => {
    const { video, fetchMock } = await open();
    await landResume(video);
    expect(sendsOf(fetchMock)).toHaveLength(0);
    await firstFrame(video, 3380);
    expect(sendsOf(fetchMock)).toHaveLength(1);
    const [url, init] = sendsOf(fetchMock)[0];
    expect(url).toMatch(/^\/api\/playback-health\/[0-9a-f-]{36}$/);
    expect(init.headers['X-CineFilm']).toBe('1');
    const report = JSON.parse(init.body);
    expect(report).toMatchObject({
      seq: 1,
      editionId: 11,
      environment: 'unknown',
      operator: null,
      latencyMs: null,
      engine: 'hlsjs',
      quality: 'auto',
      autoplayRefused: false,
      startupMs: 2380,
      stallCount: 0,
      seekCount: 0,
      reloadCount: 0,
      reconnectCount: 0,
      recoveries: 0,
      fellBack: false,
      netErrors: 0,
      timeouts: 0,
    });
    expect(report.client.screen).toMatch(/^\d{1,5}x\d{1,5}$/);
  });

  it('keeps the same id and raises seq on the next send', async () => {
    const { video, fetchMock } = await open();
    await landResume(video);
    await firstFrame(video, 2000);
    clock = 3000;
    await moveTo(video, 3862);
    await leave();
    const sends = sendsOf(fetchMock);
    expect(sends).toHaveLength(2);
    expect(sends[1][0]).toBe(sends[0][0]);
    expect(JSON.parse(sends[1][1].body).seq).toBe(2);
    expect(sends[1][1].keepalive).toBe(true);
  });

  it('sends the pinned quality', async () => {
    const { video, fetchMock } = await open({ film: heat({ quality: '720p' }), startLevel: P720 });
    await landResume(video);
    await firstFrame(video, 2000);
    expect(lastReport(fetchMock)).toMatchObject({ quality: '720p', engine: 'hlsjs' });
  });

  it('reports the engine as native on a browser with no Media Source but native HLS', async () => {
    delete window.MediaSource;
    vi.spyOn(HTMLMediaElement.prototype, 'canPlayType').mockReturnValue('maybe');
    const srv = serve();
    const fetchMock = installFetch(srv.route);
    renderApp('/films/1/play?edition=11');
    const video = await screen.findByTestId('player-video');
    await vi.waitFor(() => expect(video).toHaveAttribute('src', '/api/sessions/s1/master.m3u8'));
    await firstFrame(video, 2000);
    expect(lastReport(fetchMock)).toMatchObject({ engine: 'native', fellBack: false });
  });
});

describe('what is counted', () => {
  it('credits moving time to the rung that played it, and counts a step down', async () => {
    const { video, hls, fetchMock } = await open();
    await landResume(video);
    await firstFrame(video, 10000);
    clock = 11000;
    await moveTo(video, 3862);
    await inAct(() => hls().switchTo(P720));
    clock = 12000;
    await moveTo(video, 3863);
    clock = 13000;
    await moveTo(video, 3864);
    await leave();
    expect(lastReport(fetchMock)).toMatchObject({
      watchedMs: 3000,
      rungMs: { original: 1000, '720p': 2000 },
      stepsDown: 1,
      stepsUp: 0,
    });
    expect(lastReport(fetchMock).minutes).toEqual([[0, 0, 3, 3000, 0, 0, 0]]);
  });

  it('records the seconds buffered ahead and the rung being fetched, per minute', async () => {
    const { video, hls, fetchMock } = await open();
    await landResume(video);
    await firstFrame(video, 10000);
    Object.defineProperty(video, 'buffered', {
      configurable: true,
      value: { length: 1, start: () => 0, end: () => 3870 },
    });
    await inAct(() => hls().fetchLevel(P720));
    clock = 11000;
    await moveTo(video, 3862);
    await leave();
    expect(lastReport(fetchMock).minutes).toEqual([[0, 0, 1, 1000, 0, 8, 3]]);
  });

  it('records the speed of each segment, and not the probe’s', async () => {
    measureThroughput.mockResolvedValue(3000);
    const { video, hls, fetchMock } = await open();
    await landResume(video);
    await inAct(() =>
      hls().fragLoaded({ loaded: 250_000, loading: { start: 0, first: 0, end: 1000 } }),
    );
    await firstFrame(video, 2000);
    expect(lastReport(fetchMock)).toMatchObject({
      kbpsSamples: 1,
      kbpsLow: 2000,
      kbpsMedian: 2000,
      kbpsHigh: 2000,
    });
  });

  it('counts a stall from waiting to playing', async () => {
    const { video, fetchMock } = await open();
    await landResume(video);
    await firstFrame(video, 10000);
    clock = 10500;
    await inAct(() => fireEvent.waiting(video));
    clock = 12500;
    await inAct(() => fireEvent.playing(video));
    await leave();
    expect(lastReport(fetchMock)).toMatchObject({
      stallCount: 1,
      stallMs: 2000,
      longestStallMs: 2000,
      watchedMs: 500,
    });
  });

  it('counts a pause that ends a stall, and a film that ends', async () => {
    const { video, fetchMock } = await open();
    await landResume(video);
    await firstFrame(video, 10000);
    await inAct(() => fireEvent.waiting(video));
    clock = 10800;
    await inAct(() => fireEvent.pause(video));
    clock = 20000;
    await inAct(() => fireEvent.ended(video));
    await leave();
    expect(lastReport(fetchMock)).toMatchObject({ stallCount: 1, stallMs: 800 });
  });

  it('counts a seek the viewer asks for, from the click to seeked', async () => {
    const { video, fetchMock } = await open();
    await landResume(video);
    await firstFrame(video, 10000);
    await userEvent.click(screen.getByRole('button', { name: 'Forward 10 seconds' }));
    clock = 11200;
    await inAct(() => fireEvent.seeked(video));
    await leave();
    expect(lastReport(fetchMock)).toMatchObject({ seekCount: 1, seekWaitMs: 1200 });
  });

  it('does not count the seek that restores the resume position', async () => {
    const { video, fetchMock } = await open();
    await landResume(video);
    await firstFrame(video, 10000);
    await leave();
    expect(lastReport(fetchMock)).toMatchObject({ seekCount: 0, seekWaitMs: 0 });
  });

  it('counts a source change after the first frame as a reload, to playing', async () => {
    const { video, fetchMock } = await open();
    await landResume(video);
    await firstFrame(video, 10000);
    clock = 10400;
    await inAct(() => fireEvent(video, new Event('emptied')));
    clock = 12400;
    await inAct(() => fireEvent.playing(video));
    await leave();
    expect(lastReport(fetchMock)).toMatchObject({ reloadCount: 1, reloadMs: 2000, stallCount: 0 });
  });

  it('ends a reload at canplay while paused', async () => {
    const { video, fetchMock } = await open();
    await landResume(video);
    await firstFrame(video, 10000);
    await inAct(() => video.play()); // the element is playing as far as jsdom knows
    await inAct(() => video.pause());
    clock = 10100;
    await inAct(() => fireEvent(video, new Event('emptied')));
    clock = 10900;
    await inAct(() => fireEvent.canPlay(video));
    clock = 30000;
    await leave();
    expect(lastReport(fetchMock)).toMatchObject({ reloadCount: 1, reloadMs: 800 });
  });

  it('counts a reconnect and the network error behind it, and its time', async () => {
    const { video, hls, fetchMock } = await open();
    await landResume(video);
    await firstFrame(video, 10000);
    clock = 11000;
    await inAct(() =>
      hls().error({
        fatal: true,
        type: 'networkError',
        details: 'manifestLoadError',
        response: { code: 0 },
      }),
    );
    expect(screen.getByText(/Reconnecting/)).toBeInTheDocument();
    clock = 14000;
    await inAct(() =>
      hls().fragLoaded({ loaded: 125_000, loading: { start: 0, first: 0, end: 100 } }),
    );
    await leave();
    expect(lastReport(fetchMock)).toMatchObject({
      reconnectCount: 1,
      reconnectMs: 3000,
      netErrors: 1,
      timeouts: 0,
    });
  });

  it('counts a timeout apart from other network errors, and an error with a status as neither', async () => {
    const { video, hls, fetchMock } = await open();
    await landResume(video);
    await firstFrame(video, 10000);
    await inAct(() =>
      hls().error({ fatal: false, type: 'networkError', details: 'fragLoadTimeOut' }),
    );
    await inAct(() =>
      hls().error({ fatal: false, type: 'networkError', details: 'fragLoadError' }),
    );
    await inAct(() =>
      hls().error({
        fatal: false,
        type: 'networkError',
        details: 'fragLoadError',
        response: { code: 503 },
      }),
    );
    await leave();
    expect(lastReport(fetchMock)).toMatchObject({ timeouts: 1, netErrors: 1 });
  });

  // The session request, with the retry woken by `online` so no test waits out a backoff.
  async function openAfterNoAnswer(first) {
    const later = () =>
      respond(
        { ...fixtures.session, sessionId: 's1', masterUrl: '/api/sessions/s1/master.m3u8' },
        201,
      );
    const srv = serve({ create: (n) => (n === 1 ? first() : later()) });
    const fetchMock = installFetch(srv.route);
    renderApp('/films/1/play?edition=11');
    const video = await screen.findByTestId('player-video');
    return { ...srv, fetchMock, video };
  }

  it('counts one reconnect and a network error, and no fall back, for a session request that got no answer', async () => {
    const { video, fetchMock } = await openAfterNoAnswer(() => {
      throw new TypeError('Failed to fetch');
    });
    expect(await screen.findByText('Reconnecting…')).toBeInTheDocument();
    clock = 2000;
    await inAct(() => window.dispatchEvent(new Event('online')));
    await instances(1);
    await inAct(() => FakeHls.last.manifest(LEVELS, ORIGINAL));
    await inAct(() => FakeHls.last.switchTo(ORIGINAL));
    await landResume(video);
    await firstFrame(video, 3000);
    await leave();
    expect(lastReport(fetchMock)).toMatchObject({
      reconnectCount: 1,
      netErrors: 1,
      timeouts: 0,
      fellBack: false,
      engine: 'hlsjs',
    });
  });

  it('counts no reconnect for a 401 on the session request: it waits for the sign-in', async () => {
    const { video, fetchMock } = await openAfterNoAnswer(() => respond({ error: 'No' }, 401));
    await screen.findByRole('dialog', { name: 'Signed out' });
    await inAct(() => window.dispatchEvent(new CustomEvent('cinefilm:signed-in')));
    await instances(1);
    await inAct(() => FakeHls.last.manifest(LEVELS, ORIGINAL));
    await inAct(() => FakeHls.last.switchTo(ORIGINAL));
    await landResume(video);
    await firstFrame(video, 3000);
    await leave();
    expect(lastReport(fetchMock)).toMatchObject({
      reconnectCount: 0,
      netErrors: 0,
      fellBack: false,
    });
  });

  it('does not count a load aborted on purpose as a network error', async () => {
    const { video, hls, fetchMock } = await open();
    await landResume(video);
    await firstFrame(video, 3000);
    await inAct(() =>
      hls().error({ fatal: false, type: 'networkError', details: 'aborted', response: undefined }),
    );
    await leave();
    expect(lastReport(fetchMock)).toMatchObject({ netErrors: 0, timeouts: 0 });
  });

  it('counts a session rebuilt after it expired', async () => {
    const { video, hls, fetchMock } = await open();
    await landResume(video);
    await firstFrame(video, 10000);
    await inAct(() => hls().error({ fatal: true, type: 'networkError', response: { code: 404 } }));
    await instances(2);
    await leave();
    expect(lastReport(fetchMock)).toMatchObject({ recoveries: 1, fellBack: false });
  });

  it('counts three recoveries, then the fall back when the server will not keep sessions', async () => {
    const { video, fetchMock } = await open();
    await landResume(video);
    await firstFrame(video, 10000);
    for (let i = 0; i < 3; i += 1) {
      await inAct(() =>
        FakeHls.last.error({ fatal: true, type: 'networkError', response: { code: 404 } }),
      );
      await instances(i + 2);
    }
    await inAct(() =>
      FakeHls.last.error({ fatal: true, type: 'networkError', response: { code: 404 } }),
    );
    await vi.waitFor(() => expect(video).toHaveAttribute('src', DIRECT));
    await leave();
    expect(lastReport(fetchMock)).toMatchObject({
      recoveries: 3,
      fellBack: true,
      engine: 'direct',
    });
  });
});

describe('seeks made outside the controls, on the native engine', () => {
  async function openNative() {
    delete window.MediaSource;
    vi.spyOn(HTMLMediaElement.prototype, 'canPlayType').mockReturnValue('maybe');
    const srv = serve();
    const fetchMock = installFetch(srv.route);
    renderApp('/films/1/play?edition=11');
    const video = await screen.findByTestId('player-video');
    await vi.waitFor(() => expect(video).toHaveAttribute('src', '/api/sessions/s1/master.m3u8'));
    return { fetchMock, video };
  }

  it('reports the element’s seeking event as one seek, with its wait', async () => {
    const { fetchMock, video } = await openNative();
    await landResume(video);
    await firstFrame(video, 10000);
    await inAct(() => fireEvent.seeking(video));
    clock = 10800;
    await inAct(() => fireEvent.seeked(video));
    await leave();
    expect(lastReport(fetchMock)).toMatchObject({
      engine: 'native',
      seekCount: 1,
      seekWaitMs: 800,
    });
  });

  it('does not report the seek that restores the resume point while it is still pending', async () => {
    const { fetchMock, video } = await openNative();
    await inAct(() => fireEvent.loadedMetadata(video)); // the restoring seek is now pending
    await firstFrame(video, 10000);
    await inAct(() => fireEvent.seeking(video));
    clock = 10800;
    await inAct(() => fireEvent.seeked(video));
    await leave();
    expect(lastReport(fetchMock)).toMatchObject({ seekCount: 0, seekWaitMs: 0 });
  });

  it('reports a seek through the controls once, not twice', async () => {
    const { fetchMock, video } = await openNative();
    await landResume(video);
    await firstFrame(video, 10000);
    await userEvent.click(screen.getByRole('button', { name: 'Forward 10 seconds' }));
    await inAct(() => fireEvent.seeking(video)); // the event the element fires for that seek
    clock = 11200;
    await inAct(() => fireEvent.seeked(video));
    await leave();
    expect(lastReport(fetchMock)).toMatchObject({ seekCount: 1, seekWaitMs: 1200 });
  });
});

describe('a refused autoplay', () => {
  it('leaves the wait for the viewer out of the start-up', async () => {
    const play = vi.spyOn(HTMLMediaElement.prototype, 'play');
    play.mockRejectedValueOnce(new DOMException('blocked', 'NotAllowedError'));
    const { video, fetchMock } = await open();
    clock = 2000;
    await inAct(() => fireEvent.loadedMetadata(video));
    clock = 9000;
    await userEvent.click(screen.getAllByRole('button', { name: 'Play' })[0]);
    await firstFrame(video, 9500);
    expect(lastReport(fetchMock)).toMatchObject({ autoplayRefused: true, startupMs: 1500 });
  });

  it('ignores a rejection that is not a refusal', async () => {
    const play = vi.spyOn(HTMLMediaElement.prototype, 'play');
    play.mockRejectedValueOnce(new DOMException('aborted', 'AbortError'));
    const { video, fetchMock } = await open();
    await inAct(() => fireEvent.loadedMetadata(video));
    await firstFrame(video, 4000);
    expect(lastReport(fetchMock)).toMatchObject({ autoplayRefused: false, startupMs: 3000 });
  });
});

describe('falling back to direct play', () => {
  it('is recorded when no session could be made', async () => {
    const { video, fetchMock } = await openDirect({ create: () => respond({ error: 'No' }, 409) });
    await landResume(video);
    await firstFrame(video, 5000);
    clock = 6000;
    await moveTo(video, 3862);
    await leave();
    expect(lastReport(fetchMock)).toMatchObject({
      fellBack: true,
      engine: 'direct',
      rungMs: { original: 1000 },
    });
    expect(lastReport(fetchMock).minutes).toEqual([[0, 0, 1, 1000, 0, 0, 0]]);
  });

  it('is recorded when no HLS engine could start', async () => {
    FakeHls.supported = false;
    const { video, fetchMock } = await openDirect();
    await landResume(video);
    await firstFrame(video, 5000);
    expect(lastReport(fetchMock)).toMatchObject({ fellBack: true, engine: 'direct' });
  });

  it('stops naming a fetched rung once the stream is abandoned', async () => {
    const { video, hls, fetchMock } = await open();
    await landResume(video);
    await firstFrame(video, 5000);
    await inAct(() => hls().fetchLevel(P720));
    await inAct(() => hls().error({ fatal: true, type: 'otherError' }));
    await vi.waitFor(() => expect(video).toHaveAttribute('src', DIRECT));
    clock = 70000;
    await inAct(() => fireEvent.playing(video));
    clock = 71000;
    await moveTo(video, 3862);
    await leave();
    const report = lastReport(fetchMock);
    expect(report.engine).toBe('direct');
    expect(report.minutes.at(-1)[0]).toBe(1);
    expect(report.minutes.at(-1)[6]).toBe(0);
  });

  it('is recorded when the stream is abandoned after a fatal error', async () => {
    const { video, hls, fetchMock } = await open();
    await landResume(video);
    await firstFrame(video, 5000);
    await inAct(() => hls().error({ fatal: true, type: 'otherError' }));
    await vi.waitFor(() => expect(video).toHaveAttribute('src', DIRECT));
    await leave();
    expect(lastReport(fetchMock)).toMatchObject({ fellBack: true, engine: 'direct' });
  });

  it('is not recorded when Original is chosen with the worker offline', async () => {
    const { video, fetchMock } = await openDirect({ film: heat({ offline: true }) });
    await landResume(video);
    await firstFrame(video, 5000);
    expect(lastReport(fetchMock)).toMatchObject({
      fellBack: false,
      engine: 'direct',
      quality: 'original',
    });
  });

  it('is not recorded when Original is chosen for a film with no copy rung', async () => {
    const noCopy = {
      ...fixtures.session,
      renditions: fixtures.session.renditions.map((r) => ({ ...r, copy: false })),
    };
    const { video, fetchMock } = await openDirect({
      film: heat({ quality: 'original' }),
      create: () => respond(noCopy, 201),
    });
    await landResume(video);
    await firstFrame(video, 5000);
    expect(lastReport(fetchMock)).toMatchObject({ fellBack: false, engine: 'direct' });
  });

  it('is not recorded for a film in several parts that cannot be played', async () => {
    const film = heat();
    film.editions[0].parts = 2;
    const { video, fetchMock } = await openDirect({
      film,
      create: () => respond({ error: 'No' }, 409),
      expectSrc: false,
    });
    await screen.findByText(/can.t be played|can.t play|unavailable/i);
    await firstFrame(video, 5000);
    expect(lastReport(fetchMock)).toMatchObject({ fellBack: false, engine: null });
  });
});

describe('leaving the player', () => {
  it('sends the last totals with keepalive when the player unmounts', async () => {
    const { video, view, fetchMock } = await open();
    await landResume(video);
    await firstFrame(video, 10000);
    clock = 11000;
    await moveTo(video, 3862);
    view.unmount();
    const sends = sendsOf(fetchMock);
    expect(sends).toHaveLength(2);
    expect(sends[1][1].keepalive).toBe(true);
    expect(JSON.parse(sends[1][1].body)).toMatchObject({ seq: 2, watchedMs: 1000 });
  });
});

describe('the stream effect', () => {
  it('does not start again when the player re-renders', async () => {
    const { video, log, fetchMock } = await open();
    await landResume(video);
    await firstFrame(video, 10000);
    await userEvent.click(screen.getByRole('button', { name: 'Quality' }));
    await userEvent.click(screen.getByRole('button', { name: 'Audio and subtitles' }));
    await userEvent.click(screen.getAllByRole('button', { name: /Pause|Play/ })[0]);
    clock = 11000;
    await moveTo(video, 3862);
    expect(log.posted).toHaveLength(1);
    expect(FakeHls.instances).toHaveLength(1);
    expect(FakeHls.last.destroy).not.toHaveBeenCalled();
    expect(fetchMock.mock.calls.filter(([url]) => /\/sessions$/.test(url))).toHaveLength(1);
  });

  it('records a viewing without letting a failed send touch playback', async () => {
    const { video } = await open();
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.reject(new TypeError('Failed to fetch'))),
    );
    await landResume(video);
    await firstFrame(video, 3000);
    expect(screen.getByRole('region', { name: 'Video player' })).toBeInTheDocument();
    expect(video).not.toHaveAttribute('src', DIRECT);
  });
});

describe('the clock of a viewing', () => {
  // The app inside a router the test can steer: the same Player stays mounted as the address changes.
  let go;
  function Steer() {
    go = useNavigate();
    return null;
  }
  const twoEditions = () => {
    const film = heat();
    film.editions.push({ ...structuredClone(film.editions[0]), id: 12, positionMs: 0 });
    return film;
  };
  async function openSteered() {
    const srv = serve({ film: twoEditions() });
    const fetchMock = installFetch(srv.route);
    clock = 1000;
    render(
      <MemoryRouter initialEntries={['/films/1/play?edition=11']}>
        <Steer />
        <App />
      </MemoryRouter>,
    );
    const video = await screen.findByTestId('player-video');
    await instances(1);
    await inAct(() => FakeHls.last.manifest(LEVELS, ORIGINAL));
    await inAct(() => FakeHls.last.switchTo(ORIGINAL));
    await landResume(video);
    await firstFrame(video, 3000);
    return { fetchMock, video };
  }
  const reports = (fetchMock) =>
    sendsOf(fetchMock).map(([url, init]) => ({ url, ...JSON.parse(init.body) }));

  it('starts a new viewing’s clock when the address changes to another edition', async () => {
    const { fetchMock, video } = await openSteered();
    const [first] = reports(fetchMock);
    expect(first).toMatchObject({ editionId: 11, seq: 1, startupMs: 2000, elapsedMs: 2000 });

    clock = 33000;
    await inAct(() => go('/films/1/play?edition=12'));
    await vi.waitFor(() => expect(screen.getByTestId('player-video')).not.toBe(video));
    const next = screen.getByTestId('player-video');
    await vi.waitFor(() => expect(next).toHaveAttribute('src', '/api/editions/12/stream'));
    await firstFrame(next, 35000);

    const second = reports(fetchMock).find((r) => r.editionId === 12);
    expect(second.url).not.toBe(first.url);
    expect(second).toMatchObject({ seq: 1, startupMs: 2000, elapsedMs: 2000 });
  });

  it('keeps the viewing’s clock when only start=0 is added to the address', async () => {
    const { fetchMock, video } = await openSteered();
    const [first] = reports(fetchMock);

    clock = 33000;
    await inAct(() => go('/films/1/play?edition=11&start=0'));
    expect(screen.getByTestId('player-video')).toBe(video);
    clock = 41000;
    await leave();

    const later = reports(fetchMock).at(-1);
    expect(later.url).toBe(first.url);
    expect(later).toMatchObject({ editionId: 11, seq: 2, startupMs: 2000, elapsedMs: 40000 });
  });
});
