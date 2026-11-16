import { useEffect, useId, useRef } from 'react';
import {
  audioLabel,
  qualityDetail,
  qualityName,
  streamKey,
  subtitleOptions,
} from '../player/tracks.js';
import { workerOffline } from '../player/ladder.js';
import { subtitleTiming, subtitlesWrong } from '../state/subtitleFixCopy.js';
import { EARLIER, LATER, timingReadout } from '../state/subtitleTiming.js';
import { CheckIcon } from './icons.jsx';

/**
 * Moves focus between enabled menu items with the arrow keys. The position comes from every item, so the focused one
 * counts even when it is disabled for now (`aria-disabled`, e.g. while saving): the arrows go on from where it is.
 */
function onMenuKeyDown(event) {
  if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return;
  const all = [
    ...event.currentTarget.querySelectorAll('[role="menuitemradio"], [role="menuitem"]'),
  ];
  const usable = (item) => !item.disabled && item.getAttribute('aria-disabled') !== 'true';
  if (!all.some(usable)) return;
  event.preventDefault();
  const step = event.key === 'ArrowDown' ? 1 : -1;
  const at = all.indexOf(document.activeElement);
  // From outside the items, ArrowDown starts at the first and ArrowUp at the last.
  let index = at === -1 ? (step === 1 ? -1 : all.length) : at;
  do {
    index = (index + step + all.length) % all.length;
  } while (!usable(all[index]));
  all[index].focus();
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
  const footerId = useId();
  useFocusChecked(ref);
  const offline = workerOffline(qualities);
  return (
    <div className="menu menu--quality" ref={ref}>
      <div className="menu__heading" aria-hidden="true">
        Quality
      </div>
      <div
        className="menu__list"
        role="menu"
        tabIndex={-1}
        aria-label="Quality"
        aria-describedby={offline ? footerId : undefined}
        onKeyDown={onMenuKeyDown}
      >
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
      </div>
      {offline ? (
        <div className="menu__footer" id={footerId}>
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
function WrongSubtitles({ busy, onWrong }) {
  return (
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
  );
}

/**
 * "Earlier", "Later" and "Reset timing" for the subtitle file that shows (`timing.available`). Three plain actions, not
 * choices (`menuitem`), under a hairline, never amber. `aria-disabled`, not `disabled`, while a change is saved or when
 * there is nothing to reset or the offset is at the 10-minute limit that way, so the pressed button keeps focus. The
 * readout and any problem are not part of the menu
 * (a menu holds only items): `SubtitleNotes` shows them under it.
 */
function TimingControls({ timing }) {
  const saving = timing.busy || timing.loading;
  const blocked = timing.busy || timing.frozen;
  const action = (label, spoken, detail, onPress, blocked) => (
    <button
      type="button"
      role="menuitem"
      className="menu__item menu__item--quiet"
      aria-label={spoken}
      aria-disabled={blocked || undefined}
      onClick={() => {
        if (!blocked) onPress();
      }}
    >
      <span className="menu__tick" aria-hidden="true" />
      <span className="menu__name">{label}</span>
      {detail ? <span className="menu__detail">{detail}</span> : null}
    </button>
  );
  return (
    <div
      className="menu__timing"
      role="group"
      aria-label={subtitleTiming.group}
      aria-busy={saving || undefined}
    >
      {action(
        subtitleTiming.earlier,
        EARLIER.spoken,
        EARLIER.label,
        timing.onEarlier,
        blocked || timing.atEarliest,
      )}
      {action(
        subtitleTiming.later,
        LATER.spoken,
        LATER.label,
        timing.onLater,
        blocked || timing.atLatest,
      )}
      {action(
        subtitleTiming.reset,
        subtitleTiming.reset,
        null,
        timing.onReset,
        blocked || timing.atZero,
      )}
    </div>
  );
}

/**
 * What the Subtitles menu says about itself, kept outside the `role="menu"` so the menu holds only items: the timing
 * readout (a status region that stays mounted while timing is offered, so the first offset is announced) and the
 * problems of "Subtitles are wrong" and of a timing change (alerts).
 */
function SubtitleNotes({ id, wrong, timing }) {
  const saving = timing?.busy || timing?.loading;
  const readout = saving ? subtitleTiming.busy : timingReadout(timing?.offsets);
  return (
    <div className="menu__notes" id={id}>
      {timing?.available ? (
        <div className="menu__readout" role="status">
          {readout ? (saving ? readout : subtitleTiming.readout(readout)) : null}
        </div>
      ) : null}
      {wrong?.available && wrong.problem ? (
        <p className="menu__problem" role="alert">
          {wrong.problem}
        </p>
      ) : null}
      {timing?.available && timing.problem ? (
        <p className="menu__problem" role="alert">
          {timing.problem}
        </p>
      ) : null}
    </div>
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
  timing = null,
}) {
  const ref = useRef(null);
  const subtitlesRef = useRef(null);
  const notesId = useId();
  useFocusChecked(ref);
  // The button leaves once the download is gone. If it took focus with it (focus fell to the page), land on the checked
  // subtitle instead; when the viewer's focus is anywhere else, in the panel or outside it, it stays where it is.
  const hadWrong = useRef(Boolean(wrong?.available));
  useEffect(() => {
    const has = Boolean(wrong?.available);
    const lost = !document.activeElement || document.activeElement === document.body;
    if (hadWrong.current && !has && lost) {
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
  const noChoices = subtitleStreams.length === 0 && !hasForcedSubtitle && !hasFullSubtitle;
  // Nothing to choose and nothing to act on: a plain note, not an empty menu.
  const noSubtitles = noChoices && !timing?.available && !wrong?.available;
  // The readout and the problem lines sit under the menu; the menu is described by them so they are read with it.
  const hasNotes = Boolean(timing?.available || (wrong?.available && wrong.problem));
  return (
    <div className="menu menu--panel" role="group" aria-label="Audio and subtitles" ref={ref}>
      <div className="menu__col">
        <div className="menu__heading" aria-hidden="true">
          Audio
        </div>
        <div
          className="menu__list"
          role="menu"
          tabIndex={-1}
          aria-label="Audio"
          onKeyDown={onMenuKeyDown}
        >
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
      </div>
      <div className="menu__col">
        <div className="menu__heading" aria-hidden={noSubtitles ? undefined : 'true'}>
          Subtitles
        </div>
        {noSubtitles ? (
          <div className="menu__empty">None for this film</div>
        ) : (
          <div
            className="menu__list"
            role="menu"
            tabIndex={-1}
            aria-label="Subtitles"
            aria-describedby={hasNotes ? notesId : undefined}
            ref={subtitlesRef}
            onKeyDown={onMenuKeyDown}
          >
            {(noChoices ? [] : options).map((o) => (
              <Item
                key={String(o.value)}
                checked={String(o.value) === String(subtitleValue)}
                onSelect={() => onSubtitle(o.value)}
                detail={o.note}
              >
                {o.label}
              </Item>
            ))}
            {wrong?.available ? <WrongSubtitles busy={wrong.busy} onWrong={wrong.onWrong} /> : null}
            {timing?.available ? <TimingControls timing={timing} /> : null}
          </div>
        )}
        {hasNotes ? <SubtitleNotes id={notesId} wrong={wrong} timing={timing} /> : null}
      </div>
    </div>
  );
}
