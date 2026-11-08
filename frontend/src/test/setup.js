import '@testing-library/jest-dom/vitest';
import { afterEach } from 'vitest';
import { cleanup } from '@testing-library/react';

afterEach(() => {
  cleanup();
});

// jsdom does not implement media playback.
if (typeof window !== 'undefined') {
  window.HTMLMediaElement.prototype.play = function play() {
    this.dispatchEvent(new Event('play'));
    return Promise.resolve();
  };
  window.HTMLMediaElement.prototype.pause = function pause() {
    this.dispatchEvent(new Event('pause'));
  };
  window.HTMLMediaElement.prototype.load = function load() {};
}
