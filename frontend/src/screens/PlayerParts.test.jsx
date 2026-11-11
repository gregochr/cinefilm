import { act, fireEvent, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { measureThroughput } from '../player/probe.js';
import { FakeHls } from '../test/fakeHls.js';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';
import { resetThroughputState } from '../player/throughput.js';

// A film split across several files plays as one continuous film: every time is an edition time (from the start
// of part 1), it is never direct-played, and nothing on screen names a part.
vi.mock('hls.js/light', async () => ({ default: (await import('../test/fakeHls.js')).FakeHls }));
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

const LEVELS = [
  { height: 360, bitrate: 600000 },
  { height: 480, bitrate: 1000000 },
  { height: 720, bitrate: 2500000 },
  { height: 1080, bitrate: 24000000 },
];
const ORIGINAL = 3;
const DIRECT = '/api/editions/11/stream';
// Part 1 is 6,353.42 s of video, so part 2 starts there; the edition is 14,100 s (3h 55m) long.
const JOIN_S = 6353.42;
const RUNTIME_MS = 14_100_000;

function split({ position = 12_500_000, offline = false, quality = 'auto' } = {}) {
  const film = structuredClone(fixtures.heat);
  const edition = film.editions[0];
  edition.parts = 2;
  edition.runtimeMs = RUNTIME_MS;
  edition.positionMs = position;
  edition.creditsMarkerMs = null;
  film.preferences.quality = quality;
  for (const q of edition.qualities) q.available = !offline || q.id === 'original';
  return film;
}

function serve({ film = split(), create } = {}) {
  const log = { posted: [], deleted: [], progress: [] };
  let n = 0;
  const route = (method, url, init = {}) => {
    if (url === '/api/films/1') return respond(film);
    if (method === 'POST' && url === '/api/editions/11/sessions') {
      log.posted.push(JSON.parse(init.body));
      if (create) {
        const answer = create(log.posted.length);
        if (answer) return answer;
      }
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
      log.deleted.push(url);
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

async function open(options = {}) {
  const srv = serve(options);
  installFetch(srv.route);
  renderApp('/films/1/play?edition=11');
  const video = await screen.findByTestId('player-video');
  await instances(1);
  await hlsEvent(() => FakeHls.last.manifest(LEVELS, ORIGINAL));
  await hlsEvent(() => FakeHls.last.switchTo(ORIGINAL));
  return { ...srv, video };
}

async function landResume(video) {
  await hlsEvent(() => fireEvent.loadedMetadata(video));
  await hlsEvent(() => fireEvent.timeUpdate(video));
}

async function moveTo(video, seconds) {
  video.currentTime = seconds;
  await hlsEvent(() => fireEvent.timeUpdate(video));
}

describe('playing a film split across several files', () => {
  it('resumes inside the second part at the edition time and never probes the direct stream', async () => {
    const { log } = await open();

    // Saved at 12,500,000 ms of the whole edition; resume is 10 s earlier.
    expect(log.posted).toEqual([
      { quality: 'auto', startMs: 12_490_000, audioStreamId: 111, probeKbps: 0 },
    ]);
    expect(measureThroughput).not.toHaveBeenCalled();
    expect(FakeHls.last.config.startPosition).toBe(12490);
  });

  it('keeps the scrubber and the clocks in edition time', async () => {
    const { video } = await open();
    await landResume(video);

    const slider = screen.getByRole('slider', { name: 'Seek' });
    expect(slider).toHaveAttribute('aria-valuemax', '14100');
    expect(slider).toHaveAttribute('aria-valuenow', '12490');
    expect(slider).toHaveAttribute('aria-valuetext', '3 hours 28 minutes 10 seconds');
    expect(screen.getByText('03:28:10')).toBeInTheDocument();
    expect(screen.getByText('−00:26:50')).toBeInTheDocument();
  });

  it('steps 10 seconds across the join as plain seeks in edition time', async () => {
    const { video } = await open();
    await landResume(video);
    await moveTo(video, JOIN_S - 5);

    await userEvent.click(screen.getByRole('button', { name: 'Forward 10 seconds' }));
    expect(video.currentTime).toBeCloseTo(JOIN_S + 5, 3);

    await userEvent.click(screen.getByRole('button', { name: 'Back 10 seconds' }));
    expect(video.currentTime).toBeCloseTo(JOIN_S - 5, 3);
  });

  it('reports progress after the join as the edition time', async () => {
    const { log, video } = await open();
    await landResume(video);
    await moveTo(video, JOIN_S + 600);

    await hlsEvent(() => video.play());
    await hlsEvent(() => video.pause());

    expect(log.progress.at(-1)).toBe(Math.round((JOIN_S + 600) * 1000));
  });

  it('offers the end of the film at 92% of the summed runtime, not of a part', async () => {
    const { video } = await open();
    await landResume(video);
    await moveTo(video, 12_971);
    expect(screen.queryByText('Mark as watched')).not.toBeInTheDocument();

    await moveTo(video, 12_972);
    expect(await screen.findByText('Mark as watched')).toBeInTheDocument();
  });

  it('asks again when the server is still preparing the later parts', async () => {
    const srv = serve({
      create: (n) => (n === 1 ? respond({ error: 'The film is still being prepared' }, 503) : null),
    });
    installFetch(srv.route);
    renderApp('/films/1/play?edition=11');

    // The first answer is 503: the player waits a second and asks again instead of giving up.
    await vi.waitFor(() => expect(FakeHls.instances).toHaveLength(1), { timeout: 4000 });
    expect(srv.log.posted).toHaveLength(2);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('drops the resume still when the film cannot be played right now', async () => {
    const srv = serve({ create: (n) => (n === 1 ? respond({ error: 'No encoder' }, 409) : null) });
    installFetch(srv.route);
    renderApp('/films/1/play?edition=11');
    await screen.findByRole('alert');

    await vi.waitFor(() => expect(screen.queryByTestId('player-still')).not.toBeInTheDocument());
  });

  it('never direct-plays: a refused session says so and Try again asks once more', async () => {
    const srv = serve({ create: (n) => (n === 1 ? respond({ error: 'No encoder' }, 409) : null) });
    installFetch(srv.route);
    renderApp('/films/1/play?edition=11');
    const video = await screen.findByTestId('player-video');

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('This film can’t be played right now');
    expect(video).not.toHaveAttribute('src');
    expect(FakeHls.instances).toHaveLength(0);

    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    await instances(1);
    expect(srv.log.posted).toHaveLength(2);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(video).not.toHaveAttribute('src');
  });

  it('with the worker offline asks for no session and does not direct-play the original', async () => {
    const srv = serve({ film: split({ offline: true, quality: 'original' }) });
    installFetch(srv.route);
    renderApp('/films/1/play?edition=11');
    const video = await screen.findByTestId('player-video');

    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(srv.log.posted).toEqual([]);
    expect(video).not.toHaveAttribute('src', DIRECT);
    expect(video).not.toHaveAttribute('src');
  });

  it('does not direct-play the original when the session has no copy rung, and drops the session', async () => {
    const noCopy = {
      ...fixtures.session,
      renditions: fixtures.session.renditions.map((r) => ({ ...r, copy: false })),
    };
    const srv = serve({ film: split({ quality: 'original' }), create: () => respond(noCopy, 201) });
    installFetch(srv.route);
    renderApp('/films/1/play?edition=11');
    const video = await screen.findByTestId('player-video');

    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(video).not.toHaveAttribute('src');
    expect(srv.log.deleted).toEqual([`/api/sessions/${noCopy.sessionId}`]);
  });

  it('shows the same card instead of direct-playing when hls.js cannot recover', async () => {
    const { video } = await open();
    await hlsEvent(() => FakeHls.last.error({ fatal: true, type: 'otherError' }));

    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(video).not.toHaveAttribute('src');
  });

  it('shows Reconnecting…, not the card, when the session request gets no answer, and streams once it works', async () => {
    const srv = serve({
      create: (n) => {
        if (n === 1) throw new TypeError('Failed to fetch');
        return null;
      },
    });
    installFetch(srv.route);
    renderApp('/films/1/play?edition=11');
    const video = await screen.findByTestId('player-video');
    const notice = await screen.findByText('Reconnecting…');
    expect(notice.closest('[role="status"]')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(video).not.toHaveAttribute('src');
    await hlsEvent(() => window.dispatchEvent(new Event('online')));
    await instances(1);
    await vi.waitFor(() => expect(screen.queryByText('Reconnecting…')).not.toBeInTheDocument());
    expect(srv.log.posted).toHaveLength(2);
    expect(measureThroughput).not.toHaveBeenCalled();
  });

  it('names no part anywhere on the player', async () => {
    const { video } = await open();
    await landResume(video);

    expect(document.body.textContent).not.toMatch(/part\s*\d|pt\s*\d|cd\s*\d/i);
  });
});
