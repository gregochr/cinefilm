import { useCallback, useEffect, useRef, useState } from 'react';
import { editionsApi, sessionsApi } from '../api/resources.js';
import { AUTO, ORIGINAL, levelToast, workerOffline } from './ladder.js';
import { createSession } from './createSession.js';
import { measureThroughput } from './probe.js';
import { startStream } from './streamSource.js';
import { addSample, median } from './throughput.js';

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
  const [samples, setSamples] = useState([]);
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
    // The notice ends (or never began): the counter hears about it wherever the flag is reset.
    const clearReconnecting = () => {
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

    const run = async () => {
      const position = currentPosition();
      if (startedRef.current && position > 0) pendingSeekRef.current = position;
      startedRef.current = true;
      const startMs = pendingSeekRef.current ?? 0;

      if (quality === ORIGINAL && workerOffline(latest.current.edition.qualities)) {
        toDirect();
        return;
      }
      let probeKbps = null;
      if (quality === AUTO && !split) {
        probeKbps = await measureThroughput(editionsApi.streamUrl(editionId));
        if (cancelled) return;
        if (probeKbps) setSamples((s) => addSample(s, probeKbps));
      }
      const body = { quality, startMs };
      if (latest.current.audioStreamId !== null && latest.current.audioStreamId !== undefined)
        body.audioStreamId = latest.current.audioStreamId;
      if (probeKbps) body.probeKbps = probeKbps;
      setStream((s) =>
        s.mode === 'unavailable' ? { mode: 'loading', rung: null, renditions: [] } : s,
      );
      const created = await createSession(() => sessionsApi.create(editionId, body), {
        retryWhenPreparing: split,
        isCancelled: () => cancelled,
      });
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
            setSamples((s) => addSample(s, kbps));
            latest.current.health.throughput(kbps);
          },
          onReconnecting(on) {
            setReconnecting(on);
            latest.current.health.reconnecting(on);
          },
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
    run();

    return () => {
      cancelled = true;
      holdPlayState();
      engine?.destroy();
      if (session) {
        sessionsApi.remove(session.sessionId, { keepalive: true }).catch(() => {});
        sessionRef.current = null;
      }
    };
  }, [editionId, quality, generation, videoRef]);

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
    kbps: median(samples),
    pendingSeekRef,
    takePlayAfterLoad,
    restart,
  };
}
