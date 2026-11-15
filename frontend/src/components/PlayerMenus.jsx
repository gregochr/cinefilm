import { useEffect, useRef } from 'react';
import {
  audioLabel,
  qualityDetail,
  qualityName,
  streamKey,
  subtitleOptions,
} from '../player/tracks.js';
import { workerOffline } from '../player/ladder.js';
import { subtitlesWrong } from '../state/subtitleFixCopy.js';
import { CheckIcon } from './icons.jsx';

/** Moves focus between enabled menu items with the arrow keys. */
function onMenuKeyDown(event) {
  if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return;
  const items = [
    ...event.currentTarget.querySelectorAll(
      '[role="menuitemradio"]:not(:disabled), [role="menuitem"]:not([aria-disabled="true"])',
    ),
  ];
  if (!items.length) return;
  event.preventDefault();
  const at = items.indexOf(document.activeElement);
  const step = event.key === 'ArrowDown' ? 1 : -1;
  items[(at + step + items.length) % items.length].focus();
}

function useFocusChecked(ref) {
  useEffect(() => {
    const root = ref.current;
    if (!root) return;
    const target =
      root.querySelector('[role="menuitemradio"][aria-checked="true"]:not(:disabled)') ||
      root.querySelector('[role="menuitemradio"]:not(:disabled)');
    target?.focus();
  }, [ref]);
}

function Item({ checked, disabled, onSelect, children, detail }) {
  return (
    <button
      type="button"
      role="menuitemradio"
      aria-checked={checked}
      disabled={disabled}
      className={`menu__item${checked ? ' menu__item--on' : ''}`}
      onClick={onSelect}
    >
      <span className="menu__tick" aria-hidden="true">
        {checked ? <CheckIcon size={14} /> : null}
      </span>
      <span className="menu__name">{children}</span>
      {detail ? <span className="menu__detail">{detail}</span> : null}
    </button>
  );
}

export function QualityMenu({ qualities, value, width, activeRung, onSelect }) {
  const ref = useRef(null);
  useFocusChecked(ref);
  return (
    <div
      className="menu menu--quality"
      role="menu"
      tabIndex={-1}
      aria-label="Quality"
      ref={ref}
      onKeyDown={onMenuKeyDown}
    >
      <div className="menu__heading">Quality</div>
      {qualities.map((q) => (
        <Item
          key={q.id}
          checked={value === q.id}
          disabled={!q.available}
          onSelect={() => onSelect(q.id)}
          detail={qualityDetail(q, { width, activeRung })}
        >
          {qualityName(q.id)}
        </Item>
      ))}
      {workerOffline(qualities) ? (
        <div className="menu__footer">
          Lower qualities unavailable right now &mdash; the transcode worker is offline. Pre-made
          versions still play.
        </div>
      ) : null}
    </div>
  );
}

/**
 * "Subtitles are wrong": offered only while a downloaded subtitle shows (`wrong.available`). It never
 * leaves the menu: the player decides what happens once the download is gone. `aria-disabled`, not `disabled`, while
 * the request runs, so the button keeps focus.
 */
function WrongSubtitles({ busy, problem, onWrong }) {
  return (
    <>
      <button
        type="button"
        role="menuitem"
        className="menu__item menu__item--action"
        aria-disabled={busy || undefined}
        onClick={() => {
          if (!busy) onWrong();
        }}
      >
        <span className="menu__tick" aria-hidden="true" />
        <span className="menu__name">{busy ? subtitlesWrong.busy : subtitlesWrong.label}</span>
      </button>
      {problem ? (
        <p className="menu__problem" role="alert">
          {problem}
        </p>
      ) : null}
    </>
  );
}

export function AudioSubtitlesPanel({
  audioStreams,
  audioValue,
  subtitleStreams,
  hasForcedSubtitle = false,
  hasFullSubtitle = false,
  subtitleValue,
  onAudio,
  onSubtitle,
  wrong = null,
}) {
  const ref = useRef(null);
  const subtitlesRef = useRef(null);
  useFocusChecked(ref);
  // The button leaves once the download is gone. If it took focus with it, land on the checked subtitle instead; when
  // the viewer is elsewhere in the panel (picked another audio track, say), focus stays where it is.
  const hadWrong = useRef(Boolean(wrong?.available));
  useEffect(() => {
    const has = Boolean(wrong?.available);
    if (hadWrong.current && !has && !ref.current?.contains(document.activeElement)) {
      const root = subtitlesRef.current;
      (
        root?.querySelector('[role="menuitemradio"][aria-checked="true"]') ??
        root?.querySelector('[role="menuitemradio"]') ??
        root
      )?.focus();
    }
    hadWrong.current = has;
  }, [wrong?.available]);
  const options = subtitleOptions(subtitleStreams, hasFullSubtitle);
  return (
    <div className="menu menu--panel" role="group" aria-label="Audio and subtitles" ref={ref}>
      <div
        className="menu__col"
        role="menu"
        tabIndex={-1}
        aria-label="Audio"
        onKeyDown={onMenuKeyDown}
      >
        <div className="menu__heading">Audio</div>
        {audioStreams.map((s) => {
          const { name, detail } = audioLabel(s);
          return (
            <Item
              key={streamKey(s)}
              checked={String(streamKey(s)) === String(audioValue)}
              onSelect={() => onAudio(streamKey(s))}
              detail={detail}
            >
              {name}
            </Item>
          );
        })}
      </div>
      <div
        className="menu__col"
        role="menu"
        tabIndex={-1}
        aria-label="Subtitles"
        ref={subtitlesRef}
        onKeyDown={onMenuKeyDown}
      >
        <div className="menu__heading">Subtitles</div>
        {subtitleStreams.length === 0 && !hasForcedSubtitle && !hasFullSubtitle ? (
          <div className="menu__empty">None for this film</div>
        ) : (
          options.map((o) => (
            <Item
              key={String(o.value)}
              checked={String(o.value) === String(subtitleValue)}
              onSelect={() => onSubtitle(o.value)}
              detail={o.note}
            >
              {o.label}
            </Item>
          ))
        )}
        {wrong?.available ? (
          <WrongSubtitles busy={wrong.busy} problem={wrong.problem} onWrong={wrong.onWrong} />
        ) : null}
      </div>
    </div>
  );
}
