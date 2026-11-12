import { useEffect, useId, useState } from 'react';
import { useLocation } from 'react-router-dom';
import { Button } from '../components/Button.jsx';
import { SkeletonBlock } from '../components/Skeleton.jsx';
import EditEditions from '../components/EditEditions.jsx';
import FixMatchPanel from '../components/FixMatchPanel.jsx';
import PlaybackHealthPanel from '../components/PlaybackHealthPanel.jsx';
import SubtitlesPanel from '../components/SubtitlesPanel.jsx';
import Toast from '../components/Toast.jsx';
import TranscodingPanel from '../components/TranscodingPanel.jsx';
import { ErrorState } from '../components/StateMessages.jsx';
import { formatRuntime } from '../player/time.js';
import {
  MAX_MINUTES,
  MIN_MINUTES,
  RANGE_ERROR,
  describeInProgress,
  useSettings,
} from '../state/useSettings.js';
import { useSignOut } from '../state/useSignOut.js';
import { useToast } from '../state/useToast.js';
import { useAttention, useGroupingReport, usePlexImport, useScan } from '../state/useAdmin.js';
import {
  describeLastImport,
  moreText,
  plexPillLabel,
  plexProgressText,
  reportLists,
  reportTiles,
} from '../state/plexFormat.js';
import { describeRatingsRefresh } from '../state/ratings.js';
import {
  changeLabel,
  countTiles,
  describeLastScan,
  describeSubtitleSearch,
  formatScanTime,
  kindLabel,
  progressPercent,
  progressText,
  titleWithYear,
} from '../state/scanFormat.js';

function SubtitleSearchLine({ scan }) {
  const text = describeSubtitleSearch(
    {
      enabled: Boolean(scan.openSubtitlesEnabled),
      running: Boolean(scan.openSubtitlesRunning),
      lastSearchedAt: scan.openSubtitlesLastSearchedAt,
    },
    formatScanTime,
  );
  if (!text) return null;
  return (
    <p className="admin__mono admin__ratings-line" role="status">
      {text}
    </p>
  );
}

function RatingsLine({ scan, starting, notice, onRefresh }) {
  const running = Boolean(scan.ratingsRunning);
  const enabled = Boolean(scan.ratingsEnabled);
  return (
    <div className="admin__ratings">
      <p className="admin__mono admin__ratings-line">
        {enabled
          ? describeRatingsRefresh(scan.ratingsLastRefreshedAt, formatScanTime)
          : 'Ratings need an OMDb API key'}
      </p>
      <div className="admin__actions">
        <Button disabled={!enabled || running || starting} onClick={onRefresh}>
          {running ? 'Refreshing ratings…' : 'Refresh ratings'}
        </Button>
        {notice ? (
          <span className="admin__notice" role="status">
            {notice}
          </span>
        ) : null}
      </div>
    </div>
  );
}

function ScanPanel({
  scan,
  loading,
  error,
  starting,
  notice,
  ratingsStarting,
  ratingsNotice,
  onScan,
  onRefreshRatings,
  onRetry,
}) {
  let body;
  if (loading && !scan) {
    body = (
      <div role="status" aria-label="Loading">
        <SkeletonBlock style={{ height: 120 }} />
      </div>
    );
  } else if (error && !scan) {
    body = <ErrorState onRetry={onRetry}>Couldn&rsquo;t load the library scan.</ErrorState>;
  } else if (scan) {
    const running = scan.running;
    body = (
      <>
        {running && scan.progress ? (
          <div className="admin__progress">
            <div
              className="admin__bar"
              role="progressbar"
              aria-label="Scan progress"
              aria-valuemin={0}
              aria-valuemax={100}
              aria-valuenow={progressPercent(scan.progress)}
              aria-valuetext={progressText(scan.progress)}
            >
              <div
                className="admin__fill"
                style={{ width: `${progressPercent(scan.progress)}%` }}
              />
            </div>
            <div className="admin__progress-line">
              <span className="admin__mono">{progressText(scan.progress)}</span>
              {scan.progress.current ? (
                <span className="admin__mono admin__now">Now: {scan.progress.current}</span>
              ) : null}
            </div>
          </div>
        ) : null}
        <dl className="admin__rows">
          <dt>Folder</dt>
          <dd className="admin__mono">{scan.folder}</dd>
          <dt>Last scan</dt>
          <dd className="admin__mono">{describeLastScan(scan.lastScan)}</dd>
        </dl>
        {scan.lastScan?.error ? (
          <p className="admin__warning" role="status">
            {scan.lastScan.error}
          </p>
        ) : null}
        <ul className="admin__counts" aria-label="Library counts">
          {countTiles(scan.counts).map((tile) => (
            <li key={tile.key} className="admin__count">
              <span className="admin__count-label">{tile.label}</span>
              <span className="admin__count-value">{tile.value}</span>
            </li>
          ))}
        </ul>
        <div className="admin__actions">
          <Button variant="primary" disabled={running || starting} onClick={onScan}>
            {running ? 'Scanning…' : 'Scan now'}
          </Button>
          {notice ? (
            <span className="admin__notice" role="status">
              {notice}
            </span>
          ) : null}
        </div>
        <RatingsLine
          scan={scan}
          starting={ratingsStarting}
          notice={ratingsNotice}
          onRefresh={onRefreshRatings}
        />
        <SubtitleSearchLine scan={scan} />
      </>
    );
  }
  const running = Boolean(scan?.running);
  return (
    <section id="library-scan" className="admin__card" aria-labelledby="admin-scan-title">
      <div className="admin__head">
        <h2 id="admin-scan-title" className="admin__title">
          Library scan
        </h2>
        {scan ? (
          <span className={`admin__pill${running ? ' admin__pill--running' : ''}`}>
            <span className="admin__dot" aria-hidden="true" />
            {running ? 'Running' : 'Idle'}
          </span>
        ) : null}
      </div>
      {body}
    </section>
  );
}

function ImportReport({ report }) {
  return (
    <>
      <ul className="admin__counts" aria-label="Import report">
        {reportTiles(report).map((tile) => (
          <li key={tile.key} className="admin__count">
            <span className="admin__count-label">{tile.label}</span>
            <span className="admin__count-value">{tile.value}</span>
          </li>
        ))}
      </ul>
      {reportLists(report).map((list) => (
        <div key={list.key} className="admin__report-list">
          <h3 className="admin__subtitle">{list.title}</h3>
          <ul className="admin__candidates" aria-label={list.title}>
            {list.items.map((item, i) => (
              <li key={`${i}-${item}`}>{item}</li>
            ))}
          </ul>
          {moreText(list) ? <p className="admin__text">{moreText(list)}</p> : null}
        </div>
      ))}
    </>
  );
}

/** Shown only when Plex is configured on the server: nothing renders until the status says so. */
function PlexPanel({ plex, starting, notice, onImport, onRecheck }) {
  if (!plex || !plex.configured) return null;
  const running = plex.running;
  const last = plex.lastImport;
  return (
    <section id="plex-import" className="admin__card" aria-labelledby="admin-plex-title">
      <div className="admin__head">
        <h2 id="admin-plex-title" className="admin__title">
          Plex import
        </h2>
        <span className={`admin__pill${running ? ' admin__pill--running' : ''}`}>
          <span className="admin__dot" aria-hidden="true" />
          {plexPillLabel(plex)}
        </span>
      </div>
      {running && plex.progress ? (
        <div className="admin__progress">
          <div
            className="admin__bar"
            role="progressbar"
            aria-label="Import progress"
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={progressPercent(plex.progress)}
            aria-valuetext={plexProgressText(plex.progress)}
          >
            <div className="admin__fill" style={{ width: `${progressPercent(plex.progress)}%` }} />
          </div>
          <div className="admin__progress-line">
            <span className="admin__mono">{plexProgressText(plex.progress)}</span>
            {plex.progress.current ? (
              <span className="admin__mono admin__now">Now: {plex.progress.current}</span>
            ) : null}
          </div>
        </div>
      ) : null}
      <dl className="admin__rows">
        <dt>Last import</dt>
        <dd className="admin__mono">{describeLastImport(last)}</dd>
      </dl>
      {last?.report ? <ImportReport report={last.report} /> : null}
      <div className="admin__actions">
        <Button
          variant="secondary"
          disabled={running || starting || !plex.reachable}
          onClick={onImport}
        >
          {running ? 'Importing…' : 'Import from Plex'}
        </Button>
        {!running && !plex.reachable ? (
          <Button variant="secondary" onClick={onRecheck}>
            Check again
          </Button>
        ) : null}
        {notice ? (
          <span className="admin__notice" role="status">
            {notice}
          </span>
        ) : null}
      </div>
    </section>
  );
}

function Candidates({ candidates }) {
  return (
    <ul className="admin__candidates" aria-label="Possible matches">
      {candidates.map((c) => (
        <li key={c.tmdbId}>
          {titleWithYear(c.title, c.year)}
          {c.runtimeMinutes ? (
            <span className="admin__mono"> · {formatRuntime(c.runtimeMinutes * 60000)}</span>
          ) : null}
        </li>
      ))}
    </ul>
  );
}

function AttentionPanel({ attention, loading, error, onRetry, onFixMatch, onEditEditions }) {
  let body;
  if (loading && !attention) {
    body = (
      <div role="status" aria-label="Loading">
        <SkeletonBlock style={{ height: 120 }} />
      </div>
    );
  } else if (error && !attention) {
    body = (
      <ErrorState onRetry={onRetry}>Couldn&rsquo;t load the films needing attention.</ErrorState>
    );
  } else if (attention && attention.items.length === 0) {
    body = <p className="admin__text">Nothing needs attention.</p>;
  } else if (attention) {
    body = (
      <ul className="admin__list">
        {attention.items.map((item) => (
          <li key={`${item.kind}-${item.filmId}-${item.files.join('|')}`} className="admin__item">
            <div className="admin__item-head">
              <span className="admin__item-title">{titleWithYear(item.title, item.year)}</span>
              <span className="admin__tag">{kindLabel(item.kind)}</span>
            </div>
            <div className="admin__item-detail">{item.detail}</div>
            <div className="admin__item-files admin__mono">{item.files.join(' · ')}</div>
            {item.candidates.length > 0 ? <Candidates candidates={item.candidates} /> : null}
            {item.kind === 'needs-match' ? (
              <div className="admin__actions admin__item-actions">
                <Button onClick={() => onFixMatch(item)}>Fix match</Button>
              </div>
            ) : null}
            {item.kind === 'multiple-files' ? (
              <div className="admin__actions admin__item-actions">
                <Button onClick={(event) => onEditEditions(item, event.currentTarget)}>
                  Edit editions
                </Button>
              </div>
            ) : null}
          </li>
        ))}
      </ul>
    );
  }
  return (
    <section className="admin__card" aria-labelledby="admin-attention-title">
      <div className="admin__head">
        <h2 id="admin-attention-title" className="admin__title">
          Films needing attention
        </h2>
      </div>
      {body}
    </section>
  );
}

function GroupingReport({ report, loading, error, onRetry }) {
  let body = null;
  if (loading && !report) {
    body = (
      <div role="status" aria-label="Loading">
        <SkeletonBlock style={{ height: 60 }} />
      </div>
    );
  } else if (error && !report) {
    body = <ErrorState onRetry={onRetry}>Couldn&rsquo;t load the grouping report.</ErrorState>;
  } else if (report && report.items.length === 0) {
    body = <p className="admin__text">Nothing has been grouped or split yet.</p>;
  } else if (report) {
    body = (
      <ul className="admin__list" aria-label="Grouping changes">
        {report.items.map((change, i) => (
          <li key={`${i}-${change.happenedAt}`} className="admin__item">
            <div className="admin__item-head">
              <span className="admin__tag">{changeLabel(change.kind)}</span>
              <span className="admin__mono admin__text">{formatScanTime(change.happenedAt)}</span>
            </div>
            <div className="admin__item-detail">{change.summary}</div>
          </li>
        ))}
      </ul>
    );
  }
  return (
    <section className="admin__card" aria-labelledby="admin-grouping-title">
      <div className="admin__head">
        <h2 id="admin-grouping-title" className="admin__title">
          Grouping report
        </h2>
      </div>
      {body}
    </section>
  );
}

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

export default function Admin() {
  const {
    scan,
    error,
    loading,
    starting,
    notice,
    startScan,
    ratingsStarting,
    ratingsNotice,
    refreshRatings,
    reload,
  } = useScan();
  const plexImport = usePlexImport();
  // The list refreshes when a scan or a Plex import finishes: both settle matches.
  const attention = useAttention(
    `${scan?.lastScan?.finishedAt ?? 'never'}|${plexImport.plex?.lastImport?.finishedAt ?? 'none'}`,
  );
  const settledAt = `${scan?.lastScan?.finishedAt ?? 'never'}|${plexImport.plex?.lastImport?.finishedAt ?? 'none'}`;
  const grouping = useGroupingReport(settledAt);
  const [fixing, setFixing] = useState(null);
  const [editing, setEditing] = useState(null);
  const toast = useToast();
  const { hash } = useLocation();

  const applied = (result, stale) => {
    if (!stale) setFixing(null);
    attention.reload();
    grouping.reload();
    reload();
  };

  // A saved arrangement settles the row it was opened from, so the lists are read again.
  const edited = (result, stale) => {
    if (!stale) {
      setEditing(null);
      toast.show('Editions saved');
    }
    attention.reload();
    grouping.reload();
    reload();
  };

  useEffect(() => {
    if (hash === '#library-scan') {
      document.getElementById('library-scan')?.scrollIntoView?.();
    }
  }, [hash, loading]);

  return (
    <div className="page__stack">
      <h1 className="page-title">Admin</h1>
      <div className="admin__grid">
        <ScanPanel
          scan={scan}
          loading={loading}
          error={error}
          starting={starting}
          notice={notice}
          ratingsStarting={ratingsStarting}
          ratingsNotice={ratingsNotice}
          onScan={startScan}
          onRefreshRatings={refreshRatings}
          onRetry={reload}
        />
        <AttentionPanel
          attention={attention.data}
          loading={attention.loading}
          error={attention.error}
          onRetry={attention.reload}
          onFixMatch={setFixing}
          onEditEditions={(item, opener) =>
            setEditing({
              film: { id: item.filmId, title: item.title, year: item.year },
              opener,
            })
          }
        />
        <GroupingReport
          report={grouping.data}
          loading={grouping.loading}
          error={grouping.error}
          onRetry={grouping.reload}
        />
        {/* Read again when a scan, a subtitle search or a subtitle check finishes (new checks and evidence). */}
        <SubtitlesPanel
          version={`${settledAt}|${scan?.openSubtitlesLastSearchedAt ?? 'never'}|${scan?.subtitleChecksLastFinishedAt ?? 'never'}`}
        />
        <PlexPanel
          plex={plexImport.plex}
          starting={plexImport.starting}
          notice={plexImport.notice}
          onImport={plexImport.startImport}
          onRecheck={plexImport.reload}
        />
        <ContinueWatchingPanel />
        <TranscodingPanel />
        <PlaybackHealthPanel />
        <SessionPanel />
      </div>
      {fixing ? (
        <FixMatchPanel
          key={fixing.filmId}
          item={fixing}
          onApplied={applied}
          onCancel={() => setFixing(null)}
        />
      ) : null}
      {editing ? (
        <EditEditions
          key={editing.film.id}
          film={editing.film}
          opener={editing.opener}
          onClose={() => setEditing(null)}
          onSaved={edited}
        />
      ) : null}
      <Toast message={toast.message} />
      <p className="admin__credit">
        <TmdbMark />
        <span>This product uses the TMDB API but is not endorsed or certified by TMDB.</span>
      </p>
    </div>
  );
}
