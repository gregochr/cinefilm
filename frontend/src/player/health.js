// The playback-health counter: how one viewing went, as totals. Pure: no DOM, no React, no timers. The clock is
// injected (`now`, in ms), so every figure is exact in a test. Plan: docs/engineering/playback-health-plan.md.

/** Moving time is credited no further than this long after the last heartbeat that moved the playhead. */
export const BEAT_GRACE_MS = 1500;
/** A single step longer than this is taken to be a sleep and counts for nothing. */
export const MAX_STEP_MS = 150000;
export const MAX_MINUTES = 360;
export const MAX_SAMPLES = 4000;
export const MAX_NUMBER = 2000000000;

const MINUTE_MS = 60000;
const MAX_USER_AGENT = 300;
const MAX_OPERATOR = 100;
const ENVIRONMENTS = new Set(['home', 'train', 'away', 'unknown']);
const SCREEN_PATTERN = /^\d{1,5}x\d{1,5}$/;
const RUNG_CODES = { original: 1, '1080p': 2, '720p': 3, '480p': 4, '360p': 5, '240p': 6 };

/** A whole number from 0 to MAX_NUMBER; anything that is not a number is 0. */
function whole(value) {
  const n = Number(value);
  if (Number.isNaN(n)) return 0;
  return Math.min(MAX_NUMBER, Math.max(0, Math.round(n)));
}

const wholeOrNull = (value) => (value === null ? null : whole(value));

/** Nearest-rank percentile (`pct` a whole number from 1 to 100) of sorted values, or null with none. */
function percentile(sorted, pct) {
  if (sorted.length === 0) return null;
  return sorted[Math.ceil((pct * sorted.length) / 100) - 1];
}

const sortedCopy = (values) => [...values].sort((a, b) => a - b);

export function createHealth({ now, openedAt, editionId, client = {} }) {
  let last = openedAt; // the time of the last advance
  let changes = 0; // raised whenever anything a report shows changes (see version())
  let seq = 0;

  // The state the time between two calls is credited by. The picture counts as moving from playing() until
  // something stops it, but only while the playhead advances (see BEAT_GRACE_MS) and no seek the viewer asked
  // for is waiting: that wait is seek wait, not watched time.
  let moving = false;
  let stalled = false;
  let seeking = false;
  let reloading = false;
  let reconnectingNow = false;
  let lastBeatAt = openedAt; // moving is only ever true after playing(), which sets it
  let lastPosition = null;
  let waitingForViewer = false; // the browser refused to start the film: waiting for the viewer to press play
  let startingMs = 0; // time before the first frame, less the wait for the viewer and any sleep
  let stallCurrentMs = 0;
  let first = false;

  // The report's own fields.
  let environmentName = 'unknown';
  let operator = null;
  let latencyMs = null;
  let engineKind = null;
  let qualityId = 'auto';
  let autoplayRefused = false;
  let startupMs = null;
  let watchedMs = 0;
  let stallCount = 0;
  let stallMs = 0;
  let longestStallMs = 0;
  let seekCount = 0;
  let seekWaitMs = 0;
  let reloadCount = 0;
  let reloadMs = 0;
  let reconnectCount = 0;
  let reconnectMs = 0;
  let recoveries = 0;
  let fellBackToDirect = false;
  let stepsDown = 0;
  let stepsUp = 0;
  let netErrors = 0;
  let timeouts = 0;
  let samples = [];
  const rungMs = new Map();
  const minutes = new Map(); // minute -> { moved, stalled, samples, rung }
  let rungId = null;
  let rungHeight = null;

  const changed = () => {
    changes += 1;
  };

  /** The entry of minute `n`, or null when the cap on minutes is reached. */
  const minuteEntry = (n) => {
    let entry = minutes.get(n);
    if (!entry) {
      if (minutes.size >= MAX_MINUTES) return null;
      entry = { moved: 0, stalled: 0, samples: [], rung: 0 };
      minutes.set(n, entry);
    }
    return entry;
  };

  const minuteOf = (t) => Math.max(0, Math.floor((t - openedAt) / MINUTE_MS));

  /** Adds the time from `from` to `to` to `field` of each minute it covers (at most four: it is under 150 s). */
  const credit = (from, to, field) => {
    // Whichever kind of time it is, the minute is coded by the rung being played.
    for (let n = minuteOf(from); n <= minuteOf(to); n += 1) {
      const ms =
        Math.min(to, openedAt + (n + 1) * MINUTE_MS) - Math.max(from, openedAt + n * MINUTE_MS);
      const entry = ms > 0 ? minuteEntry(n) : null;
      if (entry) {
        entry[field] += ms;
        entry.rung = RUNG_CODES[rungId] ?? 0;
      }
    }
  };

  const advance = () => {
    const t = now();
    const dt = t - last;
    const from = last;
    last = t;
    if (!(dt > 0) || dt > MAX_STEP_MS) return t;
    // Start-up is counted like everything else, so a sleep is not in it. The step that ends at the first frame
    // counts (first is set after this runs); the step that ends at play() after a refusal does not.
    if (!first && !waitingForViewer) startingMs += dt;
    if (moving && !seeking) {
      const to = Math.min(t, lastBeatAt + BEAT_GRACE_MS);
      if (to > from) {
        watchedMs += to - from;
        if (rungId !== null) rungMs.set(rungId, (rungMs.get(rungId) ?? 0) + (to - from));
        credit(from, to, 'moved');
        changed();
      }
    }
    if (stalled) {
      stallMs += dt;
      stallCurrentMs += dt;
      longestStallMs = Math.max(longestStallMs, stallCurrentMs);
      credit(from, t, 'stalled');
      changed();
    }
    if (seeking) {
      seekWaitMs += dt;
      changed();
    }
    if (reloading) {
      reloadMs += dt;
      changed();
    }
    if (reconnectingNow) {
      reconnectMs += dt;
      changed();
    }
    return t;
  };

  const endStall = () => {
    stalled = false;
    stallCurrentMs = 0;
  };

  const heightDirection = (id, height) => {
    if (qualityId !== 'auto' || rungId === id) return;
    if (!Number.isFinite(height) || !Number.isFinite(rungHeight)) return;
    if (height < rungHeight) stepsDown += 1;
    else if (height > rungHeight) stepsUp += 1;
    else return;
    changed();
  };

  const minuteRows = () =>
    [...minutes.entries()]
      .sort((a, b) => a[0] - b[0])
      .map(([n, m]) => [
        n,
        whole(percentile(sortedCopy(m.samples), 50) ?? 0),
        m.rung,
        whole(m.moved),
        whole(m.stalled),
      ]);

  const build = (t) => {
    const sorted = sortedCopy(samples);
    const rungs = {};
    for (const [id, ms] of rungMs) rungs[id] = whole(ms);
    const screen = SCREEN_PATTERN.test(String(client.screen)) ? String(client.screen) : '0x0';
    return {
      seq,
      editionId: whole(editionId),
      elapsedMs: whole(t - openedAt),
      environment: ENVIRONMENTS.has(environmentName) ? environmentName : 'unknown',
      operator: typeof operator === 'string' ? operator.slice(0, MAX_OPERATOR) : null,
      latencyMs: wholeOrNull(latencyMs),
      engine: engineKind,
      quality: qualityId,
      autoplayRefused,
      startupMs: wholeOrNull(startupMs),
      watchedMs: whole(watchedMs),
      stallCount: whole(stallCount),
      stallMs: whole(stallMs),
      longestStallMs: whole(longestStallMs),
      seekCount: whole(seekCount),
      seekWaitMs: whole(seekWaitMs),
      reloadCount: whole(reloadCount),
      reloadMs: whole(reloadMs),
      reconnectCount: whole(reconnectCount),
      reconnectMs: whole(reconnectMs),
      recoveries: whole(recoveries),
      fellBack: fellBackToDirect,
      stepsDown: whole(stepsDown),
      stepsUp: whole(stepsUp),
      netErrors: whole(netErrors),
      timeouts: whole(timeouts),
      kbpsLow: wholeOrNull(percentile(sorted, 10)),
      kbpsMedian: wholeOrNull(percentile(sorted, 50)),
      kbpsHigh: wholeOrNull(percentile(sorted, 90)),
      kbpsSamples: whole(samples.length),
      rungMs: rungs,
      minutes: minuteRows(),
      client: {
        userAgent: String(client.userAgent ?? '').slice(0, MAX_USER_AGENT),
        screen,
        cores: Number.isFinite(client.cores) ? whole(client.cores) : null,
      },
    };
  };

  return {
    play() {
      advance();
      waitingForViewer = false;
    },
    playRefused() {
      advance();
      if (first) return;
      if (!autoplayRefused) {
        autoplayRefused = true;
        changed();
      }
      waitingForViewer = true;
    },
    playing() {
      const t = advance();
      if (!first) {
        first = true;
        waitingForViewer = false;
        startupMs = startingMs;
        changed();
      }
      moving = true;
      lastBeatAt = t;
      endStall();
      seeking = false;
      reloading = false;
    },
    canPlay(paused) {
      advance();
      if (reloading && paused) reloading = false;
    },
    paused() {
      advance();
      moving = false;
      endStall();
    },
    waiting() {
      advance();
      if (!first || reloading) return;
      moving = false;
      if (seeking || stalled) return;
      stalled = true;
      stallCount += 1;
      changed();
    },
    seek() {
      advance();
      if (!first || reloading) return;
      seekCount += 1;
      seeking = true;
      endStall();
      changed();
    },
    seeked() {
      const t = advance();
      // The wait is over and the picture goes on: the next beat interval starts now, not at a stale beat.
      if (seeking && moving) lastBeatAt = t;
      seeking = false;
    },
    ended() {
      advance();
      moving = false;
      endStall();
    },
    sourceChanged() {
      advance();
      if (!first) return;
      moving = false;
      endStall();
      seeking = false;
      if (!reloading) {
        reloading = true;
        reloadCount += 1;
        changed();
      }
    },
    beat(positionMs) {
      const t = advance();
      if (!Number.isFinite(positionMs) || positionMs === lastPosition) return;
      lastPosition = positionMs;
      lastBeatAt = t;
    },
    rung(id, height) {
      advance();
      heightDirection(id, height);
      rungId = id;
      rungHeight = Number.isFinite(height) ? height : null;
    },
    quality(id) {
      advance();
      if (qualityId === id) return;
      qualityId = id;
      // A new choice is not an automatic step: the next rung starts from nothing.
      rungHeight = null;
      changed();
    },
    engine(kind) {
      advance();
      if (engineKind === kind) return;
      engineKind = kind;
      changed();
    },
    environment(value = {}) {
      advance();
      const next = value.environment ?? 'unknown';
      const nextOperator = value.operator ?? null;
      const nextLatency = value.latencyMs ?? null;
      if (next === environmentName && nextOperator === operator && nextLatency === latencyMs)
        return;
      environmentName = next;
      operator = nextOperator;
      latencyMs = nextLatency;
      changed();
    },
    reconnecting(on) {
      advance();
      const next = Boolean(on);
      if (next === reconnectingNow) return;
      reconnectingNow = next;
      if (!next) return;
      reconnectCount += 1;
      changed();
    },
    recovered() {
      // Counted when the rebuild begins: the expired session is the event, whether or not a new one can be made.
      advance();
      recoveries += 1;
      changed();
    },
    fellBack() {
      advance();
      if (fellBackToDirect) return;
      fellBackToDirect = true;
      changed();
    },
    networkError({ timeout } = {}) {
      advance();
      if (timeout) timeouts += 1;
      else netErrors += 1;
      changed();
    },
    throughput(kbps) {
      const t = advance();
      if (!Number.isFinite(kbps) || kbps <= 0) return;
      samples = [...samples, kbps].slice(-MAX_SAMPLES);
      const entry = minuteEntry(minuteOf(t));
      if (entry) entry.samples = [...entry.samples, kbps].slice(-MAX_SAMPLES);
      changed();
    },
    started: () => first,
    version() {
      advance();
      return changes;
    },
    snapshot() {
      const t = advance();
      seq += 1;
      return { report: build(t), version: changes };
    },
  };
}
