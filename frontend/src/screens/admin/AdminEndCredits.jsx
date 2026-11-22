import { useId, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import AdminErrorBanner from '../../components/AdminErrorBanner.jsx';
import { Button } from '../../components/Button.jsx';
import { SkeletonBlock } from '../../components/Skeleton.jsx';
import { filterOf, FILTERS, hasNextPage, pagingText, rowLines } from '../../state/creditsAdmin.js';
import { creditsPage } from '../../state/creditsAdminCopy.js';
import { useCreditsAdmin } from '../../state/useCreditsAdmin.js';
import AdminTabList, { panelId, tabId } from './AdminTabList.jsx';
import EndCreditsPanel from './EndCreditsPanel.jsx';
import SectionTitle from './SectionTitle.jsx';
import { useSignInRecovery } from './useSignInRecovery.js';

/** One edition: its title (year muted, the edition's name when the film has more than one), source and time, reason. */
function CreditsRow({ row, open, onToggle }) {
  const panelDomId = useId();
  const { two, three } = rowLines(row);
  return (
    <li className="admin__todo-row credits-row">
      <div className="admin__todo-text">
        <p className="admin__todo-title">
          {row.title}
          {row.year ? <span className="admin__todo-year"> · {row.year}</span> : null}
          {row.editionCount > 1 ? (
            <span className="admin__todo-year"> · {row.editionName}</span>
          ) : null}
        </p>
        <p className="admin__todo-line admin__mono">{two}</p>
        {three ? <p className="admin__todo-line">{three}</p> : null}
      </div>
      <div className="admin__actions admin__todo-actions">
        <Button aria-expanded={open} aria-controls={panelDomId} onClick={onToggle}>
          {creditsPage.look}
        </Button>
      </div>
      {open ? <EndCreditsPanel row={row} id={panelDomId} /> : null}
    </li>
  );
}

/** Previous and Next with the range line between. Both stay focusable at the ends (`aria-disabled`, no-op). */
function Pager({ page, size, total, onPage }) {
  const first = page === 0;
  const last = !hasNextPage(page, size, total);
  return (
    <nav className="credits-pager" aria-label={creditsPage.pagerLabel}>
      <Button
        aria-disabled={first || undefined}
        onClick={first ? undefined : () => onPage(page - 1)}
      >
        {creditsPage.previous}
      </Button>
      <span className="admin__why">{pagingText(page, size, total)}</span>
      <Button aria-disabled={last || undefined} onClick={last ? undefined : () => onPage(page + 1)}>
        {creditsPage.next}
      </Button>
    </nav>
  );
}

/**
 * The End credits list opened from Library's card: every edition and its marker, in tabs (Needs a look, From Plex,
 * Yours, All; the open one is `?show=`), 25 a page with Previous and Next. A row's "Look and set…" opens its panel
 * inline; one is open at a time, and a new tab or page closes it.
 */
export default function AdminEndCredits() {
  const [params, setParams] = useSearchParams();
  const filter = filterOf(params.get('show'));
  const { data, loading, error, reload, page, setPage } = useCreditsAdmin(
    filter,
    useSignInRecovery(),
  );
  const [openId, setOpenId] = useState(null);
  const goTo = (next) => {
    setOpenId(null);
    setPage(next);
  };
  const tabs = FILTERS.map((key) => ({
    key,
    label: creditsPage.tabs[key],
    count: data ? data.counts[key] : null,
  }));
  let body;
  if (loading) {
    body = (
      <div role="status" aria-label={creditsPage.loadingLabel}>
        <SkeletonBlock style={{ height: 60 }} />
      </div>
    );
  } else if (error || !data) {
    body = (
      <AdminErrorBanner onRetry={reload} retryLabel={creditsPage.checkAgain}>
        {creditsPage.loadError}
      </AdminErrorBanner>
    );
  } else if (data.items.length === 0) {
    body = <p className="admin__text">{creditsPage.empty}</p>;
  } else {
    body = (
      <>
        <ul className="admin__todo-rows" aria-label={creditsPage.listLabel}>
          {data.items.map((row) => (
            <CreditsRow
              key={row.editionId}
              row={row}
              open={openId === row.editionId}
              onToggle={() => setOpenId(openId === row.editionId ? null : row.editionId)}
            />
          ))}
        </ul>
        <Pager page={page} size={data.size} total={data.total} onPage={goTo} />
      </>
    );
  }
  return (
    <>
      <Link to="/admin/library" className="admin__back">
        {creditsPage.back}
      </Link>
      <SectionTitle>{creditsPage.title}</SectionTitle>
      <AdminTabList
        label={creditsPage.tabsLabel}
        tabs={tabs}
        selected={filter}
        onSelect={(key) => {
          setOpenId(null);
          setParams({ show: key }, { replace: true });
        }}
      />
      <section
        className="admin__card"
        role="tabpanel"
        id={panelId(filter)}
        aria-labelledby={tabId(filter)}
      >
        {body}
      </section>
    </>
  );
}
