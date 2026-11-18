/** iPhone hands fullscreen to Safari's native player (decision 10); everywhere else uses the container. */
export function pickFullscreenStrategy({ userAgent = '', hasWebkitEnter, hasRequest }) {
  const iPhone = /iPhone|iPod/.test(userAgent);
  if (iPhone && hasWebkitEnter) return 'native-video';
  if (hasRequest) return 'container';
  if (hasWebkitEnter) return 'native-video';
  return 'none';
}

/**
 * Leaves fullscreen when the browser can. `exitFullscreen` is missing on some browsers and answers a promise on others
 * (it rejects when the page is not fullscreen any more): neither is the caller's business.
 */
export function exitFullscreen() {
  const result = document.exitFullscreen?.();
  if (typeof result?.catch === 'function') result.catch(() => {});
}

/** Toggles fullscreen using the right strategy. DOM access lives here in the player layer. */
export function toggleFullscreen(container, video) {
  if (document.fullscreenElement) {
    exitFullscreen();
    return;
  }
  const strategy = pickFullscreenStrategy({
    userAgent: navigator.userAgent,
    hasWebkitEnter: typeof video?.webkitEnterFullscreen === 'function',
    hasRequest: typeof container?.requestFullscreen === 'function',
  });
  if (strategy === 'native-video') video.webkitEnterFullscreen();
  else if (strategy === 'container') container.requestFullscreen();
}

/**
 * Leaves Safari's native fullscreen player (iPhone, `toggleFullscreen`'s `webkitEnterFullscreen`) when it is showing:
 * a card of the page (Admin stopped the playback, nothing to play) would be under it, out of sight and out of reach.
 * Container fullscreen holds the cards inside it and needs nothing.
 */
export function leaveNativeFullscreen(video) {
  if (video?.webkitDisplayingFullscreen) video.webkitExitFullscreen?.();
}
