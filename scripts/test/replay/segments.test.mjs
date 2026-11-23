// scripts/replay/segments.mjs: which requests are recorded and what is kept of each, with a made-up page that lets a
// test fire Chrome's Network events in the shapes it sends them. No Chrome and no network.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { MAX_SEGMENTS, attachSegments, classify, speedKbit } from '../../replay/segments.mjs';

const SITE = 'http://127.0.0.1:5301';
const SESSION = '3f2b8c1e-5a4d-4e6f-9b7a-1c2d3e4f5a6b';
const seg = (rung, file) => `${SITE}/api/sessions/${SESSION}/${rung}/${file}`;

// A page as cdp.mjs gives one: every send is logged, `fire` is Chrome sending an event.
function fakePage() {
  const log = [];
  const listeners = new Map();
  return {
    log,
    send(method, params) {
      log.push(['send', method, params]);
      return Promise.resolve({});
    },
    on(method, listener) {
      listeners.set(method, listener);
    },
    fire(method, params) {
      listeners.get(method)(params);
    },
  };
}

async function recording(options = {}) {
  const page = fakePage();
  const recorder = await attachSegments(page, { site: SITE, ...options });
  return { page, report: recorder.report };
}

// The events of one download as Chrome sends them: it starts at monotonic 1000 s, wall clock 1,700,000,000.5 s.
const start = (page, id, url, extra = {}) => page.fire('Network.requestWillBeSent', {
  requestId: id, timestamp: 1000, wallTime: 1700000000.5, request: { method: 'GET', url }, ...extra,
});
const headers = (page, id, status = 200, timing = { requestTime: 1000.01, receiveHeadersEnd: 120.4 }) => page.fire(
  'Network.responseReceived', { requestId: id, response: { status, timing } },
);
const finish = (page, id, at = 1000.9, bytes = 90000) => page.fire('Network.loadingFinished', {
  requestId: id, timestamp: at, encodedDataLength: bytes,
});

describe('which requests are recorded', () => {
  test('a segment on the site is in; its rung and file are kept, the session id and the address never', async () => {
    const { page, report } = await recording();
    start(page, 'a', `${seg('720p', 'seg_00012.ts')}?token=sekret`);
    finish(page, 'a');
    const out = report();
    assert.equal(out.segments.length, 1);
    assert.equal(out.segments[0].rung, '720p');
    assert.equal(out.segments[0].file, 'seg_00012.ts');
    const text = JSON.stringify(out);
    for (const secret of [SESSION, 'sekret', '127.0.0.1', '/api/']) assert.equal(text.includes(secret), false, secret);
  });

  test('a playlist is counted and not listed, whatever its case or rung', async () => {
    const { page, report } = await recording();
    start(page, 'a', seg('720p', 'index.m3u8'));
    start(page, 'b', seg('360p', 'INDEX.M3U8'));
    const out = report();
    assert.equal(out.playlists, 2);
    assert.deepEqual(out.segments, []);
  });

  test('other origins, other paths and other methods are out', async () => {
    const { page, report } = await recording();
    start(page, 'a', `http://example.com/api/sessions/${SESSION}/720p/seg_1.ts`);
    start(page, 'b', `${SITE}/api/sessions/not-a-session/720p/seg_1.ts`);
    start(page, 'c', `${SITE}/api/sessions/${SESSION}/720p/sub/seg_1.ts`);
    start(page, 'd', `${SITE}/api/editions/12/progress`);
    start(page, 'e', `${SITE}/assets/app.js`);
    start(page, 'f', seg('720p', 'seg_1.ts'), { request: { method: 'POST', url: seg('720p', 'seg_1.ts') } });
    start(page, 'g', 'not an address');
    page.fire('Network.requestWillBeSent', { requestId: 'h', timestamp: 1, request: {} });
    page.fire('Network.requestWillBeSent', {});
    const out = report();
    assert.deepEqual(out.segments, []);
    assert.equal(out.playlists, 0);
  });

  test('classify gives the rung and file, the playlist marker or null', () => {
    assert.deepEqual(classify('GET', seg('360p', 'seg_3.m4s'), SITE), { rung: '360p', file: 'seg_3.m4s' });
    assert.deepEqual(classify('GET', seg('360p', 'a.m3u8'), SITE), { playlist: true });
    assert.equal(classify('PUT', seg('360p', 'seg_3.m4s'), SITE), null);
    assert.equal(classify('GET', seg('360p', 'seg_3.m4s'), 'http://127.0.0.1:5302'), null);
  });

  test('Chrome is asked for the Network domain', async () => {
    const { page } = await recording();
    assert.deepEqual(page.log, [['send', 'Network.enable', undefined]]);
  });
});

describe('what is kept of a download', () => {
  test('start, first byte, total time, bytes and status from a CDP-shaped sequence', async () => {
    const { page, report } = await recording();
    start(page, 'a', seg('720p', 'seg_1.ts'));
    headers(page, 'a');
    finish(page, 'a', 1000.9, 90000);
    assert.deepEqual(report().segments, [{
      rung: '720p', file: 'seg_1.ts', status: 200, bytes: 90000, startMs: 1700000000500,
      ttfbMs: 130, totalMs: 900, failed: false, canceled: false, errorText: null, unfinished: false,
    }]);
  });

  test('the first byte uses receiveHeadersStart when Chrome gives it, and none when there is no timing', async () => {
    const { page, report } = await recording();
    start(page, 'a', seg('720p', 'seg_1.ts'));
    headers(page, 'a', 200, { requestTime: 1000, receiveHeadersStart: 50, receiveHeadersEnd: 80 });
    start(page, 'b', seg('720p', 'seg_2.ts'));
    headers(page, 'b', 200, null);
    start(page, 'c', seg('720p', 'seg_3.ts'));
    headers(page, 'c', 200, { requestTime: 'x', receiveHeadersEnd: 'y' });
    assert.deepEqual(report().segments.map((entry) => entry.ttfbMs), [50, null, null]);
  });

  test('a failed download keeps Chrome\'s error text, no bytes, and is not a cancelled one', async () => {
    const { page, report } = await recording();
    start(page, 'a', seg('360p', 'seg_9.ts'));
    page.fire('Network.loadingFailed', {
      requestId: 'a', timestamp: 1002.5, errorText: 'net::ERR_INTERNET_DISCONNECTED', canceled: false,
    });
    const [entry] = report().segments;
    assert.equal(entry.failed, true);
    assert.equal(entry.canceled, false);
    assert.equal(entry.errorText, 'net::ERR_INTERNET_DISCONNECTED');
    assert.equal(entry.totalMs, 2500);
    assert.equal(entry.bytes, null);
    assert.equal(entry.unfinished, false);
  });

  test('a cancelled download is cancelled and not failed', async () => {
    const { page, report } = await recording();
    start(page, 'a', seg('360p', 'seg_9.ts'));
    page.fire('Network.loadingFailed', {
      requestId: 'a', timestamp: 1000.3, errorText: 'net::ERR_ABORTED', canceled: true,
    });
    const [entry] = report().segments;
    assert.deepEqual([entry.failed, entry.canceled, entry.errorText], [false, true, 'net::ERR_ABORTED']);
  });

  test('error text is printable and short', async () => {
    const { page, report } = await recording();
    start(page, 'a', seg('360p', 'seg_9.ts'));
    const errorText = `bad\n\u001b[31m${'x'.repeat(300)}`;
    page.fire('Network.loadingFailed', { requestId: 'a', timestamp: 1001, errorText });
    const [entry] = report().segments;
    assert.match(entry.errorText, /^[\x20-\x7e]{1,100}$/);
  });

  test('a request still in flight is listed as unfinished, without a total', async () => {
    const { page, report } = await recording();
    start(page, 'a', seg('720p', 'seg_1.ts'));
    headers(page, 'a');
    const [entry] = report().segments;
    assert.deepEqual([entry.unfinished, entry.totalMs, entry.bytes, entry.status], [true, null, null, 200]);
  });

  test('events for a request whose start was never seen are counted and kept out', async () => {
    const { page, report } = await recording();
    headers(page, 'ghost');
    finish(page, 'ghost');
    page.fire('Network.loadingFailed', { requestId: 'ghost', timestamp: 1 });
    const out = report();
    assert.deepEqual(out.segments, []);
    assert.equal(out.unseen, 3);
  });

  test('a finish before the answer, and a second finish, leave one sound entry', async () => {
    const { page, report } = await recording();
    start(page, 'a', seg('720p', 'seg_1.ts'));
    finish(page, 'a', 1001, 500);
    headers(page, 'a');
    finish(page, 'a', 1005, 999);
    const out = report();
    assert.equal(out.segments.length, 1);
    assert.equal(out.segments[0].bytes, 500);
    assert.equal(out.segments[0].totalMs, 1000);
    assert.equal(out.unseen, 2);
  });

  test('a start sent twice for one request (a redirect) is one entry, timed from the first', async () => {
    const { page, report } = await recording();
    start(page, 'a', seg('720p', 'seg_1.ts'));
    start(page, 'a', seg('720p', 'seg_1.ts'), { timestamp: 1500 });
    finish(page, 'a');
    assert.equal(report().segments.length, 1);
    assert.equal(report().segments[0].totalMs, 900);
  });

  test('a start with no wall time or timestamp gives nulls, not a made-up time', async () => {
    const { page, report } = await recording();
    page.fire('Network.requestWillBeSent', { requestId: 'a', request: { method: 'GET', url: seg('720p', 'seg_1.ts') } });
    finish(page, 'a');
    const [entry] = report().segments;
    assert.deepEqual([entry.startMs, entry.totalMs], [null, null]);
  });

  test('a download the page navigated away from, never finished, stays unfinished beside a later one', async () => {
    const { page, report } = await recording();
    start(page, 'old', seg('720p', 'seg_1.ts'));
    start(page, 'new', seg('720p', 'seg_2.ts'));
    finish(page, 'new');
    assert.deepEqual(report().segments.map((entry) => entry.unfinished), [true, false]);
  });

  test('the report is a copy: changing it changes nothing kept', async () => {
    const { page, report } = await recording();
    start(page, 'a', seg('720p', 'seg_1.ts'));
    report().segments[0].rung = 'changed';
    report().segments.pop();
    assert.equal(report().segments[0].rung, '720p');
  });
});

describe('the cap', () => {
  test('the list stops at the cap, counts what it dropped and says so; a kept one still finishes', async () => {
    assert.equal(MAX_SEGMENTS, 20000);
    const { page, report } = await recording({ cap: 3 });
    for (let i = 0; i < 5; i += 1) start(page, `r${i}`, seg('720p', `seg_${i}.ts`));
    finish(page, 'r2');
    finish(page, 'r4');
    const out = report();
    assert.equal(out.cap, 3);
    assert.equal(out.capped, true);
    assert.equal(out.dropped, 2);
    assert.deepEqual(out.segments.map((entry) => [entry.file, entry.unfinished]), [
      ['seg_0.ts', true], ['seg_1.ts', true], ['seg_2.ts', false],
    ]);
    assert.equal(out.unseen, 1, 'the finish of one that was dropped is an event for a request not kept');
  });

  test('under the cap nothing is capped', async () => {
    const { page, report } = await recording({ cap: 2 });
    start(page, 'a', seg('720p', 'seg_1.ts'));
    start(page, 'b', seg('720p', 'seg_2.ts'));
    const out = report();
    assert.deepEqual([out.capped, out.dropped], [false, 0]);
  });
});

describe('speed', () => {
  const done = { status: 200, bytes: 125000, totalMs: 1000, failed: false, canceled: false, unfinished: false };

  test('bytes on the wire over the total time, in kilobits a second', () => {
    assert.equal(speedKbit(done), 1000);
    assert.equal(speedKbit({ ...done, status: 206 }), 1000);
  });

  test('none for a failed, cancelled, unfinished, empty, instant or non-2xx download', () => {
    for (const change of [
      { failed: true }, { canceled: true }, { unfinished: true }, { bytes: 0 }, { bytes: null }, { totalMs: 0 },
      { totalMs: null }, { status: 404 }, { status: null },
    ]) assert.equal(speedKbit({ ...done, ...change }), null, JSON.stringify(change));
  });
});

describe('the guard\'s own requests', () => {
  test('only a GET is recorded: the writes the guard fulfils or fails are never entries', async () => {
    const { page, report } = await recording();
    for (const method of ['POST', 'PUT', 'DELETE', 'HEAD', 'OPTIONS']) {
      start(page, method, seg('720p', 'seg_1.ts'), { request: { method, url: seg('720p', 'seg_1.ts') } });
      page.fire('Network.loadingFailed', { requestId: method, timestamp: 1001, errorText: 'net::ERR_FAILED' });
    }
    assert.deepEqual(report().segments, []);
  });
});
