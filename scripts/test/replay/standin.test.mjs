// scripts/replay/standin.mjs over real HTTP on a free port, with no round-trip delay and the link left open unless
// the test is about the link. Raw addresses are sent with http.request because fetch tidies an address first.
import assert from 'node:assert/strict';
import http from 'node:http';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setImmediate as nextTurn } from 'node:timers/promises';
import { after, describe, test } from 'node:test';
import {
  ladder, masterPlaylist, mediaPlaylist, startRung, startStandIn,
} from '../../replay/standin.mjs';
import { WIRE } from '../../replay/trace.mjs';
import { buildSegment } from '../../replay/ts.mjs';
import { FILM_PATH, makeTemplate } from './support.mjs';

const tmp = mkdtempSync(join(tmpdir(), 'replay-'));
const template = makeTemplate();
const started = [];
const pending = new Set();
const UUID = /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/;
const OTHER_VIEWING = '11111111-1111-1111-1111-111111111111';
const NO_SESSION = '00000000-0000-0000-0000-000000000000';

after(async () => {
  for (const req of pending) req.destroy();
  await Promise.all(started.map((s) => s.stop()));
  rmSync(tmp, { recursive: true, force: true });
});

// ---- the build the stand-in serves -----------------------------------------------------------------------------

const INDEX = '<!doctype html><title>the app</title>';
const FONT = Buffer.from([0, 1, 2, 255, 254, 0]);

// A folder holding a small build; `name` keeps each test's own.
function makeBuild(name) {
  const dist = join(tmp, name);
  mkdirSync(join(dist, 'assets'), { recursive: true });
  writeFileSync(join(dist, 'index.html'), INDEX);
  writeFileSync(join(dist, 'assets', 'app.js'), 'console.log("app");');
  writeFileSync(join(dist, 'assets', 'style.css'), 'body{margin:0}');
  writeFileSync(join(dist, 'assets', 'font.woff2'), FONT);
  writeFileSync(join(dist, 'assets', 'data.bin'), Buffer.from([9, 8, 7]));
  writeFileSync(join(dist, 'favicon.svg'), '<svg xmlns="http://www.w3.org/2000/svg"/>');
  return dist;
}

const DIST = makeBuild('dist');
// Files beside the build, one in a sibling folder whose name begins like the build's.
mkdirSync(join(tmp, 'dist-secret'));
writeFileSync(join(tmp, 'dist-secret', 'secret.txt'), 'SECRET-IN-A-SIBLING');
writeFileSync(join(tmp, 'outside.txt'), 'SECRET-BESIDE-THE-BUILD');

async function start(overrides = {}) {
  const standIn = await startStandIn({
    port: 0, distDir: DIST, filmPath: FILM_PATH, template, trace: [4000], rttMs: 0, ...overrides,
  });
  started.push(standIn);
  return standIn;
}

// ---- talking to it -----------------------------------------------------------------------------------------------

// One request to the end of its answer.
function request(port, { method = 'GET', path = '/', body, headers = {} } = {}) {
  return new Promise((resolve, reject) => {
    const req = http.request({ host: '127.0.0.1', port, method, path, headers, agent: false }, (res) => {
      const parts = [];
      res.on('data', (part) => parts.push(part));
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(parts) }));
      res.on('error', reject);
    });
    req.on('error', reject);
    req.end(body);
  });
}

// A request left open: what has arrived of its answer so far. Every one is aborted when the file ends.
function open(port, { method = 'GET', path = '/', body } = {}) {
  const seen = { status: null, bytes: 0, ended: false, failed: false, req: null };
  const req = http.request({ host: '127.0.0.1', port, method, path, agent: false }, (res) => {
    seen.status = res.statusCode;
    res.on('data', (part) => {
      seen.bytes += part.length;
    });
    res.on('end', () => {
      seen.ended = true;
    });
    res.on('error', () => {
      seen.failed = true;
    });
  });
  req.on('error', () => {
    seen.failed = true;
  });
  req.end(body);
  seen.req = req;
  pending.add(req);
  return seen;
}

// Lets the server and the client run, turn by turn, until `check` says so (no fixed wait). The deadline is read from
// performance.now(), not Date: a test may freeze Date (the replay clock reads it), and the deadline must still pass.
async function waitFor(check, what) {
  const deadline = performance.now() + 8000;
  while (!(await check())) {
    if (performance.now() > deadline) throw new Error(`gave up waiting for ${what}`);
    await nextTurn();
  }
}

const json = (answer) => JSON.parse(answer.body.toString('utf8'));
const stateOf = async (standIn) => json(await request(standIn.port, { path: '/__replay/state' }));
const post = (port, path, value) => request(port, { method: 'POST', path, body: typeof value === 'string' ? value : JSON.stringify(value) });
const put = (port, path, value) => request(port, { method: 'PUT', path, body: typeof value === 'string' ? value : JSON.stringify(value) });

async function newSession(standIn, body = { quality: 'auto', probeKbps: 5000 }) {
  const answer = await post(standIn.port, '/api/editions/11/sessions', body);
  assert.equal(answer.status, 200);
  return json(answer);
}

// A stand-in whose clock has started on a dead link, with a session made while it was still open.
async function startDead() {
  const standIn = await start({ trace: [0] });
  const { sessionId } = await newSession(standIn, { quality: '360p' });
  standIn.startClock();
  return { standIn, sessionId, segment: `/api/sessions/${sessionId}/360p/seg-0.ts` };
}

const entryOf = (standIn, kind) => standIn.requests.find((r) => r.kind === kind);

// ---- the film and the ladder ---------------------------------------------------------------------------------------

describe('the ladder of a 1080p source', () => {
  test('has every figure of Ladder.java and Rung.java for each rung', () => {
    assert.deepEqual(ladder(10_000_000), [
      { id: 'original', height: 1080, video: 10_000_000, copy: true, audio: 128_000, average: 10_128_000, peak: 15_128_000, width: 1920, codecs: 'avc1.640028,mp4a.40.2' },
      { id: '720p', height: 720, video: 2_500_000, copy: false, audio: 128_000, average: 2_628_000, peak: 3_128_000, width: 1280, codecs: 'avc1.64001f,mp4a.40.2' },
      { id: '480p', height: 480, video: 1_000_000, copy: false, audio: 128_000, average: 1_128_000, peak: 1_328_000, width: 852, codecs: 'avc1.64001e,mp4a.40.2' },
      { id: '360p', height: 360, video: 600_000, copy: false, audio: 96_000, average: 696_000, peak: 816_000, width: 640, codecs: 'avc1.64001e,mp4a.40.2' },
      { id: '240p', height: 240, video: 250_000, copy: false, audio: 64_000, average: 314_000, peak: 364_000, width: 426, codecs: 'avc1.64001e,mp4a.40.2' },
    ]);
  });

  test('takes its original rung from the source bitrate', () => {
    const [original, second] = ladder(8_000_000);
    assert.equal(original.video, 8_000_000);
    assert.equal(original.average, 8_128_000);
    assert.equal(original.peak, 12_128_000);
    assert.equal(second.video, 2_500_000);
  });
});

describe('startRung', () => {
  const rungs = ladder(10_000_000);
  const pick = (kbps) => startRung(rungs, kbps).id;

  test('picks a rung exactly at its peak times 1.25', () => {
    assert.equal(pick(18910), 'original');
    assert.equal(pick(3910), '720p');
    assert.equal(pick(1660), '480p');
    assert.equal(pick(1020), '360p');
    assert.equal(pick(455), '240p');
  });

  test('one kbit/s under a rung\'s figure picks the rung below', () => {
    assert.equal(pick(18909), '720p');
    assert.equal(pick(3909), '480p');
    assert.equal(pick(1659), '360p');
    assert.equal(pick(1019), '240p');
    assert.equal(pick(454), '240p');
  });

  test('starts on the floor under every rung, and on the top with no probe', () => {
    assert.equal(pick(0), '240p');
    assert.equal(pick(1), '240p');
    assert.equal(startRung(rungs, null).id, 'original');
    assert.equal(startRung(rungs, undefined).id, 'original');
    assert.equal(pick(1_000_000), 'original');
  });
});

describe('the playlists', () => {
  const rungs = ladder(10_000_000);

  test('the master playlist lists the start rung first, then the rest in order', () => {
    assert.equal(masterPlaylist(rungs, rungs[2], null), [
      '#EXTM3U',
      '#EXT-X-VERSION:3',
      '#EXT-X-INDEPENDENT-SEGMENTS',
      '#EXT-X-STREAM-INF:BANDWIDTH=1328000,AVERAGE-BANDWIDTH=1128000,RESOLUTION=852x480,CODECS="avc1.64001e,mp4a.40.2"',
      '480p/index.m3u8',
      '#EXT-X-STREAM-INF:BANDWIDTH=15128000,AVERAGE-BANDWIDTH=10128000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"',
      'original/index.m3u8',
      '#EXT-X-STREAM-INF:BANDWIDTH=3128000,AVERAGE-BANDWIDTH=2628000,RESOLUTION=1280x720,CODECS="avc1.64001f,mp4a.40.2"',
      '720p/index.m3u8',
      '#EXT-X-STREAM-INF:BANDWIDTH=816000,AVERAGE-BANDWIDTH=696000,RESOLUTION=640x360,CODECS="avc1.64001e,mp4a.40.2"',
      '360p/index.m3u8',
      '#EXT-X-STREAM-INF:BANDWIDTH=364000,AVERAGE-BANDWIDTH=314000,RESOLUTION=426x240,CODECS="avc1.64001e,mp4a.40.2"',
      '240p/index.m3u8',
      '',
    ].join('\n'));
  });

  test('a pinned master playlist lists that rung alone', () => {
    assert.equal(masterPlaylist(rungs, rungs[3], '360p'), [
      '#EXTM3U',
      '#EXT-X-VERSION:3',
      '#EXT-X-INDEPENDENT-SEGMENTS',
      '#EXT-X-STREAM-INF:BANDWIDTH=816000,AVERAGE-BANDWIDTH=696000,RESOLUTION=640x360,CODECS="avc1.64001e,mp4a.40.2"',
      '360p/index.m3u8',
      '',
    ].join('\n'));
  });

  test('mediaPlaylist(3, 4000) is exact', () => {
    assert.equal(mediaPlaylist(3, 4000), [
      '#EXTM3U',
      '#EXT-X-VERSION:3',
      '#EXT-X-PLAYLIST-TYPE:VOD',
      '#EXT-X-TARGETDURATION:4',
      '#EXT-X-MEDIA-SEQUENCE:0',
      '#EXT-X-INDEPENDENT-SEGMENTS',
      '#EXTINF:4.000,',
      'seg-0.ts',
      '#EXTINF:4.000,',
      'seg-1.ts',
      '#EXTINF:4.000,',
      'seg-2.ts',
      '#EXT-X-ENDLIST',
      '',
    ].join('\n'));
  });

  test('a segment of a part of a second is written in milliseconds and the target rounds up', () => {
    const lines = mediaPlaylist(1, 4500).split('\n');
    assert.equal(lines[3], '#EXT-X-TARGETDURATION:5');
    assert.equal(lines[6], '#EXTINF:4.500,');
    assert.equal(mediaPlaylist(0, 4000).split('\n').length, 8); // six header lines, the end, and the last newline
  });
});

describe('what it plays', () => {
  test('serves the app\'s own fixture film with one edition, nothing to resume, no subtitles and Auto', async () => {
    const standIn = await start();
    const served = json(await request(standIn.port, { path: '/api/films/1' }));
    const fixture = JSON.parse(readFileSync(FILM_PATH, 'utf8'));
    assert.equal(served.title, fixture.title);
    assert.equal(served.editions.length, 1);
    assert.equal(served.editions[0].id, 11);
    assert.equal(served.editions[0].runtimeMs, 10_200_000);
    assert.equal(served.editions[0].positionMs, 0);
    assert.deepEqual(served.editions[0].subtitleStreams, []);
    assert.equal(served.progress.positionMs, 0);
    assert.deepEqual(served.preferences, { editionId: 11, quality: 'auto', audioStreamId: 111, subtitle: 'off' });
    assert.equal(served.editions[0].audioStreams.length, fixture.editions[0].audioStreams.length);
    assert.equal(fixture.editions[0].positionMs, 3871000); // the fixture itself is left as it was
  });

  test('is 2,550 segments of four seconds', async () => {
    const standIn = await start();
    assert.equal(standIn.segmentMs, 4000);
    assert.equal(standIn.segmentCount, 2550);
    assert.deepEqual(standIn.rungs.map((r) => r.id), ['original', '720p', '480p', '360p', '240p']);
  });

  test('answers the sign-in check and the sign-in with fixed bodies', async () => {
    const standIn = await start();
    const get = await request(standIn.port, { path: '/api/session' });
    assert.deepEqual(json(get), { authenticated: true });
    assert.equal(get.headers['content-type'], 'application/json');
    assert.equal(get.headers['cache-control'], 'no-store');
    assert.deepEqual(json(await post(standIn.port, '/api/session', { password: 'whatever' })), { ok: true });
    assert.deepEqual(json(await request(standIn.port, { method: 'DELETE', path: '/api/session' })), { ok: true });
  });
});

// ---- sessions -------------------------------------------------------------------------------------------------------

describe('a session', () => {
  test('answers with its id, its master address, its start rung and every rung', async () => {
    const standIn = await start();
    const answer = await post(standIn.port, '/api/editions/11/sessions', { quality: 'auto', probeKbps: 5000 });
    assert.equal(answer.status, 200);
    const created = json(answer);
    assert.match(created.sessionId, UUID);
    assert.equal(created.masterUrl, `/api/sessions/${created.sessionId}/master.m3u8`);
    assert.equal(created.startRung, '720p');
    assert.deepEqual(created.renditions, [
      { id: 'original', height: 1080, bitrate: 10_000_000, copy: true },
      { id: '720p', height: 720, bitrate: 2_500_000, copy: false },
      { id: '480p', height: 480, bitrate: 1_000_000, copy: false },
      { id: '360p', height: 360, bitrate: 600_000, copy: false },
      { id: '240p', height: 240, bitrate: 250_000, copy: false },
    ]);
  });

  test('starts on the rung the probe allows, the top with no probe and the floor for a probe of 0', async () => {
    const standIn = await start();
    assert.equal((await newSession(standIn, { quality: 'auto', probeKbps: 0 })).startRung, '240p');
    assert.equal((await newSession(standIn, { quality: 'auto', probeKbps: 1020 })).startRung, '360p');
    assert.equal((await newSession(standIn, { quality: 'auto', probeKbps: 1019 })).startRung, '240p');
    assert.equal((await newSession(standIn, { quality: 'auto', probeKbps: null })).startRung, 'original');
    assert.equal((await newSession(standIn, { quality: 'auto' })).startRung, 'original');
  });

  test('takes any other number as a probe as it is: a fraction, or the largest safe integer', async () => {
    const standIn = await start();
    assert.equal((await newSession(standIn, { quality: 'auto', probeKbps: 1019.9 })).startRung, '240p');
    assert.equal((await newSession(standIn, { quality: 'auto', probeKbps: 1020.5 })).startRung, '360p');
    const largest = await newSession(standIn, { quality: 'auto', probeKbps: Number.MAX_SAFE_INTEGER });
    assert.equal(largest.startRung, 'original');
    assert.equal(standIn.requests[0].probeKbps, 1019.9);
    assert.equal(standIn.requests[2].probeKbps, 9007199254740991);
  });

  test('a pinned quality starts there and lists that rung alone', async () => {
    const standIn = await start();
    const created = await newSession(standIn, { quality: '480p', probeKbps: 99_999 });
    assert.equal(created.startRung, '480p');
    const master = await request(standIn.port, { path: created.masterUrl });
    assert.equal(master.body.toString(), masterPlaylist(standIn.rungs, standIn.rungs[2], '480p'));
    assert.equal(master.headers['content-type'], 'application/vnd.apple.mpegurl');
  });

  // The refusals. Three messages are SessionService.create's own ("Invalid quality", "Invalid probe", "Quality not
  // available for this film"); "Invalid request" is the stand-in's for what it cannot read.
  const refused = async (body, error) => {
    const standIn = await start();
    const answer = await post(standIn.port, '/api/editions/11/sessions', body);
    assert.equal(answer.status, 400, String(body));
    assert.equal(answer.body.toString(), JSON.stringify({ error }), String(body));
    assert.equal(answer.headers['content-type'], 'application/json');
    return standIn;
  };

  test('a quality that is missing or null is refused: Invalid quality', async () => {
    await refused({}, 'Invalid quality');
    await refused({ probeKbps: 5000 }, 'Invalid quality');
    await refused({ quality: null }, 'Invalid quality');
  });

  test('a quality that is not text is refused: Invalid request', async () => {
    for (const quality of [7, true, {}, [], ['auto']]) await refused({ quality }, 'Invalid request');
  });

  test('a text that is no quality id CineFilm knows is refused: Invalid quality', async () => {
    for (const quality of ['bogus', 'AUTO', '', ' auto', '2160p', '720P']) await refused({ quality }, 'Invalid quality');
  });

  test('a known id that this ladder lacks, 1080p, is refused as the real server does for it', async () => {
    await refused({ quality: '1080p' }, 'Quality not available for this film');
  });

  test('a probe that is not a number is refused: Invalid request', async () => {
    for (const probeKbps of ['5000', true, [], {}, [5]]) await refused({ quality: 'auto', probeKbps }, 'Invalid request');
  });

  test('a negative probe is refused: Invalid probe', async () => {
    for (const probeKbps of [-1, -0.5, -Number.MAX_SAFE_INTEGER]) {
      await refused({ quality: 'auto', probeKbps }, 'Invalid probe');
    }
  });

  test('a probe that is not finite or is outside the safe-integer range is refused: Invalid request', async () => {
    // written as JSON numbers: 1e999 reads as Infinity
    for (const probe of ['1e300', '-1e300', '1e999', '-1e999', '9007199254740992', '-9007199254740992']) {
      await refused(`{"quality":"auto","probeKbps":${probe}}`, 'Invalid request');
    }
  });

  test('a quality is judged before a probe', async () => {
    await refused({ quality: 'bogus', probeKbps: -1 }, 'Invalid quality');
    await refused({ quality: 7, probeKbps: -1 }, 'Invalid request');
  });

  test('no body, or a body that is not a JSON object, is refused: Invalid request', async () => {
    for (const body of ['', '{"quality":', 'not json', '[]', '7', 'null', '"auto"']) {
      await refused(body, 'Invalid request');
    }
  });

  test('a refusal makes no session', async () => {
    const standIn = await refused({ quality: 'bogus' }, 'Invalid quality');
    assert.equal(standIn.requests.length, 1);
    assert.equal(standIn.requests[0].status, 400);
    assert.equal(standIn.requests[0].startRung, undefined);
  });

  test('refuses a body over 64 KB, and accepts one of exactly 64 KB', async () => {
    const standIn = await start();
    const pad = (size) => `{"quality":"auto","pad":"${'x'.repeat(size - 27)}"}`;
    assert.equal(Buffer.byteLength(pad(65536)), 65536);
    const fits = await post(standIn.port, '/api/editions/11/sessions', pad(65536));
    assert.equal(fits.status, 200);
    const over = await post(standIn.port, '/api/editions/11/sessions', pad(65537));
    assert.equal(over.status, 400);
    assert.equal(over.body.toString(), '{"error":"Invalid request"}');
  });

  test('only a POST makes a session', async () => {
    const standIn = await start();
    const answer = await request(standIn.port, { path: '/api/editions/11/sessions' });
    assert.equal(answer.status, 404);
    assert.equal(answer.body.toString(), '{"error":"Not found"}');
  });

  test('serves its master playlist, the media playlist of a rung, and 404 for a rung it does not have', async () => {
    const standIn = await start();
    const created = await newSession(standIn);
    const prefix = `/api/sessions/${created.sessionId}`;
    const master = await request(standIn.port, { path: `${prefix}/master.m3u8` });
    assert.equal(master.body.toString(), masterPlaylist(standIn.rungs, standIn.rungs[1], null));
    const playlist = await request(standIn.port, { path: `${prefix}/360p/index.m3u8` });
    assert.equal(playlist.status, 200);
    assert.equal(playlist.headers['content-type'], 'application/vnd.apple.mpegurl');
    assert.equal(playlist.body.toString(), mediaPlaylist(2550, 4000));
    const lines = playlist.body.toString().split('\n');
    assert.deepEqual(lines.slice(0, 6), [
      '#EXTM3U', '#EXT-X-VERSION:3', '#EXT-X-PLAYLIST-TYPE:VOD', '#EXT-X-TARGETDURATION:4', '#EXT-X-MEDIA-SEQUENCE:0',
      '#EXT-X-INDEPENDENT-SEGMENTS',
    ]);
    assert.equal(lines.filter((line) => line.startsWith('#EXTINF')).length, 2550);
    assert.deepEqual(lines.slice(-4), ['#EXTINF:4.000,', 'seg-2549.ts', '#EXT-X-ENDLIST', '']);
    for (const path of ['999p/index.m3u8', '1080p/index.m3u8', 'master.m3u9', '360p/other.m3u8']) {
      const missing = await request(standIn.port, { path: `${prefix}/${path}` });
      assert.equal(missing.status, 404, path);
      assert.equal(missing.body.toString(), '{"error":"Not found"}');
    }
  });

  test('an unknown session is a 404 in the server\'s words, on every path', async () => {
    const standIn = await start();
    for (const path of ['master.m3u8', '360p/index.m3u8', '360p/seg-0.ts', '']) {
      const answer = await request(standIn.port, { path: `/api/sessions/${NO_SESSION}/${path}` });
      assert.equal(answer.status, 404, path);
      assert.equal(answer.body.toString(), '{"error":"No such session"}');
    }
  });

  test('ending a session answers 204 with no body, and the session is gone', async () => {
    const standIn = await start();
    const created = await newSession(standIn);
    const ended = await request(standIn.port, { method: 'DELETE', path: `/api/sessions/${created.sessionId}` });
    assert.equal(ended.status, 204);
    assert.equal(ended.body.length, 0);
    assert.equal((await request(standIn.port, { path: created.masterUrl })).status, 404);
    // ending one that is not there is the same
    assert.equal((await request(standIn.port, { method: 'DELETE', path: `/api/sessions/${NO_SESSION}` })).status, 204);
  });
});

// ---- segments -------------------------------------------------------------------------------------------------------

describe('a segment', () => {
  test('is video/mp2t of its rung\'s average bitrate times 1.08 by default, to whole packets', async () => {
    assert.equal(WIRE, 1.08);
    const standIn = await start();
    const { sessionId } = await newSession(standIn);
    const prefix = `/api/sessions/${sessionId}`;
    const a = await request(standIn.port, { path: `${prefix}/360p/seg-3.ts` });
    assert.equal(a.status, 200);
    assert.equal(a.headers['content-type'], 'video/mp2t');
    assert.equal(a.headers['cache-control'], 'no-store');
    assert.equal(a.body.length, 375_812); // 696,000 x 1.08 / 2 = 375,840 bytes, down to 1,999 packets
    assert.equal(a.body[0], 0x47);
    assert.ok(a.body.equals(buildSegment(template, 3, 375_840, 'media')));
    assert.equal((await request(standIn.port, { path: `${prefix}/240p/seg-0.ts` })).body.length, 169_388); // 169,560
  });

  test('weighs the bare average bitrate at weight 1', async () => {
    const standIn = await start({ wire: 1 });
    const { sessionId } = await newSession(standIn);
    const a = await request(standIn.port, { path: `/api/sessions/${sessionId}/360p/seg-3.ts` });
    assert.equal(a.body.length, 347_988); // 348,000 bytes down to 1,851 packets
    assert.ok(a.body.equals(buildSegment(template, 3, 348_000, 'media')));
  });

  test('weighs half as much again at weight 1.5, the most allowed', async () => {
    const standIn = await start({ wire: 1.5 });
    const { sessionId } = await newSession(standIn);
    const a = await request(standIn.port, { path: `/api/sessions/${sessionId}/360p/seg-0.ts` });
    assert.equal(a.body.length, 521_888); // 522,000 bytes
  });

  test('is padded with null packets when the stand-in is made with fill null', async () => {
    const standIn = await start({ fill: 'null' });
    const { sessionId } = await newSession(standIn);
    const a = await request(standIn.port, { path: `/api/sessions/${sessionId}/240p/seg-2.ts` });
    assert.ok(a.body.equals(buildSegment(template, 2, 169_560, 'null')));
  });

  test('the last segment answers and the one after it does not', async () => {
    const standIn = await start();
    const { sessionId } = await newSession(standIn, { quality: '240p' });
    const prefix = `/api/sessions/${sessionId}`;
    assert.equal((await request(standIn.port, { path: `${prefix}/240p/seg-2549.ts` })).status, 200);
    const after2549 = await request(standIn.port, { path: `${prefix}/240p/seg-2550.ts` });
    assert.equal(after2549.status, 404);
    assert.equal(after2549.body.toString(), '{"error":"Not found"}');
    assert.equal((await request(standIn.port, { path: `${prefix}/240p/seg-99999999999999999999.ts` })).status, 404);
    assert.equal((await request(standIn.port, { path: `${prefix}/999p/seg-0.ts` })).status, 404);
  });

  test('an error while making it is a fixed 500 that says nothing of the error', async () => {
    const standIn = await start({ template: makeTemplate({ silence: 'not bytes' }) });
    const { sessionId } = await newSession(standIn);
    const answer = await request(standIn.port, { path: `/api/sessions/${sessionId}/240p/seg-0.ts` });
    assert.equal(answer.status, 500);
    assert.equal(answer.headers['content-type'], 'text/plain');
    assert.equal(answer.body.toString(), 'Internal error');
    assert.equal(standIn.requests.filter((r) => r.kind === 'segment').length, 0);
  });
});

describe('the speed probe', () => {
  test('answers 206 with the range headers of a 512 MB file of zeros', async () => {
    const standIn = await start();
    const seen = { status: null, headers: null, bytes: 0, nonZero: 0 };
    const req = http.request({ host: '127.0.0.1', port: standIn.port, path: '/api/editions/11/stream', agent: false }, (res) => {
      seen.status = res.statusCode;
      seen.headers = res.headers;
      res.on('data', (part) => {
        seen.bytes += part.length;
        seen.nonZero += part.filter((b) => b !== 0).length;
        if (seen.bytes >= 100_000) req.destroy();
      });
      res.on('error', () => {
        // the player cuts it on purpose
      });
    });
    pending.add(req);
    req.on('error', () => {
      // the player cuts it on purpose
    });
    req.end();
    await waitFor(() => entryOf(standIn, 'probe')?.aborted, 'the probe to be cut');
    assert.equal(seen.status, 206);
    assert.equal(seen.headers['content-type'], 'video/mp4');
    assert.equal(seen.headers['content-range'], 'bytes 0-536870911/536870912');
    assert.equal(seen.headers['accept-ranges'], 'bytes');
    assert.equal(seen.headers['content-length'], '536870912');
    assert.equal(seen.headers['cache-control'], 'no-store');
    assert.equal(seen.nonZero, 0);
    assert.ok(seen.bytes >= 100_000);
  });

  test('is logged as cut short when the player stops reading', async () => {
    const standIn = await start();
    const seen = open(standIn.port, { path: '/api/editions/11/stream' });
    await waitFor(() => seen.bytes >= 70_000, 'the probe\'s first bytes');
    seen.req.destroy();
    const entry = entryOf(standIn, 'probe');
    await waitFor(() => entry.aborted, 'the log to say the probe was cut');
    assert.equal(entry.status, 206);
    assert.equal(entry.bytes, 536_870_912);
    assert.ok(entry.sent >= 70_000 && entry.sent < entry.bytes);
    assert.equal(entry.doneMs, null);
    await waitFor(async () => (await stateOf(standIn)).active === 0, 'the probe to leave the link');
  });
});

// ---- what it keeps of what the player says ----------------------------------------------------------------------

const FULL_REPORT = {
  seq: 7, editionId: 11, elapsedMs: 600_000, environment: 'train', operator: 'A Network Operator Ltd', latencyMs: 115,
  engine: 'hlsjs', quality: 'auto', autoplayRefused: false, startupMs: 900, watchedMs: 590_000, stallCount: 2,
  stallMs: 4000, longestStallMs: 3000, seekCount: 1, seekWaitMs: 500, reloadCount: 0, reloadMs: 0, reconnectCount: 0,
  reconnectMs: 0, recoveries: 0, fellBack: false, stepsDown: 3, stepsUp: 2, netErrors: 1, timeouts: 1, kbpsLow: 100,
  kbpsMedian: 900, kbpsHigh: 5000, kbpsSamples: 40, startPositionMs: 12_000, endPositionMs: 600_000, rebanksStarted: 2, rebanksDone: 1, rebanksUndone: 1, marksForgotten: 3, rungMs: { '720p': 1000, '360p': 2000 },
  minutes: [[0, 900, 4, 50_000, 1000], [1, 800, 3, 40_000, 0, 12, 3]],
  client: { userAgent: 'Mozilla/5.0 (A Test Browser)', screen: '1920x1080', cores: 8, touch: false },
};

async function report(standIn, body, viewing = '22222222-2222-2222-2222-222222222222') {
  const answer = await put(standIn.port, `/api/playback-health/${viewing}`, body);
  return { answer, entry: standIn.healthReports[standIn.healthReports.length - 1] };
}

describe('a health report', () => {
  test('keeps its known figures and flags and the words from its lists, and answers 204', async () => {
    const standIn = await start();
    const { answer, entry } = await report(standIn, FULL_REPORT);
    assert.equal(answer.status, 204);
    assert.equal(answer.body.length, 0);
    assert.deepEqual(Object.keys(entry), ['at', 'ms', 'minute', 'viewing', 'report']);
    assert.equal(typeof entry.at, 'number');
    assert.equal(entry.ms, null); // the replay clock has not started
    assert.equal(entry.minute, null);
    assert.equal(entry.viewing, 0);
    const { operator, client, ...kept } = FULL_REPORT;
    assert.equal(operator, 'A Network Operator Ltd');
    assert.equal(client.cores, 8);
    assert.deepEqual(entry.report, kept);
  });

  test('keeps every number field of the server HealthReport record, so the list cannot drift behind it', async () => {
    const source = readFileSync(new URL('../../../backend/src/main/java/gregochr/cinefilm/health/HealthDtos.java', import.meta.url), 'utf8');
    // Comments and annotations out first, so neither hides a component.
    const text = source
      .slice(source.indexOf('public record HealthReport('))
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .replace(/\/\/[^\n]*/g, '')
      .replace(/@\w+(\.\w+)*(\s*\([^)]*\))?/g, '');
    const open = text.indexOf('(');
    let depth = 0;
    let end = open;
    for (; end < text.length; end += 1) {
      if (text[end] === '(') depth += 1;
      if (text[end] === ')' && (depth -= 1) === 0) break;
    }
    // Split at the commas outside any generic's brackets.
    const pieces = [];
    let angle = 0;
    let from = open + 1;
    let commas = 0;
    for (let i = open + 1; i < end; i += 1) {
      if (text[i] === '<') angle += 1;
      if (text[i] === '>') angle -= 1;
      if (text[i] === ',' && angle === 0) {
        commas += 1;
        pieces.push(text.slice(from, i));
        from = i + 1;
      }
    }
    pieces.push(text.slice(from, end));
    const components = pieces.map((piece) => piece.trim().split(/\s+/)).map((words) => ({ type: words.at(-2), name: words.at(-1) }));
    assert.ok(components.every(({ type, name }) => type && name), 'a component of HealthReport could not be read');
    assert.equal(components.length, commas + 1, 'a component was lost reading HealthReport');
    const NUMERIC = new Set(['Long', 'long', 'Integer', 'int', 'Short', 'short', 'Double', 'double', 'Float', 'float', 'BigDecimal']);
    const numbers = components.filter(({ type }) => NUMERIC.has(type)).map(({ name }) => name);
    assert.ok(numbers.length >= 30, `read ${numbers.length} number fields from HealthReport`);
    assert.ok(numbers.includes('marksForgotten') && numbers.includes('seq'));
    const standIn = await start();
    const { entry } = await report(standIn, Object.fromEntries(numbers.map((name) => [name, 1])));
    const lost = numbers.filter((name) => !(name in entry.report));
    assert.deepEqual(lost, [], `REPORT_NUMBERS in standin.mjs lacks: ${lost.join(', ')}`);
    assert.deepEqual(Object.keys(entry.report).sort(), [...numbers].sort());
  });

  test('drops the operator, the client block and any other key, whatever it is called', async () => {
    const standIn = await start();
    const body = '{"seq":3,"operator":"Op","client":{"userAgent":"UA"},"secretKey":"s","__proto__":{"polluted":true},"constructor":{"x":1},"toString":"t"}';
    const { entry } = await report(standIn, body);
    assert.deepEqual(entry.report, { seq: 3 });
    assert.equal(Object.getPrototypeOf(entry.report), Object.prototype);
    assert.equal({}.polluted, undefined);
  });

  test('maps an unknown engine, environment or quality to other, and leaves an absent one out', async () => {
    const standIn = await start();
    const { entry } = await report(standIn, { engine: 'wasm', environment: 'on the Moon', quality: '999p' });
    assert.deepEqual(entry.report, { engine: 'other', environment: 'other', quality: 'other' });
    assert.deepEqual((await report(standIn, { engine: 7 })).entry.report, { engine: 'other' });
    assert.deepEqual((await report(standIn, { engine: null })).entry.report, {});
    assert.deepEqual((await report(standIn, {})).entry.report, {});
    for (const [engine, environment, quality] of [['native', 'home', '1080p'], ['direct', 'away', 'original'], ['hlsjs', 'unknown', '240p']]) {
      assert.deepEqual((await report(standIn, { engine, environment, quality })).entry.report, { engine, environment, quality });
    }
  });

  test('keeps a figure only when it is a finite number, and a flag only when it is a boolean', async () => {
    const standIn = await start();
    const { entry } = await report(standIn, '{"seq":"7","stallMs":1e999,"watchedMs":12.5,"stallCount":null,"timeouts":[1],"fellBack":"yes","autoplayRefused":true,"stepsUp":0}');
    assert.deepEqual(entry.report, { watchedMs: 12.5, stepsUp: 0, autoplayRefused: true });
  });

  test('keeps time on a rung only by the ladder\'s own ids, as figures', async () => {
    const standIn = await start();
    const rungMs = { original: 1, '720p': 2, '480p': 3, '360p': 4, '240p': 5, '1080p': 6, bogus: 7, '': 8 };
    assert.deepEqual((await report(standIn, { rungMs })).entry.report.rungMs, { original: 1, '720p': 2, '480p': 3, '360p': 4, '240p': 5 });
    assert.deepEqual((await report(standIn, { rungMs: { '360p': 'a lot', '240p': null } })).entry.report.rungMs, {});
    assert.equal('rungMs' in (await report(standIn, { rungMs: [1, 2] })).entry.report, false);
    assert.equal('rungMs' in (await report(standIn, { rungMs: 'x' })).entry.report, false);
  });

  test('keeps only well-formed minute rows: five or seven finite numbers', async () => {
    const standIn = await start();
    const minutes = [
      [0, 1, 2, 3, 4], [1, 2, 3, 4, 5, 6], ['1', 2, 3, 4, 5], [1, 2, 3, 4, null], 'row', null, [],
      [1, 2, 3, 4, 5, 6, 7], [1, 2, 3, 4, 5, 6, 7, 8], [-1, 0, 0, 0, 0],
    ];
    const kept = (await report(standIn, { minutes })).entry.report.minutes;
    assert.deepEqual(kept, [[0, 1, 2, 3, 4], [1, 2, 3, 4, 5, 6, 7], [-1, 0, 0, 0, 0]]);
    assert.notEqual(kept[0], minutes[0]); // a copy, not the parsed row
    assert.equal('minutes' in (await report(standIn, { minutes: 'x' })).entry.report, false);
  });

  test('keeps at most 1,000 minute rows', async () => {
    const standIn = await start();
    const minutes = Array.from({ length: 1200 }, (_, i) => [i, 1, 2, 3, 4]);
    const kept = (await report(standIn, { minutes })).entry.report.minutes;
    assert.equal(kept.length, 1000);
    assert.deepEqual(kept[999], [999, 1, 2, 3, 4]);
  });

  test('numbers the viewings in the order met and keeps no id', async () => {
    const standIn = await start();
    const first = '33333333-3333-3333-3333-333333333333';
    await report(standIn, { seq: 1 }, first);
    await report(standIn, { seq: 2 }, OTHER_VIEWING);
    await report(standIn, { seq: 3 }, first);
    await report(standIn, 'unreadable', OTHER_VIEWING);
    assert.deepEqual(standIn.healthReports.map((r) => r.viewing), [0, 1, 0, 1]);
    const text = JSON.stringify(standIn.healthReports);
    assert.equal(text.includes(first), false);
    assert.equal(text.includes(OTHER_VIEWING), false);
  });

  test('that is not JSON keeps only its length, in bytes', async () => {
    const standIn = await start();
    const { answer, entry } = await report(standIn, 'not json at all');
    assert.equal(answer.status, 204);
    assert.deepEqual(Object.keys(entry), ['at', 'viewing', 'unreadable', 'bytes']);
    assert.equal(entry.unreadable, true);
    assert.equal(entry.bytes, 15);
    assert.equal((await report(standIn, '\u00e9')).entry.bytes, 2);
    assert.equal((await report(standIn, '')).entry.bytes, 0);
    assert.equal(JSON.stringify(standIn.healthReports).includes('not json'), false);
  });

  test('that is JSON but not an object is unreadable too', async () => {
    const standIn = await start();
    for (const [body, bytes] of [['[1,2]', 5], ['null', 4], ['7', 1], ['"a report"', 10]]) {
      const { entry } = await report(standIn, body);
      assert.deepEqual({ unreadable: entry.unreadable, bytes: entry.bytes }, { unreadable: true, bytes });
      assert.equal('report' in entry, false);
    }
  });

  test('over 64 KB is refused with 400 and kept nowhere; exactly 64 KB is kept', async () => {
    const standIn = await start();
    const pad = (size) => `{"seq":1,"pad":"${'x'.repeat(size - 18)}"}`;
    assert.equal(Buffer.byteLength(pad(65536)), 65536);
    const fits = await report(standIn, pad(65536));
    assert.equal(fits.answer.status, 204);
    assert.deepEqual(fits.entry.report, { seq: 1 });
    const over = await report(standIn, pad(65537));
    assert.equal(over.answer.status, 400);
    assert.equal(over.answer.body.toString(), '{"error":"Invalid request"}');
    assert.equal(standIn.healthReports.length, 1);
  });

  test('is kept only from a PUT to a playback id', async () => {
    const standIn = await start();
    const viewing = '22222222-2222-2222-2222-222222222222';
    assert.equal((await request(standIn.port, { path: `/api/playback-health/${viewing}` })).status, 404);
    assert.equal((await post(standIn.port, `/api/playback-health/${viewing}`, { seq: 1 })).body.toString(), 'null');
    assert.equal((await put(standIn.port, '/api/playback-health/not-an-id', { seq: 1 })).body.toString(), 'null');
    assert.equal(standIn.healthReports.length, 0);
  });

  test('carries the replay clock and minute once the clock has started', async () => {
    const standIn = await start({ trace: [100, 200, 300], startMinute: 1 });
    standIn.startClock();
    const { entry } = await report(standIn, { seq: 1 });
    assert.equal(entry.minute, 1);
    assert.ok(entry.ms >= 0 && entry.ms < 60_000);
    assert.ok(Math.abs(entry.at - Date.now()) < 60_000);
  });
});

describe('progress', () => {
  const sent = async (standIn, body) => {
    const answer = await post(standIn.port, '/api/films/1/progress', body);
    assert.equal(answer.status, 200);
    return json(answer);
  };

  test('answers the number sent, and 0 for anything else', async () => {
    const standIn = await start();
    assert.deepEqual(await sent(standIn, { positionMs: 12_345 }), { positionMs: 12_345, watched: false });
    assert.deepEqual(await sent(standIn, { positionMs: 0 }), { positionMs: 0, watched: false });
    assert.deepEqual(await sent(standIn, { positionMs: 1.5 }), { positionMs: 1.5, watched: false });
    assert.deepEqual(await sent(standIn, { positionMs: -3 }), { positionMs: -3, watched: false });
    for (const body of [
      { positionMs: '12' }, { positionMs: null }, { positionMs: [1] }, { positionMs: {} }, { positionMs: true },
      {}, [], 'text', '{"positionMs":1e999}', '', 'null', '7',
    ]) {
      assert.deepEqual(await sent(standIn, body), { positionMs: 0, watched: false }, JSON.stringify(body));
    }
  });

  test('is logged with the number, or null when there was none', async () => {
    const standIn = await start();
    await sent(standIn, { positionMs: 42 });
    await sent(standIn, { positionMs: 'x' });
    assert.deepEqual(standIn.requests.map((r) => [r.kind, r.positionMs]), [['progress', 42], ['progress', null]]);
  });

  test('refuses a body over 64 KB', async () => {
    const standIn = await start();
    const answer = await post(standIn.port, '/api/films/1/progress', `{"positionMs":1,"pad":"${'x'.repeat(65536)}"}`);
    assert.equal(answer.status, 400);
    assert.equal(answer.body.toString(), '{"error":"Invalid request"}');
  });
});

describe('everything else the player might write', () => {
  test('answers a fixed null whatever the body, and echoes nothing', async () => {
    const standIn = await start();
    const marker = 'ECHO-MARKER-9f3a';
    for (const [method, path, body] of [
      ['PUT', '/api/preferences', `{"x":"${marker}"}`],
      ['POST', '/api/playback-state', marker],
      ['PATCH', '/api/anything/at/all', `{"quality":"${marker}"}`],
      ['DELETE', `/api/${marker}`, undefined],
      ['PUT', '/api/preferences', ''],
    ]) {
      const answer = await request(standIn.port, { method, path, body });
      assert.equal(answer.status, 200, `${method} ${path}`);
      assert.equal(answer.body.toString(), 'null');
      assert.equal(answer.headers['content-type'], 'application/json');
    }
    assert.equal(JSON.stringify(standIn.requests).includes(marker), false);
  });

  test('answers a GET for anything unknown with a fixed 404', async () => {
    const standIn = await start();
    for (const path of ['/api/editions/11/subtitles/113', '/api/art/abc.jpg', '/api/unknown?x=ECHO-1', '/api/']) {
      const answer = await request(standIn.port, { path });
      assert.equal(answer.status, 404, path);
      assert.equal(answer.body.toString(), '{"error":"Not found"}');
    }
    assert.deepEqual([...new Set(standIn.requests.map((r) => r.kind))], ['other']);
  });
});

// ---- the app's own files -----------------------------------------------------------------------------------------

describe('the app\'s files', () => {
  test('a file of the build answers its exact bytes and type', async () => {
    const standIn = await start();
    const get = (path) => request(standIn.port, { path });
    const js = await get('/assets/app.js');
    assert.equal(js.status, 200);
    assert.equal(js.body.toString(), 'console.log("app");');
    assert.equal(js.headers['content-type'], 'text/javascript');
    assert.equal(js.headers['cache-control'], 'no-store');
    assert.equal((await get('/assets/style.css')).headers['content-type'], 'text/css');
    assert.equal((await get('/favicon.svg')).headers['content-type'], 'image/svg+xml');
    const font = await get('/assets/font.woff2');
    assert.ok(font.body.equals(FONT));
    assert.equal(font.headers['content-type'], 'font/woff2');
    const other = await get('/assets/data.bin');
    assert.deepEqual([...other.body], [9, 8, 7]);
    assert.equal(other.headers['content-type'], 'application/octet-stream');
    assert.equal((await get('/index.html')).body.toString(), INDEX);
  });

  test('an app route, with no extension, answers the page', async () => {
    const standIn = await start();
    for (const path of ['/', '/films/1/play', '/admin', '/films/1/play?start=0', '/some/deep/route']) {
      const answer = await request(standIn.port, { path });
      assert.equal(answer.status, 200, path);
      assert.equal(answer.body.toString(), INDEX);
      assert.equal(answer.headers['content-type'], 'text/html; charset=utf-8');
      assert.equal(answer.headers['cache-control'], 'no-store');
    }
  });

  test('a file with an extension that the build does not hold is a 404', async () => {
    const standIn = await start();
    for (const path of ['/missing.js', '/assets/nope.css', '/robots.txt', '/assets/app.js.map']) {
      const answer = await request(standIn.port, { path });
      assert.equal(answer.status, 404, path);
      assert.equal(answer.body.toString(), 'Not found');
      assert.equal(answer.headers['content-type'], 'text/plain');
    }
  });

  test('a file added to the build after start-up is a 404, and one changed is served as it was', async () => {
    const dist = makeBuild('dist-late');
    const standIn = await start({ distDir: dist });
    writeFileSync(join(dist, 'assets', 'late.js'), 'late();');
    writeFileSync(join(dist, 'assets', 'app.js'), 'changed();');
    assert.equal((await request(standIn.port, { path: '/assets/late.js' })).status, 404);
    assert.equal((await request(standIn.port, { path: '/assets/app.js' })).body.toString(), 'console.log("app");');
  });

  test('a path that climbs out of the build never returns a file from outside it', async () => {
    const standIn = await start();
    for (const path of [
      '/../outside.txt',
      '/%2e%2e/outside.txt',
      '/%2E%2E%2Foutside.txt',
      '/..%2foutside.txt',
      '/../dist-secret/secret.txt',
      '/%2e%2e/dist-secret/secret.txt',
      '/assets/../../dist-secret/secret.txt',
      '/assets/%2e%2e/%2e%2e/dist-secret/secret.txt',
      '/..%5cdist-secret%5csecret.txt',
      '/../dist-secret/secret',
      '/%2e%2e/dist-secret/secret',
      '/../../../../../../../../etc/passwd',
    ]) {
      const answer = await request(standIn.port, { path });
      const text = answer.body.toString();
      assert.equal(text.includes('SECRET'), false, path);
      assert.equal(text.includes('root:'), false, path);
      // with an extension it is a file the build does not hold; without one it is an app route
      assert.deepEqual([answer.status, text], /\.[a-z]+$/.test(path) ? [404, 'Not found'] : [200, INDEX], path);
    }
  });
});

describe('an address that cannot be read', () => {
  test('answers a fixed 400 and is not logged', async () => {
    const standIn = await start();
    for (const path of ['//', '//host:99999/x']) {
      const answer = await request(standIn.port, { path });
      assert.equal(answer.status, 400, path);
      assert.equal(answer.body.toString(), 'Bad request');
      assert.equal(answer.headers['content-type'], 'text/plain');
    }
    assert.equal(standIn.requests.length, 0);
  });
});

// ---- the request log ---------------------------------------------------------------------------------------------

describe('the request log', () => {
  test('has each answer with its kind, status and bytes, in order', async () => {
    const standIn = await start();
    const created = await newSession(standIn, { quality: 'auto', probeKbps: 5000 });
    const prefix = `/api/sessions/${created.sessionId}`;
    await request(standIn.port, { path: `${prefix}/master.m3u8` });
    await request(standIn.port, { path: `${prefix}/720p/index.m3u8` });
    await request(standIn.port, { path: `${prefix}/240p/seg-1.ts` });
    await put(standIn.port, '/api/playback-health/22222222-2222-2222-2222-222222222222', { seq: 1 });
    await post(standIn.port, '/api/films/1/progress', { positionMs: 9 });
    await request(standIn.port, { method: 'PUT', path: '/api/preferences', body: '{}' });
    await request(standIn.port, { path: '/api/nothing' });
    await request(standIn.port, { method: 'DELETE', path: prefix });
    await put(standIn.port, '/api/playback-health/22222222-2222-2222-2222-222222222222', 'x'.repeat(65537));
    const shown = standIn.requests.map((r) => [r.kind, r.method, r.status, r.bytes]);
    assert.deepEqual(shown, [
      ['session', 'POST', 200, Buffer.byteLength(JSON.stringify(created))],
      ['master', 'GET', 200, Buffer.byteLength(masterPlaylist(standIn.rungs, standIn.rungs[1], null))],
      ['playlist', 'GET', 200, Buffer.byteLength(mediaPlaylist(2550, 4000))],
      ['segment', 'GET', 200, 169_388],
      ['health', 'PUT', 204, 0],
      ['progress', 'POST', 200, Buffer.byteLength('{"positionMs":9,"watched":false}')],
      ['other', 'PUT', 200, 4],
      ['other', 'GET', 404, Buffer.byteLength('{"error":"Not found"}')],
      ['session-end', 'DELETE', 204, 0],
      ['refused', 'PUT', 400, Buffer.byteLength('{"error":"Invalid request"}')],
    ]);
  });

  test('has the rung and segment of a segment, the probe and start rung of a session, and figures', async () => {
    const standIn = await start();
    const created = await newSession(standIn, { quality: 'auto', probeKbps: 5000 });
    await request(standIn.port, { path: `/api/sessions/${created.sessionId}/480p/seg-7.ts` });
    const [session, segment] = standIn.requests;
    assert.deepEqual(
      { probeKbps: session.probeKbps, startRung: session.startRung, quality: session.quality },
      { probeKbps: 5000, startRung: '720p', quality: 'auto' },
    );
    assert.deepEqual(Object.keys(segment).sort(), [
      'aborted', 'at', 'bytes', 'doneMs', 'firstByteMs', 'kind', 'method', 'minute', 'ms', 'rung', 'segment', 'sent', 'status',
    ]);
    assert.equal(segment.rung, '480p');
    assert.equal(segment.segment, 7);
    assert.equal(segment.sent, segment.bytes);
    assert.equal(segment.aborted, false);
    assert.equal(typeof segment.doneMs, 'number');
    assert.equal(typeof segment.firstByteMs, 'number');
    assert.equal(segment.minute, null); // the clock has not started
    assert.equal(segment.ms, null);
  });

  test('holds no address, no session id and no request text', async () => {
    const standIn = await start();
    const created = await newSession(standIn);
    const prefix = `/api/sessions/${created.sessionId}`;
    await request(standIn.port, { path: `${prefix}/360p/seg-2.ts` });
    await request(standIn.port, { path: '/api/films/1?token=LOG-MARKER' });
    await request(standIn.port, { method: 'PUT', path: '/api/LOG-MARKER', body: 'LOG-MARKER', headers: { 'X-Marker': 'LOG-MARKER' } });
    const text = JSON.stringify(standIn.requests);
    for (const word of ['/api', 'sessions', created.sessionId, 'seg-', 'LOG-MARKER', 'index.m3u8', 'films']) {
      assert.equal(text.includes(word), false, word);
    }
    for (const entry of standIn.requests) {
      assert.equal(Object.values(entry).some((value) => typeof value === 'string' && value.startsWith('/')), false);
    }
  });

  test('names a method outside its fixed set other', async () => {
    const standIn = await start();
    const answer = await request(standIn.port, { method: 'PURGE', path: '/api/cache', body: '{}' });
    assert.equal(answer.status, 200);
    assert.equal(standIn.requests[0].method, 'other');
    assert.equal(standIn.requests[0].kind, 'other');
  });

  test('is the array the stand-in returns, and fills as answers are made', async () => {
    const standIn = await start();
    assert.deepEqual(standIn.requests, []);
    await request(standIn.port, { path: '/api/session' });
    assert.equal(standIn.requests.length, 1);
    assert.equal(standIn.requests[0].kind, 'api');
  });
});

// ---- the link -------------------------------------------------------------------------------------------------------

describe('the link', () => {
  test('each answer waits the round-trip time before its first byte', async () => {
    const standIn = await start({ rttMs: 40 });
    const answer = await request(standIn.port, { path: '/api/session' });
    assert.equal(answer.status, 200);
    assert.ok(standIn.requests[0].firstByteMs >= 35, `first byte after ${standIn.requests[0].firstByteMs} ms`);
  });

  test('the app\'s own files are served at once, on a dead link too', async () => {
    const { standIn } = await startDead();
    const answer = await request(standIn.port, { path: '/assets/app.js' });
    assert.equal(answer.status, 200);
    assert.equal(answer.body.toString(), 'console.log("app");');
    assert.equal((await request(standIn.port, { path: '/films/1/play' })).body.toString(), INDEX);
  });

  test('not even headers leave before the first bytes may: a segment on a dead link has received nothing', async () => {
    const { standIn, segment } = await startDead();
    const seen = open(standIn.port, { path: segment });
    await waitFor(async () => (await stateOf(standIn)).active === 1, 'the segment to join the link');
    const entry = entryOf(standIn, 'segment');
    assert.equal(seen.status, null);
    assert.equal(seen.bytes, 0);
    assert.equal(entry.sent, 0);
    assert.equal(entry.firstByteMs, null);
    assert.equal(entry.doneMs, null);
  });

  test('an answer with no body waits for a live link, and is logged as cut when the player goes', async () => {
    const { standIn, sessionId } = await startDead();
    const seen = open(standIn.port, { method: 'DELETE', path: `/api/sessions/${sessionId}` });
    await waitFor(() => entryOf(standIn, 'session-end'), 'the request to be logged');
    assert.equal(seen.status, null);
    const entry = entryOf(standIn, 'session-end');
    assert.equal(entry.firstByteMs, null);
    seen.req.destroy();
    await waitFor(() => entry.aborted, 'the log to say it was cut');
    assert.equal(entry.status, 204);
  });

  test('after finish(), answers that are not media go straight through while a segment already asked for has nothing', async () => {
    const { standIn, segment } = await startDead();
    const seen = open(standIn.port, { path: segment });
    await waitFor(async () => (await stateOf(standIn)).active === 1, 'the segment to join the link');
    standIn.finish();
    const answer = await post(standIn.port, '/api/films/1/progress', { positionMs: 7 });
    assert.equal(answer.status, 200);
    assert.deepEqual(json(answer), { positionMs: 7, watched: false });
    assert.equal(answer.body.length, JSON.stringify({ positionMs: 7, watched: false }).length);
    assert.equal(seen.status, null);
    const entry = entryOf(standIn, 'segment');
    assert.equal(entry.sent, 0);
    const progress = entryOf(standIn, 'progress');
    assert.equal(progress.doneMs, 0);
    assert.equal(progress.sent, progress.bytes);
    assert.equal(progress.aborted, false);
  });

  test('after finish(), a media answer asked for still waits for the link', async () => {
    const { standIn, segment } = await startDead();
    standIn.finish();
    const seen = open(standIn.port, { path: segment });
    await waitFor(async () => (await stateOf(standIn)).active === 1, 'the segment to join the link');
    assert.equal(seen.status, null);
    assert.equal(seen.bytes, 0);
    const probe = open(standIn.port, { path: '/api/editions/11/stream' });
    await waitFor(async () => (await stateOf(standIn)).active === 2, 'the probe to join the link');
    assert.equal(probe.bytes, 0);
  });

  test('an answer already waiting when finish() is called stays where it was, and is cut when the stand-in stops', async () => {
    const { standIn } = await startDead();
    const waiting = open(standIn.port, { method: 'POST', path: '/api/films/1/progress', body: '{"positionMs":5}' });
    await waitFor(async () => (await stateOf(standIn)).active === 1, 'the answer to join the link');
    standIn.finish();
    const later = await post(standIn.port, '/api/films/1/progress', { positionMs: 6 });
    assert.equal(later.status, 200);
    assert.equal(waiting.status, null);
    await standIn.stop();
    const entry = standIn.requests[standIn.requests.length - 2];
    assert.equal(entry.positionMs, 5);
    await waitFor(() => entry.aborted, 'the log to say it was cut');
    assert.equal(waiting.status, null);
    await waitFor(() => waiting.failed, 'the player to see the connection go');
  });

  test('a request after stop() is refused', async () => {
    const standIn = await start();
    await standIn.stop();
    await assert.rejects(request(standIn.port, { path: '/api/session' }), { code: 'ECONNREFUSED' });
  });

  test('a player that goes mid-answer leaves the link, and the log says aborted', async () => {
    const standIn = await start({ trace: [800] }); // 100,000 bytes a second
    const { sessionId } = await newSession(standIn, { quality: '360p' });
    standIn.startClock();
    const seen = open(standIn.port, { path: `/api/sessions/${sessionId}/360p/seg-0.ts` });
    await waitFor(() => seen.bytes >= 3000, 'the first bytes');
    const entry = entryOf(standIn, 'segment');
    assert.equal(seen.status, 200);
    assert.equal(entry.aborted, false);
    assert.equal(entry.doneMs, null);
    seen.req.destroy();
    await waitFor(() => entry.aborted, 'the log to say aborted');
    assert.equal(entry.bytes, 375_812);
    assert.ok(entry.sent >= 3000 && entry.sent < 375_812, `sent ${entry.sent}`);
    assert.equal(entry.doneMs, null);
    await waitFor(async () => (await stateOf(standIn)).active === 0, 'the segment to leave the link');
  });

  test('two answers share the allowance: both make progress and neither finishes first', async () => {
    const standIn = await start({ trace: [800] });
    const { sessionId } = await newSession(standIn, { quality: '360p' });
    standIn.startClock();
    const a = open(standIn.port, { path: `/api/sessions/${sessionId}/360p/seg-0.ts` });
    const b = open(standIn.port, { path: `/api/sessions/${sessionId}/360p/seg-1.ts` });
    await waitFor(() => a.bytes >= 2000 && b.bytes >= 2000, 'both to receive bytes');
    assert.equal(a.ended || b.ended, false);
    const [first, second] = standIn.requests.filter((r) => r.kind === 'segment');
    assert.ok(first.sent > 0 && second.sent > 0);
    assert.equal((await stateOf(standIn)).active, 2);
  });

  test('a finished answer is complete in the log: all bytes sent, a time to the end, not aborted', async () => {
    const standIn = await start();
    await request(standIn.port, { path: '/api/films/1' });
    const [entry] = standIn.requests;
    assert.equal(entry.sent, entry.bytes);
    assert.equal(entry.aborted, false);
    assert.ok(entry.doneMs >= entry.firstByteMs);
    assert.equal('cutMs' in entry, false);
  });

  test('an answer the player cuts has a time it was cut at, from the start of the request, and no end', async () => {
    // A round trip of 300 ms, so that the time of the first byte and the start of the request are far apart.
    const standIn = await start({ trace: [800], rttMs: 300 });
    const { sessionId } = await newSession(standIn, { quality: '360p' });
    standIn.startClock();
    const asked = performance.now();
    const seen = open(standIn.port, { path: `/api/sessions/${sessionId}/360p/seg-0.ts` });
    // The link keeps at most 5,000 bytes for later and sends them with the first byte; the other 25,000 go at 100,000 a
    // second. So the 30,000th byte comes usually about 250 ms after the first and never under 200 ms (a late tick can
    // let a second 5,000 out with it). The 375,812-byte segment is nowhere near finished.
    await waitFor(() => seen.bytes >= 30000, 'well past the first bytes');
    seen.req.destroy();
    const entry = entryOf(standIn, 'segment');
    await waitFor(() => entry.aborted, 'the log to say aborted');
    const now = Math.round(performance.now() - asked);
    const figures = `cut at ${entry.cutMs}, first byte at ${entry.firstByteMs}, ${now} ms since it was asked for`;
    assert.ok(entry.firstByteMs >= 290, `the first byte did not wait for the round trip: ${figures}`);
    assert.equal(typeof entry.cutMs, 'number', figures);
    assert.ok(Number.isInteger(entry.cutMs), figures);
    // Counted from the start of the request: it cannot be later than the time that has passed since it was asked for.
    assert.ok(entry.cutMs <= now + 1, `not from the start of the request: ${figures}`);
    // And it is the time of the cut, not of the first byte: never under 200 ms after it, so 150 leaves room, while a
    // time counted from the first byte (about 250 ms) stays well under firstByteMs + 150 (about 450).
    assert.ok(entry.cutMs >= entry.firstByteMs + 150, `not the time of the cut: ${figures}`);
    assert.equal(entry.doneMs, null);
  });

  test('bytes sent after the replay clock has passed into a later minute are counted in that minute', async (t) => {
    // Only Date is mocked: it is what the replay clock reads. The link's own timer reads performance.now().
    t.mock.timers.enable({ apis: ['Date'], now: Date.now() });
    const standIn = await start();
    standIn.startClock();
    await request(standIn.port, { path: '/api/films/1' });
    t.mock.timers.setTime(Date.now() + 120_000); // two minutes on, by the clock the server reads
    await request(standIn.port, { path: '/api/session' });
    const [first, second] = standIn.requests;
    assert.ok(first.sent > 0 && second.sent > 0, `sent ${first.sent} and ${second.sent}`);
    assert.equal(first.ms, 0); // Date is frozen, so the clock reads exactly what it was set to
    assert.equal(second.ms, 120000);
    assert.equal(second.minute - first.minute, 2);
    assert.deepEqual(standIn.delivered, [first.sent, 0, second.sent]);
  });

  test('a byte is counted in the minute it was let through, not the minute its answer was asked for', async (t) => {
    // Only Date is mocked: it is what the replay clock reads. The link's own timer reads performance.now().
    t.mock.timers.enable({ apis: ['Date'], now: Date.now() });
    const standIn = await start({ trace: [800] }); // 100,000 bytes a second in every minute
    const { sessionId } = await newSession(standIn, { quality: '360p' });
    standIn.startClock();
    const seen = open(standIn.port, { path: `/api/sessions/${sessionId}/360p/seg-0.ts` });
    await waitFor(() => seen.bytes > 0, 'the first bytes');
    const entry = entryOf(standIn, 'segment');
    // The clock passes into the next minute while the answer is being sent. Nothing is awaited between the next two
    // lines, so nothing can be sent between them: atJump is exactly what was let through in minute 0.
    t.mock.timers.setTime(Date.now() + 60_000);
    const atJump = entry.sent;
    await waitFor(() => entry.sent >= atJump + 10_000, 'bytes let through in the next minute');
    seen.req.destroy();
    await waitFor(() => entry.aborted, 'the log to say aborted');
    assert.equal(entry.minute, 0); // asked for in the first minute
    assert.equal(entry.ms, 0);
    assert.ok(atJump > 0, `nothing was sent before the clock moved: ${atJump}`);
    assert.deepEqual(standIn.delivered, [atJump, entry.sent - atJump]);
  });

  test('the bytes the link let through are counted by replay minute: exactly what the answers sent', async () => {
    const standIn = await start();
    assert.deepEqual(standIn.delivered, []);
    const { sessionId } = await newSession(standIn);
    standIn.startClock();
    await request(standIn.port, { path: '/api/films/1' });
    await request(standIn.port, { path: `/api/sessions/${sessionId}/360p/seg-0.ts` });
    await request(standIn.port, { path: `/api/sessions/${sessionId}/360p/seg-1.ts` });
    const after = standIn.requests.filter((r) => r.ms !== null);
    assert.equal(after.length, 3);
    const total = after.reduce((sum, r) => sum + r.sent, 0);
    assert.ok(total > 700_000, `sent ${total}`);
    assert.deepEqual(standIn.delivered, [total]);
  });

  test('bytes sent before the replay clock started are not counted', async () => {
    const standIn = await start();
    await newSession(standIn);
    await request(standIn.port, { path: '/api/films/1' });
    assert.ok(standIn.requests.every((r) => r.ms === null && r.sent > 0));
    assert.deepEqual(standIn.delivered, []);
    standIn.startClock();
    await request(standIn.port, { path: '/api/session' });
    const [counted] = standIn.requests.filter((r) => r.ms !== null);
    assert.deepEqual(standIn.delivered, [counted.sent]);
  });

  test('an API answer released at once after finish() is not counted', async () => {
    const standIn = await start();
    standIn.startClock();
    await request(standIn.port, { path: '/api/films/1' });
    const [counted] = standIn.requests;
    standIn.finish();
    const answer = await post(standIn.port, '/api/films/1/progress', { positionMs: 7 });
    assert.equal(answer.status, 200);
    assert.deepEqual(standIn.delivered, [counted.sent]);
    assert.equal(entryOf(standIn, 'progress').sent > 0, true);
  });
});

describe('a request that is cut off', () => {
  // Says it will send 1,000 bytes, sends `bytes` and goes.
  const cutOff = (standIn, method, path, bytes) => {
    const req = http.request({
      host: '127.0.0.1', port: standIn.port, method, path, agent: false, headers: { 'Content-Length': 1000 },
    });
    req.on('error', () => {
      // destroyed on purpose
    });
    pending.add(req);
    req.write(bytes, () => req.destroy());
  };

  const cases = [
    ['a health report', 'PUT', '/api/playback-health/22222222-2222-2222-2222-222222222222', 'health', '{"seq":1}'],
    ['a progress report', 'PUT', '/api/films/1/progress', 'progress', '{"positionMs":5}'],
    ['a session request', 'POST', '/api/editions/11/sessions', 'refused', '{"quality":"auto","probeKbps":5000}'],
  ];
  for (const [what, method, path, kind, whole] of cases) {
    for (const [how, bytes] of [['8 bytes', '12345678'], ['a whole JSON object that would be accepted', whole]]) {
      test(`${what} cut off after ${how} changes nothing and is logged once, as aborted`, async () => {
        const standIn = await start();
        cutOff(standIn, method, path, bytes);
        await waitFor(() => standIn.requests.length === 1 && standIn.requests[0].aborted, 'the cut request to be logged');
        assert.equal(standIn.requests.length, 1);
        assert.equal(standIn.requests[0].kind, kind); // never 'session': no session was made
        assert.equal(standIn.requests[0].aborted, true);
        assert.equal(standIn.requests[0].sent, 0);
        assert.equal(standIn.requests[0].positionMs, kind === 'progress' ? null : undefined);
        assert.equal(standIn.healthReports.length, 0);
        assert.equal((await stateOf(standIn)).active, 0);
        // no viewing was numbered: the next report is the first
        const next = await report(standIn, { seq: 1 }, OTHER_VIEWING);
        assert.equal(next.entry.viewing, 0);
      });
    }
  }

  test('an answer with no body that waited for a live link is dropped when its player goes, and never sent', async () => {
    const trace = [0];
    const standIn = await start({ trace });
    const { sessionId } = await newSession(standIn);
    standIn.startClock();
    const seen = open(standIn.port, { method: 'DELETE', path: `/api/sessions/${sessionId}` });
    await waitFor(() => entryOf(standIn, 'session-end'), 'the request to be logged');
    // A second answer, asked for after the first, joins the link only after the first has finished waiting for a
    // live one (their round-trip timers run in the order they were set).
    open(standIn.port, { method: 'POST', path: '/api/films/1/progress', body: '{}' });
    await waitFor(async () => (await stateOf(standIn)).active === 1, 'the second answer to join the link');
    seen.req.destroy();
    const entry = entryOf(standIn, 'session-end');
    await waitFor(() => entry.aborted, 'the log to say it was cut');
    trace[0] = 800; // the link comes back: the stand-in reads the array at every tick
    const answer = await request(standIn.port, { path: '/api/session' });
    assert.equal(answer.status, 200);
    assert.equal(entry.aborted, true);
    assert.equal(entry.firstByteMs, null);
    assert.equal(entry.doneMs, null);
    assert.equal(entry.sent, 0);
  });

  test('an answer whose player goes during its round trip is never written, and is logged as cut', async () => {
    const standIn = await start({ rttMs: 600 });
    const { sessionId } = await newSession(standIn, { quality: '360p' });
    standIn.startClock();
    const seen = open(standIn.port, { method: 'DELETE', path: `/api/sessions/${sessionId}` });
    await waitFor(() => entryOf(standIn, 'session-end'), 'the request to be logged');
    seen.req.destroy();
    const entry = entryOf(standIn, 'session-end');
    await waitFor(() => entry.aborted, 'the log to say it was cut');
    // The round trip ends before a request made now has finished its own (timers run in the order they were set).
    const answer = await request(standIn.port, { path: '/api/session' });
    assert.equal(answer.status, 200);
    assert.equal(entry.aborted, true);
    assert.equal(entry.firstByteMs, null);
    assert.equal(entry.doneMs, null);
    assert.equal(entry.sent, 0);
  });
});

describe('the replay clock', () => {
  test('before it starts the link is open at 400 Mbit/s and there is no minute', async () => {
    const standIn = await start({ trace: [100, 200], startMinute: 1 });
    assert.equal(standIn.clockMs(), null);
    assert.equal(standIn.minute(), null);
    assert.equal(standIn.kbps(), 400_000);
    assert.deepEqual(
      { ...(await stateOf(standIn)) },
      { clockMs: null, minute: null, kbps: 400_000, open: false, active: 0, requests: 0, reports: 0 },
    );
  });

  test('once started it runs from the start minute at the trace\'s rate', async () => {
    const standIn = await start({ trace: [100, 200, 300], startMinute: 1 });
    standIn.startClock();
    assert.ok(standIn.clockMs() >= 0 && standIn.clockMs() < 60_000);
    assert.equal(standIn.minute(), 1);
    assert.equal(standIn.kbps(), 200);
    const state = await stateOf(standIn);
    assert.equal(state.minute, 1);
    assert.equal(state.kbps, 200);
    assert.ok(state.clockMs >= 0);
  });

  test('past the end of the trace it keeps the last figure, and a start past it too', async () => {
    const standIn = await start({ trace: [100, 200, 300], startMinute: 7 });
    standIn.startClock();
    assert.equal(standIn.minute(), 7);
    assert.equal(standIn.kbps(), 300);
  });

  test('a dead minute is a rate of 0', async () => {
    const standIn = await start({ trace: [0, 500] });
    standIn.startClock();
    assert.equal(standIn.kbps(), 0);
  });

  test('finish() is shown in the state, and the blank page is fixed', async () => {
    const standIn = await start();
    assert.equal((await stateOf(standIn)).open, false);
    standIn.finish();
    assert.equal((await stateOf(standIn)).open, true);
    const blank = await request(standIn.port, { path: '/__replay/blank' });
    assert.equal(blank.status, 200);
    assert.equal(blank.headers['content-type'], 'text/html');
    assert.equal(blank.body.toString(), '<!doctype html><title>blank</title>');
  });

  test('the state counts what has been answered and reported', async () => {
    const standIn = await start();
    await request(standIn.port, { path: '/api/session' });
    await put(standIn.port, '/api/playback-health/22222222-2222-2222-2222-222222222222', { seq: 1 });
    const state = await stateOf(standIn);
    assert.equal(state.requests, 2);
    assert.equal(state.reports, 1);
  });
});

// ---- starting it ----------------------------------------------------------------------------------------------------

describe('starting', () => {
  test('listens on 127.0.0.1 only, on the free port it was given, and says which', async () => {
    const standIn = await start();
    assert.equal(standIn.address, '127.0.0.1');
    assert.ok(Number.isInteger(standIn.port) && standIn.port > 0);
    assert.notEqual(standIn.port, 5301);
    assert.notEqual(standIn.port, 9334);
    assert.equal((await request(standIn.port, { path: '/api/session' })).status, 200);
  });

  test('refuses a weight outside 1 to 1.5 and one that is not a number', async () => {
    for (const wire of [0.99, 0, -1, 1.51, 2, '1.08', null, NaN, Infinity, {}]) {
      await assert.rejects(start({ wire }), /wire is a number from 1 to 1.5/, String(wire));
    }
  });

  test('takes weights of 1 and 1.5, and 1.08 when none is given', async () => {
    for (const wire of [1, 1.5, 1.08, undefined]) {
      const standIn = await start({ wire });
      assert.equal((await request(standIn.port, { path: '/api/session' })).status, 200);
      await standIn.stop();
    }
  });

  test('refuses a build with no index page, and a folder that is not there', async () => {
    const empty = join(tmp, 'dist-empty');
    mkdirSync(empty);
    await assert.rejects(start({ distDir: empty }), /the build holds no index\.html/);
    await assert.rejects(start({ distDir: join(tmp, 'no-such-build') }), { code: 'ENOENT' });
  });

  test('refuses a film file that is not there', async () => {
    await assert.rejects(start({ filmPath: join(tmp, 'no-film.json') }), { code: 'ENOENT' });
  });

  test('refuses a port that is taken, and stops its timer so that nothing is left running', async () => {
    const standIn = await start();
    await assert.rejects(start({ port: standIn.port }), { code: 'EADDRINUSE' });
  });

  test('reads the whole build once, nested folders too', async () => {
    const dist = makeBuild('dist-nested');
    mkdirSync(join(dist, 'assets', 'deep', 'er'), { recursive: true });
    writeFileSync(join(dist, 'assets', 'deep', 'er', 'chunk.js'), 'deep();');
    const standIn = await start({ distDir: dist });
    assert.equal((await request(standIn.port, { path: '/assets/deep/er/chunk.js' })).body.toString(), 'deep();');
  });
});
