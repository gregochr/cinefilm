import { Link, Navigate, NavLink, Outlet, useLocation } from 'react-router-dom';
import EditEditions from '../components/EditEditions.jsx';
import FixMatchPanel from '../components/FixMatchPanel.jsx';
import Toast from '../components/Toast.jsx';
import { useDocumentVisible } from '../components/useDocumentVisible.js';
import { AdminContext } from '../state/adminContext.js';
import { ADMIN_SECTIONS, OLD_SCAN_HASH, SCAN_ROUTE } from '../state/adminRoutes.js';
import { useAdminShell } from '../state/useAdminShell.js';

/** Hands focus back to the element a dialog was opened from, when it is still on the page. */
function returnFocus(opener) {
  if (opener?.isConnected) opener.focus();
}

/** The rail on desktop, "Home" and a scrollable row of links on phone: one `nav`, restyled by CSS. */
function AdminNav() {
  return (
    <div className="admin__rail">
      <Link to="/" className="admin__home" aria-label="Back to Home">
        ‹ Home
      </Link>
      <p className="admin__name">Admin</p>
      <nav className="admin__nav" aria-label="Admin sections">
        {ADMIN_SECTIONS.map((section) => (
          <NavLink key={section.key} to={section.to} end={section.end} className="admin__link-nav">
            {section.label}
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
  const shell = useAdminShell(visible);
  const { fixing, editing, toast } = shell;

  const closeFixMatch = () => {
    returnFocus(fixing?.opener);
    shell.closeFixMatch();
  };
  const applied = (result, stale) => {
    if (!stale) closeFixMatch();
    shell.markChanged();
  };
  // A saved arrangement settles the row it was opened from, so the lists are read again.
  const edited = (result, stale) => {
    if (!stale) {
      shell.closeEditEditions();
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
              onApplied={applied}
              onCancel={closeFixMatch}
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
  if (hash === OLD_SCAN_HASH && pathname.replace(/\/+$/, '') === '/admin') {
    return <Navigate replace to={SCAN_ROUTE} />;
  }
  return <AdminShell />;
}
