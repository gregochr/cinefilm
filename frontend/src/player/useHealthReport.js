import { useEffect, useRef, useState } from 'react';
import { healthApi } from '../api/resources.js';
import { createHealth } from './health.js';

/** After this long without a first frame, the viewing is recorded anyway. */
export const START_GRACE_MS = 20000;
/** How often a changed counter is sent. */
export const REPORT_INTERVAL_MS = 60000;
/** A timed send that has not finished by now is aborted. */
export const SEND_TIMEOUT_MS = 15000;

// The counter's inputs: what the player and the stream may call. Each is wrapped so it can never throw.
const INPUTS = [
  'play',
  'playRefused',
  'canPlay',
  'paused',
  'waiting',
  'seek',
  'seeking',
  'seeked',
  'ended',
  'sourceChanged',
  'beat',
  'rung',
  'quality',
  'engine',
  'environment',
  'reconnecting',
  'recovered',
  'fellBack',
  'networkError',
  'throughput',
];

const HEX = '0123456789abcdef';

/**
 * A random lower-case UUID: `crypto.randomUUID()`, else a version 4 one built from `crypto.getRandomValues`, else
 * null (no id, so no recording). Both exist on every HTTPS page and on localhost.
 */
export function newPlaybackId() {
  try {
    return crypto.randomUUID();
  } catch {
    // no randomUUID here (an insecure page): build one from random bytes
  }
  let bytes;
  try {
    bytes = crypto.getRandomValues(new Uint8Array(16));
  } catch {
    return null;
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  return [...bytes]
    .map((b, i) => `${[4, 6, 8, 10].includes(i) ? '-' : ''}${HEX[b >> 4]}${HEX[b & 15]}`)
    .join('');
}

const browserClient = () => ({
  userAgent: globalThis.navigator?.userAgent ?? '',
  screen: globalThis.screen ? `${globalThis.screen.width}x${globalThis.screen.height}` : '',
  cores: globalThis.navigator?.hardwareConcurrency ?? null,
});

const defaultNow = () => performance.now();

const noop = () => {};
// The recorder when it cannot be made: the same inputs, all doing nothing.
const inertViewing = () => ({
  id: null,
  counter: null,
  api: Object.fromEntries([...INPUTS, 'playing'].map((name) => [name, noop])),
  lastOk: 0,
  warned: false,
});

function build({ editionId, openedAt, now, makeId, sendNowRef }) {
  const id = makeId();
  if (!id) return inertViewing();
  const counter = createHealth({ now, openedAt, editionId, client: browserClient() });
  const api = {};
  for (const name of INPUTS) {
    api[name] = (...args) => {
      try {
        counter[name](...args);
      } catch {
        // Recording must never get in the way of the film.
      }
    };
  }
  // The first frame is the one input that also sends.
  api.playing = () => {
    try {
      const had = counter.started();
      counter.playing();
      if (!had && counter.started()) sendNowRef.current?.();
    } catch {
      // as above
    }
  };
  // What the sends read; the player never sees these.
  return { id, counter, api, lastOk: 0, warned: false };
}

// Runs while the player renders, so nothing here may throw: it would blank the whole app.
function create(args) {
  try {
    return build(args);
  } catch {
    return inertViewing();
  }
}

/**
 * Keeps one health record per viewing: a random id, the counter, and the sends (`PUT /api/playback-health/{id}`).
 * Returns one stable object of the counter's inputs, none of which can throw. Recording never sets state.
 */
export function useHealthReport({
  editionId,
  quality,
  openedAt,
  now = defaultNow,
  makeId = newPlaybackId,
}) {
  const sendNowRef = useRef(null);
  const [viewing] = useState(() => create({ editionId, openedAt, now, makeId, sendNowRef }));
  const { api } = viewing;

  useEffect(() => {
    api.quality(quality);
  }, [api, quality]);

  useEffect(() => {
    const { id, counter } = viewing;
    if (!id) return undefined; // no recorder: no timer, no listener, no send
    let closed = false;
    const timed = new Set(); // sends that can be aborted: { controller, timer }
    let graceTimer = null;
    let intervalTimer = null;

    const post = async (snapshot, options, entry) => {
      try {
        await healthApi.report(id, snapshot.report, options);
        viewing.lastOk = Math.max(viewing.lastOk, snapshot.version);
      } catch (error) {
        if (!closed && error?.status === 400 && !viewing.warned) {
          viewing.warned = true;
          console.warn('CineFilm: the server rejected a playback health report (400).');
        }
      } finally {
        if (entry) {
          clearTimeout(entry.timer);
          timed.delete(entry);
        }
      }
    };

    const sendTimed = () => {
      if (closed) return;
      try {
        const snapshot = counter.snapshot();
        const controller = new AbortController();
        const entry = { controller, timer: null };
        entry.timer = setTimeout(() => {
          timed.delete(entry);
          controller.abort();
        }, SEND_TIMEOUT_MS);
        timed.add(entry);
        void post(snapshot, { signal: controller.signal }, entry);
      } catch {
        // never throws into the player
      }
    };

    const sendExit = () => {
      try {
        if (counter.version() === viewing.lastOk) return;
        void post(counter.snapshot(), { keepalive: true }, null);
      } catch {
        // never throws into the player
      }
    };

    const onVisibility = () => {
      if (document.visibilityState === 'hidden') sendExit();
    };

    sendNowRef.current = sendTimed;
    graceTimer = setTimeout(() => {
      try {
        if (!closed && !counter.started()) sendTimed();
      } catch {
        // never throws
      }
    }, START_GRACE_MS);
    intervalTimer = setInterval(() => {
      try {
        if (!closed && timed.size === 0 && counter.version() !== viewing.lastOk) sendTimed();
      } catch {
        // never throws
      }
    }, REPORT_INTERVAL_MS);
    window.addEventListener('pagehide', sendExit);
    document.addEventListener('visibilitychange', onVisibility);

    return () => {
      sendExit();
      closed = true;
      sendNowRef.current = null;
      clearTimeout(graceTimer);
      clearInterval(intervalTimer);
      for (const entry of timed) {
        clearTimeout(entry.timer);
        entry.controller.abort();
      }
      window.removeEventListener('pagehide', sendExit);
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, [viewing, editionId]);

  return api;
}
