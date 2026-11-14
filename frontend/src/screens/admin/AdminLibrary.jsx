import { useEffect } from 'react';
import { useLocation } from 'react-router-dom';
import { Button } from '../../components/Button.jsx';
import { SkeletonBlock } from '../../components/Skeleton.jsx';
import PlexCreditsImport from '../../components/PlexCreditsImport.jsx';
import { ErrorState } from '../../components/StateMessages.jsx';
import SectionTitle from './SectionTitle.jsx';
import { useAdminContext } from '../../state/adminContext.js';
import { useGroupingReport } from '../../state/useAdmin.js';
import {
  describeLastImport,
  moreText,
  plexPillLabel,
  plexProgressText,
  reportLists,
  reportTiles,
} from '../../state/plexFormat.js';
import { describeRatingsRefresh } from '../../state/ratings.js';
import {
  changeLabel,
  countTiles,
  describeLastScan,
  describeSubtitleSearch,
  formatScanTime,
  progressPercent,
  progressText,
} from '../../state/scanFormat.js';

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
    <section id="scan" className="admin__card" aria-labelledby="admin-scan-title">
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
      <PlexCreditsImport reachable={plex.reachable} />
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

/** Library: the scan, the grouping report and the Plex import. Opens on `#scan` or `#plex-import` when asked. */
export default function AdminLibrary() {
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
    plexImport,
    settledAt,
    changed,
  } = useAdminContext();
  const grouping = useGroupingReport(`${settledAt}|${changed}`);
  const { hash } = useLocation();

  // Scrolls to a card named by the address once the card is there (a background refresh of the status does not
  // move the page): the scan card for `#scan`, the Plex card for `#plex-import`.
  const scanShown = scan != null;
  const plexShown = Boolean(plexImport.plex?.configured);
  useEffect(() => {
    if (hash === '#scan' && scanShown) document.getElementById('scan')?.scrollIntoView?.();
  }, [hash, scanShown]);
  useEffect(() => {
    if (hash === '#plex-import' && plexShown) {
      document.getElementById('plex-import')?.scrollIntoView?.();
    }
  }, [hash, plexShown]);

  return (
    <>
      <SectionTitle>Library</SectionTitle>
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
        <GroupingReport
          report={grouping.data}
          loading={grouping.loading}
          error={grouping.error}
          onRetry={grouping.reload}
        />
        <PlexPanel
          plex={plexImport.plex}
          starting={plexImport.starting}
          notice={plexImport.notice}
          onImport={plexImport.startImport}
          onRecheck={plexImport.reload}
        />
      </div>
    </>
  );
}
