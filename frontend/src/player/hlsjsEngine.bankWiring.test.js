import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FakeHls } from '../test/fakeHls.js';
import { chooseBankRung } from './bankRung.js';
import { RECONNECT_MS, startHlsJs } from './hlsjsEngine.js';

// The real bank wired by the engine over a fake hls.js, server and inner loader; the modules are wrapped only to
// capture what the engine builds.
const made = vi.hoisted(() => ({
  fronts: [],
  requests: [],
  stores: [],
  recording: (key, list) => async (original) => {
    const real = await original();
    return {
      ...real,
      [key]: (options) => {
        const built = real[key](options);
        list.push(built);
        return built;
      },
    };
  },
}));
vi.mock('./bankFront.js', made.recording('createBankFront', made.fronts));
vi.mock('./bankRequests.js', made.recording('createBankRequests', made.requests));
vi.mock('./segmentBank.js', made.recording('createSegmentBank', made.stores));
vi.mock('./bankRung.js', async (original) => {
  const real = await original();
  return { ...real, chooseBankRung: vi.fn(real.chooseBankRung) };
});

const rung = (id, height, bitrate) => ({ id, height, bitrate, copy: false });
const rungs = [
  rung('240p', 240, 364_000),
  rung('360p', 360, 816_000),
  rung('480p', 480, 1_328_000),
  rung('720p', 720, 3_128_000),
];
const playlistUrl = (id) => `https://film.test/api/s/1/${id}/index.m3u8`;
const segUrl = (id, n) => `https://film.test/api/s/1/${id}/seg-${n}.ts`;
const levelsOf = (ladder = rungs) =>
  ladder.map(({ id, height, bitrate }) => ({ height, bitrate, uri: playlistUrl(id) }));
const playlistText = () =>
  `#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:0\n${Array.from(
    { length: 200 },
    (_, i) => `#EXTINF:4.000,\nseg-${i}.ts`,
  ).join('\n')}\n#EXT-X-ENDLIST\n`;

class InnerLoader {
  static loads = [];
  static order = [];
  static get last() {
    return InnerLoader.loads.at(-1);
  }
  constructor() {
    this.stats = {
      aborted: false,
      loaded: 0,
      total: 0,
      loading: { start: 0, first: 0, end: 0 },
    };
  }
  load(context, config, callbacks) {
    InnerLoader.order.push('inner.load');
    this.stats.loading.start = Date.now();
    this.context = context;
    this.callbacks = callbacks;
    InnerLoader.loads.push(this);
  }
  abort() {}
  destroy() {}
  succeed(size = 1000) {
    const { stats } = this;
    stats.loading.first = stats.loading.start + 50;
    stats.loading.end = stats.loading.start + 200;
    stats.loaded = stats.total = size;
    this.callbacks.onSuccess(
      { url: this.context.url, data: new Uint8Array(size).fill(9).buffer },
      stats,
      this.context,
      null,
    );
  }
}
class WiringHls extends FakeHls {
  static DefaultConfig = { loader: InnerLoader };
}

function makeServer() {
  const server = { calls: [] };
  server.fetch = (url, init) =>
    new Promise((resolve, reject) => {
      const call = { url, aborted: false };
      const waiting = [];
      init.signal.addEventListener('abort', () => {
        call.aborted = true;
        const err = new DOMException('aborted', 'AbortError');
        reject(err);
        waiting.splice(0).forEach((w) => w.rej(err));
      });
      call.status = (status) => resolve({ status, headers: { get: () => null } });
      call.respond = (bytes) => {
        let sent = false;
        const reader = {
          read: () => {
            if (!sent) {
              sent = true;
              return Promise.resolve({ done: false, value: bytes });
            }
            return new Promise((done) => {
              call.end = () => done({ done: true });
            });
          },
        };
        resolve({ status: 200, headers: { get: () => null }, body: { getReader: () => reader } });
      };
      server.calls.push(call);
    });
  return server;
}

const flush = () => vi.advanceTimersByTimeAsync(0);
const answerPlaylist = async (call) => {
  call.respond(new TextEncoder().encode(playlistText()));
  await flush();
  call.end();
  await flush();
};
const answerSegment = async (call, size = 1000, bodyMs = 0) => {
  call.respond(new Uint8Array(size).fill(5));
  await vi.advanceTimersByTimeAsync(bodyMs);
  call.end();
  await flush();
};

const engines = [];
beforeEach(() => {
  FakeHls.reset();
  vi.useFakeTimers();
  vi.setSystemTime(1_000_000);
  InnerLoader.loads = [];
  InnerLoader.order = [];
  made.fronts.length = 0;
  made.requests.length = 0;
  made.stores.length = 0;
  chooseBankRung.mockClear();
});
afterEach(() => {
  engines.splice(0).forEach((engine) => engine.destroy());
  vi.useRealTimers();
});

const ARM = { need: 600, timeToStretch: 1500, stretchBegun: false }; // 25 minutes: armed, not on
const NEAR = { need: 600, timeToStretch: 900, stretchBegun: false }; // 15 minutes

// A <video> whose playhead and buffered ranges the test sets, over a fake hls.js on level `fetched` (480p).
function world({ auto = true, fetched = 2, ladder = rungs } = {}) {
  const video = document.createElement('video');
  const state = { time: 0, ranges: [] };
  Object.defineProperty(video, 'currentTime', { get: () => state.time });
  Object.defineProperty(video, 'paused', { get: () => false });
  Object.defineProperty(video, 'buffered', {
    get: () => ({
      length: state.ranges.length,
      start: (i) => state.ranges[i][0],
      end: (i) => state.ranges[i][1],
    }),
  });
  const names = [
    'onLevel',
    'onThroughput',
    'onReconnecting',
    'onSessionLost',
    'onStopped',
    'onFatal',
  ];
  const handlers = Object.fromEntries([...names, 'onNetworkError'].map((n) => [n, vi.fn()]));
  const server = makeServer();
  const engine = startHlsJs({
    Hls: WiringHls,
    video,
    session: { masterUrl: '/m.m3u8', renditions: ladder },
    auto,
    startSeconds: 0,
    handlers,
    fetch: server.fetch,
    clock: () => Date.now(),
  });
  engines.push(engine);
  const hls = FakeHls.last;
  const levelAccessor = Object.getOwnPropertyDescriptor(FakeHls.prototype, 'loadLevel');
  Object.defineProperty(hls, 'loadLevel', {
    get: () => levelAccessor.get.call(hls),
    set: (v) => {
      InnerLoader.order.push(`loadLevel:${v}`);
      levelAccessor.set.call(hls, v);
    },
  });
  hls.manifest(levelsOf(ladder), fetched);
  hls.fetchLevel(fetched);
  InnerLoader.order.length = 0;
  return {
    engine,
    video,
    hls,
    handlers,
    server,
    state,
    setTime: (s) => {
      state.time = s;
      video.dispatchEvent(new Event('timeupdate'));
    },
    seekTo: (s) => {
      state.time = s;
      video.dispatchEvent(new Event('seeking'));
    },
    store: () => made.stores[0],
    front: () => made.fronts.at(-1),
    requests: () => made.requests.at(-1),
    levelWrites: () => InnerLoader.order.filter((o) => o.startsWith('loadLevel:')),
  };
}

const fragOf = (sn, level) => ({
  sn,
  level,
  start: sn * 4,
  duration: 4,
  end: sn * 4 + 4,
  type: 'main',
});

function request(env, sn, level = 2) {
  const frag = fragOf(sn, level);
  env.hls.fragLoading(frag);
  const loader = new env.hls.config.fLoader(env.hls.config);
  frag.stats = loader.stats;
  const callbacks = { onSuccess: vi.fn(), onError: vi.fn(), onTimeout: vi.fn(), onAbort: vi.fn() };
  const context = {
    url: segUrl(rungs[level].id, sn),
    type: 'media-fragment',
    frag,
    responseType: 'arraybuffer',
    resetIV: false,
  };
  loader.load(context, {}, callbacks);
  return { frag, loader, callbacks };
}

function land(env, { frag }) {
  env.hls.emit(FakeHls.Events.FRAG_LOADED, {
    frag,
    part: null,
    payload: new ArrayBuffer(0),
    networkDetails: null,
  });
  env.state.ranges = [[0, frag.end]];
  env.hls.fragBuffered(frag);
}

function loadThroughInner(env, count, level = 2) {
  for (let sn = 0; sn < count; sn += 1) {
    const handled = request(env, sn, level);
    InnerLoader.last.succeed();
    land(env, handled);
  }
}

async function answerPlaylists(env, count = 3) {
  for (let i = 0; i < count; i += 1) {
    await answerPlaylist(env.server.calls.at(-1));
    await flush();
  }
}

async function armed(env, { held = 10, input = ARM, time = 5 } = {}) {
  env.engine.bankInput(input);
  await flush();
  await answerPlaylists(env);
  loadThroughInner(env, held);
  env.setTime(time);
}

async function turnedOn(env, options = {}) {
  await armed(env, options);
  env.engine.bankInput(NEAR);
  await flush();
}

const hold = (env, seq, size = 10, timings = { start: 0, first: 1, end: 2 }) =>
  env.store().putSegment({
    seq,
    rung: '480p',
    start: seq * 4,
    end: seq * 4 + 4,
    bytes: new ArrayBuffer(size),
    timings,
  });

const segmentCalls = (env) => env.server.calls.slice(3).map((c) => c.url);

describe('the bank is never armed', () => {
  it('leaves hls.js and the network alone with no stretch near, a far one, or none again', async () => {
    const env = world();
    env.engine.bankInput(null);
    env.engine.bankInput({ need: 600, timeToStretch: 4000, stretchBegun: false });
    env.setTime(5);
    const handled = request(env, 0);
    InnerLoader.last.succeed();
    land(env, handled);
    await flush();
    expect(env.server.calls).toHaveLength(0);
    expect(InnerLoader.loads).toHaveLength(1);
    expect(env.levelWrites()).toEqual([]); // no manual level
    expect(handled.frag.stats.bankAnswered).toBeUndefined();
    expect(env.handlers.onThroughput).toHaveBeenCalledTimes(1); // today's sample, untouched
    new env.hls.config.pLoader(env.hls.config).load(
      { type: 'level', level: 2, url: playlistUrl('480p') },
      {},
      { onSuccess: vi.fn() },
    );
    expect(InnerLoader.loads).toHaveLength(2);
    expect(made.fronts).toHaveLength(0);
  });

  it('does nothing with the rung pinned, whatever the look-ahead says', async () => {
    const env = world({ auto: false });
    env.engine.bankInput(NEAR);
    env.setTime(5);
    await flush();
    expect(env.server.calls).toHaveLength(0);
    expect(made.fronts).toHaveLength(0);
    expect(made.stores).toHaveLength(0);
    expect(env.hls.config.pLoader).toBeUndefined(); // today's loaders alone
  });
});

describe('arming', () => {
  it('fetches the playlist of every rung up to 480p, one at a time, with a stretch within 30 minutes', async () => {
    const env = world();
    env.engine.bankInput(ARM);
    await flush();
    expect(env.server.calls.map((c) => c.url)).toEqual([playlistUrl('240p')]);
    await answerPlaylists(env);
    expect(env.server.calls.map((c) => c.url)).toEqual(
      ['240p', '360p', '480p'].map(playlistUrl), // none for 720p
    );
  });

  it('keeps copies of what hls.js loads, and drops them when the look-ahead lapses before the bank is on', async () => {
    const env = world();
    await armed(env);
    expect(env.store().hasSegment(9)).toBe(true);
    env.engine.bankInput({ ...ARM, timeToStretch: 3999 }); // far, not only null, closes it
    expect(env.front().isPaused()).toBe(false);
    env.engine.bankInput(NEAR); // armed again, with the playlists kept: nothing to fetch, nothing covered
    await flush();
    expect(env.server.calls).toHaveLength(3);
    expect(made.fronts).toHaveLength(2);
    expect(env.levelWrites()).toEqual([]);
  });
});

describe('turning on', () => {
  it('turns on with every condition met: the front fetches the first film not held at hls.js’s rung', async () => {
    const env = world();
    await turnedOn(env);
    expect(segmentCalls(env)).toEqual([segUrl('480p', 10)]);
    expect(env.levelWrites()).toEqual(['loadLevel:2']);
  });

  it('starts the front at hls.js’s rung', async () => {
    const low = world({ fetched: 0 });
    await turnedOn(low);
    expect(segmentCalls(low)).toEqual([segUrl('240p', 10)]);
    expect(low.levelWrites()).toEqual(['loadLevel:0']);
  });

  it('starts at 480p when hls.js is above it', async () => {
    const env = world();
    await armed(env);
    env.hls.fetchLevel(3); // above the bank's rungs
    env.engine.bankInput(NEAR);
    await flush();
    expect(segmentCalls(env)).toEqual([segUrl('480p', 10)]);
  });

  it('keeps nothing of a fragment above 480p, so the bank never covers it', async () => {
    const env = world({ fetched: 3 });
    await armed(env, { held: 0 });
    loadThroughInner(env, 10, 3);
    env.engine.bankInput(NEAR);
    await flush();
    expect(env.store().hasSegment(3)).toBe(false);
  });

  it('does not turn on before the bank covers the buffer', async () => {
    const env = world();
    const before = request(env, 0); // loaded before arming: never copied
    InnerLoader.last.succeed();
    land(env, before);
    env.engine.bankInput(ARM);
    await flush();
    await answerPlaylists(env);
    env.setTime(5);
    env.engine.bankInput(NEAR);
    await flush();
    expect(env.levelWrites()).toEqual([]);
    expect(env.server.calls).toHaveLength(3);
  });

  it('does not turn on while a playlist is missing', async () => {
    const env = world();
    env.engine.bankInput(ARM);
    await flush();
    await answerPlaylists(env, 2); // the 480p playlist is asked for and not answered
    loadThroughInner(env, 10);
    env.setTime(5);
    env.engine.bankInput(NEAR);
    await flush();
    expect(env.levelWrites()).toEqual([]);
    expect(env.server.calls).toHaveLength(3);
  });

  it('does not turn on while a fragment loads through the inner loader, and does once it lands', async () => {
    const env = world();
    await armed(env, { held: 9 });
    const loading = request(env, 9);
    env.engine.bankInput(NEAR);
    env.setTime(6);
    expect(env.levelWrites()).toEqual([]);
    InnerLoader.last.succeed();
    env.hls.emit(FakeHls.Events.FRAG_LOADED, { frag: loading.frag });
    expect(env.levelWrites()).toEqual([]); // loaded, not yet appended
    env.state.ranges = [[0, 40]];
    env.hls.fragBuffered(loading.frag);
    expect(env.levelWrites()).toEqual(['loadLevel:2']);
    await flush();
    expect(segmentCalls(env)).toEqual([segUrl('480p', 10)]);
  });

  it('does not turn on once film ahead reaches F, and does while it is below', async () => {
    const env = world();
    await armed(env);
    env.engine.bankInput({ ...NEAR, need: 35 }); // 35 s ahead of the playhead: at F
    await flush();
    expect(env.levelWrites()).toEqual([]);
    env.engine.bankInput({ ...NEAR, need: 30 });
    expect(env.levelWrites()).toEqual([]);
    env.engine.bankInput({ ...NEAR, need: Number.NaN }); // no need at all
    expect(env.levelWrites()).toEqual([]);
    env.engine.bankInput({ ...NEAR, need: 36 });
    expect(env.levelWrites()).toEqual(['loadLevel:2']);
  });

  it('turns on only within 20 minutes of the stretch', async () => {
    const env = world();
    await armed(env);
    env.engine.bankInput({ ...NEAR, timeToStretch: 1201 });
    expect(env.levelWrites()).toEqual([]);
    env.engine.bankInput({ ...NEAR, timeToStretch: 1200 });
    expect(env.levelWrites()).toEqual(['loadLevel:2']);
  });
});

describe('answering hls.js', () => {
  it('answers held film from the bank with no request to the inner loader or the server', async () => {
    const env = world();
    await turnedOn(env);
    const loads = InnerLoader.loads.length;
    const calls = env.server.calls.length;
    const asked = request(env, 3);
    await flush();
    expect(asked.callbacks.onSuccess).toHaveBeenCalledTimes(1);
    expect(asked.frag.stats.bankAnswered).toBe(true);
    expect(InnerLoader.loads).toHaveLength(loads);
    expect(env.server.calls).toHaveLength(calls);
    const playlist = { onSuccess: vi.fn() };
    new env.hls.config.pLoader(env.hls.config).load(
      { type: 'level', level: 2, url: playlistUrl('480p') },
      {},
      playlist,
    );
    await flush();
    expect(playlist.onSuccess.mock.calls[0][0].data).toBe(playlistText());
  });

  it('puts hls.js on the held rung and fails a fragment held at another rung as bank-rung', async () => {
    const env = world();
    await armed(env, { held: 0 });
    loadThroughInner(env, 10, 1); // held at 360p
    env.engine.bankInput(NEAR);
    await flush();
    InnerLoader.order.length = 0;
    const asked = request(env, 3, 2); // asked at 480p
    await flush();
    expect(InnerLoader.order).toEqual(['loadLevel:1']);
    expect(asked.callbacks.onError.mock.calls[0][0]).toEqual({ code: 503, text: 'bank-rung' });
  });

  it('takes no sample from a fragment the bank answered, and reports the front’s fetches instead', async () => {
    const env = world();
    await turnedOn(env);
    expect(env.handlers.onThroughput).toHaveBeenCalledTimes(10); // the ten inner loads
    env.handlers.onThroughput.mockClear();
    const asked = request(env, 3);
    await flush();
    env.hls.emit(FakeHls.Events.FRAG_LOADED, { frag: asked.frag });
    expect(env.handlers.onThroughput).not.toHaveBeenCalled();
    const front = env.server.calls.at(-1);
    await answerSegment(front, 40_000, 1000);
    expect(env.handlers.onThroughput).toHaveBeenCalledWith(expect.any(Number));
  });
});

describe('the front’s answers from the server', () => {
  it.each([
    [410, 'onStopped', 'onSessionLost'],
    [404, 'onSessionLost', 'onStopped'],
  ])('reaches the handler for a %i, once', async (code, handler, other) => {
    const env = world();
    await turnedOn(env);
    env.server.calls.at(-1).status(code);
    await flush();
    env.hls.error({ type: 'networkError', details: 'fragLoadError', response: { code } });
    expect(env.handlers[handler]).toHaveBeenCalledTimes(1);
    expect(env.handlers[other]).not.toHaveBeenCalled();
  });
});

describe('pausing', () => {
  const fatal = (env) =>
    env.hls.error({ fatal: true, type: 'networkError', details: 'fragLoadError' });
  async function idleFront(env) {
    await armed(env);
    env.engine.bankInput({ ...NEAR, need: 38 });
    await flush();
    await answerSegment(env.server.calls.at(-1));
    expect(segmentCalls(env)).toHaveLength(1); // at F: nothing more
  }
  const wantMore = async (env) => {
    env.engine.bankInput(NEAR);
    await flush();
  };

  it('pauses on a fatal network error and resumes with exactly one request RECONNECT_MS after the first', async () => {
    const env = world();
    await idleFront(env);
    fatal(env);
    expect(env.front().isPaused()).toBe(true);
    await wantMore(env);
    await vi.advanceTimersByTimeAsync(RECONNECT_MS / 2);
    fatal(env); // a second one while paused keeps the first clock
    await vi.advanceTimersByTimeAsync(RECONNECT_MS / 2 - 1);
    expect(segmentCalls(env)).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(segmentCalls(env)).toHaveLength(2);
    expect(env.front().isPaused()).toBe(false);
  });

  it('resumes at once on a successful server answer to a request elsewhere, not on held film', async () => {
    const env = world();
    await idleFront(env);
    fatal(env);
    await wantMore(env);
    const held = request(env, 3);
    await flush();
    env.hls.emit(FakeHls.Events.FRAG_LOADED, { frag: held.frag });
    expect(env.front().isPaused()).toBe(true);
    expect(segmentCalls(env)).toHaveLength(1);
    env.hls.emit(FakeHls.Events.FRAG_LOADED, {
      frag: { sn: 'initSegment', stats: { loaded: 10, loading: { start: 0, first: 1, end: 2 } } },
    });
    await flush();
    expect(env.front().isPaused()).toBe(false);
    expect(segmentCalls(env)).toHaveLength(2);
  });

  it('leaves the front running on a media error', async () => {
    const env = world();
    await turnedOn(env);
    vi.spyOn(env.front(), 'stop');
    vi.spyOn(env.front(), 'pause');
    env.hls.error({ fatal: true, type: 'mediaError', details: 'bufferAppendError' });
    expect(env.front().stop).not.toHaveBeenCalled();
    expect(env.front().pause).not.toHaveBeenCalled();
    expect(env.server.calls.at(-1).aborted).toBe(false);
  });
});

describe('choosing the rung', () => {
  const measured = (measuredWallS, over = {}) => ({
    rung: '480p',
    rate: 1328,
    w: 0,
    d: 1.25,
    measuredS: measuredWallS,
    measuredWallS,
    ...over,
  });
  const tick = (env) => env.engine.bankInput(NEAR);

  it('chooses first after 20 s of fetching, then once a minute, and hands the answer to the front', async () => {
    const env = world();
    await turnedOn(env);
    const front = env.front();
    vi.spyOn(env.requests(), 'setRung');
    const window = vi.spyOn(front, 'window').mockReturnValue(measured(19.9));
    tick(env);
    expect(chooseBankRung).not.toHaveBeenCalled();
    window.mockReturnValue(measured(20));
    tick(env);
    expect(chooseBankRung).toHaveBeenCalledTimes(1);
    expect(chooseBankRung.mock.calls[0][0]).toMatchObject({
      filmAhead: 35,
      timeToStretch: 900,
      need: 600,
      d: 1.25,
      rate: 1328,
    });
    expect(env.requests().setRung).toHaveBeenCalledWith('240p'); // only 240p’s projection reaches F
    await vi.advanceTimersByTimeAsync(59_000);
    tick(env);
    expect(chooseBankRung).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1000);
    tick(env);
    expect(chooseBankRung).toHaveBeenCalledTimes(2);
  });

  it('never chooses inside a stretch, and chooses again at once for the next one', async () => {
    const env = world();
    await turnedOn(env);
    vi.spyOn(env.front(), 'window').mockReturnValue(measured(40));
    tick(env);
    expect(chooseBankRung).toHaveBeenCalledTimes(1);
    env.engine.bankInput({ ...NEAR, timeToStretch: 0, stretchBegun: true });
    expect(chooseBankRung).toHaveBeenCalledTimes(1);
    env.engine.bankInput({ ...NEAR, timeToStretch: 700 }); // the next stretch
    expect(chooseBankRung).toHaveBeenCalledTimes(2);
  });

  it('never chooses at F or at the limit', async () => {
    const env = world();
    await turnedOn(env);
    vi.spyOn(env.front(), 'window').mockReturnValue(measured(40));
    env.engine.bankInput({ ...NEAR, need: 35 });
    expect(chooseBankRung).not.toHaveBeenCalled();
    vi.spyOn(env.store(), 'atLimit').mockReturnValue(true);
    tick(env);
    expect(chooseBankRung).not.toHaveBeenCalled();
  });
});

const AFTER = { need: 600, timeToStretch: 3000, stretchBegun: false };

describe('turning off', () => {
  it('finishes the front, still answers held film, and releases on the first request beyond it', async () => {
    const env = world();
    await turnedOn(env);
    const front = env.front();
    vi.spyOn(front, 'finish');
    vi.spyOn(front, 'stop');
    const calls = env.server.calls.length;
    env.engine.bankInput(AFTER);
    expect(front.finish).toHaveBeenCalledTimes(1);
    expect(front.stop).not.toHaveBeenCalled();
    expect(env.server.calls.at(-1).aborted).toBe(false); // the fetch in flight is not cut
    const held = request(env, 3); // held film is still answered, with no release
    await flush();
    expect(held.callbacks.onSuccess).toHaveBeenCalledTimes(1);
    expect(env.levelWrites()).toEqual(['loadLevel:2']);
    InnerLoader.order.length = 0;
    const loads = InnerLoader.loads.length;
    const beyond = request(env, 30);
    expect(InnerLoader.loads).toHaveLength(loads + 1);
    expect(InnerLoader.order).toContain('loadLevel:-1');
    expect(InnerLoader.order.indexOf('loadLevel:-1')).toBeLessThan(
      InnerLoader.order.indexOf('inner.load'),
    ); // the release is written before the inner loader's load
    expect(env.server.calls).toHaveLength(calls); // none by the front
    expect(beyond.callbacks.onSuccess).not.toHaveBeenCalled();
    expect(env.store().heldBytes()).toBe(0); // cleared
  });

  it('stays on inside a stretch whatever its time says, and turns off when the look-ahead lapses', async () => {
    const env = world();
    await turnedOn(env);
    vi.spyOn(env.front(), 'finish');
    env.engine.bankInput({ ...AFTER, stretchBegun: true });
    expect(env.front().finish).not.toHaveBeenCalled();
    env.engine.bankInput(null);
    expect(env.front().finish).toHaveBeenCalledTimes(1);
  });

  it('releases on the first request when a seek back goes to before held film', async () => {
    const env = world();
    await turnedOn(env, { held: 20, time: 60 });
    env.engine.bankInput(AFTER);
    env.seekTo(10);
    expect(env.levelWrites()).toEqual(['loadLevel:2', 'loadLevel:2']); // the seek held the level; no release yet
    const loads = InnerLoader.loads.length;
    const calls = env.server.calls.length;
    request(env, 2);
    expect(InnerLoader.loads).toHaveLength(loads + 1);
    expect(InnerLoader.order.at(-2)).toBe('loadLevel:-1');
    expect(env.server.calls).toHaveLength(calls);
  });

  it('answers a seek inside held film from the bank, with no release and no server request', async () => {
    const env = world();
    await turnedOn(env, { held: 20, time: 60 });
    env.engine.bankInput(AFTER);
    const loads = InnerLoader.loads.length;
    const calls = env.server.calls.length;
    env.seekTo(40);
    const inside = request(env, 11);
    await flush();
    expect(inside.callbacks.onSuccess).toHaveBeenCalledTimes(1);
    expect(InnerLoader.loads).toHaveLength(loads);
    expect(env.server.calls).toHaveLength(calls);
    expect(InnerLoader.order).not.toContain('loadLevel:-1');
  });
});

describe('seeks', () => {
  it('releases at once on a seek forward beyond what is held, clears, and arms again with a stretch near', async () => {
    const env = world();
    await turnedOn(env);
    const first = env.front();
    vi.spyOn(first, 'finish');
    vi.spyOn(env.requests(), 'endGap');
    const endGap = env.requests().endGap;
    env.seekTo(500);
    expect(endGap).toHaveBeenCalledTimes(1);
    expect(first.finish).toHaveBeenCalledTimes(1);
    expect(InnerLoader.order).toContain('loadLevel:-1');
    expect(env.store().heldBytes()).toBe(0);
    expect(made.fronts).toHaveLength(2); // armed again for the stretch still near
    expect(env.server.calls).toHaveLength(4); // no new request: the playlists are held
  });

  it('clears the copies on a seek beyond them while only armed, and ignores a seek with no episode', async () => {
    const env = world();
    env.seekTo(500); // no episode yet
    await armed(env);
    env.seekTo(500);
    expect(env.store().heldBytes()).toBe(0);
    expect(made.fronts).toHaveLength(1); // still armed, not armed again
  });
});

describe('a request hls.js waits on the front’s fetch for', () => {
  const unhurt = (env, waiter) => {
    expect(waiter.callbacks.onTimeout).not.toHaveBeenCalled();
    expect(env.handlers.onNetworkError).not.toHaveBeenCalled();
    expect(env.hls.levels.map((l) => l.fragmentError)).toEqual([0, 0, 0, 0]);
    expect(InnerLoader.loads).toHaveLength(10); // no second request through the inner loader
  };

  it('is answered, not cut, when the bank turns off, and the front is finished after it', async () => {
    const env = world();
    await turnedOn(env);
    const waiter = request(env, 10);
    await flush();
    env.engine.bankInput(AFTER);
    await flush();
    expect(env.front().isFinished()).toBe(false);
    expect(env.server.calls.at(-1).aborted).toBe(false);
    await answerSegment(env.server.calls.at(-1), 700);
    expect(waiter.callbacks.onSuccess).toHaveBeenCalledTimes(1);
    land(env, waiter);
    expect(env.front().isFinished()).toBe(true);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(segmentCalls(env)).toEqual([segUrl('480p', 10)]); // one request, and nothing new
    unhurt(env, waiter);
  });

  it('ends without a timeout when hls.js abandons it on a seek past held film', async () => {
    const env = world();
    await turnedOn(env);
    const waiter = request(env, 10);
    await flush();
    env.seekTo(500);
    waiter.loader.abort(); // hls.js aborts the fragment it is loading right after, in the same event
    await vi.advanceTimersByTimeAsync(10);
    expect(waiter.callbacks.onAbort).toHaveBeenCalledTimes(1);
    expect(env.server.calls.at(-1).aborted).toBe(false); // the front's fetch is finished, not cut
    await answerSegment(env.server.calls.at(-1));
    expect(waiter.callbacks.onSuccess).not.toHaveBeenCalled();
    expect(segmentCalls(env)).toEqual([segUrl('480p', 10)]);
    unhurt(env, waiter);
  });

  it('ends without a timeout when the engine is destroyed and hls.js destroys the loader', async () => {
    const env = world();
    await turnedOn(env);
    const waiter = request(env, 10);
    await flush();
    env.hls.destroy.mockImplementation(() => waiter.loader.destroy()); // hls.destroy() destroys its loaders
    env.engine.destroy();
    await vi.advanceTimersByTimeAsync(10);
    expect(env.server.calls.at(-1).aborted).toBe(true);
    expect(segmentCalls(env)).toEqual([segUrl('480p', 10)]);
    unhurt(env, waiter);
  });
});

describe('a seek inside held film while hls.js waits on the bank', () => {
  it('lets the waiting request be answered, then moves the front past the held run', async () => {
    const env = world();
    await turnedOn(env, { held: 20, time: 60 });
    const gap = request(env, 3); // a gap: its fetch at the front, which hls.js waits on
    await flush();
    const gapCall = env.server.calls.at(-1);
    vi.spyOn(env.requests(), 'endGap');
    env.seekTo(50); // forward, inside held film
    await flush();
    expect(gapCall.aborted).toBe(false); // the fetch hls.js waits on is not cut
    expect(env.requests().endGap).not.toHaveBeenCalled();
    await answerSegment(gapCall);
    expect(gap.callbacks.onSuccess).toHaveBeenCalledTimes(1);
    const before = env.server.calls.length;
    await vi.advanceTimersByTimeAsync(1000); // the engine's tick settles the seek
    expect(env.requests().endGap).toHaveBeenCalledTimes(1);
    expect(env.front().position().seq).toBeGreaterThanOrEqual(20);
    expect(env.server.calls.slice(before).map((c) => c.url)).toEqual([segUrl('480p', 20)]);
  });

  it.each([
    ['abandoned', (gap) => gap.loader.abort()], // hls.js abandons it on the seek
    ['timed out', () => vi.advanceTimersByTimeAsync(10_000)], // hls.js's first-byte limit
  ])('settles at once when the waiting request is %s', async (_how, end) => {
    const env = world();
    await turnedOn(env, { held: 20, time: 60 });
    const gap = request(env, 3);
    await flush();
    env.seekTo(50);
    await end(gap);
    await vi.advanceTimersByTimeAsync(1000);
    expect(env.front().position().seq).toBeGreaterThanOrEqual(20);
  });
});

describe('the front never fetches film the store holds', () => {
  const asked = (env) => segmentCalls(env).map((url) => Number(/seg-(\d+)\.ts/.exec(url)[1]));

  it('does not walk through held film after a gap is filled and the playhead seeks inside it', async () => {
    const env = world();
    await turnedOn(env, { held: 20, time: 60 }); // 7 to 19 held, the front at 20
    for (const sn of [3, 2]) {
      const gap = request(env, sn); // the gap behind held film, filled through the front
      await flush();
      await answerSegment(env.server.calls.at(-1));
      expect(gap.callbacks.onSuccess).toHaveBeenCalledTimes(1);
    }
    const answered = new Set(env.server.calls);
    env.seekTo(10); // inside the film just filled: the front goes on from 4, through the gap, then must skip 7 to 19
    for (let i = 0; i < 12; i += 1) {
      await vi.advanceTimersByTimeAsync(1000);
      const call = env.server.calls.at(-1);
      if (answered.has(call)) break;
      answered.add(call);
      await answerSegment(call);
    }
    expect(asked(env)).toContain(6);
    for (const seq of asked(env)) expect(seq >= 7 && seq <= 19).toBe(false);
  });

  it('turns on past armed copies that lie beyond a gap', async () => {
    const env = world();
    await armed(env, { held: 10 });
    for (let seq = 12; seq < 15; seq += 1) hold(env, seq);
    env.engine.bankInput(NEAR);
    await flush();
    await answerSegment(env.server.calls.at(-1)); // 10
    await answerSegment(env.server.calls.at(-1)); // 11
    expect(asked(env)).toEqual([10, 11, 15]); // 12 to 14 are held
  });
});

describe('a closed episode’s finishing front', () => {
  it('answers its waiter but writes nothing into the store cleared by the release', async () => {
    const env = world();
    await turnedOn(env);
    const waiter = request(env, 10);
    await flush();
    env.seekTo(500); // beyond held film: released and cleared while the fetch of 10 is in flight
    await answerSegment(env.server.calls.at(-1), 700);
    expect(waiter.callbacks.onSuccess.mock.calls[0][0].data.byteLength).toBe(700);
    expect(env.store().hasSegment(10)).toBe(false);
    expect(env.store().heldBytes()).toBe(0);
  });

  it('is stopped by a destroy: no request, no timer left', async () => {
    const env = world();
    await turnedOn(env);
    env.seekTo(500);
    const inFlight = env.server.calls.at(-1);
    env.engine.destroy();
    expect(inFlight.aborted).toBe(true);
    expect(vi.getTimerCount()).toBe(0); // its first-byte limit, retry and pause timers are gone
    const calls = env.server.calls.length;
    await vi.advanceTimersByTimeAsync(60_000);
    expect(env.server.calls).toHaveLength(calls);
  });
});

describe('the stretch windows and the tick’s life', () => {
  it('arms at exactly 30 minutes and not beyond', async () => {
    const env = world();
    env.engine.bankInput({ ...ARM, timeToStretch: 1801 });
    await flush();
    expect(env.server.calls).toHaveLength(0);
    env.engine.bankInput({ ...ARM, timeToStretch: 1800 });
    await flush();
    expect(env.server.calls).toHaveLength(1);
  });

  it('stays on at exactly 20 minutes, turns off just beyond, and stays off between 20 and 30', async () => {
    const env = world();
    await turnedOn(env);
    vi.spyOn(env.front(), 'finish');
    env.engine.bankInput({ ...NEAR, timeToStretch: 1200 });
    expect(env.front().finish).not.toHaveBeenCalled();
    env.engine.bankInput({ ...NEAR, timeToStretch: 1201 });
    expect(env.front().finish).toHaveBeenCalledTimes(1);
    env.engine.bankInput({ ...NEAR, timeToStretch: 1500 });
    expect(env.front().finish).toHaveBeenCalledTimes(1); // not on again
    expect(env.requests().armed()).toBe(false);
  });

  it('runs one tick per episode: closing clears it, and two episodes in a row never run two', async () => {
    const env = world();
    const base = vi.getTimerCount();
    for (let episode = 0; episode < 2; episode += 1) {
      env.engine.bankInput(ARM);
      await flush();
      if (episode === 0) await answerPlaylists(env);
      expect(vi.getTimerCount()).toBe(base + 1);
      env.engine.bankInput(null); // the look-ahead lapses: the armed episode closes
      expect(vi.getTimerCount()).toBe(base);
    }
  });
});

describe('the engine’s tick', () => {
  it('hands the front the film ahead and the need every second while the bank is on', async () => {
    const env = world();
    await turnedOn(env);
    vi.spyOn(env.front(), 'update');
    env.state.time = 20; // the film plays: no event of the video is sent
    await vi.advanceTimersByTimeAsync(1000);
    expect(env.front().update).toHaveBeenCalledTimes(1);
    expect(env.front().update).toHaveBeenLastCalledWith({ filmAhead: 20, need: 600 });
    env.state.time = 25;
    await vi.advanceTimersByTimeAsync(2000);
    expect(env.front().update).toHaveBeenCalledTimes(3);
    expect(env.front().update).toHaveBeenLastCalledWith({ filmAhead: 15, need: 600 });
    env.engine.destroy(); // the tick stops with the episode
    await vi.advanceTimersByTimeAsync(5000);
    expect(env.front().update).toHaveBeenCalledTimes(3);
  });
});

describe('what answering from the bank leaves out', () => {
  it('takes no sample from answered fragments, so the floor after a release is not raised by them', async () => {
    const env = world();
    await turnedOn(env); // ten slow loads through the inner loader: the only real samples
    for (let sn = 11; sn <= 19; sn += 1) {
      hold(env, sn, 1_000_000, { start: 0, first: 50, end: 250 }); // 32,000 kbit/s replayed
      const answered = request(env, sn);
      await flush();
      env.hls.emit(FakeHls.Events.FRAG_LOADED, { frag: answered.frag });
    }
    env.state.ranges = [[0, 400]]; // a deep buffer: the hold would engage on fast samples
    env.engine.bankInput(AFTER);
    request(env, 60); // the release
    expect(env.hls.config.minAutoBitrate).toBe(0);
    expect(env.hls.autoLevelCapping).toBe(2);
  });
});

describe('the level the release falls back to', () => {
  it('is the rung of the front’s last completed fetch', async () => {
    const env = world();
    await turnedOn(env);
    env.requests().setRung('360p');
    await answerSegment(env.server.calls.at(-1)); // segment 10, at 480p
    await flush();
    expect(segmentCalls(env).at(-1)).toBe(segUrl('360p', 11));
    await answerSegment(env.server.calls.at(-1)); // segment 11, at 360p
    env.engine.bankInput(AFTER);
    request(env, 60);
    expect(env.hls.autoLevelCapping).toBe(1);
  });

  it('is the rung the front will fetch at when it has completed none', async () => {
    const env = world({ fetched: 1 });
    await turnedOn(env);
    env.engine.bankInput(AFTER);
    request(env, 60);
    expect(env.hls.autoLevelCapping).toBe(1);
  });
});

describe('a ladder with no rung up to 480p', () => {
  it('never arms the bank', async () => {
    const ladder = [rungs[3], rung('1080p', 1080, 6_000_000)];
    const env = world({ ladder, fetched: 0 });
    env.engine.bankInput(ARM);
    await flush();
    expect(env.server.calls).toHaveLength(0);
    expect(made.fronts).toHaveLength(0);
  });
});

describe('destroy', () => {
  it('stops the front and drops what is held, whatever phase the bank is in', async () => {
    const on = world();
    await turnedOn(on);
    on.engine.destroy();
    expect(on.server.calls.at(-1).aborted).toBe(true);
    expect(on.store().heldBytes()).toBe(0);
    on.engine.bankInput(NEAR); // after destroy: nothing happens
  });
});
