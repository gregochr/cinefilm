import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createBankFront } from './bankFront.js';
import { RECONNECT_MS } from './reconnect.js';
import { createSegmentBank } from './segmentBank.js';

const RUNGS = [
  { id: 'r240', height: 240, kbps: 400, playlistUrl: '/api/s/1/r240/index.m3u8' },
  { id: 'r360', height: 360, kbps: 900, playlistUrl: '/api/s/1/r360/index.m3u8' },
  { id: 'r480', height: 480, kbps: 1800, playlistUrl: '/api/s/1/r480/index.m3u8' },
  { id: 'r720', height: 720, kbps: 4000, playlistUrl: '/api/s/1/r720/index.m3u8' },
];
const T0 = 1_000_000;

const segUrl = (rung, n) => new URL(`/api/s/1/${rung}/seg-${n}.ts`, globalThis.location.href).href;
const playlistText = (count = 40) =>
  `#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:0\n${Array.from(
    { length: count },
    (_, i) => `#EXTINF:4.000,\nseg-${i}.ts`,
  ).join('\n')}\n#EXT-X-ENDLIST\n`;

// A fake fetch: each call stays open until the test answers it, and counts how many are open at once.
function makeServer() {
  const server = { calls: [], open: 0, maxOpen: 0, ignoreAbort: false };
  server.fetch = (url, init) =>
    new Promise((resolve, reject) => {
      const call = { url, init, settled: false, aborted: false };
      const waiting = [];
      server.open += 1;
      server.maxOpen = Math.max(server.maxOpen, server.open);
      const settle = () => {
        if (!call.settled) server.open -= 1;
        call.settled = true;
      };
      init.signal.addEventListener('abort', () => {
        call.aborted = true;
        settle();
        if (server.ignoreAbort) return;
        reject(new DOMException('aborted', 'AbortError'));
        waiting.splice(0).forEach((w) => w.rej(new DOMException('aborted', 'AbortError')));
      });
      call.status = (status, retryAfter = null) => {
        settle();
        resolve({ status, headers: { get: (n) => (n === 'Retry-After' ? retryAfter : null) } });
      };
      call.fail = () => {
        settle();
        reject(new TypeError('Failed to fetch'));
      };
      call.respond = () => {
        const queue = [];
        let ended = false;
        const reader = {
          read() {
            if (queue.length > 0) return Promise.resolve(queue.shift());
            if (ended) return Promise.resolve({ done: true });
            return new Promise((res, rej) => waiting.push({ res, rej }));
          },
        };
        resolve({ status: 200, headers: { get: () => null }, body: { getReader: () => reader } });
        return {
          send(bytes) {
            const item = { done: false, value: bytes };
            const w = waiting.shift();
            if (w) w.res(item);
            else queue.push(item);
          },
          end() {
            ended = true;
            settle();
            waiting.splice(0).forEach((w) => w.res({ done: true }));
          },
        };
      };
      server.calls.push(call);
    });
  return server;
}

const flush = () => vi.advanceTimersByTimeAsync(0);

// Answers one call with a body of `size` bytes: first byte after `firstMs`, the end `bodyMs` later.
async function complete(call, { size = 1000, firstMs = 0, bodyMs = 0 } = {}) {
  await vi.advanceTimersByTimeAsync(firstMs);
  const r = call.respond();
  r.send(new Uint8Array(size).fill(7));
  await vi.advanceTimersByTimeAsync(bodyMs);
  r.end();
  await flush();
}

async function answerPlaylist(call, text = playlistText()) {
  const r = call.respond();
  r.send(new TextEncoder().encode(text));
  r.end();
  await flush();
}

function setup({ limitBytes, ...options } = {}) {
  const server = makeServer();
  const bank = createSegmentBank(limitBytes === undefined ? {} : { limitBytes });
  const seen = { kbps: [], stopped: 0, lost: 0 };
  const front = createBankFront({
    rungs: RUNGS,
    bank,
    fetch: server.fetch,
    onThroughput: (kbps) => seen.kbps.push(kbps),
    onStopped: () => {
      seen.stopped += 1;
    },
    onSessionLost: () => {
      seen.lost += 1;
    },
    ...options,
  });
  return { server, bank, front, seen };
}

// Armed, the three playlists up to 480p answered, started at `seq` and `rung`, wanting film (need 1000 s).
async function running({
  seq = 0,
  rung = 'r480',
  filmAhead = 0,
  need = 1000,
  count = 40,
  ...options
} = {}) {
  const t = setup(options);
  t.front.arm();
  for (let i = 0; i < 3; i += 1) {
    await flush();
    await answerPlaylist(t.server.calls.at(-1), playlistText(count));
  }
  t.front.update({ filmAhead, need });
  t.front.start({ seq, rung });
  await flush();
  return t;
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(T0);
});

afterEach(() => {
  vi.useRealTimers();
});

describe('the bank front: playlists and positions', () => {
  it('fetches the media playlist of every rung up to 480p when armed, one at a time, into the store', async () => {
    const { server, bank, front } = setup();
    front.arm();
    await flush();
    expect(server.calls.map((c) => c.url)).toEqual([RUNGS[0].playlistUrl]);
    expect(server.calls[0].init.credentials).toBe('same-origin');
    expect(server.calls[0].init.cache).toBe('no-store');
    await answerPlaylist(server.calls[0], 'text-240');
    await answerPlaylist(server.calls[1], 'text-360');
    await answerPlaylist(server.calls[2], 'text-480');
    expect(server.calls.map((c) => c.url)).toEqual(RUNGS.slice(0, 3).map((r) => r.playlistUrl));
    expect(bank.getPlaylist('r240')).toBe('text-240');
    expect(bank.getPlaylist('r480')).toBe('text-480');
    expect(bank.getPlaylist('r720')).toBeNull();
    expect(server.maxOpen).toBe(1);
  });

  it('retries a playlist after a network error and makes no segment request before it start', async () => {
    const { server, front } = setup();
    front.arm();
    await flush();
    server.calls[0].fail();
    await vi.advanceTimersByTimeAsync(1999);
    expect(server.calls).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(server.calls).toHaveLength(2);
    expect(server.calls[1].url).toBe(server.calls[0].url);
  });

  it('starts at the given position and rung and stores the segment with its film times and timings', async () => {
    const { server, bank } = await running({ seq: 5, rung: 'r360' });
    expect(server.calls.at(-1).url).toBe(segUrl('r360', 5));
    await complete(server.calls.at(-1), { size: 2000, firstMs: 300, bodyMs: 700 });
    const held = bank.getSegment(5);
    expect(held.rung).toBe('r360');
    expect(held.start).toBe(20);
    expect(held.end).toBe(24);
    expect(held.bytes.byteLength).toBe(2000);
    expect(held.timings).toEqual({ start: T0, first: T0 + 300, end: T0 + 1000 });
  });

  it('fetches the following positions in order, never more than one request at a time', async () => {
    const { server, bank } = await running({ seq: 2 });
    for (let i = 0; i < 4; i += 1) await complete(server.calls.at(-1), { bodyMs: 10 });
    expect(server.calls.slice(3).map((c) => c.url)).toEqual(
      [2, 3, 4, 5, 6].map((n) => segUrl('r480', n)),
    );
    expect([2, 3, 4, 5].every((n) => bank.hasSegment(n))).toBe(true);
    expect(server.maxOpen).toBe(1);
  });

  it('moves past a request beyond its position: the fetch in flight is aborted and the next is after it', async () => {
    const { server, bank, front } = await running({ seq: 5 });
    const stale = server.calls.at(-1);
    front.movePast(8);
    await flush();
    expect(stale.aborted).toBe(true);
    expect(server.calls.at(-1).url).toBe(segUrl('r480', 9));
    expect(bank.hasSegment(5)).toBe(false);
    expect(server.maxOpen).toBe(1);
  });

  it('keeps its position for a move past one it has already passed, and fetches after a held one when idle', async () => {
    const { server, front } = await running({ seq: 5 });
    front.movePast(3);
    await flush();
    expect(server.calls).toHaveLength(4);
    expect(server.calls[3].aborted).toBe(false);
    await complete(server.calls[3]);
    front.update({ filmAhead: 1000, need: 1000 });
    front.movePast(9);
    await flush();
    front.update({ filmAhead: 0, need: 1000 });
    await flush();
    expect(server.calls.at(-1).url).toBe(segUrl('r480', 10));
  });

  it('makes no request after the end of the film', async () => {
    const { server } = await running({ seq: 39 });
    await complete(server.calls.at(-1));
    expect(server.calls).toHaveLength(4);
  });
});

describe('the bank front: when it wants film', () => {
  it('does not overshoot F: the segment that lands counts as film ahead until the next update', async () => {
    const { server } = await running({ filmAhead: 8, need: 12 });
    await complete(server.calls.at(-1));
    expect(server.calls).toHaveLength(4);
    const below = await running({ filmAhead: 7, need: 12 });
    await complete(below.server.calls.at(-1));
    expect(below.server.calls).toHaveLength(5);
    expect(below.server.calls[4].url).toBe(segUrl('r480', 1));
  });

  it('makes the next request once playback brings film ahead below F', async () => {
    const { server, front } = await running({ filmAhead: 8, need: 12 });
    await complete(server.calls.at(-1));
    front.update({ filmAhead: 12, need: 12 });
    await flush();
    expect(server.calls).toHaveLength(4);
    front.update({ filmAhead: 11.9, need: 12 });
    await flush();
    expect(server.calls).toHaveLength(5);
    expect(server.calls[4].url).toBe(segUrl('r480', 1));
  });

  it('stops at the bank limit and goes on when film is dropped', async () => {
    const { server, bank, front } = await running({ limitBytes: 1500 });
    await complete(server.calls.at(-1), { size: 1000 });
    await complete(server.calls.at(-1), { size: 1000 });
    expect(bank.atLimit()).toBe(true);
    expect(server.calls).toHaveLength(5);
    front.update({ filmAhead: 8, need: 1000 });
    await flush();
    expect(server.calls).toHaveLength(5);
    bank.dropBehind(40);
    expect(bank.atLimit()).toBe(false);
    front.update({ filmAhead: 8, need: 1000 });
    await flush();
    expect(server.calls).toHaveLength(6);
    expect(server.calls[5].url).toBe(segUrl('r480', 2));
  });
});

describe('the bank front: reports and the 60 s window', () => {
  it('reports the kbit/s of each completed fetch, first byte to end, and none for a failed one', async () => {
    const { server, seen } = await running();
    await complete(server.calls.at(-1), { size: 5000, firstMs: 100, bodyMs: 400 });
    expect(seen.kbps).toEqual([100]);
    server.calls.at(-1).fail();
    await flush();
    server.calls.at(-1).status(503, '1');
    await flush();
    expect(seen.kbps).toEqual([100]);
  });

  it('is empty before anything is fetched', async () => {
    const { front } = setup();
    expect(front.window()).toEqual({
      rung: null,
      rate: null,
      w: 0,
      d: 0,
      measuredS: 0,
      measuredWallS: 0,
    });
  });

  it('measures waiting and transfer per film second, a 503 wait counting as waiting', async () => {
    const { server, front } = await running();
    await complete(server.calls.at(-1), { firstMs: 1000, bodyMs: 1000 });
    server.calls.at(-1).status(503, '1');
    await vi.advanceTimersByTimeAsync(1000);
    await complete(server.calls.at(-1), { firstMs: 500, bodyMs: 500 });
    expect(front.window()).toEqual({
      rung: 'r480',
      rate: 1800,
      w: 2.5 / 8,
      d: 1.5 / 8,
      measuredS: 8,
      measuredWallS: 4,
    });
  });

  it('holds more wall seconds than film seconds for a wait-heavy fetch', async () => {
    const { server, front } = await running();
    await complete(server.calls.at(-1), { firstMs: 5000, bodyMs: 2000 });
    const w = front.window();
    expect(w.measuredS).toBe(4);
    expect(w.measuredWallS).toBe(7);
    expect(w.w).toBe(1.25);
    expect(w.d).toBe(0.5);
  });

  it('keeps only the last 60 s of fetching', async () => {
    const { server, front } = await running();
    for (let i = 0; i < 10; i += 1) {
      await complete(server.calls.at(-1), { firstMs: 5000, bodyMs: 5000 });
    }
    expect(front.window()).toMatchObject({
      measuredS: 24,
      measuredWallS: 60,
      w: 1.25,
      d: 1.25,
    });
  });

  it('keeps it at the rung fetched: a change of rung starts a new measurement', async () => {
    const { server, front } = await running({ rung: 'r240' });
    await complete(server.calls.at(-1), { firstMs: 1000, bodyMs: 1000 });
    expect(front.window().rung).toBe('r240');
    front.setRung('r480');
    await complete(server.calls.at(-1), { bodyMs: 100 }); // already in flight at r240
    await complete(server.calls.at(-1), { bodyMs: 2000 });
    expect(front.window()).toEqual({
      rung: 'r480',
      rate: 1800,
      w: 0,
      d: 0.5,
      measuredS: 4,
      measuredWallS: 2,
    });
  });

  it('counts nothing while it does not want film', async () => {
    const { server, front } = await running();
    front.update({ filmAhead: 10, need: 10 });
    await complete(server.calls.at(-1), { firstMs: 1000, bodyMs: 1000 });
    await vi.advanceTimersByTimeAsync(100_000);
    front.update({ filmAhead: 0, need: 10 });
    await flush();
    await complete(server.calls.at(-1), { firstMs: 1000, bodyMs: 1000 });
    expect(front.window()).toMatchObject({ measuredS: 8, measuredWallS: 4, w: 0.25, d: 0.25 });
  });
});

describe('the bank front: the rung', () => {
  it('applies setRung from the next fetch only, a retry keeping its rung', async () => {
    const { server, bank, front } = await running({ rung: 'r240' });
    front.setRung('r360');
    await complete(server.calls.at(-1));
    expect(bank.getSegment(0).rung).toBe('r240');
    expect(server.calls.at(-1).url).toBe(segUrl('r360', 1));
    server.calls.at(-1).fail();
    front.setRung('r480');
    await vi.advanceTimersByTimeAsync(2000);
    expect(server.calls.at(-1).url).toBe(segUrl('r360', 1));
    await complete(server.calls.at(-1));
    expect(server.calls.at(-1).url).toBe(segUrl('r360', 0)); // the boundary copy of 0, before moving on
    await complete(server.calls.at(-1));
    expect(server.calls.at(-1).url).toBe(segUrl('r480', 2));
  });

  it('fetches a start above 480p at 480p, and an unknown rung at the highest it uses', async () => {
    const above = await running({ rung: 'r720' });
    expect(above.server.calls.at(-1).url).toBe(segUrl('r480', 0));
    above.front.setRung('r720');
    await complete(above.server.calls.at(-1));
    expect(above.server.calls.at(-1).url).toBe(segUrl('r480', 1));
    const unknown = await running({ rung: 'nope' });
    expect(unknown.server.calls.at(-1).url).toBe(segUrl('r480', 0));
  });
});

describe('the bank front: server answers', () => {
  it('waits and asks again for a 503 with Retry-After, backing off to 5 s, and starts over after a success', async () => {
    const { server } = await running();
    for (const wait of [1000, 2000, 4000, 5000]) {
      const before = server.calls.length;
      server.calls.at(-1).status(503, '1');
      await vi.advanceTimersByTimeAsync(wait - 1);
      expect(server.calls).toHaveLength(before);
      await vi.advanceTimersByTimeAsync(1);
      expect(server.calls).toHaveLength(before + 1);
      expect(server.calls.at(-1).url).toBe(segUrl('r480', 0));
    }
    await complete(server.calls.at(-1));
    server.calls.at(-1).status(503, '1');
    await vi.advanceTimersByTimeAsync(999);
    expect(server.calls.at(-1).url).toBe(segUrl('r480', 1));
    expect(server.open).toBe(0);
    await vi.advanceTimersByTimeAsync(1);
    expect(server.open).toBe(1);
  });

  it('pauses on a 503 without Retry-After: no request until RECONNECT_MS from the pause, whatever happens meanwhile', async () => {
    const { server, front } = await running();
    server.calls.at(-1).status(503);
    await flush();
    const before = server.calls.length;
    await vi.advanceTimersByTimeAsync(RECONNECT_MS / 2);
    front.update({ filmAhead: 0, need: 1000 });
    front.arm();
    await vi.advanceTimersByTimeAsync(RECONNECT_MS / 2 - 1);
    expect(server.calls).toHaveLength(before);
    await vi.advanceTimersByTimeAsync(1);
    expect(server.calls).toHaveLength(before + 1);
    expect(server.calls.at(-1).url).toBe(segUrl('r480', 0));
  });

  it('resumes a pause at once on a successful server answer reported from outside, once only', async () => {
    const { server, front } = await running();
    server.calls.at(-1).status(503);
    await flush();
    const before = server.calls.length;
    await vi.advanceTimersByTimeAsync(1000);
    front.serverAnswered();
    await flush();
    expect(server.calls).toHaveLength(before + 1);
    await vi.advanceTimersByTimeAsync(RECONNECT_MS * 2);
    expect(server.calls).toHaveLength(before + 1);
  });

  it('ignores a successful answer reported while it is not paused', async () => {
    const { server, front } = await running();
    front.serverAnswered();
    await flush();
    expect(server.calls).toHaveLength(4);
  });

  it('waits 2 s and asks again after a network error, and after a status it does not know', async () => {
    const { server } = await running();
    server.calls.at(-1).fail();
    await vi.advanceTimersByTimeAsync(1999);
    expect(server.calls).toHaveLength(4);
    await vi.advanceTimersByTimeAsync(1);
    expect(server.calls).toHaveLength(5);
    expect(server.calls[4].url).toBe(segUrl('r480', 0));
    server.calls.at(-1).status(500);
    await vi.advanceTimersByTimeAsync(1999);
    expect(server.calls).toHaveLength(5);
    await vi.advanceTimersByTimeAsync(1);
    expect(server.calls).toHaveLength(6);
  });

  it('gives up on a response with no first byte after 10 s, then waits 2 s', async () => {
    const { server } = await running();
    const call = server.calls.at(-1);
    await vi.advanceTimersByTimeAsync(9999);
    expect(call.aborted).toBe(false);
    await vi.advanceTimersByTimeAsync(1);
    expect(call.aborted).toBe(true);
    await vi.advanceTimersByTimeAsync(1999);
    expect(server.calls).toHaveLength(4);
    await vi.advanceTimersByTimeAsync(1);
    expect(server.calls).toHaveLength(5);
  });

  it('gives up on a transfer with no progress for 12 s counted from the last bytes', async () => {
    const { server, bank } = await running();
    const call = server.calls.at(-1);
    await vi.advanceTimersByTimeAsync(9000);
    const r = call.respond();
    r.send(new Uint8Array(10));
    await vi.advanceTimersByTimeAsync(11_999);
    expect(call.aborted).toBe(false);
    r.send(new Uint8Array(10));
    await vi.advanceTimersByTimeAsync(11_999);
    expect(call.aborted).toBe(false);
    await vi.advanceTimersByTimeAsync(1);
    expect(call.aborted).toBe(true);
    expect(bank.hasSegment(0)).toBe(false);
  });

  it('completes a 25 s transfer that keeps moving', async () => {
    const { server, bank } = await running();
    const call = server.calls.at(-1);
    const r = call.respond();
    for (let i = 0; i < 5; i += 1) {
      r.send(new Uint8Array(100));
      await vi.advanceTimersByTimeAsync(5000);
    }
    expect(call.aborted).toBe(false);
    r.end();
    await flush();
    expect(bank.getSegment(0).bytes.byteLength).toBe(500);
    expect(bank.getSegment(0).timings).toEqual({ start: T0, first: T0, end: T0 + 25_000 });
  });

  it('stops and calls onStopped on a 410, with no request after it', async () => {
    const { server, seen, front } = await running();
    server.calls.at(-1).status(410);
    await flush();
    expect(seen).toMatchObject({ stopped: 1, lost: 0 });
    front.update({ filmAhead: 0, need: 1000 });
    await vi.advanceTimersByTimeAsync(RECONNECT_MS * 4);
    expect(server.calls).toHaveLength(4);
  });

  it('stops and calls onSessionLost on a 404, with no request after it', async () => {
    const { server, seen } = await running();
    server.calls.at(-1).status(404);
    await flush();
    expect(seen).toMatchObject({ stopped: 0, lost: 1 });
    await vi.advanceTimersByTimeAsync(RECONNECT_MS * 4);
    expect(server.calls).toHaveLength(4);
  });
});

describe('the bank front: stopping', () => {
  it('aborts the fetch in flight and makes no request after stop', async () => {
    const { server, front } = await running();
    const call = server.calls.at(-1);
    front.stop();
    expect(call.aborted).toBe(true);
    front.update({ filmAhead: 0, need: 1000 });
    front.start({ seq: 3, rung: 'r480' });
    front.movePast(9);
    front.arm();
    await vi.advanceTimersByTimeAsync(RECONNECT_MS * 4);
    expect(server.calls).toHaveLength(4);
  });

  it('cancels a pending retry and a pause', async () => {
    const retrying = await running();
    retrying.server.calls.at(-1).fail();
    await flush();
    retrying.front.stop();
    await vi.advanceTimersByTimeAsync(RECONNECT_MS * 4);
    expect(retrying.server.calls).toHaveLength(4);
    const paused = await running();
    paused.server.calls.at(-1).status(503);
    await flush();
    paused.front.stop();
    await vi.advanceTimersByTimeAsync(RECONNECT_MS * 4);
    expect(paused.server.calls).toHaveLength(4);
  });

  it('neither stores nor reports an answer that arrives after an abort', async () => {
    const { server, bank, front, seen } = await running();
    server.ignoreAbort = true;
    const call = server.calls.at(-1);
    front.stop();
    await complete(call, { size: 500, firstMs: 10, bodyMs: 10 });
    expect(bank.hasSegment(0)).toBe(false);
    expect(seen.kbps).toEqual([]);
    expect(server.calls).toHaveLength(4);
  });

  it('does not store the answer of a fetch it moved past', async () => {
    const { server, bank, front, seen } = await running({ seq: 5 });
    server.ignoreAbort = true;
    const stale = server.calls.at(-1);
    front.movePast(8);
    await flush();
    await complete(stale, { size: 500, firstMs: 10, bodyMs: 10 });
    expect(bank.hasSegment(5)).toBe(false);
    expect(seen.kbps).toEqual([]);
    expect(server.calls.at(-1).url).toBe(segUrl('r480', 9));
  });
});

describe('the bank front: a new front over a bank that holds the playlists', () => {
  it('asks for no playlist and fetches the start position from the held ones', async () => {
    const server = makeServer();
    const bank = createSegmentBank();
    for (const r of RUNGS.slice(0, 3)) bank.putPlaylist(r.id, playlistText());
    const front = createBankFront({ rungs: RUNGS, bank, fetch: server.fetch });
    front.arm();
    front.update({ filmAhead: 0, need: 100 });
    front.start({ seq: 7, rung: 'r360' });
    await flush();
    expect(server.calls.map((c) => c.url)).toEqual([segUrl('r360', 7)]);
  });
});

describe('the bank front: pause, position and fetch now', () => {
  it('pauses on request from the first pause only, a second pause restarting nothing', async () => {
    const { server, front } = await running();
    front.pause();
    expect(front.isPaused()).toBe(true);
    server.calls.at(-1).fail();
    await vi.advanceTimersByTimeAsync(RECONNECT_MS / 2);
    front.pause();
    await vi.advanceTimersByTimeAsync(RECONNECT_MS / 2 - 1);
    expect(server.calls).toHaveLength(4);
    expect(front.isPaused()).toBe(true);
    await vi.advanceTimersByTimeAsync(1);
    expect(front.isPaused()).toBe(false);
    expect(server.calls).toHaveLength(5);
    expect(server.calls[4].url).toBe(segUrl('r480', 0));
  });

  it('says what it will fetch next, a rung change showing once the fetch in flight is done', async () => {
    const { server, front } = await running({ seq: 5, rung: 'r360' });
    expect(front.position()).toEqual({ seq: 5, rung: 'r360' });
    front.setRung('r480');
    expect(front.position()).toEqual({ seq: 5, rung: 'r360' });
    await complete(server.calls.at(-1));
    expect(front.position()).toEqual({ seq: 6, rung: 'r480' });
  });

  it('fetches its next position at once while paused with one request, the pause timer untouched, a plain 503 leaving it paused', async () => {
    const { server, front } = await running();
    server.calls.at(-1).status(503);
    await vi.advanceTimersByTimeAsync(RECONNECT_MS / 2);
    front.fetchNow();
    front.fetchNow();
    await flush();
    expect(server.calls).toHaveLength(5);
    expect(server.calls[4].url).toBe(segUrl('r480', 0));
    server.calls[4].status(503);
    await vi.advanceTimersByTimeAsync(RECONNECT_MS / 2 - 1);
    expect(server.calls).toHaveLength(5);
    expect(front.isPaused()).toBe(true);
    await vi.advanceTimersByTimeAsync(1);
    expect(server.calls).toHaveLength(6);
    expect(server.maxOpen).toBe(1);
  });

  it('resumes the front on a successful fetch now, at the following position and with no second request from the old timer', async () => {
    const { server, front } = await running();
    server.calls.at(-1).status(503);
    await vi.advanceTimersByTimeAsync(1000);
    front.fetchNow();
    await flush();
    await complete(server.calls.at(-1));
    expect(front.isPaused()).toBe(false);
    expect(server.calls).toHaveLength(6);
    expect(server.calls[5].url).toBe(segUrl('r480', 1));
    await vi.advanceTimersByTimeAsync(RECONNECT_MS * 2);
    expect(server.calls).toHaveLength(6);
  });

  it('fetches now at F with one request and nothing after it', async () => {
    const { server, front } = await running({ filmAhead: 12, need: 12 });
    expect(server.calls).toHaveLength(3);
    front.fetchNow();
    await flush();
    expect(server.calls).toHaveLength(4);
    await complete(server.calls.at(-1));
    await vi.advanceTimersByTimeAsync(RECONNECT_MS * 2);
    expect(server.calls).toHaveLength(4);
  });

  it('does nothing for fetch now while a fetch is in flight, a retry is pending, before start or after stop', async () => {
    const { server, front } = await running();
    front.fetchNow();
    await flush();
    expect(server.calls).toHaveLength(4);
    server.calls.at(-1).fail();
    await flush();
    front.fetchNow();
    await flush();
    expect(server.calls).toHaveLength(4);
    front.stop();
    front.fetchNow();
    await flush();
    expect(server.calls).toHaveLength(4);
    const idle = setup();
    idle.front.fetchNow();
    idle.front.arm();
    idle.front.fetchNow();
    await flush();
    expect(idle.server.calls).toHaveLength(1);
    expect(idle.server.calls[0].url).toBe(RUNGS[0].playlistUrl);
  });

  it('does nothing for fetch now while the playlists are not all held', async () => {
    const t = setup();
    t.front.arm();
    await flush();
    await answerPlaylist(t.server.calls[0]);
    t.front.update({ filmAhead: 0, need: 100 });
    t.front.start({ seq: 0, rung: 'r480' });
    t.front.fetchNow();
    await flush();
    expect(t.server.calls).toHaveLength(2);
    expect(t.server.calls[1].url).toBe(RUNGS[1].playlistUrl);
  });
});

describe('the bank front: subscribers to a fetch', () => {
  function listener() {
    const log = [];
    return {
      log,
      onFirstByte: () => log.push(['firstByte']),
      onProgress: (n) => log.push(['progress', n]),
      onDone: (segment) => log.push(['done', segment]),
      onServerAnswer: (status, headers) => log.push(['answer', status, headers]),
      onError: (kind) => log.push(['error', kind]),
    };
  }

  it('shares the fetch in flight: first byte, progress and the done segment, with one server request', async () => {
    const { server, front } = await running();
    const l = listener();
    front.subscribe(0, l);
    const r = server.calls.at(-1).respond();
    r.send(new Uint8Array(100));
    await flush();
    r.send(new Uint8Array(150));
    await flush();
    r.end();
    await flush();
    expect(l.log.map((e) => e[0])).toEqual(['firstByte', 'progress', 'progress', 'done']);
    expect(l.log[1]).toEqual(['progress', 100]);
    expect(l.log[2]).toEqual(['progress', 250]);
    const segment = l.log[3][1];
    expect(segment.seq).toBe(0);
    expect(segment.bytes.byteLength).toBe(250);
    expect(server.calls).toHaveLength(5); // 3 playlists, seq 0, and the front's own seq 1
    expect(server.calls[3].url).toBe(segUrl('r480', 0));
    expect(server.maxOpen).toBe(1);
  });

  it('ends the subscription at done: a later fetch of the same position is not reported to it', async () => {
    const { server, front } = await running();
    const l = listener();
    front.subscribe(0, l);
    await complete(server.calls.at(-1));
    expect(l.log.map((e) => e[0])).toEqual(['firstByte', 'progress', 'done']);
    front.start({ seq: 0, rung: 'r480' });
    await flush();
    await complete(server.calls.at(-1));
    expect(l.log.map((e) => e[0])).toEqual(['firstByte', 'progress', 'done']);
  });

  it('attaches to the next fetch when none is in flight', async () => {
    const { server, front } = await running({ filmAhead: 12, need: 12 });
    const l = listener();
    front.subscribe(0, l);
    front.update({ filmAhead: 0, need: 12 });
    await flush();
    await complete(server.calls.at(-1), { size: 40 });
    expect(l.log.at(-1)[0]).toBe('done');
    expect(l.log.at(-1)[1].bytes.byteLength).toBe(40);
  });

  it('hands a 503, and its Retry-After, over at once while the front waits and asks again', async () => {
    const { server, front } = await running();
    const l = listener();
    front.subscribe(0, l);
    server.calls.at(-1).status(503, '1');
    await flush();
    expect(l.log).toHaveLength(1);
    expect(l.log[0][1]).toBe(503);
    expect(l.log[0][2].get('Retry-After')).toBe('1');
    await vi.advanceTimersByTimeAsync(1000);
    await complete(server.calls.at(-1));
    expect(l.log.at(-1)[0]).toBe('done');
  });

  it("hands a 404 and a 410 over at once, as the subscribers' last event", async () => {
    const lost = await running();
    const a = [];
    lost.front.subscribe(0, {
      onServerAnswer: (s) => a.push([s]),
      onAbort: () => a.push(['abort']),
    });
    lost.server.calls.at(-1).status(404);
    await flush();
    expect(a).toEqual([[404]]);
    expect(lost.seen.lost).toBe(1);
    const gone = await running();
    const b = [];
    gone.front.subscribe(0, {
      onServerAnswer: (s) => b.push([s]),
      onAbort: () => b.push(['abort']),
    });
    gone.server.calls.at(-1).status(410);
    await flush();
    expect(b).toEqual([[410]]);
    expect(gone.seen.stopped).toBe(1);
  });

  it('hands over a network error and a first-byte timeout', async () => {
    const { server, front } = await running();
    const l = listener();
    front.subscribe(0, l);
    server.calls.at(-1).fail();
    await flush();
    expect(l.log).toEqual([['error', 'network']]);
    await vi.advanceTimersByTimeAsync(2000);
    await vi.advanceTimersByTimeAsync(10_000);
    expect(l.log).toEqual([
      ['error', 'network'],
      ['error', 'timeout'],
    ]);
  });

  it('stops reporting after unsubscribe, and reports nothing of an aborted fetch', async () => {
    const { server, front } = await running();
    const l = listener();
    const off = front.subscribe(0, l);
    off();
    off();
    const m = listener();
    front.subscribe(0, m);
    const stale = server.calls.at(-1);
    server.ignoreAbort = true;
    front.movePast(3);
    await flush();
    const r = stale.respond();
    r.send(new Uint8Array(10));
    await flush();
    r.end();
    await flush();
    expect(l.log).toEqual([]);
    expect(m.log).toEqual([]);
  });

  it('keeps a listener with no handler harmless, and stop drops every subscription', async () => {
    const { server, front } = await running();
    front.subscribe(0, {});
    const l = listener();
    front.subscribe(0, l);
    await complete(server.calls.at(-1));
    expect(l.log.map((e) => e[0])).toEqual(['firstByte', 'progress', 'done']);
    const after = listener();
    front.subscribe(1, after);
    front.stop();
    await flush();
    expect(after.log).toEqual([]);
  });
});

describe('the bank front: the time paused', () => {
  it('counts a pause that ends by its timer as waiting in the next entry', async () => {
    const { server, front } = await running();
    server.calls.at(-1).status(503);
    await vi.advanceTimersByTimeAsync(RECONNECT_MS);
    await complete(server.calls.at(-1), { firstMs: 500, bodyMs: 500 });
    expect(front.window()).toMatchObject({
      measuredS: 4,
      measuredWallS: 6,
      w: 5.5 / 4,
      d: 0.125,
    });
  });

  it('counts a pause ended by a successful answer from outside, once only', async () => {
    const { server, front } = await running();
    server.calls.at(-1).status(503);
    await vi.advanceTimersByTimeAsync(1000);
    front.serverAnswered();
    await flush();
    await complete(server.calls.at(-1), { firstMs: 500, bodyMs: 500 });
    await complete(server.calls.at(-1), { firstMs: 500, bodyMs: 500 });
    expect(front.window()).toMatchObject({ measuredS: 8, measuredWallS: 3, w: 2 / 8, d: 0.125 });
  });

  it('counts a pause ended by a successful fetch now', async () => {
    const { server, front } = await running();
    server.calls.at(-1).status(503);
    await vi.advanceTimersByTimeAsync(2000);
    front.fetchNow();
    await flush();
    await complete(server.calls.at(-1), { firstMs: 500, bodyMs: 500 });
    expect(front.window()).toMatchObject({ measuredS: 4, measuredWallS: 3, w: 2.5 / 4, d: 0.125 });
  });
});

describe('the bank front: a move while it waits', () => {
  it('makes no request during a pause on a move past or a start, then exactly one at the new position', async () => {
    const moved = await running();
    moved.server.calls.at(-1).status(503);
    await vi.advanceTimersByTimeAsync(2000);
    moved.front.movePast(8);
    await vi.advanceTimersByTimeAsync(RECONNECT_MS - 2000 - 1);
    expect(moved.server.calls).toHaveLength(4);
    await vi.advanceTimersByTimeAsync(1);
    expect(moved.server.calls).toHaveLength(5);
    expect(moved.server.calls[4].url).toBe(segUrl('r480', 9));
    const started = await running();
    started.server.calls.at(-1).status(503);
    await vi.advanceTimersByTimeAsync(2000);
    started.front.start({ seq: 20, rung: 'r360' });
    await vi.advanceTimersByTimeAsync(RECONNECT_MS - 2000 - 1);
    expect(started.server.calls).toHaveLength(4);
    await vi.advanceTimersByTimeAsync(1);
    expect(started.server.calls).toHaveLength(5);
    expect(started.server.calls[4].url).toBe(segUrl('r360', 20));
  });

  it('asks at once at the new position during a network-error wait, the old timer never firing a request of its own', async () => {
    const { server, front } = await running();
    server.calls.at(-1).fail();
    await vi.advanceTimersByTimeAsync(1000);
    front.movePast(5);
    await flush();
    expect(server.calls).toHaveLength(5);
    expect(server.calls[4].url).toBe(segUrl('r480', 6));
    server.calls[4].fail();
    await vi.advanceTimersByTimeAsync(1999); // the old timer's time is 1000 after the move
    expect(server.calls).toHaveLength(5);
    await vi.advanceTimersByTimeAsync(1);
    expect(server.calls).toHaveLength(6);
    expect(server.calls[5].url).toBe(segUrl('r480', 6));
    expect(server.maxOpen).toBe(1);
  });

  it('asks at once during a Retry-After wait and starts the backoff over', async () => {
    const { server, front } = await running();
    server.calls.at(-1).status(503, '1');
    await vi.advanceTimersByTimeAsync(1000);
    server.calls.at(-1).status(503, '1'); // a second 503: the next wait would be 2 s, then 4 s
    await vi.advanceTimersByTimeAsync(500);
    front.start({ seq: 3, rung: 'r480' });
    await flush();
    expect(server.calls).toHaveLength(6);
    expect(server.calls[5].url).toBe(segUrl('r480', 3));
    server.calls[5].status(503, '1');
    await vi.advanceTimersByTimeAsync(999);
    expect(server.calls).toHaveLength(6);
    await vi.advanceTimersByTimeAsync(1);
    expect(server.calls).toHaveLength(7);
  });

  it('makes no request after stop during a Retry-After wait', async () => {
    const { server, front } = await running();
    server.calls.at(-1).status(503, '1');
    await vi.advanceTimersByTimeAsync(500);
    front.stop();
    await vi.advanceTimersByTimeAsync(RECONNECT_MS * 4);
    expect(server.calls).toHaveLength(4);
  });
});

describe('the bank front: uneven entries in the window', () => {
  it('keeps entries until they hold 60 s, so the window may exceed 60 s by one entry', async () => {
    const { server, front } = await running();
    for (let i = 0; i < 12; i += 1) {
      await complete(server.calls.at(-1), { firstMs: 3000, bodyMs: 4000 });
    }
    // 7 s each: eight hold 56 s, a ninth is taken to reach 63 and the tenth is not
    expect(front.window()).toMatchObject({
      measuredS: 36,
      measuredWallS: 63,
      w: 27 / 36,
      d: 36 / 36,
    });
  });
});

describe('the bank front: retries of a fetch now', () => {
  it('retries a failed fetch now at F, exactly once more', async () => {
    const { server, front } = await running({ filmAhead: 12, need: 12 });
    front.subscribe(0, {});
    front.fetchNow();
    await flush();
    server.calls.at(-1).fail();
    await vi.advanceTimersByTimeAsync(1999);
    expect(server.calls).toHaveLength(4);
    await vi.advanceTimersByTimeAsync(1);
    expect(server.calls).toHaveLength(5);
    expect(server.calls[4].url).toBe(segUrl('r480', 0));
    await vi.advanceTimersByTimeAsync(RECONNECT_MS * 2);
    expect(server.calls).toHaveLength(5);
  });

  it('retries a failed fetch now while paused, before RECONNECT_MS, exactly once more', async () => {
    const { server, front } = await running();
    front.subscribe(0, {});
    server.calls.at(-1).status(503);
    await vi.advanceTimersByTimeAsync(1000);
    front.fetchNow();
    await flush();
    server.calls.at(-1).fail();
    await vi.advanceTimersByTimeAsync(2000);
    expect(server.calls).toHaveLength(6);
    expect(server.calls[5].url).toBe(segUrl('r480', 0));
    expect(front.isPaused()).toBe(true);
  });

  it('retries a fetch now after a 503 with Retry-After at F, exactly once more', async () => {
    const { server, front } = await running({ filmAhead: 12, need: 12 });
    front.subscribe(0, {});
    front.fetchNow();
    await flush();
    server.calls.at(-1).status(503, '1');
    await vi.advanceTimersByTimeAsync(1000);
    expect(server.calls).toHaveLength(5);
    await vi.advanceTimersByTimeAsync(RECONNECT_MS * 2);
    expect(server.calls).toHaveLength(5);
  });

  it('lets a fetch now take over a retry already pending when the front no longer wants film', async () => {
    const { server, front } = await running();
    front.subscribe(0, {});
    server.calls.at(-1).fail();
    await vi.advanceTimersByTimeAsync(500);
    front.update({ filmAhead: 12, need: 12 });
    front.fetchNow();
    await vi.advanceTimersByTimeAsync(1500);
    expect(server.calls).toHaveLength(5);
    expect(server.calls[4].url).toBe(segUrl('r480', 0));
  });
});

describe('the bank front: a subscriber to a position that already failed', () => {
  it('hears a Retry-After wait under way at once, and nothing once the retry has begun', async () => {
    const { server, front } = await running();
    server.calls.at(-1).status(503, '1');
    await flush();
    const early = [];
    front.subscribe(0, {
      onServerAnswer: (status, headers) => early.push([status, headers.get('Retry-After')]),
    });
    expect(early).toEqual([[503, '1']]);
    await vi.advanceTimersByTimeAsync(1000);
    const late = [];
    front.subscribe(0, { onServerAnswer: (status) => late.push(status) });
    expect(late).toEqual([]);
  });

  it("does not replay a network-error wait, but a retry's own events reach the subscriber", async () => {
    const { server, front } = await running();
    server.calls.at(-1).fail();
    await flush();
    const log = [];
    front.subscribe(0, {
      onError: (kind) => log.push(['error', kind]),
      onDone: () => log.push(['done']),
    });
    expect(log).toEqual([]);
    await vi.advanceTimersByTimeAsync(2000);
    await complete(server.calls.at(-1));
    expect(log).toEqual([['done']]);
  });

  it('forgets a replay after a move', async () => {
    const { server, front } = await running();
    server.calls.at(-1).status(503, '4');
    await flush();
    front.movePast(4);
    await flush();
    const log = [];
    front.subscribe(0, { onServerAnswer: () => log.push('stale') });
    expect(log).toEqual([]);
  });

  it('does not replay the 503 that paused the front: a subscriber and a fetch now hear only the new fetch', async () => {
    const { server, front } = await running();
    server.calls.at(-1).status(503);
    await flush();
    const log = [];
    front.subscribe(0, {
      onServerAnswer: (status) => log.push(['answer', status]),
      onFirstByte: () => log.push(['firstByte']),
      onDone: () => log.push(['done']),
    });
    expect(log).toEqual([]);
    front.fetchNow();
    await flush();
    await complete(server.calls.at(-1));
    expect(log).toEqual([['firstByte'], ['done']]);
  });

  it('leaves nothing to replay once a retry timer has fired, though the front declined to fetch at F', async () => {
    const { server, front } = await running();
    server.calls.at(-1).fail();
    await flush();
    front.update({ filmAhead: 12, need: 12 });
    await vi.advanceTimersByTimeAsync(2000);
    expect(server.calls).toHaveLength(4);
    const log = [];
    front.subscribe(0, { onError: (kind) => log.push(kind) });
    expect(log).toEqual([]);
  });
});

describe('the bank front: pause time counted once, and only while film is wanted', () => {
  it('counts the pause once when attempts fail inside it', async () => {
    const { server, front } = await running();
    server.calls.at(-1).status(503);
    await vi.advanceTimersByTimeAsync(2000);
    front.fetchNow();
    await vi.advanceTimersByTimeAsync(1000);
    server.calls.at(-1).status(503);
    await vi.advanceTimersByTimeAsync(RECONNECT_MS - 3000);
    await complete(server.calls.at(-1), { firstMs: 500, bodyMs: 500 });
    expect(front.window()).toMatchObject({ measuredS: 4, measuredWallS: 6, w: 5.5 / 4, d: 0.125 });
  });

  it('counts a retry wait inside a pause as the pause only', async () => {
    const { server, front } = await running();
    server.calls.at(-1).status(503);
    await vi.advanceTimersByTimeAsync(1000);
    front.fetchNow();
    await flush();
    server.calls.at(-1).fail();
    await vi.advanceTimersByTimeAsync(2000);
    server.calls.at(-1).status(503, '1');
    await vi.advanceTimersByTimeAsync(RECONNECT_MS - 3000);
    await complete(server.calls.at(-1), { firstMs: 500, bodyMs: 500 });
    expect(front.window()).toMatchObject({ measuredS: 4, w: 5.5 / 4, measuredWallS: 6 });
  });

  it('counts nothing of a pause at F, whenever film is wanted again', async () => {
    const { server, front } = await running({ filmAhead: 12, need: 12 });
    front.pause();
    await vi.advanceTimersByTimeAsync(RECONNECT_MS);
    expect(front.isPaused()).toBe(false);
    await vi.advanceTimersByTimeAsync(20_000);
    front.update({ filmAhead: 0, need: 12 });
    await flush();
    await complete(server.calls.at(-1), { firstMs: 500, bodyMs: 500 });
    expect(front.window()).toMatchObject({ measuredWallS: 1, w: 0.125, d: 0.125 });
  });

  it('stops the stopwatch while film is not wanted and goes on from what it held when it is wanted again', async () => {
    const { server, front } = await running();
    server.calls.at(-1).status(503);
    await vi.advanceTimersByTimeAsync(1000);
    front.update({ filmAhead: 12, need: 12 });
    await vi.advanceTimersByTimeAsync(1000);
    front.update({ filmAhead: 0, need: 12 });
    await vi.advanceTimersByTimeAsync(RECONNECT_MS - 2000);
    await complete(server.calls.at(-1), { firstMs: 500, bodyMs: 500 });
    // 0-1 s and 2-6 s counted, 1-2 s at F not
    expect(front.window()).toMatchObject({ measuredWallS: 5, w: 4.5 / 4, d: 0.125 });
  });

  it('drops the pause time of the old position on a start, and counts the pause on from the start', async () => {
    const { server, front } = await running();
    server.calls.at(-1).status(503);
    await vi.advanceTimersByTimeAsync(2000);
    front.start({ seq: 3, rung: 'r480' });
    await vi.advanceTimersByTimeAsync(RECONNECT_MS - 2000);
    await complete(server.calls.at(-1), { firstMs: 500, bodyMs: 500 });
    expect(front.window()).toMatchObject({ measuredWallS: 4, w: 3.5 / 4 });
  });
});

describe('the bank front: the stopwatch of wall time spent wanting film', () => {
  it('holds a plain fetch whole: waiting before the first byte, then the transfer', async () => {
    const { server, front } = await running();
    await complete(server.calls.at(-1), { firstMs: 1500, bodyMs: 500 });
    expect(front.window()).toMatchObject({
      measuredS: 4,
      measuredWallS: 2,
      w: 1.5 / 4,
      d: 0.5 / 4,
    });
  });

  it('holds a Retry-After backoff as waiting', async () => {
    const { server, front } = await running();
    server.calls.at(-1).status(503, '1');
    await vi.advanceTimersByTimeAsync(1000);
    server.calls.at(-1).status(503, '1');
    await vi.advanceTimersByTimeAsync(2000);
    await complete(server.calls.at(-1), { firstMs: 500, bodyMs: 500 });
    expect(front.window()).toMatchObject({ measuredWallS: 4, w: 3.5 / 4, d: 0.125 });
  });

  it('holds a network error and its 2 s retry as waiting', async () => {
    const { server, front } = await running();
    server.calls.at(-1).fail();
    await vi.advanceTimersByTimeAsync(2000);
    await complete(server.calls.at(-1), { firstMs: 500, bodyMs: 500 });
    expect(front.window()).toMatchObject({ measuredWallS: 3, w: 2.5 / 4, d: 0.125 });
  });

  it('counts a Retry-After wait overlapped by an outside pause once: 6 s of wall time, 6 s of waiting', async () => {
    const { server, front } = await running();
    server.calls.at(-1).status(503, '4');
    await vi.advanceTimersByTimeAsync(1000);
    front.pause();
    await vi.advanceTimersByTimeAsync(RECONNECT_MS);
    await complete(server.calls.at(-1));
    expect(front.window()).toMatchObject({ measuredS: 4, measuredWallS: 6, w: 6 / 4, d: 0 });
  });

  it('keeps the time of an attempt that began before an outside pause and failed during it', async () => {
    const { server, front } = await running();
    await vi.advanceTimersByTimeAsync(3000);
    front.pause();
    server.calls.at(-1).fail();
    await vi.advanceTimersByTimeAsync(RECONNECT_MS);
    await complete(server.calls.at(-1));
    expect(front.window()).toMatchObject({ measuredS: 4, measuredWallS: 8, w: 8 / 4, d: 0 });
  });

  it('counts nothing at the bank limit, and goes on once film is dropped', async () => {
    const { server, bank, front } = await running({ limitBytes: 1500 });
    await complete(server.calls.at(-1), { size: 1000, firstMs: 500, bodyMs: 500 });
    await complete(server.calls.at(-1), { size: 1000, firstMs: 500, bodyMs: 500 });
    expect(bank.atLimit()).toBe(true);
    await vi.advanceTimersByTimeAsync(100_000);
    bank.dropBehind(40);
    front.update({ filmAhead: 0, need: 1000 });
    await flush();
    await complete(server.calls.at(-1), { firstMs: 500, bodyMs: 500 });
    expect(front.window()).toMatchObject({
      measuredS: 12,
      measuredWallS: 3,
      w: 1.5 / 12,
      d: 1.5 / 12,
    });
  });

  it('stops it when a fetch in flight fails after F was reached, and goes on when film is wanted again', async () => {
    const { server, front } = await running();
    front.update({ filmAhead: 12, need: 12 });
    await vi.advanceTimersByTimeAsync(1000);
    server.calls.at(-1).fail();
    await vi.advanceTimersByTimeAsync(100_000);
    expect(server.calls).toHaveLength(4);
    front.update({ filmAhead: 0, need: 12 });
    await flush();
    await complete(server.calls.at(-1), { firstMs: 500, bodyMs: 500 });
    expect(front.window()).toMatchObject({
      measuredS: 4,
      measuredWallS: 2,
      w: 1.5 / 4,
      d: 0.5 / 4,
    });
  });

  it('starts it over on a move to a new position', async () => {
    const { server, front } = await running();
    await vi.advanceTimersByTimeAsync(5000);
    front.movePast(8);
    await flush();
    await complete(server.calls.at(-1), { firstMs: 500, bodyMs: 500 });
    expect(front.window()).toMatchObject({
      measuredS: 4,
      measuredWallS: 1,
      w: 0.5 / 4,
      d: 0.5 / 4,
    });
  });
});

describe('the bank front: a new position while a playlist is open', () => {
  it('neither aborts the playlist fetch nor asks for it twice, and fetches the segment after the last playlist', async () => {
    const { server, front } = setup();
    front.arm();
    await flush();
    front.update({ filmAhead: 0, need: 100 });
    front.start({ seq: 3, rung: 'r480' });
    front.movePast(8);
    await flush();
    expect(server.calls).toHaveLength(1);
    expect(server.calls[0].aborted).toBe(false);
    for (let i = 0; i < 3; i += 1) {
      await answerPlaylist(server.calls.at(-1));
      await flush();
    }
    expect(server.calls.slice(0, 3).map((c) => c.url)).toEqual(
      RUNGS.slice(0, 3).map((r) => r.playlistUrl),
    );
    expect(server.calls).toHaveLength(4);
    expect(server.calls[3].url).toBe(segUrl('r480', 9));
    expect(server.maxOpen).toBe(1);
  });
});

describe('the bank front: the window keeps everything inside 60 s, whatever the count', () => {
  it('keeps 201 four-second entries on a fast link, the first wait included', async () => {
    const { server, front } = await running({ count: 260 });
    await complete(server.calls.at(-1), { firstMs: 9000, bodyMs: 100 });
    for (let i = 0; i < 200; i += 1) await complete(server.calls.at(-1), { bodyMs: 100 });
    const w = front.window();
    expect(w.measuredS).toBe(804);
    expect(w.measuredWallS).toBeCloseTo(29.1, 6);
    expect(w.w).toBeCloseTo(9 / 804, 9);
    expect(w.d).toBeCloseTo(20.1 / 804, 9);
  });

  it('holds the last 60 s of wall time whatever the count', async () => {
    const { server, front } = await running({ count: 700, need: 100_000 });
    for (let i = 0; i < 650; i += 1) await complete(server.calls.at(-1), { bodyMs: 100 });
    const w = front.window();
    // 0.1 s each: 600 entries hold 60 s (to float rounding, so possibly one more)
    expect(w.measuredWallS).toBeGreaterThan(59.99);
    expect(w.measuredWallS).toBeLessThan(60.2);
    expect(w.measuredS).toBeGreaterThanOrEqual(2400);
    expect(w.measuredS).toBeLessThanOrEqual(2404);
  });
});

describe('the bank front: any successful answer ends a pause, and the rungs may come best first', () => {
  it('ends a pause on a successful playlist answer and goes on at once', async () => {
    const { server, front } = setup();
    front.arm();
    await flush();
    front.pause();
    expect(front.isPaused()).toBe(true);
    await answerPlaylist(server.calls[0]);
    expect(front.isPaused()).toBe(false);
    expect(server.calls).toHaveLength(2);
    expect(server.calls[1].url).toBe(RUNGS[1].playlistUrl);
  });

  it('starts above 480p and with an unknown rung at 480p when the rungs come best first', async () => {
    const bestFirst = [...RUNGS].reverse();
    const above = await running({ rungs: bestFirst, rung: 'r720' });
    expect(above.server.calls.at(-1).url).toBe(segUrl('r480', 0));
    above.front.setRung('r720');
    await complete(above.server.calls.at(-1));
    expect(above.server.calls.at(-1).url).toBe(segUrl('r480', 1));
    const unknown = await running({ rungs: bestFirst, rung: 'nope' });
    expect(unknown.server.calls.at(-1).url).toBe(segUrl('r480', 0));
  });
});

describe('the bank front: a forced flag that does not outlive its fetch now', () => {
  it("makes no request on an ordinary fetch's Retry-After retry during an outside pause, after a fetch now ended in a plain 503", async () => {
    const { server, front } = await running({ filmAhead: 12, need: 12 });
    front.fetchNow();
    await flush();
    server.calls.at(-1).status(503);
    await vi.advanceTimersByTimeAsync(RECONNECT_MS);
    expect(server.calls).toHaveLength(4);
    front.update({ filmAhead: 0, need: 12 });
    await flush();
    expect(server.calls).toHaveLength(5);
    server.calls.at(-1).status(503, '1');
    await flush();
    front.pause();
    await vi.advanceTimersByTimeAsync(RECONNECT_MS - 1);
    expect(server.calls).toHaveLength(5);
    await vi.advanceTimersByTimeAsync(1);
    expect(server.calls).toHaveLength(6);
  });

  it("keeps an ordinary fetch's retry from going ahead at F after a fetch now", async () => {
    const { server, front } = await running({ filmAhead: 12, need: 12 });
    front.fetchNow();
    await flush();
    await complete(server.calls.at(-1));
    front.update({ filmAhead: 0, need: 12 });
    await flush();
    server.calls.at(-1).status(503, '1');
    await flush();
    front.update({ filmAhead: 12, need: 12 });
    await vi.advanceTimersByTimeAsync(RECONNECT_MS);
    expect(server.calls).toHaveLength(5);
  });
});

describe('the bank front: a subscriber whose fetch is dropped', () => {
  it('hears onAbort once on stop, and is forgotten', async () => {
    const { front } = await running();
    const log = [];
    front.subscribe(0, { onAbort: () => log.push('abort'), onDone: () => log.push('done') });
    front.subscribe(7, { onAbort: () => log.push('abort7') });
    front.stop();
    front.stop();
    expect(log).toEqual(['abort', 'abort7']);
  });

  it('hears onAbort on a move past its position, but not a subscriber of a later one', async () => {
    const { front } = await running();
    const log = [];
    front.subscribe(0, { onAbort: () => log.push('abort0') });
    front.subscribe(3, { onAbort: () => log.push('abort3') });
    front.subscribe(9, { onAbort: () => log.push('abort9') });
    front.movePast(3);
    await flush();
    expect(log).toEqual(['abort0', 'abort3']);
  });

  it('hears onAbort on a start elsewhere, but not a subscriber of the new start position', async () => {
    const { front } = await running();
    const log = [];
    front.subscribe(0, { onAbort: () => log.push('abort0') });
    front.subscribe(5, { onAbort: () => log.push('abort5') });
    front.start({ seq: 5, rung: 'r480' });
    await flush();
    expect(log).toEqual(['abort0']);
  });

  it('does not stop a subscriber with no onAbort handler', async () => {
    const { front } = await running();
    front.subscribe(0, {});
    expect(() => front.stop()).not.toThrow();
  });
});

describe('the bank front: a fetch now at the bank limit', () => {
  it('makes one request, hands the segment over, stays at the limit and asks for no more', async () => {
    const { server, bank, front } = await running({ limitBytes: 500 });
    const done = [];
    front.subscribe(0, { onDone: (segment) => done.push(segment) });
    front.fetchNow();
    await flush();
    expect(server.calls).toHaveLength(4);
    await complete(server.calls.at(-1), { size: 1000 });
    expect(done).toHaveLength(1);
    expect(done[0]).not.toBeNull();
    expect(done[0].bytes.byteLength).toBe(1000);
    expect(bank.atLimit()).toBe(true);
    await vi.advanceTimersByTimeAsync(RECONNECT_MS * 2);
    expect(server.calls).toHaveLength(4);
  });

  it('retries a failed fetch now at the limit, exactly once more', async () => {
    const { server, front } = await running({ limitBytes: 1 });
    await complete(server.calls.at(-1), { size: 10 });
    front.subscribe(1, {});
    front.fetchNow();
    await flush();
    server.calls.at(-1).fail();
    await vi.advanceTimersByTimeAsync(2000);
    expect(server.calls).toHaveLength(6);
    expect(server.calls[5].url).toBe(segUrl('r480', 1));
    await vi.advanceTimersByTimeAsync(RECONNECT_MS * 2);
    expect(server.calls).toHaveLength(6);
  });
});

describe('the bank front: forced retries need someone waiting', () => {
  it('makes no forced retry at F once the subscriber has gone and the fetch fails again', async () => {
    const { server, front } = await running({ filmAhead: 12, need: 12 });
    const off = front.subscribe(0, {});
    front.fetchNow();
    await flush();
    server.calls.at(-1).fail();
    await vi.advanceTimersByTimeAsync(2000);
    expect(server.calls).toHaveLength(5);
    off();
    server.calls.at(-1).fail();
    await vi.advanceTimersByTimeAsync(RECONNECT_MS * 4);
    expect(server.calls).toHaveLength(5);
  });

  it('makes no forced retry when the subscriber goes while the retry is pending', async () => {
    const { server, front } = await running({ filmAhead: 12, need: 12 });
    const off = front.subscribe(0, {});
    front.fetchNow();
    await flush();
    server.calls.at(-1).fail();
    await vi.advanceTimersByTimeAsync(500);
    off();
    await vi.advanceTimersByTimeAsync(RECONNECT_MS * 4);
    expect(server.calls).toHaveLength(4);
  });

  it('makes no forced request before RECONNECT_MS from the pause once the subscriber has gone', async () => {
    const { server, front } = await running();
    server.calls.at(-1).status(503);
    await vi.advanceTimersByTimeAsync(1000);
    const off = front.subscribe(0, {});
    front.fetchNow();
    await flush();
    server.calls.at(-1).fail();
    await vi.advanceTimersByTimeAsync(2000);
    expect(server.calls).toHaveLength(6);
    off();
    server.calls.at(-1).fail();
    await vi.advanceTimersByTimeAsync(RECONNECT_MS - 3000 - 1);
    expect(server.calls).toHaveLength(6);
    await vi.advanceTimersByTimeAsync(1);
    expect(server.calls).toHaveLength(7);
  });
});

describe('the bank front: a start at the position a subscriber waits on', () => {
  it('serves the subscriber with exactly one new request at F', async () => {
    const { server, front } = await running({ filmAhead: 12, need: 12 });
    const done = [];
    front.subscribe(0, { onDone: (segment) => done.push(segment.seq) });
    front.fetchNow();
    await flush();
    const old = server.calls.at(-1);
    front.start({ seq: 0, rung: 'r480' });
    await flush();
    expect(old.aborted).toBe(true);
    expect(server.calls).toHaveLength(5);
    expect(server.maxOpen).toBe(1);
    await complete(server.calls.at(-1));
    expect(done).toEqual([0]);
    await vi.advanceTimersByTimeAsync(RECONNECT_MS * 2);
    expect(server.calls).toHaveLength(5);
  });

  it('serves the subscriber with exactly one new request when film is wanted', async () => {
    const { server, front } = await running();
    const done = [];
    front.subscribe(0, { onDone: (segment) => done.push(segment.seq) });
    const old = server.calls.at(-1);
    front.start({ seq: 0, rung: 'r480' });
    await flush();
    expect(old.aborted).toBe(true);
    expect(server.calls).toHaveLength(5);
    expect(server.calls[4].url).toBe(segUrl('r480', 0));
    expect(server.maxOpen).toBe(1);
    await complete(server.calls.at(-1));
    expect(done).toEqual([0]);
  });
});

describe('the bank front: unsubscribing, starting and callbacks that change the front', () => {
  it("keeps a live subscriber's forced retry when a stale unsubscribe is called", async () => {
    const { server, front } = await running({ filmAhead: 12, need: 12 });
    const offA = front.subscribe(0, { onAbort: () => {} });
    front.start({ seq: 5, rung: 'r480' }); // drops A
    front.start({ seq: 0, rung: 'r480' });
    const done = [];
    front.subscribe(0, { onDone: (segment) => done.push(segment.seq) });
    front.fetchNow();
    await flush();
    offA();
    offA();
    server.calls.at(-1).fail();
    await vi.advanceTimersByTimeAsync(2000);
    expect(server.calls).toHaveLength(5);
    await complete(server.calls.at(-1));
    expect(done).toEqual([0]);
  });

  it('keeps the live subscriber when an old unsubscribe is called after it subscribed', async () => {
    const { server, front } = await running({ filmAhead: 12, need: 12 });
    const offA = front.subscribe(0, {});
    front.start({ seq: 3, rung: 'r480' });
    front.start({ seq: 0, rung: 'r480' });
    const done = [];
    front.subscribe(0, { onDone: () => done.push('done') });
    offA();
    front.fetchNow();
    await flush();
    await complete(server.calls.at(-1));
    expect(done).toEqual(['done']);
  });

  it('asks for a playlist again at once when a start comes while its retry is pending', async () => {
    const { server, front } = setup();
    front.arm();
    await flush();
    server.calls[0].fail();
    await flush();
    front.update({ filmAhead: 0, need: 100 });
    front.subscribe(0, {});
    front.start({ seq: 0, rung: 'r480' });
    await flush();
    expect(server.calls).toHaveLength(2);
    expect(server.calls[1].url).toBe(RUNGS[0].playlistUrl);
    expect(server.maxOpen).toBe(1);
  });

  it('makes no request on a start at the position of a subscriber while paused, until RECONNECT_MS from the pause', async () => {
    const { server, front } = await running();
    server.calls.at(-1).status(503);
    await vi.advanceTimersByTimeAsync(1000);
    front.subscribe(0, {});
    front.start({ seq: 0, rung: 'r480' });
    await vi.advanceTimersByTimeAsync(RECONNECT_MS - 1000 - 1);
    expect(server.calls).toHaveLength(4);
    await vi.advanceTimersByTimeAsync(1);
    expect(server.calls).toHaveLength(5);
  });

  it("gives a listener nothing but onAbort when another listener's onDone stops the front", async () => {
    const { server, front } = await running();
    const log = [];
    front.subscribe(0, { onDone: () => front.stop() });
    front.subscribe(0, { onDone: () => log.push('done'), onAbort: () => log.push('abort') });
    await complete(server.calls.at(-1));
    expect(log).toEqual(['abort']);
  });

  it("gives a listener nothing but onAbort when another listener's onServerAnswer starts elsewhere", async () => {
    const { server, front } = await running();
    const log = [];
    front.subscribe(0, { onServerAnswer: () => front.start({ seq: 9, rung: 'r480' }) });
    front.subscribe(0, {
      onServerAnswer: (status) => log.push(status),
      onAbort: () => log.push('abort'),
    });
    server.calls.at(-1).status(500);
    await flush();
    expect(log).toEqual(['abort']);
  });

  it('keeps a listener that subscribes from inside onDone, for the next fetch of the position', async () => {
    const { server, front } = await running();
    const log = [];
    front.subscribe(0, {
      onDone: () =>
        front.subscribe(0, { onDone: () => log.push('again'), onAbort: () => log.push('abort') }),
    });
    await complete(server.calls.at(-1));
    expect(log).toEqual([]);
    front.start({ seq: 0, rung: 'r480' });
    await flush();
    await complete(server.calls.at(-1));
    expect(log).toEqual(['again']);
  });

  it('tells a listener that subscribed from inside onDone when the position is dropped', async () => {
    const { server, front } = await running();
    const log = [];
    front.subscribe(0, {
      onDone: () => front.subscribe(0, { onAbort: () => log.push('abort') }),
    });
    await complete(server.calls.at(-1));
    front.start({ seq: 5, rung: 'r480' });
    expect(log).toEqual(['abort']);
  });
});

describe('the bank front: what keeps forced retries and replays honest', () => {
  it('makes no retry after a subscriber unsubscribes during its fetch now and another subscribes without one', async () => {
    const { server, front } = await running({ filmAhead: 12, need: 12 });
    const off = front.subscribe(0, {});
    front.fetchNow();
    await flush();
    off();
    front.subscribe(0, {});
    server.calls.at(-1).fail();
    await vi.advanceTimersByTimeAsync(RECONNECT_MS * 4);
    expect(server.calls).toHaveLength(4);
  });

  it('makes no retry of a fetch now that nobody subscribed to', async () => {
    const { server, front } = await running({ filmAhead: 12, need: 12 });
    front.fetchNow();
    await flush();
    server.calls.at(-1).fail();
    await vi.advanceTimersByTimeAsync(RECONNECT_MS * 4);
    expect(server.calls).toHaveLength(4);
  });

  it("replays neither a 500 nor a 503 with Retry-After 0, and the retry's own events still arrive", async () => {
    const other = await running();
    other.server.calls.at(-1).status(500);
    await flush();
    const log = [];
    other.front.subscribe(0, {
      onServerAnswer: (s) => log.push(s),
      onDone: () => log.push('done'),
    });
    expect(log).toEqual([]);
    await vi.advanceTimersByTimeAsync(2000);
    await complete(other.server.calls.at(-1));
    expect(log).toEqual(['done']);
    const zero = await running();
    zero.server.calls.at(-1).status(503, '0');
    await flush();
    const late = [];
    zero.front.subscribe(0, { onServerAnswer: (s) => late.push(s) });
    expect(late).toEqual([]);
  });
});

describe('the bank front: round four of the hooks', () => {
  it("gives no onAbort to a listener that has had its onDone when a later one's onDone stops the front", async () => {
    const { server, front } = await running();
    const log = [];
    front.subscribe(0, { onDone: () => log.push('A done'), onAbort: () => log.push('A abort') });
    front.subscribe(0, { onDone: () => front.stop(), onAbort: () => log.push('B abort') });
    front.subscribe(0, { onDone: () => log.push('C done'), onAbort: () => log.push('C abort') });
    await complete(server.calls.at(-1));
    expect(log).toEqual(['A done', 'C abort']);
  });

  it("gives no onAbort to a listener that has had its onDone when a later one's onDone starts elsewhere", async () => {
    const { server, front } = await running();
    const log = [];
    front.subscribe(0, { onDone: () => log.push('A done'), onAbort: () => log.push('A abort') });
    front.subscribe(0, { onDone: () => front.start({ seq: 9, rung: 'r480' }) });
    await complete(server.calls.at(-1));
    expect(log).toEqual(['A done']);
  });

  it('clears forced when a retry timer finds nobody waiting, so a later start at a subscribed position obeys the pause', async () => {
    const { server, front } = await running({ filmAhead: 12, need: 12 });
    front.subscribe(5, {});
    front.fetchNow();
    await flush();
    server.calls.at(-1).fail();
    await vi.advanceTimersByTimeAsync(2000);
    expect(server.calls).toHaveLength(4);
    front.pause();
    front.subscribe(0, {});
    front.start({ seq: 0, rung: 'r480' });
    await vi.advanceTimersByTimeAsync(RECONNECT_MS - 1);
    expect(server.calls).toHaveLength(4);
    await vi.advanceTimersByTimeAsync(1);
    expect(server.calls).toHaveLength(4); // at F: the pause ending asks for nothing
  });

  it('keeps a start at another position from carrying a fetch now on, while paused', async () => {
    const { server, front } = await running();
    server.calls.at(-1).status(503);
    await vi.advanceTimersByTimeAsync(1000);
    front.subscribe(0, {});
    front.fetchNow();
    await flush();
    expect(server.calls).toHaveLength(5);
    front.subscribe(5, {});
    front.start({ seq: 5, rung: 'r480' });
    await vi.advanceTimersByTimeAsync(RECONNECT_MS - 1000 - 1);
    expect(server.calls).toHaveLength(5);
    await vi.advanceTimersByTimeAsync(1);
    expect(server.calls).toHaveLength(6);
    expect(server.calls[5].url).toBe(segUrl('r480', 5));
  });

  it("stops delivering an event to a listener that another listener's callback has unsubscribed", async () => {
    const { server, front } = await running();
    const log = [];
    let offB = () => {};
    front.subscribe(0, { onFirstByte: () => offB() });
    offB = front.subscribe(0, {
      onFirstByte: () => log.push('B firstByte'),
      onProgress: () => log.push('B progress'),
      onDone: () => log.push('B done'),
    });
    await complete(server.calls.at(-1));
    expect(log).toEqual([]);
  });
  it("hears no onDone in a listener that another's onDone unsubscribed", async () => {
    const { server, front } = await running();
    const log = [];
    let offB = () => {};
    front.subscribe(0, { onDone: () => offB() });
    offB = front.subscribe(0, { onDone: () => log.push('B done') });
    front.subscribe(0, { onDone: () => log.push('C done') });
    await complete(server.calls.at(-1));
    expect(log).toEqual(['C done']);
  });

  it('keeps a listener that subscribes inside onDone after the set has emptied', async () => {
    const { server, front } = await running();
    const log = [];
    let offB = () => {};
    front.subscribe(0, {
      onDone: () => {
        offB();
        front.subscribe(0, { onAbort: () => log.push('X abort') });
      },
    });
    offB = front.subscribe(0, {});
    await complete(server.calls.at(-1));
    front.stop();
    expect(log).toEqual(['X abort']);
  });
});

describe('the bank front: callbacks that call back in from onAbort', () => {
  it('fetches the new position, not the dropped one, when onAbort calls fetchNow after a start', async () => {
    const { server, front } = await running();
    front.subscribe(0, { onAbort: () => front.fetchNow() });
    front.start({ seq: 9, rung: 'r480' });
    await flush();
    expect(server.calls).toHaveLength(5);
    expect(server.calls[4].url).toBe(segUrl('r480', 9));
    expect(server.maxOpen).toBe(1);
    await complete(server.calls.at(-1));
    expect(front.position().seq).toBe(10);
  });

  it('fetches the position after a move, not the dropped one, when onAbort calls fetchNow', async () => {
    const { server, front } = await running();
    front.subscribe(0, { onAbort: () => front.fetchNow() });
    front.movePast(8);
    await flush();
    expect(server.calls).toHaveLength(5);
    expect(server.calls[4].url).toBe(segUrl('r480', 9));
  });

  it('makes no request when onAbort calls fetchNow after a stop', async () => {
    const { server, front } = await running();
    front.subscribe(0, { onAbort: () => front.fetchNow() });
    front.stop();
    await flush();
    expect(server.calls).toHaveLength(4);
  });

  it('tells each listener once when one onAbort calls stop while a start drops several positions', async () => {
    const { front } = await running();
    const log = [];
    front.subscribe(0, {
      onAbort: () => {
        log.push(0);
        front.stop();
      },
    });
    front.subscribe(5, { onAbort: () => log.push(5) });
    front.start({ seq: 9, rung: 'r480' });
    expect(log).toEqual([0, 5]);
  });

  it('tells each listener once when one onAbort calls stop during a stop', async () => {
    const { front } = await running();
    const log = [];
    front.subscribe(0, {
      onAbort: () => {
        log.push(0);
        front.stop();
      },
    });
    front.subscribe(5, { onAbort: () => log.push(5) });
    front.stop();
    expect(log).toEqual([0, 5]);
  });

  it('does not tell a listener that an earlier onAbort unsubscribed', async () => {
    const { front } = await running();
    const log = [];
    let offB = () => {};
    front.subscribe(0, { onAbort: () => offB() });
    offB = front.subscribe(0, { onAbort: () => log.push('B abort') });
    front.stop();
    expect(log).toEqual([]);
  });
  it('makes no forced request while paused when an onAbort unsubscribes the kept listener', async () => {
    const { server, front } = await running();
    server.calls.at(-1).status(503);
    await vi.advanceTimersByTimeAsync(1000);
    const off = front.subscribe(0, {});
    front.fetchNow();
    await flush();
    expect(server.calls).toHaveLength(5);
    front.subscribe(3, { onAbort: () => off() });
    front.start({ seq: 0, rung: 'r480' });
    await flush();
    expect(server.calls).toHaveLength(5);
  });

  it('makes no forced request while paused when an onAbort moves the front', async () => {
    const { server, front } = await running();
    server.calls.at(-1).status(503);
    await vi.advanceTimersByTimeAsync(1000);
    front.subscribe(0, {});
    front.fetchNow();
    await flush();
    expect(server.calls).toHaveLength(5);
    front.subscribe(3, { onAbort: () => front.movePast(4) });
    front.start({ seq: 0, rung: 'r480' });
    await flush();
    expect(server.calls).toHaveLength(5);
  });
});

describe('the bank front: boundary copies', () => {
  const lastUrl = (server) => server.calls.at(-1).url;

  it('fetches the segment before a new rung at that rung, once, before moving on', async () => {
    const { server, bank, front } = await running({ rung: 'r240' });
    front.setRung('r360');
    await complete(server.calls.at(-1)); // 0 at 240p
    expect(lastUrl(server)).toBe(segUrl('r360', 1));
    await complete(server.calls.at(-1)); // 1 at 360p
    expect(lastUrl(server)).toBe(segUrl('r360', 0));
    expect(front.position().seq).toBe(2);
    await complete(server.calls.at(-1), { size: 321 }); // the boundary copy of 0
    expect(bank.getSegment(0).rung).toBe('r240');
    expect(bank.getBoundary(0, 'r360').bytes.byteLength).toBe(321);
    expect(lastUrl(server)).toBe(segUrl('r360', 2));
    await complete(server.calls.at(-1));
    expect(lastUrl(server)).toBe(segUrl('r360', 3)); // never again for 1 or 2
    expect(server.calls.filter((c) => c.url === segUrl('r360', 0))).toHaveLength(1);
  });

  it('fetches none when the rung did not change, or when the segment before is not held', async () => {
    const same = await running({ rung: 'r360' });
    await complete(same.server.calls.at(-1));
    await complete(same.server.calls.at(-1));
    expect(lastUrl(same.server)).toBe(segUrl('r360', 2));
    const far = await running({ seq: 5, rung: 'r360' }); // 4 is not held
    await complete(far.server.calls.at(-1));
    expect(lastUrl(far.server)).toBe(segUrl('r360', 6));
  });

  it('skips it at the limit', async () => {
    const { server, bank, front } = await running({ rung: 'r240', limitBytes: 1500 });
    front.setRung('r360');
    await complete(server.calls.at(-1), { size: 1000 });
    const before = server.calls.length;
    await complete(server.calls.at(-1), { size: 1000 }); // over the limit
    expect(bank.atLimit()).toBe(true);
    expect(server.calls).toHaveLength(before);
  });

  it('gives way to a request waiting on the next position, and goes after it', async () => {
    const { server, bank, front } = await running({ rung: 'r240' });
    front.setRung('r360');
    await complete(server.calls.at(-1));
    await complete(server.calls.at(-1)); // 1 at 360p; the boundary copy of 0 is now in flight
    const boundary = server.calls.at(-1);
    expect(boundary.url).toBe(segUrl('r360', 0));
    const done = vi.fn();
    front.subscribe(2, { onDone: done });
    front.fetchNow();
    await flush();
    expect(boundary.aborted).toBe(true);
    expect(lastUrl(server)).toBe(segUrl('r360', 2));
    await complete(server.calls.at(-1));
    expect(done).toHaveBeenCalledTimes(1);
    expect(lastUrl(server)).toBe(segUrl('r360', 0)); // still due
    await complete(server.calls.at(-1));
    expect(bank.getBoundary(0, 'r360')).not.toBeNull();
  });

  it('tells no subscriber of the boundary fetch and retries it after a network error', async () => {
    const { server, bank, front } = await running({ rung: 'r240' });
    front.setRung('r360');
    await complete(server.calls.at(-1));
    const events = [];
    front.subscribe(0, {
      onDone: () => events.push('done'),
      onFirstByte: () => events.push('byte'),
    });
    await complete(server.calls.at(-1));
    server.calls.at(-1).fail();
    await vi.advanceTimersByTimeAsync(2000);
    expect(lastUrl(server)).toBe(segUrl('r360', 0));
    await complete(server.calls.at(-1));
    expect(events).toEqual([]);
    expect(bank.getBoundary(0, 'r360')).not.toBeNull();
  });
});

describe('the bank front: finish', () => {
  it('lets the fetch in flight run to its end for its subscriber, stores it, and makes no other request', async () => {
    const { server, bank, front } = await running();
    const done = vi.fn();
    front.subscribe(0, { onDone: done });
    expect(front.fetching()).toBe(0);
    front.finish();
    expect(front.isFinished()).toBe(false);
    await complete(server.calls.at(-1), { size: 42 });
    expect(done).toHaveBeenCalledTimes(1);
    expect(bank.getSegment(0).bytes.byteLength).toBe(42);
    expect(front.isFinished()).toBe(true);
    front.update({ filmAhead: 0, need: 1000 });
    front.start({ seq: 9, rung: 'r480' });
    front.fetchNow();
    await vi.advanceTimersByTimeAsync(60_000);
    expect(server.calls).toHaveLength(4); // three playlists and segment 0
  });

  it('makes no request at all with nothing in flight', async () => {
    const { server, front } = await running({ need: 0 });
    front.finish();
    expect(front.isFinished()).toBe(true);
    front.update({ filmAhead: 0, need: 1000 });
    front.fetchNow();
    await vi.advanceTimersByTimeAsync(60_000);
    expect(server.calls).toHaveLength(3);
  });

  it('adds no boundary copy after the fetch that was in flight', async () => {
    const { server, front } = await running({ rung: 'r240' });
    front.setRung('r360');
    await complete(server.calls.at(-1));
    front.finish();
    await complete(server.calls.at(-1)); // 1 at 360p: a boundary copy of 0 would be due
    await vi.advanceTimersByTimeAsync(60_000);
    expect(server.calls).toHaveLength(5);
  });

  it('retries a 503 with Retry-After once while a subscriber waits, then stops', async () => {
    const { server, bank, front } = await running();
    const done = vi.fn();
    front.subscribe(0, { onDone: done });
    front.finish();
    server.calls.at(-1).status(503, '1');
    await flush();
    expect(front.isFinished()).toBe(false);
    await vi.advanceTimersByTimeAsync(1000);
    expect(server.calls).toHaveLength(5);
    expect(server.calls.at(-1).url).toBe(segUrl('r480', 0));
    await complete(server.calls.at(-1));
    expect(done).toHaveBeenCalledTimes(1);
    expect(bank.hasSegment(0)).toBe(true);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(server.calls).toHaveLength(5);
    expect(front.isFinished()).toBe(true);
  });

  it('makes no retry when the subscriber cancels, or when none waits', async () => {
    const waiting = await running();
    const off = waiting.front.subscribe(0, {});
    waiting.front.finish();
    waiting.server.calls.at(-1).status(503, '1');
    await flush();
    off();
    await vi.advanceTimersByTimeAsync(60_000);
    expect(waiting.server.calls).toHaveLength(4);
    const nobody = await running();
    nobody.front.finish();
    nobody.server.calls.at(-1).status(503, '1');
    await vi.advanceTimersByTimeAsync(60_000);
    expect(nobody.server.calls).toHaveLength(4);
    expect(nobody.front.isFinished()).toBe(true);
    const lost = await running();
    lost.front.subscribe(0, {});
    lost.front.finish();
    lost.server.calls.at(-1).fail();
    await vi.advanceTimersByTimeAsync(2000);
    expect(lost.server.calls).toHaveLength(5); // a network-error retry while someone waits
    const quiet = await running();
    quiet.front.finish();
    quiet.server.calls.at(-1).fail();
    await vi.advanceTimersByTimeAsync(60_000);
    expect(quiet.server.calls).toHaveLength(4);
  });

  it('is cut whole by stop', async () => {
    const { server, front } = await running();
    front.finish();
    front.stop();
    expect(server.calls.at(-1).aborted).toBe(true);
  });
});

describe('the bank front: boundary copies, round two', () => {
  const lastUrl = (server) => server.calls.at(-1).url;
  // Segment 1 fetched at 360p after 0 at 240p, with film no longer wanted: the copy of 0 is due, not started.
  async function due() {
    const t = await running({ rung: 'r240' });
    t.front.setRung('r360');
    await complete(t.server.calls.at(-1)); // 0 at 240p; 1 at 360p in flight
    t.front.update({ filmAhead: 0, need: 0 });
    await complete(t.server.calls.at(-1)); // 1 at 360p
    await flush();
    expect(t.server.calls).toHaveLength(5); // 3 playlists, 0 and 1: no copy yet
    return t;
  }

  it('forgets a due copy at a start or a move', async () => {
    const started = await due();
    started.front.start({ seq: 10, rung: 'r360' });
    started.front.update({ filmAhead: 0, need: 1000 });
    await flush();
    expect(lastUrl(started.server)).toBe(segUrl('r360', 10));
    expect(started.server.calls.filter((c) => c.url === segUrl('r360', 0))).toHaveLength(0);
    const moved = await due();
    moved.front.movePast(10);
    moved.front.update({ filmAhead: 0, need: 1000 });
    await flush();
    expect(lastUrl(moved.server)).toBe(segUrl('r360', 11));
    expect(moved.server.calls.filter((c) => c.url === segUrl('r360', 0))).toHaveLength(0);
  });

  it('keeps an out-of-order fetch out of the window and out of the copies, but reports its rate', async () => {
    const { server, front, bank, seen } = await running({ rung: 'r360' });
    await complete(server.calls.at(-1), { size: 100_000, firstMs: 10, bodyMs: 1000 });
    const before = front.window();
    expect(before.measuredS).toBe(4);
    const kbps = seen.kbps.length;
    front.update({ filmAhead: 0, need: 0 });
    front.start({ seq: 10, rung: 'r480', outOfOrder: true });
    front.subscribe(10, {});
    front.fetchNow();
    await flush();
    await complete(server.calls.at(-1), { size: 100_000, firstMs: 10, bodyMs: 1000 });
    expect(bank.getSegment(10).rung).toBe('r480');
    expect(front.window()).toEqual(before); // not reset by the other rung, nothing added
    expect(seen.kbps).toHaveLength(kbps + 1);
    front.update({ filmAhead: 0, need: 1000 });
    await flush();
    expect(lastUrl(server)).toBe(segUrl('r480', 11)); // no copy of 9 (not held) or anything behind
  });

  it('keeps a boundary copy out of the window', async () => {
    const { server, front } = await running({ rung: 'r240' });
    front.setRung('r360');
    await complete(server.calls.at(-1));
    await complete(server.calls.at(-1), { size: 100_000, firstMs: 10, bodyMs: 1000 }); // 1 at 360p
    const before = front.window();
    expect(lastUrl(server)).toBe(segUrl('r360', 0));
    await complete(server.calls.at(-1), { size: 100_000, firstMs: 10, bodyMs: 1000 }); // the copy of 0
    expect(front.window()).toEqual(before);
  });

  it('does not hold a waiter up with the retry wait of a failed boundary copy, and keeps it due', async () => {
    const { server, front } = await running({ rung: 'r240' });
    front.setRung('r360');
    await complete(server.calls.at(-1));
    await complete(server.calls.at(-1)); // 1 at 360p; the copy of 0 in flight
    server.calls.at(-1).fail();
    await flush();
    const callsBefore = server.calls.length;
    const done = vi.fn();
    front.subscribe(2, { onDone: done });
    front.fetchNow();
    await flush();
    expect(server.calls).toHaveLength(callsBefore + 1); // at once, not after the 2 s retry wait
    expect(lastUrl(server)).toBe(segUrl('r360', 2));
    await complete(server.calls.at(-1));
    expect(done).toHaveBeenCalledTimes(1);
    expect(front.boundaryFetching()).toBeNull(); // the front moved on: the copy is no longer worth it
  });

  it('does not let a failed boundary copy raise the next position backoff', async () => {
    const { server, front } = await running({ rung: 'r240' });
    front.setRung('r360');
    await complete(server.calls.at(-1));
    await complete(server.calls.at(-1));
    server.calls.at(-1).status(503, '1'); // the copy: a 503 with Retry-After
    await flush();
    front.subscribe(2, {});
    front.fetchNow();
    await flush();
    server.calls.at(-1).status(503, '1');
    await vi.advanceTimersByTimeAsync(1000); // the first backoff step: 1 s
    expect(server.calls.filter((c) => c.url === segUrl('r360', 2))).toHaveLength(2);
  });

  it('tells a boundary subscriber when the copy lands, and keeps the copy in flight for it', async () => {
    const { server, bank, front } = await running({ rung: 'r240' });
    front.setRung('r360');
    await complete(server.calls.at(-1));
    await complete(server.calls.at(-1));
    expect(front.boundaryFetching()).toEqual({ seq: 0, rung: 'r360' });
    const done = vi.fn();
    const off = front.subscribeBoundary(0, { onDone: done });
    front.subscribe(2, {});
    front.fetchNow(); // a waiter on the next position does not cut a copy hls.js waits on
    await flush();
    expect(server.calls.at(-1).aborted).toBe(false);
    await complete(server.calls.at(-1), { size: 31 });
    expect(done).toHaveBeenCalledTimes(1);
    expect(done.mock.calls[0][0].bytes.byteLength).toBe(31);
    expect(bank.getBoundary(0, 'r360')).not.toBeNull();
    expect(lastUrl(server)).toBe(segUrl('r360', 2)); // then the waiter's fetch goes
    expect(front.boundaryFetching()).toBeNull();
    off();
  });

  it('tells a boundary subscriber when the copy is cut', async () => {
    const { server, front } = await running({ rung: 'r240' });
    front.setRung('r360');
    await complete(server.calls.at(-1));
    await complete(server.calls.at(-1));
    const abort = vi.fn();
    front.subscribeBoundary(0, { onAbort: abort });
    front.start({ seq: 9, rung: 'r360' });
    expect(abort).toHaveBeenCalledTimes(1);
    const again = await running({ rung: 'r240' });
    again.front.setRung('r360');
    await complete(again.server.calls.at(-1));
    await complete(again.server.calls.at(-1));
    const stop = vi.fn();
    again.front.subscribeBoundary(0, { onAbort: stop });
    again.front.stop();
    expect(stop).toHaveBeenCalledTimes(1);
  });

  it('after finish, a failed boundary copy is not tried again', async () => {
    const { server, front } = await running({ rung: 'r240' });
    front.setRung('r360');
    await complete(server.calls.at(-1));
    await complete(server.calls.at(-1));
    server.calls.at(-1).fail(); // the copy fails: a retry is pending
    await flush();
    const calls = server.calls.length;
    front.finish();
    await vi.advanceTimersByTimeAsync(60_000);
    expect(server.calls).toHaveLength(calls);
    expect(front.isFinished()).toBe(true);
  });

  it('reports a fetch in flight or a retry pending as fetching, and nothing once it ended', async () => {
    const { server, front } = await running();
    const first = front.subscribe(0, {});
    expect(front.fetching()).toBe(0);
    server.calls.at(-1).fail();
    await flush();
    expect(front.fetching()).toBe(0); // the retry wait
    front.finish();
    first(); // nobody waits any more: the retry is not made
    await vi.advanceTimersByTimeAsync(2000);
    expect(front.fetching()).toBeNull();
  });
});
