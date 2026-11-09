// Native HLS (Safari on Mac and iPhone). Safari adapts by itself and cannot be gated, so this only reads which
// rung is playing (from the video height) and recovers when playback is really stuck. `stalled` alone is not
// a failure: it fires when fetching pauses while buffered video still plays.
import { rungForHeight } from './ladder.js';

export const STALL_MS = 15000;
export const RELOAD_SPACING_MS = 5000;
export const POLL_MS = 1000;
export const CHECK_TIMEOUT_MS = 10000;

/** Seconds of video buffered ahead of the playhead (0 when the playhead is outside every range). */
export function bufferAhead(video) {
  const { buffered, currentTime } = video;
  for (let i = 0; i < buffered.length; i += 1) {
    if (buffered.start(i) <= currentTime + 0.5 && buffered.end(i) >= currentTime) {
      return buffered.end(i) - currentTime;
    }
  }
  return 0;
}

/**
 * Starts `session` on `video`. `handlers`: onLevel(rung, { initial }), onReconnecting(boolean),
 * onReload(positionMs) (called just before the source is reloaded, so the caller can seek back),
 * onSessionLost(). Returns { destroy }.
 */
export function startNative({
  video,
  session,
  handlers,
  fetchImpl = globalThis.fetch,
  now = () => Date.now(),
  stallMs = STALL_MS,
  spacingMs = RELOAD_SPACING_MS,
  pollMs = POLL_MS,
  checkTimeoutMs = CHECK_TIMEOUT_MS,
}) {
  let rung = null;
  let lastTime = video.currentTime;
  let lastMoveAt = now();
  let lastReloadAt = -Infinity;
  let resumeMs = 0;
  let errored = false;
  let reconnecting = false;
  let destroyed = false;
  // One recovery check at a time. `generation` changes when playback resumes, so a response that arrives late
  // (for trouble that is already over) is ignored.
  let generation = 0;
  let controller = null;

  const detectLevel = () => {
    const next = rungForHeight(session.renditions, video.videoHeight);
    if (!next || next === rung) return;
    handlers.onLevel(next, { initial: rung === null });
    rung = next;
  };

  const onTimeUpdate = () => {
    if (video.currentTime === lastTime) return;
    lastTime = video.currentTime;
    lastMoveAt = now();
    if (reconnecting) {
      reconnecting = false;
      generation += 1;
      controller?.abort();
      controller = null;
      handlers.onReconnecting(false);
    }
  };
  const onError = () => {
    errored = true;
    check();
  };

  async function reload() {
    if (controller) return; // a check is already in flight
    lastReloadAt = now();
    errored = false;
    if (!reconnecting) {
      reconnecting = true;
      resumeMs = Math.round(video.currentTime * 1000);
      handlers.onReconnecting(true);
    }
    const mine = generation;
    const own = new AbortController();
    controller = own;
    const timer = setTimeout(() => own.abort(), checkTimeoutMs);
    let status;
    try {
      status = (await fetchImpl(session.masterUrl, { credentials: 'same-origin', signal: own.signal })).status;
    } catch {
      return; // Offline or timed out: the next check tries again.
    } finally {
      clearTimeout(timer);
      if (controller === own) controller = null;
    }
    if (destroyed || mine !== generation || !reconnecting) return; // stale, or playback already resumed
    if (status === 404) {
      handlers.onSessionLost();
      return;
    }
    if (status >= 400) return;
    // Never rewind behind where playback has got to.
    handlers.onReload(Math.max(resumeMs, Math.round(video.currentTime * 1000)));
    video.src = session.masterUrl;
    video.load();
  }

  function check() {
    if (destroyed || now() - lastReloadAt < spacingMs) return;
    const stuck =
      !video.paused && !video.ended && now() - lastMoveAt >= stallMs && bufferAhead(video) <= 0.5;
    if (errored || reconnecting || stuck) reload();
  }

  video.addEventListener('timeupdate', onTimeUpdate);
  video.addEventListener('resize', detectLevel);
  video.addEventListener('loadedmetadata', detectLevel);
  video.addEventListener('error', onError);
  const poll = setInterval(check, pollMs);
  video.src = session.masterUrl;

  return {
    destroy() {
      destroyed = true;
      clearInterval(poll);
      video.removeEventListener('timeupdate', onTimeUpdate);
      video.removeEventListener('resize', detectLevel);
      video.removeEventListener('loadedmetadata', detectLevel);
      video.removeEventListener('error', onError);
    },
  };
}
