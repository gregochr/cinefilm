import { useEffect, useId, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { announceLibraryChanged } from '../api/client.js';
import AddOwnFile from '../screens/admin/AddOwnFile.jsx';
import { buttons } from '../state/adminCopy.js';
import {
  REMOVED_NOTICE,
  displayName,
  fileName,
  isDownload,
  originLine,
  placement,
  reportLine,
  servedLine,
  warningsFor,
} from '../state/subtitleAdmin.js';
import { fileTools, filmFiles, kindTag } from '../state/subtitleFix.js';
import { subtitleCheck as check, subtitleFix as copy } from '../state/subtitleFixCopy.js';
import { cueResourceKey, useSubtitles } from '../state/useSubtitles.js';
import { titleWithYear } from '../state/scanFormat.js';
import { ActionWithHint } from './ActionWithHint.jsx';
import { Button } from './Button.jsx';
import RemoveDownload from './RemoveDownload.jsx';
import { SkeletonBlock } from './Skeleton.jsx';
import { ErrorState } from './StateMessages.jsx';
import SubtitleCueList from './SubtitleCueList.jsx';
import { useDialog } from './useDialog.js';

/**
 * One subtitle file of the film with the tools that apply to it (`fileTools`). While any action runs (`busy`) every
 * tool is `aria-disabled`, not `disabled`: a real `disabled` drops the pressed button's focus to the page, and a failed
 * action would leave the viewer nowhere. The button stays focusable and a press does nothing.
 */
function FixFile({ item, busy, problem, actions, onRemoved, onDone }) {
  const [previewing, setPreviewing] = useState(false);
  const cuesId = useId();
  const tools = fileTools(item);
  const origin = originLine(item);
  const name = displayName(item);
  const served = servedLine(item);
  return (
    <li className="admin__item">
      <div className="admin__item-head">
        {isDownload(item) ? (
          <>
            <span className="admin__item-title">{origin.title}</span>
            {origin.release ? (
              <span className="admin__mono subs__release">{origin.release}</span>
            ) : null}
          </>
        ) : (
          <>
            <span className="admin__item-title">{copy.yourFile}</span>
            <span className="admin__mono subs__release">{fileName(item.path)}</span>
          </>
        )}
        <span className="admin__tag">{kindTag(item)}</span>
        {item.served ? <span className="admin__tag">{copy.playing}</span> : null}
      </div>
      {served ? <p className="subs__served">{served}</p> : null}
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
      {tools.undoCheck ? <p className="admin__text">{check.checked}</p> : null}
      <div className="fix__actions">
        {tools.looksRight ? (
          <ActionWithHint
            layout="line"
            hint={check.hint}
            aria-label={`${check.label}: ${name}`}
            aria-disabled={busy || undefined}
            onClick={async () => {
              if (busy) return;
              if (await actions.verify(item.id)) onDone(check.done);
            }}
          >
            {check.label}
          </ActionWithHint>
        ) : null}
        {tools.undoCheck ? (
          <Button
            aria-label={`${check.undo}: ${check.label}, ${name}`}
            aria-disabled={busy || undefined}
            onClick={async () => {
              if (busy) return;
              // The server's answer says whether the download plays again (it recomputes the row).
              const row = await actions.unverify(item.id);
              if (row)
                onDone(row.replacedByDownload === true ? check.undoneReplaced : check.undone);
            }}
          >
            {check.undo}
          </Button>
        ) : null}
        {tools.useMine ? (
          <ActionWithHint
            layout="line"
            hint={buttons.useMyFileInstead.hint}
            aria-label={`${buttons.useMyFileInstead.label}: ${name}`}
            aria-disabled={busy || undefined}
            onClick={async () => {
              if (busy) return;
              if (await actions.verify(item.id)) onDone(copy.usedMine);
            }}
          >
            {buttons.useMyFileInstead.label}
          </ActionWithHint>
        ) : null}
        {tools.letDecide ? (
          <ActionWithHint
            layout="line"
            hint={buttons.letCineFilmDecide.hint}
            aria-label={`${buttons.letCineFilmDecide.label}: ${name}`}
            aria-disabled={busy || undefined}
            onClick={async () => {
              if (busy) return;
              if (await actions.detach(item.id)) onDone(copy.decided);
            }}
          >
            {buttons.letCineFilmDecide.label}
          </ActionWithHint>
        ) : null}
        <ActionWithHint
          layout="line"
          hint={previewing ? undefined : buttons.previewLines.hint}
          aria-label={`${previewing ? copy.hideLines : buttons.previewLines.label}: ${name}`}
          aria-expanded={previewing}
          aria-controls={previewing ? cuesId : undefined}
          onClick={() => setPreviewing(!previewing)}
        >
          {previewing ? copy.hideLines : buttons.previewLines.label}
        </ActionWithHint>
        {tools.deleteDownload ? (
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
      {previewing ? (
        <div className="subs__details">
          {/* A new key remounts the list, so lines for another attachment, offset or version are never shown. */}
          <SubtitleCueList key={cueResourceKey(item)} item={item} id={cuesId} />
        </div>
      ) : null}
    </li>
  );
}

/** The list of the film's files, or why there is none. */
function Files({ subtitles, files, onRemoved, onDone }) {
  const { items, loading, error, reload } = subtitles;
  if (loading && !items) {
    return (
      <div role="status" aria-label={copy.loading}>
        <SkeletonBlock style={{ height: 120 }} />
      </div>
    );
  }
  if (error && !items) return <ErrorState onRetry={reload}>{copy.loadError}</ErrorState>;
  return (
    <>
      {error ? <ErrorState onRetry={reload}>{copy.refreshError}</ErrorState> : null}
      {files.length === 0 ? (
        <p className="admin__text">{copy.none}</p>
      ) : (
        <ul className="admin__list" aria-label={copy.listLabel}>
          {files.map((item) => (
            <FixFile
              key={item.id}
              item={item}
              busy={subtitles.busy !== null}
              problem={subtitles.problem?.id === item.id ? subtitles.problem.text : null}
              actions={subtitles}
              onRemoved={onRemoved}
              onDone={onDone}
            />
          ))}
        </ul>
      )}
    </>
  );
}

/**
 * Film detail's "Subtitles → Fix…": a dialog over the film's subtitle files with the per-file tools that moved out of
 * Admin ("Looks right", "Use my file instead", "Preview lines", "Delete this download", "Let CineFilm decide") and "Add
 * my own file…", the existing attach route scoped to this film. It reuses Admin's `useSubtitles` (one copy of the rules
 * and the one-request-at-a-time guard). `onChanged` runs after every change that succeeded, even one that finishes
 * after the dialog was closed, so the film page reads again. `opener` is where focus goes back to on close. Timing is
 * not here: it is changed in the player (8b-1).
 */
export default function FilmSubtitlesFix({ film, opener, onClose, onChanged }) {
  const base = useId();
  const titleId = `${base}-title`;
  const dialog = useDialog({ onEscape: () => dialog.close(onClose), opener });
  const { dialogRef, closing, close } = dialog;
  const subtitles = useSubtitles(`film:${film.id}`, onChanged);
  const [adding, setAdding] = useState(false);
  const [attachBusy, setAttachBusy] = useState(false);
  // The status region stays mounted for the dialog's life and only its text changes (one inserted already filled is
  // often not spoken). `text` is what was last said, `shown` what the region holds: a new notice empties the region for
  // one commit before it fills, so the same words said twice are still a change a screen reader announces.
  const [notice, setNotice] = useState({ n: 0, text: '', shown: '' });
  const closeRef = useRef(null);
  const noticeRef = useRef(null);
  const addRef = useRef(null);
  useEffect(() => {
    closeRef.current?.focus();
  }, []);
  useEffect(() => {
    if (notice.shown !== notice.text) {
      setNotice((prev) => (prev.n === notice.n ? { ...prev, shown: prev.text } : prev));
    }
  }, [notice]);
  // The button that was used is gone (the row left, or its tool no longer applies) and took focus with it: the notice
  // takes it. Only when focus was lost (on the page): not when the viewer moved on to something else while the request
  // ran, nor when another modal (the sign-in card) is on top.
  useEffect(() => {
    if (notice.n === 0 || notice.shown === '') return;
    const active = document.activeElement;
    const lost = !active || active === document.body;
    if (lost) noticeRef.current?.focus();
  }, [notice.n, notice.shown]);
  const say = (text) => setNotice((prev) => ({ n: prev.n + 1, text, shown: '' }));
  const files = subtitles.items ? filmFiles(subtitles.items, film.id) : [];
  const attached = () => {
    announceLibraryChanged();
    onChanged();
    subtitles.reload();
    setAdding(false);
    // The form that held focus is gone: back to the button that opened it.
    addRef.current?.focus();
  };
  return createPortal(
    <div className={`ee${closing ? ' ee--closing' : ''}`}>
      <div className="ee__backdrop" aria-hidden="true" />
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className="ee__dialog"
      >
        <header className="ee__header">
          <div className="ee__heading">
            <span className="ee__eyebrow">{copy.eyebrow}</span>
            <h2 id={titleId} className="ee__title">
              {titleWithYear(film.title, film.year)}
            </h2>
          </div>
          <button
            type="button"
            ref={closeRef}
            className="ee__close"
            aria-label={copy.close}
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
          <div className="ee__stack">
            <p className="admin__text">{copy.lead}</p>
            <p className="admin__text subs__notice" role="status" tabIndex={-1} ref={noticeRef}>
              {notice.shown}
            </p>
            <Files
              subtitles={subtitles}
              files={files}
              onRemoved={() => say(REMOVED_NOTICE)}
              onDone={say}
            />
            <div className="fix__add">
              <ActionWithHint
                layout="line"
                ref={addRef}
                hint={buttons.addFileToFilm.hint}
                aria-expanded={adding}
                // Not while an attach is in flight: closing the form then would let it be opened again with its
                // one-request guard reset, and a second attach could start beside the first.
                aria-disabled={attachBusy || undefined}
                onClick={() => {
                  if (!attachBusy) setAdding(!adding);
                }}
              >
                {buttons.addFileToFilm.label}
              </ActionWithHint>
              {adding ? (
                <AddOwnFile
                  allKinds
                  filmId={film.id}
                  title={film.title}
                  onAttached={attached}
                  onCancel={() => setAdding(false)}
                  onBusyChange={setAttachBusy}
                />
              ) : null}
            </div>
          </div>
        </div>
      </div>
    </div>,
    document.body,
  );
}
