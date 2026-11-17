// scripts/replay/viewing.mjs, the first part: what the viewer reads from the player's page, what it presses in it,
// what it checks before it plays, the standstill warning and the words of each end. With a made-up page and a
// made-up document, and no Chrome, no site and no clock.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { describe, test } from 'node:test';
import { runInNewContext } from 'node:vm';
import { PAGE_TEXT, createCancel } from '../../replay/viewer.mjs';
import {
  ENDS, LOAD_MS, PLACE_BUTTON, PLACE_CARD, PRESS_RETRY, SITE_RETRY_MS, SITE_WAIT_MS, VIEWER_SAMPLE, cleanFilm,
  cleanViewerSample, endOf, exitCode, filmExpression, lengthOf, loadWaiter, makeAttempt, pressPlace, progressLine,
  standStill, timeOfDay, waitUntilReady,
} from '../../replay/viewing.mjs';

const SITE = 'http://127.0.0.1:5301';
const json = (value) => JSON.parse(JSON.stringify(value));
// 17 November 2026, 14:05:09 where the test runs: the time of day is local time.
const BEGAN = new Date(2026, 10, 17, 14, 5, 9).getTime();
const FILM = { status: 200, title: 'Heat', runtimeMs: 10200000, quality: 'auto' };

describe('how a run ends', () => {
  test('the codes: 0 for the film, --minutes and a signal; 130 for a signal before the film opened; 1 else', () => {
    assert.deepEqual(Object.keys(ENDS).map((word) => exitCode(word, true)), [0, 0, 0, 1, 1, 1, 1, 1, 1]);
    assert.equal(exitCode('signal', false), 130);
    assert.equal(exitCode('nonsense', true), 1);
  });

  test('a sample ends a run by its flags only: signed out, stopped, or the film ended', () => {
    assert.equal(endOf({ signedOut: true, stopped: true, ended: true }), 'signed-out');
    assert.equal(endOf({ stopped: true, ended: true }), 'stopped-from-admin');
    assert.equal(endOf({ ended: true }), 'film-ended');
    assert.equal(endOf({ retry: true, direct: true, paused: true }), null);
  });
});

describe('the film\'s facts', () => {
  test('the film\'s facts are rebuilt field by field: four fields, texts printable and cut, numbers finite', () => {
    const kept = cleanFilm({ status: 200, title: 'Heat', runtimeMs: 100, quality: 'auto', plot: 'x', files: [1] });
    assert.deepEqual(kept, { status: 200, title: 'Heat', runtimeMs: 100, quality: 'auto' });
    const odd = cleanFilm({ status: '200', title: `a\\nb${'x'.repeat(200)}`, runtimeMs: -1, quality: 'q'.repeat(50) });
    assert.deepEqual([odd.status, odd.title.length, odd.runtimeMs, odd.quality.length], [0, 120, null, 20]);
    for (const raw of [null, undefined, 5, 'text', [], { status: 1.5, runtimeMs: Number.NaN, title: 7, quality: {} }]) {
      assert.deepEqual(cleanFilm(raw), { status: 0, title: null, runtimeMs: null, quality: null }, JSON.stringify(raw));
    }
    assert.equal(cleanFilm({ title: 'a\nb', status: 404 }).title, 'a b');
  });

  test('the page hands back only status, title, runtime and quality; the runtime is the played one\'s', async () => {
    const film = {
      id: 42, title: 'Heat', plot: 'secret plot', editions: [{ id: 5, runtimeMs: 100, file: { path: '/media/x' } },
        { id: 6, runtimeMs: 200 }], preferences: { editionId: 6, quality: 'auto', audioStreamId: 9 },
    };
    const calls = [];
    const fetch = async (...args) => {
      calls.push(args);
      return { status: 200, json: async () => film };
    };
    const run = async (changes, answer) => json(await runInNewContext(filmExpression(42), {
      fetch: answer ?? (async (...args) => ({ status: 200, json: async () => ({ ...film, ...changes }), args })),
    }));
    assert.deepEqual(json(await runInNewContext(filmExpression(42), { fetch })), {
      status: 200, title: 'Heat', runtimeMs: 200, quality: 'auto',
    });
    assert.deepEqual(calls, [['/api/films/42']]);
    assert.equal((await run({ preferences: { quality: 'auto' } })).runtimeMs, 100);
    assert.equal((await run({ preferences: null, editions: [] })).runtimeMs, undefined);
    assert.deepEqual(await run({}, async () => ({ status: 404, json: async () => film })), { status: 404 });
    assert.deepEqual(await run({}, async () => {
      throw new Error('offline');
    }), { status: 0 });
  });
});

describe('waitUntilReady', () => {
  // `outcomes` are what the attempts give, in order (then no answer for ever); each attempt takes `took` ms.
  const loop = (outcomes, took = 0) => {
    let now = 0;
    const log = [];
    const queue = [...outcomes];
    return {
      log,
      cancel: createCancel(),
      now: () => now,
      say: (text) => log.push(`say ${text}`),
      wait: async (ms) => {
        log.push(`wait ${ms}`);
        now += ms;
      },
      attempt: async () => {
        log.push('attempt');
        now += took;
        return queue.shift() ?? { kind: 'no-answer' };
      },
    };
  };
  const ready = { kind: 'ready', film: FILM };

  test('the pause is 15 seconds and the patience ten minutes, and a page gets 30 seconds to load', () => {
    assert.deepEqual([SITE_RETRY_MS, SITE_WAIT_MS, LOAD_MS], [15000, 600000, 30000]);
  });

  test('a real answer is taken at once: ready, or a refusal', async () => {
    for (const outcome of [ready, { kind: 'refused', line: 'no' }]) {
      const c = loop([outcome]);
      assert.deepEqual(await waitUntilReady(c), outcome);
      assert.deepEqual(c.log, ['attempt']);
    }
  });

  test('no answer is said once and the whole attempt is made again after 15 seconds, until there is one', async () => {
    const c = loop([{ kind: 'no-answer' }, { kind: 'no-answer' }, { kind: 'no-answer' }, ready]);
    assert.deepEqual(await waitUntilReady(c), ready);
    assert.equal(c.log.filter((entry) => entry.startsWith('say')).length, 1);
    assert.deepEqual(c.log.filter((entry) => entry.startsWith('wait')), new Array(3).fill(`wait ${SITE_RETRY_MS}`));
    assert.match(c.log[1], /waiting for it/);
  });

  test('it gives up ten minutes after the first no answer, not before', async () => {
    const c = loop([], 20000);
    const first = { at: null };
    const attempt = c.attempt;
    c.attempt = async () => {
      const outcome = await attempt();
      first.at ??= c.now();
      return outcome;
    };
    assert.deepEqual(await waitUntilReady(c), { kind: 'gave-up' });
    const waited = c.now() - first.at;
    assert.ok(waited >= SITE_WAIT_MS, 'not before ten minutes');
    assert.ok(waited < SITE_WAIT_MS + SITE_RETRY_MS + 20000, 'and at the first look after them');
    assert.equal(c.log.filter((entry) => entry.startsWith('say')).length, 1);
  });

  test('a round that lands exactly on ten minutes gives up there, with the 41st look and not the 42nd', async () => {
    const c = loop([]);
    assert.deepEqual(await waitUntilReady(c), { kind: 'gave-up' });
    assert.equal(c.now(), 600000);
    assert.equal(c.log.filter((entry) => entry === 'attempt').length, 41);
  });

  test('a signal while an attempt is pending ends the wait at once, and the attempt is let go quietly', async () => {
    const unhandled = [];
    const watch = (reason) => unhandled.push(reason);
    process.on('unhandledRejection', watch);
    try {
      const c = loop([]);
      let fail;
      c.attempt = () => new Promise((resolve, reject) => {
        fail = reject;
      });
      const waiting = waitUntilReady(c);
      await new Promise((resolve) => setImmediate(resolve));
      c.cancel.trigger();
      assert.deepEqual(await waiting, { kind: 'cancelled' });
      fail(new Error('Chrome went away'));
      await new Promise((resolve) => setImmediate(resolve));
      assert.deepEqual(unhandled, []);
      assert.equal(c.log.some((entry) => entry.startsWith('wait')), false);
    } finally {
      process.off('unhandledRejection', watch);
    }
  });

  test('a signal ends it: before an attempt, during one, or in a wait', async () => {
    const before = loop([ready]);
    before.cancel.trigger();
    assert.deepEqual(await waitUntilReady(before), { kind: 'cancelled' });
    assert.deepEqual(before.log, []);
    const during = loop([]);
    during.attempt = async () => {
      during.cancel.trigger();
      return ready;
    };
    assert.deepEqual(await waitUntilReady(during), { kind: 'cancelled' });
    const inWait = loop([]);
    const wait = inWait.wait;
    inWait.wait = async (ms) => {
      await wait(ms);
      inWait.cancel.trigger();
    };
    assert.deepEqual(await waitUntilReady(inWait), { kind: 'cancelled' });
    assert.equal(inWait.log.filter((entry) => entry === 'attempt').length, 1);
  });

  test('an attempt that throws is not retried: the throw goes on', async () => {
    const c = loop([]);
    c.attempt = async () => {
      throw new Error('Chrome went away');
    };
    await assert.rejects(waitUntilReady(c), /Chrome went away/);
  });
});

describe('makeAttempt: the session check, the film, the player\'s page', () => {
  const PLAYER = `${SITE}/films/42/play?start=0`;
  // A page that answers as `script` says. `script.fail` names the step that goes wrong (the way it goes wrong is a
  // rejection, or an `errorText`), `script.closes` says Chrome has gone when it does.
  function pageWorld(script = {}) {
    const log = [];
    const said = [];
    const waited = [];
    const fails = (step) => script.fail === step;
    const gone = (what) => {
      if (script.closes) page.closed = true;
      throw new Error(what);
    };
    const page = {
      closed: false,
      async send(method, params) {
        log.push(params.url.replace(SITE, ''));
        if (params.url.endsWith('/api/session')) {
          if (fails('navigate-session')) gone('no reply within 30000 ms');
          return fails('error-session') ? { errorText: 'net::ERR_INTERNET_DISCONNECTED' } : {};
        }
        if (fails('open')) gone('no reply within 30000 ms');
        return fails('error-open') ? { errorText: 'net::ERR_TIMED_OUT' } : {};
      },
      async evaluate(expression) {
        if (expression === PAGE_TEXT) {
          log.push('session');
          if (fails('read-session')) gone('no reply');
          return script.session ?? '{"authenticated":true}';
        }
        log.push('film');
        if (script.filmGate) await script.filmGate;
        if (fails('film')) gone('no reply');
        return script.film ?? FILM;
      },
    };
    const cancel = createCancel();
    const attempt = makeAttempt({
      page, site: SITE, film: script.number ?? 42, nextLoad: () => Promise.resolve(), cancel,
      say: (text) => said.push(text),
      giveUpOn: async (promise, ms) => {
        await promise;
        waited.push(ms);
        return script.noLoad !== waited.length;
      },
    });
    return { attempt, log, said, waited, cancel };
  }

  test('the three steps in order, then ready with the film; the film is said before its page opens', async () => {
    const w = pageWorld();
    assert.deepEqual(await w.attempt(), { kind: 'ready', film: FILM });
    assert.deepEqual(w.log, ['/api/session', 'session', 'film', '/films/42/play?start=0']);
    assert.deepEqual(w.waited, [30000, 30000], 'both pages get 30 seconds to load');
    assert.deepEqual(w.said, [`Film 42: Heat, 2 h 50 min, on ${SITE}. Ctrl-C ends the run and writes the results.`]);
  });

  // [what goes wrong, the steps that are reached before it, the step that is not]
  const misses = [
    [{ fail: 'navigate-session' }, ['/api/session'], '/films/'],
    [{ fail: 'error-session' }, ['/api/session'], '/films/'],
    [{ fail: 'read-session' }, ['/api/session', 'session'], 'film'],
    [{ noLoad: 1 }, ['/api/session'], 'film'],
    [{ fail: 'film' }, ['/api/session', 'session', 'film'], '/films/'],
    [{ film: { status: 0 } }, ['/api/session', 'session', 'film'], '/films/'],
    [{ film: { status: 502 } }, ['/api/session', 'session', 'film'], '/films/'],
    [{ film: { status: 503 } }, ['/api/session', 'session', 'film'], '/films/'],
    [{ film: { status: 504 } }, ['/api/session', 'session', 'film'], '/films/'],
    [{ noLoad: 2 }, ['/api/session', 'session', 'film', '/films/42/play?start=0'], null],
    [{ fail: 'open' }, ['/api/session', 'session', 'film', '/films/42/play?start=0'], null],
    [{ fail: 'error-open' }, ['/api/session', 'session', 'film', '/films/42/play?start=0'], null],
  ];
  for (const [script, reached, not] of misses) {
    test(`${JSON.stringify(script)} is no answer, and the next steps are left for the next round`, async () => {
      const w = pageWorld(script);
      assert.deepEqual(await w.attempt(), { kind: 'no-answer' });
      assert.deepEqual(w.log, reached);
      if (not) assert.equal(w.log.some((entry) => entry.includes(not)), false);
    });
  }

  test('with Chrome gone, a step that fails is a failure at once, not no answer', async () => {
    for (const fail of ['navigate-session', 'read-session', 'film', 'open']) {
      const w = pageWorld({ fail, closes: true });
      await assert.rejects(w.attempt(), (error) => error.code === 'chrome-gone' && /went away/.test(error.message));
    }
  });

  // [what the site answers, the line]
  const refusals = [
    [{ session: '{"authenticated":false}' }, /Not signed in. Run --sign-in first/],
    [{ session: '<html>' }, /not understood/],
    [{ film: { status: 404 } }, /There is no film 42/],
    [{ film: { status: 500 } }, /status 500/],
    [{ film: { status: 501 } }, /status 501/],
    [{ film: { status: 505 } }, /status 505/],
    [{ film: { ...FILM, quality: '720p' } }, /quality "720p" by hand.*to Auto/],
  ];
  for (const [script, line] of refusals) {
    test(`a real answer refuses at once: ${line}`, async () => {
      const w = pageWorld(script);
      const outcome = await w.attempt();
      assert.equal(outcome.kind, 'refused');
      assert.match(outcome.line, line);
      assert.equal(w.log.some((entry) => entry.includes('/films/')), false, 'the player\'s page is not opened');
    });
  }

  test('a quality of auto, or none, is let through', async () => {
    for (const quality of ['auto', null, undefined]) {
      assert.equal((await pageWorld({ film: { ...FILM, quality } }).attempt()).kind, 'ready', String(quality));
    }
  });

  test('an attempt a signal has given up on, released afterwards, says nothing and opens no page', async () => {
    let release;
    const w = pageWorld({ filmGate: new Promise((resolve) => {
      release = resolve;
    }) });
    const running = w.attempt();
    await new Promise((resolve) => setImmediate(resolve));
    w.cancel.trigger();
    release();
    assert.deepEqual(await running, { kind: 'no-answer' });
    assert.equal(w.log.some((entry) => entry.includes('/films/')), false, 'no navigation to the player\'s page');
    assert.deepEqual(w.said, []);
  });

  test('a signal that arrives as the film is said stops the attempt before the player\'s page is opened', async () => {
    const w = pageWorld();
    w.said.push = (text) => {
      w.cancel.trigger();
      return Array.prototype.push.call(w.said, text);
    };
    assert.deepEqual(await w.attempt(), { kind: 'no-answer' });
    assert.equal(w.log.some((entry) => entry.includes('/films/')), false);
    assert.equal(w.said.length, 1);
  });

  test('a signal that is already there stops an attempt before it sends anything', async () => {
    const w = pageWorld();
    w.cancel.trigger();
    assert.deepEqual(await w.attempt(), { kind: 'no-answer' });
    assert.deepEqual(w.log, []);
  });

  test('a missing cancel is a mistake that throws at once, not ten silent minutes of waiting', () => {
    assert.throws(() => makeAttempt({
      page: {}, site: SITE, film: 42, nextLoad: () => Promise.resolve(), giveUpOn: async () => true,
      say: () => undefined,
    }), /needs the run's cancel/);
  });

  test('a film that is not a number is a mistake that goes on, not a dead link to wait for', async () => {
    for (const number of ['abc', 0, '4 2', undefined]) {
      const w = pageWorld({ number: number ?? 'x' });
      await assert.rejects(w.attempt(), /1 to 9 digits/, String(number));
      assert.deepEqual(w.log, [], 'nothing was sent');
    }
  });

  test('the film is said once, however often the attempt is made again', async () => {
    const w = pageWorld({ fail: 'open' });
    await w.attempt();
    await w.attempt();
    assert.equal(w.said.length, 1);
  });
});

describe('the laptop standing still', () => {
  test('a wait of 25 seconds or less is nothing; a longer one is a warning in the viewer\'s own words', () => {
    assert.equal(standStill(25000, 100), null);
    assert.equal(standStill(5000, 100), null);
    const warning = standStill(65000, 120);
    assert.equal(warning, 'the laptop stood still for 65 s at second 120 of the run, '
      + 'and nothing was watched in that time');
    assert.equal(/replay|trusted/.test(warning), false);
    assert.notEqual(standStill(25001, 1), null);
  });
});

describe('lengthOf', () => {
  test('is hours and minutes, or minutes', () => {
    assert.equal(lengthOf(10200000), '2 h 50 min');
    assert.equal(lengthOf(3600000), '1 h 0 min');
    assert.equal(lengthOf(2520000), '42 min');
  });

  test('a length that is 0 or not there is not known, never "0 min"', () => {
    for (const ms of [0, -5, null, undefined, Number.NaN, 'x']) assert.equal(lengthOf(ms), 'length not known');
  });

  test('under a minute is said so, and a minute is a minute', () => {
    assert.equal(lengthOf(1), 'under a minute');
    assert.equal(lengthOf(29999), 'under a minute');
    assert.equal(lengthOf(30000), '1 min');
    assert.equal(lengthOf(60000), '1 min');
  });
});

describe('the words of each end, the place buttons and the film expression', () => {
  test('the words the owner reads at the end of a run are these', () => {
    assert.deepEqual(Object.fromEntries(Object.entries(ENDS).map(([word, end]) => [word, end.line])), {
      'film-ended': 'The film ended.',
      minutes: '--minutes was reached.',
      signal: 'Stopped by a signal.',
      'signed-out': 'The player shows "Signed out". A release or restart of the server signs every browser out: '
        + 'run --sign-in again.',
      'stopped-from-admin': 'Playback was stopped from Admin.',
      'chrome-gone': 'Chrome went away.',
      'page-crashed': 'The player\'s page stopped answering.',
      refused: 'The run did not start.',
      failed: 'The run failed.',
    });
  });

  test('the place card is answered with On a train, Hotel or away, or Not now', () => {
    assert.deepEqual(PLACE_BUTTON, { train: 'On a train', away: 'Hotel or away', none: 'Not now' });
    assert.equal(PLACE_CARD.test('Where are you watching?'), true);
    assert.equal(PLACE_CARD.test('Slow connection'), false);
  });

  test('the film expression is made only for a number of 1 to 9 digits, from 1', () => {
    for (const film of [42, '42', 1, 999999999, '7']) {
      assert.match(filmExpression(film), /fetch\('\/api\/films\/\d+'\)/);
    }
    for (const film of [0, '0', -1, 1.5, 1e9, 1234567890, '', undefined, null, '1;alert(1)', '4 2', '042', {}, [1]]) {
      assert.throws(() => filmExpression(film), /1 to 9 digits/, String(film));
    }
  });
});

describe('loadWaiter', () => {
  const pageWith = () => {
    const listeners = [];
    const page = { on: (method, fn) => listeners.push([method, fn]) };
    return { page, fire: () => listeners.forEach(([, fn]) => fn()), listeners };
  };
  const flush = () => new Promise((resolve) => setImmediate(resolve));

  test('one load settles every waiter there is, once; a waiter made afterwards waits for the next load', async () => {
    const { page, fire, listeners } = pageWith();
    const next = loadWaiter(page);
    assert.deepEqual(listeners.map(([method]) => method), ['Page.loadEventFired']);
    let settled = 0;
    const one = next().then(() => (settled += 1));
    const two = next().then(() => (settled += 1));
    assert.equal(next.pending(), 2);
    fire();
    await Promise.all([one, two]);
    assert.equal(settled, 2);
    assert.equal(next.pending(), 0, 'a load leaves no waiter behind');
    const later = next().then(() => (settled += 10));
    await flush();
    assert.equal(settled, 2, 'the old load does not settle a later waiter');
    fire();
    await later;
    assert.equal(settled, 12);
    fire();
    await flush();
    assert.equal(settled, 12, 'a waiter settles once');
  });

  test('only the last 16 waiters are kept: an attempt that gave up on its load leaves nothing to grow', async () => {
    const { page, fire } = pageWith();
    const next = loadWaiter(page);
    const settled = new Array(20).fill(false);
    settled.forEach((_, n) => next().then(() => {
      settled[n] = true;
    }));
    assert.equal(next.pending(), 16);
    fire();
    await flush();
    assert.deepEqual(settled.map(Boolean), [...new Array(4).fill(false), ...new Array(16).fill(true)]);
  });
});

describe('the words and classes the viewer looks for are the app\'s own', () => {
  const FILES = {
    overlays: 'components/PlayerOverlays.jsx', signIn: 'components/SignInCard.jsx',
    states: 'components/StateMessages.jsx', player: 'screens/Player.jsx', place: 'player/environment.js',
  };
  const read = (path) => readFileSync(new URL(`../../../frontend/src/${path}`, import.meta.url), 'utf8');
  const real = Object.fromEntries(Object.entries(FILES).map(([name, path]) => [name, read(path)]));
  const expressions = `${VIEWER_SAMPLE}\n${PRESS_RETRY}\n${pressPlace('x')}`;
  const [train, away, none] = [PLACE_BUTTON.train, PLACE_BUTTON.away, PLACE_BUTTON.none];
  // Each word with the element the viewer finds it in, as the text of the source with its white space made one space.
  const PAIRS = {
    'the unavailable card, its title and its Try again': ['overlays', '<div className="slow-card" role="alert"> '
      + '<h2 className="slow-card__title">This film can’t be played right now</h2>'],
    'the unavailable card\'s Try again button, inside its actions': ['overlays', '<div className="slow-card__actions"> '
      + '<Button variant="primary" ref={retry} onClick={onRetry}> Try again </Button>'],
    'the Signed out title': ['signIn', '<h2 id="signin-title" className="signin__title"> Signed out </h2>'],
    'the stopped text, at the start of its paragraph': ['overlays',
      '<p className="slow-card__text" id={messageId}> Playback was stopped from Admin.'],
    'the load-error wrapper': ['player', '<div className="player player--loading"> <ErrorState'],
    'the load-error state\'s Try again, inside state__actions': ['states',
      '<div className="state__actions"> <Button onClick={onRetry}>Try again</Button>'],
    'the three place buttons, inside the card\'s actions': ['overlays', '<div className="slow-card__actions"> '
      + `<Button onClick={onTrain}>${train}</Button> <Button onClick={onAway}>${away}</Button> `
      + `<Button onClick={onNotNow}>${none}</Button>`],
    'the place card\'s title renders the question': ['overlays',
      '<h2 id="environment-card-title" className="slow-card__title"> {QUESTION} </h2>'],
    'the question': ['place', 'export const QUESTION = \'Where are you watching?\';'],
  };
  // What is missing from these sources (names of PAIRS): none, when the app still says what the viewer looks for.
  const missing = (sources) => Object.entries(PAIRS).filter(([, [file, text]]) => {
    const normal = sources[file].replaceAll('&rsquo;', '’').replaceAll(/\s+/g, ' ');
    return !normal.includes(text);
  }).map(([name]) => name);

  test('each word is in the element the viewer finds it in', () => {
    assert.deepEqual(missing(real), []);
  });

  test('and the viewer\'s own expressions look for those words and classes', () => {
    assert.match(expressions, /\^Signed out\$/);
    assert.match(expressions, /\^Playback was stopped from Admin/);
    assert.match(expressions, /'Try again'/);
    const cant = new RegExp(/\/(can\.t be played right now)\/i/.exec(expressions)[1], 'i');
    assert.equal(cant.test('This film can’t be played right now'), true);
    assert.equal(PLACE_CARD.test('Where are you watching?'), true);
    for (const label of [train, away, none]) assert.ok(pressPlace(label).includes(JSON.stringify(label)));
    for (const name of ['slow-card', 'slow-card__title', 'slow-card__text', 'slow-card__actions', 'signin__title',
      'state__actions']) {
      assert.ok(expressions.includes(`.${name}`), `the expression looks for .${name}`);
    }
    assert.ok(expressions.includes('.player .state__actions'));
  });

  // The ways the app could change under the viewer: each must be noticed.
  const CHANGES = [
    ['the unavailable card\'s button is renamed Retry', 'overlays',
      (text) => text.replace(/(ref=\{retry\} onClick=\{onRetry\}>\s*)Try again/, '$1Retry')],
    ['the Signed out title gets a full stop', 'signIn',
      (text) => text.replace(/(signin__title">\s*)Signed out/, '$1Signed out.')],
    ['the stopped text is no longer at the start of its paragraph', 'overlays',
      (text) => text.replace(/(id=\{messageId\}>\s*)Playback/, '$1Note: Playback')],
    ['the stopped paragraph leaves slow-card__text', 'overlays',
      (text) => text.replace('className="slow-card__text" id={messageId}', 'className="slow-note" id={messageId}')],
    ['the place card\'s title renders something else', 'overlays',
      (text) => text.replace(/(environment-card-title" className="slow-card__title">\s*)\{QUESTION\}/, '$1Pick one')],
    ['the unavailable card\'s slow-card class is renamed', 'overlays',
      (text) => text.replace('<div className="slow-card" role="alert">', '<div className="slow-box" role="alert">')],
    ['the load-error wrapper\'s class is renamed', 'player',
      (text) => text.replace(/(<div className=")player player--loading(">\s*<ErrorState)/, '$1player player--wait$2')],
  ];
  for (const [what, file, change] of CHANGES) {
    test(`if ${what}, this test notices`, () => {
      const changed = change(real[file]);
      assert.notEqual(changed, real[file], 'the change was made');
      assert.equal(missing({ ...real, [file]: changed }).length >= 1, true);
    });
  }
});

// A made-up document for the expressions the run evaluates in the page: elements by selector; `clicked` says what was
// pressed. A card's children are looked up by selector too.
function dom({ video = null, elements = {}, body = '' } = {}) {
  const clicked = [];
  const document = {
    querySelector: (selector) => (selector === 'video' ? video : elements[selector]?.[0] ?? null),
    querySelectorAll: (selector) => elements[selector] ?? [],
    body: { innerText: body },
  };
  return { document, clicked };
}
const element = (text, clicked, children = {}) => ({
  textContent: text,
  click: () => clicked.push(text),
  querySelector: (selector) => children[selector]?.[0] ?? null,
  querySelectorAll: (selector) => children[selector] ?? [],
});
const card = (title, buttons, clicked) => element('', clicked, {
  '.slow-card__title': [element(title, clicked)],
  '.slow-card__actions button': buttons.map((label) => element(label, clicked)),
});
const CANT = 'This film can\u2019t be played right now';
const evaluate = (expression, { document }) => json(runInNewContext(expression, { document }));

describe('what is read from the page and what is pressed in it', () => {
  const video = (currentSrc) => ({
    buffered: { length: 0 }, currentTime: 3, paused: false, ended: false, readyState: 4, videoHeight: 1080,
    webkitAudioDecodedByteCount: 5, currentSrc,
  });

  test('direct play: the video\'s source is there and does not begin blob:', () => {
    const direct = (src) => evaluate(VIEWER_SAMPLE, dom({ video: video(src) })).direct;
    assert.equal(direct('blob:http://x/1'), false);
    assert.equal(direct(''), false);
    assert.equal(direct('http://127.0.0.1:5301/api/editions/3/stream'), true);
    assert.equal(evaluate(VIEWER_SAMPLE, dom()).direct, false);
    assert.equal(evaluate(VIEWER_SAMPLE, dom({ video: video('blob:x') })).t, 3);
  });

  test('the Signed out card and the stopped card are found by their words, and only by them', () => {
    const flag = (elements, name) => evaluate(VIEWER_SAMPLE, dom({ elements, body: 'x' }))[name];
    const c = [];
    assert.equal(flag({ '.signin__title': [element('Signed out', c)] }, 'signedOut'), true);
    assert.equal(flag({ '.signin__title': [element('Sign in', c)] }, 'signedOut'), false);
    assert.equal(flag({ '.slow-card__text': [element('Playback was stopped from Admin.', c)] }, 'stopped'), true);
    assert.equal(flag({ '.slow-card__text': [element('The server could not prepare it.', c)] }, 'stopped'), false);
    assert.equal(flag({}, 'stopped') || flag({}, 'signedOut') || flag({}, 'retry'), false);
  });

  test('a "Try again" is seen on the card "can\'t be played right now" and on the film-not-loaded state only', () => {
    const c = [];
    const seen = (elements) => evaluate(VIEWER_SAMPLE, dom({ elements, body: 'x' })).retry;
    const unavailable = card(CANT, ['Try again'], c);
    assert.equal(seen({ '.slow-card': [unavailable] }), true);
    assert.equal(seen({ '.slow-card': [card('Slow connection', ['Try again'], c)] }), false);
    assert.equal(seen({ '.slow-card': [card(CANT, ['Play again'], c)] }), false);
    assert.equal(seen({ '.player .state__actions button': [element('Try again', c)] }), true);
    assert.equal(seen({ '.player .state__actions button': [element('Something else', c)] }), false);
  });

  test('the place card: only the button of the answer is pressed, and nothing when it is not there', () => {
    const buttons = ['On a train', 'Hotel or away', 'Not now', 'Play again', 'Try again'];
    for (const label of Object.values(PLACE_BUTTON)) {
      const clicked = [];
      const env = dom({ elements: { '.slow-card__actions button': buttons.map((b) => element(b, clicked)) } });
      assert.equal(evaluate(pressPlace(label), env), true);
      assert.deepEqual(clicked, [label]);
    }
    const clicked = [];
    const none = dom({ elements: { '.slow-card__actions button': [element('Play again', clicked)] } });
    assert.equal(evaluate(pressPlace('Not now'), none), false);
    assert.deepEqual(clicked, []);
  });

  test('a "Try again" is pressed only on the two cards, and "Play again" and other buttons never', () => {
    const cases = [
      [(c) => ({ '.slow-card': [card(CANT, ['Play again', 'Try again'], c)] }), true],
      [(c) => ({ '.player .state__actions button': [element('Try again', c)] }), true],
      [(c) => ({ '.slow-card': [card('Slow connection', ['Try again', 'Play again'], c)] }), false],
      [(c) => ({ '.slow-card': [card(CANT, ['Play again'], c)] }), false],
      [(c) => ({ '.slow-card__actions button': [element('Try again', c)] }), false],
    ];
    for (const [make, pressed] of cases) {
      const clicked = [];
      assert.equal(evaluate(PRESS_RETRY, dom({ elements: make(clicked) })), pressed);
      assert.deepEqual(clicked, pressed ? ['Try again'] : []);
    }
  });

  test('a sample is rebuilt field by field, with the viewer\'s four flags true or false and the two clocks', () => {
    const clean = cleanViewerSample({
      video: true, t: 12.5, direct: true, signedOut: 'yes', stopped: 1, retry: null, pill: `a\nb`, secret: 'x',
      ahead: Infinity,
    }, { sinceMs: 5000.4, at: BEGAN });
    assert.deepEqual(clean, {
      sinceMs: 5000, at: BEGAN, video: true, t: 12.5, ahead: null, pill: 'a b',
      direct: true, signedOut: false, stopped: false, retry: false,
    });
    assert.deepEqual(cleanViewerSample(null, { sinceMs: Number.NaN, at: 'now' }), {
      sinceMs: null, at: null, direct: false, signedOut: false, stopped: false, retry: false,
    });
  });
});

describe('the line every 30 seconds', () => {
  test('the line is the time of day, the seconds in, the playhead, the film in hand and the screen', () => {
    const at = new Date(2026, 10, 17, 9, 5, 3).getTime();
    assert.equal(timeOfDay(at), '09:05:03');
    const sample = { sinceMs: 35400, at, t: 30.5, ahead: 12, pill: 'Playing', buffering: null };
    assert.equal(progressLine(sample), '  09:05:03 +35s playhead 30.5 in hand 12s | Playing');
    assert.match(progressLine({ ...sample, direct: true, reconnecting: true, buffering: 'Buffering' }),
      /Playing \| Buffering \| Reconnecting \| DIRECT PLAY$/);
    assert.match(progressLine({ sinceMs: null, at }), /\+-s playhead - in hand -s \| $/);
  });
});
