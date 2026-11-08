import { afterEach, describe, expect, it, vi } from 'vitest';
import { pickFullscreenStrategy, toggleFullscreen } from './fullscreen.js';

const IPHONE = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X)';
const MAC = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)';

describe('pickFullscreenStrategy (decision 10)', () => {
  it.each([
    ['iPhone with the native player', IPHONE, true, true, 'native-video'],
    ['iPod with the native player', 'Mozilla/5.0 (iPod touch)', true, false, 'native-video'],
    ['iPhone without the native player', IPHONE, false, true, 'container'],
    ['desktop with both', MAC, true, true, 'container'],
    ['desktop with only the container API', MAC, false, true, 'container'],
    ['iPad-style: only the native player', MAC, true, false, 'native-video'],
    ['nothing available', MAC, false, false, 'none'],
  ])('%s', (_name, userAgent, hasWebkitEnter, hasRequest, expected) => {
    expect(pickFullscreenStrategy({ userAgent, hasWebkitEnter, hasRequest })).toBe(expected);
  });

  it('treats a missing user agent as not an iPhone', () => {
    expect(pickFullscreenStrategy({ hasWebkitEnter: true, hasRequest: true })).toBe('container');
  });
});

describe('toggleFullscreen', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    delete document.fullscreenElement;
    delete document.exitFullscreen;
  });

  const setUserAgent = (ua) => vi.stubGlobal('navigator', { ...navigator, userAgent: ua });

  it('exits when something is already fullscreen', () => {
    document.exitFullscreen = vi.fn();
    Object.defineProperty(document, 'fullscreenElement', { value: {}, configurable: true });
    const container = { requestFullscreen: vi.fn() };
    toggleFullscreen(container, {});
    expect(document.exitFullscreen).toHaveBeenCalledTimes(1);
    expect(container.requestFullscreen).not.toHaveBeenCalled();
  });

  it('copes with a browser that cannot exit fullscreen', () => {
    Object.defineProperty(document, 'fullscreenElement', { value: {}, configurable: true });
    expect(() => toggleFullscreen({}, {})).not.toThrow();
  });

  it('fullscreens the container on desktop', () => {
    setUserAgent(MAC);
    const container = { requestFullscreen: vi.fn() };
    const video = { webkitEnterFullscreen: vi.fn() };
    toggleFullscreen(container, video);
    expect(container.requestFullscreen).toHaveBeenCalledTimes(1);
    expect(video.webkitEnterFullscreen).not.toHaveBeenCalled();
  });

  it('hands the video to the native player on iPhone', () => {
    setUserAgent(IPHONE);
    const container = { requestFullscreen: vi.fn() };
    const video = { webkitEnterFullscreen: vi.fn() };
    toggleFullscreen(container, video);
    expect(video.webkitEnterFullscreen).toHaveBeenCalledTimes(1);
    expect(container.requestFullscreen).not.toHaveBeenCalled();
  });

  it('does nothing when there is no way to go fullscreen', () => {
    setUserAgent(MAC);
    expect(() => toggleFullscreen({}, {})).not.toThrow();
    expect(() => toggleFullscreen(null, undefined)).not.toThrow();
  });
});
