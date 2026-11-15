import { Link } from 'react-router-dom';
import { ActionWithHint } from '../../components/ActionWithHint.jsx';
import { SkeletonBlock } from '../../components/Skeleton.jsx';
import { ErrorState } from '../../components/StateMessages.jsx';
import { buttons, libraryPage } from '../../state/adminCopy.js';
import {
  countTiles,
  describeScanHeader,
  progressPercent,
  progressText,
} from '../../state/scanFormat.js';
import LibraryChanges from './LibraryChanges.jsx';

/** The bar and the line under it while a scan runs; `progress` is null until the first figures arrive. */
function ScanProgress({ progress }) {
  const figures = progress ?? { processed: 0, total: 0, current: null };
  return (
    <div className="admin__progress">
      <div
        className="admin__bar"
        role="progressbar"
        aria-label="Scan progress"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={progressPercent(figures)}
        aria-valuetext={progressText(figures)}
      >
        <div className="admin__fill" style={{ width: `${progressPercent(figures)}%` }} />
      </div>
      <div className="admin__progress-line">
        <span className="admin__mono">{progressText(figures)}</span>
        {figures.current ? (
          <span className="admin__mono admin__now">Now: {figures.current}</span>
        ) : null}
      </div>
    </div>
  );
}

/** One count tile: a link to the plain list of those files when the tile has one (`to`). */
function CountTile({ tile }) {
  const content = (
    <>
      <span className="admin__count-value">{tile.value}</span>
      <span className="admin__count-label">{tile.label}</span>
    </>
  );
  return (
    <li className="admin__count">
      {tile.to ? (
        <Link
          to={tile.to}
          className="admin__count-link"
          aria-label={libraryPage.scan.openList(tile.label, tile.value)}
        >
          {content}
        </Link>
      ) : (
        content
      )}
    </li>
  );
}

/**
 * The Library scan card (`#scan`): when the last scan ran and how long it took, the count tiles, "Scan library now"
 * (the one amber button in Admin) or, while a scan runs, its progress in the button's place, and what the last scan
 * changed. `now` (epoch ms) is the moment "{ago}" counts from. `changes` is the grouping report's resource.
 */
export default function LibraryScanCard({
  scan,
  loading,
  error,
  starting,
  notice,
  onScan,
  onRetry,
  changes,
  now = Date.now(),
}) {
  let body = null;
  if (loading && !scan) {
    body = (
      <div role="status" aria-label="Loading">
        <SkeletonBlock style={{ height: 120 }} />
      </div>
    );
  } else if (error && !scan) {
    body = <ErrorState onRetry={onRetry}>Couldn&rsquo;t load the library scan.</ErrorState>;
  } else if (scan) {
    body = (
      <>
        <dl className="admin__rows">
          <dt>Folder</dt>
          <dd className="admin__mono">{scan.folder}</dd>
        </dl>
        {scan.lastScan?.error ? (
          <p className="admin__warning" role="status">
            {scan.lastScan.error}
          </p>
        ) : null}
        <ul className="admin__counts" aria-label={libraryPage.scan.countsLabel}>
          {countTiles(scan.counts).map((tile) => (
            <CountTile key={tile.key} tile={tile} />
          ))}
        </ul>
        {scan.running ? (
          <ScanProgress progress={scan.progress} />
        ) : (
          <div className="admin__actions">
            <ActionWithHint
              layout="line"
              variant="primary"
              hint={buttons.scanLibrary.hint}
              disabled={starting}
              onClick={onScan}
            >
              {starting ? buttons.scanLibrary.busyLabel : buttons.scanLibrary.label}
            </ActionWithHint>
            {notice ? (
              <span className="admin__notice" role="status">
                {notice}
              </span>
            ) : null}
          </div>
        )}
      </>
    );
  }
  return (
    <section id="scan" className="admin__card" aria-labelledby="admin-scan-title">
      <div className="admin__head">
        <h2 id="admin-scan-title" className="admin__title">
          {libraryPage.scan.title}
        </h2>
        {scan ? <span className="admin__why">{describeScanHeader(scan.lastScan, now)}</span> : null}
      </div>
      {body}
      <LibraryChanges
        report={changes.data}
        loading={changes.loading}
        error={changes.error}
        scanned={Boolean(scan?.lastScan)}
        onRetry={changes.reload}
      />
    </section>
  );
}
