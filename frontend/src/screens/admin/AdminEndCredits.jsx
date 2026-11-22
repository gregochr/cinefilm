import { useEffect, useId, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import AdminErrorBanner from '../../components/AdminErrorBanner.jsx';
import { Button } from '../../components/Button.jsx';
import { SkeletonBlock } from '../../components/Skeleton.jsx';
import { useAdminContext } from '../../state/adminContext.js';
import {
  filterOf,
  FILTERS,
  focusTargetAfter,
  hasNextPage,
  neighboursOf,
  pagingText,
  rowLines,
} from '../../state/creditsAdmin.js';
import { creditsPage, creditsToast } from '../../state/creditsAdminCopy.js';
import { useCreditsAdmin } from '../../state/useCreditsAdmin.js';
import { useCreditsWrite } from '../../state/useCreditsWrite.js';
import AdminTabList, { panelId, tabId } from './AdminTabList.jsx';
import EndCreditsPanel from './EndCreditsPanel.jsx';
import SectionTitle from './SectionTitle.jsx';
import { useFallbackFocus } from './useFallbackFocus.js';
import { useSignInRecovery } from './useSignInRecovery.js';

const PANEL_PREFIX = 'admin-credits-panel';
const lookId = (editionId) => `admin-credits-look-${editionId}`;

/** One edition: its title (year muted, the edition's name when the film has more than one), source and time, reason. */
function CreditsRow({ row, open, onToggle, write }) {
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
        <Button
          id={lookId(row.editionId)}
          aria-expanded={open}
          aria-controls={panelDomId}
          onClick={onToggle}
        >
          {creditsPage.look}
        </Button>
      </div>
      {open ? (
        <EndCreditsPanel
          row={row}
          id={panelDomId}
          busy={write.busy}
          failure={write.failure?.editionId === row.editionId ? write.failure : null}
          onSet={(markerMs) => write.submit(row, 'set', markerMs)}
          onClear={() => write.submit(row, 'clear')}
          onEdit={write.clearFailure}
        />
      ) : null}
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
  const signIns = useSignInRecovery();
  const { data, loading, error, reload, setPage } = useCreditsAdmin(filter, signIns);
  const [openId, setOpenId] = useState(null);
  const { showToast, armFocusFallback, takeFocusFallback } = useAdminContext();
  const writer = useCreditsWrite();
  // The panel that is open now, read when a write ends; and whether the owner has moved on (another panel, page or
  // tab) since the write began, which stops focus being taken from where they went.
  const openRef = useRef(null);
  openRef.current = openId;
  const moved = useRef(false);
  const pending = useRef(null);
  // Every way the owner changes what is open comes through here: a stale refusal does not follow to the next panel.
  const showPanel = (next) => {
    moved.current = true;
    writer.clearFailure();
    setOpenId(next);
  };
  // After a page press the rows and pager stay while the next page loads, so the pressed button keeps focus.
  const [paged, setPaged] = useState(false);
  // A sign-in starts a fresh read, so it shows the skeleton, not the rows from before.
  const [seenSignIns, setSeenSignIns] = useState(signIns);
  if (seenSignIns !== signIns) {
    setSeenSignIns(signIns);
    setPaged(false);
  }
  const goTo = (next) => {
    showPanel(null);
    setPaged(true);
    setPage(next);
  };
  // The last page that had rows: what stays on screen while a pressed page (or its step back) loads.
  const [shown, setShown] = useState(null);
  if (data && data.items.length > 0 && data !== shown) setShown(data);
  const view = loading ? (paged ? shown : null) : data;

  // A write is never cancelled (the server may have made it). A refusal shows in the row's panel, or in the toast when
  // the owner has left that panel. A success closes the panel, reads the list again and, when it has arrived, the
  // effect below moves focus. The fallback is armed first so a list with no row left lands on the section title.
  const submit = async (row, verb, markerMs) => {
    if (writer.busy) return;
    const rowId = row.editionId;
    const before = { data, page: view.page, neighbours: neighboursOf(view.items, rowId) };
    armFocusFallback();
    moved.current = false;
    const outcome = await writer.run(rowId, verb, markerMs);
    if (outcome.ignored) return;
    if (!outcome.ok && outcome.failure.kind !== 'gone') {
      takeFocusFallback();
      const { failure } = outcome;
      if (failure.text && openRef.current !== rowId) showToast(failure.text);
      return;
    }
    // A film that has gone (404) leaves the list like a row that was set: its words go in the toast (the panel goes
    // with the row) and focus follows the same rules.
    showToast(
      outcome.ok
        ? verb === 'set'
          ? creditsToast.set
          : creditsToast.cleared
        : outcome.failure.text,
    );
    pending.current = { rowId, ...before };
    // Its panel closes; another one the owner has opened since is left alone.
    if (openRef.current === rowId) setOpenId(null);
    setPaged(true);
    reload();
  };

  // The list has been read again after a write: focus the row's own button if the row is still there, else the next
  // row's, else the previous row's; after a step back, the new page's last row. A failed read or an owner who moved
  // on keeps focus where it is. With no row left, `useFallbackFocus` (armed in `submit`) takes it to the title.
  useEffect(() => {
    const p = pending.current;
    if (!p || loading || (data === p.data && !error)) return;
    pending.current = null;
    if (error || moved.current) {
      takeFocusFallback();
      return;
    }
    const target = focusTargetAfter(p.rowId, p.neighbours, data.items, data.page < p.page);
    if (target != null) document.getElementById(lookId(target))?.focus();
  }, [data, loading, error, takeFocusFallback]);
  const [settled, setSettled] = useState(data);
  if (!loading && data !== settled) setSettled(data);
  useFallbackFocus(settled);

  const tabs = FILTERS.map((key) => ({
    key,
    label: creditsPage.tabs[key],
    count: data ? data.counts[key] : null,
  }));
  let body;
  if (loading && !view) {
    body = (
      <div role="status" aria-label={creditsPage.loadingLabel}>
        <SkeletonBlock style={{ height: 60 }} />
      </div>
    );
  } else if (error || !view) {
    body = (
      <AdminErrorBanner
        onRetry={() => {
          setPaged(false);
          reload();
        }}
        retryLabel={creditsPage.checkAgain}
      >
        {creditsPage.loadError}
      </AdminErrorBanner>
    );
  } else if (view.items.length === 0) {
    body = <p className="admin__text">{creditsPage.empty}</p>;
  } else {
    body = (
      <>
        <ul className="admin__todo-rows" aria-label={creditsPage.listLabel}>
          {view.items.map((row) => (
            <CreditsRow
              key={row.editionId}
              row={row}
              open={openId === row.editionId}
              onToggle={() => showPanel(openId === row.editionId ? null : row.editionId)}
              write={{ ...writer, submit }}
            />
          ))}
        </ul>
        <Pager page={view.page} size={view.size} total={view.total} onPage={goTo} />
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
        panelPrefix={PANEL_PREFIX}
        onSelect={(key) => {
          showPanel(null);
          setPaged(false);
          setParams({ show: key }, { replace: true });
        }}
      />
      <section
        className="admin__card"
        role="tabpanel"
        id={panelId(filter, PANEL_PREFIX)}
        aria-labelledby={tabId(filter)}
      >
        {body}
      </section>
    </>
  );
}
