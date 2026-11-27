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
async function running({ seq = 0, rung = 'r480', filmAhead = 0, need = 1000, ...options } = {}) {
  const t = setup(options);
  t.front.arm();
  for (let i = 0; i < 3; i += 1) {
    await flush();
    await answerPlaylist(t.server.calls.at(-1));
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

describe('the bank front: pause and position', () => {
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

describe('the bank front: pause time counted once, and only while film is wanted', () => {
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
