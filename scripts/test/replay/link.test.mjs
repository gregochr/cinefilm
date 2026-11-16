// scripts/replay/link.mjs: the shared allowance, driven with made-up milliseconds and no clock.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { createLink, minuteAt, rateAt } from '../../replay/link.mjs';

// A job of `size` bytes that records every send.
function job(size) {
  const sends = [];
  return {
    sends,
    got: () => sends.reduce((a, b) => a + b, 0),
    left() {
      return size - this.got();
    },
    send(n) {
      sends.push(n);
    },
  };
}

// A link whose rate (bits a second) the test can change.
function linkAt(bps) {
  const state = { bps };
  return { state, link: createLink(() => state.bps) };
}

describe('the allowance', () => {
  test('at 800 kbit/s, a hundred advances of 10 ms move 100,000 bytes and no more', () => {
    const { link } = linkAt(800_000);
    const a = job(10_000_000);
    link.add(a);
    for (let i = 0; i < 100; i += 1) link.advance(10);
    assert.equal(a.got(), 100_000);
    assert.equal(a.sends.length, 100);
    assert.ok(a.sends.every((n) => n === 1000));
  });

  test('one advance of a whole second moves only the 5,000 bytes that may be saved', () => {
    const { link } = linkAt(800_000);
    const a = job(10_000_000);
    link.add(a);
    link.advance(1000);
    assert.equal(a.got(), 5000); // a twentieth of a second of 100,000 bytes a second
    link.advance(1000);
    assert.equal(a.got(), 10_000);
  });

  test('on a slow link 3,000 bytes may be saved, not a twentieth of a second', () => {
    const { link } = linkAt(8000); // 1,000 bytes a second: a twentieth of a second is 50 bytes
    link.advance(10_000);
    const a = job(10_000);
    link.add(a);
    assert.equal(a.got(), 3000);
  });

  test('a rate of 0 moves nothing and forgets what was saved', () => {
    const { state, link } = linkAt(800_000);
    link.advance(10); // 1,000 bytes saved, nobody to take them
    state.bps = 0;
    link.advance(10);
    state.bps = 800_000;
    const a = job(10_000);
    link.add(a);
    assert.equal(a.got(), 0);
    link.advance(10);
    assert.equal(a.got(), 1000);
  });

  test('a job on a dead link gets nothing however many advances', () => {
    const { link } = linkAt(0);
    const a = job(10_000);
    link.add(a);
    for (let i = 0; i < 50; i += 1) link.advance(1000);
    assert.equal(a.got(), 0);
    assert.equal(link.size(), 1);
  });

  test('one send is at most 64 KB', () => {
    const { link } = linkAt(800_000_000); // 100 MB a second: 5,000,000 bytes may be saved
    const a = job(100_000_000);
    link.add(a);
    link.advance(1000);
    assert.equal(a.sends[0], 65536);
    assert.equal(Math.max(...a.sends), 65536);
    assert.equal(a.sends.length, 77);
    assert.equal(a.got(), 5_000_000);
  });
});

describe('sharing', () => {
  test('two jobs sharing 1,001 bytes get 501 and 500, the odd byte to the first', () => {
    const { link } = linkAt(8_008_000); // 1,001,000 bytes a second: one millisecond is 1,001 bytes
    const a = job(10_000);
    const b = job(10_000);
    link.add(a);
    link.add(b);
    link.advance(1);
    assert.equal(a.got(), 501);
    assert.equal(b.got(), 500);
  });

  test('a job that needs little leaves the rest to the other', () => {
    const { link } = linkAt(8_000_000); // 1,000 bytes a millisecond
    const small = job(100);
    const big = job(10_000);
    link.add(small);
    link.add(big);
    link.advance(1);
    assert.equal(small.got(), 100);
    assert.equal(big.got(), 900);
    assert.equal(link.size(), 1);
  });

  test('three jobs share equally, a round at a time', () => {
    const { link } = linkAt(8_000_000);
    const jobs = [job(10_000), job(10_000), job(10_000)];
    for (const j of jobs) link.add(j);
    link.advance(1);
    assert.deepEqual(jobs.map((j) => j.got()), [334, 333, 333]);
  });
});

describe('jobs', () => {
  test('a job added to a link with something saved is served at once', () => {
    const { link } = linkAt(800_000);
    link.advance(1000); // 5,000 bytes saved
    const a = job(20_000);
    link.add(a);
    assert.equal(a.got(), 5000);
  });

  test('a finished job leaves the link', () => {
    const { link } = linkAt(800_000);
    link.advance(1000);
    const a = job(1500);
    link.add(a);
    assert.equal(a.got(), 1500);
    assert.equal(link.size(), 0);
    link.advance(1000);
    assert.equal(a.got(), 1500);
  });

  test('a removed job gets nothing more', () => {
    const { link } = linkAt(800_000);
    const kept = job(10_000);
    const removed = job(10_000);
    link.add(kept);
    link.add(removed);
    assert.equal(link.size(), 2);
    link.remove(removed);
    assert.equal(link.size(), 1);
    link.advance(10);
    assert.equal(removed.got(), 0);
    assert.equal(kept.got(), 1000);
  });

  test('removing a job that is not there is harmless', () => {
    const { link } = linkAt(800_000);
    link.remove(job(10));
    assert.equal(link.size(), 0);
  });

  test('a job that closed itself (nothing left) is dropped without being sent anything', () => {
    const { link } = linkAt(800_000);
    link.advance(1000);
    const closed = job(0);
    const live = job(1000);
    link.add(closed);
    link.add(live);
    assert.deepEqual(closed.sends, []);
    assert.equal(live.got(), 1000);
    assert.equal(link.size(), 0);
  });
});

describe('whenAlive', () => {
  test('runs at once on a live link', () => {
    const { link } = linkAt(1000);
    let calls = 0;
    link.whenAlive(() => {
      calls += 1;
    });
    assert.equal(calls, 1);
  });

  test('on a dead link waits, and runs at the first advance after the rate rises', () => {
    const { state, link } = linkAt(0);
    let calls = 0;
    link.whenAlive(() => {
      calls += 1;
    });
    assert.equal(calls, 0);
    link.advance(10);
    link.advance(10);
    assert.equal(calls, 0);
    state.bps = 1000;
    assert.equal(calls, 0); // nothing happens until the link is advanced
    link.advance(10);
    assert.equal(calls, 1);
    link.advance(10);
    assert.equal(calls, 1);
  });

  test('every waiting callback runs, in the order they were added', () => {
    const { state, link } = linkAt(0);
    const order = [];
    link.whenAlive(() => order.push('a'));
    link.whenAlive(() => order.push('b'));
    link.whenAlive(() => order.push('c'));
    state.bps = 8;
    link.advance(10);
    assert.deepEqual(order, ['a', 'b', 'c']);
  });

  test('a cancelled callback never runs, and the others still do', () => {
    const { state, link } = linkAt(0);
    const order = [];
    const cancel = link.whenAlive(() => order.push('a'));
    link.whenAlive(() => order.push('b'));
    cancel();
    state.bps = 8;
    link.advance(10);
    assert.deepEqual(order, ['b']);
  });
});

describe('minuteAt', () => {
  test('is the start minute until 60,000 ms have passed, then one more', () => {
    assert.equal(minuteAt(5, 0), 5);
    assert.equal(minuteAt(5, 59_999), 5);
    assert.equal(minuteAt(5, 60_000), 6);
    assert.equal(minuteAt(24, 180_000), 27);
    assert.equal(minuteAt(0, 119_999), 1);
  });
});

describe('rateAt', () => {
  const trace = [100, 200, 300];

  test('is the trace\'s figure inside it and at its last minute', () => {
    assert.equal(rateAt(trace, 0), 100);
    assert.equal(rateAt(trace, 1), 200);
    assert.equal(rateAt(trace, 2), 300);
  });

  test('is the last figure past the end of the trace', () => {
    assert.equal(rateAt(trace, 3), 300);
    assert.equal(rateAt(trace, 500), 300);
  });

  test('is 0 for a minute with no figure', () => {
    assert.equal(rateAt([100, undefined, 300], 1), 0);
    assert.equal(rateAt([], 0), 0);
    assert.equal(rateAt(trace, -1), 0);
  });
});
