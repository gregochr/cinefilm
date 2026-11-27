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
 * `getSegment(seq)` hands out a fresh copy of the bytes each time (hls.js transfers its payload to a worker,
 * which detaches it), never the stored buffer.
 */
export function createSegmentBank({ limitBytes = BANK_LIMIT_BYTES } = {}) {
  const segments = new Map();
  const playlists = new Map();
  let heldBytes = 0;

  function drop(seq) {
    heldBytes -= segments.get(seq).bytes.byteLength;
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
      segments.set(seq, { seq, rung, start, end, bytes, timings });
      heldBytes += bytes.byteLength;
    },
    hasSegment: (seq) => segments.has(seq),
    getSegment(seq) {
      const s = segments.get(seq);
      if (s === undefined) return null;
      return { ...s, bytes: s.bytes.slice(0), timings: { ...s.timings } };
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
