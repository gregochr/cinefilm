import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { SESSION_EXPIRED_EVENT, SIGNED_IN_EVENT } from '../api/client.js';
import { editionsApi } from '../api/resources.js';
import BufferingIndicator from '../components/BufferingIndicator.jsx';
import { Button } from '../components/Button.jsx';
import {
  BackIcon,
  CaptionsIcon,
  FullscreenIcon,
  MutedIcon,
  PauseIcon,
  PlayIcon,
  QualityIcon,
  SkipBackIcon,
  SkipForwardIcon,
  VolumeIcon,
} from '../components/icons.jsx';
import { AudioSubtitlesPanel, QualityMenu } from '../components/PlayerMenus.jsx';
import {
  EndOverlay,
  IndicatorPill,
  ReconnectingNotice,
  SlowConnectionCard,
  Toast,
  UnavailableCard,
} from '../components/PlayerOverlays.jsx';
import Scrubber from '../components/Scrubber.jsx';
import { ErrorState } from '../components/StateMessages.jsx';
import { useAutoHide } from '../player/autohide.js';
import { toggleFullscreen } from '../player/fullscreen.js';
import { clampVolume, keyToAction, resolveEscape } from '../player/keymap.js';
import { indicatorModel } from '../player/indicator.js';
import { AUTO, ORIGINAL, resolveQuality, rungName, topRung } from '../player/ladder.js';
import { hasReachedEnd, resumePosition, seekLanded } from '../player/resume.js';
import { useSlowCard } from '../player/slowCard.js';
import { useStream } from '../player/useStream.js';
import { formatClock, formatRemaining } from '../player/time.js';
import {
  SUBTITLE_OFF,
  activeSubtitleKey,
  nextSubtitle,
  qualityName,
  resolveAudioChoice,
  resolveSubtitleChoice,
  streamKey,
  subtitleLabel,
  subtitleOptions,
} from '../player/tracks.js';
import { useFilm } from '../state/useFilms.js';
import { usePageExitReport } from '../player/exitReport.js';
import { usePlayback } from '../state/usePlayback.js';

function bufferedEnd(video) {
  const { buffered, currentTime } = video;
  for (let i = 0; i < buffered.length; i += 1) {
    if (buffered.start(i) <= currentTime + 0.5 && buffered.end(i) >= currentTime) {
      return buffered.end(i) * 1000;
    }
  }
  return currentTime * 1000;
}

export default function Player() {
  const { id } = useParams();
  const [params] = useSearchParams();
  const { data: film, error, loading, reload } = useFilm(id);
  if (loading && !film)
    return <div className="player player--loading" role="status" aria-label="Loading" />;
  if (error && !film) {
    return (
      <div className="player player--loading">
        <ErrorState onRetry={reload} />
      </div>
    );
  }
  if (!film) return null;
  const wanted = Number(params.get('edition'));
  const edition =
    film.editions.find((e) => e.id === wanted) ||
    film.editions.find((e) => e.id === film.preferences?.editionId) ||
    film.editions[0];
  return (
    <PlayerView
      key={edition.id}
      film={film}
      edition={edition}
      startAtZero={params.get('start') === '0'}
    />
  );
}

function PlayerView({ film, edition, startAtZero }) {
  const navigate = useNavigate();
  const containerRef = useRef(null);
  const videoRef = useRef(null);
  const positionRef = useRef(0);
  const wasPlayingBeforeExpiry = useRef(false);
  const lastPoke = useRef(0);
  const trackRefs = useRef(new Map());
  const prefs = film.preferences || {};

  const [playing, setPlaying] = useState(false);
  const [buffering, setBuffering] = useState(true);
  const [positionMs, setPositionMs] = useState(0);
  const [durationMs, setDurationMs] = useState(edition.runtimeMs);
  const [bufferedMs, setBufferedMs] = useState(0);
  const [volume, setVolume] = useState(1);
  const [muted, setMuted] = useState(false);
  const [menu, setMenu] = useState(null);
  const [fullscreen, setFullscreen] = useState(false);
  const [endDismissed, setEndDismissed] = useState(false);
  const [toast, setToast] = useState(null);
  const [quality, setQuality] = useState(() => resolveQuality(prefs.quality, edition.qualities));
  const [audio, setAudio] = useState(() => {
    const preferredKey = resolveAudioChoice(film.editions, edition, prefs.audioStreamId);
    const preferred = edition.audioStreams.find((s) => streamKey(s) === preferredKey);
    const fallback = edition.audioStreams.find((s) => s.isDefault) || edition.audioStreams[0];
    const chosen = preferred || fallback;
    return chosen ? streamKey(chosen) : null;
  });
  const [subtitle, setSubtitle] = useState(() =>
    resolveSubtitleChoice(film.editions, edition, prefs.subtitle),
  );

  const audioStreamId = edition.audioStreams.find(
    (s) => String(streamKey(s)) === String(audio),
  )?.id;
  const stream = useStream({
    videoRef,
    edition,
    quality,
    audioStreamId,
    getPositionMs: () => positionRef.current,
    initialSeekMs: startAtZero ? 0 : resumePosition(edition.positionMs),
    onToast: setToast,
  });
  const { pendingSeekRef } = stream;
  const activeRung = stream.rung ?? (quality === AUTO ? ORIGINAL : quality);
  const indicator = indicatorModel({
    quality,
    rung: activeRung,
    top: topRung(stream.renditions),
    kbps: stream.kbps,
  });
  const slowCard = useSlowCard(stream.mode === 'hls' && quality === AUTO && indicator.slow);

  const hasSubtitles = edition.subtitleStreams.length > 0;
  const atEnd = hasReachedEnd(positionMs, durationMs, edition.creditsMarkerMs);
  const endOverlay = atEnd && !endDismissed;
  const { visible, poke } = useAutoHide({ playing, menuOpen: menu !== null, ended: endOverlay });

  const playback = usePlayback({
    filmId: film.id,
    editionId: edition.id,
    playing,
    getPositionMs: () => positionRef.current,
  });

  usePageExitReport({ editionId: edition.id, getPositionMs: () => positionRef.current });

  useEffect(() => {
    containerRef.current?.focus();
  }, []);

  // Show the chosen subtitle track, or the forced one when Subtitles is Off. Tracks that are not showing stay
  // disabled, so the browser only fetches (and the server only extracts) a track when it is wanted.
  const applySubtitleModes = useCallback(() => {
    const showing = activeSubtitleKey(edition.subtitleStreams, subtitle);
    edition.subtitleStreams.forEach((subtitleStream) => {
      const key = streamKey(subtitleStream);
      const track = trackRefs.current.get(key)?.track;
      if (track) track.mode = key === showing ? 'showing' : 'disabled';
    });
  }, [edition.subtitleStreams, subtitle]);
  useEffect(applySubtitleModes, [applySubtitleModes]);

  useEffect(() => {
    if (!toast) return undefined;
    const id = setTimeout(() => setToast(null), 4000);
    return () => clearTimeout(id);
  }, [toast]);

  useEffect(() => {
    const onChange = () => setFullscreen(Boolean(document.fullscreenElement));
    document.addEventListener('fullscreenchange', onChange);
    return () => document.removeEventListener('fullscreenchange', onChange);
  }, []);

  // A 401 while watching: pause behind the sign-in card, resume afterwards. Position is untouched.
  useEffect(() => {
    const onExpired = () => {
      const video = videoRef.current;
      if (!video) return;
      wasPlayingBeforeExpiry.current = !video.paused;
      video.pause();
    };
    const onSignedIn = () => {
      if (wasPlayingBeforeExpiry.current) videoRef.current?.play().catch(() => {});
      wasPlayingBeforeExpiry.current = false;
    };
    window.addEventListener(SESSION_EXPIRED_EVENT, onExpired);
    window.addEventListener(SIGNED_IN_EVENT, onSignedIn);
    return () => {
      window.removeEventListener(SESSION_EXPIRED_EVENT, onExpired);
      window.removeEventListener(SIGNED_IN_EVENT, onSignedIn);
    };
  }, []);

  const seekTo = useCallback(
    (ms) => {
      const video = videoRef.current;
      if (!video) return;
      const clamped = Math.min(Math.max(0, ms), durationMs);
      pendingSeekRef.current = null; // the viewer chose a position: nothing is left to restore
      video.currentTime = clamped / 1000;
      positionRef.current = clamped;
      setPositionMs(clamped);
    },
    [durationMs, pendingSeekRef],
  );

  const togglePlay = useCallback(() => {
    const video = videoRef.current;
    if (!video) return;
    if (video.paused) video.play().catch(() => {});
    else video.pause();
  }, []);

  const changeVolume = useCallback((next) => {
    const video = videoRef.current;
    const v = clampVolume(next);
    setVolume(v);
    setMuted(false);
    if (video) {
      video.volume = v;
      video.muted = false;
    }
  }, []);

  const toggleMute = () => {
    const video = videoRef.current;
    const next = !muted;
    setMuted(next);
    if (video) video.muted = next;
  };

  const chooseSubtitle = useCallback(
    (value) => {
      setSubtitle(value);
      const label = subtitleOptions(edition.subtitleStreams).find(
        (o) => String(o.value) === String(value),
      )?.label;
      setToast(`Subtitles: ${label ?? 'Off'}`);
      playback.savePreferences({ subtitle: value });
    },
    [edition.subtitleStreams, playback],
  );

  const chooseAudio = (key) => {
    setAudio(key);
    playback.savePreferences({ audioStreamId: key });
    // The server picks the audio when it makes the session, so a new one starts at the current position.
    if (stream.mode === 'hls') stream.restart();
  };

  const chooseQuality = (qualityId) => {
    setQuality(qualityId);
    setMenu(null);
    playback.savePreferences({ quality: qualityId });
    containerRef.current?.focus();
  };

  const closeMenu = () => {
    setMenu(null);
    containerRef.current?.focus();
  };

  const onKeyDown = (event) => {
    poke();
    const target = event.target;
    const inMenu = target.closest?.('[role="menu"]');
    const onRange = target.tagName === 'INPUT' && target.type === 'range';
    const onButton = target.tagName === 'BUTTON';
    const action = keyToAction(event, { hasSubtitles });
    if (!action) return;
    if (action.type !== 'escape' && (inMenu || onRange)) return;
    if (action.type === 'togglePlay' && onButton) return; // Space activates the focused button.
    event.preventDefault();
    switch (action.type) {
      case 'togglePlay':
        togglePlay();
        break;
      case 'seek':
        seekTo(positionRef.current + action.seconds * 1000);
        break;
      case 'volume':
        changeVolume(volume + action.delta);
        break;
      case 'fullscreen':
        toggleFullscreen(containerRef.current, videoRef.current);
        break;
      case 'cycleCaptions':
        chooseSubtitle(nextSubtitle(edition.subtitleStreams, subtitle));
        break;
      case 'openAudio':
        setMenu('audio');
        break;
      case 'openQuality':
        setMenu('quality');
        break;
      case 'escape': {
        const step = resolveEscape({ menuOpen: menu !== null, fullscreen, endOverlay });
        if (step === 'closeMenu') closeMenu();
        else if (step === 'exitFullscreen') document.exitFullscreen?.();
        else if (step === 'dismissEnd') setEndDismissed(true);
        break;
      }
      default:
        break;
    }
  };

  const onPointerActivity = () => {
    const now = Date.now();
    if (now - lastPoke.current > 100) {
      lastPoke.current = now;
      poke();
    }
  };

  // Runs for every source load, including a new session or a reload: a fresh source may reset the text
  // tracks, and it starts at 0 until the seek to the pending position lands.
  const onLoadedMetadata = () => {
    const video = videoRef.current;
    if (Number.isFinite(video.duration) && video.duration > 0) setDurationMs(video.duration * 1000);
    const pending = pendingSeekRef.current;
    if (pending !== null) {
      video.currentTime = pending / 1000;
      positionRef.current = pending;
      setPositionMs(pending);
    }
    applySubtitleModes();
    // A restart (audio, quality, reconnect, expired session) keeps a paused film paused.
    if (stream.takePlayAfterLoad()) video.play().catch(() => {});
  };

  const onTimeUpdate = () => {
    const video = videoRef.current;
    // Until the seek lands the element reads 0: do not let that overwrite the position or the progress report.
    const pending = pendingSeekRef.current;
    if (pending !== null) {
      if (!seekLanded(video.currentTime * 1000, pending)) return;
      pendingSeekRef.current = null;
    }
    positionRef.current = video.currentTime * 1000;
    setPositionMs(positionRef.current);
    setBufferedMs(bufferedEnd(video));
  };

  const markWatchedAndLeave = async () => {
    await playback.setWatched(true);
    navigate(`/films/${film.id}`);
  };

  const remaining = formatRemaining(positionMs, durationMs);
  const director = film.directors?.[0]?.name || film.director;
  const hidden = !visible && !endOverlay;
  const progressPct = durationMs ? Math.min(100, (positionMs / durationMs) * 100) : 0;

  return (
    // eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions
    <div
      ref={containerRef}
      className={`player${hidden ? ' player--hidden' : ''}`}
      role="region"
      aria-label="Video player"
      tabIndex={-1}
      onKeyDown={onKeyDown}
      onMouseMove={onPointerActivity}
      onPointerDown={onPointerActivity}
    >
      {/* The subtitle <track>s are children (kind="subtitles"); the lint rule only recognises captions. */}
      {/* eslint-disable-next-line jsx-a11y/media-has-caption */}
      <video
        ref={videoRef}
        data-testid="player-video"
        className="player__video"
        playsInline
        preload="metadata"
        onLoadedMetadata={onLoadedMetadata}
        onTimeUpdate={onTimeUpdate}
        onProgress={() => videoRef.current && setBufferedMs(bufferedEnd(videoRef.current))}
        onPlay={() => setPlaying(true)}
        onPause={() => setPlaying(false)}
        onPlaying={() => setBuffering(false)}
        onCanPlay={() => setBuffering(false)}
        onWaiting={() => setBuffering(true)}
        onSeeked={() => setBuffering(false)}
        onEnded={() => {
          setPlaying(false);
          setPositionMs(durationMs);
        }}
      >
        {edition.subtitleStreams.map((stream) => (
          <track
            key={streamKey(stream)}
            ref={(element) => {
              if (element) trackRefs.current.set(streamKey(stream), element);
              else trackRefs.current.delete(streamKey(stream));
            }}
            kind="subtitles"
            data-testid="subtitle-track"
            label={subtitleLabel(stream)}
            src={editionsApi.subtitleUrl(edition.id, stream.index)}
          />
        ))}
      </video>
      <button
        type="button"
        className="player__surface"
        tabIndex={-1}
        aria-label={playing ? 'Pause' : 'Play'}
        onClick={() => (menu ? closeMenu() : togglePlay())}
        onDoubleClick={() => toggleFullscreen(containerRef.current, videoRef.current)}
      />

      {stream.mode === 'unavailable' ? (
        <UnavailableCard onRetry={stream.restart} />
      ) : stream.reconnecting ? (
        <ReconnectingNotice />
      ) : buffering ? (
        <BufferingIndicator />
      ) : null}
      {toast ? <Toast>{toast}</Toast> : null}

      <div className="player__chrome">
        <div className="player__top">
          <Button
            variant="icon"
            className="player__back"
            aria-label="Back"
            onClick={() => navigate(`/films/${film.id}`)}
          >
            <BackIcon size={20} />
          </Button>
          <div className="player__title">
            <span className="player__name">{film.title}</span>
            {(edition.year ?? film.year) ? (
              <span className="player__year">{edition.year ?? film.year}</span>
            ) : null}
          </div>
          <div className="player__spacer" />
          <IndicatorPill text={indicator.text} amber={indicator.amber} />
        </div>

        <div className="player__bottom">
          {menu === 'quality' ? (
            <QualityMenu
              qualities={edition.qualities}
              value={quality}
              width={edition.file?.width}
              activeRung={activeRung}
              onSelect={chooseQuality}
            />
          ) : null}
          {menu === 'audio' ? (
            <AudioSubtitlesPanel
              audioStreams={edition.audioStreams}
              audioValue={audio}
              subtitleStreams={edition.subtitleStreams}
              subtitleValue={subtitle}
              onAudio={chooseAudio}
              onSubtitle={chooseSubtitle}
            />
          ) : null}

          <div className="player__scrub">
            <span className="player__time">{formatClock(positionMs)}</span>
            <Scrubber
              positionMs={positionMs}
              durationMs={durationMs}
              bufferedMs={bufferedMs}
              onSeek={seekTo}
            />
            <span className="player__time">{remaining}</span>
          </div>

          <div className="player__row">
            <Button
              variant="bare"
              className="pbtn pbtn--lg"
              aria-label={playing ? 'Pause' : 'Play'}
              onClick={togglePlay}
            >
              {playing ? <PauseIcon size={22} /> : <PlayIcon size={22} />}
            </Button>
            <Button
              variant="bare"
              className="pbtn"
              aria-label="Back 10 seconds"
              onClick={() => seekTo(positionRef.current - 10000)}
            >
              <SkipBackIcon size={26} />
            </Button>
            <Button
              variant="bare"
              className="pbtn"
              aria-label="Forward 10 seconds"
              onClick={() => seekTo(positionRef.current + 10000)}
            >
              <SkipForwardIcon size={26} />
            </Button>
            <Button
              variant="bare"
              className="pbtn"
              aria-label={muted ? 'Unmute' : 'Mute'}
              onClick={toggleMute}
            >
              {muted || volume === 0 ? <MutedIcon size={22} /> : <VolumeIcon size={22} />}
            </Button>
            <input
              type="range"
              className="player__volume"
              aria-label="Volume"
              min={0}
              max={1}
              step={0.05}
              value={muted ? 0 : volume}
              onChange={(e) => changeVolume(Number(e.target.value))}
            />
            <div className="player__spacer" />
            <Button
              variant="bare"
              className={`pbtn pbtn--text${hasSubtitles ? '' : ' pbtn--dim'}`}
              aria-label="Audio and subtitles"
              aria-haspopup="menu"
              aria-expanded={menu === 'audio'}
              onClick={() => setMenu(menu === 'audio' ? null : 'audio')}
            >
              <CaptionsIcon size={20} />
              <span>
                {subtitle === SUBTITLE_OFF || !hasSubtitles
                  ? 'Off'
                  : subtitleOptions(edition.subtitleStreams).find(
                      (o) => String(o.value) === String(subtitle),
                    )?.label}
              </span>
            </Button>
            <Button
              variant="bare"
              className={`pbtn pbtn--text${menu === 'quality' ? ' pbtn--on' : ''}`}
              aria-label="Quality"
              aria-haspopup="menu"
              aria-expanded={menu === 'quality'}
              onClick={() => setMenu(menu === 'quality' ? null : 'quality')}
            >
              <QualityIcon size={20} />
              <span>{qualityName(quality)}</span>
            </Button>
            <Button
              variant="bare"
              className="pbtn"
              aria-label="Fullscreen"
              onClick={() => toggleFullscreen(containerRef.current, videoRef.current)}
            >
              <FullscreenIcon size={20} />
            </Button>
          </div>
          <div className="player__hints" aria-hidden="true">
            <span>Space play/pause</span>
            <span>&larr; &rarr; 10 s</span>
            <span>&uarr; &darr; volume</span>
            <span>F fullscreen</span>
            <span>C captions</span>
            <span>Q quality</span>
            <span>Controls hide after 3 s</span>
          </div>
        </div>
      </div>

      <div className="player__hairline" aria-hidden="true">
        <div className="player__hairline-fill" style={{ width: `${progressPct}%` }} />
      </div>

      {slowCard.open && !endOverlay ? (
        <SlowConnectionCard
          rung={rungName(activeRung)}
          onOk={() => {
            slowCard.close();
            containerRef.current?.focus();
          }}
          onChoose={() => {
            slowCard.close();
            setMenu('quality');
          }}
        />
      ) : null}

      {endOverlay ? (
        <EndOverlay
          title={film.title}
          year={edition.year ?? film.year}
          director={director}
          onWatched={markWatchedAndLeave}
          onLibrary={() => navigate('/library')}
          onKeepWatching={() => {
            setEndDismissed(true);
            containerRef.current?.focus();
          }}
        />
      ) : null}
    </div>
  );
}
