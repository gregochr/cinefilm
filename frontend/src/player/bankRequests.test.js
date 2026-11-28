import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createBankFront } from './bankFront.js';
import { BANK_RUNG_TEXT, createBankRequests } from './bankRequests.js';
import { RECONNECT_MS } from './reconnect.js';
import { createSegmentBank } from './segmentBank.js';

const RUNGS = [
  { id: 'r240', height: 240, kbps: 400, playlistUrl: '/api/s/1/r240/index.m3u8' },
  { id: 'r360', height: 360, kbps: 900, playlistUrl: '/api/s/1/r360/index.m3u8' },
  { id: 'r480', height: 480, kbps: 1800, playlistUrl: '/api/s/1/r480/index.m3u8' },
  { id: 'r720', height: 720, kbps: 4000, playlistUrl: '/api/s/1/r720/index.m3u8' },
];
const T0 = 1_000_000;
const L480 = 2; // hls.js's level index of r480

const levelOfRung = (id) => RUNGS.findIndex((r) => r.id === id);
const rungOfLevel = (level) => (level <= 2 ? RUNGS[level].id : null);
const segUrl = (rung, n) => new URL(`/api/s/1/${rung}/seg-${n}.ts`, globalThis.location.href).href;
const playlistText = () =>
  `#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:0\n${Array.from(
    { length: 60 },
    (_, i) => `#EXTINF:4.000,\nseg-${i}.ts`,
  ).join('\n')}\n#EXT-X-ENDLIST\n`;

// A fake fetch: each call stays open until the test answers it.
function makeServer() {
  const server = { calls: [] };
  server.fetch = (url, init) =>
    new Promise((resolve, reject) => {
      const call = { url, init, aborted: false };
      const waiting = [];
      init.signal.addEventListener('abort', () => {
        call.aborted = true;
        const err = new DOMException('aborted', 'AbortError');
        reject(err);
        waiting.splice(0).forEach((w) => w.rej(err));
      });
      call.status = (status, retryAfter = null) =>
        resolve({ status, headers: { get: (n) => (n === 'Retry-After' ? retryAfter : null) } });
      call.fail = () => reject(new TypeError('Failed to fetch'));
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
            waiting.splice(0).forEach((w) => w.res({ done: true }));
          },
        };
      };
      server.calls.push(call);
    });
  return server;
}

const flush = () => vi.advanceTimersByTimeAsync(0);

async function complete(call, { size = 1000, firstMs = 0, bodyMs = 0 } = {}) {
  await vi.advanceTimersByTimeAsync(firstMs);
  const r = call.respond();
  r.send(new Uint8Array(size).fill(7));
  await vi.advanceTimersByTimeAsync(bodyMs);
  r.end();
  await flush();
}

async function answerPlaylist(call) {
  const r = call.respond();
  r.send(new TextEncoder().encode(playlistText()));
  r.end();
  await flush();
}

// The real store and front over a fake server, the decider on top. The front is armed with its playlists
// answered and started at `seq`/`rung`; `need` 0 (the default) means it wants no film until `update` says so, and
// null that B5b has not called `update` at all.
async function setup({
  seq = 0,
  rung = 'r480',
  need = 0,
  limitBytes,
  mode = 'on',
  ...options
} = {}) {
  const server = makeServer();
  const bank = createSegmentBank(limitBytes === undefined ? {} : { limitBytes });
  const seen = { stopped: 0, lost: 0, release: [], wrong: [] };
  const front = createBankFront({
    rungs: RUNGS,
    bank,
    fetch: server.fetch,
    onStopped: () => {
      seen.stopped += 1;
    },
    onSessionLost: () => {
      seen.lost += 1;
    },
  });
  const requests = createBankRequests({
    bank,
    front,
    levelOfRung,
    rungOfLevel,
    wrongRung: (level) => seen.wrong.push(level),
    onRelease: () => seen.release.push('release'),
    ...options,
  });
  front.arm();
  for (let i = 0; i < 3; i += 1) {
    await flush();
    await answerPlaylist(server.calls.at(-1));
  }
  if (need !== null) requests.update({ filmAhead: 0, need });
  front.start({ seq, rung });
  requests.setMode(mode);
  await flush();
  return { server, bank, front, requests, seen };
}

// hls.js's request for `sn` at `level`, with a reply that records what it is told.
function ask(requests, sn, level = L480) {
  const told = [];
  const reply = {
    success: (bytes, timings) => told.push(['success', bytes, timings]),
    error: (e) => told.push(['error', e]),
    timeout: () => told.push(['timeout']),
  };
  const frag = { sn, level, start: sn * 4, end: sn * 4 + 4 };
  const decision = requests.fragmentRequest(
    { sn, level, url: segUrl(RUNGS[level].id, sn), frag, context: {} },
    reply,
  );
  return { decision, told };
}

const put = (bank, seq, rung = 'r480', size = 100) =>
  bank.putSegment({
    seq,
    rung,
    start: seq * 4,
    end: seq * 4 + 4,
    bytes: new Uint8Array(size).fill(seq).buffer,
    timings: { start: 10, first: 20, end: 30 },
  });

// The segment requests the server saw (the first three calls are the playlists).
const segmentCalls = (server) => server.calls.slice(3).map((c) => c.url);
const urls = (rung, ...ns) => ns.map((n) => segUrl(rung, n));

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(T0);
});

afterEach(() => {
  vi.useRealTimers();
});

describe('the bank decider: modes and held film', () => {
  it('passes every request while off or armed, and says armed while armed or on only', async () => {
    const { requests, server } = await setup({ mode: 'off' });
    expect(requests.armed()).toBe(false);
    expect(ask(requests, 0).decision).toEqual({ action: 'pass' });
    requests.setMode('armed');
    requests.setMode('armed');
    expect(requests.armed()).toBe(true);
    expect(ask(requests, 0).decision).toEqual({ action: 'pass' });
    requests.setMode('on');
    expect(requests.armed()).toBe(true);
    requests.setMode('turningOff');
    expect(requests.armed()).toBe(false);
    expect(segmentCalls(server)).toEqual([]);
  });

  it('keeps what the inner loader loaded while armed, with the film times of its request', async () => {
    const { requests, bank, front } = await setup({ mode: 'armed' });
    ask(requests, 7);
    ask(requests, 8, 3);
    front.pause();
    const bytes = new Uint8Array(50).fill(3).buffer;
    const timings = { start: 1, first: 2, end: 3 };
    requests.fragmentLoaded({ sn: 7, level: L480, url: 'u', bytes, timings });
    requests.fragmentLoaded({ sn: 8, level: 3, url: 'u', bytes, timings });
    requests.fragmentLoaded({ sn: 9, level: L480, url: 'u', bytes, timings });
    expect(bank.getSegment(7)).toMatchObject({ rung: 'r480', start: 28, end: 32, timings });
    expect(bank.getSegment(7).bytes.byteLength).toBe(50);
    expect(bank.hasSegment(8)).toBe(false); // above 480p: not kept
    expect(bank.hasSegment(9)).toBe(false); // never asked through the decider: no film times
    expect(front.isPaused()).toBe(false); // a successful server answer resumed the front
  });

  it('answers a held fragment at the requested rung from the bank, a fresh copy, with no server request', async () => {
    const { requests, bank, server, front } = await setup();
    put(bank, 3);
    front.pause();
    const { decision } = ask(requests, 3);
    expect(decision.action).toBe('answer');
    expect(decision.timings).toEqual({ start: 10, first: 20, end: 30 });
    expect(new Uint8Array(decision.bytes)).toEqual(new Uint8Array(100).fill(3));
    expect(decision.bytes).not.toBe(bank.getSegment(3).bytes);
    expect(front.isPaused()).toBe(true); // held film never resumes the front
    expect(segmentCalls(server)).toEqual([]);
  });

  it('calls wrongRung with the held level before failing a fragment held at another rung as a bank-rung 503', async () => {
    const calls = [];
    const { requests, bank, server } = await setup({
      wrongRung: (level) => calls.push(['wrong', level]),
    });
    put(bank, 3, 'r360');
    const reply = {
      error: (e) => calls.push(['error', e]),
      success: () => calls.push(['success']),
      timeout: () => calls.push(['timeout']),
    };
    const frag = { sn: 3, level: L480, start: 12, end: 16 };
    const decision = requests.fragmentRequest(
      { sn: 3, level: L480, url: 'u', frag, context: {} },
      reply,
    );
    expect(decision).toEqual({ action: 'wait' });
    expect(calls).toEqual([
      ['wrong', 1],
      ['error', { code: 503, text: BANK_RUNG_TEXT }],
    ]);
    expect(segmentCalls(server)).toEqual([]);
  });
});

describe("the bank decider: the front's next position", () => {
  it('fetches it with exactly one server request for hls.js and the bank, then follows on in order up to F', async () => {
    const { requests, bank, server } = await setup({ seq: 4 });
    requests.update({ filmAhead: 0, need: 12 }); // three segments of 4 s reach F
    const { decision, told } = ask(requests, 4);
    expect(decision.action).toBe('wait');
    await flush();
    expect(segmentCalls(server)).toEqual(urls('r480', 4));
    await complete(server.calls.at(-1), { size: 500, firstMs: 200, bodyMs: 300 });
    expect(told).toHaveLength(1);
    expect(told[0][0]).toBe('success');
    expect(told[0][1].byteLength).toBe(500);
    expect(told[0][2]).toEqual({ start: T0, first: T0 + 200, end: T0 + 500 });
    expect(bank.hasSegment(4)).toBe(true);
    for (let i = 0; i < 2; i += 1) await complete(server.calls.at(-1)); // hls.js quiet: the front goes on
    await vi.advanceTimersByTimeAsync(60000);
    expect(segmentCalls(server)).toEqual(urls('r480', 4, 5, 6));
  });

  it('fetches the position once and nothing after it while the front is already at F', async () => {
    const { requests, bank, server } = await setup({ seq: 4 });
    requests.update({ filmAhead: 20, need: 20 });
    const { told } = ask(requests, 4);
    await flush();
    await complete(server.calls.at(-1));
    expect(told[0][0]).toBe('success');
    expect(bank.hasSegment(4)).toBe(true);
    await vi.advanceTimersByTimeAsync(60000);
    expect(segmentCalls(server)).toEqual(urls('r480', 4));
    requests.update({ filmAhead: 10, need: 20 }); // film played out
    await flush();
    expect(segmentCalls(server)).toEqual(urls('r480', 4, 5));
  });

  it('holds the segment over the limit, answers hls.js, then fetches nothing until film plays out', async () => {
    const { requests, bank, server } = await setup({ seq: 4, limitBytes: 1500 });
    put(bank, 20, 'r480', 1600);
    requests.update({ filmAhead: 0, need: 1000 });
    expect(bank.atLimit()).toBe(true);
    const { told } = ask(requests, 4);
    await flush();
    await complete(server.calls.at(-1), { size: 2000 });
    expect(told.map((t) => t[0])).toEqual(['success']);
    expect(bank.hasSegment(4)).toBe(true);
    await vi.advanceTimersByTimeAsync(60000);
    expect(segmentCalls(server)).toEqual(urls('r480', 4));
  });

  it('fetches at once while paused, before RECONNECT_MS, and a success resumes the front at the next position', async () => {
    const { requests, front, server } = await setup({ seq: 4, need: 1000 });
    await flush();
    server.calls.at(-1).status(503); // plain 503 to the front's own fetch: paused
    await flush();
    expect(front.isPaused()).toBe(true);
    const callsBefore = server.calls.length;
    await vi.advanceTimersByTimeAsync(RECONNECT_MS / 2);
    const { told } = ask(requests, 4);
    await flush();
    expect(server.calls).toHaveLength(callsBefore + 1);
    await complete(server.calls.at(-1));
    expect(told[0][0]).toBe('success');
    expect(front.isPaused()).toBe(false);
    expect(server.calls.at(-1).url).toBe(segUrl('r480', 5));
  });

  it('hands a plain 503 to hls.js at once and leaves the pause timer alone: nothing before t0 + RECONNECT_MS, one at it', async () => {
    const { requests, front, server } = await setup({ seq: 4, need: 1000 });
    await flush();
    const pausedAt = Date.now();
    server.calls.at(-1).status(503);
    await flush();
    await vi.advanceTimersByTimeAsync(RECONNECT_MS / 2);
    const { told } = ask(requests, 4);
    await flush();
    const callsBefore = server.calls.length;
    server.calls.at(-1).status(503);
    await flush();
    expect(told).toEqual([['error', { code: 503, text: 'Service Unavailable' }]]);
    expect(front.isPaused()).toBe(true);
    await vi.advanceTimersByTimeAsync(pausedAt + RECONNECT_MS - 1 - Date.now());
    expect(server.calls).toHaveLength(callsBefore);
    await vi.advanceTimersByTimeAsync(1);
    expect(server.calls).toHaveLength(callsBefore + 1);
    expect(front.isPaused()).toBe(false);
  });

  it('hands a 503 with Retry-After over at once, and a wait already under way to a new request too', async () => {
    const { requests, server } = await setup({ seq: 4 });
    const first = ask(requests, 4);
    await flush();
    server.calls.at(-1).status(503, '2');
    await flush();
    expect(first.told).toEqual([['error', { code: 503, text: 'Service Unavailable' }]]);
    const callsBefore = server.calls.length;
    const second = ask(requests, 4); // the front is in its Retry-After wait
    expect(second.told).toEqual([['error', { code: 503, text: 'Service Unavailable' }]]);
    expect(second.decision.action).toBe('wait');
    expect(server.calls).toHaveLength(callsBefore);
  });

  it('calls onStopped at once for a 410 and onSessionLost for a 404, handing each to hls.js', async () => {
    const gone = await setup({ seq: 4 });
    const a = ask(gone.requests, 4);
    await flush();
    gone.server.calls.at(-1).status(410);
    await flush();
    expect(a.told).toEqual([['error', { code: 410, text: 'Gone' }]]);
    expect(gone.seen).toMatchObject({ stopped: 1, lost: 0 });
    const lost = await setup({ seq: 4 });
    const b = ask(lost.requests, 4);
    await flush();
    lost.server.calls.at(-1).status(404);
    await flush();
    expect(b.told).toEqual([['error', { code: 404, text: 'Not Found' }]]);
    expect(lost.seen).toMatchObject({ stopped: 0, lost: 1 });
  });

  it('keeps waiting through a network error and an answer it does not hand over', async () => {
    const { requests, server } = await setup({ seq: 4 });
    const { told } = ask(requests, 4);
    await flush();
    server.calls.at(-1).fail();
    await vi.advanceTimersByTimeAsync(2000);
    server.calls.at(-1).status(500);
    await vi.advanceTimersByTimeAsync(2000);
    expect(told).toEqual([]);
    await complete(server.calls.at(-1));
    expect(told.map((t) => t[0])).toEqual(['success']);
  });
});

describe("the bank decider: a waiting request's limits", () => {
  it("times out at hls.js's first-byte limit counted from its own request while the fetch carries on, and answers the retry from it", async () => {
    const { requests, server } = await setup({ seq: 4, need: 1000, firstByteMs: 5000 });
    await flush(); // the front's own fetch of 4 is open before hls.js asks
    await vi.advanceTimersByTimeAsync(3000);
    const { told } = ask(requests, 4);
    await vi.advanceTimersByTimeAsync(4999);
    expect(told).toEqual([]);
    await vi.advanceTimersByTimeAsync(1);
    expect(told).toEqual([['timeout']]);
    expect(server.calls.at(-1).aborted).toBe(false);
    await complete(server.calls.at(-1));
    expect(ask(requests, 4).decision.action).toBe('answer');
    expect(segmentCalls(server)).toEqual(urls('r480', 4, 5)); // 4 once: the second is the front's own onward fetch
  });

  it("ends the first-byte limit at the first byte; the load limit counts on from hls.js's request", async () => {
    const { requests, server } = await setup({ seq: 4, firstByteMs: 5000, loadMs: 20000 });
    const { told } = ask(requests, 4);
    await flush();
    await vi.advanceTimersByTimeAsync(1000);
    const r = server.calls.at(-1).respond();
    r.send(new Uint8Array(10));
    await vi.advanceTimersByTimeAsync(10000);
    r.send(new Uint8Array(10)); // progress, so the front's no-progress rule does not end it
    await vi.advanceTimersByTimeAsync(8999);
    expect(told).toEqual([]);
    await vi.advanceTimersByTimeAsync(1);
    expect(told).toEqual([['timeout']]);
    expect(server.calls.at(-1).aborted).toBe(false);
  });

  it("gives timeout at once when the front's no-progress rule ends the fetch", async () => {
    const { requests, server } = await setup({ seq: 4 });
    const { told } = ask(requests, 4);
    await flush();
    await vi.advanceTimersByTimeAsync(500);
    server.calls.at(-1).respond().send(new Uint8Array(10));
    await vi.advanceTimersByTimeAsync(11999);
    expect(told).toEqual([]);
    await vi.advanceTimersByTimeAsync(1);
    expect(told).toEqual([['timeout']]);
  });

  it("tells hls.js at once when the front's own first-byte limit ends the fetch", async () => {
    const { requests } = await setup({ seq: 4, firstByteMs: 60000 });
    const { told } = ask(requests, 4);
    await vi.advanceTimersByTimeAsync(9999);
    expect(told).toEqual([]);
    await vi.advanceTimersByTimeAsync(1);
    expect(told).toEqual([['timeout']]);
  });

  it('a cancel mid-wait stops its timers and replies, and the fetch carries on', async () => {
    const { requests, server, bank } = await setup({ seq: 4, firstByteMs: 5000 });
    const { decision, told } = ask(requests, 4);
    await flush();
    decision.cancel();
    decision.cancel();
    await vi.advanceTimersByTimeAsync(8000);
    expect(told).toEqual([]);
    expect(server.calls.at(-1).aborted).toBe(false);
    await complete(server.calls.at(-1));
    expect(told).toEqual([]);
    expect(bank.hasSegment(4)).toBe(true);
  });

  it('serves a second request for the same position with the same one server request', async () => {
    const { requests, server } = await setup({ seq: 4 });
    const a = ask(requests, 4);
    const b = ask(requests, 4);
    await flush();
    a.decision.cancel(); // the first loader is destroyed
    await complete(server.calls.at(-1));
    expect(segmentCalls(server)).toHaveLength(1);
    expect(a.told).toEqual([]);
    expect(b.told.map((t) => t[0])).toEqual(['success']);
  });

  it('fails a fetched fragment held at another rung as a bank-rung error after wrongRung', async () => {
    const { requests, server, seen } = await setup({ seq: 4 });
    const { told } = ask(requests, 4, 1); // hls.js is at 360p, the front fetches 480p
    await flush();
    await complete(server.calls.at(-1));
    expect(seen.wrong).toEqual([L480]);
    expect(told).toEqual([['error', { code: 503, text: BANK_RUNG_TEXT }]]);
  });

  it('ends the wait with timeout when the front drops the position', async () => {
    const { requests, front, server } = await setup({ seq: 4 });
    const { told } = ask(requests, 4);
    await flush();
    front.movePast(10);
    expect(told).toEqual([['timeout']]);
    expect(server.calls.at(-1).aborted).toBe(true);
  });
});

describe('the bank decider: behind and beyond the front', () => {
  // Held 6..9 at 480p; the front at 10 and wanting film; hls.js seeks back to segment 2.
  async function gapSetup() {
    const t = await setup({ seq: 10 });
    for (let n = 6; n < 10; n += 1) put(t.bank, n);
    t.requests.update({ filmAhead: 0, need: 1000 });
    await flush();
    return t;
  }

  it("waits through a gap: the front fetches only what hls.js asks, and returns to the held film's end", async () => {
    const { requests, server, front } = await gapSetup();
    const first = ask(requests, 2);
    await flush();
    await complete(server.calls.at(-1));
    expect(first.told.map((t) => t[0])).toEqual(['success']);
    await vi.advanceTimersByTimeAsync(10 * 60 * 1000); // hls.js quiet for ten minutes mid-gap
    const quiet = server.calls.length;
    expect(server.calls.at(-1).url).toBe(segUrl('r480', 2));
    for (const n of [3, 4, 5]) {
      const r = ask(requests, n);
      await flush();
      await complete(server.calls.at(-1));
      expect(r.told.map((t) => t[0])).toEqual(['success']);
    }
    expect(server.calls).toHaveLength(quiet + 3);
    expect(front.position().seq).toBe(6);
    expect(ask(requests, 6).decision.action).toBe('answer'); // held film: the front returns to its end
    await flush();
    expect(server.calls.at(-1).url).toBe(segUrl('r480', 10));
    expect(front.position().seq).toBe(10);
  });

  it('ending a gap on held film makes one server request, for the segment after the held run, none for the held one', async () => {
    const { requests, server } = await gapSetup();
    for (const n of [2, 3, 4, 5]) {
      ask(requests, n);
      await flush();
      await complete(server.calls.at(-1));
    }
    const before = server.calls.length;
    expect(ask(requests, 6).decision.action).toBe('answer');
    await flush();
    expect(server.calls.slice(before).map((c) => c.url)).toEqual(urls('r480', 10));
  });

  it('a held request behind the gap does not end the wait', async () => {
    const { requests, bank, server } = await gapSetup();
    put(bank, 0);
    ask(requests, 2);
    await flush();
    await complete(server.calls.at(-1));
    expect(ask(requests, 0).decision.action).toBe('answer');
    await vi.advanceTimersByTimeAsync(60000);
    expect(server.calls.at(-1).url).toBe(segUrl('r480', 2));
  });

  async function inGap() {
    const t = await gapSetup();
    ask(t.requests, 2);
    await flush();
    await complete(t.server.calls.at(-1));
    await vi.advanceTimersByTimeAsync(60000);
    return t;
  }

  it('endGap (a seek or a clear) lets the front carry on from where it is', async () => {
    const { requests, server } = await inGap();
    const quiet = server.calls.length;
    requests.endGap();
    requests.endGap();
    await flush();
    expect(server.calls).toHaveLength(quiet + 1);
    expect(server.calls.at(-1).url).toBe(segUrl('r480', 3));
  });

  it('ends the gap when hls.js skips the held film it already has: the front carries on past it', async () => {
    const { requests, server, bank } = await setup({ seq: 41, need: 1000 });
    for (let n = 30; n < 41; n += 1) put(bank, n);
    requests.update({ filmAhead: 0, need: 1000 });
    await flush();
    for (const n of [25, 26, 27, 28, 29]) {
      ask(requests, n);
      await flush();
      await complete(server.calls.at(-1));
    }
    const skipped = ask(requests, 41); // 30..40 are in hls.js's buffer: it asks for 41
    await flush();
    await complete(server.calls.at(-1));
    expect(skipped.told.map((t) => t[0])).toEqual(['success']);
    await complete(server.calls.at(-1));
    await complete(server.calls.at(-1));
    expect(segmentCalls(server).slice(-4)).toEqual(urls('r480', 41, 42, 43, 44));
    requests.update({ filmAhead: 0, need: 1000 }); // B5b's next update: the need is no longer held at 0
    await complete(server.calls.at(-1));
    expect(segmentCalls(server).at(-1)).toBe(segUrl('r480', 45));
  });

  it('ends the gap when the held film above it is dropped behind the playhead', async () => {
    const { requests, server, bank } = await setup({ seq: 41, need: 1000 });
    for (let n = 30; n < 41; n += 1) put(bank, n);
    requests.update({ filmAhead: 0, need: 1000 });
    await flush();
    ask(requests, 25);
    await flush();
    await complete(server.calls.at(-1));
    bank.dropBehind(500); // everything above 25 is gone
    expect(bank.hasSegmentAbove(25)).toBe(false);
    const next = ask(requests, 26);
    await flush();
    await complete(server.calls.at(-1));
    expect(next.told.map((t) => t[0])).toEqual(['success']);
    await complete(server.calls.at(-1));
    expect(segmentCalls(server).slice(-3)).toEqual(urls('r480', 26, 27, 28));
    requests.update({ filmAhead: 0, need: 1000 });
    await complete(server.calls.at(-1));
    expect(segmentCalls(server).at(-1)).toBe(segUrl('r480', 29));
  });

  it('a change of mode ends the wait without sending the front anything', async () => {
    const { requests, server } = await inGap();
    const quiet = server.calls.length;
    requests.setMode('turningOff');
    await vi.advanceTimersByTimeAsync(60000);
    expect(server.calls).toHaveLength(quiet);
  });

  it('a gap before B5b has called update still moves the front and waits', async () => {
    const { requests, server, bank } = await setup({ seq: 10, need: null });
    put(bank, 6);
    const { decision } = ask(requests, 2);
    expect(decision.action).toBe('wait');
    await flush();
    expect(server.calls.at(-1).url).toBe(segUrl('r480', 2));
    requests.endGap();
    requests.endGap();
  });

  it('fetches a request beyond the front through the front alone, one open request at a time, then carries on', async () => {
    const { requests, front, server } = await setup({ seq: 4, need: 1000 });
    await flush();
    const inFlight = server.calls.at(-1);
    expect(inFlight.url).toBe(segUrl('r480', 4));
    const { decision, told } = ask(requests, 9);
    expect(decision.action).toBe('wait');
    await flush();
    expect(inFlight.aborted).toBe(true);
    expect(segmentCalls(server)).toEqual(urls('r480', 4, 9)); // only 9 is open, nothing beside it
    await complete(server.calls.at(-1));
    expect(told.map((t) => t[0])).toEqual(['success']);
    expect(segmentCalls(server)).toEqual(urls('r480', 4, 9, 10)); // then 10, after it
    expect(server.calls.at(-1).aborted).toBe(false);
    expect(front.position().seq).toBe(10);
  });

  it('a retry behind the front with no held film above it starts no gap: the front carries on to F in order', async () => {
    const { requests, server } = await setup({ seq: 10, need: 1000 });
    await flush();
    const { told } = ask(requests, 7);
    await flush();
    await complete(server.calls.at(-1));
    expect(told.map((t) => t[0])).toEqual(['success']);
    await complete(server.calls.at(-1));
    await complete(server.calls.at(-1));
    expect(segmentCalls(server)).toEqual(urls('r480', 10, 7, 8, 9, 10));
  });
});

describe('the bank decider: the rung boundary', () => {
  const L240 = 0;
  const L360 = 1;
  const kinds = (told) => told.map((t) => t[0]);
  // Held: 3 at 360p, 4 at 480p (a rung change inside the bank); the front is past them, at 5.
  async function boundarySetup(options = {}) {
    const t = await setup({ seq: 5, rung: 'r480', ...options });
    put(t.bank, 3, 'r360');
    put(t.bank, 4, 'r480');
    return t;
  }

  it('fetches the re-asked segment once at the asked rung after a bank-rung 503 for its neighbour, then answers the neighbour', async () => {
    const { requests, bank, server, seen } = await boundarySetup();
    const next = ask(requests, 4, L360); // hls.js at 360p asks for 4, held at 480p
    expect(next.decision).toEqual({ action: 'wait' });
    expect(next.told).toEqual([['error', { code: 503, text: BANK_RUNG_TEXT }]]);
    expect(seen.wrong).toEqual([L480]);
    expect(segmentCalls(server)).toEqual([]);

    const again = ask(requests, 3, L480); // the level change: hls.js asks for 3 again, now at 480p
    expect(again.decision.action).toBe('wait');
    await flush();
    expect(segmentCalls(server)).toEqual(urls('r480', 3));
    await complete(server.calls.at(-1), { size: 321 });
    expect(kinds(again.told)).toEqual(['success']);
    expect(again.told[0][1].byteLength).toBe(321);
    expect(seen.wrong).toEqual([L480]); // the breaker is not a wrong-rung answer
    expect(bank.rungOf(3)).toBe('r480'); // what hls.js appended is what is held

    const onward = ask(requests, 4, L480);
    expect(onward.decision.action).toBe('answer');
    await flush();
    expect(segmentCalls(server)).toEqual(urls('r480', 3)); // no request for 4
  });

  it('the reverse order: the re-asked segment first gets its one fetch, and its neighbour is then answered with no 503', async () => {
    const { requests, server, seen } = await boundarySetup();
    const again = ask(requests, 3, L480);
    await flush();
    await complete(server.calls.at(-1));
    expect(kinds(again.told)).toEqual(['success']);
    const onward = ask(requests, 4, L480);
    expect(onward.decision.action).toBe('answer');
    expect(seen.wrong).toEqual([]);
    expect(segmentCalls(server)).toEqual(urls('r480', 3));
  });

  it('keeps the bank-rung 503 and makes no fetch for a lone wrong-rung request with no neighbour held at the asked rung', async () => {
    const { requests, bank, server, seen } = await setup({ seq: 5 });
    put(bank, 3, 'r360');
    put(bank, 4, 'r360'); // the neighbour is at the held rung, not at the asked one
    const lone = ask(requests, 3, L480);
    expect(lone.decision).toEqual({ action: 'wait' });
    expect(lone.told).toEqual([['error', { code: 503, text: BANK_RUNG_TEXT }]]);
    expect(seen.wrong).toEqual([L360]);
    put(bank, 8, 'r360'); // nothing held after 8
    const last = ask(requests, 8, L480);
    expect(last.told).toEqual([['error', { code: 503, text: BANK_RUNG_TEXT }]]);
    await flush();
    expect(segmentCalls(server)).toEqual([]);
  });

  it('never replaces the held neighbour, and the front goes back to its own rung, past the held run, with no request at the old position', async () => {
    const { requests, bank, server, front } = await setup({ seq: 5, rung: 'r360', need: 0 });
    put(bank, 3, 'r240');
    put(bank, 4, 'r480');
    const before = bank.getSegment(4);
    requests.update({ filmAhead: 0, need: 1000 });
    await flush();
    expect(segmentCalls(server)).toEqual(urls('r360', 5)); // the front fetching at its own rung

    const again = ask(requests, 3, L480);
    await flush();
    expect(segmentCalls(server)).toEqual([...urls('r360', 5), ...urls('r480', 3)]);
    await complete(server.calls.at(-1));
    expect(kinds(again.told)).toEqual(['success']);
    expect(segmentCalls(server)).toHaveLength(2); // held as a gap: nothing at 4 or 5 meanwhile

    expect(ask(requests, 4, L480).decision.action).toBe('answer');
    await flush();
    expect(bank.getSegment(4)).toEqual(before);
    expect(segmentCalls(server)).toEqual([
      ...urls('r360', 5),
      ...urls('r480', 3),
      ...urls('r360', 5),
    ]);
    expect(front.position()).toEqual({ seq: 5, rung: 'r360' });
    expect(server.calls.at(-1).aborted).toBe(false);
    expect(bank.rungOf(4)).toBe('r480');
  });

  it('costs one request again at the next boundary and never refetches the later segments', async () => {
    const { requests, bank, server } = await boundarySetup();
    put(bank, 5, 'r480');
    put(bank, 6, 'r240');
    put(bank, 7, 'r360');
    // First boundary: 3 (360p) then 4 (480p).
    ask(requests, 3, L480);
    await flush();
    await complete(server.calls.at(-1));
    for (const n of [4, 5]) expect(ask(requests, n, L480).decision.action).toBe('answer');
    // Second boundary: 5 and 6 are held at 480p and 240p, then 7 at 360p; hls.js moves down to 240p and asks 6.
    expect(ask(requests, 6, L240).decision.action).toBe('answer');
    // The level change to 360p re-asks 6, whose neighbour 7 is held at 360p.
    ask(requests, 6, L360);
    await flush();
    await complete(server.calls.at(-1));
    for (const n of [7]) expect(ask(requests, n, L360).decision.action).toBe('answer');
    expect(segmentCalls(server)).toEqual([...urls('r480', 3), ...urls('r360', 6)]);
  });

  it('answers the re-ask from a boundary copy with no request and no 503', async () => {
    const { requests, bank, server, seen } = await boundarySetup();
    bank.putBoundary(3, 'r480', new Uint8Array(55).buffer, { start: 1, first: 2, end: 3 });
    const again = ask(requests, 3, L480);
    expect(again.decision.action).toBe('answer');
    expect(again.decision.bytes.byteLength).toBe(55);
    expect(again.decision.timings).toEqual({ start: 1, first: 2, end: 3 });
    expect(ask(requests, 4, L480).decision.action).toBe('answer');
    expect(bank.rungOf(3)).toBe('r360'); // the held copy is untouched
    expect(seen.wrong).toEqual([]);
    await flush();
    expect(segmentCalls(server)).toEqual([]);
  });

  it('while turning off, releases and passes a boundary request with no copy, sending the front nothing', async () => {
    const { requests, front, server, seen } = await boundarySetup();
    const update = vi.spyOn(front, 'update');
    requests.setMode('turningOff');
    requests.update({ filmAhead: 5, need: 100 });
    expect(update).not.toHaveBeenCalled();
    expect(ask(requests, 3, L480).decision).toEqual({ action: 'pass' });
    expect(seen.release).toEqual(['release']);
    expect(requests.armed()).toBe(false);
    expect(segmentCalls(server)).toEqual([]);
  });

  it('while turning off, answers a boundary request from a copy, and waits on the fetch in flight for its fragment', async () => {
    const { requests, bank, front, server, seen } = await boundarySetup({ need: 1000 });
    await flush();
    expect(segmentCalls(server)).toEqual(urls('r480', 5)); // the front's fetch in flight
    bank.putBoundary(3, 'r480', new Uint8Array(5).buffer, { start: 1, first: 2, end: 3 });
    requests.setMode('turningOff');
    front.finish();
    expect(ask(requests, 3, L480).decision.action).toBe('answer');
    const inFlight = ask(requests, 5, L480);
    expect(inFlight.decision.action).toBe('wait');
    await complete(server.calls.at(-1), { size: 77 });
    expect(inFlight.told.map((t) => t[0])).toEqual(['success']);
    expect(seen.release).toEqual([]);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(segmentCalls(server)).toEqual(urls('r480', 5)); // nothing new
    expect(ask(requests, 6, L480).decision).toEqual({ action: 'pass' }); // any other request releases
    expect(seen.release).toEqual(['release']);
  });

  it('while turning off, a boundary request for the fragment in flight waits on it', async () => {
    const { requests, front, server, seen } = await boundarySetup();
    ask(requests, 3, L480); // the breaker's fetch of 3 at 480p
    await flush();
    requests.setMode('turningOff');
    front.finish();
    const again = ask(requests, 3, L480);
    expect(again.decision.action).toBe('wait');
    await complete(server.calls.at(-1));
    expect(again.told.map((t) => t[0])).toEqual(['success']);
    expect(seen.release).toEqual([]);
  });

  it('after the breaker, a request for film not held moves the front on its own rung, not the asked one', async () => {
    const { requests, bank, server, front } = await setup({ seq: 5, rung: 'r360' });
    put(bank, 3, 'r240');
    put(bank, 4, 'r480');
    ask(requests, 3, L480);
    await flush();
    await complete(server.calls.at(-1));
    const next = ask(requests, 7, L360); // s + 2 is not held: the gap ends without s + 1 being asked
    expect(next.decision.action).toBe('wait');
    await flush();
    expect(server.calls.at(-1).url).toBe(segUrl('r360', 7));
    expect(front.chosenRung()).toBe('r360');
  });

  it('keeps a rung chosen during the boundary fetch for the return', async () => {
    const { requests, bank, server, front } = await setup({ seq: 5, rung: 'r360' });
    put(bank, 3, 'r240');
    put(bank, 4, 'r480');
    ask(requests, 3, L480);
    await flush();
    requests.setRung('r240'); // the bank's choice while the front is on the asked rung
    expect(front.chosenRung()).toBe('r480');
    await complete(server.calls.at(-1));
    expect(ask(requests, 4, L480).decision.action).toBe('answer');
    expect(front.position()).toEqual({ seq: 5, rung: 'r240' });
    requests.setRung('r360'); // no boundary fetch: straight to the front
    expect(front.chosenRung()).toBe('r360');
  });

  it('hands a failed boundary fetch to hls.js and stays consistent for its retry', async () => {
    const { requests, bank, server, front } = await setup({ seq: 5, rung: 'r360' });
    put(bank, 3, 'r240');
    put(bank, 4, 'r480');
    const first = ask(requests, 3, L480);
    await flush();
    server.calls.at(-1).status(503);
    await flush();
    expect(first.told).toEqual([['error', { code: 503, text: 'Service Unavailable' }]]);
    requests.setRung('r240'); // still inside the gap: kept
    const retry = ask(requests, 3, L480);
    await flush();
    expect(segmentCalls(server)).toEqual([...urls('r480', 3), ...urls('r480', 3)]);
    await complete(server.calls.at(-1));
    expect(retry.told.map((t) => t[0])).toEqual(['success']);
    expect(ask(requests, 4, L480).decision.action).toBe('answer');
    expect(front.position()).toEqual({ seq: 5, rung: 'r240' });
  });

  it('while turning off, releases a request for a fragment whose fetch failed and was not retried', async () => {
    const { requests, front, server, seen } = await setup({ seq: 5, need: 1000 });
    await flush();
    requests.setMode('turningOff');
    front.finish();
    server.calls.at(-1).fail(); // nobody waits: no retry
    await vi.advanceTimersByTimeAsync(60_000);
    expect(front.fetching()).toBeNull();
    expect(ask(requests, 5, L480).decision).toEqual({ action: 'pass' });
    expect(seen.release).toEqual(['release']);
    expect(segmentCalls(server)).toEqual(urls('r480', 5));
  });

  it('while turning off, releases the retry of a fragment whose waiter was handed a 503', async () => {
    const { requests, front, server, seen } = await setup({ seq: 5, need: 1000 });
    const waiter = ask(requests, 5, L480);
    requests.setMode('turningOff');
    front.finish();
    server.calls.at(-1).status(503, '1');
    await flush();
    expect(waiter.told).toEqual([['error', { code: 503, text: 'Service Unavailable' }]]);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(ask(requests, 5, L480).decision).toEqual({ action: 'pass' });
    expect(seen.release).toEqual(['release']);
  });

  it('makes no request for the segment before after the on-demand fetch and the end of its gap', async () => {
    const { requests, bank, server } = await setup({ seq: 6, rung: 'r360' });
    put(bank, 2, 'r240');
    put(bank, 3, 'r360');
    put(bank, 4, 'r480');
    requests.update({ filmAhead: 0, need: 1000 });
    ask(requests, 3, L480);
    await flush();
    await complete(server.calls.at(-1));
    expect(ask(requests, 4, L480).decision.action).toBe('answer');
    await flush();
    expect(segmentCalls(server)).toEqual([
      ...urls('r360', 6),
      ...urls('r480', 3),
      ...urls('r360', 5),
    ]);
  });

  it('waits on the boundary copy being fetched, with exactly one server request for the segment at that rung', async () => {
    const { requests, bank, server } = await setup({ seq: 4, rung: 'r480' });
    put(bank, 3, 'r240');
    requests.update({ filmAhead: 0, need: 1000 });
    await flush();
    await complete(server.calls.at(-1)); // 4 at 480p: the copy of 3 at 480p goes next
    expect(segmentCalls(server)).toEqual([...urls('r480', 4), ...urls('r480', 3)]);
    const again = ask(requests, 3, L480);
    expect(again.decision.action).toBe('wait');
    await flush();
    await complete(server.calls.at(-1), { size: 66 });
    expect(again.told.map((t) => t[0])).toEqual(['success']);
    expect(again.told[0][1].byteLength).toBe(66);
    expect(bank.rungOf(3)).toBe('r240');
    expect(server.calls.filter((c) => c.url === segUrl('r480', 3))).toHaveLength(1);
    expect(server.calls.every((c) => !c.aborted)).toBe(true);
  });
});

describe('the bank decider: turning off and the playlists', () => {
  it('answers held film while turning off, releases once on the first request for film it does not hold, and fetches nothing', async () => {
    const { requests, bank, server, seen } = await setup({ seq: 4 });
    put(bank, 4);
    requests.setMode('turningOff');
    expect(ask(requests, 4).decision.action).toBe('answer');
    expect(seen.release).toEqual([]);
    expect(ask(requests, 7).decision).toEqual({ action: 'pass' });
    expect(seen.release).toEqual(['release']);
    expect(requests.armed()).toBe(false);
    expect(ask(requests, 8).decision).toEqual({ action: 'pass' });
    expect(seen.release).toEqual(['release']);
    expect(segmentCalls(server)).toEqual([]);
  });

  it('answers a media playlist from the bank only while on or turning off', async () => {
    const { requests } = await setup({ mode: 'armed' });
    expect(requests.playlistText({ level: L480 })).toBeNull();
    requests.setMode('on');
    expect(requests.playlistText({ level: L480 })).toBe(playlistText());
    expect(requests.playlistText({ level: 3 })).toBeNull();
    requests.setMode('turningOff');
    expect(requests.playlistText({ level: 0 })).toBe(playlistText());
  });
});

describe('the bank decider: optional hooks', () => {
  it('runs without a wrongRung or onRelease hook', () => {
    const bank = createSegmentBank();
    const front = {
      position: () => ({ seq: 0, rung: 'r480' }),
      fetching: () => null,
      boundaryFetching: () => null,
    };
    const requests = createBankRequests({ bank, front, levelOfRung, rungOfLevel });
    requests.setMode('on');
    put(bank, 3, 'r360');
    expect(ask(requests, 3).decision).toEqual({ action: 'wait' });
    requests.setMode('turningOff');
    expect(ask(requests, 9).decision).toEqual({ action: 'pass' });
  });
});
