import { useId } from 'react';
import { Button } from '../../components/Button.jsx';
import SectionTitle from './SectionTitle.jsx';
import { SkeletonBlock } from '../../components/Skeleton.jsx';
import { ErrorState } from '../../components/StateMessages.jsx';
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
function ContinueWatchingPanel() {
  const { settings, loadError, loading, reload, draft, edit, commit, error, status } =
    useSettings();
  const inputId = useId();
  const helpId = useId();
  const errorId = useId();
  let body;
  if (loading && !settings) {
    body = (
      <div role="status" aria-label="Loading">
        <SkeletonBlock style={{ height: 120 }} />
      </div>
    );
  } else if (loadError && !settings) {
    body = <ErrorState onRetry={reload}>Couldn&rsquo;t load the settings.</ErrorState>;
  } else if (settings) {
    const value = draft ?? String(settings.continueWatchingMinMinutes);
    body = (
      <>
        <div className="admin__setting">
          <label htmlFor={inputId} className="admin__setting-label">
            Minimum watched before a film counts as in progress
          </label>
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
              aria-invalid={error === RANGE_ERROR ? 'true' : undefined}
              aria-describedby={error === RANGE_ERROR ? `${helpId} ${errorId}` : helpId}
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
              minutes
            </span>
          </div>
          <p id={helpId} className="admin__text">
            Films you&rsquo;ve watched for less than this stay out of Continue watching. Their place
            is still saved.
          </p>
          {error === RANGE_ERROR ? (
            <p id={errorId} className="admin__field-error">
              {error}
            </p>
          ) : null}
        </div>
        <p className="admin__mono admin__ratings-line">
          {describeInProgress(settings.inProgressCount)}
        </p>
      </>
    );
  }
  return (
    <section className="admin__card" aria-labelledby="admin-continue-title">
      <div className="admin__head">
        <h2 id="admin-continue-title" className="admin__title">
          Continue watching
        </h2>
        <span className="admin__notice" role="status">
          {status ?? (error === RANGE_ERROR ? null : error)}
        </span>
      </div>
      {body}
    </section>
  );
}

function SessionPanel() {
  const { signingOut, error, signOut } = useSignOut();
  return (
    <section className="admin__card" aria-labelledby="admin-session-title">
      <div className="admin__head">
        <h2 id="admin-session-title" className="admin__title">
          Session
        </h2>
      </div>
      <p className="admin__text">Signed in to this browser.</p>
      <div className="admin__actions">
        <Button variant="secondary" disabled={signingOut} onClick={signOut}>
          {signingOut ? 'Signing out…' : 'Sign out'}
        </Button>
        {error ? (
          <span className="admin__notice" role="status">
            {error}
          </span>
        ) : null}
      </div>
    </section>
  );
}

/** Settings: Continue watching and the session, with the TMDB credit at the bottom. */
export default function AdminSettings() {
  return (
    <>
      <SectionTitle>Settings</SectionTitle>
      <div className="admin__grid">
        <ContinueWatchingPanel />
        <SessionPanel />
      </div>
      <p className="admin__credit">
        <TmdbMark />
        <span>This product uses the TMDB API but is not endorsed or certified by TMDB.</span>
      </p>
    </>
  );
}
