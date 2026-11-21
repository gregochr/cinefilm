// scripts/replay/guard.mjs: what the guard lets through, answers itself and keeps, with a made-up page that records
// what it is sent and lets a test fire the paused requests. No Chrome and no network.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { describe, test } from 'node:test';
import {
  HELD, MAX_HELD_ENTRIES, MAX_RECORD_CHARS, MAX_VIEWINGS, PASS, PROGRESS, RECORD, attachGuard, decide, heldAnswer,
  progressAnswer, shapeOf,
} from '../../replay/guard.mjs';

const SITE = 'http://127.0.0.1:5301';
const UUID_A = '3f2b8c1e-5a4d-4e6f-9b7a-1c2d3e4f5a6b';
const UUID_B = '0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d';
const flush = () => new Promise((resolve) => setImmediate(resolve));
const decoded = (answer) => JSON.parse(Buffer.from(answer.body, 'base64').toString('utf8'));
// The nth made-up viewing id.
const viewing = (n) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;

// A page as cdp.mjs gives one: `send` answers by `reply(method, params)` (resolved by default) and every call is
// logged, with the listener's registration, in `log`; `fire` is Chrome pausing a request.
function fakePage(reply = () => Promise.resolve({})) {
  const log = [];
  const listeners = new Map();
  return {
    log,
    sent: () => log.filter((entry) => entry[0] === 'send' && entry[1] !== 'Fetch.enable').map((e) => ({
      method: e[1], params: e[2],
    })),
    send(method, params) {
      log.push(['send', method, params]);
      return reply(method, params);
    },
    on(method, listener) {
      log.push(['on', method]);
      listeners.set(method, listener);
    },
    fire(params) {
      listeners.get('Fetch.requestPaused')(params);
    },
  };
}

// Chrome pausing `method` `path` on the site (or any address given whole), with a body when `postData` is given.
function pause(page, requestId, method, path, postData) {
  const url = path.startsWith('http') ? path : `${SITE}${path}`;
  const request = { method, url, hasPostData: postData !== undefined };
  if (postData !== undefined) request.postData = postData;
  page.fire({ requestId, request });
}

async function guarded(options = {}) {
  const page = fakePage();
  const guard = await attachGuard(page, { site: SITE, ...options });
  return { page, guard };
}

// Every write the app makes: [name in the app, method, path as resources.js writes it, what the guard does].
const WRITES = [
  ['filmsApi.savePreferences', 'PUT', '/api/films/:id/preferences', HELD],
  ['editionsApi.reportProgress', 'PUT', '/api/editions/:id/progress', PROGRESS],
  ['editionsApi.setWatched', 'POST', '/api/editions/:id/watched', HELD],
  ['editionsApi.setHiddenFromContinueWatching', 'PUT', '/api/editions/:id/continue-watching', HELD],
  ['editionsApi.restoreState', 'PUT', '/api/editions/:id/playback-state', HELD],
  ['sessionsApi.create', 'POST', '/api/editions/:editionId/sessions', PASS],
  ['sessionsApi.remove', 'DELETE', '/api/sessions/:sessionId', PASS],
  ['healthApi.report', 'PUT', '/api/playback-health/:playbackId', RECORD],
  ['sessionApi.signIn', 'POST', '/api/session', HELD],
  ['sessionApi.signOut', 'DELETE', '/api/session', HELD],
  ['adminApi.startScan', 'POST', '/api/admin/scan', HELD],
  ['adminApi.startRatingsRefresh', 'POST', '/api/admin/ratings/refresh', HELD],
  ['adminApi.applyMatch', 'POST', '/api/admin/films/:filmId/match', HELD],
  ['adminApi.leaveUnmatched', 'POST', '/api/admin/films/:filmId/leave-unmatched', HELD],
  ['adminApi.putBack', 'POST', '/api/admin/films/:filmId/put-back', HELD],
  ['adminApi.saveEditions', 'PUT', '/api/admin/films/:filmId/editions', HELD],
  ['adminApi.startPlexImport', 'POST', '/api/admin/plex/import', HELD],
  ['adminApi.startPlexCreditsImport', 'POST', '/api/admin/plex/credits/import', HELD],
  ['adminApi.stopTranscodingSession', 'DELETE', '/api/admin/transcoding/sessions/:id', HELD],
  ['adminApi.attachSubtitle', 'POST', '/api/admin/subtitles/:id/attach', HELD],
  ['adminApi.detachSubtitle', 'POST', '/api/admin/subtitles/:id/detach', HELD],
  ['adminApi.setSubtitleOffset', 'PUT', '/api/admin/subtitles/:id/offset', HELD],
  ['adminApi.verifySubtitle', 'POST', '/api/admin/subtitles/:id/verify', HELD],
  ['adminApi.unverifySubtitle', 'DELETE', '/api/admin/subtitles/:id/verify', HELD],
  ['adminApi.removeSubtitle', 'DELETE', '/api/admin/subtitles/:id', HELD],
  ['adminApi.setForcedNeed', 'PUT', '/api/admin/films/:filmId/forced-need', HELD],
  ['adminApi.setFullNeed', 'PUT', '/api/admin/films/:filmId/full-need', HELD],
  ['adminApi.downloadOpenSubtitles', 'POST', '/api/admin/editions/:editionId/opensubtitles/download', HELD],
  ['settingsApi.update', 'PUT', '/api/admin/settings', HELD],
];

// The address of a write: a viewing's id where the app puts a session's or a playback's, else a number.
const concrete = (path) => path.replaceAll(/:(\w+)/g, (_, name) => (/session|playback/i.test(name) ? UUID_A : '12'));

describe('every write the app can make', () => {
  for (const [name, method, path, outcome] of WRITES) {
    test(`${name} (${method} ${path}) is ${outcome}`, () => {
      assert.equal(decide(method, `${SITE}${concrete(path)}`, SITE), outcome);
    });
  }

  test('exactly three are let through or copied, one is answered as a progress save, the rest are held', () => {
    const by = (outcome) => WRITES.filter((write) => write[3] === outcome).map((write) => write[0]).sort();
    assert.deepEqual(by(PASS), ['sessionsApi.create', 'sessionsApi.remove']);
    assert.deepEqual(by(RECORD), ['healthApi.report']);
    assert.deepEqual(by(PROGRESS), ['editionsApi.reportProgress']);
    assert.equal(by(HELD).length, WRITES.length - 4);
  });

  test('resources.js holds no write that this list does not name, and this list names none that is gone', () => {
    const source = readFileSync(new URL('../../../frontend/src/api/resources.js', import.meta.url), 'utf8');
    const found = [];
    for (const call of source.matchAll(/\b(put|post|del)\(\s*(['`])/g)) {
      const start = call.index + call[0].length;
      // The text up to the closing quote (a path with a query written in it ends at its inner quote), the query cut
      // off, a half-written `${` dropped and each `${name}` written `:name`.
      const text = source.slice(start, source.indexOf(call[2], start)).split('?')[0];
      const path = text.replace(/\$\{\w*\s*$/, '').replaceAll(/\$\{(\w+)\}/g, ':$1');
      found.push(`${{ put: 'PUT', post: 'POST', del: 'DELETE' }[call[1]]} ${path}`);
    }
    const named = WRITES.map(([, method, path]) => `${method} ${path}`);
    assert.deepEqual(found.sort(), named.sort());
  });
});

describe('decide', () => {
  test('GET and HEAD go through, on the site and anywhere else', () => {
    for (const method of ['GET', 'HEAD']) {
      assert.equal(decide(method, `${SITE}/api/films`, SITE), PASS);
      assert.equal(decide(method, 'https://image.tmdb.org/t/p/w500/x.jpg?a=1', SITE), PASS);
      assert.equal(decide(method, 'not an address', SITE), PASS);
    }
  });

  test('a method that is not exactly GET or HEAD is held on a read address', () => {
    for (const method of ['get', 'Post', 'PATCH', 'OPTIONS', 'head', 'TRACE', '', undefined, null, 5]) {
      assert.equal(decide(method, `${SITE}/api/films`, SITE), HELD, String(method));
    }
  });

  test('a lower-case or mixed-case method is not one of the three writes either', () => {
    assert.equal(decide('post', `${SITE}/api/editions/12/sessions`, SITE), HELD);
    assert.equal(decide('Delete', `${SITE}/api/sessions/${UUID_A}`, SITE), HELD);
    assert.equal(decide('put', `${SITE}/api/playback-health/${UUID_A}`, SITE), HELD);
    assert.equal(decide('PATCH', `${SITE}/api/editions/12/sessions`, SITE), HELD);
  });

  test('a write on the wrong path or with the wrong method is held', () => {
    assert.equal(decide('PUT', `${SITE}/api/editions/12/sessions`, SITE), HELD);
    assert.equal(decide('DELETE', `${SITE}/api/editions/12/sessions`, SITE), HELD);
    assert.equal(decide('POST', `${SITE}/api/sessions/${UUID_A}`, SITE), HELD);
    assert.equal(decide('POST', `${SITE}/api/playback-health/${UUID_A}`, SITE), HELD);
    assert.equal(decide('DELETE', `${SITE}/api/playback-health/${UUID_A}`, SITE), HELD);
    assert.equal(decide('POST', `${SITE}/api/editions/12/progress`, SITE), HELD);
    assert.equal(decide('DELETE', `${SITE}/api/editions/12/progress`, SITE), HELD);
  });

  test('the edition in an address is 1 to 12 digits, and nothing else', () => {
    assert.equal(decide('POST', `${SITE}/api/editions/1/sessions`, SITE), PASS);
    assert.equal(decide('POST', `${SITE}/api/editions/${'9'.repeat(12)}/sessions`, SITE), PASS);
    for (const edition of ['', '9'.repeat(13), 'a', '1a', '-1', '1.5', '١']) {
      assert.equal(decide('POST', `${SITE}/api/editions/${edition}/sessions`, SITE), HELD, edition);
      assert.equal(decide('PUT', `${SITE}/api/editions/${edition}/progress`, SITE), HELD, edition);
    }
  });

  test('the id of a session or a playback is a lower-case UUID with its hyphens, and nothing else', () => {
    const bad = [UUID_A.toUpperCase(), UUID_A.replaceAll('-', ''), UUID_A.slice(1), `${UUID_A}0`, 'abc', '12',
      UUID_A.replace('3f2b', '3g2b'), `${UUID_A}/`];
    for (const id of bad) {
      assert.equal(decide('DELETE', `${SITE}/api/sessions/${id}`, SITE), HELD, id);
      assert.equal(decide('PUT', `${SITE}/api/playback-health/${id}`, SITE), HELD, id);
    }
  });

  // Each of the four writes the guard knows, in a form that goes wrong in one way.
  const GOOD = [
    ['POST', '/api/editions/12/sessions', PASS],
    ['DELETE', `/api/sessions/${UUID_A}`, PASS],
    ['PUT', `/api/playback-health/${UUID_A}`, RECORD],
    ['PUT', '/api/editions/12/progress', PROGRESS],
  ];
  const LOOK_ALIKES = {
    'the same address on the site': (path) => `${SITE}${path}`,
    'another port': (path) => `http://127.0.0.1:5302${path}`,
    'another host': (path) => `http://evil.example${path}`,
    'another scheme': (path) => `https://127.0.0.1:5301${path}`,
    'a user name': (path) => `http://user@127.0.0.1:5301${path}`,
    'a user name and password': (path) => `http://user:pass@127.0.0.1:5301${path}`,
    'a host that merely starts with the site': (path) => `http://127.0.0.1:5301.evil.example${path}`,
    'a longer path': (path) => `${SITE}${path}/more`,
    'a longer last segment': (path) => `${SITE}${path}x`,
    'a trailing slash': (path) => `${SITE}${path}/`,
    'a doubled slash at the start': (path) => `${SITE}/${path}`,
    'a doubled slash inside': (path) => `${SITE}${path.replace('/api/', '/api//')}`,
    'a percent-encoded segment': (path) => `${SITE}${path.replace('/api/', '/%61pi/')}`,
    'a percent-encoded character at the end': (path) => {
      const last = path.at(-1).charCodeAt(0).toString(16);
      return `${SITE}${path.slice(0, -1)}%${last}`;
    },
    'a dot segment': (path) => `${SITE}${path.replace('/api/', '/api/x/../')}`,
    'a query': (path) => `${SITE}${path}?x=1`,
    'an empty query': (path) => `${SITE}${path}?`,
    'a fragment': (path) => `${SITE}${path}#x`,
    'upper-case letters in the path': (path) => `${SITE}${path.replace('/api/', '/API/')}`,
    'an address that does not parse': () => 'not an address',
    'an empty address': () => '',
    'no address': () => undefined,
  };
  for (const [method, path, outcome] of GOOD) {
    for (const [what, make] of Object.entries(LOOK_ALIKES)) {
      const expected = what === 'the same address on the site' ? outcome : HELD;
      test(`${method} ${path.replaceAll(UUID_A, '<id>')} with ${what} is ${expected}`, () => {
        assert.equal(decide(method, make(path), SITE), expected);
      });
    }
  }
});

describe('the answers', () => {
  const answer = (body) => progressAnswer(body);

  test('a progress save is answered 200 with JSON in the server\'s shape and the saved position', () => {
    const reply = answer('{"positionMs":123456}');
    assert.equal(reply.responseCode, 200);
    assert.deepEqual(reply.responseHeaders, [{ name: 'Content-Type', value: 'application/json' }]);
    assert.equal(Buffer.from(reply.body, 'base64').toString('utf8'), '{"positionMs":123456,"watched":false}');
  });

  test('a fraction is floored, zero is kept, and extra fields are ignored', () => {
    assert.equal(decoded(answer('{"positionMs":1234.9}')).positionMs, 1234);
    assert.equal(decoded(answer('{"positionMs":0}')).positionMs, 0);
    assert.deepEqual(decoded(answer('{"positionMs":7,"watched":true,"x":1}')), { positionMs: 7, watched: false });
  });

  test('a position that is negative, not a number or not finite is 0', () => {
    for (const body of ['{"positionMs":-1}', '{"positionMs":-0.5}', '{"positionMs":"5000"}', '{"positionMs":null}',
      '{"positionMs":true}', '{"positionMs":[5]}', '{"positionMs":{"a":1}}', '{"positionMs":1e999}', '{}']) {
      assert.deepEqual(decoded(answer(body)), { positionMs: 0, watched: false }, body);
    }
  });

  test('a body that is not an object, not JSON or missing still gets the answer, with position 0', () => {
    for (const body of ['"5000"', 'null', '[5000]', '[{"positionMs":5}]', '5000', 'positionMs=5000', '',
      '{"positionMs"', undefined, null, 42]) {
      const reply = answer(body);
      assert.equal(reply.responseCode, 200, String(body));
      assert.deepEqual(decoded(reply), { positionMs: 0, watched: false }, String(body));
    }
  });

  test('anything else held is answered 204 with no body', () => {
    assert.deepEqual(heldAnswer(), { responseCode: 204, responseHeaders: [] });
    assert.equal('body' in heldAnswer(), false);
  });
});

describe('shapeOf', () => {
  test('every run of digits is N and every UUID is <id>; the query is never part of it', () => {
    assert.equal(shapeOf(`${SITE}/api/editions/12/progress?secret=abc`), '/api/editions/N/progress');
    assert.equal(shapeOf(`${SITE}/api/sessions/${UUID_A}`), '/api/sessions/<id>');
    assert.equal(shapeOf(`${SITE}/api/admin/subtitles/340/offset`), '/api/admin/subtitles/N/offset');
    assert.equal(shapeOf(`${SITE}/api/sessions/${UUID_A.toUpperCase()}`), '/api/sessions/<id>');
  });

  test('an address that does not parse has a fixed shape and a long path is cut', () => {
    assert.equal(shapeOf('not an address'), '(unreadable address)');
    assert.equal(shapeOf(`${SITE}/${'a'.repeat(500)}`).length, 200);
  });
});

describe('the keeping of records', () => {
  const report = (seq, extra = {}) => JSON.stringify({ seq, ...extra });
  const send = (page, id, body, requestId = `r-${Math.random()}`) => pause(page, requestId, 'PUT',
    `/api/playback-health/${id}`, body);

  test('a record is let through after its copy is kept', async () => {
    const { page, guard } = await guarded();
    send(page, UUID_A, report(1, { stalls: 2 }), 'r1');
    assert.deepEqual(page.sent(), [{ method: 'Fetch.continueRequest', params: { requestId: 'r1' } }]);
    assert.deepEqual(guard.report().records, [{ id: UUID_A, count: 1, latest: { seq: 1, stalls: 2 } }]);
  });

  test('the highest seq is kept whatever order the records come in, and all are counted', async () => {
    for (const order of [[1, 2, 3], [3, 2, 1], [2, 3, 1], [1, 3, 2]]) {
      const { page, guard } = await guarded();
      order.forEach((seq) => send(page, UUID_A, report(seq, { mark: `m${seq}` })));
      const [kept] = guard.report().records;
      assert.deepEqual([kept.count, kept.latest], [3, { seq: 3, mark: 'm3' }], order.join());
    }
  });

  test('with no seq the one that came last is kept', async () => {
    const { page, guard } = await guarded();
    send(page, UUID_A, '{"mark":"first"}');
    send(page, UUID_A, '{"mark":"second"}');
    send(page, UUID_A, '{"mark":"third"}');
    assert.deepEqual(guard.report().records[0].latest, { mark: 'third' });
    assert.equal(guard.report().records[0].count, 3);
  });

  test('a record with a whole-number seq is not replaced by one without, or with a fraction', async () => {
    const { page, guard } = await guarded();
    send(page, UUID_A, '{"seq":4,"mark":"kept"}');
    send(page, UUID_A, '{"mark":"no seq"}');
    send(page, UUID_A, '{"seq":9.5,"mark":"fraction"}');
    send(page, UUID_A, '{"seq":"9","mark":"text"}');
    assert.deepEqual(guard.report().records[0].latest, { seq: 4, mark: 'kept' });
    assert.equal(guard.report().records[0].count, 4);
  });

  test('two records with the same seq: the first one is kept, as at the server, and both are counted', async () => {
    const { page, guard } = await guarded();
    send(page, UUID_A, report(5, { mark: 'one' }));
    send(page, UUID_A, report(5, { mark: 'two' }));
    assert.equal(guard.report().records[0].latest.mark, 'one');
    assert.equal(guard.report().records[0].count, 2);
  });

  test('a record with a whole-number seq replaces one with none, and one with none does not replace it back',
    async () => {
      const { page, guard } = await guarded();
      send(page, UUID_A, '{"mark":"no seq"}');
      send(page, UUID_A, report(1, { mark: 'seq 1' }));
      assert.deepEqual(guard.report().records[0].latest, { seq: 1, mark: 'seq 1' });
      send(page, UUID_A, '{"mark":"no seq again"}');
      assert.equal(guard.report().records[0].latest.mark, 'seq 1');
    });

  test('two viewings are kept apart, in the order they were first seen', async () => {
    const { page, guard } = await guarded();
    send(page, UUID_B, report(1, { mark: 'b1' }));
    send(page, UUID_A, report(7, { mark: 'a7' }));
    send(page, UUID_B, report(2, { mark: 'b2' }));
    assert.deepEqual(guard.report().records, [
      { id: UUID_B, count: 2, latest: { seq: 2, mark: 'b2' } },
      { id: UUID_A, count: 1, latest: { seq: 7, mark: 'a7' } },
    ]);
  });

  test('a body of 65,536 characters is kept; one of 65,537 is let through, counted and not kept', async () => {
    const wrapper = JSON.stringify({ seq: 1, pad: '' }).length;
    const sized = (length) => JSON.stringify({ seq: 1, pad: 'x'.repeat(length - wrapper) });
    assert.equal(MAX_RECORD_CHARS, 65_536);
    assert.equal(sized(65_536).length, 65_536);
    const { page, guard } = await guarded();
    send(page, UUID_A, sized(65_536), 'fits');
    assert.equal(guard.report().records[0].count, 1);
    assert.equal(guard.report().notRead, 0);
    send(page, UUID_B, sized(65_537), 'long');
    assert.equal(guard.report().records.length, 1);
    assert.equal(guard.report().notRead, 1);
    assert.deepEqual(page.sent().map((s) => [s.method, s.params.requestId]), [
      ['Fetch.continueRequest', 'fits'], ['Fetch.continueRequest', 'long'],
    ]);
  });

  test('a long record of a viewing already kept does not replace what is kept', async () => {
    const { page, guard } = await guarded();
    send(page, UUID_A, report(1, { mark: 'kept' }));
    send(page, UUID_A, report(2, { pad: 'x'.repeat(MAX_RECORD_CHARS) }));
    assert.deepEqual(guard.report().records[0], { id: UUID_A, count: 1, latest: { seq: 1, mark: 'kept' } });
    assert.equal(guard.report().notRead, 1);
  });

  test('a body missing, not JSON or not an object is let through, counted as not read and not kept', async () => {
    const { page, guard } = await guarded();
    // Chrome may say a body was sent and give none: `hasPostData` with no `postData`.
    page.fire({ requestId: 'none', request: { method: 'PUT', url: `${SITE}/api/playback-health/${UUID_A}`,
      hasPostData: true } });
    ['not json', '[1,2]', 'null', '7', '"text"', '{"seq":'].forEach((body, n) => send(page, UUID_A, body, `bad${n}`));
    assert.equal(guard.report().notRead, 7);
    assert.deepEqual(guard.report().records, []);
    const methods = page.sent().map((sent) => sent.method);
    assert.equal(methods.length, 7);
    assert.ok(methods.every((method) => method === 'Fetch.continueRequest'));
  });

  test('a 51st viewing is let through and counted as not read; the 50 already kept keep counting', async () => {
    assert.equal(MAX_VIEWINGS, 50);
    const { page, guard } = await guarded();
    for (let n = 1; n <= 50; n += 1) send(page, viewing(n), report(1));
    send(page, viewing(51), report(1), 'fifty-first');
    assert.equal(guard.report().records.length, 50);
    assert.equal(guard.report().notRead, 1);
    assert.deepEqual(page.sent().at(-1), { method: 'Fetch.continueRequest', params: { requestId: 'fifty-first' } });
    send(page, viewing(1), report(2));
    assert.equal(guard.report().records[0].count, 2);
    assert.equal(guard.report().notRead, 1);
  });

  test('a record with a query, or on another host, is held and never kept', async () => {
    const { page, guard } = await guarded();
    pause(page, 'q', 'PUT', `/api/playback-health/${UUID_A}?x=1`, report(1));
    pause(page, 'h', 'PUT', `http://evil.example/api/playback-health/${UUID_A}`, report(1));
    assert.deepEqual(page.sent().map((sent) => sent.method), ['Fetch.fulfillRequest', 'Fetch.fulfillRequest']);
    assert.deepEqual(guard.report().records, []);
    assert.equal(guard.report().notRead, 0);
  });
});

describe('attachGuard', () => {
  test('the listener is registered before Fetch.enable is sent, with the pattern for the site at the request stage',
    async () => {
      const { page } = await guarded();
      assert.deepEqual(page.log.map((entry) => entry.slice(0, 2).join(' ')), [
        'on Fetch.requestPaused', 'send Fetch.enable',
      ]);
      assert.deepEqual(page.log[1][2], { patterns: [{ urlPattern: `${SITE}/*`, requestStage: 'Request' }] });
    });

  test('the pattern is for the site\'s origin even when the site is given with a trailing slash', async () => {
    const page = fakePage();
    await attachGuard(page, { site: `${SITE}/` });
    assert.equal(page.log[1][2].patterns[0].urlPattern, `${SITE}/*`);
    pause(page, 'r', 'POST', '/api/editions/3/sessions');
    assert.equal(page.sent()[0].method, 'Fetch.continueRequest');
  });

  test('it does not resolve before Chrome has acknowledged Fetch.enable', async () => {
    let acknowledge;
    const page = fakePage(() => new Promise((resolve) => {
      acknowledge = resolve;
    }));
    let resolved = false;
    const attached = attachGuard(page, { site: SITE }).then((guard) => {
      resolved = true;
      return guard;
    });
    await flush();
    await flush();
    assert.equal(resolved, false);
    acknowledge({});
    const guard = await attached;
    assert.equal(resolved, true);
    assert.equal(typeof guard.report, 'function');
  });

  test('a request paused before Fetch.enable is acknowledged is answered all the same', async () => {
    let acknowledge;
    const page = fakePage((method) => (method === 'Fetch.enable'
      ? new Promise((resolve) => {
        acknowledge = resolve;
      }) : Promise.resolve({})));
    const attached = attachGuard(page, { site: SITE });
    pause(page, 'early', 'PATCH', '/api/films/1');
    assert.deepEqual(page.sent().map((sent) => sent.method), ['Fetch.fulfillRequest']);
    acknowledge({});
    await attached;
  });

  test('it rejects when Fetch.enable fails', async () => {
    const page = fakePage(() => Promise.reject(new Error('Fetch.enable failed: no')));
    await assert.rejects(attachGuard(page, { site: SITE }), /Fetch\.enable failed: no/);
  });

  test('a request that may go through is continued, once, with nothing else', async () => {
    const { page } = await guarded();
    pause(page, 'a', 'GET', '/api/films');
    pause(page, 'b', 'HEAD', 'https://image.tmdb.org/t/p/w500/x.jpg');
    pause(page, 'c', 'POST', '/api/editions/12/sessions', '{"x":1}');
    pause(page, 'd', 'DELETE', `/api/sessions/${UUID_A}`);
    assert.deepEqual(page.sent(), ['a', 'b', 'c', 'd'].map((requestId) => ({
      method: 'Fetch.continueRequest', params: { requestId },
    })));
  });

  test('a progress save is fulfilled with the server\'s answer and never continued', async () => {
    const { page } = await guarded();
    pause(page, 'p', 'PUT', '/api/editions/12/progress', '{"positionMs":61000.7}');
    const [sent, ...rest] = page.sent();
    assert.equal(rest.length, 0);
    assert.equal(sent.method, 'Fetch.fulfillRequest');
    assert.equal(sent.params.requestId, 'p');
    assert.equal(sent.params.responseCode, 200);
    assert.deepEqual(sent.params.responseHeaders, [{ name: 'Content-Type', value: 'application/json' }]);
    assert.deepEqual(decoded(sent.params), { positionMs: 61000, watched: false });
  });

  test('any other write is fulfilled with a 204 and never continued', async () => {
    const { page } = await guarded();
    pause(page, 'w', 'POST', '/api/editions/12/watched', '{"watched":true}');
    pause(page, 'x', 'PATCH', '/api/films/3');
    pause(page, 'y', 'OPTIONS', '/api/films/3');
    pause(page, 'z', 'POST', `http://evil.example/api/editions/12/sessions`, '{}');
    assert.deepEqual(page.sent(), ['w', 'x', 'y', 'z'].map((requestId) => ({
      method: 'Fetch.fulfillRequest', params: { requestId, responseCode: 204, responseHeaders: [] },
    })));
  });

  test('a method that is missing is held', async () => {
    const { page } = await guarded();
    page.fire({ requestId: 'm', request: { url: `${SITE}/api/films` } });
    assert.equal(page.sent()[0].method, 'Fetch.fulfillRequest');
  });

  test('a request paused again after a redirect is judged by the address it now has', async () => {
    const { page } = await guarded();
    pause(page, 'same', 'POST', '/api/editions/12/sessions');
    pause(page, 'same', 'POST', 'http://evil.example/api/editions/12/sessions');
    pause(page, 'other', 'GET', '/api/films');
    pause(page, 'other', 'PUT', '/api/films/3/preferences', '{}');
    assert.deepEqual(page.sent().map((sent) => [sent.params.requestId, sent.method]), [
      ['same', 'Fetch.continueRequest'], ['same', 'Fetch.fulfillRequest'],
      ['other', 'Fetch.continueRequest'], ['other', 'Fetch.fulfillRequest'],
    ]);
  });

  test('a decision that throws fails the request and never continues it', async () => {
    const { page, guard } = await guarded({ judge: () => {
      throw new Error('boom');
    } });
    pause(page, 'f', 'GET', '/api/films');
    assert.deepEqual(page.sent(), [{
      method: 'Fetch.failRequest', params: { requestId: 'f', errorReason: 'Failed' },
    }]);
    assert.equal(guard.report().failed, 1);
  });

  test('a copy that throws fails the request and never continues it', async () => {
    const { page, guard } = await guarded();
    const request = { method: 'PUT', url: `${SITE}/api/playback-health/${UUID_A}`, hasPostData: true };
    Object.defineProperty(request, 'postData', { get() {
      throw new Error('no body');
    } });
    page.fire({ requestId: 'c', request });
    assert.deepEqual(page.sent(), [{
      method: 'Fetch.failRequest', params: { requestId: 'c', errorReason: 'Failed' },
    }]);
    assert.equal(guard.report().failed, 1);
  });

  test('a decision that is not one of the four is a hold', async () => {
    const { page } = await guarded({ judge: () => 'maybe' });
    pause(page, 'u', 'GET', '/api/films');
    assert.equal(page.sent()[0].method, 'Fetch.fulfillRequest');
  });

  test('a paused request that cannot be read is counted as failed and sent nothing', async () => {
    const { page, guard } = await guarded();
    page.fire({});
    page.fire(undefined);
    page.fire({ requestId: 7, request: { method: 'GET', url: SITE } });
    assert.deepEqual(page.sent(), []);
    assert.equal(guard.report().failed, 3);
  });

  test('a send that is rejected is caught, counted, never retried, and leaves nothing unhandled', async () => {
    const unhandled = [];
    const watch = (reason) => unhandled.push(reason);
    process.on('unhandledRejection', watch);
    try {
      const page = fakePage((method) => (method === 'Fetch.enable' ? Promise.resolve({})
        : Promise.reject(new Error('Chrome has gone'))));
      const guard = await attachGuard(page, { site: SITE });
      pause(page, 'a', 'GET', '/api/films');
      pause(page, 'b', 'PATCH', '/api/films/1');
      await flush();
      await flush();
      assert.equal(guard.report().rejected, 2);
      assert.equal(page.sent().length, 2);
      assert.deepEqual(unhandled, []);
    } finally {
      process.off('unhandledRejection', watch);
    }
  });

  test('a send that throws at once is counted too, and so is a failRequest that is rejected', async () => {
    const unhandled = [];
    const watch = (reason) => unhandled.push(reason);
    process.on('unhandledRejection', watch);
    try {
      const page = fakePage((method) => {
        if (method === 'Fetch.continueRequest') throw new Error('closed');
        return method === 'Fetch.enable' ? Promise.resolve({}) : Promise.reject(new Error('gone'));
      });
      const guard = await attachGuard(page, { site: SITE, judge: (method) => {
        if (method === 'POST') throw new Error('boom');
        return PASS;
      } });
      pause(page, 'a', 'GET', '/api/films');
      pause(page, 'b', 'POST', '/api/films');
      await flush();
      await flush();
      assert.deepEqual([guard.report().rejected, guard.report().failed], [2, 1]);
      assert.deepEqual(page.sent().map((sent) => sent.method), ['Fetch.continueRequest', 'Fetch.failRequest']);
      assert.deepEqual(unhandled, []);
    } finally {
      process.off('unhandledRejection', watch);
    }
  });
});

describe('what the guard reports as held', () => {
  test('methods and shapes with counts: digits are N, ids are <id>, a repeat is counted', async () => {
    const { page, guard } = await guarded();
    pause(page, '1', 'POST', '/api/editions/12/watched', '{"watched":true}');
    pause(page, '2', 'POST', '/api/editions/345/watched', '{"watched":false}');
    pause(page, '3', 'PUT', '/api/editions/9/progress', '{"positionMs":5}');
    pause(page, '4', 'DELETE', `/api/admin/subtitles/${UUID_A}`);
    pause(page, '5', 'GET', '/api/films');
    pause(page, '6', 'POST', '/api/editions/12/sessions');
    assert.deepEqual(guard.report().held, [
      { method: 'POST', shape: '/api/editions/N/watched', count: 2 },
      { method: 'PUT', shape: '/api/editions/N/progress', count: 1 },
      { method: 'DELETE', shape: '/api/admin/subtitles/<id>', count: 1 },
    ]);
  });

  test('the same shape under another method is its own entry', async () => {
    const { page, guard } = await guarded();
    pause(page, '1', 'PATCH', '/api/films/1');
    pause(page, '2', 'PUT', '/api/films/2');
    pause(page, '3', 'PATCH', '/api/films/3');
    assert.deepEqual(guard.report().held, [
      { method: 'PATCH', shape: '/api/films/N', count: 2 },
      { method: 'PUT', shape: '/api/films/N', count: 1 },
    ]);
  });

  test('no query, header, cookie or body is in what is reported', async () => {
    const { page, guard } = await guarded();
    page.fire({ requestId: 'a', request: {
      method: 'PATCH', url: `${SITE}/api/films/3?token=sekret-query`, postData: '{"password":"sekret-body"}',
      hasPostData: true, headers: { Authorization: 'Bearer sekret-header', Cookie: 'cinefilm=sekret-cookie' },
    } });
    pause(page, 'b', 'PUT', '/api/editions/12/progress?k=sekret-query', '{"positionMs":5,"note":"sekret-body"}');
    pause(page, 'c', 'POST', '/api/admin/scan', '{"password":"sekret-body"}');
    assert.equal(guard.report().held.length, 3);
    const text = JSON.stringify(guard.report());
    for (const secret of ['sekret-query', 'sekret-body', 'sekret-header', 'sekret-cookie', 'password', 'token']) {
      assert.equal(text.includes(secret), false, secret);
    }
  });

  test('the method is printable text of at most 16 characters', async () => {
    const { page, guard } = await guarded();
    pause(page, 'a', 'X'.repeat(40), '/api/films');
    pause(page, 'b', 'BAD\nMETHOD\u001b[31m', '/api/films');
    const methods = guard.report().held.map((entry) => entry.method);
    assert.deepEqual(methods, ['X'.repeat(16), 'BAD METHOD?[31m']);
  });

  test('the list is cut at 200 distinct entries; the ones in it go on counting and the rest are counted apart',
    async () => {
      const { page, guard } = await guarded();
      assert.equal(MAX_HELD_ENTRIES, 200);
      for (let n = 0; n < 250; n += 1) pause(page, `r${n}`, `M${n}`, '/api/films');
      pause(page, 'again', 'M0', '/api/films');
      const { held, heldDropped } = guard.report();
      assert.equal(held.length, 200);
      assert.equal(heldDropped, 50);
      assert.equal(held[0].count, 2);
      assert.equal(page.sent().length, 251);
      assert.ok(page.sent().every((sent) => sent.method === 'Fetch.fulfillRequest'));
    });

  test('addresses that differ only by what a page controls cannot grow the list past 200 either', async () => {
    const { page, guard } = await guarded();
    for (let n = 0; n < 300; n += 1) {
      pause(page, `r${n}`, 'PATCH', `/${String.fromCharCode(97 + (n % 26))}${'b'.repeat(n)}`);
    }
    assert.equal(guard.report().held.length, 200);
    assert.ok(guard.report().held.every((entry) => entry.shape.length <= 200));
  });

  test('a report is a snapshot: changing it changes nothing in the guard', async () => {
    const { page, guard } = await guarded();
    pause(page, '1', 'PATCH', '/api/films/1');
    pause(page, '2', 'PUT', `/api/playback-health/${UUID_A}`, '{"seq":4,"deep":{"a":1}}');
    const first = guard.report();
    first.held[0].count = 99;
    first.held.length = 0;
    first.records[0].count = 99;
    first.records[0].latest.seq = 99;
    first.records[0].latest.deep.a = 99;
    first.records.length = 0;
    const again = guard.report();
    assert.equal(again.held[0].count, 1);
    assert.deepEqual(again.records, [{ id: UUID_A, count: 1, latest: { seq: 4, deep: { a: 1 } } }]);
  });
});
