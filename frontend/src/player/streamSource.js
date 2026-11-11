// Chooses how a session plays: hls.js (lazy-loaded) wherever Media Source exists, the browser's own HLS on iPhone,
// or direct play as the fallback.
import { startHlsJs } from './hlsjsEngine.js';
import { startNative } from './nativeEngine.js';
import { backoffMs } from './retry.js';

export const HLS_MIME = 'application/vnd.apple.mpegurl';

// The light build of hls.js (about 65 KB smaller gzipped). It lacks alternate audio and subtitle renditions, EME,
// CMCD, interstitials, I-frame playlists, variable substitution, Media Capabilities, and HEVC and AC-3 in MPEG-TS;
// CineFilm sends H.264 + AAC in TS under a master of plain `#EXT-X-STREAM-INF` variants, and its subtitles are
// `<track>` VTT. An HEVC copy rung, AC-3 passthrough in TS segments or `#EXT-X-MEDIA` renditions would need the
// full `hls.js` build again.
const loadHlsJs = async () => (await import('hls.js/light')).default;
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

// Loads hls.js. A failed load asks whether the server answers at all (a retried `import()` fails at once in
// Safari, so waiting would never help while the server is up): if it does, one more try and then null (the
// caller direct-plays); if not, waits (backoff, woken early by `wait`) and tries again, without a cap, under
// "Reconnecting…". Null too when cancelled.
async function loadWithRetry({ loadHls, reachable, onRetrying, wait, isCancelled }) {
  let retrying = false;
  const say = (on) => {
    if (on === retrying || isCancelled()) return;
    retrying = on;
    onRetrying(on);
  };
  for (let failures = 0; ; failures += 1) {
    try {
      const Hls = await loadHls();
      say(false);
      return Hls;
    } catch {
      // Fall through to ask the server.
    }
    if (isCancelled()) return null;
    const answers = await reachable();
    if (isCancelled()) return null;
    if (answers) {
      say(false);
      try {
        return await loadHls();
      } catch {
        return null;
      }
    }
    say(true);
    await wait(backoffMs(failures));
    if (isCancelled()) return null;
  }
}

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
 * Starts `session` on `video` and returns the running engine ({ destroy, kind: 'hlsjs' | 'native' }), or null when this browser can
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
  reachable = async () => true,
  onRetrying = () => {},
  wait = sleep,
  ...timing
}) {
  const hasMediaSource = typeof window.MediaSource !== 'undefined';
  const nativeHls = Boolean(video.canPlayType(HLS_MIME));
  if (!hasMediaSource) {
    if (chooseEngine({ hasMediaSource, hlsSupported: false, nativeHls }) !== 'native') return null;
    return { ...startNative({ video, session, handlers, ...timing }), kind: 'native' };
  }
  const Hls = await loadWithRetry({ loadHls, reachable, onRetrying, wait, isCancelled });
  if (!Hls) return null;
  if (isCancelled()) return null; // The viewer moved on while hls.js loaded: attach nothing.
  if (chooseEngine({ hasMediaSource, hlsSupported: Hls.isSupported(), nativeHls }) !== 'hlsjs')
    return null;
  return {
    ...startHlsJs({ Hls, video, session, auto, startSeconds, handlers, ...timing }),
    kind: 'hlsjs',
  };
}
