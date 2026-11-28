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
    playhead.request(44); // segments below 14 are deleted
    playhead.request(14); // 30 behind: exactly 2 minutes
    assert.equal(playhead.state().playhead, 44);
    assert.equal(playhead.state().restarts, 0);
    playhead.request(13); // 31 behind, deleted, and the run is stopped: it restarts there, the playhead at it
    assert.equal(playhead.state().playhead, 13);
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
    // Stopping deleted the file the run was writing (46), and a segment is complete only when the next one's file is
    // there (SessionManager :514-536, :424-435): 45 is not complete any more, 44 is.
    assert.equal(playhead.complete(44), true);
    assert.equal(playhead.complete(45), false);
    assert.equal(playhead.readyInMs(45), null);
  });

  test('the last segment a stopped run made is asked for again: the restart is at it, for a reason: stopped', () => {
    const { playhead, wait } = make();
    wait(10_000);
    assert.equal(playhead.running(), false);
    assert.deepEqual(playhead.request(45), { restart: 'stopped' });
    assert.equal(playhead.state().runStart, 45);
  });

  test('a restart stops the old run the same way: the last segment it made is gone, the ones before are not', () => {
    const { playhead, wait } = make();
    wait(2000); // 0 to 9 made
    assert.deepEqual(playhead.request(35), { restart: 'ahead' });
    assert.equal(playhead.complete(9), false);
    assert.equal(playhead.complete(8), true);
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
    assert.deepEqual(playhead.request(100), { restart: null, deleted: true });
    assert.equal(playhead.state().playhead, 100); // far enough behind for the playhead to go back to it
    assert.equal(playhead.state().restarts, 0);
    assert.equal(playhead.readyInMs(100), null);
    // The playhead went back 100 segments, so the run is more than 3 minutes ahead of it: the sweep stops it, a waiting
    // request is let go (503), and the player's retry of the same segment is what restarts it.
    assert.equal(playhead.running(), false);
    assert.deepEqual(playhead.request(100), { restart: 'lost-stop' });
    assert.equal(playhead.state().restarts, 1);
  });
});

describe('a segment lost and asked again', () => {
  // The bank asks 35 segments ahead of the player: the playhead goes to the bank's segment, the player's own
  // segments fall more than 2 minutes behind it and are deleted, and the player asking for one pulls the playhead back.
  function lostScenario() {
    const made = make({ speed: 19 });
    made.wait(9000); // 42 segments made
    assert.deepEqual(made.playhead.request(10), { restart: null });
    assert.deepEqual(made.playhead.request(45), { restart: null }); // the bank: inside what the run will reach
    return made;
  }

  test('a request for a segment made and deleted is told apart: no restart, never ready, and `deleted`', () => {
    const { playhead, wait } = lostScenario();
    wait(100);
    assert.equal(playhead.complete(12), false); // below 15 is deleted now
    assert.deepEqual(playhead.request(12), { restart: null, deleted: true });
    assert.equal(playhead.readyInMs(12), null);
    assert.equal(playhead.state().playhead, 12); // pulled back
    assert.equal(playhead.state().restarts, 0);
  });

  test('a segment the run has not made yet is not `deleted`, and is not once it is made', () => {
    const { playhead, wait } = lostScenario();
    assert.deepEqual(playhead.request(50), { restart: null }); // reached by the run, not made yet
    wait(3300);
    playhead.request(45);
    assert.equal(playhead.complete(50), true);
    assert.deepEqual(playhead.request(50), { restart: null });
  });

  test('the run is then stopped for being ahead of the playhead, and the restart is a `lost-stop`', () => {
    const { playhead, wait } = lostScenario();
    playhead.request(12);
    wait(3300); // the run reaches its 58th segment: 3 minutes past the playhead now at 12
    assert.equal(playhead.running(), false);
    assert.deepEqual(playhead.request(12), { restart: 'lost-stop' });
    assert.equal(playhead.state().restarts, 1);
  });

  test('a run that stopped on its own before the player came back is a `lost-stop` too, for a lost segment', () => {
    const { playhead, wait } = lostScenario();
    wait(20_000); // the bank's playhead at 45: the run stops at its 91st segment, long before the player asks again
    assert.equal(playhead.running(), false);
    assert.deepEqual(playhead.request(12), { restart: 'lost-stop' });
  });

  test('a restart clears what was lost at or after it: the segment is then only one the run has not made', () => {
    const { playhead } = lostScenario();
    assert.deepEqual(playhead.request(200), { restart: 'ahead' });
    assert.deepEqual(playhead.request(5), { restart: 'behind' }); // the new run's start is 5: 12 is lost no more
    assert.deepEqual(playhead.request(12), { restart: null });
  });

  test('a stop with no such request before it is only stopped', () => {
    const { playhead, wait } = make();
    wait(20_000);
    assert.deepEqual(playhead.request(46), { restart: 'stopped' });
  });
});

describe('a session that starts part-way', () => {
  const at = (startSegment) => {
    const clock = { t: 0 };
    const playhead = createPlayhead({ segmentCount: 1800, segmentMs: SEGMENT_MS, speed: 20, now: () => clock.t, startSegment });
    return { playhead, wait: (ms) => { clock.t += ms; } };
  };

  test('runs from the segment of its start time: its first request is no restart, one before it is behind', () => {
    const { playhead, wait } = at(150); // a start of 600 s
    assert.deepEqual(playhead.request(150), { restart: null });
    assert.equal(playhead.state().runStart, 150);
    assert.equal(playhead.state().playhead, 150);
    wait(400);
    assert.equal(playhead.complete(150), true);
    assert.deepEqual(playhead.request(160), { restart: null });
    assert.deepEqual(playhead.request(100), { restart: 'behind' });
  });

  test('a request more than 10 past the start is ahead, as from 0', () => {
    const { playhead } = at(150);
    assert.deepEqual(playhead.request(162), { restart: 'ahead' });
  });

  test('deletes nothing below the start: the mark starts there', () => {
    const { playhead, wait } = at(150);
    wait(2000);
    playhead.request(155);
    assert.equal(playhead.complete(150), true);
  });

  test('refuses a start outside the film and a negative start-up', () => {
    const base = { segmentCount: 10, segmentMs: 4000, speed: 20, now: () => 0 };
    for (const bad of [{ startSegment: 10 }, { startSegment: -1 }, { startSegment: 1.5 }, { startupMs: -1 }, { startupMs: NaN }]) {
      assert.throws(() => createPlayhead({ ...base, ...bad }), /start segment/);
    }
  });
});

describe('the encoder\'s start-up time', () => {
  const withStartup = (startupMs) => {
    const clock = { t: 0 };
    const playhead = createPlayhead({ segmentCount: 1800, segmentMs: SEGMENT_MS, speed: 20, now: () => clock.t, startupMs });
    return { playhead, wait: (ms) => { clock.t += ms; } };
  };

  test('nothing is made until it has passed, and a segment takes it plus its period', () => {
    const { playhead, wait } = withStartup(1000);
    assert.equal(playhead.readyInMs(0), 1200);
    wait(1000);
    assert.equal(playhead.complete(0), false);
    wait(200);
    assert.equal(playhead.complete(0), true);
  });

  test('a second fetcher beyond 10 past the restart restarts it again while it lasts, and not without it', () => {
    for (const [startupMs, expected] of [[1000, 'ahead'], [0, null]]) {
      const { playhead, wait } = withStartup(startupMs);
      assert.deepEqual(playhead.request(100), { restart: 'ahead' });
      wait(900); // 4 segments made without a start-up time; none with one
      assert.deepEqual(playhead.request(112), { restart: expected }, `start-up ${startupMs}`);
    }
  });

  test('a restart waits the start-up time again', () => {
    const { playhead, wait } = withStartup(1000);
    wait(5000);
    playhead.request(300);
    assert.equal(playhead.readyInMs(300), 1200);
  });
});

describe('a restart sets the playhead and the deletion mark', () => {
  test('behind within 2 minutes of the playhead still moves it, so the run is stopped 3 minutes past the new one', () => {
    const { playhead, wait } = make({ speed: 19 });
    wait(2000); // 0 to 8 made
    assert.deepEqual(playhead.request(60), { restart: 'ahead' });
    // the player, 20 segments behind the bank: 40 is not made and is before the run's start. It is within 2 minutes
    // of the playhead, so `follow` leaves the playhead at 60, and ensureReaching moves it (:458).
    assert.deepEqual(playhead.request(40), { restart: 'behind' });
    assert.equal(playhead.state().playhead, 40);
    wait(10_000); // 47 segments of 46 allowed from the playhead at 40; from 60 the run would still be going
    assert.equal(playhead.state().status, 'stopped');
  });

  test('a seek back lowers the deletion mark, so what the new run makes below the old mark is deleted later', () => {
    const { playhead, wait } = make({ speed: 19 });
    wait(10_000); // 0 to 44 made, the run stopped
    assert.deepEqual(playhead.request(60), { restart: 'stopped' }); // the mark goes to 30
    wait(600);
    assert.deepEqual(playhead.request(20), { restart: 'behind' }); // :492: the mark goes down to 20
    wait(5500); // 20 to 45 made
    assert.deepEqual(playhead.request(55), { restart: null }); // within 10 of the newest, the playhead at 55
    assert.equal(playhead.complete(24), false); // 124 s behind the playhead: deleted
    assert.equal(playhead.complete(22), false);
    assert.equal(playhead.complete(25), true);
  });
});
