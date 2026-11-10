import { useEffect, useId, useLayoutEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import { NEW, PART_OPTIONS, fileMeta, footNote, splitName } from '../state/editEditions.js';
import { useEditEditions } from '../state/useEditEditions.js';
import { Button } from './Button.jsx';
import { ErrorState } from './StateMessages.jsx';
import { useDialog } from './useDialog.js';

function FileName({ name, muted = false }) {
  const { head, tail } = splitName(name);
  return (
    <span
      // Focusable so the full name can be read from the keyboard; the name is also the hover title.
      // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex
      tabIndex={0}
      title={name}
      aria-label={`File ${name}`}
      className={`ee__name${muted ? ' ee__name--muted' : ''}`}
    >
      <span className="ee__name-head">{head}</span>
      <span className="ee__name-tail">{tail}</span>
    </span>
  );
}

function Skeleton() {
  return (
    <div role="status" aria-busy="true" aria-label="Loading files" className="ee__skeleton">
      <div className="ee__bone ee__bone--line" style={{ width: '70%' }} />
      {[1, 2, 3].map((n) => (
        <div key={n} className="ee__skel-row">
          <div className="ee__skel-text">
            <div className="ee__bone ee__bone--text" style={{ width: '80%' }} />
            <div className="ee__bone ee__bone--text" style={{ width: '45%', height: 12 }} />
          </div>
          <div className="ee__bone ee__bone--box" style={{ width: 200 }} />
          <div className="ee__bone ee__bone--box" style={{ width: 120 }} />
        </div>
      ))}
      <div className="ee__bone ee__bone--line" style={{ width: '40%', marginTop: 10 }} />
      <div className="ee__bone" style={{ width: '100%', height: 48, borderRadius: 10 }} />
    </div>
  );
}

function Row({ item, fixed, ids, locked, onEdition, onName, onPart, onNot }) {
  const { file, row, isNew, nameError } = item;
  const nameId = `${ids.base}-name-${file.id}`;
  const partDescribed = item.partsInError ? ids.partsError : undefined;
  return (
    <div role="group" aria-label={file.fileName} className="ee__row">
      <div className="ee__file">
        <FileName name={file.fileName} />
        <span className="ee__meta">
          {fileMeta(file)}
          {file.presence === 'MISSING' ? <span className="ee__missing">Missing</span> : null}
        </span>
      </div>
      <div className="ee__field">
        <label className="ee__label" htmlFor={`${ids.base}-ed-${file.id}`}>
          Edition
        </label>
        <select
          id={`${ids.base}-ed-${file.id}`}
          className="ee__select"
          disabled={locked}
          value={row.edition}
          onChange={(event) => onEdition(file.id, event.target.value)}
        >
          {fixed.map((f) => (
            <option key={f.key} value={f.key}>
              {f.name}
            </option>
          ))}
          <option value={NEW}>New edition…</option>
        </select>
        {isNew ? (
          <>
            <span className="ee__namebox">
              <input
                type="text"
                aria-label="New edition name"
                aria-invalid={nameError ? 'true' : undefined}
                aria-describedby={nameError ? nameId : undefined}
                className={`ee__input${nameError ? ' ee__input--bad' : ''}`}
                maxLength={32}
                disabled={locked}
                placeholder="Edition name"
                value={row.newName}
                onChange={(event) => onName(file.id, event.target.value)}
              />
              <span className="ee__count" aria-hidden="true">
                {row.newName.length}/32
              </span>
            </span>
            {nameError ? (
              <span role="alert" id={nameId} className="ee__error">
                {nameError}
              </span>
            ) : null}
          </>
        ) : null}
      </div>
      <div className="ee__field">
        <label className="ee__label" htmlFor={`${ids.base}-part-${file.id}`}>
          Part
        </label>
        <select
          id={`${ids.base}-part-${file.id}`}
          className={`ee__select${item.partsInError ? ' ee__select--bad' : ''}`}
          aria-invalid={item.partsInError ? 'true' : undefined}
          aria-describedby={partDescribed}
          disabled={locked}
          value={row.part}
          onChange={(event) => onPart(file.id, event.target.value)}
        >
          {PART_OPTIONS.map((o) => (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ))}
        </select>
      </div>
      <div className="ee__not-cell">
        <button
          type="button"
          className="ee__pill"
          disabled={locked}
          onClick={() => onNot(file.id, true)}
        >
          Not this film
        </button>
      </div>
    </div>
  );
}

function Groups({ edit, ids }) {
  const { view, fixed } = edit;
  return view.groups.map((group, g) => {
    const partsError = `${ids.base}-g${g}-err`;
    return (
      <section key={group.key} aria-label={group.label} className="ee__group">
        <div className="ee__group-head">
          <span className="ee__group-name">{group.name}</span>
          <span className="ee__group-summary">{group.summary}</span>
        </div>
        {group.error ? (
          <div role="alert" id={partsError} className="ee__group-error">
            {group.error}
          </div>
        ) : null}
        {group.rows.map((item) => (
          <Row
            key={item.file.id}
            item={{ ...item, partsInError: group.partsInError }}
            fixed={fixed}
            ids={{ base: ids.base, partsError }}
            locked={edit.saving}
            onEdition={edit.setEdition}
            onName={edit.setNewName}
            onPart={edit.setPart}
            onNot={edit.setNot}
          />
        ))}
      </section>
    );
  });
}

function NotThisFilm({ files, locked, onBack }) {
  if (files.length === 0) return null;
  return (
    <section aria-label="Not this film" className="ee__group ee__group--not">
      <div className="ee__not-head">
        <span className="ee__group-name">Not this film</span>
        <span className="ee__not-text">
          When you save, each of these becomes its own unmatched film. Fix it with Fix match in
          Admin.
        </span>
      </div>
      {files.map((file) => (
        <div key={file.id} role="group" aria-label={file.fileName} className="ee__not-row">
          <div className="ee__file">
            <FileName name={file.fileName} muted />
            <span className="ee__meta">{fileMeta(file)}</span>
          </div>
          <button
            type="button"
            className="ee__pill ee__pill--small"
            disabled={locked}
            onClick={() => onBack(file.id, false)}
          >
            Move back
          </button>
        </div>
      ))}
    </section>
  );
}

function Defaults({ edit, ids }) {
  const { view } = edit;
  const described = view.noEditions || view.noDefault ? ids.defaultError : undefined;
  return (
    <section aria-labelledby={`${ids.base}-eds`} className="ee__after">
      <h3 id={`${ids.base}-eds`} className="ee__after-title">
        Editions after saving
      </h3>
      <fieldset className="ee__defaults">
        <legend className="sr-only">Default edition</legend>
        {view.defaults.map((d) => (
          <label key={d.name} className={`ee__edition${d.on ? ' ee__edition--on' : ''}`}>
            <input
              type="radio"
              name={`${ids.base}-default`}
              className="ee__radio"
              checked={d.on}
              disabled={edit.saving}
              aria-describedby={described}
              onChange={() => edit.setDefault(d.name)}
            />
            <span className="ee__edition-text">
              <span className="ee__edition-name">{d.name}</span>
              <span className="ee__edition-meta">{d.meta}</span>
            </span>
            {d.on ? <span className="ee__default-label">Default edition</span> : null}
          </label>
        ))}
        {view.noEditions ? (
          <span role="alert" id={ids.defaultError} className="ee__error">
            {view.noEditions}
          </span>
        ) : null}
        {view.noDefault ? (
          <span role="alert" id={ids.defaultError} className="ee__error">
            {view.noDefault}
          </span>
        ) : null}
      </fieldset>
    </section>
  );
}

function Body({ edit, ids }) {
  const { view } = edit;
  return (
    <div className="ee__stack">
      {edit.problem ? (
        <div role="alert" className="ee__banner">
          <svg
            width="18"
            height="18"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            aria-hidden="true"
          >
            <circle cx="12" cy="12" r="9" />
            <path d="M12 7v6M12 16.5v.5" />
          </svg>
          <span>
            <strong>Couldn’t save.</strong> {edit.problem}
          </span>
        </div>
      ) : null}
      {view.other.map((message) => (
        <p key={message} role="alert" className="ee__error">
          {message}
        </p>
      ))}
      {edit.reason ? (
        <div className="ee__suggest">
          <span className="ee__suggested">Suggested</span>
          <span>{edit.reason}</span>
        </div>
      ) : null}
      <Groups edit={edit} ids={ids} />
      <NotThisFilm files={view.notRows} locked={edit.saving} onBack={edit.setNot} />
      <Defaults edit={edit} ids={ids} />
    </div>
  );
}

/**
 * The Edit editions dialog (a full-screen sheet on phones): arrange a film's files into editions and parts, pick the
 * default, or split off files that are not this film. `onSaved` runs once the save went through; `onClose` on
 * Cancel, ×, or Esc (there is no confirm prompt: this is a single-user app). `opener` is the element focus goes back
 * to when the dialog closes (else whatever had it when it opened).
 */
export default function EditEditions({ film, opener, onClose, onSaved }) {
  const base = useId();
  const titleId = `${base}-title`;
  const subId = `${base}-sub`;
  const ids = { base, defaultError: `${base}-default-error` };
  const dialog = useDialog({ onEscape: () => dialog.close(onClose), opener });
  const { dialogRef, closing, close } = dialog;
  const edit = useEditEditions(film.id, (result) => close(() => onSaved(result)));
  const loading = edit.status === 'loading';

  useEffect(() => {
    // Focus goes to the first Edition select, or to Close while there is nothing else to land on.
    const root = dialogRef.current;
    const active = document.activeElement;
    const first = edit.status === 'ready' ? root.querySelector('select') : null;
    if (first && (!root.contains(active) || active.getAttribute('aria-label') === 'Close')) {
      first.focus();
    } else if (!root.contains(active)) {
      root.querySelector('[aria-label="Close"]').focus();
    }
  }, [edit.status, dialogRef]);

  // Moving a row to another group remounts it: put focus back on the control the person was using.
  const focused = useRef(null);
  useLayoutEffect(() => {
    const id = focused.current;
    if (id && document.activeElement === document.body) document.getElementById(id)?.focus();
  });

  const errors = edit.view?.errorCount ?? 0;
  const saveDisabled = loading || edit.status === 'error' || errors > 0 || edit.saving;

  return createPortal(
    <div className={`ee${closing ? ' ee--closing' : ''}`}>
      <div className="ee__backdrop" aria-hidden="true" />
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={subId}
        className="ee__dialog"
        onFocus={(event) => {
          focused.current = event.target.id || null;
        }}
      >
        <header className="ee__header">
          <div className="ee__heading">
            <span id={subId} className="ee__eyebrow">
              Edit editions
            </span>
            <h2 id={titleId} className="ee__title">
              {film.title}
              {film.year ? (
                <>
                  {' '}
                  <span className="ee__year">· {film.year}</span>
                </>
              ) : null}
            </h2>
          </div>
          <button
            type="button"
            className="ee__close"
            aria-label="Close"
            onClick={() => close(onClose)}
          >
            <svg
              width="16"
              height="16"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              aria-hidden="true"
            >
              <path d="M6 6l12 12M18 6L6 18" />
            </svg>
          </button>
        </header>
        <div className="ee__body">
          {loading ? <Skeleton /> : null}
          {edit.status === 'error' ? (
            <ErrorState onRetry={edit.retry}>Couldn’t load this film’s files.</ErrorState>
          ) : null}
          {edit.status === 'ready' ? <Body edit={edit} ids={ids} /> : null}
        </div>
        <footer className="ee__footer">
          <span className="ee__foot-note">{footNote(loading, errors)}</span>
          <div className="ee__foot-buttons">
            <Button className="ee__cancel" onClick={() => close(onClose)}>
              Cancel
            </Button>
            <Button
              variant="primary"
              className="ee__save"
              disabled={saveDisabled}
              onClick={edit.save}
            >
              {edit.saving ? 'Saving…' : 'Save'}
            </Button>
          </div>
        </footer>
      </div>
    </div>,
    document.body,
  );
}
