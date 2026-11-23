// Adaptive playback through hls.js (Chrome, Firefox). hls.js owns the buffer and the level choice; this adds
// the resilience rules from the transcoding plan: retries instead of failures, "Reconnecting…" with one
// restart 5 s after each fatal network error (at once when the browser comes back online), a new session when the old one has expired, and Auto stepping up one level only after ~60 s. The step gate follows the level hls.js
// starts to FETCH (LEVEL_SWITCHING), so a step down caps Auto at once; LEVEL_SWITCHED, the level on screen,
// only reports what is playing. The cap rises only while hls.js is fetching at the cap, and a fragment that
// times out or gets no answer pulls it down to the level being fetched. While the buffer is deep (floorHold.js)
// Auto may not go below 360p on a ladder with a 240p bottom rung: `minAutoBitrate` is raised to the 360p level,
// but only once hls.js is fetching at 360p or above and not after fragment trouble (a floor, not a pull). The
// hold reads the engine's own fragment rates, timed from `loading.start`, and the rate of the fragment in flight,
// re-read every HOLD_CHECK_MS (floor-hold-by-time-plan.md). Above 480p Auto climbs only on headroom
// (champagne.js, step-up-bank-plan.md): the cap is the lower of the step gate and that ceiling, worked out in
// one place (`applyCap`) whatever the ladder.
import { bankedAhead } from './buffered.js';
import { champagneBase, champagneCeiling } from './champagne.js';
import { createFloorHold } from './floorHold.js';
import { rungForLevel } from './ladder.js';
import { createNoProgressLoader } from './noProgressLoader.js';
import { createStepGate } from './stepGate.js';
import { addSample, fragmentKbps, lowerMiddle, networkGeneration } from './throughput.js';

export const RECONNECT_MS = 5000;
// How often the rate of the fragment in flight is read for the floor hold, in ms.
export const HOLD_CHECK_MS = 2000;
// A fragment in flight counts for the release only once it has been receiving this long, in ms, counted from
// its first byte: a cold first byte can take seconds, and a fragment that stays silent is ended as trouble.
export const IN_FLIGHT_MIN_AGE_MS = 4000;
// How many of the newest fragment rates the release reads.
const RELEASE_SAMPLES = 3;

// kbit/s of one loaded fragment, timed from `loading.start` so the wait for the first byte counts; null when
// there is nothing to measure.
function loadedKbps(stats) {
  const start = stats?.loading?.start;
  const ms = stats?.loading?.end - start;
  return stats?.loaded > 0 && Number.isFinite(start) && ms > 0
    ? Math.round((stats.loaded * 8) / ms)
    : null;
}

const retries = (max) => ({
  maxNumRetry: max,
  retryDelayMs: 1000,
  maxRetryDelayMs: 8000,
  backoff: 'exponential',
});

/**
 * hls.js's `shouldRetry` hook: its own answer (`retry`), or a request that got no HTTP answer at all (status 0)
 * while retries remain, whatever `navigator.onLine` says: it is retried with backoff like a 5xx instead of
 * costing a rung. Timeouts and HTTP answers keep hls.js's decision.
 */
export function retryNoAnswer(retryConfig, retryCount, isTimeout, loaderResponse, retry) {
  return Boolean(
    retry || (!isTimeout && loaderResponse?.code === 0 && retryCount < retryConfig.maxNumRetry),
  );
}

// A 503 with Retry-After: 1 (the encoder catching up) is an error response, so errorRetry retries it.
const policy = (ttfbMs, loadMs, maxRetries) => ({
  default: {
    maxTimeToFirstByteMs: ttfbMs,
    maxLoadTimeMs: loadMs,
    timeoutRetry: retries(maxRetries),
    errorRetry: { ...retries(maxRetries), shouldRetry: retryNoAnswer },
  },
});

// The least film kept ahead on any level, in seconds (hls.js's `maxBufferLength`).
export const MIN_BUFFER_AHEAD_S = 120;
// The most film kept ahead on any level, in seconds (hls.js's `maxMaxBufferLength`).
export const MAX_BUFFER_AHEAD_S = 480;
// The byte budget between the two, in bytes (hls.js's `maxBufferSize`).
export const BUFFER_BUDGET_BYTES = 100 * 1000 * 1000;

/**
 * hls.js settings. The forward buffer is as deep as the byte budget allows at the level's peak bitrate, never
 * under MIN_BUFFER_AHEAD_S and never over MAX_BUFFER_AHEAD_S: eight minutes on the levels whose eight minutes
 * fit in 100 MB (240p, 360p, 480p), about 256 s at 720p, about 130 s at a 1080p transcode, and 120 s (the
 * original) on heavy originals. A deep buffer on the low levels banks film while the link is good. If the
 * browser's buffer fills, hls.js lowers its own ceiling and carries on.
 */
export function hlsConfig(startSeconds) {
  return {
    maxBufferLength: MIN_BUFFER_AHEAD_S,
    maxMaxBufferLength: MAX_BUFFER_AHEAD_S,
    maxBufferSize: BUFFER_BUDGET_BYTES,
    backBufferLength: 30,
    // Standard MediaSource only: iOS (ManagedMediaSource) plays natively instead.
    preferManagedMediaSource: false,
    startPosition: startSeconds > 0 ? startSeconds : -1,
    // A rung penalised by a fatal-looking blip may be chosen again after the minute the step gate asks for.
    errorPenaltyExpireMs: 60000,
    // A segment may take 120 s (hls.js's default); the no-progress loader ends one that has gone silent sooner.
    fragLoadPolicy: policy(10000, 120000, 8),
    playlistLoadPolicy: policy(10000, 20000, 6),
    manifestLoadPolicy: policy(10000, 20000, 4),
  };
}

/**
 * Starts `session` on `video`. `handlers`: onLevel(rung, { initial }), onThroughput(kbps),
 * onReconnecting(boolean), onSessionLost(), onFatal(), and the optional onStopped() (a 410: Admin stopped this
 * playback, so nothing is recovered), onNetworkError({ timeout }) and
 * onFetching(rung) (the rung hls.js has started to fetch, ahead of the one being played). In Auto, on a ladder
 * with a 240p bottom and a 360p level, a deep buffer holds Auto at 360p or above while it is fetching there
 * and has had no fragment trouble; the cap never climbs above the level being fetched by more than one.
 * Returns { destroy }.
 */
export function startHlsJs({
  Hls,
  video,
  session,
  auto,
  startSeconds,
  handlers,
  now = () => Date.now(),
  clock = () => performance.now(), // the clock hls.js stamps `loading.start` with
  reconnectMs = RECONNECT_MS,
}) {
  const config = hlsConfig(startSeconds);
  const hls = new Hls({
    ...config,
    // `fLoader` is set here, not in `hlsConfig`, because only here is `Hls` at hand.
    fLoader: createNoProgressLoader(Hls.DefaultConfig.loader),
  });
  const gate = createStepGate({ now });
  const { Events, ErrorTypes, ErrorDetails } = Hls;
  let top = 0;
  let applied = null; // the cap on hls.js: the lower of the step gate's level and the champagne ceiling
  let gateCap = null; // the step gate's own level
  let ceiling = 0; // the champagne ceiling last worked out
  let initial = true;
  let lost = false;
  let reconnecting = false;
  let timer = null; // the one pending restart, if any
  const hold = createFloorHold();
  let holdLevel = null; // the 360p level of a ladder with a 240p bottom (the only kind that is held)
  let floorSet = 0;
  let fetched = null; // the level hls.js is fetching (LEVEL_SWITCHING), null until the first
  let troubled = false; // a fragment timed out or got no answer, and none has loaded since
  let samples = []; // the newest fragment rates (kbit/s) for the hold, under `samplesNetwork`
  let headroom = []; // the newest first-byte fragment rates (kbit/s), retried fragments left out
  let samplesNetwork = networkGeneration();
  let inFlight = null; // the fragment hls.js is loading, whose rate so far the release reads
  let holdTimer = null; // the one check of the in-flight rate, running while a fragment loads

  // The samples, emptied first when the network has changed since they were taken.
  const currentSamples = () => {
    if (samplesNetwork !== networkGeneration()) {
      samples = [];
      headroom = [];
      ceiling = champagneBase(hls.levels); // nothing measured on this link has earned a rung above 480p
      samplesNetwork = networkGeneration();
      hold.reset(); // the link it was armed on is gone
    }
    return samples;
  };

  // The rate of the fragment in flight since its first byte, null when none is loading, when no byte has come
  // yet (noProgressLoader and hls.js's first-byte limit end a silent fragment as trouble) or when the transfer
  // is younger than IN_FLIGHT_MIN_AGE_MS.
  const inFlightKbps = () => {
    const stats = inFlight?.stats;
    const first = stats?.loading?.first;
    if (!stats || stats.aborted || stats.loading.end > 0 || !(first > 0) || !(stats.loaded > 0)) {
      return null;
    }
    const ms = clock() - first;
    return ms >= IN_FLIGHT_MIN_AGE_MS ? (stats.loaded * 8) / ms : null;
  };

  const stopHoldTimer = () => {
    clearInterval(holdTimer);
    holdTimer = null;
    inFlight = null;
  };

  // Raises hls.js's minimum bitrate to the 360p level while the buffer is deep, the cap allows 360p, hls.js is
  // already fetching at 360p or above and no fragment is in trouble.
  const reevaluateHold = () => {
    if (holdLevel === null) return;
    const bitrate = hls.levels[holdLevel]?.bitrate ?? 0; // bit/s, the variant's peak
    const rateKbps = bitrate / 1000;
    const recent = currentSamples();
    const flying = inFlightKbps();
    const armed = hold.update({
      aheadS: bankedAhead(video),
      armKbps: lowerMiddle(recent),
      releaseKbps: Math.min(...recent.slice(-RELEASE_SAMPLES), flying ?? Infinity),
      rateKbps,
    });
    const engaged =
      armed &&
      !troubled &&
      applied !== null &&
      applied >= holdLevel &&
      fetched !== null &&
      fetched >= holdLevel;
    const floor = engaged ? bitrate : 0;
    if (floor === floorSet) return;
    floorSet = floor;
    hls.config.minAutoBitrate = floor;
  };

  // The one place the cap is worked out: the step gate's level, never above the champagne ceiling. A gate level
  // above the ceiling is pulled down to it, so a ceiling that comes back climbs a rung at a time.
  const applyCap = () => {
    if (auto && gateCap !== null) {
      currentSamples();
      ceiling = champagneCeiling({
        levels: hls.levels,
        heldCeiling: ceiling,
        aheadS: bankedAhead(video),
        kbps: lowerMiddle(headroom),
        troubled,
        bank: {
          budgetBytes: BUFFER_BUDGET_BYTES,
          minS: MIN_BUFFER_AHEAD_S,
          maxS: MAX_BUFFER_AHEAD_S,
        },
      });
      if (gateCap > ceiling) gateCap = gate.switched(ceiling);
      const level = Math.min(gateCap, ceiling);
      if (level !== applied) {
        applied = level;
        hls.autoLevelCapping = level;
      }
    }
    reevaluateHold();
  };

  // The step gate moved to `level` (or is asked again): the cap follows.
  const cap = (level) => {
    gateCap = level;
    applyCap();
  };

  const stopReconnecting = () => {
    clearTimeout(timer);
    timer = null;
    if (!reconnecting) return;
    reconnecting = false;
    handlers.onReconnecting(false);
  };

  // At the playhead once the video has metadata; before that it reads 0, so a resumed film restarts where it
  // was asked to start (-1 loads from the beginning).
  const restart = () => {
    clearTimeout(timer);
    timer = null;
    stopHoldTimer(); // the fragment in flight is aborted by the reload
    if (hls.levels?.length) {
      hls.startLoad(video.readyState > 0 ? video.currentTime : config.startPosition);
    } else {
      hls.loadSource(session.masterUrl);
    }
  };

  // One restart per fatal network error, `reconnectMs` later, replacing one already pending. Both `startLoad`
  // and `loadSource` abort the request the one before made, so nothing is scheduled again until the next
  // fatal error: hls.js's own backoff does the retrying in between.
  const beginReconnecting = () => {
    clearTimeout(timer);
    if (!reconnecting) {
      reconnecting = true;
      handlers.onReconnecting(true);
    }
    timer = setTimeout(restart, reconnectMs);
  };

  // The connection is back: a pending restart need not wait out its 5 s.
  const onOnline = () => {
    if (timer !== null) restart();
  };
  window.addEventListener('online', onOnline);

  const onWaiting = () => {
    gate.trouble();
    reevaluateHold();
  };
  video.addEventListener('waiting', onWaiting);
  video.addEventListener('timeupdate', applyCap);
  video.addEventListener('seeking', applyCap);

  hls.on(Events.MANIFEST_PARSED, (_event, data) => {
    top = data.levels.length - 1;
    const index = data.levels.findIndex((l) => rungForLevel(session.renditions, l) === '360p');
    const qualifies =
      auto && index > 0 && rungForLevel(session.renditions, data.levels[0]) === '240p';
    holdLevel = qualifies ? index : null;
    if (auto) {
      // The playlist lists the session's start rung first, and a remembered fast speed could start at 720p or
      // above with an empty bank: the start is clamped to the ceiling.
      ceiling = champagneBase(data.levels);
      cap(gate.start(Math.min(data.firstLevel, ceiling)));
    }
  });

  hls.on(Events.LEVEL_SWITCHED, (_event, data) => {
    const rung = rungForLevel(session.renditions, hls.levels[data.level]);
    if (!rung) return;
    handlers.onLevel(rung, { initial });
    initial = false;
  });

  // The level hls.js now fetches: the gate follows it, so a step down lowers the cap at once.
  hls.on(Events.LEVEL_SWITCHING, (_event, data) => {
    // hls.js has come up from a lower level to the cap: the healthy minute starts now.
    const arrived = auto && fetched !== null && fetched < data.level && data.level === applied;
    fetched = data.level;
    const rung = rungForLevel(session.renditions, hls.levels[data.level]);
    if (!rung) return;
    handlers.onFetching?.(rung);
    if (arrived) gate.arrived();
    if (auto) cap(gate.switched(data.level));
    reevaluateHold();
  });

  // A fragment starts to load (in Auto): the hold and the ceiling are re-read every HOLD_CHECK_MS until it ends, the
  // hold with the rate of the fragment so far. An init segment is a
  // few bytes behind a round trip, so it says nothing about the link.
  hls.on(Events.FRAG_LOADING, (_event, data) => {
    if (!auto || data.frag?.sn === 'initSegment') return;
    inFlight = data.frag;
    holdTimer ??= setInterval(applyCap, HOLD_CHECK_MS);
  });

  // hls.js gave up on the fragment for a faster level: nothing is in flight any more.
  hls.on(Events.FRAG_LOAD_EMERGENCY_ABORTED, stopHoldTimer);

  hls.on(Events.FRAG_LOADED, (_event, data) => {
    stopReconnecting();
    troubled = false;
    stopHoldTimer();
    const kbps = fragmentKbps(data.frag?.stats);
    if (kbps) handlers.onThroughput(kbps);
    if (data.frag?.sn !== 'initSegment') {
      currentSamples();
      samples = addSample(samples, loadedKbps(data.frag?.stats));
      // A fragment that needed a retry says nothing about the link's rate.
      if (!(data.frag?.stats?.retry > 0)) headroom = addSample(headroom, kbps);
    }
    applyCap();
    // The cap rises only while hls.js is fetching at it, so it is never more than a level above the fetch, and
    // the step gate never runs ahead of the ceiling.
    if (auto && (fetched === null || fetched >= applied) && gateCap < ceiling) cap(gate.tick(top));
  });

  hls.on(Events.ERROR, (_event, data) => {
    if (data.type === ErrorTypes.NETWORK_ERROR && !data.response?.code) {
      // A load that got no HTTP answer and failed or ran out of time, for the health record. A load aborted on
      // purpose, and a playlist that arrived but could not be read, are not counted.
      const details = String(data.details ?? '');
      if (details.endsWith('LoadTimeOut')) handlers.onNetworkError?.({ timeout: true });
      else if (details.endsWith('LoadError')) handlers.onNetworkError?.({ timeout: false });
    }
    // Fragment trouble: a timeout, or an error with no HTTP answer (a 503 while the encoder catches up is not).
    if (
      data.details === ErrorDetails.FRAG_LOAD_TIMEOUT ||
      (data.details === ErrorDetails.FRAG_LOAD_ERROR && !data.response?.code)
    ) {
      gate.trouble();
      if (auto && fetched !== null) gateCap = gate.switched(fetched);
      troubled = true;
      samples = []; // what was measured before the trouble says nothing about the link now
      headroom = [];
      hold.reset();
      stopHoldTimer();
      applyCap();
    }
    if (data.response?.code === 410) {
      // Admin stopped this playback on purpose: no new session, no retry (owner 2026-11-15).
      if (!lost) {
        lost = true;
        handlers.onStopped?.();
      }
      return;
    }
    if (data.response?.code === 404) {
      // The session expired (or was replaced): the caller makes a new one at the current position.
      if (!lost) {
        lost = true;
        handlers.onSessionLost();
      }
      return;
    }
    if (!data.fatal) return;
    if (data.type === ErrorTypes.NETWORK_ERROR) beginReconnecting();
    else if (data.type === ErrorTypes.MEDIA_ERROR) hls.recoverMediaError();
    else handlers.onFatal();
  });

  hls.loadSource(session.masterUrl);
  hls.attachMedia(video);

  return {
    destroy() {
      clearTimeout(timer);
      timer = null;
      stopHoldTimer();
      window.removeEventListener('online', onOnline);
      video.removeEventListener('waiting', onWaiting);
      video.removeEventListener('timeupdate', applyCap);
      video.removeEventListener('seeking', applyCap);
      hls.destroy();
    },
  };
}
