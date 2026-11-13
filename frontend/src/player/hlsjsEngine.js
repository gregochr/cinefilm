// Adaptive playback through hls.js (Chrome, Firefox). hls.js owns the buffer and the level choice; this adds
// the resilience rules from the transcoding plan: retries instead of failures, "Reconnecting…" with one
// restart 5 s after each fatal network error (at once when the browser comes back online), a new session when the old one has expired, and Auto stepping up one level only after ~60 s. The step gate follows the level hls.js
// starts to FETCH (LEVEL_SWITCHING), so a step down caps Auto at once; LEVEL_SWITCHED, the level on screen,
// only reports what is playing. The cap rises only while hls.js is fetching at the cap, and a fragment that
// times out or gets no answer pulls it down to the level being fetched. While the buffer is deep (floorHold.js)
// Auto may not go below 360p on a ladder with a 240p bottom rung: `minAutoBitrate` is raised to the 360p level,
// but only once hls.js is fetching at 360p or above and not after fragment trouble (a floor, not a pull).
import { bankedAhead } from './buffered.js';
import { createFloorHold } from './floorHold.js';
import { rungForLevel } from './ladder.js';
import { createNoProgressLoader } from './noProgressLoader.js';
import { createStepGate } from './stepGate.js';
import { fragmentKbps } from './throughput.js';

export const RECONNECT_MS = 5000;

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
 * onReconnecting(boolean), onSessionLost(), onFatal(), and the optional onNetworkError({ timeout }) and
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
  let applied = null;
  let initial = true;
  let lost = false;
  let reconnecting = false;
  let timer = null; // the one pending restart, if any
  const hold = createFloorHold();
  let holdLevel = null; // the 360p level of a ladder with a 240p bottom (the only kind that is held)
  let floorSet = 0;
  let fetched = null; // the level hls.js is fetching (LEVEL_SWITCHING), null until the first
  let troubled = false; // a fragment timed out or got no answer, and none has loaded since

  // Raises hls.js's minimum bitrate to the 360p level while the buffer is deep, the cap allows 360p, hls.js is
  // already fetching at 360p or above and no fragment is in trouble.
  const reevaluateHold = () => {
    if (holdLevel === null) return;
    const armed = hold.update(bankedAhead(video));
    const engaged =
      armed &&
      !troubled &&
      applied !== null &&
      applied >= holdLevel &&
      fetched !== null &&
      fetched >= holdLevel;
    const floor = engaged ? (hls.levels[holdLevel]?.bitrate ?? 0) : 0;
    if (floor === floorSet) return;
    floorSet = floor;
    hls.config.minAutoBitrate = floor;
  };

  const cap = (level) => {
    if (level === applied) return;
    applied = level;
    hls.autoLevelCapping = level;
    reevaluateHold();
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
  video.addEventListener('timeupdate', reevaluateHold);
  video.addEventListener('seeking', reevaluateHold);

  hls.on(Events.MANIFEST_PARSED, (_event, data) => {
    top = data.levels.length - 1;
    const index = data.levels.findIndex((l) => rungForLevel(session.renditions, l) === '360p');
    const qualifies =
      auto && index > 0 && rungForLevel(session.renditions, data.levels[0]) === '240p';
    holdLevel = qualifies ? index : null;
    if (auto) cap(gate.start(data.firstLevel));
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

  hls.on(Events.FRAG_LOADED, (_event, data) => {
    stopReconnecting();
    troubled = false;
    const kbps = fragmentKbps(data.frag?.stats);
    if (kbps) handlers.onThroughput(kbps);
    // The cap rises only while hls.js is fetching at it, so it is never more than a level above the fetch.
    if (auto && (fetched === null || fetched >= applied)) cap(gate.tick(top));
    reevaluateHold();
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
      if (auto && fetched !== null) cap(gate.switched(fetched));
      troubled = true;
      reevaluateHold();
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
      window.removeEventListener('online', onOnline);
      video.removeEventListener('waiting', onWaiting);
      video.removeEventListener('timeupdate', reevaluateHold);
      video.removeEventListener('seeking', reevaluateHold);
      hls.destroy();
    },
  };
}
