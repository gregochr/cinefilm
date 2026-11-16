import { useId, useRef, useEffect } from 'react';
import { createPortal } from 'react-dom';
import { imageUrl } from '../api/images.js';
import { useFixMatch } from '../state/useFixMatch.js';
import { resultPeople, resultRuntime, resultTitle } from '../state/fixMatch.js';
import { Button } from './Button.jsx';
import { SkeletonBlock } from './Skeleton.jsx';
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

function Results({ search, picked, onPick }) {
  if (search.status === 'loading') {
    return (
      <div role="status" aria-label="Searching TMDB">
        <SkeletonBlock style={{ height: 96 }} />
      </div>
    );
  }
  if (search.status === 'error') {
    return <Problem>{search.problem}</Problem>;
  }
  if (search.results.length === 0) {
    return <p className="admin__text">TMDB has nothing for that. Try another title or year.</p>;
  }
  return (
    <fieldset className="fixmatch__results">
      <legend className="admin__text">Pick the right film</legend>
      {search.results.map((result) => {
        const poster = imageUrl(result.posterPath);
        const runtime = resultRuntime(result);
        return (
          <label
            key={result.tmdbId}
            className={`fixmatch__result${picked === result.tmdbId ? ' fixmatch__result--on' : ''}`}
          >
            <input
              type="radio"
              name="match"
              checked={picked === result.tmdbId}
              onChange={() => onPick(result.tmdbId)}
            />
            {poster ? (
              <img className="fixmatch__poster" src={poster} alt="" loading="lazy" />
            ) : (
              <span className="fixmatch__poster" aria-hidden="true" />
            )}
            <span className="fixmatch__text">
              <span className="fixmatch__name">{resultTitle(result)}</span>
              <span className="admin__text">{resultPeople(result)}</span>
            </span>
            {runtime ? <span className="admin__mono fixmatch__runtime">{runtime}</span> : null}
          </label>
        );
      })}
    </fieldset>
  );
}

/**
 * The Fix match dialog (a full-screen sheet on phones): search TMDB for a film that needs a match and pick the right
 * one. Focus opens in the search box and goes back to `opener` on close. `onCancel` runs on Cancel, the close button
 * or Esc (also while an apply is running); `onApplied(result, stale)` runs once the match is applied, after the
 * dialog has faded out. An apply that finishes after the dialog was dismissed is still reported at once, so the
 * lists are read again, and `stale` is then true because the film is no longer on screen.
 */
export default function FixMatchPanel({ item, opener, onApplied, onCancel }) {
  const base = useId();
  const titleId = `${base}-title`;
  const subId = `${base}-sub`;
  const searchRef = useRef(null);
  // `useDialog.close` runs only its first `after`, so Cancel, ×, Esc and a successful apply close once, and the
  // buttons are disabled while `closing`. `dismissed` is set by Cancel, × and Esc only and decides how an apply that
  // finishes is reported: after a dismissal the unmount that ends the fade would drop a second fade's timer, so the
  // result is reported at once (the lists must still be read again); otherwise it fades first.
  const dismissed = useRef(false);
  const dismiss = () => {
    dismissed.current = true;
    dialog.close(onCancel);
  };
  const dialog = useDialog({ onEscape: dismiss, opener });
  const { dialogRef, closing, close } = dialog;
  const fix = useFixMatch(item.filmId, item.title, (result, stale) => {
    if (stale || dismissed.current) {
      onApplied(result, stale);
      return;
    }
    close(() => onApplied(result, false));
  });
  const { search } = fix;

  useEffect(() => {
    searchRef.current?.focus();
  }, []);

  return createPortal(
    <div className={`ee${closing ? ' ee--closing' : ''}`}>
      <div className="ee__backdrop" aria-hidden="true" />
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={subId}
        className="ee__dialog fixmatch"
      >
        <header className="ee__header">
          <div className="ee__heading">
            <span id={subId} className="ee__eyebrow admin__mono">
              File: {item.files.join(' · ')}
            </span>
            <h2 id={titleId} className="ee__title">
              Fix match
            </h2>
          </div>
          <button type="button" className="ee__close" aria-label="Close" onClick={dismiss}>
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
            <form
              className="fixmatch__form"
              onSubmit={(event) => {
                event.preventDefault();
                fix.submit();
              }}
            >
              <label className="fixmatch__field fixmatch__field--query">
                Search TMDB
                <input
                  ref={searchRef}
                  type="search"
                  className="fixmatch__input"
                  value={fix.query}
                  onChange={(event) => fix.setQuery(event.target.value)}
                />
              </label>
              <label className="fixmatch__field fixmatch__field--year">
                Year (optional)
                <input
                  type="text"
                  inputMode="numeric"
                  maxLength={4}
                  className="fixmatch__input"
                  value={fix.year}
                  onChange={(event) => fix.setYear(event.target.value)}
                />
              </label>
              <Button type="submit" disabled={search.status === 'loading' || closing}>
                Search
              </Button>
            </form>
            <Results search={search} picked={fix.picked} onPick={fix.setPicked} />
            {fix.applyProblem ? <Problem>{fix.applyProblem}</Problem> : null}
          </div>
        </div>
        <footer className="ee__footer">
          <div className="ee__foot-buttons">
            <Button className="ee__cancel" onClick={dismiss}>
              Cancel
            </Button>
            <Button
              variant="primary"
              disabled={fix.picked == null || fix.applying || closing}
              onClick={fix.apply}
            >
              {fix.applying ? 'Applying…' : 'Use this match'}
            </Button>
          </div>
        </footer>
      </div>
    </div>,
    document.body,
  );
}
