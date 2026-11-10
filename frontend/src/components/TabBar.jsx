import { Link, useLocation } from 'react-router-dom';
import { isSheetOpen, SHEET_STATE, sheetTo } from '../state/searchSheet.js';
import { HomeIcon, LibraryIcon, SearchIcon } from './icons.jsx';
import { raiseKeyboard } from './searchKeyboard.js';

/** Phone bottom tab bar (shown by CSS at ≤ 600px): Home / Library / Search. Search opens the full-screen sheet. */
export default function TabBar() {
  const location = useLocation();
  const { search, pathname } = location;
  const inLibrary = pathname === '/library' || pathname.startsWith('/library/');
  let current = '';
  if (isSheetOpen(search)) current = 'search';
  else if (pathname === '/') current = 'home';
  else if (inLibrary) current = 'library';
  const tab = (key, to, label, Icon, extra) => (
    <Link
      to={to}
      className={`tabbar__tab${current === key ? ' tabbar__tab--current' : ''}`}
      aria-current={current === key ? 'page' : undefined}
      {...extra}
    >
      <Icon size={22} />
      <span>{label}</span>
    </Link>
  );
  return (
    <nav className="tabbar" aria-label="Tabs">
      {tab('home', '/', 'Home', HomeIcon)}
      {tab('library', '/library', 'Library', LibraryIcon)}
      {tab('search', sheetTo(location), 'Search', SearchIcon, {
        state: SHEET_STATE,
        'data-search-tab': true,
        onClick: raiseKeyboard,
      })}
    </nav>
  );
}
