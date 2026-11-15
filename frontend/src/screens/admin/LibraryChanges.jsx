import { useState } from 'react';
import { Button } from '../../components/Button.jsx';
import { SkeletonBlock } from '../../components/Skeleton.jsx';
import { ErrorState } from '../../components/StateMessages.jsx';
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
 * closed, inside the scan card. The list shows 25 changes at a time, with a button for the next page.
 */
export default function LibraryChanges({ report, loading, error, onRetry }) {
  // The page count belongs to one report: a new report (after another scan) starts at the first page again.
  const [paging, setPaging] = useState({ report, shown: GROUPING_PAGE_SIZE });
  const shown = paging.report === report ? paging.shown : GROUPING_PAGE_SIZE;
  const items = report?.items ?? [];
  const copy = libraryPage.changes;
  let body;
  if (loading && !report) {
    body = (
      <div role="status" aria-label="Loading">
        <SkeletonBlock style={{ height: 60 }} />
      </div>
    );
  } else if (error && !report) {
    body = <ErrorState onRetry={onRetry}>{copy.loadError}</ErrorState>;
  } else if (items.length === 0) {
    body = <p className="admin__text">{copy.empty}</p>;
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
            onClick={() => setPaging({ report, shown: shown + GROUPING_PAGE_SIZE })}
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
