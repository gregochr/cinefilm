import { useEffect } from 'react';
import { useLocation } from 'react-router-dom';
import { Button } from '../components/Button.jsx';
import { SkeletonBlock } from '../components/Skeleton.jsx';
import TranscodingPanel from '../components/TranscodingPanel.jsx';
import { ErrorState } from '../components/StateMessages.jsx';
import { formatRuntime } from '../player/time.js';
import { useAttention, usePlexImport, useScan } from '../state/useAdmin.js';
import {
  describeLastImport,
  moreText,
  plexPillLabel,
  plexProgressText,
  reportLists,
  reportTiles,
} from '../state/plexFormat.js';
import {
  countTiles,
  describeLastScan,
  kindLabel,
  progressPercent,
  progressText,
  titleWithYear,
} from '../state/scanFormat.js';

function ScanPanel({ scan, loading, error, starting, notice, onScan, onRetry }) {
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

function AttentionPanel({ attention, loading, error, onRetry }) {
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

export default function Admin() {
  const { scan, error, loading, starting, notice, startScan, reload } = useScan();
  const plexImport = usePlexImport();
  // The list refreshes when a scan or a Plex import finishes: both settle matches.
  const attention = useAttention(
    `${scan?.lastScan?.finishedAt ?? 'never'}|${plexImport.plex?.lastImport?.finishedAt ?? 'none'}`,
  );
  const { hash } = useLocation();

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
          onScan={startScan}
          onRetry={reload}
        />
        <AttentionPanel
          attention={attention.data}
          loading={attention.loading}
          error={attention.error}
          onRetry={attention.reload}
        />
        <PlexPanel
          plex={plexImport.plex}
          starting={plexImport.starting}
          notice={plexImport.notice}
          onImport={plexImport.startImport}
          onRecheck={plexImport.reload}
        />
        <TranscodingPanel />
      </div>
      <p className="admin__credit">
        <TmdbMark />
        <span>This product uses the TMDB API but is not endorsed or certified by TMDB.</span>
      </p>
    </div>
  );
}
