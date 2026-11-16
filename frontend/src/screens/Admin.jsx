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
  const applied = (result, stale) => {
    if (!stale) {
      shell.closeFixMatch();
      shell.armFocusFallback();
    }
    shell.markChanged();
  };
  // A saved arrangement settles the row it was opened from, so the lists are read again.
  const edited = (result, stale) => {
    if (!stale) {
      shell.closeEditEditions();
      shell.armFocusFallback();
      toast.show('Editions saved');
    }
    shell.markChanged();
  };

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
              onCancel={shell.closeFixMatch}
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
              onClose={shell.closeEditEditions}
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
