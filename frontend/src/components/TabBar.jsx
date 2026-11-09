import { Link, useLocation } from 'react-router-dom';
import { HomeIcon, LibraryIcon, SearchIcon } from './icons.jsx';

/** Phone bottom tab bar (shown by CSS at ≤ 600px): Home / Library / Search. */
export default function TabBar() {
  const { search, pathname } = useLocation();
  const inLibrary = pathname === '/library' || pathname.startsWith('/library/');
  const searching = inLibrary && new URLSearchParams(search).has('focus');
  const current = searching ? 'search' : pathname === '/' ? 'home' : inLibrary ? 'library' : '';
  const tab = (key, to, label, Icon) => (
    <Link
      to={to}
      className={`tabbar__tab${current === key ? ' tabbar__tab--current' : ''}`}
      aria-current={current === key ? 'page' : undefined}
    >
      <Icon size={22} />
      <span>{label}</span>
    </Link>
  );
  return (
    <nav className="tabbar" aria-label="Tabs">
      {tab('home', '/', 'Home', HomeIcon)}
      {tab('library', '/library', 'Library', LibraryIcon)}
      {tab('search', '/library?focus=search', 'Search', SearchIcon)}
    </nav>
  );
}
