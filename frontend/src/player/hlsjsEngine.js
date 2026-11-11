// Adaptive playback through hls.js (Chrome, Firefox). hls.js owns the buffer and the level choice; this adds
// the resilience rules from the transcoding plan: retries instead of failures, "Reconnecting…" with one
// restart 5 s after each fatal network error (at once when the browser comes back online), a new session when the old one has expired, and Auto stepping up one level only after ~60 s.
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

/** hls.js settings: a deep forward buffer (120 s of the copy rung fits in ~250 MB), a short back buffer. */
export function hlsConfig(startSeconds) {
  return {
    maxBufferLength: 120,
    maxMaxBufferLength: 120,
    maxBufferSize: 250 * 1000 * 1000,
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
 * onReconnecting(boolean), onSessionLost(), onFatal(), and the optional onNetworkError({ timeout }). Returns { destroy }.
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

  const cap = (level) => {
    if (level === applied) return;
    applied = level;
    hls.autoLevelCapping = level;
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

  const onWaiting = () => gate.trouble();
  video.addEventListener('waiting', onWaiting);

  hls.on(Events.MANIFEST_PARSED, (_event, data) => {
    top = data.levels.length - 1;
    if (auto) cap(gate.start(data.firstLevel));
  });

  hls.on(Events.LEVEL_SWITCHED, (_event, data) => {
    const rung = rungForLevel(session.renditions, hls.levels[data.level]);
    if (!rung) return;
    handlers.onLevel(rung, { initial });
    initial = false;
    if (auto) cap(gate.switched(data.level));
  });

  hls.on(Events.FRAG_LOADED, (_event, data) => {
    stopReconnecting();
    const kbps = fragmentKbps(data.frag?.stats);
    if (kbps) handlers.onThroughput(kbps);
    if (auto) cap(gate.tick(top));
  });

  hls.on(Events.ERROR, (_event, data) => {
    if (data.type === ErrorTypes.NETWORK_ERROR && !data.response?.code) {
      // A load that got no HTTP answer and failed or ran out of time, for the health record. A load aborted on
      // purpose, and a playlist that arrived but could not be read, are not counted.
      const details = String(data.details ?? '');
      if (details.endsWith('LoadTimeOut')) handlers.onNetworkError?.({ timeout: true });
      else if (details.endsWith('LoadError')) handlers.onNetworkError?.({ timeout: false });
    }
    if (data.details === ErrorDetails.FRAG_LOAD_TIMEOUT) gate.trouble();
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
      hls.destroy();
    },
  };
}
