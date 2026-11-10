import { Link, NavLink, useLocation } from 'react-router-dom';
import { SettingsIcon } from './icons.jsx';
import LogoMark from './LogoMark.jsx';
import SearchBox from './SearchBox.jsx';

export default function Header() {
  const { pathname } = useLocation();
  const showSearch = !pathname.startsWith('/library');

  return (
    <header className="header">
      <Link to="/" className="wordmark header__wordmark" aria-label="CineFilm — Home">
        <LogoMark size={30} />
        <span aria-hidden="true">CineFilm</span>
      </Link>
      <nav className="header__nav" aria-label="Primary">
        <NavLink to="/" end className="header__link">
          Home
        </NavLink>
        <NavLink to="/library" className="header__link">
          Library
        </NavLink>
      </nav>
      <div className="header__spacer" />
      {showSearch ? <SearchBox /> : null}
      <Link to="/admin" className="header__settings" aria-label="Settings">
        <SettingsIcon />
      </Link>
    </header>
  );
}
