import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { Button } from '../../components/Button.jsx';
import { SkeletonBlock } from '../../components/Skeleton.jsx';
import {
  EMPTY_NONE_TEXT,
  EMPTY_PENDING_ADD_TEXT,
  RULE_TEXT,
  EMPTY_TAB_TEXT,
  emptyKind,
  emptyPendingText,
  keywordPercent,
  formatCount,
  tabCounts,
  tabRows,
} from '../../state/christmas.js';
import { usePhoneLayout } from '../../components/usePhoneLayout.js';
import { useAdminContext } from '../../state/adminContext.js';
import { useChristmas } from '../../state/useChristmas.js';
import ChristmasAdd from './ChristmasAdd.jsx';
import ChristmasList from './ChristmasList.jsx';
import ChristmasStatusCard from './ChristmasStatusCard.jsx';
import ChristmasTabs, { panelDomId, tabDomId } from './ChristmasTabs.jsx';
import ChristmasToast from './ChristmasToast.jsx';
import SectionTitle from './SectionTitle.jsx';

function ChristmasSkeleton() {
  return (
    <div className="xmas-card xmas-skeleton" role="status" aria-label="Loading">
      <div className="xmas-skeleton__head">
        <SkeletonBlock style={{ height: 20, width: '60%' }} />
        <SkeletonBlock style={{ height: 14, width: '40%' }} />
      </div>
      {Array.from({ length: 5 }, (_, i) => (
        <div className="xmas-skeleton__row" key={i}>
          <SkeletonBlock style={{ width: 40, height: 60 }} />
          <SkeletonBlock style={{ height: 16, width: '30%' }} />
          <SkeletonBlock style={{ height: 44, width: 104, borderRadius: 999 }} />
        </div>
      ))}
    </div>
  );
}

/** `panelRef` makes the panel the place focus falls back to when the list empties under a focused button. */
function EmptyPanel({ kind, keywords, panelRef }) {
  if (kind === 'none') {
    return (
      <section ref={panelRef} tabIndex={-1} className="xmas-empty" aria-label="No Christmas films">
        <h2 className="xmas-empty__title">No Christmas films yet</h2>
        <p>{EMPTY_NONE_TEXT}</p>
      </section>
    );
  }
  return (
    <section ref={panelRef} tabIndex={-1} className="xmas-empty" aria-label="No Christmas films">
      <h2 className="xmas-empty__title">No Christmas films yet</h2>
      {/* Polite: the 30-second polls change these numbers as keywords arrive. */}
      <p role="status">{emptyPendingText(keywords)}</p>
      <div className="xmas-empty__progress">
        <span className="xmas-progress" aria-hidden="true">
          <span className="xmas-progress__fill" style={{ width: `${keywordPercent(keywords)}%` }} />
        </span>
        <span className="xmas-empty__count">
          {formatCount(keywords.fetched)} / {formatCount(keywords.total)}
        </span>
      </div>
      <p className="xmas-empty__add">{EMPTY_PENDING_ADD_TEXT}</p>
    </section>
  );
}

/**
 * Christmas films: what Home's Christmas week will show, with a way to leave a film out, put it back, remove one the
 * owner added or add any film from the library (the search beside the tabs). The status card holds the Home-row preview
 * (on a phone a sheet, its state here beside the search sheet's, so the toast follows whichever is up).
 */
export default function AdminChristmas() {
  const state = useChristmas();
  const { openFixMatch, changed } = useAdminContext();
  const { data, version, loading, error, busy, toast, focusRequest, refresh, pinToast } = state;
  const [tab, setTab] = useState('inWeek');
  const phone = usePhoneLayout();
  const [sheetOpen, setSheetOpen] = useState(false);
  const [panelOpen, setPanelOpen] = useState(false);
  // Decided here, in the one render, where the toast goes: inside the sheet while it is up, else in the page.
  const sheetShown = sheetOpen && phone;
  const [previewOpen, setPreviewOpen] = useState(false);
  // The preview sheet lives in the status card, which the empty screen replaces: no card, no sheet.
  const previewShown = previewOpen && phone && Boolean(data) && !emptyKind(data);
  const overlayShown = sheetShown || previewShown;
  const buttons = useRef(new Map());
  const panelButtons = useRef(new Map());
  const panel = useRef(null);
  const undoButton = useRef(null);

  const registerButton = (filmId) => (node) => {
    if (node) buttons.current.set(filmId, node);
    else buttons.current.delete(filmId);
  };

  const registerPanelButton = (filmId) => (node) => {
    if (node) panelButtons.current.set(filmId, node);
    else panelButtons.current.delete(filmId);
  };

  // The toast stays up while the search panel or sheet is open: focus stays on the result, out of reach of its Undo.
  useEffect(() => {
    pinToast(panelOpen || overlayShown);
  }, [panelOpen, overlayShown, pinToast]);

  // A saved match changes what the lists hold (the film now has a TMDB id), so they are read again, quietly.
  const changedAtStart = useRef(changed);
  useEffect(() => {
    if (changed !== changedAtStart.current) refresh();
  }, [changed, refresh]);

  // After a change lands: for one made in the search panel (or its Undo) the result's button keeps focus by itself, so
  // focus is set only if it was lost (an Undo press takes the toast's button away): then back onto that button. Else
  // onto the toast's Undo button (the row's button is gone, and the toast sits after the whole list, so a keyboard user
  // could not reach Undo within its 8 seconds; the toast waits while it has focus), back onto the row's button (after
  // an Undo), or, when focus was lost with a row or the toast, onto the list so the keyboard is not left on the page
  // body.
  useEffect(() => {
    if (!focusRequest) return;
    const active = document.activeElement;
    const lost = !active || active === document.body || !active.isConnected;
    const resultButton = panelButtons.current.get(focusRequest.filmId);
    // With the phone's sheet up, the last place focus may fall back to is inside it, never the page behind it.
    const sheetField = document.querySelector('.xmas-add-sheet .xmas-add__input');
    if (focusRequest.panel) {
      // The result's button; with the panel shut, the film's row in the list; else the list.
      if (lost)
        (
          resultButton ??
          sheetField ??
          buttons.current.get(focusRequest.filmId) ??
          panel.current
        )?.focus();
      return;
    }
    if (focusRequest.undo && undoButton.current) {
      undoButton.current.focus();
      return;
    }
    // The preview sheet is modal too: the row's button behind it is out of reach, so focus stays in the sheet.
    const previewClose = document.querySelector('.xmas-preview-sheet__bar button');
    const button =
      focusRequest.filmId === null || previewClose
        ? null
        : (buttons.current.get(focusRequest.filmId) ?? resultButton);
    if (button) {
      button.focus();
      return;
    }
    if (lost) (sheetField ?? previewClose ?? panel.current)?.focus();
  }, [focusRequest]);

  // The preview sheet goes when the window is widened or the lists empty. Its open state goes with it, so it does not
  // come back unasked when the window narrows again, and focus (it was on the sheet) moves to a live element.
  useEffect(() => {
    if (!previewOpen || previewShown) return;
    setPreviewOpen(false);
    const active = document.activeElement;
    if (!active || active === document.body || !active.isConnected) {
      (document.querySelector('.xmas-preview__toggle') ?? panel.current)?.focus();
    }
  }, [previewOpen, previewShown]);

  // While the phone's search sheet is up it shows the toast itself, inside its dialog.
  const toastEl = (
    <ChristmasToast
      toast={toast}
      undoRef={undoButton}
      onUndo={state.undo}
      onHold={state.holdToast}
      onRelease={state.releaseToast}
    />
  );

  let body;
  if (data) {
    const empty = emptyKind(data);
    const rows = empty ? [] : tabRows(data, tab);
    // The controls row is in the same place with or without lists, so adding the first film from the empty screen
    // does not rebuild the search field (and close its panel) under the owner.
    body = (
      <>
        {empty ? (
          <EmptyPanel kind={empty} keywords={data.keywords} panelRef={panel} />
        ) : (
          <ChristmasStatusCard
            data={data}
            phone={phone}
            previewSheetOpen={previewShown}
            onPreviewSheetOpenChange={setPreviewOpen}
            toast={toastEl}
          />
        )}
        <div className="xmas-controls">
          {empty ? null : (
            <ChristmasTabs counts={tabCounts(data)} selected={tab} onSelect={setTab} />
          )}
          <ChristmasAdd
            data={data}
            version={version}
            issued={state.issued}
            busy={busy}
            onAct={state.changeResult}
            onFix={openFixMatch}
            registerButton={registerPanelButton}
            refreshKey={changed}
            toast={toastEl}
            phone={phone}
            sheetOpen={sheetOpen}
            onSheetOpenChange={setSheetOpen}
            onPanelChange={setPanelOpen}
          />
        </div>
        {empty ? null : (
          <div
            ref={panel}
            id={panelDomId(tab)}
            role="tabpanel"
            aria-labelledby={tabDomId(tab)}
            tabIndex={-1}
            className="xmas-panel"
          >
            {rows.length > 0 ? (
              <ChristmasList
                rows={rows}
                tab={tab}
                max={data.limits.max}
                short={phone}
                busy={busy}
                onAct={state.change}
                registerButton={registerButton}
              />
            ) : (
              <p className="xmas-panel__empty">{EMPTY_TAB_TEXT[tab]}</p>
            )}
          </div>
        )}
      </>
    );
  } else if (error) {
    body = (
      <div className="xmas-error">
        <div className="xmas-error__banner" role="alert">
          <strong>Couldn&rsquo;t load Christmas films.</strong> CineFilm didn&rsquo;t answer. Your
          choices are safe; nothing was changed.
        </div>
        <Button
          aria-busy={loading || undefined}
          aria-disabled={loading || undefined}
          onClick={loading ? undefined : state.reload}
        >
          Try again
        </Button>
      </div>
    );
  } else if (loading) {
    body = <ChristmasSkeleton />;
  }

  return (
    <>
      <header className="xmas-head">
        {phone ? (
          <Link to="/admin" className="xmas-head__back">
            ‹ Admin
          </Link>
        ) : null}
        <p className="xmas-head__eyebrow">Admin</p>
        <SectionTitle>Christmas films</SectionTitle>
        <p className="xmas-head__rule">{RULE_TEXT}</p>
      </header>
      {body}
      {overlayShown ? null : toastEl}
    </>
  );
}
