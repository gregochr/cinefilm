// The film bank's store (outside-bank-plan.md, decisions 1 and 9): whole segments in page memory, one copy per
// sequence number at the rung it was fetched at, plus the media playlist text of each rung. Pure: no hls.js, no
// fetch, no timers.

export const BANK_LIMIT_BYTES = 600 * 1024 * 1024;
// A segment is dropped once the playhead is this far past its end; hls.js's back buffer is the same length.
export const KEEP_BEHIND_S = 30;
// Playlist times are floats summed from durations; two edges closer than this are the same edge.
const EDGE_EPSILON_S = 1e-3;

/**
 * `putSegment({ seq, rung, start, end, bytes, timings })`: `start` and `end` are film seconds, `bytes` an
 * ArrayBuffer, `timings` `{ start, first, end }` of the last fetch attempt. A later put of the same `seq`
 * replaces the earlier copy. A put is always accepted, over the limit too; `atLimit()` tells the front to stop.
 * The bank copies the bytes on the way in and on the way out: the caller's buffer may be transferred to hls.js's
 * worker (which detaches it) and must not affect what is held. `getSegment(seq)` hands out a fresh copy each
 * time, never the stored buffer.
 */
export function createSegmentBank({ limitBytes = BANK_LIMIT_BYTES } = {}) {
  const segments = new Map();
  const playlists = new Map();
  let heldBytes = 0;

  function drop(seq) {
    const gone = segments.get(seq);
    heldBytes -= gone.size + (gone.boundary?.size ?? 0);
    segments.delete(seq);
  }

  // The contiguous run (by sequence number) of held segments that starts with the one holding film second `t`;
  // empty when no held segment holds it.
  function runHolding(t) {
    let first = null;
    for (const s of segments.values()) {
      if (s.start <= t + EDGE_EPSILON_S && t < s.end - EDGE_EPSILON_S) first = s;
    }
    if (first === null) return [];
    const run = [first];
    for (
      let next = segments.get(first.seq + 1);
      next !== undefined;
      next = segments.get(next.seq + 1)
    ) {
      run.push(next);
    }
    return run;
  }

  function maxEnd() {
    let end = -Infinity;
    for (const s of segments.values()) end = Math.max(end, s.end);
    return end;
  }

  function dropBehind(playhead) {
    for (const s of [...segments.values()]) {
      if (s.end < playhead - KEEP_BEHIND_S - EDGE_EPSILON_S) drop(s.seq);
    }
  }

  return {
    putSegment({ seq, rung, start, end, bytes, timings }) {
      if (segments.has(seq)) drop(seq);
      const own = bytes.slice(0);
      segments.set(seq, {
        seq,
        rung,
        start,
        end,
        bytes: own,
        size: own.byteLength,
        timings,
        boundary: null,
      });
      heldBytes += own.byteLength;
    },
    /**
     * A boundary copy: the held segment `seq` also at a second rung, fetched after the first segment of a new rung
     * because hls.js, on a change of level, asks again for the segment before it at the new level. At most one per
     * segment (a later one replaces it); counted in `heldBytes` and dropped with its segment. Ignored when `seq` is
     * not held or is held at `rung` already.
     */
    putBoundary(seq, rung, bytes, timings) {
      const s = segments.get(seq);
      if (s === undefined || s.rung === rung) return;
      heldBytes -= s.boundary?.size ?? 0;
      const own = bytes.slice(0);
      s.boundary = { rung, bytes: own, size: own.byteLength, timings };
      heldBytes += own.byteLength;
    },
    /** A fresh copy of the boundary copy of `seq` at `rung` (as `getSegment`, at that rung); null when none. */
    getBoundary(seq, rung) {
      const s = segments.get(seq);
      if (s === undefined || s.boundary?.rung !== rung) return null;
      return {
        seq: s.seq,
        rung,
        start: s.start,
        end: s.end,
        bytes: s.boundary.bytes.slice(0),
        timings: { ...s.boundary.timings },
      };
    },
    /** The rung of the boundary copy of `seq`; null when it has none. */
    boundaryRungOf: (seq) => segments.get(seq)?.boundary?.rung ?? null,
    hasSegment: (seq) => segments.has(seq),
    /** The rung of the held segment `seq`, without copying its bytes; null when it is not held. */
    rungOf: (seq) => segments.get(seq)?.rung ?? null,
    /** True when some held segment has a sequence number above `seq`. */
    hasSegmentAbove(seq) {
      for (const s of segments.keys()) if (s > seq) return true;
      return false;
    },
    getSegment(seq) {
      const s = segments.get(seq);
      if (s === undefined) return null;
      return {
        seq: s.seq,
        rung: s.rung,
        start: s.start,
        end: s.end,
        bytes: s.bytes.slice(0),
        timings: { ...s.timings },
      };
    },
    putPlaylist(rung, text) {
      playlists.set(rung, text);
    },
    getPlaylist: (rung) => playlists.get(rung) ?? null,
    allPlaylistsHeld: (rungs) => rungs.every((r) => playlists.has(r)),
    heldBytes: () => heldBytes,
    atLimit: () => heldBytes >= limitBytes,
    /** Drops each segment that ends more than KEEP_BEHIND_S before the playhead. */
    dropBehind,
    /**
     * A seek to `target`. Beyond everything held (or nothing held): returns 'beyond' and changes nothing, the
     * caller clears. Otherwise returns 'inside': a seek forward drops what ends more than KEEP_BEHIND_S before
     * the target; a seek back drops nothing, so everything held ahead is kept.
     */
    seek(target) {
      if (segments.size === 0 || target >= maxEnd() - EDGE_EPSILON_S) return 'beyond';
      dropBehind(target);
      return 'inside';
    },
    clear() {
      segments.clear();
      heldBytes = 0;
    },
    /**
     * True only when held segments cover film without a gap from `playhead` − KEEP_BEHIND_S (not below 0) to
     * `bufferedEnd`, hls.js's buffered end.
     */
    covers(playhead, bufferedEnd) {
      const run = runHolding(Math.max(0, playhead - KEEP_BEHIND_S));
      return run.length > 0 && run[run.length - 1].end >= bufferedEnd - EDGE_EPSILON_S;
    },
    /** The rung of the held segment holding film second `t`; null when none does. */
    rungAt: (t) => runHolding(t)[0]?.rung ?? null,
    /** The sequence number after the contiguous held run holding film second `t`; null when `t` is not held. */
    nextAfterRun(t) {
      const run = runHolding(t);
      return run.length === 0 ? null : run[run.length - 1].seq + 1;
    },
    /** Seconds of film held without a gap from film second `t` to the end of its run; 0 when `t` is not held. */
    filmAheadHeld(t) {
      const run = runHolding(t);
      return run.length === 0 ? 0 : run[run.length - 1].end - t;
    },
  };
}
