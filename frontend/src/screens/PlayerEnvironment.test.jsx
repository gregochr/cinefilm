import { act, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, useNavigate } from 'react-router-dom';
import App from '../App.jsx';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { measureThroughput } from '../player/probe.js';
import { NETWORKS_KEY } from '../state/networks.js';
import { FakeHls } from '../test/fakeHls.js';
import { fixtures, installFetch, renderApp, respond } from '../test/mockApi.jsx';
import { installStorage } from '../test/storage.js';
import { resetThroughputState } from '../player/throughput.js';

// The environment as the player wires it, with the real measurement, lookup, hook, card and recorder. Only the
// browser's edges are faked: a PerformanceObserver that is handed an entry for /api/session, and the lookup
// frame, which is answered with the message its own script would post. hls.js and the probe are the same
// stand-ins the streaming tests use.
vi.mock('hls.js/light', async () => ({ default: (await import('../test/fakeHls.js')).FakeHls }));
vi.mock('../player/probe.js', () => ({ measureThroughput: vi.fn() }));

class FakeObserver {
  static instances = [];
  constructor(callback) {
    this.callback = callback;
    this.queued = [];
    FakeObserver.instances.push(this);
  }
  observe() {}
  takeRecords() {
    const taken = this.queued;
    this.queued = [];
    return taken;
  }
  disconnect() {}
}

let latency; // the round trip in ms every request to /api/session is timed at, or null for no timing
let storage;
beforeEach(() => {
  FakeHls.reset();
  FakeObserver.instances = [];
  latency = 48;
  window.MediaSource = class {};
  measureThroughput.mockReset().mockResolvedValue(null);
  sessionStorage.clear();
  resetThroughputState();
  storage = installStorage();
  vi.stubGlobal('PerformanceObserver', FakeObserver);
});
afterEach(() => {
  delete window.MediaSource;
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const NAME = 'Where are you watching?';
const RAIL = 'AS64500 Example Rail';
const NEW_NETWORK =
  'CineFilm hasn’t seen this network before: Example Rail. It will remember your answer on this device.';
const NO_NETWORK = 'CineFilm can’t tell which network this is, so it will ask again next time.';
const HEALTH = /^\/api\/playback-health\//;
const LEVELS = [
  { height: 360, bitrate: 600000 },
  { height: 480, bitrate: 1000000 },
  { height: 720, bitrate: 2500000 },
  { height: 1080, bitrate: 24000000 },
];
const [, P480, , ORIGINAL] = [0, 1, 2, 3];

// Heat that direct-plays (the worker is off, so Original is the only rung), or Heat on Auto with every rung.
function heat({ direct = true, parts } = {}) {
  const film = structuredClone(fixtures.heat);
  film.preferences.quality = direct ? 'original' : 'auto';
  for (const q of film.editions[0].qualities) q.available = !direct || q.id === 'original';
  if (parts) film.editions[0].parts = parts;
  return film;
}

function serve({ film, create }) {
  return (method, url) => {
    if (url === '/api/films/1') return respond(film);
    if (method === 'GET' && url === '/api/session') {
      // The browser's timing of this very request.
      if (latency !== null) {
        FakeObserver.instances.at(-1).queued.push({
          name: new URL('/api/session', window.location.href).href,
          requestStart: 100,
          responseStart: 100 + latency,
        });
      }
      return null;
    }
    if (method === 'POST' && url === '/api/editions/11/sessions') {
      return (
        create?.() ??
        respond(
          { ...fixtures.session, sessionId: 's1', masterUrl: '/api/sessions/s1/master.m3u8' },
          201,
        )
      );
    }
    if (method === 'DELETE' && url.startsWith('/api/sessions/')) return respond(null, 204);
    return null;
  };
}

const inAct = (fn) => act(async () => fn());
const sendsOf = (fetchMock) =>
  fetchMock.mock.calls.filter(([url, init]) => init?.method === 'PUT' && HEALTH.test(url));
const lastReport = (fetchMock) => JSON.parse(sendsOf(fetchMock).at(-1)[1].body);
const leave = () => inAct(() => window.dispatchEvent(new Event('pagehide')));
const player = () => screen.getByRole('region', { name: 'Video player' });
const announcement = () => screen.getByTestId('player-announcement');
const card = () => screen.getByRole('group', { name: NAME });
const noCard = () => expect(screen.queryByRole('group', { name: NAME })).not.toBeInTheDocument();

async function open({ film = heat(), create } = {}) {
  const fetchMock = installFetch(serve({ film, create }));
  const view = renderApp('/films/1/play?edition=11');
  const video = await screen.findByTestId('player-video');
  return { fetchMock, view, video };
}

// The frame the page put in the document answers, as its own script would.
async function answerLookup(org) {
  const frame = await screen.findByTestId('operator-lookup');
  await inAct(() =>
    window.dispatchEvent(
      new MessageEvent('message', {
        data: { cinefilmOperator: org },
        origin: 'null',
        source: frame.contentWindow,
      }),
    ),
  );
}

const startFilm = async (video) => {
  await inAct(() => fireEvent.loadedMetadata(video));
  await inAct(() => fireEvent.timeUpdate(video));
  await inAct(() => fireEvent.playing(video));
};

// The report the recorder would send now: leaving flushes it. Waits until the environment has reached it.
const reportWhere = (fetchMock, expected) =>
  vi.waitFor(async () => {
    await leave();
    expect(lastReport(fetchMock)).toMatchObject(expected);
  });

describe('the question', () => {
  it('shows on a network not seen before, with the film still in the player’s hands', async () => {
    const { video } = await open();
    expect(announcement()).toBeEmptyDOMElement();
    await answerLookup(RAIL);
    const group = await screen.findByRole('group', { name: NAME });
    expect(group).toHaveAccessibleDescription(NEW_NETWORK);
    expect(announcement()).toHaveTextContent(`${NAME} ${NEW_NETWORK}`);
    expect(player()).toHaveFocus();

    await userEvent.keyboard(' ');
    expect(video.paused).toBe(false);
    await userEvent.keyboard(' ');
    expect(video.paused).toBe(true);
    expect(card()).toBeInTheDocument();
  });

  it('has Tab from the player reach its buttons before the controls, and Space there answers and does not pause', async () => {
    const { video } = await open();
    await answerLookup(RAIL);
    await screen.findByRole('group', { name: NAME });
    expect(player()).toHaveFocus();
    await userEvent.keyboard(' '); // play
    expect(video.paused).toBe(false);

    await userEvent.tab();
    const yes = screen.getByRole('button', { name: 'On a train' });
    expect(yes).toHaveFocus();
    await userEvent.keyboard(' ');

    noCard();
    expect(video.paused).toBe(false);
    expect(JSON.parse(storage.getItem(NETWORKS_KEY))).toEqual([
      { operator: RAIL, environment: 'train' },
    ]);
    expect(player()).toHaveFocus();
  });

  it('On a train closes it, says so, remembers it and reports the train, the operator and the latency', async () => {
    const { video, fetchMock } = await open();
    await startFilm(video);
    await answerLookup(RAIL);
    await userEvent.click(await screen.findByRole('button', { name: 'On a train' }));

    noCard();
    expect(screen.getByText('Remembered for Example Rail')).toBeInTheDocument();
    expect(JSON.parse(storage.getItem(NETWORKS_KEY))).toEqual([
      { operator: RAIL, environment: 'train' },
    ]);
    expect(player()).toHaveFocus();
    expect(announcement()).toBeEmptyDOMElement();
    await leave();
    expect(lastReport(fetchMock)).toMatchObject({
      environment: 'train',
      operator: RAIL,
      latencyMs: 48,
    });
  });

  it('Hotel or away remembers away', async () => {
    const { video, fetchMock } = await open();
    await startFilm(video);
    await answerLookup(RAIL);
    await userEvent.click(await screen.findByRole('button', { name: 'Hotel or away' }));

    noCard();
    expect(screen.getByText('Remembered for Example Rail')).toBeInTheDocument();
    expect(JSON.parse(storage.getItem(NETWORKS_KEY))).toEqual([
      { operator: RAIL, environment: 'away' },
    ]);
    expect(player()).toHaveFocus();
    await leave();
    expect(lastReport(fetchMock)).toMatchObject({
      environment: 'away',
      operator: RAIL,
      latencyMs: 48,
    });
  });

  it('Not now closes it, remembers nothing and returns focus to the player', async () => {
    const { video, fetchMock } = await open();
    await startFilm(video);
    await answerLookup(RAIL);
    await userEvent.click(await screen.findByRole('button', { name: 'Not now' }));

    noCard();
    expect(screen.queryByText(/Remembered for/)).not.toBeInTheDocument();
    expect(storage.getItem(NETWORKS_KEY)).toBeNull();
    expect(player()).toHaveFocus();
    expect(announcement()).toBeEmptyDOMElement();
    await leave();
    expect(lastReport(fetchMock)).toMatchObject({
      environment: 'unknown',
      operator: RAIL,
      latencyMs: 48,
    });
  });

  it('Escape closes it, remembers nothing and leaves focus on the player', async () => {
    await open();
    await answerLookup(RAIL);
    await screen.findByRole('group', { name: NAME });
    await userEvent.keyboard('{Escape}');

    noCard();
    expect(storage.getItem(NETWORKS_KEY)).toBeNull();
    expect(player()).toHaveFocus();
  });

  it('hides while a menu is open, comes back when it closes, and a second Escape closes it', async () => {
    await open();
    await answerLookup(RAIL);
    await screen.findByRole('group', { name: NAME });

    await userEvent.keyboard('q');
    expect(screen.getByRole('menu', { name: 'Quality' })).toBeInTheDocument();
    noCard();
    expect(announcement()).toBeEmptyDOMElement();

    await userEvent.keyboard('{Escape}');
    expect(screen.queryByRole('menu', { name: 'Quality' })).not.toBeInTheDocument();
    expect(card()).toBeInTheDocument();
    expect(announcement()).toHaveTextContent(`${NAME} ${NEW_NETWORK}`);
    expect(player()).toHaveFocus();

    await userEvent.keyboard('{Escape}');
    noCard();
  });
});

describe('what it does not ask', () => {
  it('shows nothing on a network that has been answered, and reports that answer', async () => {
    storage.setItem(NETWORKS_KEY, JSON.stringify([{ operator: RAIL, environment: 'train' }]));
    const { video, fetchMock } = await open();
    await startFilm(video);
    await answerLookup(RAIL);
    await reportWhere(fetchMock, { environment: 'train', operator: RAIL, latencyMs: 48 });
    noCard();
    expect(screen.queryByText(/Remembered for/)).not.toBeInTheDocument();
    expect(announcement()).toBeEmptyDOMElement();
  });

  it('is home at 3 ms: no lookup frame, no card', async () => {
    latency = 3;
    const { video, fetchMock } = await open();
    await startFilm(video);
    await reportWhere(fetchMock, { environment: 'home', operator: null, latencyMs: 3 });
    expect(screen.queryByTestId('operator-lookup')).not.toBeInTheDocument();
    noCard();
  });

  it('is unknown with no timing: no lookup frame, no card, one request to /api/session', async () => {
    latency = null;
    const { video, fetchMock } = await open();
    await startFilm(video);
    await leave();
    expect(lastReport(fetchMock)).toMatchObject({
      environment: 'unknown',
      operator: null,
      latencyMs: null,
    });
    expect(screen.queryByTestId('operator-lookup')).not.toBeInTheDocument();
    noCard();
    const probes = fetchMock.mock.calls.filter(([url]) => url === '/api/session');
    expect(probes).toHaveLength(1);
  });
});

describe('when the network cannot be named', () => {
  it('asks the other question, stores nothing, says nothing and still reports the answer', async () => {
    const { video, fetchMock } = await open();
    await startFilm(video);
    await answerLookup(null);
    const group = await screen.findByRole('group', { name: NAME });
    expect(group).toHaveAccessibleDescription(NO_NETWORK);
    expect(announcement()).toHaveTextContent(`${NAME} ${NO_NETWORK}`);

    await userEvent.click(screen.getByRole('button', { name: 'On a train' }));
    noCard();
    expect(screen.queryByText(/Remembered for/)).not.toBeInTheDocument();
    expect(storage.getItem(NETWORKS_KEY)).toBeNull();
    await leave();
    expect(lastReport(fetchMock)).toMatchObject({
      environment: 'train',
      operator: null,
      latencyMs: 48,
    });
  });

  it('treats a relay like no network: asked as such, never stored, and recorded with the viewing', async () => {
    const relay = 'AS13335 Cloudflare, Inc.';
    storage.setItem(NETWORKS_KEY, JSON.stringify([{ operator: relay, environment: 'away' }]));
    const before = storage.getItem(NETWORKS_KEY);
    const { video, fetchMock } = await open();
    await startFilm(video);
    await answerLookup(relay);
    const group = await screen.findByRole('group', { name: NAME });
    expect(group).toHaveAccessibleDescription(NO_NETWORK);

    await userEvent.click(screen.getByRole('button', { name: 'On a train' }));
    expect(screen.queryByText(/Remembered for/)).not.toBeInTheDocument();
    expect(storage.getItem(NETWORKS_KEY)).toBe(before);
    await leave();
    expect(lastReport(fetchMock)).toMatchObject({
      environment: 'train',
      operator: relay,
      latencyMs: 48,
    });
  });
});

describe('one card at a time', () => {
  const slowDialog = () => screen.queryByRole('dialog', { name: 'You’re on a slow connection' });

  async function openAuto() {
    const opened = await open({ film: heat({ direct: false }) });
    await vi.waitFor(() => expect(FakeHls.instances).toHaveLength(1));
    await inAct(() => FakeHls.last.manifest(LEVELS, ORIGINAL));
    await inAct(() => FakeHls.last.switchTo(ORIGINAL));
    // The slow-connection card needs a known speed below home: a slow segment (2,000 kbit/s) first.
    await inAct(() =>
      FakeHls.last.fragLoaded({ loaded: 250_000, loading: { start: 0, first: 0, end: 1000 } }),
    );
    return opened;
  }

  it('makes the slow-connection card wait until the question is answered', async () => {
    await openAuto();
    await answerLookup(RAIL);
    await screen.findByRole('group', { name: NAME });

    await inAct(() => FakeHls.last.switchTo(P480));
    expect(slowDialog()).not.toBeInTheDocument();
    expect(card()).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Hotel or away' }));
    expect(
      await screen.findByRole('dialog', { name: 'You’re on a slow connection' }),
    ).toBeInTheDocument();
  });

  it('makes the question wait while the slow-connection card is open', async () => {
    await openAuto();
    await inAct(() => FakeHls.last.switchTo(P480));
    const slow = await screen.findByRole('dialog', { name: 'You’re on a slow connection' });

    await answerLookup(RAIL);
    // Give a card that would wrongly appear the time to do so.
    await inAct(() => {});
    noCard();
    expect(announcement()).toBeEmptyDOMElement();

    await userEvent.click(screen.getByRole('button', { name: 'OK' }));
    expect(slowDialog()).not.toBeInTheDocument();
    expect(slow).not.toBeInTheDocument();
    expect(await screen.findByRole('group', { name: NAME })).toBeInTheDocument();
  });

  it('does not show over the end card, and comes back when the credits are kept', async () => {
    const { video } = await open();
    await inAct(() => fireEvent.loadedMetadata(video));
    await inAct(() => fireEvent.timeUpdate(video));
    await answerLookup(RAIL);
    await screen.findByRole('group', { name: NAME });

    video.currentTime = 9900;
    await inAct(() => fireEvent.timeUpdate(video));
    const end = await screen.findByRole('dialog', { name: 'Heat' });
    noCard();
    expect(announcement()).toBeEmptyDOMElement();

    await userEvent.click(screen.getByRole('button', { name: 'Keep watching the credits' }));
    expect(end).not.toBeInTheDocument();
    expect(card()).toBeInTheDocument();
  });

  it('does not show over the can’t-be-played card', async () => {
    const film = heat({ direct: false, parts: 2 });
    await open({ film, create: () => respond({ error: 'No encoder' }, 409) });
    await screen.findByRole('alert');
    await answerLookup(RAIL);
    await inAct(() => {});
    noCard();
    expect(screen.getByRole('alert')).toHaveTextContent('This film can’t be played right now');
    expect(announcement()).toBeEmptyDOMElement();
  });
});

describe('leaving the player', () => {
  it('removes the lookup frame', async () => {
    const { view } = await open();
    expect(await screen.findByTestId('operator-lookup')).toBeInTheDocument();
    view.unmount();
    expect(screen.queryByTestId('operator-lookup')).not.toBeInTheDocument();
  });
});

describe('a new viewing in the same player', () => {
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
    const fetchMock = installFetch(serve({ film: twoEditions() }));
    render(
      <MemoryRouter initialEntries={['/films/1/play?edition=11']}>
        <Steer />
        <App />
      </MemoryRouter>,
    );
    const video = await screen.findByTestId('player-video');
    return { fetchMock, video };
  }
  const roundTrips = (fetchMock) => fetchMock.mock.calls.filter(([url]) => url === '/api/session');
  // Moves to the other edition; resolves with its video once the new viewing has mounted.
  async function openSecond(video) {
    await inAct(() => go('/films/1/play?edition=12'));
    await vi.waitFor(() => expect(screen.getByTestId('player-video')).not.toBe(video));
    return screen.getByTestId('player-video');
  }

  it('measures again when the address changes to another edition in the same player', async () => {
    const { fetchMock, video } = await openSteered();
    await answerLookup(RAIL);
    await userEvent.click(await screen.findByRole('button', { name: 'Not now' }));
    noCard();
    expect(roundTrips(fetchMock)).toHaveLength(3);

    latency = 3;
    const next = await openSecond(video);
    await startFilm(next);
    await reportWhere(fetchMock, {
      editionId: 12,
      environment: 'home',
      latencyMs: 3,
      operator: null,
    });
    expect(roundTrips(fetchMock)).toHaveLength(6);
    expect(screen.queryByTestId('operator-lookup')).not.toBeInTheDocument();
    noCard();
    expect(announcement()).toBeEmptyDOMElement();
  });

  it('asks again for the new viewing when the question was dismissed', async () => {
    const { fetchMock, video } = await openSteered();
    await answerLookup(RAIL);
    await userEvent.click(await screen.findByRole('button', { name: 'Not now' }));
    noCard();
    expect(roundTrips(fetchMock)).toHaveLength(3);

    await openSecond(video);
    await answerLookup(RAIL);
    expect(await screen.findByRole('group', { name: NAME })).toHaveAccessibleDescription(
      NEW_NETWORK,
    );
    expect(roundTrips(fetchMock)).toHaveLength(6);
    expect(storage.getItem(NETWORKS_KEY)).toBeNull();
  });

  it('aborts the first viewing’s lookup when the address changes', async () => {
    const { fetchMock, video } = await openSteered();
    const frame = await screen.findByTestId('operator-lookup');
    const source = frame.contentWindow;
    expect(roundTrips(fetchMock)).toHaveLength(3);

    latency = 3;
    const next = await openSecond(video);
    expect(frame).not.toBeInTheDocument();
    expect(screen.queryByTestId('operator-lookup')).not.toBeInTheDocument();

    await inAct(() =>
      window.dispatchEvent(
        new MessageEvent('message', { data: { cinefilmOperator: RAIL }, origin: 'null', source }),
      ),
    );
    await startFilm(next);
    await reportWhere(fetchMock, { editionId: 12, environment: 'home', operator: null });
    noCard();
    expect(storage.getItem(NETWORKS_KEY)).toBeNull();
  });
});
