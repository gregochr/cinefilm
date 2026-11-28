// scripts/replay/playhead.mjs: the server's one encoder playhead per session, on a clock the test sets. Segments are
// four seconds and the encoder runs 20 times faster than real time, so it makes one every 200 ms. Each test names the
// rule of SessionManager.java it pins.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import {
  BEHIND_MS, LOOK_AHEAD_MS, WAIT_AHEAD_SEGMENTS, WAIT_MAX_MS, createPlayhead,
} from '../../replay/playhead.mjs';

const SEGMENT_MS = 4000;

// A playhead and the clock it reads; `at(ms)` sets the clock.
function make({ segmentCount = 1800, speed = 20 } = {}) {
  const clock = { t: 1_000_000 };
  const playhead = createPlayhead({ segmentCount, segmentMs: SEGMENT_MS, speed, now: () => clock.t });
  return { playhead, clock, wait: (ms) => { clock.t += ms; } };
}

describe('the constants', () => {
  test('are the server\'s: wait ahead 10 segments, 6 s, 3 minutes ahead, 2 minutes behind', () => {
    assert.equal(WAIT_AHEAD_SEGMENTS, 10);
    assert.equal(WAIT_MAX_MS, 6000);
    assert.equal(LOOK_AHEAD_MS, 180000);
    assert.equal(BEHIND_MS, 120000);
  });
});

describe('createPlayhead', () => {
  test('refuses a speed, a segment length or a count that is not above 0', () => {
    const base = { segmentCount: 10, segmentMs: 4000, speed: 20, now: () => 0 };
    for (const bad of [{ speed: 0 }, { speed: -1 }, { speed: NaN }, { segmentMs: 0 }, { segmentCount: 0 }]) {
      assert.throws(() => createPlayhead({ ...base, ...bad }), /needs a speed/);
    }
  });
});

describe('the first run', () => {
  test('starts at segment 0 at once and makes a segment every segment length over the speed', () => {
    const { playhead, wait } = make();
    assert.equal(playhead.complete(0), false);
    assert.equal(playhead.readyInMs(0), 200);
    wait(199);
    assert.equal(playhead.complete(0), false);
    assert.equal(playhead.readyInMs(0), 1);
    wait(1);
    assert.equal(playhead.complete(0), true);
    assert.equal(playhead.readyInMs(0), 0);
    assert.equal(playhead.complete(1), false);
    assert.equal(playhead.readyInMs(3), 600); // four periods from the start, 200 ms gone
  });

  test('a request for a segment it will reach is left alone: no restart, whatever the rung', () => {
    const { playhead, wait } = make();
    assert.deepEqual(playhead.request(0), { restart: null });
    wait(1000); // five segments made, the sixth being written
    assert.deepEqual(playhead.request(5), { restart: null });
    assert.deepEqual(playhead.request(15), { restart: null }); // 10 past the newest mark (5), still waited for
    assert.equal(playhead.state().restarts, 0);
  });

  test('a request more than 10 past the newest mark restarts it there, for a reason: ahead', () => {
    const { playhead, wait } = make();
    wait(1000);
    assert.deepEqual(playhead.request(16), { restart: 'ahead' });
    const state = playhead.state();
    assert.equal(state.restarts, 1);
    assert.equal(state.playhead, 16);
    assert.equal(state.runStart, 16);
  });

  test('the run ends at the last segment and a segment past what it made is not there', () => {
    const { playhead, wait } = make({ segmentCount: 10 });
    wait(60_000);
    assert.equal(playhead.complete(9), true);
    assert.equal(playhead.running(), false);
    assert.equal(playhead.state().status, 'finished');
    assert.equal(playhead.readyInMs(9), 0);
  });
});

describe('a restart', () => {
  test('begins at the request: its segment takes one period, and the old run\'s newer segments are not waited for', () => {
    const { playhead, wait } = make();
    wait(2000); // ten segments made
    assert.equal(playhead.complete(9), true);
    assert.deepEqual(playhead.request(40), { restart: 'ahead' });
    assert.equal(playhead.complete(40), false);
    assert.equal(playhead.readyInMs(40), 200);
    wait(200);
    assert.equal(playhead.complete(40), true);
    assert.equal(playhead.complete(41), false);
  });

  test('deletes from its segment to the end, so what an older run made there is made again', () => {
    const { playhead, wait } = make();
    wait(10_000); // 0 to 45 made, then stopped for running 3 minutes ahead
    assert.deepEqual(playhead.request(60), { restart: 'stopped' }); // playhead 60: segments below 30 are deleted
    assert.equal(playhead.complete(35), true);
    assert.equal(playhead.complete(10), false);
    // 20 is deleted and before the run's start (60): a restart at 20 deletes 20 to the end, 35 with them
    assert.deepEqual(playhead.request(20), { restart: 'behind' });
    assert.equal(playhead.complete(35), false);
    assert.equal(playhead.state().runStart, 20);
    assert.equal(playhead.state().playhead, 20);
  });

  test('a request before the run\'s start, for a segment that is not there, is behind', () => {
    const { playhead, wait } = make();
    wait(400);
    assert.deepEqual(playhead.request(50), { restart: 'ahead' });
    wait(400); // 50 and 51 made
    assert.equal(playhead.complete(51), true);
    assert.deepEqual(playhead.request(20), { restart: 'behind' }); // never made, and before the run's start
    assert.equal(playhead.state().restarts, 2);
    assert.equal(playhead.complete(50), false); // the restart deleted from 20 to the end
  });

  test('every restart is counted, and the first run is not one', () => {
    const { playhead } = make();
    assert.equal(playhead.state().restarts, 0);
    playhead.request(100);
    playhead.request(300);
    playhead.request(5);
    assert.equal(playhead.state().restarts, 3);
  });
});

describe('the playhead', () => {
  test('goes forward to any newer request and stays on one a little behind', () => {
    const { playhead, wait } = make();
    wait(2000); // 0 to 9 made
    playhead.request(5);
    assert.equal(playhead.state().playhead, 5);
    playhead.request(9);
    assert.equal(playhead.state().playhead, 9);
    playhead.request(2);
    assert.equal(playhead.state().playhead, 9);
  });

  test('stays on a request 2 minutes behind and goes back to one more than that, which is deleted by then', () => {
    const { playhead, wait } = make();
    wait(10_000); // 0 to 45 made, then stopped
    playhead.request(45); // segments below 15 are deleted
    playhead.request(15); // 30 behind: exactly 2 minutes
    assert.equal(playhead.state().playhead, 45);
    assert.equal(playhead.state().restarts, 0);
    playhead.request(14); // 31 behind, deleted, and the run is stopped: it restarts there, the playhead at it
    assert.equal(playhead.state().playhead, 14);
    assert.equal(playhead.state().restarts, 1);
  });
});

describe('the encoder running too far ahead', () => {
  test('is stopped when its newest mark is more than 3 minutes past the playhead', () => {
    const { playhead, wait } = make();
    wait(8000); // 40 segments of 46 allowed
    assert.equal(playhead.running(), true);
    wait(1400); // the 46th is made: the mark, 46, is 184 s past the playhead
    assert.equal(playhead.running(), false);
    assert.equal(playhead.state().status, 'stopped');
    assert.equal(playhead.complete(45), true);
    assert.equal(playhead.complete(46), false);
    assert.equal(playhead.readyInMs(46), null);
  });

  test('the next request past what it made restarts it, for a reason: stopped', () => {
    const { playhead, wait } = make();
    wait(20_000);
    assert.equal(playhead.running(), false);
    assert.deepEqual(playhead.request(46), { restart: 'stopped' });
    assert.equal(playhead.state().restarts, 1);
    assert.equal(playhead.running(), true);
  });

  test('a playhead that moves on keeps the run going past where it would have stopped', () => {
    const { playhead, wait } = make();
    wait(2000); // 10 made
    playhead.request(8);
    wait(1800); // 19 made
    playhead.request(28); // within 10 of the mark (19): no restart
    wait(3000); // 34 made
    playhead.request(44);
    wait(4000); // 54 made: by itself the run would have stopped at 46
    assert.equal(playhead.running(), true);
    assert.equal(playhead.complete(53), true);
    assert.equal(playhead.state().restarts, 0);
  });
});

describe('segments behind the playhead', () => {
  test('are deleted when more than 2 minutes behind it', () => {
    const { playhead, wait } = make();
    wait(5000); // 25 made
    playhead.request(24);
    assert.equal(playhead.complete(0), true);
    playhead.request(31); // 7 past: segments below 1 are more than 2 minutes behind
    assert.equal(playhead.complete(0), false);
    assert.equal(playhead.complete(1), true);
  });

  test('inside the live run\'s own span a deleted one is only waited for, and the run is stopped for being ahead of the playhead gone back', () => {
    const { playhead, wait } = make({ segmentCount: 5000, speed: 200 }); // a segment every 20 ms
    // The player follows the run, asking for the newest segment made, so that the run is never stopped for being ahead.
    for (let i = 0; i < 20; i += 1) {
      wait(200);
      playhead.request(10 * (i + 1) - 1);
    }
    assert.equal(playhead.state().runStart, 0);
    assert.equal(playhead.complete(100), false); // 199 is the playhead: everything below 169 is deleted
    assert.deepEqual(playhead.request(100), { restart: null });
    assert.equal(playhead.state().playhead, 100); // far enough behind for the playhead to go back to it
    assert.equal(playhead.state().restarts, 0);
    assert.equal(playhead.readyInMs(100), null);
    // The playhead went back 100 segments, so the run is more than 3 minutes ahead of it: the sweep stops it, a waiting
    // request is let go (503), and the player's retry of the same segment is what restarts it.
    assert.equal(playhead.running(), false);
    assert.deepEqual(playhead.request(100), { restart: 'stopped' });
    assert.equal(playhead.state().restarts, 1);
  });
});
