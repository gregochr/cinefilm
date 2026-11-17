// scripts/replay/viewing.mjs, the run: with Chrome, the site, the clock, the terminal and the keyboard made up. A fake
// page logs every call in one list, so that the order of what the run does (the guard before the first address, the
// page sent away before Chrome is ended, the results after) is asserted as an order. Folders are made under a
// temporary folder that is removed after. `main`, which starts the real Chrome, is checked by hand.
import assert from 'node:assert/strict';
import {
  existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, renameSync, rmSync, statSync, writeFileSync,
} from 'node:fs';
import fsp from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { after, describe, test } from 'node:test';
import { PAGE_TEXT, crashHandler, createCancel, takeLock, viewerPaths } from '../../replay/viewer.mjs';
import {
  END_WAIT_MS, ENDS, KEEP_MS, LOOK_MS, PRESS_RETRY, SITE_RETRY_MS, SITE_WAIT_MS, STATUS_EXPRESSION, leave, runName,
  viewing, resultsWriter, watchRunSignals, writeResults, writeResultsAsync, writeSynced, writeSyncedAsync,
} from '../../replay/viewing.mjs';

const SITE = 'http://127.0.0.1:5301';
// More waits than any test here needs (the longest, three minutes of looks, is 36; ten minutes of a dead site, 41).
const MAX_WAITS = 100;
const UUID = '3f2b8c1e-5a4d-4e6f-9b7a-1c2d3e4f5a6b';
const tmp = mkdtempSync(join(tmpdir(), 'viewing-'));
after(() => rmSync(tmp, { recursive: true, force: true }));
let counter = 0;
const modeOf = (path) => statSync(path).mode & 0o777;
const realAsyncFs = { open: fsp.open, rename: fsp.rename, rm: fsp.rm };
// 17 November 2026, 14:05:09 where the test runs: the time of day and the folder name are local time.
const BEGAN = new Date(2026, 10, 17, 14, 5, 9).getTime();
const FILM = { status: 200, title: 'Heat', runtimeMs: 10200000, quality: 'auto' };
const playing = (t) => ({ video: true, t, ahead: 8, ended: false, paused: false, pill: 'Playing' });

// A world to run in: the page logs into `log` (calls, in order); `script` says how the site and the page answer.
function world(script = {}) {
  const home = join(tmp, `home-${counter += 1}`, 'cinefilm-viewer');
  const log = [];
  const said = [];
  const listeners = new Map();
  let now = 5000;
  let wall = BEGAN;
  let waits = 0;
  let overran = false;
  let looks = 0;
  let hung = false;
  let current = 'session';
  const hungSettlers = [];
  // A look that is never answered: it rejects (logging that it did) at the run's next wait, or after 200 ms if the run
  // is stuck on it, which is code that waited for it.
  const hungLook = () => new Promise((_, reject) => {
    let done = false;
    const settle = () => {
      if (done) return;
      done = true;
      log.push('hung look rejects');
      reject(new Error('waited for a look never answered'));
    };
    hungSettlers.push(settle);
    setTimeout(settle, 200);
  });
  const cancel = createCancel();
  const held = {};
  const samples = script.samples ?? [playing(1), playing(6), { ...playing(9), ended: true }];
  const page = {
    closed: false,
    on(method, fn) {
      log.push(`on ${method}`);
      listeners.set(method, fn);
    },
    async send(method, params) {
      log.push(`send ${method}${params?.url ? ` ${params.url}` : ''}`);
      if (params?.url?.startsWith(SITE)) current = params.url.endsWith('/api/session') ? 'session' : 'player';
      if (script.failSend?.(method, params, page)) throw new Error(`${method}: no reply within 30000 ms`);
      if (method === 'Page.navigate' && params.url.endsWith('/api/session') && script.noAnswer?.()) {
        return { errorText: 'net::ERR_INTERNET_DISCONNECTED' };
      }
      // A page that is opened loads (the player's page too: the viewer counts it opened when it has).
      if (method === 'Page.navigate' && params.url !== 'about:blank') {
        setImmediate(() => listeners.get('Page.loadEventFired')?.());
      }
      return {};
    },
    async evaluate(expression) {
      if (expression === STATUS_EXPRESSION) {
        const status = script.status?.[current];
        return typeof status === 'function' ? status() : status ?? null;
      }
      if (expression === PAGE_TEXT) return script.session ?? '{"authenticated":true}';
      if (expression.startsWith('(async')) {
        if (typeof script.filmThrows === 'function' ? script.filmThrows() : script.filmThrows) {
          throw new Error('no reply within 30000 ms');
        }
        return script.film ?? FILM;
      }
      if (expression === PRESS_RETRY) {
        log.push('press retry');
        return true;
      }
      if (expression.includes('.click()')) {
        log.push(`press place ${JSON.parse(/=== ("[^"]*")\)/.exec(expression)[1])}`);
        return true;
      }
      looks += 1;
      script.onLook?.(looks, page);
      const answer = samples[Math.min(looks, samples.length) - 1];
      const unreadable = script.unreadable?.(looks);
      hung = unreadable === 'hang';
      if (unreadable === 'throw') throw new Error('Execution context was destroyed');
      // A look never answered: the viewer gives it up (the fake `within` says so). Code that waited for it anyway would
      // hang, so after a moment it is answered with an error that says so, and its test fails with that reason.
      return hung ? hungLook() : answer;
    },
  };
  const chrome = {
    page,
    async close() {
      log.push('close');
    },
  };
  const deps = {
    opt: { site: SITE, film: 42, minutes: script.minutes ?? null, place: script.place ?? null },
    viewerHome: home, cancel, held, pid: process.pid,
    say: (text) => {
      if (script.sayThrows) throw new Error('no terminal');
      said.push(text);
    },
    clock: { mono: () => now, wall: () => wall },
    wait: async (ms) => {
      waits += 1;
      // A run that never ends (a wrong rule) would repeat the fake page's last sample for ever: it is stopped here, and
      // `run()` then fails with this reason instead of hanging.
      if (waits > (script.maxWaits ?? MAX_WAITS)) {
        overran = true;
        cancel.trigger();
      }
      log.push(`wait ${ms}`);
      for (const settle of hungSettlers.splice(0)) settle();
      now += ms;
      wall += ms + (script.extraWall?.(waits) ?? 0);
      script.onWait?.(waits, { cancel, listeners, held });
    },
    pause: async (ms) => log.push(`pause ${ms}`),
    within: async (promise, ms) => {
      // A look is given 10 s: made up here, no real wait; a look that is never answered is given up on at once.
      if (ms === LOOK_MS) {
        log.push(`look within ${ms}`);
        if (hung) return false;
      }
      await promise;
      return script.loads !== false;
    },
    launch: async (args) => {
      log.push('launch');
      deps.launchArgs = args;
      args.onSpawn(() => log.push('hard stop'));
      return chrome;
    },
    awake: () => log.push('awake'),
    ...script.deps,
  };
  return { deps, log, said, home, page, listeners, cancel, held, run: async () => {
    const code = await viewing(deps);
    if (overran) throw new Error(`the run did not end: the fake page was waited on more than ${script.maxWaits ?? MAX_WAITS} times`);
    return code;
  } };
}

const END_ORDER = ['send Page.navigate about:blank', `pause ${END_WAIT_MS}`, 'close'];
const tail = (log, n) => log.slice(-n);

describe('a run to the film\'s end', () => {
  test('is ordered: awake, Chrome, the guard on before any address, the checks, then the player', async () => {
    const w = world();
    assert.equal(await w.run(), 0);
    const at = (entry) => w.log.indexOf(entry);
    const firstAddress = w.log.findIndex((entry) => entry.startsWith('send Page.navigate'));
    assert.deepEqual(w.log.slice(0, 3), ['awake', 'launch', 'on Fetch.requestPaused']);
    assert.ok(at('send Fetch.enable') > at('on Fetch.requestPaused'));
    assert.ok(at('send Fetch.enable') < firstAddress, 'Fetch.enable is sent before the first address is opened');
    assert.equal(w.log[firstAddress], `send Page.navigate ${SITE}/api/session`);
    assert.ok(w.log.includes(`send Page.navigate ${SITE}/films/42/play?start=0`));
    assert.ok(at(`send Page.navigate ${SITE}/films/42/play?start=0`) > firstAddress);
    assert.deepEqual(w.deps.launchArgs, {
      profile: viewerPaths(w.home).profile, channel: 'pipe', ownGroup: true, onSpawn: w.deps.launchArgs.onSpawn,
    });
  });

  test('says the film, its length and the site, and that Ctrl-C ends the run', async () => {
    const w = world();
    await w.run();
    assert.equal(w.said[0], `Film 42: Heat, 2 h 50 min, on ${SITE}. Ctrl-C ends the run and writes the results.`);
  });

  test('ends in order: the page sent away, the three seconds, Chrome closed; then how it ended is said', async () => {
    const w = world();
    await w.run();
    assert.deepEqual(tail(w.log, 3), END_ORDER);
    assert.equal(w.said.at(-1), `${ENDS['film-ended'].line} 3 samples taken.`);
  });

  test('keeps every sample, the last one (which says the film ended) included, with both clocks', async () => {
    const w = world();
    await w.run();
    const { samples } = w.held.run;
    assert.equal(samples.length, 3);
    assert.deepEqual(samples.map((sample) => sample.sinceMs), [5000, 10000, 15000]);
    assert.deepEqual(samples.map((sample) => sample.at), [BEGAN + 5000, BEGAN + 10000, BEGAN + 15000]);
    assert.equal(samples[2].ended, true);
  });

  test('lets the lock go at the end', async () => {
    const w = world();
    await w.run();
    assert.equal(existsSync(viewerPaths(w.home).lock), false);
  });
});

describe('what ends a run', () => {
  // [name, script, word, exit code, whether Chrome is hard-stopped before the end, a line that is said]
  const cases = [
    ['the film ends', {}, 'film-ended', 0, false, /film ended/],
    ['--minutes is reached', { minutes: 1, samples: [playing(1)] }, 'minutes', 0, false, /--minutes/],
    ['a signal comes', { onWait: (n, { cancel }) => n === 2 && cancel.trigger() }, 'signal', 0, false, /signal/],
    ['the Signed out card is up', { samples: [playing(1), { video: false, signedOut: true }] }, 'signed-out', 1, false,
      /signs every browser out.*--sign-in/],
    ['Admin stopped it', { samples: [playing(1), { video: false, stopped: true }] }, 'stopped-from-admin', 1, false,
      /stopped from Admin/],
    ['Chrome goes', { onLook: (n, page) => n === 2 && (page.closed = true) }, 'chrome-gone', 1, true, /Chrome went/],
  ];
  for (const [name, script, word, code, hard, line] of cases) {
    test(`${name}: ${word}, exit ${code}, what was kept says how it ended, and the end in order`, async () => {
      const w = world(script);
      assert.equal(await w.run(), code);
      assert.equal(w.held.run.ended, word);
      assert.ok(w.held.run.samples.length >= 1);
      assert.match(w.said.join('\n'), line);
      assert.deepEqual(tail(w.log, hard ? 2 : 3), hard ? ['hard stop', 'close'] : END_ORDER);
      assert.equal(w.log.includes('send Page.navigate about:blank') || w.log.includes(`pause ${END_WAIT_MS}`), !hard);
    });
  }

  test('--minutes is a sample at its exact time, and no more', async () => {
    const w = world({ minutes: 1, samples: [playing(1)] });
    await w.run();
    assert.equal(w.held.run.samples.length, 12);
    assert.deepEqual(w.log.filter((entry) => entry.startsWith('wait')), new Array(12).fill('wait 5000'));
  });
});

describe('the end of a run, on every way out', () => {
  test('after an error: the page is still sent away, given three seconds, then Chrome is closed', async () => {
    const w = world({ failSend: (method) => method === 'Runtime.enable' });
    assert.equal(await w.run(), 1);
    assert.deepEqual(tail(w.log, 3), END_ORDER);
    assert.match(w.said.join('\n'), /The run failed: Runtime.enable: no reply/);
  });

  test('a signal before the film opened: exit 130, Chrome ended in order, no sample, lock let go', async () => {
    const w = world({ noAnswer: () => true, onWait: (n, { cancel }) => cancel.trigger() });
    assert.equal(await w.run(), 130);
    assert.deepEqual(tail(w.log, 3), END_ORDER);
    assert.equal(w.held.run.samples.length, 0);
    assert.equal(w.said.at(-1), `${ENDS.signal.line} 0 samples taken.`);
    assert.equal(existsSync(viewerPaths(w.home).lock), false);
  });

  // [what goes wrong, the page, what is logged]
  const cannot = [
    ['the page is closed', { closed: true }, ['hard stop', 'close']],
    ['the send rejects', { send: () => Promise.reject(new Error('gone')) },
      ['send Page.navigate about:blank', 'hard stop', 'close']],
    ['Chrome answers the navigation with an errorText', { send: () => ({ errorText: 'net::ERR_ABORTED' }) },
      ['send Page.navigate about:blank', 'hard stop', 'close']],
    ['the send throws', { send: () => {
      throw new Error('gone');
    } }, ['send Page.navigate about:blank', 'hard stop', 'close']],
  ];
  for (const [what, change, expected] of cannot) {
    test(`when ${what}, Chrome is hard-stopped before anything else, with no wait, then closed`, async () => {
      const log = [];
      const page = { closed: false, send: async (method, params) => log.push(`send ${method} ${params.url}`) };
      Object.assign(page, change);
      if (change.send) {
        const send = change.send;
        page.send = (method, params) => {
          log.push(`send ${method} ${params.url}`);
          return send();
        };
      }
      const held = { chromeKill: () => log.push('hard stop') };
      const chrome = { page, close: async () => log.push('close') };
      await leave({ chrome, held, pause: async () => log.push('pause') });
      assert.deepEqual(log.map((entry) => entry.trim()), expected);
      assert.equal(held.chromeKill, null);
    });
  }

  test('Chrome that will not close is hard-stopped after; with none started only the hard stop is tried', async () => {
    const log = [];
    const held = { chromeKill: () => log.push('hard stop') };
    const page = { closed: false, send: async () => log.push('away') };
    const stuck = { page, close: async () => Promise.reject(new Error('stuck')) };
    await leave({ chrome: stuck, held, pause: async () => 0 });
    assert.deepEqual(log, ['away', 'hard stop']);
    await leave({ chrome: null, held: { chromeKill: () => log.push('only') }, pause: async () => 0 });
    assert.deepEqual(log, ['away', 'hard stop', 'only']);
  });

  test('a hard stop that throws does not stop the end', async () => {
    const held = { chromeKill: () => {
      throw new Error('no');
    } };
    const log = [];
    const page = { closed: true, send: async () => 0 };
    await leave({ chrome: { page, close: async () => log.push('close') }, held, pause: async () => 0 });
    assert.deepEqual(log, ['close']);
  });
});

describe('the checks before it plays, in a run', () => {
  const refusals = [
    ['the site never answers', { noAnswer: () => true }, /did not answer in 10 minutes/],
    ['the page does not load in 30 seconds', { loads: false }, /did not answer in 10 minutes/],
    ['the film cannot be fetched', { film: { status: 0 } }, /did not answer in 10 minutes/],
    ['the film cannot be asked for', { filmThrows: true }, /did not answer in 10 minutes/],
    ['signed out', { session: '{"authenticated":false}' }, /Not signed in. Run --sign-in/],
    ['the answer is not understood', { session: '<html>' }, /not understood/],
    ['there is no such film', { film: { status: 404 } }, /There is no film 42/],
    ['the site answers another status', { film: { status: 500 } }, /status 500/],
    ['the quality is set by hand', { film: { ...FILM, quality: '720p' } }, /quality "720p" by hand.*to Auto/],
  ];
  for (const [name, script, line] of refusals) {
    test(`${name}: one line, exit 1, nothing played, nothing sampled`, async () => {
      const w = world(script);
      assert.equal(await w.run(), 1);
      assert.match(w.said.join('\n'), line);
      assert.equal(w.log.some((entry) => entry.includes('/films/')), false);
      assert.equal(w.held.run.samples.length, 0);
      assert.deepEqual(tail(w.log, 3), END_ORDER);
    });
  }

  test('with the site not answering it says so once and tries again every 15 seconds for ten minutes', async () => {
    const w = world({ noAnswer: () => true });
    await w.run();
    assert.equal(w.said.filter((line) => /waiting for it/.test(line)).length, 1);
    assert.equal(w.log.filter((entry) => entry === `wait ${SITE_RETRY_MS}`).length, SITE_WAIT_MS / SITE_RETRY_MS);
    const asked = w.log.filter((entry) => entry === `send Page.navigate ${SITE}/api/session`);
    assert.equal(asked.length, SITE_WAIT_MS / SITE_RETRY_MS + 1);
  });

  test('a gateway error on the player\'s page once is waited out like a dead link, and the run goes on', async () => {
    let opens = 0;
    const w = world({ status: { player: () => ((opens += 1) === 1 ? 503 : 200) } });
    assert.equal(await w.run(), 0);
    assert.equal(w.said.filter((line) => /waiting for it/.test(line)).length, 1);
    assert.equal(w.log.filter((entry) => entry === 'wait 15000').length, 1);
    assert.equal(opens, 2);
    assert.equal(w.held.run.samples.length, 3);
  });

  // A send, or a read of the film, that Chrome does not answer in 30 seconds on a dead link: the first two times.
  function dead() {
    const state = { used: 0 };
    return {
      state,
      fail: () => {
        if (state.used >= 2) return false;
        state.used += 1;
        return true;
      },
    };
  }
  const steps = [
    ['the session check', ({ fail }) => ({
      failSend: (method, params) => params?.url?.endsWith('/api/session') && fail(),
    })],
    ['the film\'s facts', ({ fail }) => ({ filmThrows: fail })],
    ['the opening of the player\'s page', ({ fail }) => ({
      failSend: (method, params) => /\/play\?/.test(params?.url ?? '') && fail(),
    })],
  ];
  for (const [step, make] of steps) {
    test(`a dead link at ${step} sends the loop round again from the start, and the run goes on`, async () => {
      const link = dead();
      const w = world(make(link));
      assert.equal(await w.run(), 0);
      assert.equal(link.state.used, 2);
      assert.deepEqual(w.log.filter((entry) => entry.startsWith('wait')).slice(0, 2), ['wait 15000', 'wait 15000']);
      assert.equal(w.said.filter((line) => /waiting for it/.test(line)).length, 1);
      assert.equal(w.log.filter((entry) => entry === `send Page.navigate ${SITE}/api/session`).length, 3);
      assert.equal(w.held.run.samples.length, 3);
    });
  }

  test('Chrome gone at the start is a failure at once, not a wait: exit 1, hard-stopped, no 15 seconds', async () => {
    const w = world({ failSend: (method, params, page) => {
      page.closed = true;
      return params?.url?.endsWith('/api/session');
    } });
    assert.equal(await w.run(), 1);
    assert.match(w.said.join('\n'), /The run failed: Chrome went away/);
    assert.equal(w.said.at(-1), `${ENDS['chrome-gone'].line} 0 samples taken.`);
    assert.equal(w.log.some((entry) => entry.startsWith('wait')), false);
    assert.deepEqual(tail(w.log, 2), ['hard stop', 'close']);
    assert.equal(existsSync(viewerPaths(w.home).lock), false);
  });

  test('another viewer holding the lock refuses, starts no Chrome and leaves the lock alone', async () => {
    const first = world();
    const release = takeLock(first.home, { pid: 4242, isAlive: () => true });
    const second = world({ deps: { viewerHome: first.home, isAlive: () => true } });
    assert.equal(await second.run(), 1);
    assert.match(second.said.join('\n'), /another viewer is running \(process 4242\)/);
    assert.equal(second.log.includes('launch'), false);
    assert.equal(readFileSync(viewerPaths(first.home).lock, 'utf8'), '4242');
    release();
  });
});

describe('the end of a run: what is kept, said and let go', () => {
  test('what was kept is written first, then how it ended is said, then the lock is let go', async () => {
    const w = world();
    w.deps.writeNow = () => w.log.push('write');
    w.deps.lock = () => () => w.log.push('lock');
    w.deps.say = (text) => w.log.push(`say ${text}`);
    await w.run();
    const at = w.log.indexOf('write');
    assert.deepEqual(w.log.slice(at - 3, at), END_ORDER);
    assert.deepEqual(w.log.slice(at, at + 3), ['write', 'say The film ended. 3 samples taken.', 'lock']);
  });

  test('a writer that throws is one line and exit 1, and the lock is still let go', async () => {
    const w = world();
    w.deps.writeNow = () => {
      throw new Error('disk full');
    };
    w.deps.lock = () => () => w.log.push('lock');
    assert.equal(await w.run(), 1);
    assert.match(w.said.join('\n'), /the results could not be written: disk full/);
    assert.equal(w.log.at(-1), 'lock');
  });

});

describe('a page that stops answering', () => {
  // Looks that read fine, the tenth ending the film: a run that does not end where it should ends there, and its test
  // fails with a plain reason instead of hanging.
  const many = [...new Array(9).fill(playing(5)), { ...playing(9), ended: true }];
  const looksOf = (w) => w.log.filter((entry) => entry === `look within ${LOOK_MS}`).length;

  test('one and two looks that could not be read are samples with their error text, and the run goes on', async () => {
    const w = world({
      samples: [playing(1), playing(2), playing(3), { ...playing(4), ended: true }],
      unreadable: (n) => (n === 2 ? 'throw' : n === 3 ? 'hang' : null),
    });
    assert.equal(await w.run(), 0);
    const { samples } = w.held.run;
    assert.equal(samples.length, 4);
    assert.deepEqual(samples.map((sample) => sample.video), [true, false, false, true]);
    assert.equal(samples[1].error, 'Execution context was destroyed');
    assert.equal(samples[2].error, 'no answer in 10 s');
    assert.equal(w.held.run.ended, 'film-ended');
  });

  test('three in a row end the run: page-crashed, exit 1, the line, and the end in order', async () => {
    const w = world({ samples: many, unreadable: (n) => (n >= 2 && n <= 4 ? 'throw' : null) });
    assert.equal(await w.run(), 1);
    assert.equal(w.held.run.ended, 'page-crashed');
    assert.equal(w.held.run.samples.length, 4, 'it ends at the third, not before and not after');
    assert.equal(w.said.at(-1), 'The player\'s page stopped answering. 4 samples taken.');
    assert.deepEqual(tail(w.log, 3), END_ORDER);
  });

  test('a look that is read between them starts the count again', async () => {
    const w = world({
      samples: [...many.slice(0, 7), { ...playing(9), ended: true }],
      unreadable: (n) => ([2, 3, 5, 6].includes(n) ? 'throw' : null),
    });
    assert.equal(await w.run(), 0);
    assert.equal(w.held.run.ended, 'film-ended');
    assert.equal(w.held.run.unread, 0);
  });

  test('a look that is not answered is given 10 seconds, each time, and counts as unread', async () => {
    const w = world({ samples: many, unreadable: (n) => (n >= 2 && n <= 4 ? 'hang' : null) });
    assert.equal(await w.run(), 1);
    assert.equal(looksOf(w), 4, 'one within per look');
    // The run gave the look up and went on (its next wait) before the hung look ever rejected: it was not waited for.
    const gaveUp = w.log.indexOf(`look within ${LOOK_MS}`, w.log.indexOf(`look within ${LOOK_MS}`) + 1);
    assert.ok(gaveUp > 0);
    assert.ok(w.log.indexOf('wait 5000', gaveUp) < w.log.indexOf('hung look rejects'), 'it did not wait for the look');
    assert.equal(w.held.run.ended, 'page-crashed');
    assert.deepEqual(w.held.run.samples.map((sample) => sample.error ?? null), [null, 'no answer in 10 s',
      'no answer in 10 s', 'no answer in 10 s']);
  });

  test('nothing is pressed on a look that could not be read', async () => {
    const retry = { ...playing(2), retry: true, card: 'Where are you watching?' };
    const w = world({
      samples: [playing(1), retry, { ...playing(9), ended: true }], unreadable: (n) => (n === 2 ? 'throw' : null),
    });
    await w.run();
    assert.equal(w.log.some((entry) => entry.startsWith('press')), false);
  });

  test('with Chrome gone, unread looks are Chrome gone, not a page that stopped answering', async () => {
    const w = world({
      samples: many, unreadable: (n) => (n >= 2 ? 'throw' : null), onLook: (n, page) => n === 2 && (page.closed = true),
    });
    assert.equal(await w.run(), 1);
    assert.equal(w.held.run.ended, 'chrome-gone');
  });
});

describe('the signals of a run', () => {
  const listening = (held, say = () => undefined) => {
    const log = [];
    const handlers = new Map();
    const cancel = createCancel();
    watchRunSignals(cancel, held, say, {
      exit: (code) => log.push(`exit ${code}`), listen: (name, fn) => handlers.set(name, fn),
    });
    return { log, handlers, cancel };
  };

  const stepsLogging = (log) => ({
    chromeKill: () => log.push('hard stop'), writeNow: () => log.push('write'), release: () => log.push('lock'),
  });

  test('listens for SIGINT, SIGTERM and SIGHUP', () => {
    assert.deepEqual([...listening({}).handlers.keys()], ['SIGINT', 'SIGTERM', 'SIGHUP']);
  });

  test('the first signal says so and cancels, and stops nothing and does not exit', () => {
    const log = [];
    const held = stepsLogging(log);
    const l = listening(held, (text) => log.push(`say ${text}`));
    l.handlers.get('SIGINT')();
    assert.deepEqual(log, ['say stopping (a second Ctrl-C quits at once)']);
    assert.equal(l.cancel.cancelled, true);
    assert.deepEqual(l.log, []);
  });

  test('the first signal ends the run even when the terminal has gone and printing throws', () => {
    const l = listening({}, () => {
      throw new Error('no terminal');
    });
    l.handlers.get('SIGHUP')();
    assert.equal(l.cancel.cancelled, true);
  });

  test('a second signal hard-stops Chrome, writes the results, lets the lock go, then exits 130, in that order', () => {
    const log = [];
    const held = stepsLogging(log);
    const l = listening(held);
    l.handlers.get('SIGINT')();
    l.handlers.get('SIGINT')();
    assert.deepEqual([...log, ...l.log], ['hard stop', 'write', 'lock', 'exit 130']);
  });

  test('a step that throws, or one not there yet, skips none of the rest, and the exit still happens', () => {
    const log = [];
    const boom = () => {
      throw new Error('x');
    };
    const l = listening({ chromeKill: boom, writeNow: boom, release: () => log.push('lock') });
    l.handlers.get('SIGINT')();
    l.handlers.get('SIGTERM')();
    assert.deepEqual([...log, ...l.log], ['lock', 'exit 130']);
    const bare = listening({});
    bare.handlers.get('SIGINT')();
    bare.handlers.get('SIGINT')();
    assert.deepEqual(bare.log, ['exit 130']);
  });

  test('a second signal in the three seconds of the end still writes what there is, before it exits', async () => {
    const w = world({ onWait: (n) => n === 2 && handlers.get('SIGINT')() });
    const handlers = new Map();
    w.deps.writeNow = (word) => w.log.push(`write ${word}`);
    const state = () => `lock ${existsSync(viewerPaths(w.home).lock)}`;
    watchRunSignals(w.cancel, w.held, w.deps.say, {
      exit: (code) => w.log.push(`exit ${code} with ${state()}`),
      listen: (name, fn) => handlers.set(name, fn),
    });
    w.deps.pause = async (ms) => {
      w.log.push(`pause ${ms}`);
      handlers.get('SIGINT')();
    };
    assert.equal(await w.run(), 0);
    const at = w.log.indexOf(`pause ${END_WAIT_MS}`);
    assert.deepEqual(w.log.slice(at, at + 4), [
      `pause ${END_WAIT_MS}`, 'hard stop', 'write signal', 'exit 130 with lock false',
    ]);
    assert.equal(w.held.run.samples.length, 1);
  });

  test('two signals while the page is looked at write with the word signal, exit, and let the lock go', async () => {
    const handlers = new Map();
    const w = world({ onLook: (n) => {
      if (n !== 2) return;
      handlers.get('SIGINT')();
      handlers.get('SIGINT')();
    } });
    w.deps.writeNow = (word) => w.log.push(`write ${word} after ${w.held.run.samples.length} samples`);
    watchRunSignals(w.cancel, w.held, w.deps.say, {
      exit: (code) => w.log.push(`exit ${code} lock ${existsSync(viewerPaths(w.home).lock)}`),
      listen: (name, fn) => handlers.set(name, fn),
    });
    await w.run();
    const at = w.log.indexOf('hard stop');
    assert.deepEqual(w.log.slice(at, at + 3), ['hard stop', 'write signal after 1 samples', 'exit 130 lock false']);
    assert.equal(w.held.run.ended, 'signal');
  });

  test('a signal that comes while the page is being looked at is not followed by a press or a line', async () => {
    const retry = { ...playing(2), retry: true };
    const w = world({
      samples: [playing(1), retry], onLook: (n, page) => n === 2 && w.cancel.trigger(),
    });
    assert.equal(await w.run(), 0);
    assert.equal(w.log.some((entry) => entry.startsWith('press')), false);
    assert.equal(w.said.filter((line) => line.includes('playhead')).length, 1, 'only the first sample has its line');
    assert.equal(w.held.run.ended, 'signal');
    assert.equal(w.held.run.samples.length, 2);
  });
});

describe('what is pressed during a run', () => {
  const place = { video: false, card: 'Where are you watching?' };
  test('the place card is pressed once, with the --place answer (none: Not now), though it stays up', async () => {
    for (const [argument, label] of [['train', 'On a train'], ['away', 'Hotel or away'], [null, 'Not now']]) {
      const w = world({ place: argument, samples: [playing(1), place, place, place, { ...playing(9), ended: true }] });
      await w.run();
      assert.deepEqual(w.log.filter((entry) => entry.startsWith('press')), [`press place ${label}`], String(argument));
      assert.equal(w.held.run.presses.place, label);
    }
  });

  test('"Try again" is pressed once in each sample that shows one, counted; nothing else is ever pressed', async () => {
    const retry = { ...playing(1), retry: true };
    const w = world({ samples: [retry, retry, playing(2), retry, { ...playing(9), ended: true }] });
    await w.run();
    assert.deepEqual(w.log.filter((entry) => entry.startsWith('press')), new Array(3).fill('press retry'));
    assert.deepEqual(w.held.run.presses, { place: null, retries: 3 });
  });

  test('when Admin has stopped it, "Play again" is not pressed: the run ends', async () => {
    const w = world({ samples: [playing(1), { video: false, stopped: true, retry: true }] });
    await w.run();
    assert.deepEqual(w.log.filter((entry) => entry.startsWith('press')), []);
  });

  test('a press the page cannot take is not counted', async () => {
    const w = world({ samples: [{ ...playing(1), retry: true }, { ...playing(9), ended: true }] });
    const evaluateIt = w.page.evaluate;
    w.page.evaluate = async (expression) => {
      if (expression === PRESS_RETRY) throw new Error('gone');
      return evaluateIt(expression);
    };
    await w.run();
    assert.equal(w.held.run.presses.retries, 0);
  });
});

describe('the line every 30 seconds, the warnings and the page\'s errors, in a run', () => {
  test('one is printed with the first sample and then every 30 seconds', async () => {
    const w = world({ samples: [...new Array(8).fill(playing(5)), { ...playing(9), ended: true }] });
    await w.run();
    const lines = w.said.filter((line) => line.includes('playhead'));
    assert.equal(lines.length, 2);
    assert.match(lines[0], /\+5s/);
    assert.match(lines[1], /\+35s/);
  });

  test('a laptop that stood still is a warning, kept with the run', async () => {
    const w = world({ extraWall: (n) => (n === 2 ? 60000 : 0) });
    await w.run();
    const { warnings } = w.held.run;
    assert.equal(warnings.length, 1);
    assert.match(warnings[0], /^the laptop stood still for 65 s at second \d+ of the run, and nothing was watched/);
  });

  test('the page\'s errors and console errors are kept, printable, at most 100', async () => {
    const w = world({ onWait: (n, { listeners }) => {
      if (n !== 1) return;
      for (let i = 0; i < 120; i += 1) {
        const exception = { description: `boom ${i}\nline` };
        listeners.get('Runtime.exceptionThrown')({ exceptionDetails: { exception } });
      }
      listeners.get('Runtime.consoleAPICalled')({ type: 'log', args: [{ value: 'quiet' }] });
    } });
    await w.run();
    const { pageErrors } = w.held.run;
    assert.equal(pageErrors.length, 100);
    assert.equal(pageErrors[0], 'boom 0 line');
  });
});

// The folder of the run that was written (there is at most one), as the files in it.
function results(w) {
  const runs = join(w.home, 'runs');
  const [name] = readdirSync(runs);
  if (!name) return null;
  const dir = join(runs, name);
  const read = (file) => JSON.parse(readFileSync(join(dir, file), 'utf8'));
  return { dir, name, samples: read('samples.json'), health: read('health.json'), held: read('held.json') };
}

// What is on disk in the run's folder now: the file names, how many samples, what `ended` says; null with no folder.
function onDisk(w) {
  const runs = join(w.home, 'runs');
  if (!existsSync(runs) || readdirSync(runs).length === 0) return null;
  const out = results(w);
  return { files: readdirSync(out.dir).sort(), samples: out.samples.length, ended: out.held.run.ended };
}

const REPORT = { held: [], heldDropped: 0, records: [], notRead: 0, failed: 0, rejected: 0 };
const RUN = { samples: [{ t: 1 }], pageErrors: [], warnings: [], presses: { place: null, retries: 0 }, ended: null };

describe('what is written', () => {
  test('the folder is named from the time the run began, by local time, with the right modes', async () => {
    const w = world();
    await w.run();
    const out = results(w);
    assert.equal(out.name, '20261117-140509');
    assert.equal(modeOf(join(w.home, 'runs')), 0o700);
    assert.equal(modeOf(out.dir), 0o700);
    for (const file of ['samples.json', 'health.json', 'held.json', 'summary.txt']) {
      assert.equal(modeOf(join(out.dir, file)), 0o600);
    }
    assert.equal(runName(new Date(2026, 0, 5, 3, 4, 9).getTime()), '20260105-030409');
  });

  test('health.json holds the records as sent, held.json what was held and how it ended, nothing else', async () => {
    const fire = (listeners, requestId, method, path, extra = {}) => listeners.get('Fetch.requestPaused')({
      requestId, request: { method, url: `${SITE}${path}`, ...extra },
    });
    const w = world({ onWait: (n, { listeners }) => {
      if (n !== 1) return;
      const headers = { Cookie: 'cinefilm=sekret-cookie', Authorization: 'Bearer sekret-header' };
      fire(listeners, 'a', 'PUT', `/api/playback-health/${UUID}`, { postData: '{"seq":1,"operator":"Acme"}', headers });
      fire(listeners, 'b', 'PUT', `/api/playback-health/${UUID}`, { postData: '{"seq":2,"operator":"Acme"}', headers });
      fire(listeners, 'c', 'PUT', `/api/playback-health/${UUID}`, { postData: 'not json', headers });
      const body = { postData: '{"p":"sekret-body"}', headers };
      fire(listeners, 'd', 'POST', '/api/editions/12/watched?x=sekret-query', body);
      fire(listeners, 'e', 'PUT', '/api/editions/12/progress', { postData: '{"positionMs":5}', headers });
    } });
    await w.run();
    const out = results(w);
    const latest = { seq: 2, operator: 'Acme' };
    assert.deepEqual(out.health, { sent: 3, notRead: 1, viewings: [{ id: UUID, count: 2, latest }] });
    assert.deepEqual(out.held.held, [
      { method: 'POST', shape: '/api/editions/N/watched', count: 1 },
      { method: 'PUT', shape: '/api/editions/N/progress', count: 1 },
    ]);
    assert.deepEqual([out.held.failed, out.held.rejected, out.held.heldDropped], [0, 0, 0]);
    assert.deepEqual(out.held.run, {
      film: 42, title: 'Heat', runtimeMs: 10200000, site: SITE, beganAt: BEGAN, runMs: 15000, ended: 'film-ended',
    });
    for (const file of ['samples.json', 'health.json', 'held.json', 'summary.txt']) {
      const text = readFileSync(join(out.dir, file), 'utf8');
      for (const secret of ['sekret', 'Cookie', 'Authorization']) {
        assert.equal(text.includes(secret), false, `${file} ${secret}`);
      }
    }
  });

  test('the summary is printed at the end, line for line what summary.txt holds', async () => {
    const w = world();
    await w.run();
    const { dir } = results(w);
    const printed = w.said.slice(w.said.indexOf('Film 42 Heat'), -2);
    assert.deepEqual(printed, readFileSync(join(dir, 'summary.txt'), 'utf8').trimEnd().split('\n'));
    assert.equal(w.said.at(-2), `results: ${dir}`);
  });

  test('a run that took no sample writes nothing and says so', async () => {
    const w = world({ noAnswer: () => true });
    assert.equal(await w.run(), 1);
    assert.equal(onDisk(w), null);
    assert.equal(w.said.some((line) => line === 'nothing was written: no sample was taken'), true);
  });

  test('a folder that cannot be made is one line, exit 1, and the lock is still let go', async () => {
    const w = world();
    mkdirSync(join(w.home, 'runs'), { recursive: true });
    writeFileSync(join(w.home, 'runs', '20261117-140509'), 'a file in the way');
    assert.equal(await w.run(), 1);
    assert.match(w.said.join('\n'), /the results could not be written/);
    assert.equal(existsSync(viewerPaths(w.home).lock), false);
  });

  test('printing that throws does not stop the run or the writing of the results', async () => {
    const w = world({ sayThrows: true });
    assert.equal(await w.run(), 0);
    assert.equal(results(w).samples.length, 3);
    assert.deepEqual(tail(w.log, 3), END_ORDER);
  });

  test('writeResults gives the folder and the summary\'s lines, the file holding the same lines', () => {
    const runsDir = join(tmp, 'direct-runs');
    const run = { ...RUN, pageErrors: ['e'], warnings: ['w'], ended: 'minutes' };
    const report = { ...REPORT, heldDropped: 2, failed: 1, rejected: 3 };
    const { dir, lines } = writeResults({ runsDir, began: BEGAN, run, report, facts: { film: 1 } });
    assert.equal(dir, join(runsDir, '20261117-140509'));
    assert.deepEqual(readdirSync(dir).sort(), ['health.json', 'held.json', 'samples.json', 'summary.txt']);
    assert.equal(readFileSync(join(dir, 'summary.txt'), 'utf8'), `${lines.join('\n')}\n`);
    const held = JSON.parse(readFileSync(join(dir, 'held.json'), 'utf8'));
    assert.deepEqual(Object.keys(held).sort(), [
      'failed', 'held', 'heldDropped', 'pageErrors', 'presses', 'rejected', 'run', 'warnings',
    ]);
    assert.deepEqual([held.heldDropped, held.failed, held.rejected], [2, 1, 3]);
    assert.equal(held.run.ended, 'minutes');
  });

  test('a write that is not the last says the run has not ended and writes no summary', () => {
    const runsDir = join(tmp, 'mid-runs');
    const { dir, lines } = writeResults({
      runsDir, began: BEGAN, run: { ...RUN, ended: 'x' }, report: REPORT, facts: { film: 1 }, final: false,
    });
    assert.deepEqual(readdirSync(dir).sort(), ['health.json', 'held.json', 'samples.json']);
    assert.equal(JSON.parse(readFileSync(join(dir, 'held.json'), 'utf8')).run.ended, null);
    assert.deepEqual(lines, []);
  });
});

describe('a file written whole, and forced to the disk', () => {
  test('a write that fails at the rename leaves the last good file as it was, and no temporary file', () => {
    const runsDir = join(tmp, 'whole-runs');
    const write = (run, io) => writeResults({
      runsDir, began: BEGAN, run, report: REPORT, facts: {}, final: false, io,
    });
    const { dir } = write(RUN);
    const first = readFileSync(join(dir, 'samples.json'), 'utf8');
    const io = { write: writeSynced, remove: (file) => rmSync(file, { force: true }), rename: () => {
      throw new Error('power cut');
    } };
    assert.throws(() => write({ ...RUN, samples: [{ t: 1 }, { t: 2 }] }, io), /power cut/);
    assert.equal(readFileSync(join(dir, 'samples.json'), 'utf8'), first, 'the last good file stands');
    assert.deepEqual(readdirSync(dir).sort(), ['health.json', 'held.json', 'samples.json']);
  });

  test('each file is written to its temporary name and renamed onto the real one, one after the other', () => {
    const log = [];
    const io = {
      write: (file) => {
        log.push(`write ${file.split('/').at(-1)}`);
        writeFileSync(file, '');
      },
      rename: (from, to) => {
        log.push(`rename ${to.split('/').at(-1)}`);
        renameSync(from, to);
      },
    };
    writeResults({
      runsDir: join(tmp, 'order-runs'), began: BEGAN, run: RUN, report: REPORT, facts: {}, final: false, io,
    });
    assert.deepEqual(log, [
      'write samples.json.tmp', 'rename samples.json', 'write health.json.tmp', 'rename health.json',
      'write held.json.tmp', 'rename held.json',
    ]);
  });

  // A made-up `fs` that logs what it is asked; `take(length)` says how many bytes each write takes.
  const madeUpFs = (log, take = (length) => length) => ({
    openSync: (file, flags, mode) => {
      log.push(`open ${flags} ${mode.toString(8)}`);
      return 7;
    },
    writeSync: (descriptor, bytes, offset, length) => {
      const taken = take(length);
      log.push(`write ${bytes.subarray(offset, offset + taken).toString()}`);
      return taken;
    },
    fsyncSync: () => log.push('fsync'),
    closeSync: () => log.push('close'),
  });

  test('the data is forced to the disk before the file is closed', () => {
    const log = [];
    writeSynced('x', 'data', madeUpFs(log));
    assert.deepEqual(log, ['open w 600', 'write data', 'fsync', 'close']);
  });

  test('a write that takes only part of the bytes is made again until every byte is written', () => {
    const log = [];
    writeSynced('x', 'abcdefgh', madeUpFs(log, (length) => Math.min(3, length)));
    assert.deepEqual(log, ['open w 600', 'write abc', 'write def', 'write gh', 'fsync', 'close']);
    const stuck = [];
    assert.throws(() => writeSynced('x', 'abc', madeUpFs(stuck, () => 0)), /no progress/);
    assert.deepEqual(stuck, ['open w 600', 'write ', 'close'], 'a write that takes nothing is an error, and closes');
  });

  test('the file is closed when the write fails, and an error from the close does not hide it', () => {
    const log = [];
    const fs = madeUpFs(log);
    fs.writeSync = () => {
      throw new Error('disk full');
    };
    fs.closeSync = () => {
      log.push('close');
      throw new Error('close failed');
    };
    assert.throws(() => writeSynced('x', 'data', fs), /disk full/);
    assert.deepEqual(log, ['open w 600', 'close']);
    const closing = madeUpFs([]);
    closing.closeSync = () => {
      throw new Error('close failed');
    };
    assert.throws(() => writeSynced('x', 'data', closing), /close failed/, 'a close that fails after a good write');
  });

  test('a temporary file that cannot be removed does not hide the error that came first', () => {
    const runsDir = join(tmp, 'stuck-runs');
    const io = {
      write: (file) => {
        mkdirSync(file);
        writeFileSync(join(file, 'inside'), 'x');
        throw new Error('first error');
      },
      rename: renameSync,
    };
    const attempt = () => writeResults({ runsDir, began: BEGAN, run: RUN, report: REPORT, facts: {}, io });
    assert.throws(attempt, /first error/);
  });
});

describe('what is written as the run goes', () => {
  test('with the first sample, then each minute, then at the end; whole, no temporary file left', async () => {
    assert.equal(KEEP_MS, 60000);
    const seen = [];
    const w = world({ minutes: 3, samples: [playing(1)], onWait: () => seen.push(onDisk(w)) });
    assert.equal(await w.run(), 0);
    const counts = seen.map((disk) => disk?.samples ?? 0);
    // a write at the 1st, 13th and 25th sample (the clock moves 5 s a sample); one at the end, the 36th
    assert.deepEqual(counts, [0, ...new Array(12).fill(1), ...new Array(12).fill(13), ...new Array(11).fill(25)]);
    for (const disk of seen.slice(1)) {
      assert.deepEqual(disk.files, ['health.json', 'held.json', 'samples.json'], 'no summary and no temporary file');
      assert.equal(disk.ended, null, 'while it goes, the run has not ended');
    }
    const last = results(w);
    assert.equal(last.samples.length, 36);
    assert.equal(last.held.run.ended, 'minutes');
    assert.equal(readdirSync(last.dir).includes('summary.txt'), true);
    assert.equal(readdirSync(last.dir).some((name) => name.endsWith('.tmp')), false);
  });

  test('a write that fails while the run goes is one warning, kept, and the run goes on to the end', async () => {
    let failing = true;
    const afs = { ...realAsyncFs, open: async (...args) => {
      if (failing) throw new Error('disk full');
      return realAsyncFs.open(...args);
    } };
    const w = world({ minutes: 3, samples: [playing(1)], deps: { afs }, onWait: (n) => {
      if (n === 30) failing = false;
    } });
    assert.equal(await w.run(), 0);
    const out = results(w);
    assert.equal(out.samples.length, 36, 'the last write wins');
    assert.equal(out.held.warnings.filter((line) => /could not be written: disk full/.test(line)).length, 1);
    assert.equal(readdirSync(out.dir).some((name) => name.endsWith('.tmp')), false);
  });

  test('a warning for something that is not an Error says what it was, not "undefined"', async () => {
    let failing = true;
    const afs = { ...realAsyncFs, open: async (...args) => {
      if (failing) throw 'a plain string';
      return realAsyncFs.open(...args);
    } };
    const w = world({ deps: { afs }, onWait: (n) => {
      if (n === 2) failing = false;
    } });
    await w.run();
    assert.equal(results(w).held.warnings[0], 'the results could not be written: a plain string');
  });

  test('a last write that fails is the error it is: one line and exit 1', async () => {
    const io = { write: () => {
      throw new Error('disk full');
    }, rename: renameSync };
    const w = world({ deps: { io } });
    assert.equal(await w.run(), 1);
    assert.match(w.said.join('\n'), /the results could not be written: disk full/);
  });

  test('the last write is made once, however many call it; the word counts before the run has ended', async () => {
    const w = world({ onWait: (n) => n === 2 && w.held.writeNow('failed') });
    await w.run();
    const out = results(w);
    assert.equal(out.held.run.ended, 'failed', 'the word given before the run had ended');
    assert.equal(out.samples.length, 1, 'what the crash wrote is what stays');
    assert.equal(w.held.writeNow('signal'), null);
    const done = world();
    await done.run();
    assert.equal(done.held.writeNow('failed'), null, 'nothing is written twice');
    assert.equal(results(done).held.run.ended, 'film-ended');
    const none = world({ noAnswer: () => true });
    await none.run();
    assert.equal(none.held.writeNow('failed'), null, 'nothing without a sample');
  });

  test('a crash while the page is being sent away does not change how the run ended', async () => {
    const w = world();
    w.deps.pause = async () => {
      w.held.writeNow('failed');
    };
    await w.run();
    assert.equal(results(w).held.run.ended, 'film-ended');
  });

  test('the last write says how the run ended, whichever way: a second signal, a crash', async () => {
    const handlers = new Map();
    const signalled = world({ onLook: (n) => {
      if (n !== 2) return;
      handlers.get('SIGINT')();
      handlers.get('SIGINT')();
    } });
    watchRunSignals(signalled.cancel, signalled.held, signalled.deps.say, {
      exit: () => undefined, listen: (name, fn) => handlers.set(name, fn),
    });
    await signalled.run();
    assert.equal(results(signalled).held.run.ended, 'signal');
    const crashed = world({ onWait: (n) => n === 3 && crashHandler(crashed.held, {
      say: () => undefined, exit: () => undefined,
    })(new Error('boom')) });
    await crashed.run();
    const out = results(crashed);
    assert.equal(out.held.run.ended, 'failed');
    assert.equal(out.samples.length, 2, 'what the crash wrote is what stays');
  });
});

describe('the writes made while the run goes', () => {
  // A made-up asynchronous fs: logs what it is asked, in order; `gate` (a promise) holds the write of the text.
  const asyncFs = (log, gate = Promise.resolve()) => ({
    open: async (file, flags, mode) => {
      log.push(`open ${file.split('/').at(-1)} ${flags} ${mode.toString(8)}`);
      return {
        writeFile: async () => {
          await gate;
          log.push('write');
        },
        sync: async () => log.push('sync'),
        close: async () => log.push('close'),
      };
    },
    rename: async (from, to) => log.push(`rename ${to.split('/').at(-1)}`),
    rm: async () => log.push('rm'),
  });

  test('each file is opened, written whole, synced, closed and only then renamed', async () => {
    const log = [];
    await writeSyncedAsync('x', 'data', asyncFs(log));
    assert.deepEqual(log, ['open x w 600', 'write', 'sync', 'close']);
    log.length = 0;
    await writeResultsAsync({
      runsDir: join(tmp, 'async-order'), began: BEGAN, run: RUN, report: REPORT, facts: {}, afs: asyncFs(log),
    });
    const each = (name) => [`open ${name}.async.tmp w 600`, 'write', 'sync', 'close', `rename ${name}`];
    assert.deepEqual(log, [...each('samples.json'), ...each('health.json'), ...each('held.json')]);
  });

  test('a write that fails leaves no temporary file behind, and the error goes on', async () => {
    const log = [];
    const afs = asyncFs(log);
    afs.rename = async () => {
      throw new Error('no rename');
    };
    await assert.rejects(writeResultsAsync({
      runsDir: join(tmp, 'async-fail'), began: BEGAN, run: RUN, report: REPORT, facts: {}, afs,
    }), /no rename/);
    assert.equal(log.at(-1), 'rm');
  });

  // The writer on its own, with a clock and a gated asynchronous fs.
  function writerWith(afs, io) {
    let now = 0;
    const ctx = {
      run: { ...RUN, ended: null }, opt: { film: 1, site: SITE }, opened: 0, guard: null,
      deps: { clock: { mono: () => now }, afs, io },
    };
    const writer = resultsWriter(ctx, { began: BEGAN, runsDir: join(tmp, `writer-${counter += 1}`) });
    return { writer, ctx, at: (ms) => {
      now = ms;
    } };
  }
  const opens = (log) => log.filter((entry) => entry.startsWith('open')).length;

  test('one write at a time: while one is out another is not started, however long it has been', async () => {
    const log = [];
    let release;
    const gate = new Promise((resolve) => {
      release = resolve;
    });
    const { writer, at } = writerWith(asyncFs(log, gate));
    const first = writer.keep();
    await new Promise((resolve) => setImmediate(resolve));
    at(10 * KEEP_MS);
    const second = writer.keep();
    await new Promise((resolve) => setImmediate(resolve));
    assert.equal(opens(log), 1, 'no second write while the first is out');
    release();
    await Promise.all([first, second]);
    at(20 * KEEP_MS);
    await writer.keep();
    assert.equal(opens(log) > 1, true, 'once it has settled, a later write is made');
  });

  test('a write that is out when the crash handler writes never lands over what the handler wrote', async () => {
    let release;
    const gate = new Promise((resolve) => {
      release = resolve;
    });
    const real = {
      ...realAsyncFs,
      open: async (...args) => {
        const handle = await realAsyncFs.open(...args);
        return {
          writeFile: async (text) => {
            await gate;
            return handle.writeFile(text);
          },
          sync: () => handle.sync(),
          close: () => handle.close(),
        };
      },
    };
    const { writer, ctx } = writerWith(real);
    const runsDir = join(tmp, `writer-${counter}`);
    ctx.run.samples = [{ t: 1 }];
    const out = writer.keep();
    await new Promise((resolve) => setImmediate(resolve));
    ctx.run.samples = [{ t: 1 }, { t: 2 }];
    const handler = crashHandler({ writeNow: writer.final }, { say: () => undefined, exit: () => undefined });
    handler(new Error('boom'));
    release();
    await out;
    const dir = join(runsDir, readdirSync(runsDir)[0]);
    assert.equal(JSON.parse(readFileSync(join(dir, 'samples.json'), 'utf8')).length, 2, 'the handler\'s file stands');
    assert.equal(JSON.parse(readFileSync(join(dir, 'held.json'), 'utf8')).run.ended, 'failed');
    assert.deepEqual(readdirSync(dir).sort(), ['health.json', 'held.json', 'samples.json', 'summary.txt']);
  });

  test('the last write clears the mid-run temporary files before it writes, and again after', () => {
    const log = [];
    const name = (file) => file.split('/').at(-1);
    const io = {
      write: (file) => log.push(`write ${name(file)}`),
      rename: (from, to) => log.push(`rename ${name(to)}`),
      remove: (file) => log.push(`remove ${name(file)}`),
    };
    const { writer, ctx } = writerWith(undefined, io);
    ctx.run.samples = [{ t: 1 }];
    writer.final('failed');
    const removals = ['samples', 'health', 'held'].map((file) => `remove ${file}.json.async.tmp`);
    const writes = ['samples.json', 'health.json', 'held.json', 'summary.txt'].flatMap((file) => [
      `write ${file}.tmp`, `rename ${file}`,
    ]);
    assert.deepEqual(log, [...removals, ...writes, ...removals]);
  });

  test('a removal that fails does not stop the others, or the last write', () => {
    const attempts = [];
    const written = [];
    const io = {
      write: (file) => written.push(file),
      rename: (from, to) => written.push(to),
      remove: (file) => {
        attempts.push(file);
        throw new Error('cannot remove');
      },
    };
    const { writer, ctx } = writerWith(undefined, io);
    ctx.run.samples = [{ t: 1 }];
    assert.equal(typeof writer.final('failed').dir, 'string');
    assert.equal(attempts.length, 6, 'all three, before and after');
    assert.equal(written.length, 8, 'the last write was made');
  });

  test('a mid-run write held just before its rename never lands over the last write, and leaves nothing', async () => {
    let reached;
    const atRename = new Promise((resolve) => {
      reached = resolve;
    });
    let release;
    const gate = new Promise((resolve) => {
      release = resolve;
    });
    const held = { ...realAsyncFs, rename: async (...args) => {
      reached();
      await gate;
      return realAsyncFs.rename(...args);
    } };
    const { writer, ctx } = writerWith(held);
    const runsDir = join(tmp, `writer-${counter}`);
    ctx.run.samples = [{ t: 1 }];
    const out = writer.keep();
    await atRename;
    ctx.run.samples = [{ t: 1 }, { t: 2 }];
    writer.final('failed');
    release();
    await out;
    const dir = join(runsDir, readdirSync(runsDir)[0]);
    assert.equal(JSON.parse(readFileSync(join(dir, 'samples.json'), 'utf8')).length, 2, 'the last write stands');
    assert.equal(JSON.parse(readFileSync(join(dir, 'held.json'), 'utf8')).run.ended, 'failed');
    assert.deepEqual(readdirSync(dir).sort(), ['health.json', 'held.json', 'samples.json', 'summary.txt']);
  });

  test('while a slow write is out, a request the page paused is still answered by the guard', async () => {
    const order = [];
    let w;
    const slow = {
      ...realAsyncFs,
      open: async (...args) => {
        const handle = await realAsyncFs.open(...args);
        return {
          writeFile: async (text) => {
            // A request the page makes while the write is out (a macrotask later, as Chrome's event would arrive).
            setImmediate(() => w.listeners.get('Fetch.requestPaused')({
              requestId: 'segment', request: { method: 'GET', url: `${SITE}/api/editions/1/segment.ts` },
            }));
            await new Promise((resolve) => setTimeout(resolve, 40));
            order.push('write settles');
            return handle.writeFile(text);
          },
          sync: () => handle.sync(),
          close: () => handle.close(),
        };
      },
    };
    w = world({ samples: [{ ...playing(1), ended: true }], deps: { afs: slow } });
    const send = w.page.send;
    w.page.send = async (method, params) => {
      if (method === 'Fetch.continueRequest') order.push('request continued');
      return send(method, params);
    };
    await w.run();
    assert.deepEqual(order.slice(0, 2), ['request continued', 'write settles']);
  });
});
