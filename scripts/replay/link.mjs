// The link of a replay: one allowance shared by everything the player asks for.
//
// It is pure arithmetic. It holds no timer and reads no clock: whoever drives it says how many milliseconds have
// passed since the last `advance`, and the rate it is refilled at comes from the function it was made with. That
// keeps its rules testable with made-up times and no waiting; standin.mjs wires it to a 10 ms timer.
//
// The rules:
//  - Each advance adds `rate / 8 x ms` bytes to what is saved. Nothing is saved while the rate is 0 (and what was
//    saved is forgotten), and never more than a twentieth of a second of the rate, or 3,000 bytes on a very slow
//    link, is kept for later.
//  - What is saved is handed to the jobs sharing the link, equally, with an odd byte going to the first; one send
//    is never more than 64 KB.
//  - A job is anything with `left()` (bytes still to send) and `send(n)`. One that is added is served at once from
//    what is saved, and one with nothing left leaves.

const SAVE_SECONDS = 0.05;
const SAVE_FLOOR_BYTES = 3000;
const SEND_LIMIT = 65536;

/** The replay minute `elapsedMs` after the clock started at record minute `startMinute`. */
export function minuteAt(startMinute, elapsedMs) {
  return startMinute + Math.floor(elapsedMs / 60000);
}

/**
 * Counts bytes by replay minute: `add(ms, bytes)` puts `bytes` in the minute `Math.floor(ms / 60000)` of the replay
 * clock, and `list()` gives a copy of the counts from minute 0 to the last minute with any, a minute in between
 * with nothing counted being 0. A time that is not a finite number of 0 or more, or bytes that are not above 0, are
 * ignored. Pure arithmetic: it reads no clock.
 */
export function createByteCounter() {
  const places = [];
  return {
    add(ms, bytes) {
      if (!Number.isFinite(ms) || ms < 0 || !(bytes > 0)) return;
      const minute = Math.floor(ms / 60000);
      while (places.length <= minute) places.push(0);
      places[minute] += bytes;
    },
    list: () => [...places],
  };
}

/**
 * The trace's figure in kbit/s for `minute`: the last figure when the replay has run past the end of the trace, 0
 * for a minute that has none.
 */
export function rateAt(trace, minute) {
  return trace[Math.min(minute, trace.length - 1)] ?? 0;
}

/**
 * A link whose rate in bits a second is `rate()`, asked at every advance. Returns `add(job)`, `remove(job)`,
 * `whenAlive(callback)` (which returns a function that cancels it), `advance(ms)` and `size()`.
 */
export function createLink(rate) {
  const jobs = new Set();
  let waiting = [];
  let saved = 0;

  // Serves one job its share of what is saved and returns the bytes moved. A job with nothing left leaves.
  function serve(job, share) {
    const n = Math.min(share, job.left(), Math.floor(saved), SEND_LIMIT);
    if (n > 0) {
      job.send(n);
      saved -= n;
    }
    if (job.left() <= 0) jobs.delete(job);
    return Math.max(n, 0);
  }

  // Hands out what is saved, round after round, until it is gone or nobody can take any.
  function pump() {
    while (saved >= 1 && jobs.size > 0) {
      const share = Math.max(1, Math.floor(saved / jobs.size));
      let moved = 0;
      for (const job of [...jobs]) moved += serve(job, share);
      if (moved === 0) break;
    }
  }

  function refill(ms) {
    const bytesPerSecond = rate() / 8;
    if (bytesPerSecond > 0) {
      const most = Math.max(bytesPerSecond * SAVE_SECONDS, SAVE_FLOOR_BYTES);
      saved = Math.min(saved + (bytesPerSecond * ms) / 1000, most);
    } else {
      saved = 0;
    }
  }

  function wake() {
    if (rate() > 0) {
      const ready = waiting;
      waiting = [];
      for (const callback of ready) callback();
    }
  }

  return {
    add(job) {
      jobs.add(job);
      pump();
    },
    remove(job) {
      jobs.delete(job);
    },
    whenAlive(callback) {
      if (rate() > 0) {
        callback();
        return () => {
          // nothing was waiting, so there is nothing to cancel
        };
      }
      const entry = () => callback();
      waiting.push(entry);
      return () => {
        waiting = waiting.filter((other) => other !== entry);
      };
    },
    advance(ms) {
      refill(ms);
      pump();
      wake();
    },
    size: () => jobs.size,
  };
}
