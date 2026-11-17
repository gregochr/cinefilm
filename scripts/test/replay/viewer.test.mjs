// scripts/replay/viewer.mjs: the sign-in, without Chrome, a window, a keyboard, the network or the real home folder.
// Everything the sign-in touches is made up here; folders are made under a temporary folder that is removed after.
// `main`, which starts the real Chrome, is checked by hand.
import assert from 'node:assert/strict';
import {
  chmodSync, existsSync, linkSync, mkdirSync, mkdtempSync, readFileSync, rmSync, statSync, symlinkSync, writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { PassThrough } from 'node:stream';
import { after, describe, test } from 'node:test';
import {
  DEFAULT_SITE, PAGE_TEXT, SETTLE_MS, checkProfileFree, checkSession, crashHandler, createCancel, makeFolders,
  options, readAnswer, resultOf, signIn, siteOrigin, takeLock, viewerPaths, waitForReturn, watchSignals,
} from '../../replay/viewer.mjs';

const tmp = mkdtempSync(join(tmpdir(), 'viewer-'));
after(() => rmSync(tmp, { recursive: true, force: true }));
let counter = 0;
// A fresh folder to stand for ~/cinefilm-viewer (it does not exist yet).
const freshHome = () => join(tmp, `home-${counter += 1}`, 'cinefilm-viewer');
const modeOf = (path) => statSync(path).mode & 0o777;
const flush = () => new Promise((resolve) => setImmediate(resolve));
const soon = (promise) => Promise.race([
  promise,
  new Promise((_, reject) => setImmediate(reject, new Error('did not settle'))),
]);

describe('options', () => {
  test('with nothing given: no sign-in and the real site', () => {
    assert.deepEqual(options([]), { signIn: false, site: DEFAULT_SITE });
  });

  test('--sign-in and --site are read', () => {
    assert.deepEqual(options(['--sign-in', '--site', 'http://127.0.0.1:5301']), {
      signIn: true, site: 'http://127.0.0.1:5301',
    });
  });

  test('an unknown option, a repeat and a missing value are refused', () => {
    assert.throws(() => options(['--film', '3']), /unknown option --film/);
    assert.throws(() => options(['site']), /unknown option site/);
    assert.throws(() => options(['--sign-in', '--sign-in']), /--sign-in was given twice/);
    assert.throws(() => options(['--site', 'https://a.example', '--site', 'https://b.example']), /given twice/);
    assert.throws(() => options(['--site']), /--site needs a value/);
    assert.throws(() => options(['--site', '--sign-in']), /--site needs a value/);
  });

  test('an unknown option is printed as printable text, cut short', () => {
    assert.throws(() => options([`--${'x'.repeat(100)}\n`]), (error) => !error.message.includes('\n')
      && error.message.length < 80);
  });
});

describe('siteOrigin', () => {
  test('https with a host, with or without a port and a lone trailing slash, gives the origin', () => {
    assert.equal(siteOrigin('https://cinefilm.example'), 'https://cinefilm.example');
    assert.equal(siteOrigin('https://cinefilm.example/'), 'https://cinefilm.example');
    assert.equal(siteOrigin('https://cinefilm.example:8443'), 'https://cinefilm.example:8443');
    assert.equal(siteOrigin('HTTPS://CineFilm.Example'), 'https://cinefilm.example');
  });

  test('http is accepted for 127.0.0.1 and localhost only, with or without a port', () => {
    assert.equal(siteOrigin('http://127.0.0.1'), 'http://127.0.0.1');
    assert.equal(siteOrigin('http://127.0.0.1:5301/'), 'http://127.0.0.1:5301');
    assert.equal(siteOrigin('http://localhost:5301'), 'http://localhost:5301');
  });

  test('every other form is refused', () => {
    const refused = [
      'ftp://cinefilm.example', 'cinefilm.example', '', 'https://', 'http://cinefilm.example',
      'http://127.0.0.1.evil.example', 'http://localhost.evil.example', 'http://[::1]:5301',
      'https://user@cinefilm.example', 'https://user:pass@cinefilm.example', 'http://127.0.0.1@evil.example',
      'https://cinefilm.example/films', 'https://cinefilm.example//', 'https://cinefilm.example/?a=1',
      'https://cinefilm.example?a=1', 'https://cinefilm.example/#top', 'https://cinefilm.example#top',
      'https://cinefilm.example\\evil', 'https://cinefilm .example', 'https://cinefilm.example\n',
      'https://cinefilm.example:99999',
    ];
    for (const text of refused) assert.throws(() => siteOrigin(text), /--site must be/, JSON.stringify(text));
  });
});

describe('the folders and the first preferences', () => {
  test('are made for the owner alone: folders 700, preferences 600, with the offer to save passwords off', () => {
    const home = freshHome();
    makeFolders(home);
    const { profile } = viewerPaths(home);
    assert.equal(modeOf(home), 0o700);
    assert.equal(modeOf(profile), 0o700);
    assert.equal(modeOf(join(profile, 'Default')), 0o700);
    const file = join(profile, 'Default', 'Preferences');
    assert.equal(modeOf(file), 0o600);
    assert.deepEqual(JSON.parse(readFileSync(file, 'utf8')), {
      credentials_enable_service: false, profile: { password_manager_enabled: false },
    });
  });

  test('a preferences file that is there is left byte for byte as it was', () => {
    const home = freshHome();
    makeFolders(home);
    const file = join(viewerPaths(home).profile, 'Default', 'Preferences');
    const theirs = Buffer.from('{"mine":1,\n"bytes":"é"}');
    writeFileSync(file, theirs);
    makeFolders(home);
    assert.deepEqual(readFileSync(file), theirs);
  });

  test('a folder that is there with wider permissions is narrowed to 700', () => {
    const home = freshHome();
    mkdirSync(join(home, 'profile', 'Default'), { recursive: true });
    for (const dir of [home, join(home, 'profile'), join(home, 'profile', 'Default')]) chmodSync(dir, 0o755);
    makeFolders(home);
    for (const dir of [home, join(home, 'profile'), join(home, 'profile', 'Default')]) {
      assert.equal(modeOf(dir), 0o700, dir);
    }
  });
});

describe('the lock', () => {
  const never = () => false;
  const always = () => true;

  test('is taken by creating the file with this process\'s id, for the owner alone', () => {
    const home = freshHome();
    takeLock(home, { pid: 111, isAlive: never });
    const { lock } = viewerPaths(home);
    assert.equal(readFileSync(lock, 'utf8'), '111');
    assert.equal(modeOf(lock), 0o600);
  });

  test('is refused while the process it names lives, and the error names that process', () => {
    const home = freshHome();
    takeLock(home, { pid: 111, isAlive: never });
    assert.throws(
      () => takeLock(home, { pid: 222, isAlive: (pid) => pid === 111 }),
      /another viewer is running \(process 111\)/,
    );
    assert.equal(readFileSync(viewerPaths(home).lock, 'utf8'), '111', 'the holder\'s file is as it was');
  });

  test('is refused too when the check says the process is there but not ours (EPERM counts as alive)', () => {
    const eperm = (pid) => {
      try {
        throw Object.assign(new Error('not permitted'), { code: 'EPERM', pid });
      } catch (error) {
        return error.code === 'EPERM';
      }
    };
    const home = freshHome();
    takeLock(home, { pid: 111, isAlive: never });
    assert.throws(() => takeLock(home, { pid: 222, isAlive: eperm }), /another viewer is running/);
  });

  test('is taken over when the process has gone, and when the file holds no process id', () => {
    for (const rubbish of ['111', '', 'hello', '12abc', '-5', '0', '99999999999999']) {
      const home = freshHome();
      mkdirSync(home, { recursive: true });
      const { lock } = viewerPaths(home);
      writeFileSync(lock, rubbish);
      takeLock(home, { pid: 222, isAlive: (pid) => pid === 5 });
      assert.equal(readFileSync(lock, 'utf8'), '222', JSON.stringify(rubbish));
      assert.equal(modeOf(lock), 0o600);
    }
  });

  test('a take-over that reads back another process\'s id is refused as if the lock were held, and leaves it', () => {
    const home = freshHome();
    takeLock(home, { pid: 111, isAlive: never });
    const { lock } = viewerPaths(home);
    // Another start renamed its own file onto the lock between this one's rename and its read.
    const readBack = (path) => (readFileSync(path, 'utf8') === '222' ? 333 : 111);
    assert.throws(
      () => takeLock(home, { pid: 222, isAlive: never, readHolder: readBack }),
      /another viewer has just taken the lock \(process 333\)/,
    );
    assert.equal(existsSync(lock), true);
  });

  test('a take-over leaves no temporary file behind', () => {
    const home = freshHome();
    takeLock(home, { pid: 111, isAlive: never });
    takeLock(home, { pid: 222, isAlive: never });
    assert.equal(existsSync(`${viewerPaths(home).lock}.222.tmp`), false);
  });

  test('is let go only by its holder: a release finds another process\'s id and leaves the file', () => {
    const home = freshHome();
    const release = takeLock(home, { pid: 111, isAlive: never });
    const { lock } = viewerPaths(home);
    writeFileSync(lock, '222');
    release();
    assert.equal(readFileSync(lock, 'utf8'), '222');
    writeFileSync(lock, '111');
    release();
    assert.equal(existsSync(lock), false);
    release();
  });

  test('a lock that was never there is not an error to release; always-alive is not consulted for a fresh take', () => {
    const home = freshHome();
    const release = takeLock(home, { pid: 111, isAlive: always });
    rmSync(viewerPaths(home).lock);
    assert.doesNotThrow(release);
  });

  test('a fresh take is whole in one step: the pid is already in the file that is linked onto the lock', () => {
    const home = freshHome();
    const { lock } = viewerPaths(home);
    const seen = [];
    takeLock(home, {
      pid: 111, isAlive: never,
      linkFile: (from, to) => {
        seen.push([readFileSync(from, 'utf8'), existsSync(to)]);
        linkSync(from, to);
      },
    });
    assert.deepEqual(seen, [['111', false]], 'linked once, with the pid in it, onto a lock that was not there');
    assert.equal(readFileSync(lock, 'utf8'), '111');
    assert.equal(existsSync(`${lock}.111.tmp`), false);
  });

  test('a lock that appears just before the link is made is refused, naming its holder, and left alone', () => {
    const home = freshHome();
    const { lock } = viewerPaths(home);
    const appearing = (from, to) => {
      writeFileSync(to, '111'); // another start takes the lock in the moment between the write and the link
      linkSync(from, to);
    };
    assert.throws(
      () => takeLock(home, { pid: 222, isAlive: (pid) => pid === 111, linkFile: appearing }),
      /another viewer is running \(process 111\)/,
    );
    assert.equal(readFileSync(lock, 'utf8'), '111');
    assert.equal(existsSync(`${lock}.222.tmp`), false);
  });
});

describe('Chrome\'s own lock in the profile', () => {
  const alive = (pid) => pid === 4321;
  function profileWithLink(target) {
    const dir = join(tmp, `profile-${counter += 1}`);
    mkdirSync(dir);
    if (target !== null) symlinkSync(target, join(dir, 'SingletonLock'));
    return dir;
  }

  test('a link whose target ends -<pid> of a live process refuses, naming it, and says to quit that Chrome', () => {
    const dir = profileWithLink('MacBook-Pro.local-4321');
    assert.throws(
      () => checkProfileFree(dir, { isAlive: alive }),
      new RegExp(`still using the viewer's profile \\(process 4321\\): quit it first, or if none is running, `
        + `remove the link ${join(dir, 'SingletonLock').replaceAll('.', '\\.')}$`),
    );
  });

  test('no link, a target that does not parse, or a process that has gone: go on', () => {
    assert.doesNotThrow(() => checkProfileFree(profileWithLink(null), { isAlive: alive }));
    assert.doesNotThrow(() => checkProfileFree(profileWithLink('somewhere-else'), { isAlive: alive }));
    assert.doesNotThrow(() => checkProfileFree(profileWithLink('host-99999999999'), { isAlive: () => true }));
    assert.doesNotThrow(() => checkProfileFree(profileWithLink('host-0'), { isAlive: () => true }));
    assert.doesNotThrow(() => checkProfileFree(profileWithLink('host-777'), { isAlive: alive }));
  });

  test('the link is read, never followed: a link to a file that does not exist still counts', () => {
    const dir = profileWithLink(join(tmp, 'no', 'such', 'place-4321'));
    assert.throws(() => checkProfileFree(dir, { isAlive: alive }), /process 4321/);
  });

  test('a plain file in the link\'s place is not a link: go on', () => {
    const dir = profileWithLink(null);
    writeFileSync(join(dir, 'SingletonLock'), 'host-4321');
    assert.doesNotThrow(() => checkProfileFree(dir, { isAlive: alive }));
  });
});

describe('readAnswer', () => {
  test('{"signedIn":true} is signed in and {"signedIn":false} is not, with spaces or a newline round them', () => {
    assert.equal(readAnswer('{"signedIn":true}'), 'signed-in');
    assert.equal(readAnswer('{"signedIn":false}'), 'signed-out');
    assert.equal(readAnswer('  \n{ "signedIn" : true }\n '), 'signed-in');
    assert.equal(readAnswer('\n{"signedIn":false}\n'), 'signed-out');
  });

  test('other JSON is not understood', () => {
    for (const text of [
      '{}', '{"signedIn":"true"}', '{"signedIn":1}', '{"signedIn":null}', '{"signedIn":true,"admin":true}',
      '{"signed":true}', '[]', 'null', 'true', '"signedIn"', '[{"signedIn":true}]',
    ]) assert.equal(readAnswer(text), 'unknown', text);
  });

  test('text that is not JSON, and nothing, are not understood', () => {
    for (const text of ['<html>Sign in</html>', '{"signedIn":tru', '', '   ', undefined, null]) {
      assert.equal(readAnswer(text), 'unknown', String(text));
    }
  });
});

describe('resultOf', () => {
  test('each answer gives its line and its exit code', () => {
    const site = 'https://film.example';
    const signedIn = resultOf('signed-in', site);
    assert.equal(signedIn.code, 0);
    assert.match(signedIn.line, /^Signed in\. .*server restarts.*30 days/);
    const out = resultOf('signed-out', site);
    assert.equal(out.code, 1);
    assert.match(out.line, /Not signed in.*--sign-in again.*only once the site shows the films/);
    const none = resultOf('no-answer', site);
    assert.equal(none.code, 1);
    assert.match(none.line, /did not answer at https:\/\/film\.example/);
    const odd = resultOf('unknown', site);
    assert.equal(odd.code, 1);
    assert.match(odd.line, /not understood/);
  });
});

// A fake page and a fake launch for the check: what Chrome was asked for, and what the page says.
function fakeLaunch({ navigation = {}, text = '{"signedIn":true}', fires = true, evaluateFails = false } = {}) {
  const log = { launched: null, sent: [], closed: 0, spawned: null };
  const listeners = new Map();
  const page = {
    on: (method, fn) => listeners.set(method, fn),
    send: async (method, params) => {
      log.sent.push([method, params]);
      if (method === 'Page.navigate' && fires) setImmediate(() => listeners.get('Page.loadEventFired')?.({}));
      return method === 'Page.navigate' ? navigation : {};
    },
    evaluate: async (expression) => {
      log.sent.push(['evaluate', expression]);
      if (evaluateFails) throw new Error('page: boom');
      return text;
    },
  };
  const launch = async (settings) => {
    log.launched = settings;
    settings.onSpawn?.(() => {
      log.spawned = true;
    });
    return { page, close: async () => { log.closed += 1; } };
  };
  return { launch, log };
}

describe('checkSession', () => {
  test('starts the kept profile over the pipe in its own group, reads /api/session, stops Chrome', async () => {
    const { launch, log } = fakeLaunch();
    const answer = await checkSession({ site: 'https://film.example', profile: '/kept', launch });
    assert.equal(answer, 'signed-in');
    assert.equal(log.launched.profile, '/kept');
    assert.equal(log.launched.channel, 'pipe');
    assert.equal(log.launched.ownGroup, true);
    assert.deepEqual(log.sent.find(([method]) => method === 'Page.navigate'), [
      'Page.navigate', { url: 'https://film.example/api/session' },
    ]);
    assert.deepEqual(log.sent.find(([method]) => method === 'evaluate'), ['evaluate', PAGE_TEXT]);
    assert.equal(log.closed, 1);
  });

  test('the page\'s text is the <pre> in the body when there is one, else the body\'s text, else nothing', () => {
    const read = (document) => new Function('document', `return ${PAGE_TEXT}`)(document);
    const pre = (text) => ({ textContent: text });
    const asked = [];
    const withPre = {
      querySelector: (selector) => {
        asked.push(selector);
        return pre('{"signedIn":true}');
      },
      body: { innerText: 'JSON viewer: Pretty print {"signedIn":true}' },
    };
    assert.equal(read(withPre), '{"signedIn":true}');
    assert.deepEqual(asked, ['body > pre']);
    assert.equal(read({ querySelector: () => null, body: { innerText: '{"signedIn":false}' } }), '{"signedIn":false}');
    assert.equal(read({ querySelector: () => null, body: null }), '');
  });

  test('each of the server\'s answers gives its own result', async () => {
    const expected = new Map([
      ['{"signedIn":false}', 'signed-out'], ['<html></html>', 'unknown'], ['', 'unknown'],
    ]);
    for (const [text, answer] of expected) {
      const { launch } = fakeLaunch({ text });
      assert.equal(await checkSession({ site: 'https://a.example', profile: '/p', launch }), answer, text);
    }
  });

  test('a navigation that fails is "the site did not answer", and Chrome is stopped', async () => {
    const { launch, log } = fakeLaunch({ navigation: { errorText: 'net::ERR_NAME_NOT_RESOLVED' } });
    assert.equal(await checkSession({ site: 'https://a.example', profile: '/p', launch }), 'no-answer');
    assert.equal(log.closed, 1);
    assert.equal(log.sent.some(([method]) => method === 'evaluate'), false, 'nothing was read from an error page');
  });

  test('a page that never finishes loading is "the site did not answer" after 30 seconds, not before', async (t) => {
    t.mock.timers.enable({ apis: ['setTimeout'] });
    const { launch, log } = fakeLaunch({ fires: false });
    const checking = checkSession({ site: 'https://a.example', profile: '/p', launch });
    await flush();
    t.mock.timers.tick(29999);
    await flush();
    assert.equal(log.closed, 0, 'still waiting one millisecond short');
    t.mock.timers.tick(1);
    assert.equal(await soon(checking), 'no-answer');
    assert.equal(log.closed, 1);
  });

  test('Chrome is stopped when reading the page throws, and the error goes on', async () => {
    const { launch, log } = fakeLaunch({ evaluateFails: true });
    await assert.rejects(checkSession({ site: 'https://a.example', profile: '/p', launch }), /page: boom/);
    assert.equal(log.closed, 1);
  });

  test('the hard stop of the check\'s Chrome is handed on to whoever asked for it', async () => {
    const { launch, log } = fakeLaunch();
    let kill = null;
    await checkSession({ site: 'https://a.example', profile: '/p', launch, onSpawn: (k) => {
      kill = k;
    } });
    kill();
    assert.equal(log.spawned, true);
  });
});

describe('createCancel and waitForReturn', () => {
  test('a cancel sets the flag, settles the promise and cuts a wait short', async (t) => {
    t.mock.timers.enable({ apis: ['setTimeout'] });
    const cancel = createCancel();
    assert.equal(cancel.cancelled, false);
    let waited = false;
    const waiting = cancel.wait(60000).then(() => {
      waited = true;
    });
    await flush();
    assert.equal(waited, false);
    cancel.trigger();
    await soon(waiting);
    await soon(cancel.promise);
    assert.equal(cancel.cancelled, true);
    await soon(cancel.wait(60000));
  });

  test('a wait that is not cut short ends after its time, and not one millisecond before', async (t) => {
    t.mock.timers.enable({ apis: ['setTimeout'] });
    const cancel = createCancel();
    let waited = false;
    const waiting = cancel.wait(1000).then(() => {
      waited = true;
    });
    t.mock.timers.tick(999);
    await flush();
    assert.equal(waited, false);
    t.mock.timers.tick(1);
    await soon(waiting);
    assert.equal(waited, true);
  });

  test('Return (a line) resolves, and nothing resolves it before', async () => {
    const input = new PassThrough();
    let done = false;
    const returned = waitForReturn(input).then(() => {
      done = true;
    });
    input.write('some typing');
    await flush();
    assert.equal(done, false, 'no line yet');
    input.write('\n');
    await soon(returned);
    assert.equal(done, true);
  });

  test('the end of the input counts as Return', async () => {
    const input = new PassThrough();
    let done = false;
    const returned = waitForReturn(input).then(() => {
      done = true;
    });
    await flush();
    assert.equal(done, false, 'nothing resolves it before the input ends');
    input.end();
    await soon(returned);
    assert.equal(done, true);
  });
});

// The sign-in with everything made up. `events` is the order in which things happened.
function harness({ isAlive = () => false, check, onWait } = {}) {
  const events = [];
  const said = [];
  const cancel = createCancel();
  const home = freshHome();
  let leave;
  const exited = new Promise((resolve) => {
    leave = resolve;
  });
  let returnNow;
  const pressed = new Promise((resolve) => {
    returnNow = resolve;
  });
  const window = {
    exited,
    stop: async () => {
      events.push('window stopped');
      leave();
    },
    kill: () => events.push('window killed'),
  };
  const deps = {
    viewerHome: home,
    site: 'https://film.example',
    say: (line) => said.push(line),
    open: ({ profile, address }) => {
      const which = profile === viewerPaths(home).profile ? 'the profile' : profile;
      events.push(`window opened on ${address} with ${which}`);
      return window;
    },
    waitReturn: () => {
      events.push('waiting for Return');
      return pressed;
    },
    wait: async (ms) => {
      events.push(`wait ${ms}`);
      await onWait?.();
    },
    check: check ?? (async () => {
      events.push('check');
      return 'signed-in';
    }),
    cancel,
    isAlive,
    pid: process.pid,
    held: {},
  };
  return { deps, events, said, home, leave, returnNow, cancel, window, lock: viewerPaths(home).lock };
}

describe('signIn', () => {
  test('the window opens first; Return means the 35 seconds, then the window is stopped, then the check', async () => {
    const h = harness();
    const running = signIn(h.deps);
    await flush();
    assert.deepEqual(h.events.slice(0, 2), [
      'window opened on https://film.example with the profile', 'waiting for Return',
    ]);
    assert.equal(existsSync(h.lock), true, 'the lock is held while the window is open');
    assert.equal(h.events.includes('check'), false, 'no check while the window is open');
    h.returnNow();
    assert.equal(await soon(running), 0);
    assert.deepEqual(h.events.slice(2), [`wait ${SETTLE_MS}`, 'window stopped', 'check']);
    assert.equal(SETTLE_MS, 35000);
    assert.equal(existsSync(h.lock), false, 'the lock is let go');
  });

  test('the window going first means no wait, and the check runs after it', async () => {
    const h = harness();
    const running = signIn(h.deps);
    await flush();
    h.leave();
    assert.equal(await soon(running), 0);
    assert.equal(h.events.some((event) => event.startsWith('wait ')), false);
    assert.ok(h.events.indexOf('check') > h.events.indexOf('window stopped'));
  });

  test('Return and the window\'s end arriving together: the window is stopped before the one check', async () => {
    const h = harness();
    const running = signIn(h.deps);
    await flush();
    h.leave();
    h.returnNow();
    assert.equal(await soon(running), 0);
    assert.ok(h.events.indexOf('check') > h.events.indexOf('window stopped'), h.events.join(' | '));
    assert.equal(h.events.filter((event) => event === 'check').length, 1);
  });

  test('says what to do first, then that the sign-in is being kept and takes 35 seconds, then the result', async () => {
    const h = harness();
    const running = signIn(h.deps);
    await flush();
    assert.match(h.said[0], /window is opening on https:\/\/film\.example\. Sign in there/);
    assert.match(h.said.join(' '), /press Return.*Cmd-Q/);
    h.returnNow();
    await soon(running);
    assert.match(h.said.join('\n'), /Keeping the sign-in: this takes 35 seconds/);
    assert.match(h.said.at(-1), /^Signed in\./);
  });

  test('each of the four answers gives its line and its exit code', async () => {
    const wanted = [['signed-in', 0, /^Signed in/], ['signed-out', 1, /^Not signed in/],
      ['no-answer', 1, /did not answer/], ['unknown', 1, /not understood/]];
    for (const [answer, code, line] of wanted) {
      const h = harness({ check: async () => answer });
      const running = signIn(h.deps);
      await flush();
      h.leave();
      assert.equal(await soon(running), code, answer);
      assert.match(h.said.at(-1), line, answer);
    }
  });

  test('a lock held by a live process refuses with one line, opens no window, and leaves that lock alone', async () => {
    const h = harness({ isAlive: (pid) => pid === 77 });
    mkdirSync(h.home, { recursive: true });
    writeFileSync(h.lock, '77');
    assert.equal(await soon(signIn(h.deps)), 1);
    assert.match(h.said.at(-1), /another viewer is running \(process 77\)/);
    assert.equal(h.events.length, 0);
    assert.equal(readFileSync(h.lock, 'utf8'), '77');
    assert.equal(existsSync(viewerPaths(h.home).profile), false, 'no folder is made before the lock is taken');
  });

  test('a Chrome still using the profile refuses before a window opens, and the lock is let go', async () => {
    const h = harness({ isAlive: (pid) => pid === 4321 });
    const { profile } = viewerPaths(h.home);
    mkdirSync(profile, { recursive: true });
    symlinkSync('host-4321', join(profile, 'SingletonLock'));
    assert.equal(await soon(signIn(h.deps)), 1);
    assert.match(h.said.at(-1), /still using the viewer's profile \(process 4321\)/);
    assert.equal(h.events.length, 0);
    assert.equal(existsSync(h.lock), false);
  });

  test('a Chrome that started using the profile while the window was open stops the check from starting', async () => {
    const h = harness({ isAlive: (pid) => pid === 4321 });
    const running = signIn(h.deps);
    await flush();
    symlinkSync('host-4321', join(viewerPaths(h.home).profile, 'SingletonLock'));
    h.leave();
    assert.equal(await soon(running), 1);
    assert.equal(h.events.includes('check'), false);
    assert.match(h.said.at(-1), /quit it first/);
  });

  test('a step that throws: one line, exit 1, the window stopped and the lock let go', async () => {
    const h = harness({ check: async () => {
      throw new Error('Chrome did not start');
    } });
    const running = signIn(h.deps);
    await flush();
    h.leave();
    assert.equal(await soon(running), 1);
    assert.match(h.said.at(-1), /The sign-in failed: Chrome did not start/);
    assert.equal(existsSync(h.lock), false);
  });

  test('a throw while the window is open stops the window', async () => {
    const h = harness();
    h.deps.waitReturn = () => Promise.reject(new Error('stdin broke'));
    assert.equal(await soon(signIn(h.deps)), 1);
    assert.ok(h.events.includes('window stopped'));
    assert.equal(existsSync(h.lock), false);
  });

  test('a signal while the window is open: no check, window stopped, lock let go, exit 130', async () => {
    const h = harness();
    const running = signIn(h.deps);
    await flush();
    h.cancel.trigger();
    assert.equal(await soon(running), 130);
    assert.deepEqual(h.events.filter((event) => event !== 'waiting for Return').slice(1), ['window stopped']);
    assert.equal(existsSync(h.lock), false);
  });

  test('a signal during the 35 seconds ends it at once: no check, window stopped, exit 130', async () => {
    const h = harness({ onWait: () => h.cancel.trigger() });
    const running = signIn(h.deps);
    await flush();
    h.returnNow();
    assert.equal(await soon(running), 130);
    assert.equal(h.events.includes('check'), false);
    assert.ok(h.events.includes('window stopped'));
    assert.equal(existsSync(h.lock), false);
  });

  test('a signal during the check: given up on, the check\'s Chrome hard-stopped, lock let go, exit 130', async () => {
    let finish;
    let hardStopped = 0;
    let lockHeldAtHardStop = null;
    const h = harness({
      check: ({ onSpawn }) => {
        onSpawn(() => {
          hardStopped += 1;
          lockHeldAtHardStop = existsSync(h.lock);
        });
        return new Promise((_, reject) => {
          finish = reject;
        });
      },
    });
    const running = signIn(h.deps);
    await flush();
    h.leave();
    await flush();
    await flush();
    h.cancel.trigger();
    assert.equal(await soon(running), 130);
    assert.equal(hardStopped, 1);
    assert.equal(lockHeldAtHardStop, true, 'the check\'s Chrome is hard-stopped before the lock is let go');
    assert.equal(existsSync(h.lock), false);
    finish(new Error('Chrome closed the connection')); // the abandoned check ends later: that is no crash
    await flush();
  });

  test('after a check that finished, its Chrome is not hard-stopped again', async () => {
    let hardStopped = 0;
    const h = harness({ check: async ({ onSpawn }) => {
      onSpawn(() => {
        hardStopped += 1;
      });
      return 'signed-in';
    } });
    const running = signIn(h.deps);
    await flush();
    h.leave();
    assert.equal(await soon(running), 0);
    assert.equal(hardStopped, 0);
  });

  test('a second sign-in started while the first holds the lock is refused', async () => {
    const first = harness();
    const running = signIn(first.deps);
    await flush();
    const second = harness({ isAlive: (pid) => pid === process.pid });
    second.deps.viewerHome = first.home;
    assert.equal(await soon(signIn(second.deps)), 1);
    assert.match(second.said.at(-1), /another viewer is running/);
    assert.equal(second.events.length, 0);
    assert.equal(existsSync(first.lock), true, 'the first still holds it');
    first.leave();
    assert.equal(await soon(running), 0);
  });
});

describe('signIn: signals', () => {
  test('with the real wait, a signal part-way through the 35 seconds ends it with no further tick', async (t) => {
    t.mock.timers.enable({ apis: ['setTimeout'] });
    const h = harness();
    delete h.deps.wait;
    const running = signIn(h.deps);
    await flush();
    h.returnNow();
    await flush();
    t.mock.timers.tick(10000);
    await flush();
    h.cancel.trigger();
    assert.equal(await soon(running), 130);
    assert.equal(h.events.includes('check'), false);
  });

  test('a signal while the window is being stopped ends it before the check starts a Chrome', async () => {
    const h = harness();
    h.window.stop = async () => {
      h.events.push('window stopped');
      h.cancel.trigger();
      h.leave();
    };
    const running = signIn(h.deps);
    await flush();
    h.leave();
    assert.equal(await soon(running), 130);
    assert.equal(h.events.includes('check'), false);
    assert.equal(existsSync(h.lock), false);
  });

  test('a signal and then a step that throws is still a signal: 130, with the window hard-stopped', async () => {
    const h = harness();
    h.window.stop = async () => {
      h.cancel.trigger();
      throw new Error('stop failed');
    };
    const running = signIn(h.deps);
    await flush();
    h.leave();
    assert.equal(await soon(running), 130);
    assert.ok(h.events.includes('window killed'));
    assert.equal(existsSync(h.lock), false);
  });

  test('a step that throws with no signal is 1 (the window that will not stop is hard-stopped too)', async () => {
    const h = harness();
    h.window.stop = async () => {
      throw new Error('stop failed');
    };
    const running = signIn(h.deps);
    await flush();
    h.leave();
    assert.equal(await soon(running), 1);
    assert.ok(h.events.includes('window killed'));
  });
});

describe('watchSignals', () => {
  function watched(held = {}) {
    const log = [];
    const handlers = new Map();
    const cancel = createCancel();
    watchSignals(cancel, held, (line) => log.push(`say ${line}`), {
      exit: (code) => log.push(`exit ${code}`),
      listen: (name, fn) => handlers.set(name, fn),
    });
    return { log, handlers, cancel, held };
  }

  test('listens for SIGINT, SIGTERM and SIGHUP', () => {
    assert.deepEqual([...watched().handlers.keys()], ['SIGINT', 'SIGTERM', 'SIGHUP']);
  });

  test('the first signal says so and cancels, and stops nothing and does not exit', () => {
    const killed = [];
    const w = watched({ window: { kill: () => killed.push('window') }, chromeKill: () => killed.push('chrome') });
    w.handlers.get('SIGHUP')();
    assert.deepEqual(w.log, ['say stopping (a second Ctrl-C quits at once)']);
    assert.equal(w.cancel.cancelled, true);
    assert.deepEqual(killed, []);
  });

  test('a second signal hard-stops the window and the check\'s Chrome, lets the lock go and exits 130', () => {
    const w = watched();
    Object.assign(w.held, {
      window: { kill: () => w.log.push('window') }, chromeKill: () => w.log.push('chrome'),
      release: () => w.log.push('lock'),
    });
    w.handlers.get('SIGINT')();
    w.log.length = 0;
    w.handlers.get('SIGTERM')();
    assert.deepEqual(w.log, ['window', 'chrome', 'lock', 'exit 130']);
  });

  test('a second signal while the window is still being stopped hard-stops it and lets the lock go', async () => {
    const h = harness();
    let finishStop;
    h.window.stop = async () => {
      h.events.push('window stopping');
      await new Promise((resolve) => {
        finishStop = resolve;
      });
    };
    const exits = [];
    const handlers = [];
    watchSignals(h.cancel, h.deps.held, () => undefined, {
      exit: (code) => exits.push(code), listen: (name, fn) => handlers.push(fn),
    });
    const running = signIn(h.deps);
    await flush();
    handlers[0](); // the first signal: signIn ends, and its clean-up begins on the window
    await flush();
    await flush();
    assert.ok(h.events.includes('window stopping'));
    assert.equal(existsSync(h.lock), true, 'still held while the window is being stopped');
    handlers[0](); // the second, in the middle of that
    assert.deepEqual(exits, [130]);
    assert.ok(h.events.includes('window killed'), h.events.join(' | '));
    assert.equal(existsSync(h.lock), false);
    finishStop();
    assert.equal(await soon(running), 130);
  });
});

describe('crashHandler', () => {
  function crash(held) {
    const log = [];
    const handler = crashHandler(held, {
      say: (line) => log.push(`say ${line}`), exit: (code) => log.push(`exit ${code}`),
    });
    return { log, handler };
  }

  test('hard-stops the window and the check\'s Chrome, lets the lock go, says so, then exits with 1', () => {
    const log = [];
    const held = {
      window: { kill: () => log.push('window') }, chromeKill: () => log.push('chrome'), release: () => log.push('lock'),
    };
    const crashed = crashHandler(held, { say: (line) => log.push(line), exit: (code) => log.push(`exit ${code}`) });
    crashed(new Error('boom'));
    assert.deepEqual(log, ['window', 'chrome', 'lock', 'The viewer stopped on an unexpected error: boom', 'exit 1']);
  });

  test('a step that throws does not skip the rest, and the exit still happens', () => {
    const log = [];
    const held = {
      window: { kill: () => {
        throw new Error('already gone');
      } },
      chromeKill: () => log.push('chrome'),
      release: () => log.push('lock'),
    };
    crashHandler(held, { say: () => {
      throw new Error('terminal gone');
    }, exit: (code) => log.push(`exit ${code}`) })('not an error object');
    assert.deepEqual(log, ['chrome', 'lock', 'exit 1']);
  });

  test('with nothing started it only says so and exits', () => {
    const { log, handler } = crash({});
    handler(new Error('early'));
    assert.deepEqual(log, ['say The viewer stopped on an unexpected error: early', 'exit 1']);
  });
});
