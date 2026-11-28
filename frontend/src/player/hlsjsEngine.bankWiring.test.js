import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FakeHls } from '../test/fakeHls.js';
import { chooseBankRung } from './bankRung.js';
import { RECONNECT_MS, startHlsJs } from './hlsjsEngine.js';

// The real store, front, decider and loaders, wired by the engine, over a fake hls.js, a fake server and a fake
// inner loader. The modules below are wrapped only to capture what the engine builds, so a test can spy on it.
const made = vi.hoisted(() => ({ fronts: [], requests: [], stores: [] }));
vi.mock('./bankFront.js', async (original) => {
  const real = await original();
  return {
    ...real,
    createBankFront: (options) => {
      const front = real.createBankFront(options);
      made.fronts.push(front);
      return front;
    },
  };
});
vi.mock('./bankRequests.js', async (original) => {
  const real = await original();
  return {
    ...real,
    createBankRequests: (options) => {
      const requests = real.createBankRequests(options);
      made.requests.push(requests);
      return requests;
    },
  };
});
vi.mock('./segmentBank.js', async (original) => {
  const real = await original();
  return {
    ...real,
    createSegmentBank: (options) => {
      const store = real.createSegmentBank(options);
      made.stores.push(store);
      return store;
    },
  };
});
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
const levelsOf = () =>
  rungs.map(({ id, height, bitrate }) => ({ height, bitrate, uri: playlistUrl(id) }));
const playlistText = () =>
  `#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:0\n${Array.from(
    { length: 200 },
    (_, i) => `#EXTINF:4.000,\nseg-${i}.ts`,
  ).join('\n')}\n#EXT-X-ENDLIST\n`;

// hls.js's default loader, as far as the bank's loaders touch it: `stats` at construction, one `load`, recorded.
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
      retry: 0,
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

// A fake fetch: each call stays open until the test answers it.
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
      // The body is one chunk; the stream ends when the test calls `end` (time passes on the fake clock before).
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
function world({ auto = true, fetched = 2 } = {}) {
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
  const handlers = {
    onLevel: vi.fn(),
    onThroughput: vi.fn(),
    onReconnecting: vi.fn(),
    onSessionLost: vi.fn(),
    onStopped: vi.fn(),
    onFatal: vi.fn(),
    onNetworkError: vi.fn(),
  };
  const server = makeServer();
  const engine = startHlsJs({
    Hls: WiringHls,
    video,
    session: { masterUrl: '/m.m3u8', renditions: rungs },
    auto,
    startSeconds: 0,
    handlers,
    fetch: server.fetch,
    clock: () => Date.now(),
  });
  engines.push(engine);
  const hls = FakeHls.last;
  // Every write of the manual level, in order with the inner loader's loads.
  const levelAccessor = Object.getOwnPropertyDescriptor(FakeHls.prototype, 'loadLevel');
  Object.defineProperty(hls, 'loadLevel', {
    get: () => levelAccessor.get.call(hls),
    set: (v) => {
      InnerLoader.order.push(`loadLevel:${v}`);
      levelAccessor.set.call(hls, v);
    },
  });
  hls.manifest(levelsOf(), fetched);
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

// hls.js asks the fragment loader for media fragment `sn` at `level`.
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

// The fragment is loaded, then appended: the buffered range now reaches its end.
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

// `count` fragments loaded through the inner loader, in film order, at `level`.
function loadThroughInner(env, count, level = 2) {
  for (let sn = 0; sn < count; sn += 1) {
    const handled = request(env, sn, level);
    InnerLoader.last.succeed();
    land(env, handled);
  }
}

async function armed(env, { held = 10, input = ARM } = {}) {
  env.engine.bankInput(input);
  await flush();
  for (let i = 0; i < 3; i += 1) {
    await answerPlaylist(env.server.calls.at(-1));
    await flush();
  }
  loadThroughInner(env, held);
  env.setTime(5);
}

// Armed, covered and turned on: the front's fetch of the first film not held is pending.
async function turnedOn(env, options = {}) {
  await armed(env, options);
  env.engine.bankInput(NEAR);
  await flush();
}

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
    expect(handled.callbacks.onSuccess).toHaveBeenCalledTimes(1);
    expect(handled.frag.stats.bankAnswered).toBeUndefined();
    expect(env.handlers.onThroughput).toHaveBeenCalledTimes(1); // today's sample, untouched
    expect(made.fronts).toHaveLength(0);
  });

  it('passes a playlist request to the inner loader', () => {
    const env = world();
    const loader = new env.hls.config.pLoader(env.hls.config);
    const callbacks = { onSuccess: vi.fn() };
    loader.load({ type: 'level', level: 2, url: playlistUrl('480p') }, {}, callbacks);
    expect(InnerLoader.loads).toHaveLength(1);
  });

  it('does nothing with the rung pinned, whatever the look-ahead says', async () => {
    const env = world({ auto: false });
    env.engine.bankInput(NEAR);
    env.setTime(5);
    await flush();
    expect(env.server.calls).toHaveLength(0);
    expect(made.fronts).toHaveLength(0);
    expect(made.stores).toHaveLength(0);
    expect(env.levelWrites()).toEqual([]);
  });
});

describe('arming', () => {
  it('fetches the playlist of every rung up to 480p, one at a time, with a stretch within 30 minutes', async () => {
    const env = world();
    env.engine.bankInput(ARM);
    await flush();
    expect(env.server.calls.map((c) => c.url)).toEqual([playlistUrl('240p')]);
    await answerPlaylist(env.server.calls.at(-1));
    await flush();
    await answerPlaylist(env.server.calls.at(-1));
    await flush();
    await answerPlaylist(env.server.calls.at(-1));
    await flush();
    expect(env.server.calls.map((c) => c.url)).toEqual(
      ['240p', '360p', '480p'].map(playlistUrl), // none for 720p
    );
    expect(env.levelWrites()).toEqual([]); // hls.js is still on Auto
  });

  it('is not armed by a stretch beyond 30 minutes', async () => {
    const env = world();
    env.engine.bankInput({ ...ARM, timeToStretch: 1801 });
    await flush();
    expect(env.server.calls).toHaveLength(0);
  });

  it('keeps copies of what hls.js loads, and drops them when the look-ahead lapses before the bank is on', async () => {
    const env = world();
    await armed(env);
    expect(env.store().hasSegment(9)).toBe(true);
    env.engine.bankInput(null);
    expect(env.store().hasSegment(9)).toBe(false);
    expect(env.front().isPaused()).toBe(false);
    env.engine.bankInput(NEAR); // armed again, with the playlists kept: nothing to fetch, nothing covered
    await flush();
    expect(env.server.calls).toHaveLength(3);
    expect(made.fronts).toHaveLength(2);
    expect(env.levelWrites()).toEqual([]);
  });

  it('is armed again by a stretch beyond 30 minutes coming back within it', async () => {
    const env = world();
    await armed(env);
    env.engine.bankInput({ ...ARM, timeToStretch: 2400 });
    expect(env.store().hasSegment(9)).toBe(false);
    env.engine.bankInput(ARM);
    expect(made.fronts).toHaveLength(2);
  });

  it('keeps nothing of a fragment above 480p, so the bank never covers it', async () => {
    const env = world({ fetched: 3 });
    env.engine.bankInput(ARM);
    await flush();
    for (let i = 0; i < 3; i += 1) {
      await answerPlaylist(env.server.calls.at(-1));
      await flush();
    }
    loadThroughInner(env, 10, 3);
    env.setTime(5);
    env.engine.bankInput(NEAR);
    await flush();
    expect(env.store().hasSegment(3)).toBe(false);
    expect(env.levelWrites()).toEqual([]);
    expect(env.server.calls).toHaveLength(3);
  });
});

describe('turning on', () => {
  it('turns on with every condition met: the front fetches the first film not held at hls.js’s rung', async () => {
    const env = world();
    await turnedOn(env);
    expect(segmentCalls(env)).toEqual([segUrl('480p', 10)]);
    expect(env.levelWrites()).toEqual(['loadLevel:2']);
    expect(env.requests().armed()).toBe(true);
  });

  it('starts the front at hls.js’s rung when it is on 480p or below, else at 480p', async () => {
    const low = world({ fetched: 0 });
    await turnedOn(low);
    expect(segmentCalls(low)).toEqual([segUrl('240p', 10)]);
    expect(low.levelWrites()).toEqual(['loadLevel:0']);
    FakeHls.reset();
    const high = world({ fetched: 2 });
    await armed(high);
    high.hls.fetchLevel(3); // hls.js has moved above the bank's rungs
    high.engine.bankInput(NEAR);
    await flush();
    expect(segmentCalls(high)).toEqual([segUrl('480p', 10)]);
  });

  it('does not turn on before the bank covers the buffer', async () => {
    const env = world();
    const before = request(env, 0); // loaded before arming: never copied
    InnerLoader.last.succeed();
    land(env, before);
    env.engine.bankInput(ARM);
    await flush();
    for (let i = 0; i < 3; i += 1) {
      await answerPlaylist(env.server.calls.at(-1));
      await flush();
    }
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
    await answerPlaylist(env.server.calls.at(-1));
    await flush();
    await answerPlaylist(env.server.calls.at(-1));
    await flush(); // the 480p playlist is asked for and not answered
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
    env.engine.bankInput({ ...NEAR, need: 36 });
    expect(env.levelWrites()).toEqual(['loadLevel:2']);
  });

  it('does not turn on without a need', async () => {
    const env = world();
    await armed(env);
    env.engine.bankInput({ ...NEAR, need: Number.NaN });
    expect(env.levelWrites()).toEqual([]);
  });

  it('turns on only within 20 minutes of the stretch', async () => {
    const env = world();
    await armed(env);
    env.engine.bankInput({ ...NEAR, timeToStretch: 1201 });
    expect(env.levelWrites()).toEqual([]);
    env.engine.bankInput({ ...NEAR, timeToStretch: 1200 });
    expect(env.levelWrites()).toEqual(['loadLevel:2']);
  });

  it('keeps 30 s of film behind the playhead', async () => {
    const env = world();
    await turnedOn(env);
    env.setTime(35); // film up to 5 s is more than 30 s behind
    expect(env.store().hasSegment(0)).toBe(false);
    expect(env.store().hasSegment(1)).toBe(true);
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
    expect(asked.callbacks.onSuccess.mock.calls[0][0].data.byteLength).toBe(1000);
    expect(asked.frag.stats.bankAnswered).toBe(true);
    expect(InnerLoader.loads).toHaveLength(loads);
    expect(env.server.calls).toHaveLength(calls);
  });

  it('answers a request at the front’s position from the front’s one server request', async () => {
    const env = world();
    await turnedOn(env);
    const asked = request(env, 10);
    await flush();
    expect(segmentCalls(env)).toEqual([segUrl('480p', 10)]);
    await answerSegment(env.server.calls.at(-1), 700);
    expect(asked.callbacks.onSuccess.mock.calls[0][0].data.byteLength).toBe(700);
    expect(InnerLoader.loads).toHaveLength(10);
  });

  it('puts hls.js on the held rung and fails a fragment held at another rung as bank-rung', async () => {
    const env = world();
    await armed(env, { held: 0 });
    loadThroughInner(env, 10, 1); // held at 360p
    env.setTime(5);
    env.engine.bankInput(NEAR);
    await flush();
    env.engine.bankInput(NEAR);
    InnerLoader.order.length = 0;
    const asked = request(env, 3, 2); // asked at 480p
    await flush();
    expect(InnerLoader.order).toEqual(['loadLevel:1']);
    expect(asked.callbacks.onError.mock.calls[0][0]).toEqual({ code: 503, text: 'bank-rung' });
    expect(asked.callbacks.onSuccess).not.toHaveBeenCalled();
    expect(InnerLoader.loads).toHaveLength(10);
  });

  it('answers a held media playlist', async () => {
    const env = world();
    await turnedOn(env);
    const loader = new env.hls.config.pLoader(env.hls.config);
    const callbacks = { onSuccess: vi.fn() };
    loader.load({ type: 'level', level: 2, url: playlistUrl('480p') }, {}, callbacks);
    await flush();
    expect(callbacks.onSuccess.mock.calls[0][0].data).toBe(playlistText());
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
    expect(env.handlers.onThroughput).toHaveBeenCalledTimes(1);
    expect(env.handlers.onThroughput).toHaveBeenCalledWith(expect.any(Number));
  });
});

describe('the front’s answers from the server', () => {
  it('reaches onStopped on a 410, once', async () => {
    const env = world();
    await turnedOn(env);
    env.server.calls.at(-1).status(410);
    await flush();
    expect(env.handlers.onStopped).toHaveBeenCalledTimes(1);
    expect(env.handlers.onSessionLost).not.toHaveBeenCalled();
  });

  it('reaches onSessionLost on a 404, once, and onStopped not at all', async () => {
    const env = world();
    await turnedOn(env);
    env.server.calls.at(-1).status(404);
    await flush();
    env.hls.error({
      fatal: false,
      type: 'networkError',
      details: 'fragLoadError',
      response: { code: 404 },
    });
    expect(env.handlers.onSessionLost).toHaveBeenCalledTimes(1);
    expect(env.handlers.onStopped).not.toHaveBeenCalled();
  });
});

describe('pausing', () => {
  const fatal = (env) =>
    env.hls.error({ fatal: true, type: 'networkError', details: 'fragLoadError' });
  // The front is at F with the first fetch done and idle; the film wanted then goes up.
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

  it('pauses on a fatal network error and resumes after RECONNECT_MS with exactly one request', async () => {
    const env = world();
    await idleFront(env);
    fatal(env);
    expect(env.front().isPaused()).toBe(true);
    await wantMore(env);
    await vi.advanceTimersByTimeAsync(RECONNECT_MS - 1);
    expect(segmentCalls(env)).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(segmentCalls(env)).toHaveLength(2);
    expect(env.front().isPaused()).toBe(false);
  });

  it('keeps the first clock when a second fatal error comes while paused', async () => {
    const env = world();
    await idleFront(env);
    fatal(env);
    await wantMore(env);
    await vi.advanceTimersByTimeAsync(RECONNECT_MS / 2);
    fatal(env);
    await vi.advanceTimersByTimeAsync(RECONNECT_MS / 2 - 1);
    expect(segmentCalls(env)).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(segmentCalls(env)).toHaveLength(2);
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
    // The init segment came from the server: a successful answer.
    env.hls.emit(FakeHls.Events.FRAG_LOADED, {
      frag: { sn: 'initSegment', stats: { loaded: 10, loading: { start: 0, first: 1, end: 2 } } },
    });
    await flush();
    expect(env.front().isPaused()).toBe(false);
    expect(segmentCalls(env)).toHaveLength(2);
  });

  it('is not paused by a fatal network error while the bank is not on', async () => {
    const env = world();
    await armed(env);
    fatal(env);
    expect(env.front().isPaused()).toBe(false);
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
    const held = request(env, 3); // still answered from the bank
    await flush();
    expect(held.callbacks.onSuccess).toHaveBeenCalledTimes(1);
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
    vi.spyOn(front, 'setRung');
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
      stretchBegun: false,
      atLimit: false,
      w: 0,
      d: 1.25,
      measuredWallS: 20,
      rate: 1328,
    });
    expect(chooseBankRung.mock.calls[0][0].candidates.map((c) => c.id)).toEqual([
      '240p',
      '360p',
      '480p',
    ]);
    expect(front.setRung).toHaveBeenCalledWith('240p'); // only 240p’s projection reaches F
    await vi.advanceTimersByTimeAsync(59_000);
    tick(env);
    expect(chooseBankRung).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1000);
    tick(env);
    expect(chooseBankRung).toHaveBeenCalledTimes(2);
  });

  it('keeps the front’s rung when no choice is made', async () => {
    const env = world();
    await turnedOn(env);
    vi.spyOn(env.front(), 'setRung');
    vi.spyOn(env.front(), 'window').mockReturnValue(measured(30, { w: 0, d: 0 }));
    tick(env);
    expect(chooseBankRung).toHaveBeenCalledTimes(1);
    expect(env.front().setRung).not.toHaveBeenCalled();
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

  it('tells the front the film ahead and the need through the decider', async () => {
    const env = world();
    await turnedOn(env);
    vi.spyOn(env.requests(), 'update');
    vi.spyOn(env.front(), 'update');
    env.setTime(6);
    expect(env.requests().update).toHaveBeenCalledWith({ filmAhead: 34, need: 600 });
    expect(env.front().update).toHaveBeenCalledWith({ filmAhead: 34, need: 600 });
  });
});

describe('turning off', () => {
  const AFTER = { need: 600, timeToStretch: 3000, stretchBegun: false };

  it('stops the front, still answers held film, and releases on the first request beyond it', async () => {
    const env = world();
    await turnedOn(env);
    const front = env.front();
    vi.spyOn(front, 'stop');
    const calls = env.server.calls.length;
    env.engine.bankInput(AFTER);
    expect(front.stop).toHaveBeenCalledTimes(1);
    expect(env.server.calls.at(-1).aborted).toBe(true);
    expect(env.levelWrites()).toEqual(['loadLevel:2']); // not released yet
    const held = request(env, 3);
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
    const later = request(env, 3); // nothing held any more: the inner loader
    expect(InnerLoader.loads).toHaveLength(loads + 2);
    expect(later.callbacks.onSuccess).not.toHaveBeenCalled();
  });

  it('turns off when the look-ahead lapses', async () => {
    const env = world();
    await turnedOn(env);
    vi.spyOn(env.front(), 'stop');
    env.engine.bankInput(null);
    expect(env.front().stop).toHaveBeenCalledTimes(1);
  });

  it('stays on inside a stretch, whatever its time says', async () => {
    const env = world();
    await turnedOn(env);
    vi.spyOn(env.front(), 'stop');
    env.engine.bankInput({ ...AFTER, stretchBegun: true });
    expect(env.front().stop).not.toHaveBeenCalled();
  });

  it('releases on the first request when a seek back goes to before held film', async () => {
    const env = world();
    await armed(env, { held: 20 });
    env.setTime(60); // film before 30 s is dropped: 28 s on is held
    env.engine.bankInput(NEAR);
    await flush();
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
    await armed(env, { held: 20 });
    env.setTime(60);
    env.engine.bankInput(NEAR);
    await flush();
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

  it('is armed again by the next stretch after the release', async () => {
    const env = world();
    await turnedOn(env);
    env.engine.bankInput(AFTER);
    request(env, 30); // the release
    env.engine.bankInput(ARM);
    expect(made.fronts).toHaveLength(2);
  });
});

describe('seeks', () => {
  it('releases at once on a seek forward beyond what is held, clears, and arms again with a stretch near', async () => {
    const env = world();
    await turnedOn(env);
    const first = env.front();
    vi.spyOn(first, 'stop');
    vi.spyOn(env.requests(), 'endGap');
    const endGap = env.requests().endGap;
    env.seekTo(500);
    expect(endGap).toHaveBeenCalledTimes(1);
    expect(first.stop).toHaveBeenCalledTimes(1);
    expect(InnerLoader.order).toContain('loadLevel:-1');
    expect(env.store().heldBytes()).toBe(0);
    expect(made.fronts).toHaveLength(2); // armed again for the stretch still near
    expect(made.requests.at(-1).armed()).toBe(true);
    expect(env.server.calls).toHaveLength(4); // no new request: the playlists are held
  });

  it('keeps the bank on and ends any gap on a seek inside held film', async () => {
    const env = world();
    await armed(env, { held: 20 });
    env.engine.bankInput(NEAR);
    await flush();
    vi.spyOn(env.requests(), 'endGap');
    vi.spyOn(env.front(), 'stop');
    env.seekTo(50);
    expect(env.requests().endGap).toHaveBeenCalledTimes(1);
    expect(env.front().stop).not.toHaveBeenCalled();
    expect(env.store().hasSegment(19)).toBe(true);
    env.seekTo(2); // back: what is held ahead is kept
    expect(env.store().hasSegment(19)).toBe(true);
    expect(env.requests().endGap).toHaveBeenCalledTimes(2);
  });

  it('clears the copies on a seek beyond them while only armed, and keeps the bank armed', async () => {
    const env = world();
    await armed(env);
    env.seekTo(500);
    expect(env.store().heldBytes()).toBe(0);
    expect(made.fronts).toHaveLength(1);
    env.seekTo(2); // nothing held: still beyond; nothing to clear
    expect(made.fronts).toHaveLength(1);
  });

  it('ignores a seek with no bank episode', () => {
    const env = world();
    env.seekTo(500);
    expect(made.stores[0].heldBytes()).toBe(0);
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
    FakeHls.reset();
    const idle = world();
    idle.engine.destroy();
    FakeHls.reset();
    const pinned = world({ auto: false });
    pinned.engine.destroy();
    expect(pinned.server.calls).toHaveLength(0);
  });
});
