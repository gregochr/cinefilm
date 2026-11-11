import '@testing-library/jest-dom/vitest';
import { afterEach } from 'vitest';
import { cleanup, configure } from '@testing-library/react';
import { clearResourceCache } from '../state/resourceCache.js';

// Whole-suite runs (and CI) are slow; a screen that loads two resources needs more than the 1 s default.
configure({ asyncUtilTimeout: 5000 });

afterEach(() => {
  cleanup();
  clearResourceCache(); // no test sees another's answers
});

// jsdom does not implement media playback: track `paused` ourselves so play() and pause() behave
// like a real element (state change plus the matching event).
if (typeof window !== 'undefined') {
  const pausedState = new WeakMap();
  Object.defineProperty(window.HTMLMediaElement.prototype, 'paused', {
    configurable: true,
    get() {
      return pausedState.get(this) ?? true;
    },
  });
  window.HTMLMediaElement.prototype.play = function play() {
    pausedState.set(this, false);
    this.dispatchEvent(new Event('play'));
    return Promise.resolve();
  };
  window.HTMLMediaElement.prototype.pause = function pause() {
    if (this.paused) return;
    pausedState.set(this, true);
    this.dispatchEvent(new Event('pause'));
  };
  window.HTMLMediaElement.prototype.load = function load() {};
}
