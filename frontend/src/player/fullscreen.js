/** iPhone hands fullscreen to Safari's native player (decision 10); everywhere else uses the container. */
export function pickFullscreenStrategy({ userAgent = '', hasWebkitEnter, hasRequest }) {
  const iPhone = /iPhone|iPod/.test(userAgent);
  if (iPhone && hasWebkitEnter) return 'native-video';
  if (hasRequest) return 'container';
  if (hasWebkitEnter) return 'native-video';
  return 'none';
}

/** Toggles fullscreen using the right strategy. DOM access lives here in the player layer. */
export function toggleFullscreen(container, video) {
  if (document.fullscreenElement) {
    document.exitFullscreen?.();
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
