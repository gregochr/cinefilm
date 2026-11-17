import { useId } from 'react';
import { Button } from '../../components/Button.jsx';
import { buttons, settingsPage } from '../../state/adminCopy.js';
import AdminTodoNotice from './AdminTodoNotice.jsx';
import SectionTitle from './SectionTitle.jsx';
import {
  MAX_MINUTES,
  MIN_MINUTES,
  RANGE_ERROR,
  describeInProgress,
  useSettings,
} from '../../state/useSettings.js';
import { useSignOut } from '../../state/useSignOut.js';

/** A plain wordmark standing in for TMDB's logo asset, which is not bundled. */
function TmdbMark() {
  return (
    <svg
      className="admin__tmdb"
      viewBox="0 0 74 18"
      width="74"
      height="18"
      role="img"
      aria-label="TMDB"
    >
      <defs>
        <linearGradient id="tmdb-gradient" x1="0" x2="1">
          <stop offset="0" stopColor="#90cea1" />
          <stop offset="1" stopColor="#01b4e4" />
        </linearGradient>
      </defs>
      <text
        x="0"
        y="15"
        fontFamily="Inter, system-ui, sans-serif"
        fontWeight="700"
        fontSize="17"
        letterSpacing="1"
        fill="url(#tmdb-gradient)"
      >
        TMDB
      </text>
    </svg>
  );
}

/** The minimum watch time for Continue watching. It saves on blur or Enter: there is nothing to batch. */
function ContinueWatchingCard() {
  const { settings, loadError, loading, reload, draft, edit, commit, error, status } =
    useSettings();
  const inputId = useId();
  const helpId = useId();
  const errorId = useId();
  const rangeError = error === RANGE_ERROR;
  let body;
  if (settings) {
    const value = draft ?? String(settings.continueWatchingMinMinutes);
    body = (
      <div className="admin__set">
        <div className="admin__set-text">
          <label htmlFor={inputId} className="admin__set-label">
            {settingsPage.continueLabel}
          </label>
          <p id={helpId} className="admin__set-hint">
            {settingsPage.continueHint}
          </p>
          <p className="admin__mono admin__ratings-line">
            {describeInProgress(settings.inProgressCount)}
          </p>
        </div>
        <div className="admin__set-control">
          <div className="admin__setting-row">
            <input
              id={inputId}
              type="number"
              inputMode="numeric"
              className="admin__number"
              min={MIN_MINUTES}
              max={MAX_MINUTES}
              step={1}
              value={value}
              aria-invalid={rangeError ? 'true' : undefined}
              aria-describedby={rangeError ? `${helpId} ${errorId}` : helpId}
              onChange={(event) => edit(event.target.value)}
              onBlur={() => commit(draft)}
              onKeyDown={(event) => {
                if (event.key === 'Enter') {
                  event.preventDefault();
                  commit(draft);
                }
              }}
            />
            <span className="admin__suffix" aria-hidden="true">
              {settingsPage.continueUnit}
            </span>
          </div>
          {rangeError ? (
            <p id={errorId} className="admin__field-error">
              {error}
            </p>
          ) : null}
          <span className="admin__notice" role="status">
            {status ?? (rangeError ? null : error)}
          </span>
        </div>
      </div>
    );
  } else {
    body = (
      <div className="admin__card-pad">
        <AdminTodoNotice
          label={settingsPage.loadingLabel}
          error={loadError && !loading ? settingsPage.loadError : null}
          onRetry={reload}
        />
      </div>
    );
  }
  return (
    <section className="admin__card admin__card--rows" aria-label={settingsPage.continueCardLabel}>
      {body}
    </section>
  );
}

function SignedInCard() {
  const { signingOut, error, signOut } = useSignOut();
  const hintId = useId();
  return (
    <section className="admin__card admin__card--rows" aria-label={settingsPage.signedInLabel}>
      <div className="admin__set">
        <div className="admin__set-text">
          <p className="admin__set-label">{settingsPage.signedInLabel}</p>
          <p id={hintId} className="admin__set-hint">
            {buttons.signOut.hint}
          </p>
        </div>
        <div className="admin__set-control">
          {/* aria-disabled, not disabled: the button keeps focus while the request runs; the hook ignores a second press. */}
          <Button
            aria-describedby={hintId}
            aria-disabled={signingOut || undefined}
            onClick={signingOut ? undefined : signOut}
          >
            {signingOut ? buttons.signOut.busyLabel : buttons.signOut.label}
          </Button>
          {error ? (
            <span className="admin__notice" role="status">
              {error}
            </span>
          ) : null}
        </div>
      </div>
    </section>
  );
}

/**
 * Settings: the Continue watching minimum and the session, with the TMDB credit and the data credits (OpenSubtitles,
 * OMDb, IPinfo) at the bottom.
 */
export default function AdminSettings() {
  return (
    <>
      <SectionTitle>Settings</SectionTitle>
      <div className="admin__settings">
        <ContinueWatchingCard />
        <SignedInCard />
      </div>
      <p className="admin__credit">
        <TmdbMark />
        <span>{settingsPage.credit}</span>
      </p>
      <ul className="admin__credits" aria-label={settingsPage.dataCreditsLabel}>
        {settingsPage.dataCredits.map((line) => (
          <li key={line.key}>
            {line.parts.map((part) =>
              part.href ? (
                <a
                  key={part.text}
                  className="admin__credit-link"
                  href={part.href}
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  {part.text} <span className="sr-only">{settingsPage.opensInNewTab}</span>
                </a>
              ) : (
                <span key={part.text}>{part.text}</span>
              ),
            )}
          </li>
        ))}
      </ul>
    </>
  );
}
