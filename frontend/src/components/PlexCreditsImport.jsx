import { useId } from 'react';
import { Button } from './Button.jsx';
import { SkeletonBlock } from './Skeleton.jsx';
import { progressPercent } from '../state/scanFormat.js';
import {
  creditsProgressText,
  creditsSkipLines,
  creditsTiles,
  describeLastCreditsImport,
} from '../state/plexCreditsFormat.js';
import { usePlexCreditsImport } from '../state/useAdmin.js';

/**
 * "Import credits from Plex", inside the Plex import card: copies Plex's credits markers once and stores them for
 * checking. Read-only towards Plex, and nothing is applied to a film yet. `reachable` is the card's own reading of
 * whether Plex answers.
 */
export default function PlexCreditsImport({ reachable }) {
  const titleId = useId();
  const { credits, error, loading, starting, notice, announcement, checking, startImport, reload } =
    usePlexCreditsImport();
  const running = Boolean(credits?.running);
  return (
    <div className="admin__report-list" role="group" aria-labelledby={titleId}>
      <h3 id={titleId} className="admin__subtitle">
        Credits from Plex
      </h3>
      <p className="admin__text">
        Copies the credits start Plex holds for each film, once, so it can be checked against what
        CineFilm finds. Nothing is changed in Plex, and no film uses these yet.
      </p>
      {!credits && loading ? (
        <div role="status" aria-label="Loading credits import">
          <SkeletonBlock style={{ height: 48 }} />
        </div>
      ) : null}
      {!loading && error ? (
        <div className="admin__actions">
          <span className="admin__notice" role="alert">
            {credits
              ? 'Couldn\u2019t refresh the credits import status.'
              : 'Couldn\u2019t load the credits import status.'}
          </span>
          <Button variant="secondary" aria-busy={checking} onClick={reload}>
            {checking ? 'Checking\u2026' : 'Check again'}
          </Button>
        </div>
      ) : null}
      {credits ? <CreditsStatus credits={credits} /> : null}
      <div className="admin__actions">
        <Button
          variant="secondary"
          disabled={running || starting || !reachable || !credits}
          onClick={startImport}
        >
          {running ? 'Importing credits…' : 'Import credits from Plex'}
        </Button>
        <span className="admin__notice" role="status">
          {announcement}
        </span>
        {notice ? (
          <span className="admin__notice" role="status">
            {notice}
          </span>
        ) : null}
      </div>
    </div>
  );
}

/** The progress, last import and report of a loaded status. */
function CreditsStatus({ credits }) {
  const running = Boolean(credits.running);
  const last = credits.lastImport;
  return (
    <>
      {running && credits.progress ? (
        <div className="admin__progress">
          <div
            className="admin__bar"
            role="progressbar"
            aria-label="Credits import progress"
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={progressPercent(credits.progress)}
            aria-valuetext={creditsProgressText(credits.progress)}
          >
            <div
              className="admin__fill"
              style={{ width: `${progressPercent(credits.progress)}%` }}
            />
          </div>
          <div className="admin__progress-line">
            <span className="admin__mono">{creditsProgressText(credits.progress)}</span>
          </div>
        </div>
      ) : null}
      <dl className="admin__rows">
        <dt>Last credits import</dt>
        <dd className="admin__mono">{describeLastCreditsImport(last)}</dd>
      </dl>
      {last?.report ? (
        <>
          <ul className="admin__counts" aria-label="Credits import report">
            {creditsTiles(last.report).map((tile) => (
              <li key={tile.key} className="admin__count">
                <span className="admin__count-label">{tile.label}</span>
                <span className="admin__count-value">{tile.value}</span>
              </li>
            ))}
          </ul>
          {last.report.skipped.length > 0 ? (
            <ul className="admin__candidates" aria-label="Not stored">
              {creditsSkipLines(last.report).map((line) => (
                <li key={line.key}>{line.text}</li>
              ))}
            </ul>
          ) : null}
        </>
      ) : null}
    </>
  );
}
