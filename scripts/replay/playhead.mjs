// The server's one encoder playhead per session, for the stand-in (standin.mjs). It mirrors SessionManager.java
// (backend/src/main/java/gregochr/cinefilm/transcode/), for a film of one part:
//
//   - segmentLive, :221-258: a request moves the playhead (`follow`), is answered at once when its segment is
//     complete, otherwise `ensureReaching` runs and the request waits up to WAIT_MAX (6 s) for the segment, then gets
//     503 with Retry-After: 1 (HlsSessionController.java:75-78).
//   - follow, :416-422: the playhead goes to any newer request, and back only to one more than BEHIND (2 min) behind.
//   - ensureReaching, :448-470: a live encoder that will reach the segment soon is left alone (the segment is at or
//     past the run's first and no more than WAIT_AHEAD_SEGMENTS, 10, past its newest); any other request, and any
//     request when the encoder is not running (it finished, or was stopped for running too far ahead), stops it and
//     restarts it at the segment.
//   - startRun, :473-493: a restart first deletes every rung's files from its segment to the end of the part, so
//     what the old run had encoded past that point is gone and is encoded again.
//   - tooFarAhead, :504-507, and maintain, :353-369: the sweep stops the encoder when its newest segment is more than
//     LOOK_AHEAD (3 min) past the playhead.
//   - deleteBehind, :538-548: segments more than BEHIND behind the playhead are deleted. A request for one of them
//     (the playhead then goes back to it) finds a live run that began at or before it and so only waits: nothing
//     encodes it again, and the request ends 503 after 6 s, until a request before the run's start restarts it.
//     A fetcher ahead of the player makes this a fight with no restart in it: the playhead is pulled back, the run
//     is stopped for being ahead, and the player starves. Such a request is reported as `deleted`.
//   - stop -> cleanPartial, :514-536, and complete, :424-435: stopping a run deletes the segment file it was writing,
//     and a segment is complete only when the next one's file exists, so the last segment a stopped run made is not
//     complete, and a request for it restarts the encoder there.
//   - create, :161, and TranscodeSession.java:90-91: the first run, the playhead and the deletion mark start at the
//     segment of the session's startMs.
//
// Every rung is encoded by the one run, so a segment is made for all of them together and a request on any rung moves
// the one playhead. The encoder's speed is a multiple of real time (`speed`); a segment is made after the time that
// takes, counted from the run's start plus `startupMs`, the time a spawn and a seek take (not measured on the real
// server). What is not modelled: the sweep's two-second grain (the state is brought up
// to date when a request arrives, and the encoder stops on the first segment past the limit), the encoder's lag
// between rungs (cleanPartial's loop over the rungs), a film of several parts, the two-minute idle stop and the session being
// rebuilt after ten minutes of quiet (the player never goes quiet that long in a replay).

/** A segment this many past the run's newest is waited for; further than that restarts the encoder. */
export const WAIT_AHEAD_SEGMENTS = 10;
/** How long a request waits for its segment before it is answered 503. */
export const WAIT_MAX_MS = 6000;
/** The encoder stops when its newest segment is this far past the playhead. */
export const LOOK_AHEAD_MS = 3 * 60 * 1000;
/** Segments this far behind the playhead are deleted. */
export const BEHIND_MS = 2 * 60 * 1000;

/**
 * One session's encoder and playhead. `segmentMs` is the length of every segment, `speed` how many times faster than
 * real time the encoder runs, `now()` the clock in milliseconds. The first run starts at `startSegment` (the server's
 * `cuts.segmentAt(startMs)`, SessionManager.create :161, SessionService :125-168) at once. `startupMs` is the time from
 * a run's start (a spawn or a restart) to the first segment's making beginning: until it has passed the run's newest
 * mark stays at its first segment.
 */
export function createPlayhead({ segmentCount, segmentMs, speed, now, startSegment = 0, startupMs = 0 }) {
  if (!(speed > 0) || !(segmentMs > 0) || !(segmentCount > 0)) throw new Error('a playhead needs a speed, a segment length and a count above 0');
  if (!(startupMs >= 0) || !Number.isInteger(startSegment) || startSegment < 0 || startSegment >= segmentCount) {
    throw new Error('a playhead needs a start segment inside the film and a start-up time of 0 or more');
  }
  const last = segmentCount - 1;
  const present = new Set();
  // Segments made and then deleted for being behind the playhead, and not made again: a request for one inside the
  // live run's span only waits (see the header), which is why it is told apart.
  const lost = new Set();
  let playhead = startSegment; // TranscodeSession.java:90-91: the playhead and the behind-deletion mark start at the run's
  let deletedBelow = startSegment;
  let restarts = 0;
  // `fight` is set when a request for a lost segment moved the playhead back: the run then gets stopped for being ahead.
  let run = { start: startSegment, startedAt: now(), done: 0, status: 'running', fight: false };

  // The most segments the run may make before the sweep stops it: the first whose newest-segment mark is past the limit.
  const cap = () => Math.floor(LOOK_AHEAD_MS / segmentMs) + playhead - run.start + 1;

  function deleteBehind() {
    const below = Math.floor(Math.max(0, playhead * segmentMs - BEHIND_MS) / segmentMs);
    for (let n = deletedBelow; n < below; n += 1) {
      if (present.delete(n)) lost.add(n);
    }
    deletedBelow = Math.max(deletedBelow, below);
  }

  // SessionManager.stop -> cleanPartial, :514-536: stopping a run deletes the file it was writing, and a segment is
  // complete only when the next one's file exists (:424-435), so the last segment it made is no longer complete.
  function halt() {
    if (run.done > 0) present.delete(run.start + run.done - 1);
  }

  // Brings the run up to the clock: segments made, the end of the part, the stop for running too far ahead.
  function advance() {
    if (run.status === 'running') {
      const wanted = Math.min(last + 1 - run.start, Math.floor((Math.max(0, now() - run.startedAt - startupMs) * speed) / segmentMs));
      const produced = Math.max(run.done, Math.min(wanted, cap()));
      for (let i = run.done; i < produced; i += 1) {
        present.add(run.start + i);
        lost.delete(run.start + i);
      }
      run.done = produced;
      if (run.start + run.done > last) {
        run.status = 'finished';
      } else if (run.done >= cap()) {
        run.status = 'stopped';
        halt();
      }
    }
    deleteBehind();
  }

  function follow(n) {
    const behind = (playhead - n) * segmentMs;
    if (behind < 0 || behind > BEHIND_MS) playhead = n;
  }

  // Whether the encoder, left alone, will reach segment n (the run's newest mark is the segment it is writing).
  function reaching(n) {
    return run.status === 'running' && n >= run.start && n <= run.start + run.done + WAIT_AHEAD_SEGMENTS;
  }

  function restart(n) {
    if (run.status === 'running') halt();
    playhead = n;
    for (let i = n; i <= last; i += 1) {
      present.delete(i);
      lost.delete(i);
    }
    deletedBelow = Math.min(deletedBelow, n);
    run = { start: n, startedAt: now(), done: 0, status: 'running', fight: false };
    restarts += 1;
  }

  return {
    /**
     * A request for segment n arrived: the playhead follows it, and the encoder is restarted if it will not reach the
     * segment. Returns `restart`: null when it was not, else why: 'stopped' (the encoder was not running: it had
     * finished, or been stopped for running 3 minutes past the playhead, which a player that watches on does every
     * few minutes), 'behind' (the segment is before the run's first: a seek back, or a fetcher behind the other) or
     * 'ahead' (more than 10 past its newest: a seek forward, or a fetcher ahead of the other), or 'fight-stop' (the
     * run had stopped after a request for a lost segment moved the playhead back: see `deleted`).
     * Returns `deleted: true` for a request for a segment that was made and then deleted for being behind the
     * playhead, inside the live run's span: no restart (that is the server's own rule), only a wait and then 503, the
     * player starving while the playhead is pulled back and the run is stopped for being ahead of it. A fight
     * between fetchers that shows in no restart until then.
     */
    request(n) {
      advance();
      follow(n);
      if (present.has(n)) return { restart: null };
      if (reaching(n)) {
        if (!lost.has(n)) return { restart: null };
        run.fight = true;
        return { restart: null, deleted: true };
      }
      let why = 'ahead';
      // A stop with a lost segment asked for is the fetchers' doing even if the run stopped before the player came back.
      if (run.status !== 'running') why = run.fight || lost.has(n) ? 'fight-stop' : 'stopped';
      else if (n < run.start) why = 'behind';
      restart(n);
      return { restart: why };
    },
    /** Whether segment n is made (and not deleted). */
    complete(n) {
      advance();
      return present.has(n);
    },
    /** Whether an encoder run is going. A request that waits is let go at once, with 503, when it is not. */
    running() {
      advance();
      return run.status === 'running';
    },
    /**
     * The milliseconds until the run makes segment n, 0 when it is made, null when the run never will (it is not
     * running, the segment is before its start, was made and deleted, or is past where it will be stopped).
     */
    readyInMs(n) {
      advance();
      if (present.has(n)) return 0;
      if (run.status !== 'running' || n < run.start + run.done || n > last) return null;
      const needed = n - run.start + 1;
      if (needed > cap()) return null;
      return Math.max(0, Math.ceil(startupMs + (needed * segmentMs) / speed - (now() - run.startedAt)));
    },
    /**
     * The milliseconds until the run stops (it makes the segment that puts it past the limit, or its last), 0 when it
     * has, null when it is not going to (it is already stopped). SessionManager.settled, :437-441, ends a wait then.
     */
    stopsInMs() {
      advance();
      if (run.status !== 'running') return null;
      const target = Math.min(cap(), last + 1 - run.start);
      return Math.max(0, Math.ceil(startupMs + (target * segmentMs) / speed - (now() - run.startedAt)));
    },
    /** The playhead's segment and how many times the encoder has been restarted. */
    state() {
      advance();
      return { playhead, restarts, runStart: run.start, status: run.status };
    },
  };
}
