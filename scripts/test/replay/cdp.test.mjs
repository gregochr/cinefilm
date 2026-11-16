// scripts/replay/cdp.mjs: how Chrome's messages are matched to the requests and listeners waiting for them, with
// made-up messages and no socket. Launching Chrome and the socket itself are checked by hand.
import assert from 'node:assert/strict';
import { describe, mock, test } from 'node:test';
import {
  failAll, pageSocketUrl, parseActivePort, routeMessage, stopChrome, track,
} from '../../replay/cdp.mjs';

// A request that is waiting, as `track` and the socket code make it; `log` records how it ended.
function waiter(method, log) {
  return {
    method,
    resolve: (result) => log.push(['resolved', result]),
    reject: (error) => log.push(['rejected', error.message]),
  };
}

describe('routeMessage', () => {
  test('a reply resolves its own request with the result, and only that one', () => {
    const first = [];
    const second = [];
    const pending = new Map([[1, waiter('Page.enable', first)], [2, waiter('Runtime.enable', second)]]);
    const kind = routeMessage('{"id":2,"result":{"ok":true}}', pending, new Map());
    assert.equal(kind, 'reply');
    assert.deepEqual(second, [['resolved', { ok: true }]]);
    assert.deepEqual(first, []);
    assert.deepEqual([...pending.keys()], [1]);
  });

  test('an error reply rejects its request with an error that names the method and holds what Chrome said', () => {
    const log = [];
    const pending = new Map([[7, waiter('Page.navigate', log)]]);
    const kind = routeMessage('{"id":7,"error":{"code":-32000,"message":"Cannot navigate"}}', pending, new Map());
    assert.equal(kind, 'error');
    assert.deepEqual(log, [['rejected', 'Page.navigate: {"code":-32000,"message":"Cannot navigate"}']]);
    assert.equal(pending.size, 0);
  });

  test('an event reaches every listener of its method, in order, with its params, and no other', () => {
    const heard = [];
    const listeners = new Map([
      ['Runtime.consoleAPICalled', [(p) => heard.push(['a', p]), (p) => heard.push(['b', p])]],
      ['Page.loadEventFired', [(p) => heard.push(['other', p])]],
    ]);
    const kind = routeMessage('{"method":"Runtime.consoleAPICalled","params":{"type":"error"}}', new Map(), listeners);
    assert.equal(kind, 'event');
    assert.deepEqual(heard, [['a', { type: 'error' }], ['b', { type: 'error' }]]);
  });

  test('a listener that throws does not stop the ones after it', () => {
    const heard = [];
    const listeners = new Map([['X.y', [() => {
      throw new Error('listener failed');
    }, (p) => heard.push(p)]]]);
    assert.equal(routeMessage('{"method":"X.y","params":{"n":1}}', new Map(), listeners), 'event');
    assert.deepEqual(heard, [{ n: 1 }]);
  });

  test('an event nobody listens to is still an event, and changes nothing', () => {
    const log = [];
    const pending = new Map([[1, waiter('A.b', log)]]);
    assert.equal(routeMessage('{"method":"Nobody.listens","params":{}}', pending, new Map()), 'event');
    assert.equal(pending.size, 1);
    assert.deepEqual(log, []);
  });

  test('a reply with an id nobody waits for is ignored without throwing, and leaves the others waiting', () => {
    const log = [];
    const pending = new Map([[1, waiter('A.b', log)]]);
    assert.equal(routeMessage('{"id":99,"result":{}}', pending, new Map()), 'unknown');
    assert.equal(pending.size, 1);
    assert.deepEqual(log, []);
  });

  test('a message that is not JSON, or is JSON but not an object, is ignored without throwing', () => {
    const log = [];
    const pending = new Map([[1, waiter('A.b', log)]]);
    for (const raw of ['not json', '', '{"id":', 'null', '42', '"text"']) {
      assert.equal(routeMessage(raw, pending, new Map()), 'ignored', raw);
    }
    assert.equal(pending.size, 1);
    assert.deepEqual(log, []);
  });

  test('a reply is used once: the same reply again is unknown', () => {
    const log = [];
    const pending = new Map([[1, waiter('A.b', log)]]);
    assert.equal(routeMessage('{"id":1,"result":3}', pending, new Map()), 'reply');
    assert.equal(routeMessage('{"id":1,"result":3}', pending, new Map()), 'unknown');
    assert.deepEqual(log, [['resolved', 3]]);
  });
});

describe('track and failAll', () => {
  test('a request that is answered resolves, and a reply that comes after it timed out is unknown', async () => {
    const pending = new Map();
    const answered = track(pending, 1, 'Runtime.evaluate', 60000);
    routeMessage('{"id":1,"result":{"value":5}}', pending, new Map());
    assert.deepEqual(await answered, { value: 5 });
    assert.equal(pending.size, 0);

    const slow = track(pending, 2, 'Page.navigate', 1);
    await assert.rejects(slow, { message: 'Page.navigate: no reply within 1 ms' });
    assert.equal(pending.size, 0, 'a request that was given up on is no longer waiting');
    assert.equal(routeMessage('{"id":2,"result":{}}', pending, new Map()), 'unknown');
  });

  test('a request is waiting from the moment it is tracked', () => {
    const pending = new Map();
    const waiting = track(pending, 5, 'Page.enable', 60000);
    assert.deepEqual([...pending.keys()], [5]);
    assert.equal(pending.get(5).method, 'Page.enable');
    failAll(pending, new Error('stop'));
    return assert.rejects(waiting, { message: 'stop' });
  });

  test('failAll rejects every waiting request with the error and forgets them', async () => {
    const pending = new Map();
    const a = track(pending, 1, 'A.a', 60000);
    const b = track(pending, 2, 'B.b', 60000);
    failAll(pending, new Error('Chrome closed the connection'));
    await assert.rejects(a, { message: 'Chrome closed the connection' });
    await assert.rejects(b, { message: 'Chrome closed the connection' });
    assert.equal(pending.size, 0);
  });
});

describe('pageSocketUrl', () => {
  const PAGE = { type: 'page', webSocketDebuggerUrl: 'ws://127.0.0.1:9999/devtools/page/AB12-cd' };

  test('is rebuilt from the port given and the target id, not taken from what Chrome sent', () => {
    assert.equal(pageSocketUrl([PAGE], 9334), 'ws://127.0.0.1:9334/devtools/page/AB12-cd');
  });

  test('skips targets that are not pages, and takes the first page', () => {
    const worker = { type: 'service_worker', webSocketDebuggerUrl: 'ws://127.0.0.1:9334/devtools/page/WORKER' };
    const second = { type: 'page', webSocketDebuggerUrl: 'ws://127.0.0.1:9334/devtools/page/SECOND' };
    assert.equal(pageSocketUrl([worker, PAGE, second], 9334), 'ws://127.0.0.1:9334/devtools/page/AB12-cd');
  });

  test('a target whose address is not a page socket is not used, whatever host it names', () => {
    const elsewhere = { type: 'page', webSocketDebuggerUrl: 'ws://host.invalid:80/devtools/page/X/../../y' };
    const noId = { type: 'page', webSocketDebuggerUrl: 'ws://127.0.0.1:9334/devtools/page/' };
    const notText = { type: 'page', webSocketDebuggerUrl: 42 };
    const missing = { type: 'page' };
    assert.equal(pageSocketUrl([elsewhere, noId, notText, missing], 9334), null);
  });

  test('a list that holds null, or a target that is not an object, is stepped over', () => {
    assert.equal(pageSocketUrl([null, undefined, 'text', PAGE], 9334), 'ws://127.0.0.1:9334/devtools/page/AB12-cd');
  });

  test('no page, or something that is not a list, gives null', () => {
    assert.equal(pageSocketUrl([], 9334), null);
    assert.equal(pageSocketUrl({ type: 'page' }, 9334), null);
    assert.equal(pageSocketUrl(null, 9334), null);
  });
});

describe('track: its timer', () => {
  // The timeout is a mocked timer, so that advancing it is an event and not a wait.
  test('is cleared when the reply comes: advancing past the timeout does not reject or touch pending', async (t) => {
    t.mock.timers.enable({ apis: ['setTimeout'] });
    const pending = new Map();
    const answered = track(pending, 1, 'A.b', 1000);
    routeMessage('{"id":1,"result":7}', pending, new Map());
    assert.equal(await answered, 7);
    const sentinel = { method: 'later', resolve: () => undefined, reject: () => undefined };
    pending.set(1, sentinel);
    t.mock.timers.tick(5000);
    assert.equal(pending.get(1), sentinel);
  });

  test('is cleared when an error reply comes', async (t) => {
    t.mock.timers.enable({ apis: ['setTimeout'] });
    const pending = new Map();
    const failed = track(pending, 1, 'A.b', 1000);
    routeMessage('{"id":1,"error":{"message":"no"}}', pending, new Map());
    await assert.rejects(failed, { message: 'A.b: {"message":"no"}' });
    const sentinel = { method: 'later', resolve: () => undefined, reject: () => undefined };
    pending.set(1, sentinel);
    t.mock.timers.tick(5000);
    assert.equal(pending.get(1), sentinel);
  });

  test('fires after its time when nothing came, and one tick short of it does not', async (t) => {
    t.mock.timers.enable({ apis: ['setTimeout'] });
    const pending = new Map();
    const slow = track(pending, 3, 'Page.navigate', 1000);
    t.mock.timers.tick(999);
    assert.equal(pending.size, 1);
    t.mock.timers.tick(1);
    await assert.rejects(slow, { message: 'Page.navigate: no reply within 1000 ms' });
    assert.equal(pending.size, 0);
  });

  test('is cleared when failAll rejects the request', async (t) => {
    t.mock.timers.enable({ apis: ['setTimeout'] });
    const pending = new Map();
    const waiting = track(pending, 1, 'A.b', 1000);
    failAll(pending, new Error('gone'));
    await assert.rejects(waiting, { message: 'gone' });
    const sentinel = { method: 'later', resolve: () => undefined, reject: () => undefined };
    pending.set(1, sentinel);
    t.mock.timers.tick(5000);
    assert.equal(pending.get(1), sentinel);
  });
});

describe('parseActivePort', () => {
  test('is the first line of the file when it is a whole number from 1 to 65535', () => {
    assert.equal(parseActivePort('41773\n/devtools/browser/abc-123\n'), 41773);
    assert.equal(parseActivePort('1'), 1);
    assert.equal(parseActivePort('65535'), 65535);
    assert.equal(parseActivePort('41773\r\n/devtools/browser/x'), 41773);
  });

  test('is null for an empty file, a port of 0, 65536, text, a fraction or a sign', () => {
    for (const bad of ['', '\n/devtools/browser/x', '0', '65536', '123456', 'abc', '12.5', '-5', '+5', '80 80', '9334abc']) {
      assert.equal(parseActivePort(bad), null, JSON.stringify(bad));
    }
  });

  test('only the first line counts: a number on the second line is not the port', () => {
    assert.equal(parseActivePort('/devtools/browser/x\n41773'), null);
  });
});

describe('stopChrome', () => {
  // A process that has not gone, and a way to make it go.
  function fake({ goesOn }) {
    const calls = [];
    let go;
    const exited = new Promise((resolve) => {
      go = resolve;
    });
    return {
      calls,
      exited,
      kill: (signal) => {
        calls.push(`kill ${signal}`);
        if (signal === goesOn) go();
      },
      removeProfile: () => calls.push('remove'),
    };
  }

  test('a process that goes on the first kill is waited for, not killed again, and then the profile is removed', async () => {
    const p = fake({ goesOn: 'SIGTERM' });
    await stopChrome({ ...p, graceMs: 600000 });
    assert.deepEqual(p.calls, ['kill SIGTERM', 'remove']);
  });

  test('a process that has not gone within the grace period is killed for good, waited for, and then the profile is removed', async () => {
    const p = fake({ goesOn: 'SIGKILL' });
    await stopChrome({ ...p, graceMs: 1, hardMs: 600000 });
    assert.deepEqual(p.calls, ['kill SIGTERM', 'kill SIGKILL', 'remove']);
  });

  test('a process that does not go even for SIGKILL is not waited for ever: the profile is still removed', async () => {
    const p = fake({ goesOn: 'never' });
    await stopChrome({ ...p, graceMs: 1, hardMs: 1 });
    assert.deepEqual(p.calls, ['kill SIGTERM', 'kill SIGKILL', 'remove']);
  });

  test('a profile that cannot be removed does not make it throw', async () => {
    const p = fake({ goesOn: 'SIGTERM' });
    const failing = () => {
      p.calls.push('remove');
      throw new Error('EBUSY');
    };
    await stopChrome({ ...p, removeProfile: failing, graceMs: 600000 });
    assert.deepEqual(p.calls, ['kill SIGTERM', 'remove']);
  });

  test('the profile is removed after the process has gone, not before', async () => {
    const calls = [];
    let go;
    const exited = new Promise((resolve) => {
      go = resolve;
    });
    const stopping = stopChrome({
      kill: () => calls.push('kill'),
      exited,
      removeProfile: () => calls.push('remove'),
      graceMs: 600000,
    });
    await Promise.resolve();
    assert.deepEqual(calls, ['kill']);
    go();
    await stopping;
    assert.deepEqual(calls, ['kill', 'remove']);
  });
});
