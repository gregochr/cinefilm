import { useState } from 'react';
import { Button } from '../../components/Button.jsx';
import { SkeletonBlock } from '../../components/Skeleton.jsx';
import AdminErrorBanner from '../../components/AdminErrorBanner.jsx';
import { libraryPage } from '../../state/adminCopy.js';
import {
  GROUPING_PAGE_SIZE,
  changeLabel,
  describeGroupingCounts,
  formatScanTime,
  groupingPage,
} from '../../state/scanFormat.js';

/**
 * "What the last scan changed · 2 split, 1 joined, 1 flagged": the grouping report in a `<details>` that starts
 * closed, inside the scan card. The list shows 25 changes at a time, with a button for the next page. `scanned`
 * is false before any scan has finished (an empty report then says so, instead of "changed nothing") and null while
 * that is not yet known (an empty report then says nothing).
 */
export default function LibraryChanges({ report, loading, error, onRetry, scanned = true }) {
  const items = report?.items ?? [];
  // The page count belongs to one report's content: a refetch that returns the same lines keeps the page, and a
  // different report (another scan) starts at the first page again.
  const content = `${items.length}|${items[0]?.happenedAt}|${items[0]?.summary}`;
  const [paging, setPaging] = useState({ content, shown: GROUPING_PAGE_SIZE });
  const shown = paging.content === content ? paging.shown : GROUPING_PAGE_SIZE;
  const copy = libraryPage.changes;
  let body;
  if (loading && !report) {
    body = (
      <div role="status" aria-label="Loading">
        <SkeletonBlock style={{ height: 60 }} />
      </div>
    );
  } else if (error && !report) {
    body = <AdminErrorBanner onRetry={onRetry}>{copy.loadError}</AdminErrorBanner>;
  } else if (items.length === 0) {
    if (scanned !== null) {
      body = <p className="admin__text">{scanned ? copy.empty : libraryPage.scan.neverScanned}</p>;
    }
  } else {
    const page = groupingPage(items, shown);
    body = (
      <>
        <ul className="admin__list" aria-label={copy.listLabel}>
          {page.visible.map((change, i) => (
            <li key={`${i}-${change.happenedAt}`} className="admin__item">
              <div className="admin__item-head">
                <span className="admin__tag">{changeLabel(change.kind)}</span>
                <span className="admin__mono admin__text">{formatScanTime(change.happenedAt)}</span>
              </div>
              <div className="admin__item-detail">{change.summary}</div>
            </li>
          ))}
        </ul>
        {page.moreLabel ? (
          <Button
            className="admin__more"
            onClick={() => setPaging({ content, shown: shown + GROUPING_PAGE_SIZE })}
          >
            {page.moreLabel}
          </Button>
        ) : null}
      </>
    );
  }
  return (
    <details className="admin__changes" data-testid="library-changes">
      <summary data-testid="library-changes-summary">
        <span className="admin__changes-title">{copy.summary}</span>
        {report ? ` · ${describeGroupingCounts(items)}` : null}
      </summary>
      {body}
    </details>
  );
}
