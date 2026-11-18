import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  exitFullscreen,
  leaveNativeFullscreen,
  pickFullscreenStrategy,
  toggleFullscreen,
} from './fullscreen.js';

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

describe('leaveNativeFullscreen', () => {
  it('exits Safari’s native player when it is showing', () => {
    const video = { webkitDisplayingFullscreen: true, webkitExitFullscreen: vi.fn() };

    leaveNativeFullscreen(video);

    expect(video.webkitExitFullscreen).toHaveBeenCalledTimes(1);
  });

  it('does nothing when the native player is not showing', () => {
    const video = { webkitDisplayingFullscreen: false, webkitExitFullscreen: vi.fn() };

    leaveNativeFullscreen(video);

    expect(video.webkitExitFullscreen).not.toHaveBeenCalled();
  });

  it('does not throw without the native API, or without a video', () => {
    expect(() => leaveNativeFullscreen({ webkitDisplayingFullscreen: true })).not.toThrow();
    expect(() => leaveNativeFullscreen({})).not.toThrow();
    expect(() => leaveNativeFullscreen(null)).not.toThrow();
  });
});

describe('exitFullscreen', () => {
  afterEach(() => {
    delete document.exitFullscreen;
  });

  it('asks the document to exit fullscreen', () => {
    document.exitFullscreen = vi.fn();

    exitFullscreen();

    expect(document.exitFullscreen).toHaveBeenCalledTimes(1);
  });

  it('copes with a browser that has no exitFullscreen', () => {
    expect(() => exitFullscreen()).not.toThrow();
  });

  it('copes with an exitFullscreen that answers nothing, or something that is not a promise', () => {
    document.exitFullscreen = vi.fn(() => ({}));
    expect(() => exitFullscreen()).not.toThrow();
    document.exitFullscreen = vi.fn(() => undefined);
    expect(() => exitFullscreen()).not.toThrow();
  });

  it('swallows the rejection of a page that was not fullscreen after all', () => {
    const rejection = Promise.reject(new TypeError('Document not active'));
    const catchSpy = vi.spyOn(rejection, 'catch');
    document.exitFullscreen = vi.fn(() => rejection);

    exitFullscreen();

    expect(catchSpy).toHaveBeenCalledTimes(1);
  });
});
