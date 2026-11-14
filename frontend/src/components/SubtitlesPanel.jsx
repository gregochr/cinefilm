import { useEffect, useId, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  FILE_FILTERS,
  NUDGES,
  REMOVED_NOTICE,
  REMOVE_CONFIRM,
  alignmentSummary,
  checkSummary,
  cueTime,
  fileFilterCounts,
  fileName,
  filterSidecars,
  formatOffset,
  isDownload,
  isFull,
  originLine,
  ownerTimingLine,
  placement,
  playFromHereMs,
  playerPath,
  reportLine,
  servedLine,
  sidecarGroups,
  warningsFor,
} from '../state/subtitleAdmin.js';
import { cueResourceKey, useSubtitleCues, useSubtitles } from '../state/useSubtitles.js';
import AttachPicker from './AttachPicker.jsx';
import { Button } from './Button.jsx';
import { SkeletonBlock } from './Skeleton.jsx';
import ForcedNeedSection from './ForcedNeedSection.jsx';
import FullNeedSection from './FullNeedSection.jsx';
import { ErrorState } from './StateMessages.jsx';

/** The cues of one file with "Play from here" (the player opens 3 s before the cue). Cue text is rendered as text. */
function CueList({ item, id }) {
  const { data, error, loading, reload } = useSubtitleCues(item);
  if (loading && !data) {
    return (
      <div role="status" aria-label="Loading cues">
        <SkeletonBlock style={{ height: 80 }} />
      </div>
    );
  }
  const failure = error ? (
    <ErrorState onRetry={reload}>Couldn&rsquo;t load the cues.</ErrorState>
  ) : null;
  if (!data) return failure;
  if (data.cues.length === 0) {
    return (
      <>
        {failure}
        <p className="admin__text">This file has no cues.</p>
      </>
    );
  }
  return (
    <>
      {failure}
      <ol className="subs__cues" id={id} aria-label={`Cues in ${fileName(item.path)}`}>
        {data.cues.map((cue, i) => (
          <li key={`${i}-${cue.startMs}`} className="subs__cue">
            <span className="admin__mono subs__time">{cueTime(cue)}</span>
            <span className="subs__text">{cue.text}</span>
            {item.filmId == null ? null : (
              <Link
                className="admin__link"
                to={playerPath(item, playFromHereMs(cue))}
                aria-label={`Play from here: ${cue.text.replace(/\s+/g, ' ')}`}
              >
                Play from here
              </Link>
            )}
          </li>
        ))}
      </ol>
    </>
  );
}

function Timing({ item, busy, actions }) {
  return (
    <div className="subs__timing" role="group" aria-label="Timing">
      <output className="admin__mono">{formatOffset(item.offsetMs)}</output>
      {NUDGES.map((nudge) => (
        <Button
          key={nudge.key}
          aria-label={nudge.spoken}
          disabled={busy}
          onClick={() => actions.nudge(item, nudge.delta)}
        >
          {nudge.label}
        </Button>
      ))}
      <Button disabled={busy || item.offsetMs === 0} onClick={() => actions.resetTiming(item.id)}>
        Reset timing
      </Button>
    </div>
  );
}

function Check({ item, busy, actions, replaced }) {
  if (item.verified) {
    return (
      <div className="admin__actions">
        <span className="admin__text">You checked this: the lines look right.</span>
        <Button disabled={busy} onClick={() => actions.unverify(item.id)}>
          Undo
        </Button>
      </div>
    );
  }
  // A replaced file has its own, more prominent button on the row.
  if (replaced) return null;
  return (
    <div className="admin__actions">
      <Button disabled={busy} onClick={() => actions.verify(item.id)}>
        Looks right
      </Button>
    </div>
  );
}

/** What fitting the file to the film's own English track says, and "Timing set by you"; never colour alone. */
function TimingLine({ item }) {
  const summary = alignmentSummary(item);
  const owner = ownerTimingLine(item);
  if (!summary && !owner) return null;
  return (
    <div className={`subs__check subs__check--${summary ? summary.kind : 'unchecked'}`}>
      {summary ? (
        <>
          <span className="subs__check-label">{summary.label}</span>
          {summary.note ? <p className="subs__check-note">{summary.note}</p> : null}
          {summary.lines.length > 0 ? (
            <ul className="subs__check-reasons" aria-label={`${summary.label}: details`}>
              {summary.lines.map((line) => (
                <li key={line}>{line}</li>
              ))}
            </ul>
          ) : null}
        </>
      ) : null}
      {owner ? <p className="subs__check-note">{owner}</p> : null}
    </div>
  );
}

/** Removing a download: "Remove", then an inline confirmation. Focus goes to the safe choice, and back on cancel. */
function RemoveDownload({ item, name, busy, failed, actions, onRemoved }) {
  const [confirming, setConfirming] = useState(false);
  const removeRef = useRef(null);
  const keepRef = useRef(null);
  const returnFocus = useRef(false);
  useEffect(() => {
    if (confirming) keepRef.current?.focus();
    else if (returnFocus.current) {
      returnFocus.current = false;
      removeRef.current?.focus();
    }
  }, [confirming]);
  const confirm = async () => {
    if (await actions.remove(item.id)) onRemoved();
  };
  const cancel = () => {
    returnFocus.current = true;
    setConfirming(false);
  };
  if (!confirming) {
    return (
      <Button
        ref={removeRef}
        aria-label={`Remove the OpenSubtitles download ${name}`}
        disabled={busy}
        onClick={() => setConfirming(true)}
      >
        Remove
      </Button>
    );
  }
  return (
    <div className="subs__confirm" role="group" aria-label="Remove this download">
      <p className="admin__text">{REMOVE_CONFIRM}</p>
      <div className="admin__actions">
        <Button disabled={busy} onClick={confirm}>
          {failed ? 'Try again' : 'Remove download'}
        </Button>
        <Button ref={keepRef} disabled={busy} onClick={cancel}>
          Keep it
        </Button>
      </div>
    </div>
  );
}

/** What the comparison with OpenSubtitles says about a file, as words (never colour alone). */
function CheckLine({ item }) {
  const summary = checkSummary(item);
  if (!summary) return null;
  return (
    <div className={`subs__check subs__check--${summary.kind}`}>
      <span className="subs__check-label">{summary.label}</span>
      {summary.kind === 'unchecked' ? <p className="subs__check-note">{summary.lines[0]}</p> : null}
      {summary.kind !== 'unchecked' && summary.lines.length > 0 ? (
        <ul className="subs__check-reasons" aria-label={`${summary.label}: details`}>
          {summary.lines.map((line) => (
            <li key={line}>{line}</li>
          ))}
        </ul>
      ) : null}
      {summary.reference ? <p className="subs__check-note">{summary.reference}</p> : null}
    </div>
  );
}

function Item({ item, busy, problem, actions, onRemoved }) {
  const [open, setOpen] = useState(false);
  const [attaching, setAttaching] = useState(false);
  const cuesId = useId();
  const origin = originLine(item);
  const download = isDownload(item);
  const name = download ? (origin.release ?? 'download') : fileName(item.path);
  const matched = item.filmId != null;
  const full = isFull(item);
  const replaced = item.replacedByDownload === true;
  const served = servedLine(item);
  const attach = async (target) => {
    if (await actions.attach(item.id, target)) setAttaching(false);
  };
  return (
    <li className="admin__item">
      <div className="admin__item-head">
        {download ? (
          <>
            <span className="admin__item-title">{origin.title}</span>
            {origin.release ? (
              <span className="admin__mono subs__release">{origin.release}</span>
            ) : null}
          </>
        ) : (
          <span className="admin__item-title admin__mono">{origin.title}</span>
        )}
        {full ? <span className="admin__tag">Full</span> : null}
        {item.verified && !full ? <span className="admin__tag">Checked</span> : null}
        {item.served ? <span className="admin__tag">Playing</span> : null}
      </div>
      {served ? <p className="subs__served">{served}</p> : null}
      <div className="admin__item-detail">{placement(item)}</div>
      <div className="admin__item-files admin__mono">{reportLine(item)}</div>
      <CheckLine item={item} />
      <TimingLine item={item} />
      {warningsFor(item).map((warning) => (
        <p key={warning} className="admin__warning" role="status">
          {warning}
        </p>
      ))}
      {problem ? (
        <p className="admin__warning" role="alert">
          {problem}
        </p>
      ) : null}
      <div className="admin__actions admin__item-actions subs__actions">
        {replaced && !item.verified ? (
          <Button disabled={busy} onClick={() => actions.verify(item.id)}>
            Use my file (looks right)
          </Button>
        ) : null}
        {download ? null : (
          <Button
            aria-label={`${matched ? 'Move' : 'Attach'} ${name} ${matched ? 'to another film' : 'to a film'}`}
            aria-expanded={attaching}
            disabled={busy}
            onClick={() => setAttaching(!attaching)}
          >
            {matched ? 'Move to…' : 'Attach to…'}
          </Button>
        )}
        {item.ownerAttached ? (
          <Button
            aria-label={`Give ${name} back to automatic matching`}
            disabled={busy}
            onClick={() => actions.detach(item.id)}
          >
            Back to automatic
          </Button>
        ) : null}
        <Button
          aria-label={`${open ? 'Hide' : 'Show'} the cues of ${name}`}
          aria-expanded={open}
          aria-controls={cuesId}
          onClick={() => setOpen(!open)}
        >
          {open ? 'Hide cues' : 'Show cues'}
        </Button>
        {download ? (
          <RemoveDownload
            item={item}
            name={name}
            busy={busy}
            failed={problem !== null}
            actions={actions}
            onRemoved={onRemoved}
          />
        ) : null}
      </div>
      {attaching ? (
        <AttachPicker
          name={name}
          busy={busy}
          onAttach={attach}
          onCancel={() => setAttaching(false)}
        />
      ) : null}
      {open ? (
        <div className="subs__details">
          {matched ? (
            <>
              <Timing item={item} busy={busy} actions={actions} />
              {full ? null : (
                <Check item={item} busy={busy} actions={actions} replaced={replaced} />
              )}
            </>
          ) : null}
          {/* A new key remounts the list, so cues for another attachment, offset or version are never shown. */}
          <CueList key={cueResourceKey(item)} item={item} id={cuesId} />
        </div>
      ) : null}
    </li>
  );
}

/**
 * Admin: the English subtitle files beside the films, forced (checked and played as the forced subtitle) and full
 * (labelled "Full": found and served, with no checks). They are grouped (Unmatched, Conflicts, Missing,
 * Matched); each shows how it was read, can be attached to a film by hand, its cues read, its timing nudged and its
 * lines marked as checked. The cue text is untrusted: it is only ever rendered as text.
 */
export default function SubtitlesPanel({ version }) {
  const subtitles = useSubtitles(version);
  const { items, loading, error, reload } = subtitles;
  const [filter, setFilter] = useState('all');
  // The film whose full files the full-need list asked to see; any chip the owner taps afterwards clears it.
  const [film, setFilm] = useState(null);
  const [removals, setRemovals] = useState(0);
  const noticeRef = useRef(null);
  const filmRef = useRef(null);
  const mounted = useRef(true);
  useEffect(() => {
    // Set on every setup: Strict Mode runs setup, cleanup, setup again.
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);
  useEffect(() => {
    if (removals > 0) noticeRef.current?.focus();
  }, [removals]);
  useEffect(() => {
    // Keyboard and screen reader users land on the line that says which files are listed.
    if (film) filmRef.current?.focus();
  }, [film]);
  const showFilmFiles = (target) => {
    setFilter('full');
    setFilm(target);
  };
  const chooseFilter = (key) => {
    setFilter(key);
    setFilm(null);
  };
  const onRemoved = () => {
    if (mounted.current) setRemovals((n) => n + 1);
  };
  const counts = useMemo(() => (items ? fileFilterCounts(items) : null), [items]);
  const filmId = film ? film.filmId : null;
  const shown = useMemo(
    () => (items ? filterSidecars(items, filter, filmId) : []),
    [items, filter, filmId],
  );
  let body;
  if (loading && !items) {
    body = (
      <div role="status" aria-label="Loading">
        <SkeletonBlock style={{ height: 120 }} />
      </div>
    );
  } else if (error && !items) {
    body = <ErrorState onRetry={reload}>Couldn&rsquo;t load the subtitle files.</ErrorState>;
  } else if (!items || items.length === 0) {
    body = (
      <p className="admin__text">
        No subtitle files found. They are named like Film.en.forced.srt (forced) or Film.en.srt
        (full) and sit beside the film.
      </p>
    );
  } else {
    const groups = sidecarGroups(shown);
    body = (
      <>
        <div className="segmented subs__segmented" role="group" aria-label="Show subtitle files">
          {FILE_FILTERS.map((f) => (
            <button
              key={f.key}
              type="button"
              className="segmented__btn"
              aria-pressed={filter === f.key}
              onClick={() => chooseFilter(f.key)}
            >
              {f.label} ({counts[f.key]})
            </button>
          ))}
        </div>
        {groups.length === 0 ? <p className="admin__text">No files here.</p> : null}
        {groups.map((group) => (
          <div key={group.key} className="subs__group">
            <h3 className="admin__subtitle">
              {group.title} ({group.items.length})
            </h3>
            <ul className="admin__list" aria-label={`${group.title} subtitles`}>
              {group.items.map((item) => (
                <Item
                  key={item.id}
                  item={item}
                  onRemoved={onRemoved}
                  busy={subtitles.busy !== null}
                  problem={subtitles.problem?.id === item.id ? subtitles.problem.text : null}
                  actions={subtitles}
                />
              ))}
            </ul>
          </div>
        ))}
      </>
    );
  }
  // A failed refresh keeps the list that is there and says so, with a way to try again.
  const refreshFailure =
    error && items ? (
      <ErrorState onRetry={reload}>Couldn&rsquo;t refresh the subtitle files.</ErrorState>
    ) : null;
  return (
    <section className="admin__card" aria-labelledby="admin-subtitles-title">
      <div className="admin__head">
        <h2 id="admin-subtitles-title" className="admin__title">
          Subtitles
        </h2>
      </div>
      <ForcedNeedSection refreshKey={`${version}:${subtitles.changes}`} />
      <FullNeedSection refreshKey={`${version}:${subtitles.changes}`} onShowFiles={showFilmFiles} />
      {film ? (
        <div className="subs__film-filter">
          <p className="admin__text" role="status" tabIndex={-1} ref={filmRef}>
            Showing the full subtitle files of {film.title}.
          </p>
          <div className="admin__actions">
            <Button onClick={() => chooseFilter('all')}>Show all files</Button>
          </div>
        </div>
      ) : null}
      {removals > 0 ? (
        <p
          key={removals}
          className="admin__text subs__notice"
          role="status"
          tabIndex={-1}
          ref={noticeRef}
        >
          {REMOVED_NOTICE}
        </p>
      ) : null}
      {refreshFailure}
      {body}
    </section>
  );
}
