import { fireEvent } from '@testing-library/react';

const TRACK_LOADED = 2;
const TRACK_ERROR = 3;

const finish = (element, readyState, fire) => {
  Object.defineProperty(element, 'readyState', { configurable: true, value: readyState });
  fire(element);
};

/**
 * Finishes a `<track>` the way a browser does: its own `readyState` becomes LOADED (2) first, then `load` is
 * dispatched. jsdom leaves `readyState` at 0, and the player's effect, which attaches its listeners after the
 * element appears, reads `readyState` to catch a track that finished before it listened; a bare `fireEvent.load`
 * fired in that gap would be lost.
 */
export const loadTrack = (element) => finish(element, TRACK_LOADED, fireEvent.load);

/** Fails a `<track>` the way a browser does: `readyState` becomes ERROR (3) first, then `error` is dispatched. */
export const failTrack = (element) => finish(element, TRACK_ERROR, fireEvent.error);
