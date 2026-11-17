import { useEffect, useRef } from 'react';
import { Link, Navigate, NavLink, Outlet, useLocation } from 'react-router-dom';
import EditEditions from '../components/EditEditions.jsx';
import FixMatchPanel from '../components/FixMatchPanel.jsx';
import SubtitlePicker from '../components/SubtitlePicker.jsx';
import Toast from '../components/Toast.jsx';
import { useDocumentVisible } from '../components/useDocumentVisible.js';
import { AdminContext, useAdminContext } from '../state/adminContext.js';
import { railCounts } from '../state/adminSummaryFormat.js';
import { ADMIN_SECTIONS, OLD_SCAN_HASH, SCAN_ROUTE, normalisePath } from '../state/adminRoutes.js';
import { useAdminShell } from '../state/useAdminShell.js';

/** The rail on desktop, "Home" and a scrollable row of links on phone: one `nav`, restyled by CSS. */
function AdminNav() {
  const { adminSummary } = useAdminContext();
  // No counts until a summary has been read; a later failed read leaves the last good ones on screen.
  const counts = railCounts(adminSummary.summary);
  return (
    <div className="admin__rail">
      <Link to="/" className="admin__home" aria-label="Back to Home">
        ‹ Home
      </Link>
      <p className="admin__name">Admin</p>
      <nav className="admin__nav" aria-label="Admin sections">
        {ADMIN_SECTIONS.map((section) => (
          <NavLink
            key={section.key}
            to={section.to}
            end={section.end}
            className="admin__link-nav"
            aria-label={counts[section.key]?.label}
          >
            {section.label}
            {counts[section.key] ? (
              <span
                className={`admin__count-pill${section.key === 'playback' ? ' admin__count-pill--live' : ''}`}
              >
                {counts[section.key].badge}
              </span>
            ) : null}
          </NavLink>
        ))}
      </nav>
      <p className="admin__note">
        Each section has its own address: /admin, /admin/subtitles, /admin/library, /admin/playback,
        /admin/settings.
      </p>
    </div>
  );
}

function AdminShell() {
  const visible = useDocumentVisible();
  const { pathname } = useLocation();
  const shell = useAdminShell(visible, pathname);
  const { fixing, editing, picking, toast } = shell;

  // The dialog hands focus back to its opener itself (useDialog) when it unmounts, if the opener is still there.
  // The match is saved whatever the dialog is doing, so the lists are read again at once. The row it opened from goes,
  // and focus must not be left on the body, but the fallback is armed only once the dialog is gone: armed during the
  // fade it would be spent on a heading behind the still-open dialog.
  const fallbackDue = useRef(false);
  const applied = (result, stale) => {
    if (stale) shell.armFocusFallback();
    else fallbackDue.current = true;
    shell.markChanged();
  };
  // Once it is gone: arm the fallback for a re-read still to come, and, when the row went during the fade, settle focus
  // now (the re-read that would have used the fallback has already happened).
  const checkFocus = useRef(false);
  const { takeFocusFallback } = shell.value;
  const closeFixMatch = () => {
    if (fallbackDue.current) {
      shell.armFocusFallback();
      checkFocus.current = true;
    }
    fallbackDue.current = false;
    shell.closeFixMatch();
  };
  useEffect(() => {
    if (fixing) return;
    // Gone by any road (Cancel, an apply, a change of section): a due fallback belongs to that dialog only.
    fallbackDue.current = false;
    if (!checkFocus.current) return;
    checkFocus.current = false;
    const active = document.activeElement;
    if (!active || active === document.body || !active.isConnected) {
      document.getElementById('admin-section-title')?.focus();
      takeFocusFallback();
    }
  }, [fixing, takeFocusFallback]);
  // A saved arrangement settles the row it was opened from, so the lists are read again. Same focus rule as Fix match
  // (mirrored here, not shared: the two read differently). EditEditions reports a save that lands during its fade-out
  // as stale too, so "still mounted" is read from the open `editing` through a ref: a due fallback is armed only once
  // the dialog is gone, and one that lands after it is gone is armed at once.
  const editingRef = useRef(editing);
  useEffect(() => {
    editingRef.current = editing;
  }, [editing]);
  const editFallbackDue = useRef(false);
  const editCheckFocus = useRef(false);
  const edited = (result, stale) => {
    if (!stale) {
      shell.closeEditEditions();
      shell.armFocusFallback();
      toast.show('Editions saved');
    } else if (editingRef.current?.film.id === result.filmId) {
      editFallbackDue.current = true;
    } else {
      shell.armFocusFallback();
    }
    shell.markChanged();
  };
  const closeEditEditions = () => {
    if (editFallbackDue.current) {
      shell.armFocusFallback();
      editCheckFocus.current = true;
    }
    editFallbackDue.current = false;
    shell.closeEditEditions();
  };
  useEffect(() => {
    if (editing) return;
    // Gone by any road (Cancel, a save, a change of section): a due fallback belongs to that dialog only.
    editFallbackDue.current = false;
    if (!editCheckFocus.current) return;
    editCheckFocus.current = false;
    const active = document.activeElement;
    if (!active || active === document.body || !active.isConnected) {
      document.getElementById('admin-section-title')?.focus();
      takeFocusFallback();
    }
  }, [editing, takeFocusFallback]);

  return (
    <AdminContext.Provider value={shell.value}>
      <div className="admin">
        <AdminNav />
        <div className="admin__main">
          <Outlet />
          {fixing ? (
            <FixMatchPanel
              key={fixing.item.filmId}
              item={fixing.item}
              opener={fixing.opener}
              onApplied={applied}
              onClose={closeFixMatch}
            />
          ) : null}
          {picking ? (
            <SubtitlePicker
              key={`${picking.editionId}:${picking.kind}`}
              film={picking.film}
              editionId={picking.editionId}
              editionName={picking.editionName}
              kind={picking.kind}
              opener={picking.opener}
              onClose={shell.closeSubtitlePicker}
              onPicked={() => shell.pickerDone(picking)}
            />
          ) : null}
          {editing ? (
            <EditEditions
              key={editing.film.id}
              film={editing.film}
              opener={editing.opener}
              onClose={closeEditEditions}
              onSaved={edited}
            />
          ) : null}
        </div>
      </div>
      <Toast message={toast.message} />
    </AdminContext.Provider>
  );
}

/**
 * The Admin frame: a rail (desktop) or tab row (phone) of the five sections, around whichever section the address
 * names. The old `/admin#library-scan` address redirects before any section mounts, so To do never fetches.
 */
export default function AdminLayout() {
  const { pathname, hash } = useLocation();
  if (hash === OLD_SCAN_HASH && normalisePath(pathname) === '/admin') {
    return <Navigate replace to={SCAN_ROUTE} />;
  }
  return <AdminShell />;
}
