import { useEffect, useId, useRef } from 'react';
import { createPortal } from 'react-dom';
import { subtitlePicker as copy } from '../state/adminCopy.js';
import { resultDetails, resultName } from '../state/subtitlePicker.js';
import { useSubtitlePicker } from '../state/useSubtitlePicker.js';
import { titleWithYear } from '../state/scanFormat.js';
import { Button } from './Button.jsx';
import { useDialog } from './useDialog.js';

function Problem({ children }) {
  return (
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
      <span>{children}</span>
    </div>
  );
}

function Skeleton() {
  return (
    <div role="status" aria-busy="true" aria-label={copy.loading} className="ee__skeleton">
      {[1, 2, 3].map((n) => (
        <div key={n} className="ee__skel-row">
          <div className="ee__skel-text">
            <div className="ee__bone ee__bone--text" style={{ width: '80%' }} />
            <div className="ee__bone ee__bone--text" style={{ width: '45%', height: 12 }} />
          </div>
          <div className="ee__bone ee__bone--box" style={{ width: 130 }} />
        </div>
      ))}
    </div>
  );
}

function ResultRow({ result, downloading, onChoose }) {
  const name = resultName(result);
  const here = downloading === result.fileId;
  return (
    <li className="sp__row">
      <div className="sp__text">
        <span className="sp__name">{name}</span>
        <span className="sp__meta">{resultDetails(result).join(' · ')}</span>
      </div>
      {/* aria-disabled, not disabled: the button keeps focus while its request runs; the hook ignores clicks. */}
      <Button
        className="sp__choose"
        aria-label={copy.chooseName(name)}
        aria-disabled={downloading != null || undefined}
        aria-busy={here || undefined}
        onClick={() => onChoose(result)}
      >
        {here ? copy.choose.busyLabel : copy.choose.label}
      </Button>
    </li>
  );
}

/**
 * The OpenSubtitles picker: a dialog (a full-screen sheet on phones) listing the files OpenSubtitles has for one
 * edition (`editionName` says which cut when the film has several), best match first as the server ranks them. Choosing one downloads it; `onPicked(answer)` runs once that went
 * through (the layout closes the dialog and reads the lists again). A failed search or download says so in the dialog,
 * in the server's own words. `onClose` runs on Cancel, the close button or Esc, also while a download is running (its
 * late answer is then ignored here, see `useSubtitlePicker`). `opener` is the element focus goes back to on close.
 */
export default function SubtitlePicker({
  film,
  editionId,
  editionName,
  kind,
  opener,
  onClose,
  onPicked,
}) {
  const base = useId();
  const titleId = `${base}-title`;
  const subId = `${base}-sub`;
  // Set when Cancel, Close or Esc starts the fade-out: a file that arrives in that window is reported at once, because
  // the unmount that ends the fade would cancel a second fade's timer and the lists would never read again.
  const dismissed = useRef(false);
  const dismiss = () => {
    dismissed.current = true;
    dialog.close(onClose);
  };
  const dialog = useDialog({ onEscape: dismiss, opener });
  const { dialogRef, closing, close } = dialog;
  const picker = useSubtitlePicker({
    editionId,
    kind,
    onDownloaded: (answer, { open }) =>
      open && !dismissed.current ? close(() => onPicked(answer)) : onPicked(answer),
  });

  useEffect(() => {
    dialogRef.current.querySelector('[aria-label="Close"]').focus();
  }, [dialogRef]);

  const ready = picker.status === 'ready';
  return createPortal(
    <div className={`ee${closing ? ' ee--closing' : ''}`}>
      <div className="ee__backdrop" aria-hidden="true" />
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={subId}
        className="ee__dialog sp"
      >
        <header className="ee__header">
          <div className="ee__heading">
            <span id={subId} className="ee__eyebrow">
              {copy.eyebrow[kind]}
            </span>
            <h2 id={titleId} className="ee__title">
              {titleWithYear(film.title, film.year)}
              {editionName ? (
                <>
                  {' '}
                  <span className="ee__year">{copy.editionSuffix(editionName)}</span>
                </>
              ) : null}
            </h2>
          </div>
          <button type="button" className="ee__close" aria-label={copy.close} onClick={dismiss}>
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
            <p className="sp__lead">{copy.lead[kind]}</p>
            {picker.problem ? <Problem>{picker.problem}</Problem> : null}
            {picker.status === 'loading' ? <Skeleton /> : null}
            {picker.status === 'error' ? (
              <>
                <Problem>{picker.searchProblem}</Problem>
                <div>
                  <Button onClick={picker.retry}>{copy.retry}</Button>
                </div>
              </>
            ) : null}
            {ready && picker.results.length === 0 ? (
              <p className="sp__empty">{copy.empty}</p>
            ) : null}
            {ready && picker.results.length > 0 ? (
              <ul className="sp__list" aria-label={copy.resultsLabel}>
                {picker.results.map((result) => (
                  <ResultRow
                    key={result.fileId}
                    result={result}
                    downloading={picker.downloading}
                    onChoose={picker.choose}
                  />
                ))}
              </ul>
            ) : null}
          </div>
        </div>
        <footer className="ee__footer">
          <span className="ee__foot-note">
            {ready && picker.downloadsLeft != null ? copy.left(picker.downloadsLeft) : ''}
          </span>
          <div className="ee__foot-buttons">
            <Button className="ee__cancel" onClick={dismiss}>
              {copy.cancel}
            </Button>
          </div>
        </footer>
      </div>
    </div>,
    document.body,
  );
}
