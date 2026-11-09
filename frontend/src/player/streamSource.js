// Chooses how a session plays: Safari's own HLS, hls.js (lazy-loaded), or direct play as the fallback.
import { startHlsJs } from './hlsjsEngine.js';
import { startNative } from './nativeEngine.js';

export const HLS_MIME = 'application/vnd.apple.mpegurl';

const loadHlsJs = async () => (await import('hls.js')).default;

/**
 * Starts `session` on `video` and returns the running engine ({ destroy }), or null when this browser can
 * play no HLS (the caller direct-plays the file). Native HLS wins where the browser has it (Safari, so the
 * iPhone keeps its native fullscreen); otherwise hls.js when the browser supports Media Source.
 */
export async function startStream({
  video,
  session,
  auto,
  startSeconds,
  handlers,
  loadHls = loadHlsJs,
  ...timing
}) {
  if (video.canPlayType(HLS_MIME)) return startNative({ video, session, handlers, ...timing });
  let Hls;
  try {
    Hls = await loadHls();
  } catch {
    return null;
  }
  if (!Hls.isSupported()) return null;
  return startHlsJs({ Hls, video, session, auto, startSeconds, handlers, ...timing });
}
