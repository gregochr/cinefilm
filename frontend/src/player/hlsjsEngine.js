// Adaptive playback through hls.js (Chrome, Firefox). hls.js owns the buffer and the level choice; this adds
// the resilience rules from the transcoding plan: retries instead of failures, "Reconnecting…" with a retry
// every 5 s, a new session when the old one has expired, and Auto stepping up one level only after ~60 s.
import { rungForLevel } from './ladder.js';
import { createStepGate } from './stepGate.js';
import { fragmentKbps } from './throughput.js';

export const RECONNECT_MS = 5000;

const retries = (max) => ({
  maxNumRetry: max,
  retryDelayMs: 1000,
  maxRetryDelayMs: 8000,
  backoff: 'exponential',
});
// A 503 with Retry-After: 1 (the encoder catching up) is an error response, so errorRetry retries it.
const policy = (ttfbMs, maxRetries) => ({
  default: {
    maxTimeToFirstByteMs: ttfbMs,
    maxLoadTimeMs: ttfbMs * 2,
    timeoutRetry: retries(maxRetries),
    errorRetry: retries(maxRetries),
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
    fragLoadPolicy: policy(10000, 8),
    playlistLoadPolicy: policy(10000, 6),
    manifestLoadPolicy: policy(10000, 4),
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
  const hls = new Hls(hlsConfig(startSeconds));
  const gate = createStepGate({ now });
  const { Events, ErrorTypes } = Hls;
  let top = 0;
  let applied = null;
  let initial = true;
  let lost = false;
  let reconnecting = false;
  let timer = null;

  const cap = (level) => {
    if (level === applied) return;
    applied = level;
    hls.autoLevelCapping = level;
  };

  const stopReconnecting = () => {
    clearTimeout(timer);
    if (!reconnecting) return;
    reconnecting = false;
    handlers.onReconnecting(false);
  };

  const retryLater = () => {
    timer = setTimeout(() => {
      if (hls.levels?.length) hls.startLoad(video.currentTime);
      else hls.loadSource(session.masterUrl);
      retryLater();
    }, reconnectMs);
  };

  const beginReconnecting = () => {
    clearTimeout(timer);
    if (!reconnecting) {
      reconnecting = true;
      handlers.onReconnecting(true);
    }
    retryLater();
  };

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
    // A network error that got no HTTP answer at all (an error with a status is not counted), for the health record.
    if (data.type === ErrorTypes.NETWORK_ERROR && !data.response?.code) {
      handlers.onNetworkError?.({ timeout: String(data.details ?? '').endsWith('TimeOut') });
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
      video.removeEventListener('waiting', onWaiting);
      hls.destroy();
    },
  };
}
