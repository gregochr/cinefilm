import { useCallback, useEffect, useRef, useState } from 'react';
import { editionsApi, sessionApi, sessionsApi } from '../api/resources.js';
import { AUTO, ORIGINAL, levelToast, workerOffline } from './ladder.js';
import { REACH_TIMEOUT_MS, createSession } from './createSession.js';
import { measureThroughput } from './probe.js';
import { createWaiter, reachable } from './retry.js';
import { startStream } from './streamSource.js';
import {
  addSample,
  lowerMiddle,
  median,
  networkGeneration,
  REMEMBER_MS,
  recallSpeed,
  rememberSpeed,
} from './throughput.js';

// What `useStream` calls when no health recorder is given: every other caller and test is unaffected.
const noop = () => {};
const NO_HEALTH = {
  rung: noop,
  throughput: noop,
  reconnecting: noop,
  recovered: noop,
  engine: noop,
  fellBack: noop,
  networkError: noop,
};

const heightOf = (renditions, id) => renditions.find((r) => r.id === id)?.height ?? null;

/** More than this many expired-session recoveries inside the window means the server is not keeping sessions. */
export const MAX_RECOVERIES = 3;
export const RECOVERY_WINDOW_MS = 60000;

/**
 * Owns how the edition reaches the `<video>`: creates the HLS session for the chosen quality (Auto probes the
 * link first), attaches hls.js or Safari's native player, recovers an expired session at the current
 * position, and falls back to direct play when there is no encoder, no index or no HLS in the browser. A film
 * split across several files (`edition.parts` above 1) has no single file to direct-play: where a film in one
 * piece would fall back, it reports `mode: 'unavailable'` instead, and a session the server is still preparing
 * (503) is asked for again.
 *
 * `pendingSeekRef` holds the film position (ms) to seek to once the new source has loaded metadata; the
 * caller applies it and clears it. Returns the active rung, its renditions, the reconnecting flag, the median
 * measured throughput and `restart()` (a new session at the current position, e.g. after an audio change).
 */
export function useStream({
  videoRef,
  edition,
  quality,
  audioStreamId,
  getPositionMs,
  initialSeekMs,
  onToast,
  health = NO_HEALTH,
}) {
  const [stream, setStream] = useState({ mode: 'loading', rung: null, renditions: [] });
  const [reconnecting, setReconnecting] = useState(false);
  // The samples live in a ref (read by the effect's run, never inside a state updater) and are mirrored to state
  // for the render; `network` is the network counter they were taken under.
  const [samples, setSamples] = useState([]);
  const [reusedKbps, setReusedKbps] = useState(null);
  const samplesRef = useRef({ list: [], network: networkGeneration(), at: 0 });
  const [generation, setGeneration] = useState(0);
  const pendingSeekRef = useRef(initialSeekMs > 0 ? initialSeekMs : null);
  const startedRef = useRef(false);
  const recoveriesRef = useRef([]);
  const sessionRef = useRef(null);
  // Whether the new source should start playing once it has metadata: true for the first load (autoplay), and
  // after a restart or reload whatever the viewer had at that moment (a paused film stays paused).
  const resumeRef = useRef({ play: true, held: false });
  const latest = useRef({ edition, audioStreamId, getPositionMs, onToast, health });
  useEffect(() => {
    latest.current = { edition, audioStreamId, getPositionMs, onToast, health };
  });

  const editionId = edition.id;
  useEffect(() => {
    const video = videoRef.current;
    let cancelled = false;
    let engine = null;
    let session = null;
    let rung = null;
    // One switch per run for "Reconnecting…", acting only on a change: the probe's "unreachable", the session
    // request's retries and the engine's own are one episode. A cancelled run changes nothing.
    let noticeOn = false;
    const setNotice = (on) => {
      if (cancelled || on === noticeOn) return;
      noticeOn = on;
      setReconnecting(on);
      latest.current.health.reconnecting(on);
    };
    // The notice ends (or never began): the counter hears about it wherever the flag is reset.
    const clearReconnecting = () => {
      noticeOn = false;
      setReconnecting(false);
      latest.current.health.reconnecting(false);
    };
    clearReconnecting();

    const holdPlayState = () => {
      // Only the first change counts: a source swapped out before its metadata arrived reads paused, wrongly.
      if (resumeRef.current.held) return;
      resumeRef.current = { play: !video.paused, held: true };
    };
    const currentPosition = () => Math.round(latest.current.getPositionMs());
    const dropSession = (id) => sessionsApi.remove(id).catch(() => {});
    const forget = () => {
      if (session) dropSession(session.sessionId);
      session = null;
      sessionRef.current = null;
    };

    const split = (latest.current.edition.parts ?? 1) > 1;
    const toDirect = () => {
      if (split) {
        setStream({ mode: 'unavailable', rung: null, renditions: [] });
        clearReconnecting();
        return;
      }
      const position = currentPosition();
      if (position > 0) pendingSeekRef.current = position;
      setStream({ mode: 'direct', rung: ORIGINAL, renditions: [] });
      clearReconnecting();
      video.src = editionsApi.streamUrl(editionId);
      latest.current.health.engine('direct');
      latest.current.health.rung(ORIGINAL, latest.current.edition.file?.height ?? null);
    };

    // Streaming failed and the film direct-plays instead (a split film has no file to play, so nothing falls back).
    const failToDirect = () => {
      toDirect();
      if (!split) latest.current.health.fellBack();
    };

    // Give up on HLS for this film: stop the engine, drop the session and direct-play from here.
    const abandon = () => {
      engine?.destroy();
      forget();
      failToDirect();
    };

    const recover = () => {
      const now = Date.now();
      recoveriesRef.current = [...recoveriesRef.current, now].filter(
        (at) => now - at < RECOVERY_WINDOW_MS,
      );
      if (recoveriesRef.current.length > MAX_RECOVERIES) abandon();
      else {
        setGeneration((g) => g + 1);
        // Counted when the rebuild begins: the expired session is the event, whether or not a new one can then be made.
        latest.current.health.recovered();
      }
    };

    // This run's waits end at once when the effect is cleaned up (`dispose` lets a pending wait go); the waiter
    // hears `online` and sign-ins meanwhile.
    const waiter = createWaiter();
    const { wait, untilSignedIn } = waiter;
    const serverAnswers = () =>
      reachable((signal) => sessionApi.status({ signal }), { timeoutMs: REACH_TIMEOUT_MS });

    // A measurement from the probe or a segment: kept under the current network, and remembered for the next start.
    const addMeasured = (kbps) => {
      const kept = samplesRef.current;
      const network = networkGeneration();
      const base = kept.network === network ? kept.list : [];
      const list = addSample(base, kbps);
      samplesRef.current = { list, network, at: Date.now() };
      setSamples(list);
      if (list !== base) rememberSpeed(lowerMiddle(list));
    };
    // The start rung's speed: this player's own measurements under this network, else the one remembered.
    // They have an age: ten minutes after the last measurement (or a network change) they are discarded.
    const knownSpeed = () => {
      const kept = samplesRef.current;
      const usable = kept.network === networkGeneration() && Date.now() - kept.at < REMEMBER_MS;
      if (!usable && kept.list.length > 0) {
        samplesRef.current = { list: [], network: networkGeneration(), at: 0 };
        setSamples([]);
      }
      const own = usable ? lowerMiddle(kept.list) : null;
      return own === null ? { kbps: recallSpeed(), own: false } : { kbps: own, own: true };
    };

    const go = async () => {
      const position = currentPosition();
      if (startedRef.current && position > 0) pendingSeekRef.current = position;
      startedRef.current = true;
      const startMs = pendingSeekRef.current ?? 0;

      if (quality === ORIGINAL && workerOffline(latest.current.edition.qualities)) {
        toDirect();
        return;
      }
      // Auto's start rung: a speed measured lately is reused; else a film in one piece probes, and anything
      // still unmeasured starts on the lowest rung (probeKbps 0).
      let probeKbps = null;
      let reused = null;
      if (quality === AUTO) {
        const known = knownSpeed();
        if (known.kbps) {
          probeKbps = Math.round(known.kbps);
          if (!known.own) reused = probeKbps;
        } else if (split) {
          probeKbps = 0;
        } else {
          const measured = await measureThroughput(editionsApi.streamUrl(editionId), {
            checkReachable: serverAnswers,
            onUnreachable: () => setNotice(true),
          });
          if (cancelled) return;
          if (measured) addMeasured(measured);
          probeKbps = measured ? Math.round(lowerMiddle(samplesRef.current.list)) : 0;
        }
      }
      setReusedKbps(reused);
      const chosenOn = networkGeneration();
      setStream((s) =>
        s.mode === 'unavailable' ? { mode: 'loading', rung: null, renditions: [] } : s,
      );
      // The body is built on each attempt, so an audio track chosen while the request retries is the one asked for.
      const buildBody = () => {
        const next = { quality, startMs };
        const audio = latest.current.audioStreamId;
        if (audio !== null && audio !== undefined) next.audioStreamId = audio;
        // A speed chosen on another network says nothing about this one: start low, Auto steps up.
        if (probeKbps !== null) {
          next.probeKbps = networkGeneration() === chosenOn ? probeKbps : 0;
        }
        return next;
      };
      const created = await createSession(
        (signal) => sessionsApi.create(editionId, buildBody(), { signal }),
        {
          retryWhenPreparing: split,
          isCancelled: () => cancelled,
          wait,
          untilSignedIn,
          reachable: serverAnswers,
          onRetrying: setNotice,
          onNoAnswer: (error) => {
            if (!cancelled) latest.current.health.networkError(error);
          },
        },
      );
      setNotice(false);
      if (!created) {
        if (!cancelled) failToDirect(); // No encoder, no keyframe index yet, or the request failed.
        return;
      }
      if (cancelled) {
        dropSession(created.sessionId);
        return;
      }
      if (quality === ORIGINAL && !created.renditions.some((r) => r.copy)) {
        dropSession(created.sessionId);
        toDirect(); // No copy rung for this file: the untouched file is the original.
        return;
      }
      session = created;
      sessionRef.current = created;
      rung = created.startRung;
      setStream({ mode: 'hls', rung, renditions: created.renditions });
      latest.current.health.rung(rung, heightOf(created.renditions, rung));
      engine = await startStream({
        video,
        session: created,
        auto: quality === AUTO,
        startSeconds: startMs / 1000,
        isCancelled: () => cancelled,
        reachable: serverAnswers,
        onRetrying: setNotice,
        wait,
        handlers: {
          onLevel(next, { initial }) {
            const previous = rung;
            rung = next;
            setStream((s) => ({ ...s, rung: next }));
            const text =
              initial || quality !== AUTO ? null : levelToast(created.renditions, previous, next);
            if (text) latest.current.onToast(text);
            latest.current.health.rung(next, heightOf(created.renditions, next));
          },
          onThroughput(kbps) {
            addMeasured(kbps);
            latest.current.health.throughput(kbps);
          },
          onReconnecting: setNotice,
          onReload(ms) {
            holdPlayState();
            if (ms > 0) pendingSeekRef.current = ms;
          },
          onSessionLost: recover,
          onFatal: abandon,
          onNetworkError: (error) => latest.current.health.networkError(error),
        },
      });
      if (cancelled) {
        engine?.destroy();
        return;
      }
      if (engine) {
        latest.current.health.engine(engine.kind);
      } else {
        forget();
        failToDirect();
      }
    };
    const run = async () => {
      try {
        await go();
      } finally {
        waiter.dispose();
      }
    };
    void run();

    return () => {
      cancelled = true;
      waiter.dispose();
      holdPlayState();
      engine?.destroy();
      if (session) {
        sessionsApi.remove(session.sessionId, { keepalive: true }).catch(() => {});
        sessionRef.current = null;
      }
    };
  }, [editionId, quality, generation, videoRef]);

  // The network changed (offline or online): what was measured on the old one says nothing about the new, so the
  // samples and a reused speed go, and the indicator and the slow card stop reading them.
  useEffect(() => {
    const forgetMeasurements = () => {
      samplesRef.current = { list: [], network: networkGeneration(), at: 0 };
      setSamples([]);
      setReusedKbps(null);
    };
    window.addEventListener('offline', forgetMeasurements);
    window.addEventListener('online', forgetMeasurements);
    return () => {
      window.removeEventListener('offline', forgetMeasurements);
      window.removeEventListener('online', forgetMeasurements);
    };
  }, []);

  // Closing the tab: tell the server so it stops encoding now rather than after the idle timeout.
  useEffect(() => {
    const onPageHide = () => {
      const session = sessionRef.current;
      if (session) sessionsApi.remove(session.sessionId, { keepalive: true }).catch(() => {});
    };
    window.addEventListener('pagehide', onPageHide);
    return () => window.removeEventListener('pagehide', onPageHide);
  }, []);

  /** Whether to play now that the source has metadata; reads once and goes back to "play". */
  const takePlayAfterLoad = useCallback(() => {
    const { play } = resumeRef.current;
    resumeRef.current = { play: true, held: false };
    return play;
  }, []);

  const restart = useCallback(() => setGeneration((g) => g + 1), []);

  return {
    mode: stream.mode,
    rung: stream.rung,
    renditions: stream.renditions,
    reconnecting,
    kbps: median(samples) ?? reusedKbps,
    pendingSeekRef,
    takePlayAfterLoad,
    restart,
  };
}
