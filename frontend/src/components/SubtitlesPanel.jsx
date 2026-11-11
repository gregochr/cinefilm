import { useId, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  NUDGES,
  cueTime,
  fileName,
  formatOffset,
  placement,
  playFromHereMs,
  playerPath,
  reportLine,
  sidecarGroups,
  warningsFor,
} from '../state/subtitleAdmin.js';
import { cueResourceKey, useSubtitleCues, useSubtitles } from '../state/useSubtitles.js';
import AttachPicker from './AttachPicker.jsx';
import { Button } from './Button.jsx';
import { SkeletonBlock } from './Skeleton.jsx';
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

function Check({ item, busy, actions }) {
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
  return (
    <div className="admin__actions">
      <Button disabled={busy} onClick={() => actions.verify(item.id)}>
        Looks right
      </Button>
    </div>
  );
}

function Item({ item, busy, problem, actions }) {
  const [open, setOpen] = useState(false);
  const [attaching, setAttaching] = useState(false);
  const cuesId = useId();
  const name = fileName(item.path);
  const matched = item.filmId != null;
  const attach = async (target) => {
    if (await actions.attach(item.id, target)) setAttaching(false);
  };
  return (
    <li className="admin__item">
      <div className="admin__item-head">
        <span className="admin__item-title admin__mono">{item.path}</span>
        {item.verified ? <span className="admin__tag">Checked</span> : null}
      </div>
      <div className="admin__item-detail">{placement(item)}</div>
      <div className="admin__item-files admin__mono">{reportLine(item)}</div>
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
        <Button
          aria-label={`${matched ? 'Move' : 'Attach'} ${name} ${matched ? 'to another film' : 'to a film'}`}
          aria-expanded={attaching}
          disabled={busy}
          onClick={() => setAttaching(!attaching)}
        >
          {matched ? 'Move to…' : 'Attach to…'}
        </Button>
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
              <Check item={item} busy={busy} actions={actions} />
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
 * Admin: the forced English subtitle files beside the films. They are grouped (Unmatched, Conflicts, Missing,
 * Matched); each shows how it was read, can be attached to a film by hand, its cues read, its timing nudged and its
 * lines marked as checked. The cue text is untrusted: it is only ever rendered as text.
 */
export default function SubtitlesPanel({ version }) {
  const subtitles = useSubtitles(version);
  const { items, loading, error, reload } = subtitles;
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
        No forced subtitle files found. They are named like Film.en.forced.srt and sit beside the
        film.
      </p>
    );
  } else {
    body = sidecarGroups(items).map((group) => (
      <div key={group.key} className="subs__group">
        <h3 className="admin__subtitle">
          {group.title} ({group.items.length})
        </h3>
        <ul className="admin__list" aria-label={`${group.title} subtitles`}>
          {group.items.map((item) => (
            <Item
              key={item.id}
              item={item}
              busy={subtitles.busy !== null}
              problem={subtitles.problem?.id === item.id ? subtitles.problem.text : null}
              actions={subtitles}
            />
          ))}
        </ul>
      </div>
    ));
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
      {refreshFailure}
      {body}
    </section>
  );
}
