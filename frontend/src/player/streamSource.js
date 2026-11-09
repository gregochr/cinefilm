// Chooses how a session plays: hls.js (lazy-loaded) wherever Media Source exists, the browser's own HLS on iPhone,
// or direct play as the fallback.
import { startHlsJs } from './hlsjsEngine.js';
import { startNative } from './nativeEngine.js';

export const HLS_MIME = 'application/vnd.apple.mpegurl';

const loadHlsJs = async () => (await import('hls.js')).default;

/**
 * Which engine plays HLS. hls.js wherever the standard Media Source exists and hls.js supports it (Chrome, Firefox,
 * Edge, desktop Safari: our ABR, buffer and reconnect policy apply, and Chrome may claim native HLS too); the
 * browser's own HLS only where there is no Media Source (iPhone Safari: AirPlay, native fullscreen, power).
 */
export function chooseEngine({ hasMediaSource, hlsSupported, nativeHls }) {
  if (hasMediaSource) return hlsSupported ? 'hlsjs' : null;
  return nativeHls ? 'native' : null;
}

/**
 * Starts `session` on `video` and returns the running engine ({ destroy }), or null when this browser can
 * play no HLS (the caller direct-plays the file). `isCancelled` is checked once hls.js has loaded: when it
 * says so, nothing is attached and the result is null.
 */
export async function startStream({
  video,
  session,
  auto,
  startSeconds,
  handlers,
  loadHls = loadHlsJs,
  isCancelled = () => false,
  ...timing
}) {
  const hasMediaSource = typeof window.MediaSource !== 'undefined';
  const nativeHls = Boolean(video.canPlayType(HLS_MIME));
  if (!hasMediaSource) {
    if (chooseEngine({ hasMediaSource, hlsSupported: false, nativeHls }) !== 'native') return null;
    return startNative({ video, session, handlers, ...timing });
  }
  let Hls;
  try {
    Hls = await loadHls();
  } catch {
    return null;
  }
  if (isCancelled()) return null; // The viewer moved on while hls.js loaded: attach nothing.
  if (chooseEngine({ hasMediaSource, hlsSupported: Hls.isSupported(), nativeHls }) !== 'hlsjs') return null;
  return startHlsJs({ Hls, video, session, auto, startSeconds, handlers, ...timing });
}
