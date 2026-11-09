import { useCallback, useEffect, useRef, useState } from 'react';
import { editionsApi, sessionsApi } from '../api/resources.js';
import { AUTO, ORIGINAL, levelToast, workerOffline } from './ladder.js';
import { measureThroughput } from './probe.js';
import { startStream } from './streamSource.js';
import { addSample, median } from './throughput.js';

/** More than this many expired-session recoveries inside the window means the server is not keeping sessions. */
export const MAX_RECOVERIES = 3;
export const RECOVERY_WINDOW_MS = 60000;

/**
 * Owns how the edition reaches the `<video>`: creates the HLS session for the chosen quality (Auto probes the
 * link first), attaches hls.js or Safari's native player, recovers an expired session at the current
 * position, and falls back to direct play when there is no encoder, no index or no HLS in the browser.
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
  const latest = useRef({ edition, audioStreamId, getPositionMs, onToast });
  useEffect(() => {
    latest.current = { edition, audioStreamId, getPositionMs, onToast };
  });

  const editionId = edition.id;
  useEffect(() => {
    const video = videoRef.current;
    let cancelled = false;
    let engine = null;
    let session = null;
    let rung = null;
    setReconnecting(false);

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

    const toDirect = () => {
      const position = currentPosition();
      if (position > 0) pendingSeekRef.current = position;
      setStream({ mode: 'direct', rung: ORIGINAL, renditions: [] });
      setReconnecting(false);
      video.src = editionsApi.streamUrl(editionId);
    };

    // Give up on HLS for this film: stop the engine, drop the session and direct-play from here.
    const abandon = () => {
      engine?.destroy();
      forget();
      toDirect();
    };

    const recover = () => {
      const now = Date.now();
      recoveriesRef.current = [...recoveriesRef.current, now].filter(
        (at) => now - at < RECOVERY_WINDOW_MS,
      );
      if (recoveriesRef.current.length > MAX_RECOVERIES) abandon();
      else setGeneration((g) => g + 1);
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
      if (quality === AUTO) {
        probeKbps = await measureThroughput(editionsApi.streamUrl(editionId));
        if (cancelled) return;
        if (probeKbps) setSamples((s) => addSample(s, probeKbps));
      }
      let created;
      try {
        const body = { quality, startMs };
        if (latest.current.audioStreamId !== null && latest.current.audioStreamId !== undefined)
          body.audioStreamId = latest.current.audioStreamId;
        if (probeKbps) body.probeKbps = probeKbps;
        created = await sessionsApi.create(editionId, body);
      } catch {
        if (!cancelled) toDirect(); // No encoder, no keyframe index yet, or the request failed.
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
          },
          onThroughput: (kbps) => setSamples((s) => addSample(s, kbps)),
          onReconnecting: setReconnecting,
          onReload(ms) {
            holdPlayState();
            if (ms > 0) pendingSeekRef.current = ms;
          },
          onSessionLost: recover,
          onFatal: abandon,
        },
      });
      if (cancelled) {
        engine?.destroy();
        return;
      }
      if (!engine) {
        forget();
        toDirect();
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
