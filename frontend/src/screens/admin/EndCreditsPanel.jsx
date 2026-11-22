import { useId, useState } from 'react';
import { editionsApi } from '../../api/resources.js';
import { Button } from '../../components/Button.jsx';
import { SkeletonBlock } from '../../components/Skeleton.jsx';
import { formatResume } from '../../player/time.js';
import { checkTime, panelTimeMs, rowLines, stillTimes } from '../../state/creditsAdmin.js';
import { creditsPanel, creditsRow } from '../../state/creditsAdminCopy.js';

/**
 * One still in a reserved 16:9 box, with a static skeleton until it loads. A failed one (an image cannot tell a 404
 * from a 503) says so and offers Try again, which remounts the image; nothing retries by itself.
 */
function Still({ editionId, atMs, caption }) {
  const [state, setState] = useState({ attempt: 0, status: 'loading' });
  const clock = formatResume(atMs);
  return (
    <figure className="credits-still">
      <div className="credits-still__box">
        {state.status === 'failed' ? (
          <div className="credits-still__failed">
            <p className="admin__text">{creditsPanel.noStill}</p>
            <Button
              className="btn--bare admin__quiet"
              onClick={() => setState((s) => ({ attempt: s.attempt + 1, status: 'loading' }))}
            >
              {creditsPanel.tryAgain}
            </Button>
          </div>
        ) : (
          <>
            {state.status === 'loading' ? (
              <SkeletonBlock className="credits-still__skeleton" />
            ) : null}
            <img
              key={state.attempt}
              className="credits-still__img"
              src={editionsApi.stillUrl(editionId, atMs)}
              alt={creditsPanel.frame(clock)}
              onLoad={() => setState((s) => ({ ...s, status: 'loaded' }))}
              onError={() => setState((s) => ({ ...s, status: 'failed' }))}
            />
          </>
        )}
      </div>
      <figcaption className="admin__why">
        {caption} · <span className="admin__mono">{clock}</span>
      </figcaption>
    </figure>
  );
}

/**
 * A row's panel, drawn inline under it only while it is open (so no still is requested before): two stills of the
 * film at the panel's time and 30 s earlier, the time field (prefilled with the panel's time) with Show stills and
 * Set this time, and, on a "Yours" row, Clear my time. With no known length there are no stills and no field, only
 * the reason line. `failure` is what the last write for this row said; `busy` is true while any write is in flight
 * (its buttons then do nothing but stay focusable).
 */
export default function EndCreditsPanel({ row, id, busy, failure, onSet, onClear, onEdit }) {
  const startMs = row.runtimeMs ? panelTimeMs(row) : null;
  const [atMs, setAtMs] = useState(startMs);
  const [draft, setDraft] = useState(startMs == null ? '' : formatResume(startMs));
  const [invalid, setInvalid] = useState(null);
  const fieldId = useId();
  const hintId = useId();
  const errorId = useId();
  const clearHintId = useId();
  const times = atMs == null ? null : stillTimes(atMs);
  const fieldError = invalid ?? (failure?.kind === 'field' ? failure.text : null);
  const alertText = failure?.kind === 'alert' ? failure.text : null;

  const edit = (text) => {
    setDraft(text);
    setInvalid(null);
    onEdit();
  };
  const showStills = () => {
    const checked = checkTime(draft, row.runtimeMs);
    setInvalid(checked.error ?? null);
    if (checked.ms != null) setAtMs(checked.ms);
  };
  const set = (event) => {
    event.preventDefault();
    if (busy) return;
    const checked = checkTime(draft, row.runtimeMs);
    setInvalid(checked.error ?? null);
    if (checked.ms != null) onSet(checked.ms);
  };

  return (
    <div
      id={id}
      className="credits-panel"
      role="group"
      aria-label={creditsPanel.stillsLabel(row.title)}
    >
      {times ? (
        <div className="credits-panel__stills">
          <Still
            key={`earlier-${times[0]}`}
            editionId={row.editionId}
            atMs={times[0]}
            caption={creditsPanel.earlier}
          />
          <Still
            key={`at-${times[1]}`}
            editionId={row.editionId}
            atMs={times[1]}
            caption={creditsPanel.atTime}
          />
        </div>
      ) : (
        <p className="admin__text">{rowLines(row).three ?? creditsRow.noTimeline}</p>
      )}
      {row.runtimeMs ? (
        <form className="credits-panel__form" onSubmit={set} noValidate>
          <label htmlFor={fieldId} className="admin__set-label">
            {creditsPanel.timeLabel}
          </label>
          <p id={hintId} className="admin__set-hint">
            {creditsPanel.timeHint}
          </p>
          <div className="credits-panel__actions">
            <input
              id={fieldId}
              type="text"
              inputMode="numeric"
              autoComplete="off"
              className="admin__number"
              value={draft}
              aria-invalid={fieldError ? 'true' : undefined}
              aria-describedby={fieldError ? `${hintId} ${errorId}` : hintId}
              onChange={(event) => edit(event.target.value)}
            />
            <Button onClick={showStills}>{creditsPanel.showStills}</Button>
            <Button type="submit" aria-disabled={busy || undefined}>
              {creditsPanel.set}
            </Button>
          </div>
          {fieldError ? (
            <p id={errorId} role="alert" className="admin__field-error">
              {fieldError}
            </p>
          ) : null}
        </form>
      ) : null}
      {alertText ? (
        <p role="alert" className="admin__field-error">
          {alertText}
        </p>
      ) : null}
      {row.source === 'OWNER' ? (
        <div className="credits-panel__actions">
          <Button
            className="btn--bare admin__quiet credits-panel__clear"
            aria-describedby={clearHintId}
            aria-disabled={busy || undefined}
            onClick={busy ? undefined : onClear}
          >
            {creditsPanel.clear}
          </Button>
          <span id={clearHintId} className="admin__set-hint">
            {creditsPanel.clearHint}
          </span>
        </div>
      ) : null}
    </div>
  );
}
